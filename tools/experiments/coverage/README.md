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

2026-10-03, first lists: relevant videos 418 of 464 removed.
SCP "MTF", FTM trading, trains); innocent 0 of 444 removed.

2026-10-04, after the multilingual lists (45 relevant searches in 9 languages, 48 innocent ones
chosen to probe collisions: Moby Dick, Ford Escort, Queen, çıplak ayak, kaşar peyniri, news in 8
languages): relevant 581 of 728 removed, innocent 0 of 864 removed. Most of what is kept is
suggestive rather than explicit (dance videos, film kiss scenes, swimwear listings), where the
words are innocent and only the picture tells.

2026-10-03, names and titles (18 more relevant searches: performers, OnlyFans creators, Game of
Thrones, Euphoria, Bridgerton, 365 Days, Saltburn…; 20 more innocent ones probing title
collisions: Saltburn-by-the-Sea, American Pie the song, Rome and Spartacus history, Fortnite
skins, Lisa Ann Walter): relevant 611 → 755 of 921 removed; innocent 18 of 1244 removed, all
about the blocked series (a Bridgerton dance cover, Euphoria's soundtrack, #houseofthedragon)
except Deep Throat of Watergate, which was already blocked as the film's title.
