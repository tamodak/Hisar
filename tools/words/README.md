# Word lists by language and topic

Merged into the main list by `tools/update-lists.sh`, alongside LDNOOBW (en, tr) and
`tools/extra-words.txt`. One entry per line, `#` for comments; matched as whole words (phrases
across punctuation), case-insensitively, with the disguises Readings undoes. Chinese, Japanese
and Korean entries are matched inside text, since those scripts don't put spaces between words.

`names.txt` (adult performers, OnlyFans creators, Japanese AV and Turkish erotic film stars) and
`titles.txt` (porn and erotic films, mainstream films and series with sex scenes, adult anime,
games, studios and sites) are added joined as well, as they are written in hashtags
(#gameofthrones). Titles that are everyday words on their own (Euphoria, Rome, 365 Days) are in
`tools/combinations.txt`, blocked only next to a word that makes them the title.

Every file is checked for collisions with everyday words of twelve languages by
`tools/check_collisions.py`: a word that is common in any of them (Portuguese "comer", to eat;
Spanish "coger", to take) needs a phrase instead, or goes to search-only-words.txt.

Sources: LDNOOBW (sexual terms only, picked by hand), dsojevic/profanity-list (sexual, lgbtq and
shock tags), algospeak glossaries (Wikipedia "Algospeak", Know Your Meme), emoji glossaries
(Emojipedia NSFW, crissov's sexting glossaries), and measured gaps on real search results
(tools/experiments/coverage).
