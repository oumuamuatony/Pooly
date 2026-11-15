import json
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib import font_manager as fm
from matplotlib.collections import LineCollection

for f in fm.findSystemFonts():
    if "NotoSansCJK-Regular" in f or "NotoSansCJK-Medium" in f:
        fm.fontManager.addfont(f)
plt.rcParams["font.family"] = "Noto Sans CJK JP"
plt.rcParams["axes.unicode_minus"] = False

R = json.load(open("results.json"))
r0 = R[0]
GRAY, TEAL, CORAL, PURPLE = "#888780", "#1D9E75", "#D85A30", "#7F77DD"

fig = plt.figure(figsize=(13, 9.2), dpi=130)
gs = fig.add_gridspec(2, 3, height_ratios=[1, 1.15], hspace=0.38, wspace=0.28)

# 1) 학습 곡선
ax = fig.add_subplot(gs[0, 0])
c = np.array(r0["curve"])
ax.plot(c[:, 0], c[:, 1], color=PURPLE, lw=1.6, label="학습 지역 오차")
ax.plot(c[:, 0], c[:, 2], color=CORAL, lw=1.6, label="검증용 구간 오차")
ax.set_title("① GNN 학습 곡선 — 오차가 줄어든다", fontsize=12, loc="left")
ax.set_xlabel("학습 반복 횟수"); ax.set_ylabel("오차(MSE)")
ax.legend(frameon=False, fontsize=9); ax.spines[["top", "right"]].set_visible(False)

# 2) 순위 상관 비교 (5개 도시)
names = list(r0["res"].keys())[1:]
short = ["머신러닝\n(자기 특징만)", "머신러닝\n+이웃 평균", "GNN"]
cols = [GRAY, GRAY, TEAL]
ax = fig.add_subplot(gs[0, 1])
vals = np.array([[r["res"][k]["rho"] for r in R] for k in names])
ax.bar(range(3), vals.mean(1), yerr=vals.std(1), color=cols, capsize=4, width=0.6)
for i, v in enumerate(vals.mean(1)):
    ax.text(i, v + 0.06, f"{v:.2f}", ha="center", fontsize=10)
ax.set_xticks(range(3), short, fontsize=9); ax.set_ylim(0, 1.05)
ax.set_title("② 기록 없는 지역에서 '좋은 순서' 맞히기", fontsize=12, loc="left")
ax.set_ylabel("순위 상관 (1에 가까울수록 정확)"); ax.spines[["top", "right"]].set_visible(False)

# 3) 오차 비교
ax = fig.add_subplot(gs[0, 2])
names_all = list(r0["res"].keys())
vals = np.array([[r["res"][k]["mae"] for r in R] for k in names_all])
ax.bar(range(4), vals.mean(1), yerr=vals.std(1), color=[GRAY, GRAY, GRAY, TEAL], capsize=4, width=0.6)
for i, v in enumerate(vals.mean(1)):
    ax.text(i, v + 0.008, f"{v:.3f}", ha="center", fontsize=10)
ax.set_xticks(range(4), ["단순\n평균"] + short, fontsize=9); ax.set_ylim(0, 0.15)
ax.set_title("③ 점수 오차 (낮을수록 정확)", fontsize=12, loc="left")
ax.set_ylabel("평균 절대 오차 (0~1 점수 기준)"); ax.spines[["top", "right"]].set_visible(False)

# 4) 지도: 숨은 정답 / 학습에 쓴 기록 / GNN 예측
m = r0["map"]
uv = np.array(m["seg_uv"], dtype=float)
q, gnn = np.array(m["q"]), np.array(m["gnn"])
y = np.array([np.nan if v is None else v for v in m["y"]])
test = np.array(m["test"]); ns = np.array(m["no_stop"]).astype(bool)
cmap = plt.get_cmap("RdYlGn")


def draw(ax, val, title, mask_unknown=None):
    segs = uv.reshape(-1, 2, 2)
    colors = cmap(np.clip(np.nan_to_num(val, nan=0.5), 0, 1))
    if mask_unknown is not None:
        colors[mask_unknown] = (0.85, 0.85, 0.83, 1)
    lc = LineCollection(segs, colors=colors, linewidths=2.6)
    ax.add_collection(lc)
    G = 22
    ax.add_patch(plt.Rectangle((G / 2 - 0.5, -0.5), G / 2, G / 2, fill=False, ls="--", lw=1.4, ec="#185FA5"))
    ax.set_xlim(-0.8, G - 0.2); ax.set_ylim(-0.8, G - 0.2); ax.set_aspect("equal"); ax.axis("off")
    ax.set_title(title, fontsize=11.5, loc="left")


ax = fig.add_subplot(gs[1, 0]); draw(ax, q, "④ 숨은 정답 (모델은 못 봄)")
ax = fig.add_subplot(gs[1, 1])
known = ~np.isnan(y) & ~test
draw(ax, y, "⑤ 학습에 쓴 승차 기록 (회색 = 기록 없음)", mask_unknown=~known)
ax = fig.add_subplot(gs[1, 2]); draw(ax, gnn, "⑥ GNN 예측 — 파란 점선 구역은 처음 보는 지역")
sm = plt.cm.ScalarMappable(cmap=cmap, norm=plt.Normalize(0, 1))
cb = fig.colorbar(sm, ax=fig.axes[3:], orientation="horizontal", fraction=0.04, pad=0.03, aspect=50)
cb.set_label("픽업 품질 점수 (빨강 나쁨 → 초록 좋음)")
fig.suptitle("가상 도시 실험: 픽업 기록이 없는 지역(파란 점선)의 픽업 품질을 예측할 수 있는가", fontsize=13.5, x=0.06, ha="left")
fig.savefig("results.png", bbox_inches="tight")
print("saved")
