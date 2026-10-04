"""Out-of-fold (5-fold) scores of a logistic head on each backbone's embeddings, with false alarms by kind."""
import json, sys, numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.model_selection import StratifiedKFold
from sklearn.metrics import roc_auc_score
import os; order = json.load(open(os.environ.get('ORDER', 'order.json'))); lab = json.load(open('labels.json'))
sample = {e['id']: e['stratum'] for f in ('sample.json', 'pool.json') for e in json.load(open(f))}
y = np.array([1 if lab[k] >= 1 else 0 for k in order]); sure = np.array([lab[k] != 2 for k in order])
st = np.array([sample[k] for k in order])
def oof(X, C=2):
    s = np.zeros(len(y))
    for tr, te in StratifiedKFold(5, shuffle=True, random_state=0).split(X, y):
        s[te] = LogisticRegression(C=C, max_iter=5000, class_weight='balanced').fit(X[tr], y[tr]).decision_function(X[te])
    return s
def line(name, s):
    out = [f'{name:28s} AUC {roc_auc_score(y, s):.3f}']
    for r in [.95, .98]:
        t = np.quantile(s[y == 1], 1 - r); out.append(f'FPR@{int(r*100)} {(s[y == 0] >= t).mean():.3f}')
        out.append(f'(sure-only recall {(s[(y == 1) & sure] >= t).mean():.3f})')
    t = np.quantile(s[y == 1], 0.05)
    out.append('| ' + ' '.join(f'{g}:{(s[(st == g) & (y == 0)] >= t).mean():.2f}' for g in ['none', 'm_other', 'm_garment', 'f_other', 'f_clothes', 'f_limbs', 'f_garment']))
    return '  '.join(out)
if __name__ == '__main__':
    for spec in sys.argv[1:]:
        X = np.concatenate([np.load(f'emb/{p}.npy') for p in spec.split('+')], 1)
        for C in [1, 4]:
            print(line(f'{spec} C={C}', oof(X, C)), flush=True)
