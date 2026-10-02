#!/usr/bin/env bash
# Refreshes the filter lists embedded in the APK (app/src/main/assets/extensions/hisar/lists/),
# where both the app and its built-in browser extension read them.
#
# The lists ship inside the app rather than being fetched at runtime: a browser whose filtering
# depends on a download can be defeated by blocking that download. Run this, review the diff, and
# rebuild to update them.
#
# Every list is normalised to one lower-case entry per line, sorted and de-duplicated, so the app
# does no parsing beyond reading lines.
set -euo pipefail

# --words-only rebuilds just the word lists (after editing extra-words.txt or
# search-only-words.txt) without refreshing the domain lists.
WORDS_ONLY=false
[ "${1:-}" = "--words-only" ] && WORDS_ONLY=true

cd "$(dirname "$0")/.."
OUT=app/src/main/assets/extensions/hisar/lists
mkdir -p "$OUT"

STEVENBLACK=https://raw.githubusercontent.com/StevenBlack/hosts/master
HAGEZI=https://raw.githubusercontent.com/hagezi/dns-blocklists/main
LDNOOBW=https://raw.githubusercontent.com/LDNOOBW/List-of-Dirty-Naughty-Obscene-and-Otherwise-Bad-Words/master

# Hosts-file format ("0.0.0.0 example.com") to bare domains.
hosts_to_domains() {
    grep -E '^0\.0\.0\.0[[:space:]]' \
        | awk '{print tolower($2)}' \
        | grep -vE '^(0\.0\.0\.0|localhost|localhost\.localdomain|local|broadcasthost)$' \
        | sort -u
}

# Plain domain lists (one per line, # comments) to bare domains.
plain_domains() {
    grep -vE '^[[:space:]]*(#|$)' | tr '[:upper:]' '[:lower:]' | sed -E 's/^[[:space:]]+|[[:space:]]+$//; s/^\*\.//' | grep -v '^$' | sort -u
}

normalize_words() {
    grep -vE '^[[:space:]]*(#|$)' | tr '[:upper:]' '[:lower:]' | sed -E 's/^[[:space:]]+|[[:space:]]+$//g' | grep -v '^$' | sort -u
}

# Drops tools/word-exceptions.txt: words that collide with everyday words in another language.
drop_exceptions() {
    grep -vxF -f <(normalize_words < tools/word-exceptions.txt)
}

if ! $WORDS_ONLY; then
echo "Ads & malware (StevenBlack unified hosts)…"
curl -fsSL "$STEVENBLACK/hosts" | hosts_to_domains > "$OUT/ads.txt"

# Adult sites: HaGeZi's NSFW list and oisd's NSFW Small (its NSFW domains among the Tranco top
# million). Measured against the popular adult domains the main lists agree on, together they
# cover nearly all of them at about the size StevenBlack's porn list had, which covered 54-70%.
# Gambling: StevenBlack's gambling extension.
echo "Harmful sites (HaGeZi NSFW + oisd NSFW Small + StevenBlack gambling)…"
{
    curl -fsSL "$HAGEZI/wildcard/nsfw-onlydomains.txt" | plain_domains
    curl -fsSL "https://nsfw-small.oisd.nl/domainswild2" | plain_domains
    curl -fsSL "$STEVENBLACK/alternates/gambling-only/hosts" | hosts_to_domains
} | sort -u > "$OUT/harmful.txt"
fi

echo "Blocked words (LDNOOBW en + tr) and local additions…"
{
    curl -fsSL "$LDNOOBW/en"
    echo
    curl -fsSL "$LDNOOBW/tr"
    echo
    # Hisar's own additions, one per line; lines starting with # are comments.
    grep -vE '^[[:space:]]*(#|$)' tools/extra-words.txt || true
} | normalize_words | drop_exceptions > "$OUT/words.txt"

echo "Search-only words (blocked in searches, not counted on pages)…"
normalize_words < tools/search-only-words.txt | drop_exceptions > "$OUT/search_words.txt"

echo "Looser matching rules, where a dictionary shows they are safe…"
python3 tools/derive_word_rules.py "$OUT"

wc -l "$OUT"/*.txt
