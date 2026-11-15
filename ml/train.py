"""픽업 품질 예측 모델 비교 (가상 데이터).

평가 방식: 지도의 한 구역(오른쪽 아래 1/4)을 통째로 '기록 없는 지역'으로 숨기고,
나머지 지역의 기록만으로 학습한 뒤 숨긴 지역의 품질을 얼마나 맞히는지 본다.

비교 모델
 A. 단순 평균        : 학습 지역 점수의 평균 하나로 모든 구간 예측
 B. 일반 머신러닝    : 구간 자기 특징만 사용 (Gradient Boosting)
 C. 머신러닝+이웃평균: 자기 특징 + 이웃 구간 특징 평균(1칸, 2칸)을 손으로 추가
 D. GNN (GraphSAGE)  : 이웃 정보를 섞는 방법 자체를 학습
"""
import json, sys, time
import numpy as np
import jax, jax.numpy as jnp
from scipy.stats import spearmanr
from sklearn.ensemble import HistGradientBoostingRegressor

import make_data as md


def features(segs):
    X = []
    for s in segs:
        X.append([s["lanes"] == 1, s["lanes"] == 2, s["lanes"] == 4, s["main"], s["dead_end"],
                  s["bus"], s["crosswalk"], s["stores"], s["dist_station"], s["no_stop"]])
    X = np.array(X, dtype=float)
    return (X - X.mean(0)) / (X.std(0) + 1e-9)


def adj_mean(nbrs, n):
    rows, cols, vals = [], [], []
    for i in range(n):
        nb = nbrs[i]
        for j in nb:
            rows.append(i); cols.append(j); vals.append(1 / len(nb))
    return rows, cols, vals


def sage_init(key, d, h=64):
    """GraphSAGE(mean) 2층: h' = ReLU(W_self·h + W_nbr·mean(이웃 h))"""
    ks = jax.random.split(key, 5)
    g = lambda k, a, b: jax.random.normal(k, (a, b)) * jnp.sqrt(2.0 / a)
    return dict(s1=g(ks[0], d, h), n1=g(ks[1], d, h), b1=jnp.zeros(h),
                s2=g(ks[2], h, h), n2=g(ks[3], h, h), b2=jnp.zeros(h),
                o=g(ks[4], h, 1) * 0.1, bo=jnp.zeros(1))


def sage_apply(P, x, A, key=None, drop=0.2):
    h = jax.nn.relu(x @ P["s1"] + (A @ x) @ P["n1"] + P["b1"])
    if key is not None:
        h = h * jax.random.bernoulli(key, 1 - drop, h.shape) / (1 - drop)
    h = jax.nn.relu(h @ P["s2"] + (A @ h) @ P["n2"] + P["b2"])
    return jax.nn.sigmoid(h @ P["o"] + P["bo"]).squeeze(-1)


def run(seed, verbose=False):
    segs, nbrs = md.build_city(seed)
    q = md.true_quality(segs, nbrs, seed)
    ev = md.simulate_pickups(segs, q, n_events=2500, seed=seed)
    y, cnt = md.label_from_events(segs, ev)
    n = len(segs)
    X = features(segs)
    labeled = ~np.isnan(y)
    test_region = np.array([s["x"] >= md.GRID / 2 and s["y"] < md.GRID / 2 for s in segs])
    train_pool = np.where(labeled & ~test_region)[0]
    rng = np.random.default_rng(seed)
    rng.shuffle(train_pool)
    n_val = len(train_pool) // 5
    val_idx, tr_idx = train_pool[:n_val], train_pool[n_val:]
    test_idx = np.where(test_region & (np.array([s["no_stop"] for s in segs]) == 0))[0]

    r, c, v = adj_mean(nbrs, n)
    A_np = np.zeros((n, n)); A_np[r, c] = v
    preds = {}

    preds["A. 단순 평균"] = np.full(n, y[tr_idx].mean())

    gb = HistGradientBoostingRegressor(max_iter=300, learning_rate=0.05, random_state=seed)
    gb.fit(X[tr_idx], y[tr_idx]); preds["B. 머신러닝(자기 특징만)"] = gb.predict(X)

    X1 = A_np @ X; X2 = A_np @ X1
    Xn = np.hstack([X, X1, X2])
    gb2 = HistGradientBoostingRegressor(max_iter=300, learning_rate=0.05, random_state=seed)
    gb2.fit(Xn[tr_idx], y[tr_idx]); preds["C. 머신러닝+이웃 평균"] = gb2.predict(Xn)

    A = jnp.array(A_np, dtype=jnp.float32)
    xt = jnp.array(X, dtype=jnp.float32)
    yt = jnp.array(np.nan_to_num(y), dtype=jnp.float32)
    tr_t, va_t = jnp.array(tr_idx), jnp.array(val_idx)
    key = jax.random.PRNGKey(seed)
    P = sage_init(key, X.shape[1])
    wd, lr, b1, b2, eps = 1e-4, 0.01, 0.9, 0.999, 1e-8
    m = jax.tree_util.tree_map(jnp.zeros_like, P); v2 = jax.tree_util.tree_map(jnp.zeros_like, P)

    def loss_fn(P, k):
        p = sage_apply(P, xt, A, k)
        return jnp.mean((p[tr_t] - yt[tr_t]) ** 2) + wd * sum(jnp.sum(w ** 2) for w in jax.tree_util.tree_leaves(P))

    @jax.jit
    def step(P, m, v2, t, k):
        l, g = jax.value_and_grad(loss_fn)(P, k)
        m = jax.tree_util.tree_map(lambda a, b: b1 * a + (1 - b1) * b, m, g)
        v2 = jax.tree_util.tree_map(lambda a, b: b2 * a + (1 - b2) * b * b, v2, g)
        P = jax.tree_util.tree_map(lambda p, a, b: p - lr * (a / (1 - b1 ** t)) / (jnp.sqrt(b / (1 - b2 ** t)) + eps), P, m, v2)
        return P, m, v2, l

    @jax.jit
    def val_loss(P):
        p = sage_apply(P, xt, A)
        return jnp.mean((p[va_t] - yt[va_t]) ** 2), jnp.mean((p[tr_t] - yt[tr_t]) ** 2)

    best, best_P, curve, patience = 1e9, P, [], 0
    for ep in range(1, 601):
        key, k = jax.random.split(key)
        P, m, v2, _ = step(P, m, v2, ep, k)
        vl, tl = val_loss(P); vl, tl = float(vl), float(tl)
        curve.append((ep, tl, vl))
        if vl < best - 1e-5:
            best, best_P, patience = vl, P, 0
        else:
            patience += 1
            if patience > 80:
                break
    preds["D. GNN (GraphSAGE)"] = np.array(sage_apply(best_P, xt, A))

    # 평가 1: 숨긴 지역에서 숨은 정답(q)과의 오차·순위 상관
    res = {}
    for k, p in preds.items():
        mae = float(np.mean(np.abs(p[test_idx] - q[test_idx])))
        rho = float(spearmanr(p[test_idx], q[test_idx]).correlation) if np.std(p[test_idx]) > 0 else 0.0
        res[k] = dict(mae=mae, rho=rho)

    # 평가 2: 추천 품질 — 숨긴 지역 안 무작위 탑승자 위치 200곳에서
    # 반경 3칸 이내, 금지 아닌 구간 중 모델 점수 상위 3곳을 고르고 실제 품질 평균을 본다.
    pos = rng.uniform([md.GRID / 2, 0], [md.GRID, md.GRID / 2], size=(200, 2))
    xy = np.array([[s["x"], s["y"]] for s in segs])
    for k, p in preds.items():
        got, best_possible, rand = [], [], []
        for px, py in pos:
            d = np.hypot(xy[test_idx, 0] - px, xy[test_idx, 1] - py)
            cand = test_idx[d <= 3]
            if len(cand) < 3:
                continue
            top = cand[np.argsort(-p[cand])[:3]]
            got.append(q[top].mean())
            best_possible.append(np.sort(q[cand])[-3:].mean())
            rand.append(q[cand].mean())
        res[k]["rec"] = float(np.mean(got))
        res[k]["rec_best"] = float(np.mean(best_possible))
        res[k]["rec_rand"] = float(np.mean(rand))
    info = dict(n_seg=n, n_events=len(ev), n_labeled=int(labeled.sum()), n_train=len(tr_idx),
                n_val=len(val_idx), n_test=len(test_idx), test_labeled=int((labeled & test_region).sum()))
    out = dict(res=res, info=info, curve=curve)
    if verbose:
        out["map"] = dict(xy=xy.tolist(), q=q.tolist(), y=[None if np.isnan(t) else float(t) for t in y],
                          test=test_region.tolist(), no_stop=[s["no_stop"] for s in segs],
                          gnn=preds["D. GNN (GraphSAGE)"].tolist(), gbm=preds["B. 머신러닝(자기 특징만)"].tolist(),
                          seg_uv=[[list(s["u"]), list(s["v"])] for s in segs])
    return out


if __name__ == "__main__":
    seeds = [int(s) for s in sys.argv[1:]] or [0, 1, 2, 3, 4]
    allres = []
    for sd in seeds:
        t = time.time()
        o = run(sd, verbose=(sd == seeds[0]))
        allres.append(o)
        print(f"seed {sd} ({time.time() - t:.1f}s) {o['info']}")
        for k, v in o["res"].items():
            print(f"   {k:22s} MAE {v['mae']:.3f}  순위상관 {v['rho']:.3f}  추천3곳 품질 {v['rec']:.3f} (최선 {v['rec_best']:.3f}, 무작위 {v['rec_rand']:.3f})")
    json.dump(allres, open("results.json", "w"), ensure_ascii=False)
