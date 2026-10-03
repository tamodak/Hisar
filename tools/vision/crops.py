"""Person crops (Open Images boxes standing in for the detector) embedded with CLIP B/32, letterboxed."""
import csv, json, numpy as np, torch, open_clip, collections
from PIL import Image
torch.set_num_threads(6)
order = json.load(open('order.json')); want = set(order)
PEOPLE = {'/m/03bt1vf': 'w', '/m/05r655': 'w', '/m/04yx4': 'm', '/m/01bl7v': 'm', '/m/01g317': 'p'}
boxes = collections.defaultdict(list)
for split in ['validation', 'test']:
    for r in csv.DictReader(open(f'oi/{split}-annotations-bbox.csv')):
        if r['ImageID'] in want and r['LabelName'] in PEOPLE:
            x0, x1, y0, y1 = (float(r[k]) for k in ('XMin', 'XMax', 'YMin', 'YMax'))
            boxes[r['ImageID']].append((PEOPLE[r['LabelName']], x0, y0, x1, y1))
model, _, pre = open_clip.create_model_and_transforms('ViT-B-32', pretrained='openai'); model.eval()
norm = pre.transforms[-1]
import torchvision.transforms as T
def lb(im):
    s = 224 / max(im.size); im = im.resize((max(1, round(im.width * s)), max(1, round(im.height * s))), Image.BICUBIC)
    out = Image.new('RGB', (224, 224), (123, 117, 104)); out.paste(im, ((224 - im.width) // 2, (224 - im.height) // 2)); return out
tf = T.Compose([lb, T.ToTensor(), norm])
rows, crops = [], []
for idx, k in enumerate(order):
    im = Image.open(f'img/{k}.jpg').convert('RGB'); W, H = im.size
    bs = sorted(boxes.get(k, []), key=lambda b: -(b[3] - b[1]) * (b[4] - b[2]))
    seen = []
    for kind, x0, y0, x1, y1 in bs:
        if any(abs(x0 - a) < .05 and abs(y0 - b) < .05 and abs(x1 - c) < .05 and abs(y1 - d) < .05 for a, b, c, d in seen): continue
        seen.append((x0, y0, x1, y1))
        if len(seen) > 4 or (x1 - x0) * (y1 - y0) < 0.01: break
        mx, my = .08 * (x1 - x0), .05 * (y1 - y0)
        box = (max(0, (x0 - mx) * W), max(0, (y0 - my) * H), min(W, (x1 + mx) * W), min(H, (y1 + my) * H))
        rows.append((idx, kind)); crops.append(tf(im.crop(box)))
out = []
with torch.no_grad():
    for i in range(0, len(crops), 64):
        out.append(torch.nn.functional.normalize(model.encode_image(torch.stack(crops[i:i + 64])), dim=-1).numpy())
np.save('emb/crops_openai_b32.npy', np.concatenate(out)); json.dump(rows, open('emb/crops_rows.json', 'w'))
print(len(rows), 'crops from', len(set(r[0] for r in rows)), 'images')
