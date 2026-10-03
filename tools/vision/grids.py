"""Numbered contact sheets for hand labelling: grids/NNN.jpg, 20 pictures each (5x4, 256 px cells)."""
import json, random, os, sys
from PIL import Image, ImageDraw, ImageFont
s = json.load(open('sample.json')); random.seed(3); random.shuffle(s)
order = [e['id'] for e in s if os.path.exists(f"img/{e['id']}.jpg")]
json.dump(order, open('order.json', 'w'))
os.makedirs('grids', exist_ok=True)
C, N = 256, 20
font = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf', 22)
for g in range(0, len(order), N):
    sheet = Image.new('RGB', (5 * C, 4 * C), 'white'); d = ImageDraw.Draw(sheet)
    for k, i in enumerate(order[g:g + N]):
        im = Image.open(f'img/{i}.jpg'); im.thumbnail((C - 4, C - 4))
        x, y = (k % 5) * C, (k // 5) * C
        sheet.paste(im, (x + (C - im.width) // 2, y + (C - im.height) // 2))
        d.rectangle([x, y, x + 34, y + 26], fill='yellow'); d.text((x + 3, y + 1), str(k), fill='black', font=font)
    sheet.save(f'grids/{g // N:03d}.jpg', quality=85)
print(len(order), (len(order) + N - 1) // N)
