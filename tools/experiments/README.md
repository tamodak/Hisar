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

## Filtering data before pages read it (2026-10-03)

Text on the page can't catch what a page never shows as text (a video's tags), and hiding after
the fact leaves the thing playable. The data filter (`json-filter.js`) works on any site's JSON —
fetched responses and the data pages embed in their HTML — with the page filter's rule: the
smallest list entry that is an item (3+ readable strings) and holds a blocked word, #hashtag or tag
is removed. Measured:

| Check | Result |
|---|---|
| YouTube "tran" search (real data) | 12 of 12 trans videos removed; 6 innocent kept (Tren, Transformers, "outfit transition", "Japan trans vs Indian tran") |
| Watch pages (player data with tags) | 12 of 12 trans videos blocked as a whole; 6 innocent play |
| 12 sites x 2 runs | 0 leaks, 0 new page errors; 3-250 ms per page |
| bbc.com (server-rendered, React) | entries already in the HTML are left to the page filter: removing them broke hydration |
| instagram.com | a blocked phrase in a plain string list is blanked, not taken as the page's subject |
| YouTube's service worker cache | bypasses the data filter; the page's own title/tags rule leaves the page |
