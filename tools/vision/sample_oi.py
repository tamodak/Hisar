"""Picks a stratified sample of Open Images validation/test images for the image filter's data."""
import csv, random, collections, json, sys
random.seed(7)
L = {'woman': '/m/03bt1vf', 'girl': '/m/05r655', 'man': '/m/04yx4', 'boy': '/m/01bl7v',
     'swimwear': '/m/01gkx_', 'bra': '/m/01gmv2', 'miniskirt': '/m/01cmb2', 'shorts': '/m/01bfm9',
     'leg': '/m/035r7c', 'foot': '/m/031n1', 'arm': '/m/0dzf4', 'body': '/m/02p0tk3', 'face': '/m/0dzct',
     'dress': '/m/01d40f', 'skirt': '/m/02wv6h6', 'jeans': '/m/0fly7', 'suit': '/m/01xyhv', 'sandal': '/m/03nfch',
     'heels': '/m/06k2mb', 'person': '/m/01g317', 'hand': '/m/0k65p'}
inv = {v: k for k, v in L.items()}
labels = collections.defaultdict(set)
for split in ['validation', 'test']:
    with open(f'{split}-annotations-bbox.csv') as f:
        for r in csv.DictReader(f):
            labels[(split, r['ImageID'])].add(inv.get(r['LabelName'], 'other'))
F = {'woman', 'girl'}; M = {'man', 'boy'}; G = {'swimwear', 'bra', 'miniskirt', 'shorts'}
P = F | M | {'person', 'leg', 'foot', 'arm', 'body', 'face', 'hand'}
strata = collections.defaultdict(list)
for key, s in labels.items():
    if s & F and s & G: strata['f_garment'].append(key)
    elif s & F and s & {'leg', 'foot', 'arm', 'sandal', 'heels'}: strata['f_limbs'].append(key)
    elif s & F and s & {'dress', 'skirt', 'jeans', 'suit'}: strata['f_clothes'].append(key)
    elif s & F: strata['f_other'].append(key)
    elif s & {'leg', 'foot'} and not s & (M | {'person'}): strata['parts'].append(key)
    elif s & M and s & G: strata['m_garment'].append(key)
    elif s & M: strata['m_other'].append(key)
    elif not s & P: strata['none'].append(key)
want = {'f_garment': 800, 'f_limbs': 700, 'f_clothes': 500, 'f_other': 300, 'parts': 400, 'm_garment': 200, 'm_other': 350, 'none': 600}
out = []
for k, n in want.items():
    pool = sorted(strata[k]); random.shuffle(pool)
    print(k, len(pool), file=sys.stderr)
    out += [{'split': sp, 'id': i, 'stratum': k, 'labels': sorted(labels[(sp, i)])} for sp, i in pool[:n]]
json.dump(out, open('../sample.json', 'w'))
print(len(out), file=sys.stderr)
