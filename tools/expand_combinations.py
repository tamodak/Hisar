#!/usr/bin/env python3
"""
Expands tools/combinations.txt into phrases for the main word list (printed one per line).

Each line is "base | words after | words before | Turkish words after"; Turkish words are
expanded with their plural, case and possessive endings, following vowel harmony, buffer
consonants (kişi -> kişiyi) and the softening of a final k (erkek -> erkeği).

Usage: expand_combinations.py tools/combinations.txt
"""
import sys

BACK = set("aıou")
FRONT = set("eiöü")
VOICELESS = set("çfhkpsşt")


def last_vowel(word):
    for c in reversed(word):
        if c in BACK or c in FRONT:
            return c
    return "e"


def two_way(word):
    """a/e by vowel harmony."""
    return "a" if last_vowel(word) in BACK else "e"


def four_way(word):
    """ı/i/u/ü by vowel harmony."""
    v = last_vowel(word)
    return {"a": "ı", "ı": "ı", "o": "u", "u": "u", "e": "i", "i": "i", "ö": "ü", "ü": "ü"}[v]


def vowels(word):
    return sum(1 for c in word if c in BACK or c in FRONT)


def softened(word):
    """The stem before a vowel ending: a final k softens in words of more than one syllable."""
    if word.endswith("k") and vowels(word) > 1:
        return word[:-1] + "ğ"
    return word


def turkish_forms(noun):
    """A Turkish noun with its common endings, singular and plural."""
    forms = {noun}
    ends_vowel = noun[-1] in BACK or noun[-1] in FRONT
    a, i = two_way(noun), four_way(noun)
    d = "t" if noun[-1] in VOICELESS else "d"
    stem = noun if ends_vowel else softened(noun)
    if ends_vowel:
        forms |= {noun + "y" + i, noun + "n" + i + "n", noun + "y" + a, noun + "s" + i, noun + "y" + "l" + a}
    else:
        forms |= {stem + i, stem + i + "n", stem + a, noun + "l" + a}
    forms |= {noun + d + a, noun + d + a + "n", noun + "l" + a + "r"}
    plural = noun + "l" + a + "r"
    pa, pi = two_way(plural), four_way(plural)
    forms |= {plural + pi, plural + pi + "n", plural + pa, plural + "d" + pa, plural + "d" + pa + "n", plural + "l" + pa}
    return forms


def expand(lines):
    phrases = set()
    for line in lines:
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = [p.strip() for p in line.split("|")] + ["", "", ""]
        base, after, before, turkish = parts[0], parts[1].split(), parts[2].split(), parts[3].split()
        for word in after:
            phrases.add(f"{base} {word}")
        for word in before:
            phrases.add(f"{word} {base}")
        for noun in turkish:
            for form in turkish_forms(noun):
                phrases.add(f"{base} {form}")
    return sorted(phrases)


if __name__ == "__main__":
    with open(sys.argv[1], encoding="utf-8") as f:
        print("\n".join(expand(f.readlines())))
