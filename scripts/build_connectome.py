#!/usr/bin/env python3
"""Convert the real FlyWire v783 connectivity (as exported by Shiu et al., github.com/philshiu/Drosophila_brain_model)
into FlyAgent's FLYC binary format. Nothing here is synthesized: every edge comes from Connectivity_783.parquet.

Outputs (in OUT):
  fly783_full.bin      all 138,639 neurons / all edges (published as a GitHub Release asset, downloaded by the app)
  fly783_subset.bin    induced subgraph around the sugar-sensing pathway (bundled in the APK)
  sugar_ids.txt        the 21 sugar GRN IDs and MN9 ID from the paper's example.ipynb
  ref_sugar100.json    reference rates from the paper's own Brian2 run (results/example/sugarR_100Hz.parquet, v630 data)
"""
import json, struct, sys, os
import numpy as np, pandas as pd

SHIU, OUT, ASSET = sys.argv[1], sys.argv[2], sys.argv[3]
os.makedirs(OUT, exist_ok=True); os.makedirs(os.path.dirname(ASSET), exist_ok=True)

# from example.ipynb (verified present in Completeness_783.csv)
SUGAR = [720575940624963786, 720575940630233916, 720575940637568838, 720575940638202345, 720575940617000768,
         720575940630797113, 720575940632889389, 720575940621754367, 720575940621502051, 720575940640649691,
         720575940639332736, 720575940616885538, 720575940639198653, 720575940620900446, 720575940617937543,
         720575940632425919, 720575940633143833, 720575940612670570, 720575940628853239, 720575940629176663,
         720575940611875570]
MN9 = 720575940660219265

comp = pd.read_csv(f"{SHIU}/Completeness_783.csv", index_col=0)
con = pd.read_parquet(f"{SHIU}/Connectivity_783.parquet")
ids = comp.index.to_numpy(dtype=np.int64)
pre = con["Presynaptic_Index"].to_numpy(np.int64); post = con["Postsynaptic_Index"].to_numpy(np.int64)
w = con["Excitatory x Connectivity"].to_numpy(np.float32)
# sanity: the index columns must agree with the ID columns (index = row in Completeness, as model.py assumes)
assert (ids[pre] == con["Presynaptic_ID"].to_numpy(np.int64)).all() and (ids[post] == con["Postsynaptic_ID"].to_numpy(np.int64)).all()
print(f"FlyWire v783: {len(ids)} neurons, {len(w)} connections, {int(np.abs(w).sum())} synapses")

def write(path, sel_ids, p, q, ww, source):
    order = np.lexsort((q, p)); p, q, ww = p[order], q[order], ww[order]
    n = len(sel_ids); rowptr = np.zeros(n + 1, np.uint32)
    np.add.at(rowptr, p + 1, 1); rowptr = np.cumsum(rowptr).astype(np.uint32)
    src = source.encode()
    with open(path, "wb") as f:
        f.write(b"FLYC"); f.write(struct.pack("<IIQI", 1, n, len(q), len(src))); f.write(src)
        f.write(np.asarray(sel_ids, np.int64).tobytes()); f.write(rowptr.tobytes())
        f.write(q.astype(np.uint32).tobytes()); f.write(ww.astype(np.float32).tobytes())
    import hashlib
    print(f"wrote {path}: {n} neurons, {len(q)} edges, {os.path.getsize(path)/1e6:.1f} MB, sha256 {hashlib.sha256(open(path,'rb').read()).hexdigest()[:16]}")

CITE = "FlyWire Consortium, connectome v783 (Dorkenwald et al. 2024; Schlegel et al. 2024), CC BY 4.0, doi:10.5281/zenodo.10676866; export by Shiu et al. 2024 (MIT)"
write(f"{OUT}/fly783_full.bin", ids, pre, post, w, f"FULL brain | {CITE}")

# Subset: sugar GRNs + neurons strongly downstream (>= 5 synapses from the current set), up to 3 hops, plus MN9.
# Induced subgraph = all real edges among the selected neurons. Inputs from outside the subset are absent, so subset
# dynamics are an approximation of the full model; the app says so and offers the full brain.
idx = {int(x): i for i, x in enumerate(ids)}
missing = [s for s in SUGAR if s not in idx]  # v630 IDs can change in v783 after re-proofreading; we do not guess replacements
print(f"sugar GRNs present in v783: {len(SUGAR) - len(missing)}/{len(SUGAR)}; missing: {missing}")
SUGAR = [s for s in SUGAR if s in idx]
sel = {idx[s] for s in SUGAR}
frontier = set(sel)
for hop in range(3):
    m = np.isin(pre, list(frontier)) & (np.abs(w) >= 5)
    new = set(post[m].tolist()) - sel
    if len(sel) + len(new) > 6000:  # keep the bundled asset small: strongest targets first, ties broken by index
        score = np.bincount(post[m], weights=np.abs(w[m]).astype(np.float64), minlength=len(ids))
        cand = np.array(sorted(new))
        new = set(cand[np.lexsort((cand, -score[cand]))][: 6000 - len(sel)].tolist())
    print(f"hop {hop+1}: +{len(new)}"); sel |= new; frontier = new
    if not new: break
sel.add(idx[MN9]); print("MN9 reached by pathway expansion:", idx[MN9] in sel)
selected = np.array(sorted(sel)); remap = -np.ones(len(ids), np.int64); remap[selected] = np.arange(len(selected))
m = (remap[pre] >= 0) & (remap[post] >= 0)
write(ASSET, ids[selected], remap[pre[m]], remap[post[m]], w[m], f"SUBSET (sugar pathway, 3 hops >=5 syn, induced subgraph) | {CITE}")

with open(f"{OUT}/sugar_ids.txt", "w") as f: f.write("\n".join(map(str, SUGAR)) + "\n")
ref = pd.read_parquet(f"{SHIU}/results/example/sugarR_100Hz.parquet")
rates = (ref.groupby("flywire_id").size() / (30 * 1.0)).to_dict()  # 30 trials x 1 s, as in the notebook
json.dump({"mn9": MN9, "rates": {str(k): v for k, v in rates.items()}}, open(f"{OUT}/ref_sugar100.json", "w"))
print(f"reference: {len(rates)} active neurons, MN9 = {rates.get(MN9, 0):.1f} Hz")
