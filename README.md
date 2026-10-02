# Hisar

A safe Android browser built on WebView. Filtering is built in and cannot be switched off: no
settings, allowlist or "disable" button.

| Protection | How |
|---|---|
| **Harmful sites** | ~83k porn and gambling domains ([StevenBlack](https://github.com/StevenBlack/hosts) extensions). Subdomains included. Blocked as pages *and* as images, frames and scripts. |
| **Ad blocker** | ~75k ad, tracker and malware domains (StevenBlack unified hosts), dropped at the network request. |
| **Blocked words** | [LDNOOBW](https://github.com/LDNOOBW/List-of-Dirty-Naughty-Obscene-and-Otherwise-Bad-Words) en + tr, plus Hisar's own English and Turkish lists of sexuality-related words (`tools/extra-words.txt`). Words with an innocent everyday meaning ("olgun", "meme", "mature") are in `tools/search-only-words.txt`: they stop searches but don't count against pages. `tools/word-exceptions.txt` drops words that collide with another language ("am"). Rebuild with `tools/update-lists.sh --words-only`. Matching is whole-word only and folds case. |
| **Enforced SafeSearch** | Google `safe=active`, Bing `adlt=strict`, DuckDuckGo `kp=1`, Yahoo `vm=r`, Brave `safesearch=strict`, forced on every search and overriding `safe=off`. Yandex, Ecosia and Startpage are blocked because Hisar cannot force their SafeSearch from the URL. YouTube Restricted Mode is set through its `PREF` cookie. |
| **Hardening** | Google Safe Browsing on. No file access. No popups. Downloads are filtered: nothing from a blocked site or with a blocked file name, and no `.apk` installers. Links to other apps (`intent:`, `market:`, custom schemes) are refused so they can't open an unfiltered browser. Service-worker requests are filtered too. Can be set as the default browser. |

## Browser features

Chrome's design and the Chrome features people use most:

- **Toolbar**: Home, address bar, tab counter, ⋮ menu. It slides away when you scroll down and
  comes back when you scroll up. **Swipe it sideways** to switch tabs. **Pull down** at the top of
  a page to reload.
- **New tab page** like Chrome's, without Discover: the logo, a search box (search icon, then
  **voice search** and **image search** buttons) and eight shortcut tiles. Tiles start with popular
  sites (YouTube, Instagram, Amazon, …), give way to your most-visited sites as you browse, and
  any tile you edit is pinned first. Long-press a tile: open in new tab, edit, copy link, delete
  (deleting hides that site). Icons of sites not yet visited are fetched from the site, only if
  the filters allow it. The toolbar's address bar stays hidden until the box scrolls away.
- **Address bar**: selects the whole address on tap, and shows search terms on results pages.
  Suggestions come from your search engine (filtered for blocked words), bookmarks and history.
  Mic and camera buttons appear while it's empty.
- **Voice search**: Android's speech recogniser. What it hears becomes a normal search, so every
  filter applies.
- **Image search**: take a photo or pick one (or long-press an image → "Search image"). It's
  uploaded to Google Lens, whose results are a normal Google results page with SafeSearch
  enforced.
- **Tabs**: a grid of page snapshots with a **New tab** button. Undo after closing, tabs restored on
  restart, and **Recent tabs** to reopen ones closed earlier. **Tab groups**: long-press a tab to
  group it. Groups have a name and colour, show as one card, and give their tabs Chrome's bottom
  strip. Links can "Open in new tab in group".
- **⋮ menu**: forward / bookmark / **download page** / page info / reload; new tab, history,
  downloads, recent tabs, delete browsing data, bookmarks; share, find in page, **translate**,
  **simplified view**, add to Home screen, desktop site, **simple mode**; settings.
- **Translate** uses Google Translate's page proxy. Proxied hosts (`*.translate.goog`) are mapped
  back to the real site, so a blocked site stays blocked when translated.
- **Simplified view**: the article alone, in a clean reading layout.
- **Offline pages**: "Download page" saves an MHTML archive. Saved pages are listed under
  Downloads → Pages and open offline, through Hisar's own non-exported FileProvider.
- **Downloads** go through Android's DownloadManager (notification, shared Downloads folder) and
  carry the page's cookies. Downloads screen with progress, open, delete.
- **Simple mode**: every page in one font, one size, white on black (links stay underlined). The
  app turns black and white too. Pick the font in Settings. Otherwise the app follows the system
  theme with Material You colours, as Chrome does.
- **Home-screen search widget** (as in Chrome): the box, the mic and the camera each open Hisar.
- Page info, long-press link/image menu, history grouped by day with search, bookmarks, delete
  browsing data, full-screen video, file uploads, popup blocking, English and Turkish.

No incognito mode, deliberately: it would hide browsing from whoever set Hisar up.

The lists are compiled into the APK (`app/src/main/assets/lists/`), so there is no download to
block. To refresh them: `tools/update-lists.sh`, review the diff, rebuild.

## Logo

A fortress shield ("hisar" means fortress) with a search glass at its centre, as flat warm-green
line art (#86B83E) on deep slate (#172129). Source: `art/hisar-icon.svg`; store icon:
`art/hisar-icon-512.png`; in the app: `res/drawable/ic_launcher_foreground.xml` (adaptive icon,
with a monochrome layer for themed icons) and `ic_hisar_logo.xml`.

## Build

Same toolchain and API levels as Killit (AGP 9.3.1, Gradle 9.5, minSdk 26, targetSdk 36).

```bash
./gradlew assembleDebug            # build
./gradlew installDebug             # install on the connected device
./gradlew :app:testDebugUnitTest   # JVM tests for the filter logic
```

`adb logcat | grep Hisar` shows what was blocked and why.

## Nothing blocked leaves the phone

A search with a blocked word, or a blocked site, is stopped before the request is made, wherever
it comes from:

- **Typed, spoken, or tapped in Hisar** (address bar, new tab page, voice, tiles, history,
  bookmarks, links opened in new tabs): not opened at all; a message says why and the tab stays put.
- **A page's links, forms and redirects:** cancelled in `shouldOverrideUrlLoading`.
- **A page's background requests** (Google's suggestions as you type, instant results):
  refused in `shouldInterceptRequest` when a search parameter (`q=`, `query=`, …) carries a
  blocked word.
- **Text typed into any site** (YouTube, Instagram, X, Reddit…, which send searches as binary
  or JSON bodies no request filter can read reliably): two layers judge the words before the page
  can send them.
  - *Keyboard* (`web/GuardedWebView.kt`): the WebView's `InputConnection` is wrapped, so every
    keyboard's committed words and its Go/Search/Enter are checked first. A finished blocked word
    never reaches the page; a submit with one is swallowed and the field cleared. Words are
    judged when finished, not letter by letter ("analysis" isn't "anal").
  - *Page* (`web/PageGuardScript.kt`, injected before any page script, in every frame and shadow
    root): blocks submit, Enter and search-button taps on fields holding a blocked word, pasted
    or dropped text, and `fetch`/XHR bodies whose search keys (`query`, `q`, …) carry one; and
    hides list items (suggestions, results, posts) whose text contains a blocked word.
- **Hisar's own suggestions:** typed text is checked before it is sent to the search engine.

Matching is whole-word, plus two looser rules derived by `tools/derive_word_rules.py` where a
dictionary shows they can't hit an innocent word: long words inside joined names
("r/actuallesbians") and Turkish endings ("femboyun").

The first letters of a word do reach the site while typing (for its suggestions); explicit
suggestions those return are hidden by the page layer.

Pages blocked for their content had to be loaded to be judged. Their search is remembered by
query, so the same search is refused under any address, and Back (the system's, and the block
page's own button) skips every blocked entry.

## Search engines and CAPTCHAs

Google answers WebView's own user agent (`…; wv) … Version/4.0 Chrome/…`) with "unusual traffic"
CAPTCHAs, so Hisar sends the Chrome form of it (`HisarWebView.mobileUserAgent`), as other
WebView-based browsers do. SafeSearch rewrites also happen *before* a request leaves the app
(`shouldOverrideUrlLoading`) rather than as a redirect after it, because an aborted request
followed straight away by an identical one also triggers the CAPTCHA.

## Architecture

Follows Android's [guide to app architecture](https://developer.android.com/topic/architecture)
and its [recommendations](https://developer.android.com/topic/architecture/recommendations).

- **Data layer** (`data/`): repositories over Room (history, bookmarks, recently closed tabs,
  saved pages, downloads, the open-tab session) and Preferences DataStore (settings). They expose
  `Flow`s and `suspend` functions and run on injected dispatchers, never on the main thread.
  `FilterRepository` loads the embedded lists once, in the application scope.
- **Filtering** (`filter/`): pure Kotlin, no Android, unit-tested. `Guard` makes every decision.
- **UI layer** (`ui/`): one `@HiltViewModel` per screen exposing `StateFlow` UI state, collected
  with `collectAsStateWithLifecycle`. Screens take state and callbacks. Navigation 3 handles the
  screens; one-off messages are UI state that the screen reports back once shown.
- **WebViews**: a ViewModel must never hold a View, so the tabs' WebViews live in
  `BrowserController`, an activity-scoped UI state holder. WebView events go to
  `BrowserViewModel`, which updates state; a tab's `pendingLoad` is how the ViewModel asks the
  controller to load something. `web/` holds the WebView plumbing; `HisarWebViewClient.shouldInterceptRequest`
  is the enforcement point for every request.
- **DI**: Hilt. **Async**: coroutines and Flow only.
- **WebView guidance**: renderer crashes handled (`onRenderProcessGone` replaces the WebView);
  Safe Browsing on in the manifest; no file access; saved pages through a non-exported
  `FileProvider` (WebView renders MHTML only from `file:`/`content:`, so the https
  `WebViewAssetLoader` can't serve them); a day/night app theme sets the pages'
  `prefers-color-scheme`.
- **Platform**: SplashScreen API, edge-to-edge, network security config, backup and
  device-transfer exclusion, photo picker (no storage permission), 48 dp touch targets.
- `./gradlew lintDebug` passes with no errors. The remaining warnings are newer library versions
  that require compileSdk 37 (Hisar matches Killit's 36) and a verified false positive.

## Known MVP limits

- No image/video content analysis. Explicit images on otherwise clean sites get through.
- The page-text threshold (6 matches, or 3 different words) is strict: news front pages that cover
  crime can be blocked. Tune `Guard.PAGE_*_THRESHOLD`.
- `blob:` and `data:` downloads (files a page builds in JavaScript) aren't supported yet.
- The toolbar only hides on pages that scroll the document itself, not on pages that scroll an
  inner panel.
- Chromium may still look up a blocked site's name in DNS (link prefetching) though no request
  is made to it; WebView offers no way to turn that off.
- The first letters of a word are sent before it becomes a blocked word ("p", "po", "por").
- The page-text scan runs once the page has loaded, so a page can show briefly before it is
  blocked.
- Domain-based ad blocking only: no EasyList cosmetic rules, so empty ad slots can remain.
- YouTube Restricted Mode relies on a cookie and is best-effort.
- Nothing stops the user from installing or using another browser. Pair Hisar with Killit to
  block the others.
- The lists take roughly 1–3 s to load on a cold start. Requests wait for them rather than going
  through unfiltered.
