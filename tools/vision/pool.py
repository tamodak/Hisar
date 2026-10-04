"""A second pool of Open Images people pictures not yet labelled, for active learning."""
import json, random, sys
sys.argv = ['x']; exec(open('../tools/sample_oi.py').read().split("want = {")[0])
done = set(e['id'] for e in json.load(open('../sample.json')))
random.seed(21)
want = {'f_garment': 400, 'f_limbs': 3500, 'f_clothes': 600, 'f_other': 2500, 'parts': 300, 'm_garment': 200, 'm_other': 500}
out = []
for k, n in want.items():
    pool = sorted(x for x in strata[k] if x[1] not in done); random.shuffle(pool)
    out += [{'split': sp, 'id': i, 'stratum': k, 'labels': sorted(labels[(sp, i)])} for sp, i in pool[:n]]
json.dump(out, open('../pool.json', 'w')); print(len(out))
