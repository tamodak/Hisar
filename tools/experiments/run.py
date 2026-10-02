#!/usr/bin/env python3
"""
Compares filtering strategies on real sites, on a device or emulator running a debug build.

For each strategy and site it starts Hisar with clean data, opens the site with the strategy
switched on (the "hisar.experiment" extra, read by debug builds only), and collects what the
extension measures (logcat tag Hisar.Metrics):

  scanMs      time the content script spent filtering the page
  checks      items it judged
  hidden      items it hid
  leaks       visible items still holding a blocked word, 5 s after load, found by a full walk
              of the page and every shadow root, whatever the strategy
  errors      the page's own script errors (a sign of breakage), and unhandled rejections
  loadMs      the page's load event, from navigation start
  htmlMs      time spent cleaning the page's HTML before parsing, and the text runs removed

Usage: run.py [--device SERIAL] [--out DIR] [--strategies a,b] [--sites a,b] [--repeat N]
Writes DIR/results.jsonl, a screenshot per run, and prints a table per site.
"""
import argparse
import json
import os
import subprocess
import sys
import time

PACKAGE = "org.tamodak.hisar"

# Each strategy changes one thing from "current", except "base", which measures pages untouched.
STRATEGIES = {
    "base": {"filter": False, "html": False},
    "current": {},
    "nohtml": {"html": False},
    "sheet": {"hide": "sheet"},
    "noempty": {"empty": False},
    "shadow-main": {"shadow": "main"},
    "shadow-none": {"shadow": "none"},
    "scan-node": {"scan": "node"},
    "yt-header": {"youtube": "header"},
    # Leave server-rendered text alone (React hydration): hide with CSS only.
    "css-only": {"html": False, "empty": False},
    "css-only-sheet": {"html": False, "empty": False, "hide": "sheet"},
    # Judging the smallest readable block instead of "the item, else the parent element".
    "block": {"html": False, "empty": False, "scan": "block"},
    "block-html": {"empty": False, "scan": "block"},
    "item-block": {"html": False, "empty": False, "scan": "item-block"},
    "block-collapse": {"html": False, "empty": False, "scan": "block", "collapse": True},
    "block-sheet": {"html": False, "empty": False, "scan": "block", "hide": "sheet"},
    "block-shadow-main": {"html": False, "empty": False, "scan": "block", "shadow": "main"},
    "block-shadow-none": {"html": False, "empty": False, "scan": "block", "shadow": "none"},
    "dns": {"dns": "cleanbrowsing"},
}

SITES = {
    # The hard cases (testpage/), served from this machine: deterministic, unlike real feeds.
    "cases": "http://localhost:8765/index.html",
    "reddit": "https://www.reddit.com/",
    "askreddit": "https://www.reddit.com/r/AskReddit/",
    "youtube": "https://m.youtube.com/",
    "yt-search": "https://m.youtube.com/results?search_query=tran",
    "yt-restrict": "https://www.youtube.com/check_content_restrictions",
    "google": "https://www.google.com/search?q=kedi&safe=active",
    "wikipedia": "https://en.wikipedia.org/wiki/Venus_de_Milo",
    "bbc": "https://www.bbc.com/news",
    "x": "https://x.com/explore",
    "instagram": "https://www.instagram.com/explore/",
}

TIMEOUT_SECONDS = 60


def adb(device, *args, check=True):
    return subprocess.run(["adb", "-s", device, *args], capture_output=True, text=True, check=check).stdout


def run_one(device, out, strategy, site, url, attempt):
    config = dict(STRATEGIES[strategy], name=strategy)
    adb(device, "shell", "pm", "clear", PACKAGE)
    adb(device, "logcat", "-c")
    command = (
        f"am start -n {PACKAGE}/.MainActivity -a android.intent.action.VIEW -d '{url}' "
        f"--es hisar.experiment '{json.dumps(config, separators=(',', ':'))}'"
    )
    adb(device, "shell", command)
    started = time.time()
    page, html = None, []
    while time.time() - started < TIMEOUT_SECONDS:
        time.sleep(3)
        page, html = None, []
        for line in adb(device, "logcat", "-d", "-s", "Hisar.Metrics:I").splitlines():
            if "{" not in line:
                continue
            try:
                m = json.loads(line[line.index("{"):])
            except ValueError:
                continue
            if m.get("kind") == "html":
                html.append(m)
            elif m.get("type") == "metrics" and m.get("kind") is None:
                page = m
        if page:
            break
    shot = os.path.join(out, f"{site}-{strategy}-{attempt}.png")
    with open(shot, "wb") as f:
        f.write(subprocess.run(["adb", "-s", device, "exec-out", "screencap", "-p"], capture_output=True).stdout)
    result = {"strategy": strategy, "site": site, "attempt": attempt, "url": url, "screenshot": shot}
    if page:
        for key in ("scanMs", "checks", "hidden", "roots", "errors", "rejections", "leaks", "samples", "errorSamples", "hiddenSamples", "hiddenChars", "auditMs", "loadMs", "domContentLoadedMs", "probe", "url"):
            result["page" if key == "url" else key] = page.get(key)
        result["htmlMs"] = sum(h.get("ms", 0) for h in html)
        result["htmlRemoved"] = sum(h.get("removed", 0) for h in html)
    else:
        result["missing"] = True
    return result


def table(results):
    by_site = {}
    for r in results:
        by_site.setdefault(r["site"], []).append(r)
    columns = ["strategy", "leaks", "hidden", "hiddenChars", "errors", "scanMs", "htmlMs", "htmlRemoved", "roots", "loadMs"]
    for site, rows in by_site.items():
        print(f"\n## {site}")
        print(" | ".join(columns))
        for r in rows:
            print(" | ".join("n/a" if r.get("missing") else str(r.get(c, "")) for c in columns))
            if site == "cases" and r.get("probe"):
                print("    cases: " + r["probe"])
            if r.get("errorSamples"):
                print("    errors: " + " / ".join(r["errorSamples"]))
            if r.get("samples"):
                print("    leaks: " + " / ".join(r["samples"]))


def serve_test_page(device):
    """Serves testpage/ on two ports (a second origin for the cross-origin frame)."""
    here = os.path.join(os.path.dirname(os.path.abspath(__file__)), "testpage")
    for port in (8765, 8766):
        subprocess.Popen([sys.executable, "-m", "http.server", str(port)], cwd=here,
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        adb(device, "reverse", f"tcp:{port}", f"tcp:{port}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--device", default="emulator-5554")
    parser.add_argument("--out", default="experiment-results")
    parser.add_argument("--strategies", default=",".join(STRATEGIES))
    parser.add_argument("--sites", default=",".join(SITES))
    parser.add_argument("--repeat", type=int, default=1)
    args = parser.parse_args()
    os.makedirs(args.out, exist_ok=True)
    if "cases" in args.sites.split(","):
        serve_test_page(args.device)
    results = []
    with open(os.path.join(args.out, "results.jsonl"), "a") as log:
        for site in args.sites.split(","):
            for strategy in args.strategies.split(","):
                for attempt in range(args.repeat):
                    r = run_one(args.device, args.out, strategy, site, SITES[site], attempt)
                    results.append(r)
                    log.write(json.dumps(r) + "\n")
                    log.flush()
                    print(f"{site:12} {strategy:12} " + ("no metrics" if r.get("missing") else
                          f"leaks={r['leaks']} hidden={r['hidden']} errors={r['errors']} scanMs={r['scanMs']} htmlMs={r['htmlMs']} loadMs={r['loadMs']}"),
                          flush=True)
    table(results)


if __name__ == "__main__":
    sys.exit(main())
