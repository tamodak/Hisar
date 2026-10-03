"""Trains CLIP B/32's last blocks (after the cached block N) plus a linear head on the hand labels.

5-fold out-of-fold scores (compare.line) to compare with the frozen-encoder heads; --final trains on
everything and saves the weights for export.
"""
import json, sys, copy, numpy as np, torch, open_clip
sys.path.insert(0, 'tools'); import compare
from compare import line, order
from sklearn.model_selection import StratifiedKFold
torch.set_num_threads(10); torch.manual_seed(0)
N = 8; EPOCHS = int(dict(a.split('=') for a in sys.argv[1:] if '=' in a).get('epochs', 6))
LR = float(dict(a.split('=') for a in sys.argv[1:] if '=' in a).get('lr', 2e-5))
lab = json.load(open('labels.json')); y = np.array([1 if lab[k] >= 1 else 0 for k in order]); compare.y = y
T = [np.load(f'emb/tokens{N}_flip{f}.npy', mmap_mode='r') for f in (0, 1)]
base, _, _ = open_clip.create_model_and_transforms('ViT-B-32', pretrained='openai'); base.eval()
class Tail(torch.nn.Module):
    def __init__(self, v):
        super().__init__()
        self.blocks = copy.deepcopy(v.transformer.resblocks[N:]); self.ln = copy.deepcopy(v.ln_post); self.proj = torch.nn.Parameter(v.proj.detach().clone())
        self.head = torch.nn.Linear(512, 1)
    def embed(self, x):
        for b in self.blocks: x = b(x)
        e = self.ln(x[:, 0]) @ self.proj
        return torch.nn.functional.normalize(e, dim=-1)
    def forward(self, x): return self.head(self.embed(x) * 10).squeeze(-1)
def train(idx):
    m = Tail(base.visual); m.train()
    opt = torch.optim.AdamW([{'params': [p for n, p in m.named_parameters() if not n.startswith('head')], 'lr': LR},
                             {'params': m.head.parameters(), 'lr': 1e-3}], weight_decay=0.05)
    pos = y[idx].mean(); w = torch.tensor([(1 - pos) / pos])
    steps = EPOCHS * (len(idx) // 32 + 1); sched = torch.optim.lr_scheduler.OneCycleLR(opt, [LR, 1e-3], total_steps=steps, pct_start=0.15)
    rng = np.random.default_rng(0)
    for ep in range(EPOCHS):
        p = rng.permutation(idx)
        for i in range(0, len(p), 32):
            b = np.sort(p[i:i + 32]); f = rng.integers(0, 2)
            x = torch.from_numpy(np.asarray(T[f][b])).float()
            loss = torch.nn.functional.binary_cross_entropy_with_logits(m(x), torch.from_numpy(y[b]).float(), pos_weight=w)
            opt.zero_grad(); loss.backward(); opt.step(); sched.step()
    m.eval(); return m
def score(m, idx):
    out = []
    with torch.no_grad():
        for i in range(0, len(idx), 128):
            b = idx[i:i + 128]
            out.append(((m(torch.from_numpy(np.asarray(T[0][b])).float()) + m(torch.from_numpy(np.asarray(T[1][b])).float())) / 2).numpy())
    return np.concatenate(out)
if '--final' in sys.argv:
    m = train(np.arange(len(y))); torch.save(m.state_dict(), f'tail{N}.pt'); print('saved')
else:
    s = np.zeros(len(y))
    for k, (tr, te) in enumerate(StratifiedKFold(5, shuffle=True, random_state=0).split(np.zeros(len(y)), y)):
        m = train(tr); s[te] = score(m, np.sort(te)); print('fold', k, flush=True)
    np.save(f'emb/oof_ft{N}_e{EPOCHS}_lr{LR}.npy', s)
    print(line(f'finetune N={N} e={EPOCHS} lr={LR}', s))
