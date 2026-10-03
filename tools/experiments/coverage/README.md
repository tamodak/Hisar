# List coverage

How much of what the lists are meant to catch they do catch, and what they catch by mistake,
measured on real YouTube search results:

    tools/experiments/coverage/fetch.py relevant.txt     # caches results in coverage/data/
    tools/experiments/coverage/fetch.py innocent.txt
    node tools/experiments/coverage/measure.js

`measure.js` runs the extension's data filter with the current lists over every cached search
and prints, per search, how many results it removes and the ones it keeps (relevant searches) or
removes (innocent searches). Read those by hand: the kept list shows missing words, the removed
list shows words that need an allowed phrase (tools/allowed-phrases.txt) or a narrower form.

2026-10-03: relevant videos 418 of 464 removed (the rest mostly innocent: Kendrick Lamar's "PRIDE",
SCP "MTF", FTM trading, trains); innocent 0 of 444 removed.
