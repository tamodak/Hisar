# Hisar — Progress

Hisar is a safe Android browser: a Chrome look-alike whose filtering can't be turned off. This
file records what has been built, in order, and the decisions behind it, from the first MVP to
now (2026-10-04). Commit hashes point to the work in git where it exists; the first phases were
done before the repository was created and are in `version 0.0.1` / `0.0.2`.

---

## 1. The MVP (WebView)

**Asked for:** an Android browser in Kotlin on WebView, with Killit's API levels (minSdk 26,
targetSdk 36), whose difference is safety: an embedded blocked-word list, embedded harmful-site
lists, enforced SafeSearch and an ad blocker, built from open-source lists.

**Built:**
- Kotlin + Jetpack Compose, Hilt, Room, DataStore, Navigation 3, ViewModels with StateFlow.
- Lists compiled into the APK (`tools/update-lists.sh`): StevenBlack unified hosts (ads,
  trackers, malware), StevenBlack porn + gambling (harmful sites), LDNOOBW English + Turkish
  (words). Nothing is downloaded at runtime, so there is nothing to block.
- `Guard`: every filtering decision, independent of the engine and unit-tested on the JVM —
  blocked domains (subdomains included), blocked words in URLs and search parameters,
  whole-word matching with case folding (Turkish İ/ı), SafeSearch rewriting.
- SafeSearch enforced by rewriting search URLs (Google `safe=active`, Bing `adlt=strict`,
  DuckDuckGo `kp=1`, Yahoo, Brave); engines that can't be forced (Yandex, Ecosia, Startpage) are
  blocked. YouTube Restricted Mode through its PREF cookie.
- `shouldInterceptRequest` as the enforcement point for every request; links to other apps
  (intent:, market:) refused so no unfiltered browser can be opened.

## 2. Chrome's look and features

**Asked for:** Chrome's UI and the features people use; no CAPTCHAs; downloads; pull to
refresh; toolbar hiding on scroll; a search widget; Chrome's new tab page (without Discover);
voice and image search; and, after asking: translate, reader mode, recently closed tabs, swipe
between tabs, tab groups, offline pages and a simple mode.

**Built:**
- Chrome-like toolbar, omnibox with suggestions, tab grid with a "New tab" button, tab groups
  with Chrome's colours and group strip, swipe on the toolbar to switch tabs, menus, page info.
- New tab page like Chrome's: logo, search box with search icon, voice and camera buttons,
  shortcut tiles (popular sites first, then most visited; pin, edit, copy link, delete by long
  press).
- Downloads through DownloadManager (no APK installers), pull to refresh, toolbar that hides on
  scroll, snackbars that dismiss themselves, search widget (search, voice, image).
- Voice search; image search through Google Lens's upload endpoint (camera or photo picker).
- Translate (Google Translate proxy, filtered as the original page), simplified (reader) view,
  recently closed tabs, offline pages, simple mode (one font and size, white on black, font of
  the user's choice).
- History, bookmarks, settings screens; Material You theme with a green fallback.
- Customisation (theme colour, background image) was added and then removed at the user's
  request: "Chrome doesn't have that."
- New logo from the user's sketch: a flat crenellated shield with a search icon centred inside,
  warm-green lines on a dark background.

**CAPTCHAs:** Google answered WebView's user agent ("; wv") and duplicate requests with "unusual
traffic" pages. Fixed by sending the Chrome form of the user agent and rewriting SafeSearch
before the request instead of redirecting after it; WebViews that loaded while detached at 0×0
were also fixed.

## 3. Android best practices

**Asked for:** the whole codebase to follow developer.android.com.

**Done:** a review against the guidance: the ViewModel never holds Views (an activity-scoped
`BrowserController` owns the pages and follows the ViewModel's `pendingLoad`), lifecycle-aware
collection, injected dispatchers, Room with exported schemas and auto-migrations, splash screen
API, edge-to-edge, data extraction and backup rules, network security config, FileProvider,
renderer-crash recovery, and lint cleaned up.

## 4. Nothing blocked leaves the phone

**Reported by the user:** searching "lesbian" showed a block page, and Back revealed all the
results; Back on the block page sometimes did nothing; "lesbian" wasn't even on the list.

**Rule set by the user:** a search with a blocked word, or a blocked site, must never leave the
phone — checked before the request, not shown as a block page afterwards. And block every word
that opens even the slightest door to sexuality (gay, mature, olgun, ifşa, türbanlı, sex
positions, sakso…).

**Done:**
- Every route is checked before anything is sent: address bar, new tab page, voice, tiles,
  history, bookmarks, links, forms, redirects, and Google's suggestions as you type.
- Word lists greatly extended in English and Turkish; "search-only" words (olgun, meme, mature)
  that block searches but don't count against pages; word exceptions for cross-language
  collisions ("am").
- Back skips blocked pages; the block page's own button works.

## 5. Searches inside sites (YouTube, Instagram, X, Reddit)

**Reported:** typing "lesbian" in YouTube's own search box still showed results; repeated
searching then blocked YouTube entirely.

**Researched:** YouTube sends its searches as binary protobuf, so request inspection can't be
relied on; the robust points are the keyboard and the page.

**Built, in layers:**
- Keyboard: `GuardedWebView` wraps the input connection every on-screen keyboard uses; a
  finished blocked word never reaches the page, and Go/Search/Enter with one is swallowed and the
  field cleared. Words are judged when finished, not letter by letter ("analysis" is not "anal",
  Turkish "gayet" is not "gay").
- Page script: blocks submit, Enter and search-button taps with a blocked word, pasting and
  dropping, request bodies with search keys; hides list items (suggestions, results) with
  blocked words, also inside shadow DOM.
- Looser matching where a dictionary shows it is safe: blocked words inside joined names
  ("r/actuallesbians") and with Turkish endings ("femboyun").

**Security review** (requested, no code changes): typed text never leaves the phone or reaches
other sites; findings listed (password fields should be skipped by the keyboard layer, pages can
detect Hisar, the message channel accepted any page's messages, `hisar://back` could be
triggered without a tap).

## 6. Moving to GeckoView — `e4af246`, `1ee16e2`

**Decided by the user:** WebView gives too little control. Hisar must see every request and
response and the DOM before and after rendering, with an image masker
(`~/omer/android/photo-filter`) to come. Move to GeckoView completely; no "This page is blocked"
screen any more — remove what is blocked from the page instead.

**User's choices:** upgrade the toolchain for GeckoView (compileSdk 37.1, Kotlin 2.4.20, AGP
9.4.1, Gradle 9.6.1; minSdk 26 and targetSdk 36 kept); hide elements rather than delete them
from the DOM (deleting breaks React sites); offline pages as single HTML files; bare GeckoView
with our own Compose code rather than Mozilla's Android Components.

**Built:**
- GeckoView 157 with Hisar's filter as a built-in WebExtension (`assets/extensions/hisar`):
  - `background.js` sees every request before it is sent (top-level navigations are asked of the
    app's `Guard` over native messaging; everything else is judged by `guard.js`);
  - `content.js` runs in an isolated world on every page and frame (pages can't see or change it);
  - native messaging between the extension and Kotlin.
- `TabSession` per tab, one `GuardedGeckoView` for display, the keyboard guard moved onto it.
- Everything WebView did itself rebuilt: page dialogs (alert, confirm, select, date, colour,
  sign-in, file picker) as Compose dialogs, find in page, full screen, downloads and Lens with
  the engine's cookies (`CookieStore`), error pages, permissions denied to pages.
- Shared test cases (`guard-cases.json`) check that the Kotlin rules and the extension's
  JavaScript agree; the extension's scripts run in Node inside the unit tests.

## 7. Better site list; licence — `aae5218`

**Measured:** StevenBlack's porn list covered only 54–70% of popular adult sites. HaGeZi NSFW +
oisd NSFW Small cover nearly all of them at the same size, with no mainstream site on them.

**Decided by the user:** switch; all Tamodak projects are free software, pick a licence. Hisar
is **GPL-3.0-or-later** (HaGeZi's lists are GPL); the README credits every source.

## 8. Best practices by measurement, not site by site — `afaf344`

**Asked for:** stop fixing sites one by one; research how others solve the same problems, try
the alternatives, and keep what gives fewest bugs and best performance.

**Researched:** uBlock Origin (HTML filtering, cosmetic filtering), Wingman Jr. and HaramBlur
(image filtering in extensions), Google's documented SafeSearch/YouTube restriction methods,
family DNS resolvers, Mozilla's GeckoView docs.

**Built a measurement harness** (`tools/experiments`): debug builds switch strategies per run;
each site is measured for visible blocked text left after load (walking every shadow root),
the page's own script errors, and time. A test page with 17 hard cases (split words, Turkish
endings, open/closed/nested/late shadow roots, same- and cross-origin frames, text added or
changed later, false positives).

**Winners:**
- Hide the smallest readable block (paragraph, heading, link, list entry), and a card left
  without its title.
- Hide with CSS only: blanking text and rewriting server HTML broke React hydration (26 errors
  per load on bbc.com).
- Find shadow roots by sweeping the page, not by hooking the page's code.
- YouTube restriction by Google's documented `YouTube-Restrict` header (confirmed by YouTube's
  own check page).
- Family DNS not adopted.

## 9. Filtering the data before the page reads it — `87bf5a5`

**Reported:** "tran" on YouTube showed trans videos; a video with a "trans" tag is about it.
Restricted Mode hides every comment, which users don't want.

**Decided by the user:** Restricted Mode off; "trans woman", "trans girl"… on the main list; a
"trans" tag or hashtag means the item is about it; intervene at the data, and prefer approaches
that work on every site before polishing for the most used ones.

**Built:**
- A general data filter (`json-filter.js`) for any site's JSON — fetched responses and the data
  pages embed in their HTML: the smallest list entry that is an item and holds a blocked word,
  #hashtag or tag is removed before the page reads it.
- A response about one blocked thing (a video's player data with its tags) is refused; a page
  whose own title or tags hold one is left, however it arrived (network, a site's service worker
  cache, single-page navigation).
- Entries the server already rendered are left to the page filter (removing them broke
  hydration); a blocked phrase in a plain string list is blanked, not taken as the page's subject.

**Measured:** YouTube "tran": 12 of 12 trans videos removed, 6 innocent kept (trains,
Transformers, "outfit transition"); 12 of 12 trans watch pages left; 12 sites with no leaks and
no new errors.

## 10. Identity words, hashtags and emoji — `0e1d867`

**Reported:** "TRANS MAN GLOWS UP", "…trans?🏳️‍⚧️", "🏳️‍🌈Trans…" slipped; about 90% caught.

**Measured** with a new coverage tool (`tools/experiments/coverage`): real YouTube results for
topic searches and innocent searches, read by hand.

**Built:**
- A combination generator: ambiguous words (trans, drag, bi, queer…) with people words in six
  languages; Turkish nouns with all their endings (bireyin, kadınları…).
- Hashtag compounds: an identity prefix joined with known words or a number (#gaycouple,
  #transman, #pride2024) counts; #transformation, #transporte, #dragon, #gayet don't.
- Emoji symbols (⚧ in every trans flag, the rainbow flag, same-sex couple emoji).
- Allowed phrases for blocked words meaning something else (Gay-Lussac's law).

**Measured:** topic results 348 → 433 of 488 removed; innocent 19 → 0 of 444.

## 11. Lists in many languages; disguised spellings — `da38872`, `1682fe6`

**Asked for:** extensive research into sexual vocabulary online, edge cases, combinations,
emoji and languages — "lists are so important".

**Researched:** dsojevic/profanity-list, LDNOOBW in 23 languages, ooguz's Turkish blacklist,
algospeak glossaries, emoji sexting glossaries, evasion research, word-frequency lists.

**Built:**
- `tools/words/`: per-language and per-topic lists — English additions, algospeak, emoji codes,
  Turkish, Spanish, Portuguese, German, French, Italian, Russian, Arabic, Indonesian,
  Hindi/Urdu, Japanese, Korean, Chinese, Dutch, Polish; sexual terms picked by hand, with what was
  left out and why noted in each file.
- Disguise-aware matching in both the app and the extension: leetspeak, spaced and stretched
  letters, look-alike Cyrillic/Greek letters, small capitals, regional-indicator letters,
  invisible characters; Chinese/Japanese/Korean matched inside text; emoji regardless of skin
  tone; number-only entries ignored.
- The build adds ASCII spellings of Turkish entries and English plurals, unless they are
  everyday words.
- `tools/check_collisions.py`: everyday-word frequencies of twelve languages flag blocked words
  that are common words somewhere (Lund, pedo, domme, negro, semen, erotisch in neurotisch…).

**Measured:** topic results 581 of 728 removed (45 searches, 9 languages); innocent 0 of 864
(48 searches chosen to probe collisions). List build 10+ minutes → 6.5 s.

## 12. Names and titles

**Asked for:** well-known OnlyFans creators and porn performers, porn and erotic films, and
mainstream films and series with sex scenes (Game of Thrones) — "the more we can cover, the
better".

**Built:**
- `tools/words/names.txt`: ~300 performers, OnlyFans/Fansly creators, Japanese AV performers (in
  Latin and Japanese script) and Turkish erotic film stars of the 1970s; full names only.
- `tools/words/titles.txt`: ~520 porn and erotic films, art-house and mainstream films and series
  with sex scenes, their titles in other languages, characters known only from them, adult anime,
  games, studios, magazines, Indian adult streaming apps, leak sites and "spicy" book phrases.
- Both are also added joined, as hashtags (#gameofthrones, #miakhalifa).
- Titles that are everyday words (Euphoria, Girls, Rome, 365 Days, Saltburn) go through
  `combinations.txt` ("euphoria hbo", "euphoria s02e03", "euphoria dizisi") and, where the hashtag
  is unambiguous, `tag-words.txt`.
- Left out: mainstream celebrities who also have OnlyFans, names that are common names or read
  as a sentence ("Autumn Falls"), titles that are ordinary phrases with no safe qualifier.

**Measured:** topic results 611 → 755 of 921 removed; innocent 18 of 1244, all about the blocked
series except Watergate's "Deep Throat" (already blocked as the film's title).

## 13. The picture filter

**Asked for:** a picture filter like photo-filter: every picture masked before it is drawn when it
shows a woman's bare body beyond face and hands, and videos whose thumbnail shows one taken out
like the word filter does. The user chose a grey box from a fast classifier first and the outline
mask later, a classifier that looks for bare female body parts rather than women, women only.
For the MVP: models downloaded inside the app on first start (forced, like games), Play-sized APK.

**Built:**
- `image-filter.js`: every picture held back whole (filterResponseData, as Wingman Jr. does),
  judged by the app, passed or replaced by a grey picture of the same size; `data:`/`blob:`
  pictures hidden until judged. `content.js`: sure thumbnails remove their video's card, other
  covered pictures get the outline mask.
- `vision/`: BodyClassifier (CLIP ViT-B/32 + logistic head), OutlineMasker (photo-filter's
  pipeline without men's bands), ImageFilter, ModelRepository (DownloadManager, SHA-256 checks)
  and a first-start setup screen.
- 5,711 Open Images pictures labelled by hand (`tools/vision/labels.json`); backbones compared
  (README, picture filter measurements).
- Pages wait for the filter after a cold start (the first page used to load without the page script).

**Measured:** S20 FE release build, 32 pictures: median wait 0.2 s, max 0.4 s. Play download
95.5 MB (arm64); models 227 MB downloaded on first start.

---

## Where things stand

**Layers, from the keyboard to the screen:**
1. Keyboard (`GuardedGeckoView`): blocked words never reach a page; submits with them are
   swallowed.
2. Navigation (`Guard.checkNavigation`, before any request): blocked sites and searches never
   leave the phone; SafeSearch enforced.
3. Requests (`background.js`): ads, trackers, harmful sites, blocked words in parameters and
   bodies.
4. Data (`json-filter.js`): entries with blocked words, hashtags or tags removed before the page
   reads them; pages about blocked content left.
5. Page (`content.js`): anything left is hidden as it appears; submits, taps and pastes with
   blocked words stopped.

**Lists:** ~94k adult and gambling domains, ~75k ad/tracker domains, ~10,500 word entries in 15
languages plus emoji, tags and hashtag compounds.

**Known limits:**
- Suggestive content with innocent words (dance videos, kiss scenes, swimwear listings) — only
  an image model can catch it.
- "ifşa" also hides news that "discloses" documents; pages whose title holds a blocked word are
  left, which includes some news articles.
- Filtering adds about 0–0.6 s per page load on the emulator; Instagram's data filtering
  (~450 ms) can be optimised.
- From the security review, still open: skip password fields in the keyboard layer, accept
  extension messages only from the current tab's top page, and strip logs from release builds.
- Instagram and X typing checks on a real phone; the README's architecture section still
  describes WebView in places.

**Next candidates:** channel reputation (hide a channel's other videos when its videos keep
being blocked — works on any site), and the image model (`photo-filter`) on thumbnails and
images, through the same data and response hooks.

**Tools for the work ahead:**
- `tools/update-lists.sh` — rebuild the lists (`--words-only` for words).
- `tools/check_collisions.py` — collision report for any list.
- `tools/experiments/coverage` — what the lists catch and catch by mistake, on real results.
- `tools/experiments/run.py` — strategies compared on real sites and the hard-case page.
