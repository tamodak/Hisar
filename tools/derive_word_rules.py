#!/usr/bin/env python3
"""
Derives the two looser matching rules from the word lists, keeping only words where they are safe.

Whole-word matching misses blocked words that sites glue into names ("r/actuallesbians") and
Turkish words with endings ("femboyun", "pornolar"). Two rules catch those, but both can hit
innocent words ("therapist" contains "rapist", "specialist" contains "cialis"), so each blocked
word gets a rule only if no ordinary word would trip it:

  substring_words.txt  words of 6+ letters matched inside other words
  suffix_stems.txt     words of 4+ letters matched with a Turkish ending attached

"Ordinary word" means a word in the system dictionary (English) that isn't the blocked word with
a plain English inflection (lesbian -> lesbians is still the blocked word). Turkish has no
dictionary here, so Turkish endings are limited to a fixed list of grammatical suffixes.

Usage: derive_word_rules.py <lists dir>
"""
import os
import re
import sys
import unicodedata

# Turkish case, plural and possessive endings, longest first. Must match TURKISH_SUFFIXES in
# WordFilter.kt and PageGuardScript.
TURKISH_SUFFIXES = sorted(
    """
    lerin ların leri ları lere lara lerde larda lerden lardan ler lar
    nın nin nun nün ın in un ün
    yla yle la le
    dan den tan ten da de ta te
    ya ye yı yi yu yü a e ı i u ü
    sı si su sü
    """.split(),
    key=len,
    reverse=True,
)
ENGLISH_INFLECTIONS = ["s", "es", "ed", "ing", "er", "ers", "ly", "y"]
FREQUENCY_LANGUAGES = ["en", "tr", "es", "pt", "de", "fr", "it", "ru", "ar", "id", "nl", "pl"]
FREQUENCY_CACHE = os.path.expanduser("~/.cache/hisar-frequency")
DICTIONARIES = ["/usr/share/dict/american-english", "/usr/share/dict/british-english", "/usr/share/dict/words"]


def tokens(text):
    text = unicodedata.normalize("NFKC", text.lower()).replace("i̇", "i")
    return [w for w in re.split(r"[^\w]+|_", text) if w]


def main(lists_dir):
    entries = []
    for name in ("words.txt", "search_words.txt"):
        with open(os.path.join(lists_dir, name), encoding="utf-8") as f:
            entries += [line.strip() for line in f if line.strip()]
    single = sorted({t[0] for t in map(tokens, entries) if len(t) == 1})

    dictionary = set()
    for path in DICTIONARIES:
        if os.path.exists(path):
            with open(path, encoding="utf-8", errors="ignore") as f:
                dictionary |= {w.strip().lower() for w in f if w.strip().isalpha()}
    # Everyday words of twelve languages (tools/check_collisions.py downloads them): blocked words
    # from those languages must not hide innocent ones either (German "erotisch" is inside
    # "neurotisch").
    for language in FREQUENCY_LANGUAGES:
        path = os.path.join(FREQUENCY_CACHE, language + ".txt")
        if os.path.exists(path):
            with open(path, encoding="utf-8", errors="ignore") as f:
                dictionary |= {line.split(" ")[0].strip().lower() for line in f if line.split(" ")[0].strip().isalpha()}
    if not dictionary:
        sys.exit("No system dictionary found; install wamerican to derive the word rules safely.")

    def inflection_of(word, other):
        return any(other == word + ending for ending in ENGLISH_INFLECTIONS)

    blocked = set(single)

    def innocent(word, other):
        """A dictionary word containing [word] that isn't just it, inflected or blocked itself."""
        return other != word and not inflection_of(word, other) and other not in blocked

    # The dictionary as one string, one word per line: str.find scans it in C, far faster than
    # testing each of its 600 000 words in turn.
    joined = "\n" + "\n".join(sorted(dictionary)) + "\n"

    def containing(word):
        """The dictionary words that contain [word]."""
        found = []
        at = joined.find(word)
        while at >= 0:
            start = joined.rfind("\n", 0, at) + 1
            end = joined.find("\n", at)
            found.append(joined[start:end])
            at = joined.find(word, end)
        return found

    substring = []
    for word in single:
        if len(word) < 6:
            continue
        # A letter stuck to an everyday word ("tgirl" = t + girl) turns up inside harmless names
        # ("@manifestgirls"): such words get no substring rule.
        if word[1:] in dictionary or word[:-1] in dictionary:
            continue
        collisions = [d for d in containing(word) if innocent(word, d)]
        if not collisions:
            substring.append(word)

    stems = []
    for word in single:
        if len(word) < 4:
            continue
        if not any(innocent(word, word + suffix) and (word + suffix) in dictionary for suffix in TURKISH_SUFFIXES):
            stems.append(word)

    if "--report" in sys.argv:
        for word in single:
            if len(word) >= 6 and word not in substring:
                print("no substring rule:", word, sorted(d for d in containing(word) if innocent(word, d))[:5])
    for name, items in (("substring_words.txt", substring), ("suffix_stems.txt", stems)):
        with open(os.path.join(lists_dir, name), "w", encoding="utf-8") as f:
            f.write("\n".join(items) + "\n")
    print(f"{len(substring)} substring words, {len(stems)} suffix stems")


if __name__ == "__main__":
    main(sys.argv[1])  # --report lists words left without the substring rule, and why
