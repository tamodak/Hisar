"""Compares backbones and heads on the hand labels. Unsure labels count as unsafe (the filter covers when unsure)."""
import json, sys, numpy as np, torch, open_clip
from sklearn.linear_model import LogisticRegression
from sklearn.model_selection import StratifiedKFold
from sklearn.metrics import roc_auc_score
sys.path.insert(0, 'tools'); from prompts import UNSAFE, SAFE
order = json.load(open('order.json')); lab = json.load(open('labels.json'))
y = np.array([1 if lab[k] >= 1 else 0 for k in order])
rng = np.random.default_rng(0); test = rng.random(len(y)) < 0.25
def fpr_at(score, y, recall):
    t = np.quantile(score[y == 1], 1 - recall); return (score[y == 0] >= t).mean()
def report(name, s, yy):
    return f'{name:34s} AUC {roc_auc_score(yy, s):.3f}  FPR@95 {fpr_at(s, yy, .95):.3f}  FPR@98 {fpr_at(s, yy, .98):.3f}'
OC = {'openai_b32': ('ViT-B-32', 'openai'), 'laion2b_b32': ('ViT-B-32', 'laion2b_s34b_b79k'), 'datacomp_b32': ('ViT-B-32', 'datacomp_xl_s13b_b90k'),
      'siglip_b16': ('ViT-B-16-SigLIP', 'webli'), 'siglip2_b16': ('ViT-B-16-SigLIP2', 'webli')}
def text_emb(name):
    if name in OC:
        m, _, _ = open_clip.create_model_and_transforms(*OC[name]); tok = open_clip.get_tokenizer(OC[name][0])
        with torch.no_grad(): f = lambda p: torch.nn.functional.normalize(m.encode_text(tok(p)), dim=-1).detach().numpy()
        return f(UNSAFE), f(SAFE)
    from transformers import CLIPModel, CLIPTokenizer
    hub = {'tinyclip_8m16': 'wkcn/TinyCLIP-ViT-8M-16-Text-3M-YFCC15M', 'tinyclip_40m32': 'wkcn/TinyCLIP-ViT-40M-32-Text-19M-LAION400M', 'tinyclip_61m32': 'wkcn/TinyCLIP-ViT-61M-32-Text-29M-LAION400M'}[name]
    m = CLIPModel.from_pretrained(hub).eval(); tok = CLIPTokenizer.from_pretrained(hub)
    with torch.no_grad(): f = lambda p: torch.nn.functional.normalize(m.get_text_features(**tok(p, padding=True, return_tensors='pt')), dim=-1).detach().numpy()
    return f(UNSAFE), f(SAFE)
for name in sys.argv[1:]:
    U, S = text_emb(name)
    for mode in ['crop', 'lb']:
        X = np.load(f'emb/{name}_{mode}.npy')
        sims = X @ np.concatenate([U, S]).T * 100
        z = np.exp(sims - sims.max(1, keepdims=True)); p = z[:, :len(U)].sum(1) / z.sum(1)
        print(report(f'{name}/{mode} zero-shot', p[test], y[test]))
        feats = np.concatenate([X, sims / 100], 1)
        for C in [0.5, 2, 8]:
            clf = LogisticRegression(C=C, max_iter=4000, class_weight='balanced').fit(X[~test], y[~test])
            print(report(f'{name}/{mode} probe C={C}', clf.decision_function(X[test]), y[test]))
        clf = LogisticRegression(C=2, max_iter=4000, class_weight='balanced').fit(feats[~test], y[~test])
        print(report(f'{name}/{mode} probe+prompts', clf.decision_function(feats[test]), y[test]), flush=True)
