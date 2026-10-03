#!/usr/bin/env python3
"""Fetches YouTube's mobile search data for each query in a file into coverage/data/<file>/."""
import json, os, re, subprocess, sys, urllib.parse

UA = "Mozilla/5.0 (Android 14; Mobile; rv:157.0) Gecko/157.0 Firefox/157.0"
HERE = os.path.dirname(os.path.abspath(__file__))


def fetch(query, out):
    path = os.path.join(out, re.sub(r"\W+", "_", query) + ".json")
    if os.path.exists(path):
        return True
    html = subprocess.run(["curl", "-s", "-A", UA, "-H", "Accept-Language: tr,en;q=0.8",
                           "https://m.youtube.com/results?search_query=" + urllib.parse.quote(query)],
                          capture_output=True, text=True).stdout
    m = re.search(r"var ytInitialData = '(.*?)';", html, re.S)
    if not m:
        return False
    # The data is a JS string literal: \xHH escapes become JSON's \u00HH, and \' a plain quote.
    literal = re.sub(r"\\x([0-9a-fA-F]{2})", r"\\u00\1", m.group(1).replace("\\'", "'"))
    try:
        data = json.loads(json.loads('"' + literal + '"'))
    except ValueError:
        return False
    json.dump(data, open(path, "w"))
    return True


if __name__ == "__main__":
    name = sys.argv[1]
    out = os.path.join(HERE, "data", os.path.splitext(os.path.basename(name))[0])
    os.makedirs(out, exist_ok=True)
    for q in open(os.path.join(HERE, name), encoding="utf-8").read().split("\n"):
        if q.strip():
            print(q, "ok" if fetch(q.strip(), out) else "FAILED")
