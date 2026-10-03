#!/usr/bin/env python3
"""
Collisions between blocked words and everyday words, in twelve languages.

A blocked word that is also a common word somewhere (Portuguese "comer", to eat; Swedish "Lund")
hides innocent text. Word frequencies come from hermitdave/FrequencyWords (OpenSubtitles 2018,
CC BY-SA 4.0), downloaded on first use into ~/.cache/hisar-frequency/; they are only used here,
never shipped.

  check_collisions.py report <list> [<list>...]   words common in any language, for review
  check_collisions.py fold <list> [--skip <lists>]  Turkish entries spelt in ASCII (amcık -> amcik),
                                                  printed unless the ASCII spelling is itself a
                                                  common word (göt -> got, sık -> sik)

Turkish is often typed without its letters, so the ASCII spellings are what many people write.
"""
import os
import re
import sys
import urllib.request

LANGUAGES = ["en", "tr", "es", "pt", "de", "fr", "it", "ru", "ar", "id", "nl", "pl"]
CACHE = os.path.expanduser("~/.cache/hisar-frequency")
URL = "https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/{0}/{0}_50k.txt"
# A word ranked this high is everyday vocabulary. Frequency lists from subtitles also hold swear
# words, so a hit is a prompt to review, not a verdict.
COMMON_RANK = 15000

TURKISH_FOLD = str.maketrans("çğıöşüâîû", "cgiosuaiu")


def frequencies(language):
    path = os.path.join(CACHE, language + ".txt")
    if not os.path.exists(path):
        os.makedirs(CACHE, exist_ok=True)
        urllib.request.urlretrieve(URL.format(language), path)
    ranks = {}
    with open(path, encoding="utf-8", errors="ignore") as f:
        for rank, line in enumerate(f):
            word = line.split(" ")[0].strip().lower()
            if word and word not in ranks:
                ranks[word] = rank
    return ranks


def tokens(text):
    return [t for t in re.split(r"[^\w]+|_", text.lower()) if t]


def entries(path):
    with open(path, encoding="utf-8") as f:
        return [l.strip() for l in f if l.strip() and not l.lstrip().startswith("#")]


def fold(word):
    return word.lower().translate(TURKISH_FOLD)


def report(paths):
    ranks = {lang: frequencies(lang) for lang in LANGUAGES}
    for path in paths:
        for entry in entries(path):
            words = tokens(entry)
            if len(words) != 1:
                continue  # Phrases are specific enough; single words are what collide.
            word = words[0]
            hits = [f"{lang}:{ranks[lang][word]}" for lang in LANGUAGES if ranks[lang].get(word, COMMON_RANK) < COMMON_RANK]
            if hits:
                print(f"{os.path.basename(path)}\t{word}\t{' '.join(hits)}")


def folded_variants(path, skip=()):
    """
    ASCII spellings of Turkish entries that don't collide with common words. Entries of [skip]
    (other languages' lists: German "möse" is not Turkish) are left as they are.
    """
    foreign = {e.lower() for p in skip for e in entries(p)}
    turkish = frequencies("tr")
    english = frequencies("en")
    # Common words of either language, as they look typed without Turkish letters.
    common = {fold(w) for w, r in turkish.items() if r < COMMON_RANK} | {w for w, r in english.items() if r < COMMON_RANK}
    out = []
    for entry in entries(path):
        if entry.lower() in foreign:
            continue
        folded = fold(entry)
        if folded == entry.lower():
            continue
        clashing = [w for w in tokens(folded) if w in common and w not in tokens(entry.lower())]
        # A phrase may contain common words; it collides only if it is one word.
        if len(tokens(folded)) == 1 and clashing:
            continue
        out.append(folded)
    return out


if __name__ == "__main__":
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    if sys.argv[1] == "report":
        report(sys.argv[2:])
    elif sys.argv[1] == "fold":
        skip = sys.argv[sys.argv.index("--skip") + 1:] if "--skip" in sys.argv else []
        print("\n".join(folded_variants(sys.argv[2], skip)))
