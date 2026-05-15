# Deezer Voice Shim

A tiny Android app that makes Google Assistant / Gemini voice commands play music on Deezer — by impersonating Spotify.

## The problem

On modern Android, saying *"Hey Google, play [song] on Deezer"* fails. The assistant either:

- Replies "I can't play music on Deezer directly" and gives up, or
- Says "Playing [song] on Deezer" but plays an unrelated song / shows an error / does nothing

The failure happens entirely on **Google's side**, before any code in the Deezer app runs. Gemini's voice-routing layer doesn't include Deezer in the list of music services it'll dispatch `PLAY_MEDIA` intents to — likely because Deezer's partner integration with Google Assistant has lapsed or was never updated for the Gemini/Robin pipeline. The same voice command works perfectly for Spotify on the same device, because Google routes those intents to `com.spotify.music`.

Reproducible test: install both apps, voice "play X on Spotify" works; "play X on Deezer" doesn't. The Deezer process is never even invoked — confirmed via `logcat`.

## The workaround

This repo is a small "shim" app that:

1. Installs with the package name `com.spotify.music` (the real Spotify must not be installed)
2. Registers a `MediaBrowserService` and the standard Android-for-Cars metadata, so Google's routing accepts it
3. Listens for the Activity intent Google emits to play a track (`VIEW https://open.spotify.com/...` with structured `artist`/`title`/`album` extras)
4. Hits Deezer's free public Search API to resolve those structured fields into a Deezer track ID
5. Launches Deezer with `deezer://www.deezer.com/track/<id>?autoplay=true`

End-to-end voice → playback is about 750ms (most of which is the API call).

The phrasing you use is *"Play [song] on Spotify"* — and Deezer plays it. UX wart, but the only way to ride Google's routing decision.

### Why this works

- Google's intent dispatch is package-name-based with no signature verification for media routing
- Google supplies structured query fields (`android.intent.extra.artist`, `.title`, `.album`, `.focus`) in the intent — better data than what Deezer's own voice handler ever receives
- Deezer's public Search API needs no auth and returns track IDs for an artist+title query
- Deezer has stable deep-link URIs (`deezer://www.deezer.com/track/<id>`) that auto-play a specific track

## How to build

Prerequisites:

- macOS or Linux with `bash`
- Android SDK `build-tools;34.0.0` and `platforms;android-34` (installed via `sdkmanager`)
- OpenJDK 17+ (the build script assumes a path; edit if yours differs)
- `adb` to install on a device

The included `build.sh` produces an unsigned APK:

```bash
./build.sh
# → build/shim-unsigned.apk
```

Then sign it. The simplest way is [uber-apk-signer](https://github.com/patrickfav/uber-apk-signer):

```bash
java -jar uber-apk-signer.jar --apks build/shim-unsigned.apk --allowResign
```

## How to install

```bash
# 1. Uninstall the real Spotify (cannot coexist with shim — same package name)
adb uninstall com.spotify.music

# 2. Install the signed shim
adb install build/shim-unsigned-aligned-debugSigned.apk

# 3. Make sure Deezer is installed (Play Store version is fine)
adb shell pm path deezer.android.app

# 4. Test
#    On the phone, trigger Gemini and say "Play [song] on Spotify"
#    Deezer should open and start playing
```

To watch what's happening, filter logcat by the `DeezerShim` tag:

```bash
adb logcat -s DeezerShim
```

## How it works internally

```
User says "Play Bohemian Rhapsody by Queen on Spotify"
   ↓
Google Gemini ("Robin") parses → routes PLAY_MEDIA to com.spotify.music
   ↓
Shim's ShimMediaBrowserService.onGetRoot is invoked (accepts connection)
   ↓
Google launches Intent.ACTION_VIEW with
   data = https://open.spotify.com/track/<spotify_track_id>
   extras = {
     android.intent.extra.artist = "Queen"
     android.intent.extra.title  = "Bohemian Rhapsody"
     android.intent.extra.album  = "A Night at the Opera"
     android.intent.extra.focus  = "vnd.android.cursor.item/audio"
     android.intent.extra.START_PLAYBACK = true
   }
   ↓
Shim's MainActivity catches it (intent filter on open.spotify.com)
   ↓
On a background thread, MainActivity hits Deezer's API:
   https://api.deezer.com/search?q=artist:"Queen" track:"Bohemian Rhapsody"&limit=1
   ↓
Parses JSON → gets a Deezer track ID
   ↓
Launches: Intent.ACTION_VIEW
   data = deezer://www.deezer.com/track/<deezer_track_id>?autoplay=true
   package = deezer.android.app
   ↓
Deezer plays the track.
```

## Layout

```
.
├── AndroidManifest.xml      — claims `com.spotify.music`, MediaBrowserService,
│                              car-app declarations, URL intent filter
├── build.sh                 — minimal Android build (aapt2 + javac + d8)
├── res/
│   ├── values/strings.xml
│   └── xml/automotive_app_desc.xml
└── src/com/spotify/music/
    ├── MainActivity.java        — receives Robin's intent, calls API, hands off to Deezer
    ├── ShimMediaBrowserService.java — MBS + MediaSession for Robin to discover
    └── ShimCarAppService.java   — empty stub; only its manifest declaration matters
```

## Limitations

- **Voice phrasing is "Spotify"**, not "Deezer." Cognitively jarring but works.
- **Real Spotify cannot be installed.** Same package name. Pick one.
- **Re-signed APK has no Play Store update path.** Manual re-install for updates.
- **Tested only on the phone (Pixel 6a, Android 16).** Android Auto is a different routing pipeline; the shim probably doesn't play in the car yet — audio routing to the car speakers requires the music app to *own* the active media session and stream audio bytes, which would mean implementing a full Deezer player from scratch. The current shim hands off to the Deezer app, which is fine on a phone but not in AA.
- **Edge cases:** if Deezer's catalog doesn't have an exact match for an obscure query, the shim falls through to opening Deezer's search screen rather than playing directly.
- **No artist / no title:** if Google only supplies a partial query, the API call may not return useful results.
- **Fragile contract:** if Google ever tightens Robin to verify package signatures, or changes the intent shape, this breaks silently.

## Legal / use

This is for personal use only. It works around a routing decision in Google's voice assistant by claiming a package name that belongs to Spotify AB. It doesn't ship any Spotify or Deezer code; it doesn't modify either app; it doesn't bypass any DRM or paywall. But you should not redistribute the resulting APK or publish it under Spotify's name.

## Acknowledgements

- Built on top of Deezer's free public Search API
- Google for shipping enough structured query metadata that this is even possible
