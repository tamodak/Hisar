"""Scores the unlabelled pool with the current head and picks the pictures it is least sure about
(between a bit below COVER and a bit above SURE) for hand labelling: pool_order.json + grids2/."""
import json, os, numpy as np, torch, open_clip
from PIL import Image, ImageDraw, ImageFont
torch.set_num_threads(10)
pool = [e['id'] for e in json.load(open('pool.json')) if os.path.exists(f"img/{e['id']}.jpg")]
model, _, pre = open_clip.create_model_and_transforms('ViT-B-32', pretrained='openai'); model.eval()
out = []
with torch.no_grad():
    for i in range(0, len(pool), 64):
        x = torch.stack([pre(Image.open(f'img/{k}.jpg')) for k in pool[i:i + 64]])
        out.append(torch.nn.functional.normalize(model.encode_image(x), dim=-1).numpy())
E = np.concatenate(out); np.save('emb/pool_openai_b32_crop.npy', E); json.dump(pool, open('pool_ids.json', 'w'))
head = np.fromfile('body_head.bin', '<f4'); s = E @ head[:512] + head[512]
COVER, SURE = -1.337, 1.075
band = np.where((s > COVER - 1.5) & (s < SURE + 1.0))[0]
rng = np.random.default_rng(5)
pick = band if len(band) <= 2000 else rng.choice(band, 2000, replace=False)
pick = [pool[i] for i in rng.permutation(pick)]
json.dump(pick, open('pool_order.json', 'w'))
print(len(pool), 'pool;', len(band), 'in the band;', len(pick), 'picked')
os.makedirs('grids2', exist_ok=True)
C = 256; font = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf', 22)
for g in range(0, len(pick), 20):
    sheet = Image.new('RGB', (5 * C, 4 * C), 'white'); d = ImageDraw.Draw(sheet)
    for k, i in enumerate(pick[g:g + 20]):
        im = Image.open(f'img/{i}.jpg'); im.thumbnail((C - 4, C - 4)); x, yy = (k % 5) * C, (k // 5) * C
        sheet.paste(im, (x + (C - im.width) // 2, yy + (C - im.height) // 2)); d.rectangle([x, yy, x + 34, yy + 26], fill='yellow'); d.text((x + 3, yy + 1), str(k), fill='black', font=font)
    sheet.save(f'grids2/{g // 20:03d}.jpg', quality=85)
