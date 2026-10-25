#!/usr/bin/env python3
"""Compare FlyAgent's C++ engine output with the paper's own Brian2 run. Fails CI if the engine diverges."""
import json, sys, numpy as np
ours = {int(k): v for k, v in json.load(open(sys.argv[1])).items()}
ref_all = json.load(open(sys.argv[2])); ref = {int(k): v for k, v in ref_all["rates"].items()}; mn9 = ref_all["mn9"]
label = sys.argv[3]; strict = len(sys.argv) > 4
common = sorted(set(ours) | set(ref))
a = np.array([ours.get(i, 0) for i in common]); b = np.array([ref.get(i, 0) for i in common])
r = float(np.corrcoef(a, b)[0, 1]) if len(common) > 2 else 0.0
top_ref = set(sorted(ref, key=ref.get, reverse=True)[:50]); top_ours = set(sorted(ours, key=ours.get, reverse=True)[:50])
print(f"[{label}] active neurons ours={len(ours)} ref={len(ref)}  Pearson r={r:.3f}  top-50 overlap={len(top_ref & top_ours)}/50  "
      f"MN9 ours={ours.get(mn9, 0):.1f} Hz ref={ref.get(mn9, 0):.1f} Hz")
print(f"::notice::{label}: r={r:.3f}, top50 overlap {len(top_ref & top_ours)}/50, MN9 {ours.get(mn9,0):.1f} vs {ref.get(mn9,0):.1f} Hz")
if strict:
    assert r > 0.8, "engine diverges from the published model"
    assert ours.get(mn9, 0) > 0, "MN9 (feeding motor neuron) should respond to sugar GRN activation"
