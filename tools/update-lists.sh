#!/usr/bin/env bash
# Refreshes the filter lists embedded in the APK (app/src/main/assets/lists/).
#
# The lists ship inside the app rather than being fetched at runtime: a browser whose filtering
# depends on a download can be defeated by blocking that download. Run this, review the diff, and
# rebuild to update them.
#
# Every list is normalised to one lower-case entry per line, sorted and de-duplicated, so the app
# does no parsing beyond reading lines.
set -euo pipefail

cd "$(dirname "$0")/.."
OUT=app/src/main/assets/lists
mkdir -p "$OUT"

STEVENBLACK=https://raw.githubusercontent.com/StevenBlack/hosts/master
LDNOOBW=https://raw.githubusercontent.com/LDNOOBW/List-of-Dirty-Naughty-Obscene-and-Otherwise-Bad-Words/master

# Hosts-file format ("0.0.0.0 example.com") to bare domains.
hosts_to_domains() {
    grep -E '^0\.0\.0\.0[[:space:]]' \
        | awk '{print tolower($2)}' \
        | grep -vE '^(0\.0\.0\.0|localhost|localhost\.localdomain|local|broadcasthost)$' \
        | sort -u
}

echo "Ads & malware (StevenBlack unified hosts)…"
curl -fsSL "$STEVENBLACK/hosts" | hosts_to_domains > "$OUT/ads.txt"

echo "Harmful sites (StevenBlack porn + gambling extensions)…"
{
    curl -fsSL "$STEVENBLACK/alternates/porn-only/hosts"
    curl -fsSL "$STEVENBLACK/alternates/gambling-only/hosts"
} | hosts_to_domains > "$OUT/harmful.txt"

echo "Blocked words (LDNOOBW en + tr) and local additions…"
{
    curl -fsSL "$LDNOOBW/en"
    echo
    curl -fsSL "$LDNOOBW/tr"
    echo
    # Hisar's own additions, one per line; lines starting with # are comments.
    grep -vE '^[[:space:]]*(#|$)' tools/extra-words.txt || true
} | tr '[:upper:]' '[:lower:]' | sed -E 's/^[[:space:]]+|[[:space:]]+$//g' | grep -v '^$' | sort -u > "$OUT/words.txt"

wc -l "$OUT"/*.txt
