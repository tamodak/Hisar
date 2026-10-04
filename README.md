# Hisar

**A safe browser for Android.** Hisar ("fortress" in Turkish) looks and works like Chrome, with
filtering built in that cannot be switched off: no settings, no allowlist, no "disable" button.
It is meant for families, schools and anyone who wants to browse without running into sexual
content.

- Adult and gambling sites, ads and trackers are blocked before any request leaves the phone.
- Searches and text with sexual words are stopped, in 15 languages, in the address bar and inside
  sites (YouTube, Instagram, X, Reddit…).
- **Pictures are checked by AI before they are drawn.** A picture showing a woman's bare body or
  nudity is covered; a video whose thumbnail shows one is taken out of the list. Everything runs
  on the phone.
- SafeSearch is forced on every search engine that supports it.

Hisar is free software (GPL-3.0-or-later), built on Mozilla's
[GeckoView](https://firefox-source-docs.mozilla.org/mobile/android/geckoview/), the engine of
Firefox for Android.

## How it protects

Hisar filters in layers, from the keyboard to the screen. Each layer catches what the one before
it cannot see.

| Layer | What it does | Where |
|---|---|---|
| **Keyboard** | A finished word on the block list never reaches the page; Go/Enter with one is swallowed and the field cleared. Words are judged when finished, so "analysis" is not "anal". | `web/GuardedGeckoView.kt` |
| **Navigation** | Blocked sites and searches are refused before the request is made, wherever they come from: address bar, voice, links, forms, redirects. SafeSearch is enforced (Google, Bing, DuckDuckGo, Yahoo, Brave); engines whose SafeSearch can't be forced (Yandex, Ecosia, Startpage) are blocked. | `filter/Guard.kt` |
| **Requests** | Every request any page, frame or service worker makes: ~94k adult and gambling domains, ~75k ad, tracker and malware domains, and blocked words in search parameters and request bodies. | `assets/extensions/hisar/background.js`, `guard.js` |
| **Data** | JSON that pages read (search results, feeds, comments, a video's player data), from the network or embedded in the HTML: entries with blocked words, hashtags or tags are removed before the page sees them. A video tagged with a blocked topic doesn't play. | `json-filter.js` |
| **Pictures** | Every picture is held back until an AI model has judged it (below). | `image-filter.js`, `vision/` |
| **Page** | Anything left is hidden as it appears, also inside shadow DOM and frames; submits, taps and pastes with blocked words are stopped. | `content.js` |

The filter is a WebExtension built into the app (isolated from pages, which can neither see nor
change it), talking to the Kotlin side through native messaging. Nothing that is blocked is
fetched first and hidden afterwards: a blocked search or site never leaves the phone.

### The picture filter

Every picture a page loads (`<img>`, CSS backgrounds, `<picture>`, video posters, pictures that
pages or their service workers fetch, and pictures a page holds itself as `data:` or `blob:`) is
held back whole until it has been judged, as the [Wingman Jr.](https://github.com/wingman-jr-addon/wingman_jr)
Firefox extension does. Nothing unjudged is ever drawn, not even for a frame.

1. **Fast classifier** (tens of milliseconds a picture on a Galaxy S20 FE). OpenAI's CLIP ViT-B/32 image
   encoder and a logistic head trained on 5,711 hand-labelled
   [Open Images](https://storage.googleapis.com/openimages/web/index.html) pictures. It looks for
   *a woman's or girl's bare body beyond her face and hands* (arms, shoulders, chest, belly, back,
   legs, feet) and nudity of anyone. It looks for the bare body, not for a woman: a bare leg or
   foot alone counts, since who it belongs to often can't be told.
   - Safe: the original picture is shown.
   - Not safe: a grey picture of the same size is shown instead, so the layout stays.
2. **Video thumbnails**: when the classifier is sure, the video's card is taken out of the list,
   as a blocked word in its title would.
3. **Outline mask** (1–3 s, in the background): other covered pictures are replaced by a version
   where only the people are painted black, following their outlines, and the rest is shown.
   This is the pipeline of the Photo Filter project: EfficientDet-Lite2 finds people, CLIP tells
   women from men, CLIPSeg maps body parts, MobileSAM outlines them, MoveNet guides the outlines.

The models (about 230 MB) are not in the APK. Hisar downloads them on first start, before the
browser opens, from this repository's [GitHub release](https://github.com/tamodak/Hisar/releases)
`models-1`, checks each file's SHA-256, and keeps them in app storage that is excluded from
backups. No picture ever leaves the phone.

How the classifier was chosen and how well it does: [Picture filter measurements](#picture-filter-measurements).

### Word lists

About 10,500 entries in 15 languages (English, Turkish, Spanish, Portuguese, German, French,
Italian, Russian, Arabic, Indonesian, Hindi/Urdu, Japanese, Korean, Chinese, Dutch, Polish), plus
algospeak, emoji codes, names of adult performers and titles of adult films and series with sex
scenes. Matching sees through leetspeak, spaced and stretched letters, look-alike Cyrillic and
Greek letters and invisible characters. Words with an innocent everyday meaning stop searches but
don't count against pages. Every change is checked against everyday-word frequency lists of 12
languages (`tools/check_collisions.py`) and measured on real YouTube results
(`tools/experiments/coverage`).

Edit `tools/words/*.txt` and rebuild with `tools/update-lists.sh --words-only`.

## Browser features

Chrome's design and the features people use most: toolbar that hides on scroll, swipe between
tabs, pull to refresh, new tab page with shortcut tiles (no Discover feed), address bar with
suggestions, voice search, image search (Google Lens), tabs with a grid and tab groups, recent
tabs, bookmarks, history, downloads, offline pages, translate, simplified (reader) view, find in
page, desktop site, a home-screen search widget, a simple mode (one font, white on black), and
English and Turkish.

No incognito mode, deliberately: it would hide browsing from whoever set Hisar up.

## Build

Requirements: JDK 17+, Android SDK with platform 37.1. Toolchain: AGP 9.4, Gradle 9.6, Kotlin
2.4. minSdk 26 (Android 8.0), targetSdk 36.

```bash
./gradlew assembleDebug            # debug APKs, one per CPU type (app/build/outputs/apk/debug)
./gradlew installDebug             # install on the connected device
./gradlew :app:testDebugUnitTest   # filter logic and the extension's scripts (needs node)
./gradlew lintDebug
./gradlew bundleRelease            # Play Store bundle (app/build/outputs/bundle/release)
```

Release builds are signed when a `keystore.properties` file in the project root points at your
key (git ignores it; keep the key itself outside the project):

```properties
storeFile=/home/you/keys/hisar-upload.jks
storePassword=…
keyAlias=upload
keyPassword=…
```

Without the file, release builds come out unsigned. For Google Play this is the *upload* key;
Play App Signing keeps the key that signs what phones install.

### The picture filter's models

The app downloads them from `https://github.com/tamodak/Hisar/releases/download/models-N/`, where
`N` is `hisar.modelsVersion` in `gradle.properties`.

```bash
tools/vision/package_models.sh 1   # dist/models-1/ + app/src/main/assets/vision-models.json
# then attach every file in dist/models-1/ to a GitHub release with the tag models-1
```

`package_models.sh` takes the outline models from the Photo Filter project (its `tools/` scripts
convert them) and the fast classifier's head from `tools/vision/body_head.bin`. To test a build
against a local copy: `-Phisar.modelsUrl=http://localhost:8767/`, serve `dist/models-1` there and
`adb reverse tcp:8767 tcp:8767`.

### Size

| | arm64 |
|---|---|
| APK on disk (native libraries stored uncompressed, as Android recommends) | ~200 MB |
| Play Store download (compressed) | ~95 MB |
| Models, downloaded on first start | ~230 MB |

Almost all of the app is GeckoView's engine (`libxul.so`, 150 MB uncompressed). The Play Store
serves each phone only its own CPU type's libraries from the bundle.

## Architecture

Follows Android's [guide to app architecture](https://developer.android.com/topic/architecture)
and its [recommendations](https://developer.android.com/topic/architecture/recommendations).

```
app/src/main/java/org/tamodak/hisar/
  data/        repositories over Room (history, bookmarks, tabs, downloads…) and DataStore (settings)
  filter/      Guard: every filtering decision, pure Kotlin, unit-tested
  vision/      picture filter: BodyClassifier, OutlineMasker and its models, ImageFilter, ModelRepository
  web/         GeckoView: the engine and its extension (GeckoEngine), tabs (TabSession), keyboard guard
  ui/          Compose screens, one Hilt ViewModel each; BrowserController owns the tabs' views
  di/          Hilt modules
app/src/main/assets/extensions/hisar/
  background.js  requests, responses, pictures      content.js   the page itself
  guard.js       domain and word rules               json-filter.js  data before pages read it
  image-filter.js  holding and judging pictures      matcher.js   disguise-aware word matching
tools/
  words/, update-lists.sh, check_collisions.py   word and domain lists
  experiments/   strategies compared on real sites and test pages (run.py, testpage/)
  vision/        picture filter data, labels, model comparison and packaging
```

- Repositories expose `Flow`s and `suspend` functions on injected dispatchers; ViewModels expose
  `StateFlow` UI state, collected with `collectAsStateWithLifecycle`; screens are stateless.
- A ViewModel never holds a View: the GeckoViews live in the activity-scoped `BrowserController`.
- Coroutines only, no raw threads. The image models run on the CPU through LiteRT's
  CompiledModel API (fp16 where the phone supports it); the outline models are released when the
  system runs short of memory.
- After a cold start, pages wait until the extension has its lists and page script in place.

## Picture filter measurements

Tools and data: `tools/vision/` (labels in `labels.json`, by Open Images ID).

**Labels.** 3,711 Open Images pictures sampled across women in swimwear, shorts and dresses, bare
legs and feet without a visible person, men, and pictures without people; then 2,000 more chosen
where the first classifier was least sure. Each was labelled by hand against the rule above;
unsure ones count as unsafe.

**Backbones compared** (frozen encoder + logistic head, 5-fold cross-validation, false covers of
safe pictures when 95 % of unsafe ones are caught, on all 5,711 labels, which are weighted towards
hard cases):

| Encoder | Time (desktop CPU) | False covers |
|---|---|---|
| OpenAI CLIP ViT-B/32 | 37 ms | 40 % |
| LAION CLIP ViT-B/32 | 37 ms | 42 % |
| SigLIP B/16 | 150 ms | 37 % |
| SigLIP 2 B/16 | 150 ms | 38 % |

A small neural head did worse than the linear one (it overfits); padding the whole picture into
the square did worse than the centre square; adding per-person crops gained little. OpenAI's
B/32 was kept for its speed: about 30 ms on a Galaxy S20 FE, and it is already the model the
outline mask uses.

**Thresholds.** A picture is covered from the score that catches 97 % of the unsafe labelled
pictures (it also covers half of the safe ones in this hard-weighted set); a video is removed only
from the score where 95 % of the pictures above it are unsafe (57 % of unsafe pictures reach it).
Pictures covered by caution get the outline mask, so a false cover costs a few seconds of grey,
not the picture.

**On a phone** (Galaxy S20 FE, release build, test page `tools/experiments/testpage/images.html`):
on a page of 32 pictures, each waited a median of 0.2 s for its verdict (at most 0.4 s). 28 of 36
cases as expected; the others are safe pictures covered by caution, which the outline mask then
shows with only the people covered.

## Known limits

- The fast classifier misses about one unsafe picture in ten among hard cases (swimwear far
  away, partial views, drawings), and covers many safe pictures of women by caution. The outline
  mask then shows those again with only the people covered.
- Animated GIFs and WebP are judged by their first frame. Videos themselves are not analysed,
  only their thumbnails and posters.
- Pictures drawn into a `<canvas>` are not judged.
- Hiding a video's card relies on recognising video cards (links to watch pages, durations,
  known thumbnail hosts); unusual layouts keep the card with a covered thumbnail.
- Suggestive content with innocent words and no bare skin (dances, kisses) can get through.
- Nothing stops the user from installing another browser. Pair Hisar with a device-level control
  such as Killit.

## License

Hisar is free software: you can redistribute it and/or modify it under the terms of the
[GNU General Public License](LICENSE) as published by the Free Software Foundation, either
version 3 of the License, or (at your option) any later version.

It includes or downloads:

| Part | Source | License |
|---|---|---|
| Browser engine | [GeckoView](https://firefox-source-docs.mozilla.org/mobile/android/geckoview/) (Mozilla) | MPL-2.0 |
| Model runtime | [LiteRT](https://ai.google.dev/edge/litert) (Google) | Apache-2.0 |
| Picture classifier encoder | [OpenAI CLIP ViT-B/32](https://github.com/openai/CLIP) | MIT |
| Person detector | EfficientDet-Lite2 (TensorFlow) | Apache-2.0 |
| Pose | MoveNet SinglePose Lightning (TensorFlow) | Apache-2.0 |
| Body-part maps | [CLIPSeg](https://huggingface.co/CIDAS/clipseg-rd64-refined) | Apache-2.0 (per its model card) |
| Outlines | [MobileSAM](https://github.com/ChaoningZhang/MobileSAM) | Apache-2.0 |
| Training pictures (not shipped) | [Open Images](https://storage.googleapis.com/openimages/web/index.html) | CC BY 2.0 |
| Adult-site list | [HaGeZi DNS blocklists](https://github.com/hagezi/dns-blocklists), NSFW | GPL-3.0 |
| Adult-site list | [oisd](https://oisd.nl/), NSFW Small | free to use, credited here |
| Ad, tracker and gambling lists | [StevenBlack/hosts](https://github.com/StevenBlack/hosts) | MIT |
| Word lists (part) | [LDNOOBW](https://github.com/LDNOOBW/List-of-Dirty-Naughty-Obscene-and-Otherwise-Bad-Words) | CC BY 4.0 |
| Word lists (part, reviewed by hand) | [dsojevic/profanity-list](https://github.com/dsojevic/profanity-list) | MIT |
| Turkish words (part, reviewed by hand) | [ooguz/turkce-kufur-karaliste](https://github.com/ooguz/turkce-kufur-karaliste) | CC BY-SA 4.0 |
| Collision checks (build tool only) | [hermitdave/FrequencyWords](https://github.com/hermitdave/FrequencyWords) | CC BY-SA 4.0 |

Logo: a fortress shield with a search glass at its centre (`art/hisar-icon.svg`).
