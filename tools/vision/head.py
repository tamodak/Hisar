"""Trains the logistic head on frozen CLIP B/32 embeddings of all labelled pictures and picks its
thresholds from out-of-fold scores: COVER catches [--recall] of the unsafe pictures, SURE is where
[--precision] of the pictures scored above are unsafe. Writes body_head.bin (512 weights, bias)."""
import json, sys, numpy as np
from sklearn.linear_model import LogisticRegression
sys.path.insert(0, 'tools'); import compare
from compare import oof
import os
order = json.load(open(os.environ.get('ORDER', 'order.json')))
args = dict(a.split('=') for a in sys.argv[1:] if '=' in a)
recall = float(args.get('recall', 0.97)); precision = float(args.get('precision', 0.95)); C = float(args.get('C', 4))
lab = json.load(open('labels.json')); y = np.array([1 if lab[k] >= 1 else 0 for k in order]); compare.y = y
X = np.load(args.get('emb', 'emb/openai_b32_crop.npy'))
s = oof(X, C)
cover = float(np.quantile(s[y == 1], 1 - recall))
ts = np.sort(s)[::-1]
sure = next(t for t in np.sort(s) if (y[s >= t]).mean() >= precision)
clf = LogisticRegression(C=C, max_iter=5000, class_weight='balanced').fit(X, y)
np.concatenate([clf.coef_[0], clf.intercept_]).astype('<f4').tofile(args.get('out', 'body_head.bin'))
print(f'COVER {cover:.3f}: catches {(s[y == 1] >= cover).mean():.3f} of unsafe, covers {(s[y == 0] >= cover).mean():.3f} of safe')
print(f'SURE {sure:.3f}: {(s[y == 1] >= sure).mean():.3f} of unsafe reach it, {(s[y == 0] >= sure).mean():.3f} of safe')
