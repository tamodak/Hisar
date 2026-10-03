#!/usr/bin/env python3
"""
Prints the plural of each entry's last word (English and Spanish-style: +s, +es, -y -> -ies), for
entries written in Latin letters. Matching is by whole word, so "thirst trap" would otherwise
miss "thirst traps". Turkish endings have their own rule (suffix stems).

Usage: inflect.py <list>
"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from check_collisions import COMMON_RANK, LANGUAGES, frequencies  # noqa: E402

VOWELS = set("aeiou")


def plural(word):
    if re.search(r"(s|x|z|ch|sh)$", word):
        return word + "es"
    if word.endswith("y") and len(word) > 1 and word[-2] not in VOWELS:
        return word[:-1] + "ies"
    return word + "s"


# A made-up plural that is an everyday word in another language is dropped: Turkish "götte" spelt
# in ASCII would become German "gottes" (God's).
everyday = set()
for language in LANGUAGES:
    if language != "en":
        everyday |= {w for w, r in frequencies(language).items() if r < COMMON_RANK}

with open(sys.argv[1], encoding="utf-8") as f:
    for line in f:
        entry = line.strip()
        if not entry or not re.fullmatch(r"[a-z0-9 ]*[a-z]", entry):
            continue
        words = entry.split(" ")
        last = words[-1]
        # Short words are often abbreviations or parts of names; their plurals are left alone.
        if len(last) < 3:
            continue
        made = plural(last)
        if made in everyday:
            continue
        print(" ".join(words[:-1] + [made]))
