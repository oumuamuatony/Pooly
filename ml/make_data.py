"""가상 도시 도로망 + 가상 픽업 기록 생성.

실제 지도/공공데이터 대신 쓰는 시뮬레이션이다. 실제 데이터로 바꿀 때는
build_city()만 OSM/표준노드링크 + 공공데이터 결합 코드로 교체하면 된다.

구조
- 교차로 격자(GRID x GRID). 5줄마다 큰길(4차로), 나머지는 2차로, 일부는 1차로 골목.
- GNN의 '점' = 도로 구간(교차로-교차로). 같은 교차로를 공유하는 구간끼리 이웃.
- 구간 특징: 차로 수, 큰길 여부, 막다른 길 여부, 근처 정류장/횡단보도/상가 수,
  주정차 금지 여부, 역까지 거리.
- 숨은 '진짜 픽업 품질'은 자기 특징 + 이웃 구간 특징(이웃에 큰길이 있어 접근 쉬움,
  이웃 상가 많음 등)으로 정해진다. 모델은 이 값을 직접 보지 못하고,
  가상 승차 기록(대기 시간·정차 시간·재정차)을 통해서만 간접적으로 본다.
"""
import json
import numpy as np
import networkx as nx

GRID = 22


def build_city(seed=0):
    rng = np.random.default_rng(seed)
    G = nx.grid_2d_graph(GRID, GRID)
    # 일부 연결 제거 → 막다른 길·불규칙성
    edges = list(G.edges())
    rng.shuffle(edges)
    for e in edges[: int(len(edges) * 0.12)]:
        G.remove_edge(*e)
        if not nx.is_connected(G):
            G.add_edge(*e)

    def lanes_of(u, v):
        (x1, y1), (x2, y2) = u, v
        if y1 == y2 and y1 % 5 == 0:
            return 4  # 가로 큰길
        if x1 == x2 and x1 % 5 == 0:
            return 4  # 세로 큰길
        return 1 if rng.random() < 0.3 else 2

    stations = [(5, 5), (15, 10), (10, 17)]
    segs = []
    for i, (u, v) in enumerate(G.edges()):
        lanes = lanes_of(u, v)
        mx, my = (u[0] + v[0]) / 2, (u[1] + v[1]) / 2
        d_station = min(np.hypot(mx - sx, my - sy) for sx, sy in stations)
        near = np.exp(-d_station / 4)
        segs.append(dict(
            id=i, u=u, v=v, x=mx, y=my, lanes=lanes,
            main=int(lanes == 4),
            bus=int(rng.poisson(0.9 if lanes == 4 else 0.08)),
            crosswalk=int(rng.poisson(0.8 if lanes == 4 else 0.2)),
            stores=int(rng.poisson(1 + 9 * near)),
            dist_station=float(d_station),
        ))
    deg = dict(G.degree())
    for s in segs:
        s["dead_end"] = int(deg[s["u"]] == 1 or deg[s["v"]] == 1)
        # 주정차 금지: 큰길 일부 + 정류장 있는 구간 + 무작위 일부
        s["no_stop"] = int((s["main"] and rng.random() < 0.6) or s["bus"] > 1 or rng.random() < 0.04)
    # 이웃 관계 (같은 교차로 공유)
    by_node = {}
    for s in segs:
        by_node.setdefault(s["u"], []).append(s["id"])
        by_node.setdefault(s["v"], []).append(s["id"])
    nbrs = {s["id"]: sorted({j for n in (s["u"], s["v"]) for j in by_node[n] if j != s["id"]}) for s in segs}
    return segs, nbrs


def true_quality(segs, nbrs, seed=0):
    """숨은 정답. 자기 특징 + 이웃 특징의 비선형 조합."""
    rng = np.random.default_rng(seed + 1)
    q = []
    for s in segs:
        nb = [segs[j] for j in nbrs[s["id"]]]
        nb_main = np.mean([n["main"] for n in nb]) if nb else 0
        nb_stores = np.mean([n["stores"] for n in nb]) if nb else 0
        z = 0.0
        z += {1: -0.2, 2: 0.6, 4: -0.4}[s["lanes"]]          # 2차로가 정차하기 가장 쉬움
        z += 0.12 * min(s["stores"], 8)                       # 랜드마크·보행자 접근
        z += 0.8 * nb_main * (1 - s["main"])                  # 큰길 바로 옆 골목 = 접근 쉽고 정차 여유
        z += 0.06 * min(nb_stores, 8)
        z -= 1.2 * s["dead_end"]                              # 막다른 길: 다시 돌아 나와야 함
        z -= 0.5 * s["bus"]                                   # 정류장 앞은 혼잡
        z += 0.3 * min(s["crosswalk"], 2) * (1 - s["main"])   # 횡단보도 근처 골목은 건너오기 쉬움
        z += rng.normal(0, 0.25)
        q.append(z)
    q = np.array(q)
    return 1 / (1 + np.exp(-(q - np.median(q))))  # 0~1


def simulate_pickups(segs, q, n_events=2500, seed=0):
    """가상 승차 기록. 수요(역 근처·상가)가 많은 곳에서 더 자주 발생.
    금지 구간에서는 기록이 거의 생기지 않음(실제로도 서지 않으므로)."""
    rng = np.random.default_rng(seed + 2)
    demand = np.array([np.exp(-s["dist_station"] / 5) * (1 + s["stores"]) * (0.05 if s["no_stop"] else 1) for s in segs])
    p = demand / demand.sum()
    events = []
    for sid in rng.choice(len(segs), size=n_events, p=p):
        qq = q[sid]
        wait = max(3, rng.normal(120 - 100 * qq, 20))            # 정차~착석(초)
        stop = wait + max(5, rng.normal(25, 8))                    # 총 정차 시간
        restop = int(rng.random() < 0.6 * (1 - qq))                 # 주변을 다시 돌았는지
        hour = int(rng.choice([8, 12, 18, 21]))
        events.append(dict(seg=int(sid), wait=float(wait), stop=float(stop), restop=restop, hour=hour))
    return events


def label_from_events(segs, events, min_events=3):
    """기록 → 구간별 관측 품질 점수. 대기·정차가 짧고 재정차가 없을수록 높음."""
    agg = {}
    for e in events:
        a = agg.setdefault(e["seg"], [])
        score = 1 - np.clip(e["wait"] / 130, 0, 1) * 0.6 - np.clip(e["stop"] / 180, 0, 1) * 0.2 - e["restop"] * 0.2
        a.append(score)
    y = np.full(len(segs), np.nan)
    cnt = np.zeros(len(segs), dtype=int)
    for sid, v in agg.items():
        cnt[sid] = len(v)
        if len(v) >= min_events:
            y[sid] = float(np.mean(v))
    return y, cnt


if __name__ == "__main__":
    segs, nbrs = build_city()
    q = true_quality(segs, nbrs)
    ev = simulate_pickups(segs, q)
    y, cnt = label_from_events(segs, ev)
    print("구간 수", len(segs), "기록 수", len(ev), "점수 있는 구간", int(np.sum(~np.isnan(y))),
          f"({np.mean(~np.isnan(y)) * 100:.0f}%)")
    print("관측 점수 vs 숨은 정답 상관", np.corrcoef(y[~np.isnan(y)], q[~np.isnan(y)])[0, 1].round(3))
    json.dump(dict(segs=[{k: (list(v) if isinstance(v, tuple) else v) for k, v in s.items()} for s in segs],
                   nbrs={str(k): v for k, v in nbrs.items()}, q=q.tolist(), y=[None if np.isnan(v) else v for v in y],
                   events=ev), open("data.json", "w"), ensure_ascii=False)
