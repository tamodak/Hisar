"""Caches CLIP B/32's tokens after block N (frozen part), with flips, for fast training of the last blocks."""
import json, sys, numpy as np, torch, open_clip
from PIL import Image
torch.set_num_threads(6)
N = int(sys.argv[1]) if len(sys.argv) > 1 else 8
import os; order = json.load(open(os.environ.get('ORDER', 'order.json')))
model, _, pre = open_clip.create_model_and_transforms('ViT-B-32', pretrained='openai'); model.eval()
v = model.visual
def tokens(x):
    x = v.conv1(x); x = x.reshape(x.shape[0], x.shape[1], -1).permute(0, 2, 1)
    x = torch.cat([v.class_embedding.to(x.dtype) + torch.zeros(x.shape[0], 1, x.shape[-1], dtype=x.dtype), x], 1)
    x = x + v.positional_embedding.to(x.dtype); x = v.patch_dropout(x); x = v.ln_pre(x)
    for blk in v.transformer.resblocks[:N]: x = blk(x)
    return x
for flip in [0, 1]:
    out = []
    with torch.no_grad():
        for i in range(0, len(order), 64):
            ims = [Image.open(f'img/{k}.jpg') for k in order[i:i + 64]]
            if flip: ims = [im.transpose(Image.FLIP_LEFT_RIGHT) for im in ims]
            out.append(tokens(torch.stack([pre(im) for im in ims])).half().numpy())
    np.save(f'emb/{os.environ.get("PREFIX", "")}tokens{N}_flip{flip}.npy', np.concatenate(out)); print('flip', flip, flush=True)
