import json, io, concurrent.futures as cf, urllib.request, os
from PIL import Image
s = json.load(open('sample.json'))
def get(e):
    p = f"img/{e['id']}.jpg"
    if os.path.exists(p): return 1
    try:
        b = urllib.request.urlopen(f"https://open-images-dataset.s3.amazonaws.com/{e['split']}/{e['id']}.jpg", timeout=60).read()
        im = Image.open(io.BytesIO(b)).convert('RGB'); im.thumbnail((640, 640)); im.save(p, quality=90); return 1
    except Exception as x: return 0
with cf.ThreadPoolExecutor(24) as ex: print(sum(ex.map(get, s)), len(s))
