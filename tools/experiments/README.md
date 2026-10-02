# Filtering experiments

Compares the page filter's strategies on real sites and on `testpage/` (17 hard cases: split
words, Turkish endings, open/closed/nested/late shadow roots, same- and cross-origin frames,
content added or changed later, false positives, search-only words).

Needs a debug build on a device or emulator (`adb`), and Python 3:

    tools/experiments/run.py --strategies base,current,css-only --sites cases,reddit --repeat 2

Strategies are switched per run through the `hisar.experiment` intent extra, which only debug
builds read. Each run reports leaks (visible blocked text after 5 s, found by a full walk of the
page and every shadow root), the page's own script errors, the filter's time, and what it hid.

## Results that set the defaults (2026-10, emulator, 8 sites x 2 runs + the test page)

| Choice | Winner | Why |
|---|---|---|
| Unit hidden | smallest readable block + collapse of an emptied card | "item, else parent element" left 5-9 headlines visible (bbc.com) |
| Emptying hidden text | off | changing React-rendered text threw 26 errors per load on bbc.com; hiding alone leaks nothing |
| Cleaning HTML before parsing | off | same React errors; no leaks without it |
| Shadow roots | sweep on load + focus | as good as a page-world attachShadow hook, without code in the page; none = leaks |
| Inline style vs stylesheet | inline | equal |
| YouTube Restricted Mode | `YouTube-Restrict: Strict` header | confirmed by youtube.com/check_content_restrictions; the PREF cookie was not |
| Family DNS (DoH) | not adopted | resolvers unreachable on the test network; would block Reddit outright |
