"""Linear vs small MLP heads on the same embeddings, 5-fold, all labels."""
import json, os, sys, numpy as np, torch
from sklearn.model_selection import StratifiedKFold
from sklearn.linear_model import LogisticRegression
sys.path.insert(0, 'tools'); os.environ['ORDER'] = 'order_all.json'
import compare
X = np.concatenate([np.load(f'emb/{p}.npy') for p in sys.argv[1].split('+')], 1).astype('float32'); y = compare.y
def mlp(tr, te, hidden=256, epochs=60, wd=1e-3, drop=0.3):
    torch.manual_seed(0)
    m = torch.nn.Sequential(torch.nn.Dropout(0.1), torch.nn.Linear(X.shape[1], hidden), torch.nn.GELU(), torch.nn.Dropout(drop), torch.nn.Linear(hidden, 1))
    opt = torch.optim.AdamW(m.parameters(), 1e-3, weight_decay=wd)
    xt, yt = torch.from_numpy(X[tr] * 10), torch.from_numpy(y[tr]).float()
    pw = torch.tensor((1 - y[tr].mean()) / y[tr].mean())
    for ep in range(epochs):
        p = torch.randperm(len(tr))
        for i in range(0, len(tr), 64):
            b = p[i:i + 64]
            loss = torch.nn.functional.binary_cross_entropy_with_logits(m(xt[b]).squeeze(-1), yt[b], pos_weight=pw)
            opt.zero_grad(); loss.backward(); opt.step()
    m.eval()
    with torch.no_grad(): return m(torch.from_numpy(X[te] * 10)).squeeze(-1).numpy()
for name, f in [('linear', None), ('mlp256', dict(hidden=256)), ('mlp512', dict(hidden=512, drop=0.5))]:
    s = np.zeros(len(y))
    for tr, te in StratifiedKFold(5, shuffle=True, random_state=0).split(X, y):
        s[te] = LogisticRegression(C=4, max_iter=5000, class_weight='balanced').fit(X[tr], y[tr]).decision_function(X[te]) if f is None else mlp(tr, te, **f)
    print(compare.line(f'{sys.argv[1][:20]} {name}', s), flush=True)
