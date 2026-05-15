# Deezer Voice Shim

A small Android app that makes Google Gemini's voice routing play your music on Deezer — by impersonating Spotify's package name, intercepting the voice intent, and playing the resolved track via a custom Deezer streaming player.

Works on:
- **Phone**: "Hey Google, play X on Spotify" → audio plays via Deezer
- **Android Auto**: same voice command in the car → audio plays through car speakers via Deezer

## The problem

On modern Android, saying *"Hey Google, play X on Deezer"* fails. Gemini's voice-routing layer doesn't dispatch `PLAY_MEDIA` intents to Deezer directly — likely because Deezer's partner integration with Google Assistant has lapsed or was never updated for the Gemini/Robin pipeline. The same voice command works perfectly for Spotify on the same device. The Deezer app's voice handler code is never even invoked — confirmed by `logcat` showing zero Deezer process activity.

Manifest-level changes (declaring `androidx.car.app.category.MEDIA`, adding `MUSIC_PLAYER` intent filters, etc.) move Deezer from "completely ignored" to "noticed by Cast scanning," but don't fix the routing decision. The actual gate is in Google's voice-fulfillment layer and isn't patchable from the APK side.

## The workaround

A small "shim" app that:

1. Installs with package name `com.spotify.music` (real Spotify must not be installed alongside)
2. Registers a `MediaBrowserService` + `MediaSession` + the modern Android-for-Cars metadata, so Gemini accepts it as a valid voice fulfillment target
3. Listens for both:
   - **Phone path**: `Intent.ACTION_VIEW` on `https://open.spotify.com/*` URLs — handled in `MainActivity`
   - **Android Auto path**: `MediaSession.Callback.onPlayFromUri` / `onPlayFromSearch` — handled in `ShimMediaBrowserService`
4. Either path receives structured extras from Gemini (`android.intent.extra.artist`, `.title`, `.album`, `.focus`)
5. Phone path: launches Deezer with a deep-link search URL (so Deezer's own UI handles playback)
6. Android Auto path: runs the full custom player — Deezer auth → search resolve → HTTP stream → Blowfish-CBC chunk decryption on the fly → `MediaPlayer` + `MediaSession` + `PlaybackState` so AA gets real playback signals

End-to-end voice command → audio playing in AA: ~4 seconds (most of which is Deezer's API roundtrips).

## How the AA path works

```
User says "Play music by Doja Cat on Spotify" into AA's mic
   ↓
Gemini's NLU resolves → dispatches playFromUri to com.spotify.music
   ↓
ShimMediaBrowserService.onGetRoot accepts connection
   ↓
MediaSession.Callback.onPlayFromUri(
   uri = https://open.spotify.com/artist/<spotify_id>,
   extras = {
     android.intent.extra.artist = "Doja Cat",
     android.intent.extra.focus  = "vnd.android.cursor.item/artist",
     ...
   })
   ↓
Read structured extras (the Spotify ID itself is useless — different catalog)
   ↓
DeezerClient.authenticate()  via ARL cookie + cookie-jar session
   ↓
DeezerClient.searchTrack("Doja Cat")  → Deezer Search API
   ↓
DeezerClient.getStreamInfo(trackId)
   - gw-light.php?method=song.getData  → TRACK_TOKEN
   - media.deezer.com/v1/get_url       → encrypted stream URL (FLAC if HiFi)
   - HEAD                              → content length
   ↓
StreamingMediaDataSource starts producer thread:
   - HTTP GET stream URL
   - For each 2048-byte chunk: if chunkIdx % 3 == 0, decrypt with
     Blowfish-CBC using key = md5(trackId)[0:16] XOR md5(trackId)[16:32]
     XOR "g4el58wc0zvf9na1"
   - Write to in-memory buffer; readAt() blocks if more bytes needed than buffered
   ↓
MediaPlayer.setDataSource(streamingSource) → prepareAsync → start
   ↓
PlaybackState transitions: NONE → BUFFERING → PLAYING
MediaMetadata: title, artist
   ↓
AA reads PlaybackState + MediaMetadata, renders its own UI on the car screen.
Audio comes out of the car speakers via AA's audio routing.
```

The phone path is similar but ends at "open Deezer's app with a deep-link search URL." Deezer's UI then takes over. We use this path because on the phone, Deezer's full UI is useful; in AA, AA owns the UI and just needs the audio.

## What we tried that didn't work (and why)

Hours of investigation discovered:

| Hypothesis | Status |
|---|---|
| Deezer's manifest is missing something Spotify declares | False — manifests are nearly identical |
| Deezer's `MediaBrowserService` package validator rejects Gemini | False — Gemini is allow-listed |
| Modern car-app declarations (`androidx.car.app.category.MEDIA`) on Deezer | Real but not the decisive lever — got Deezer "noticed" by Cast scanning but Gemini's routing still bypasses it |
| Gemini server-side allowlist of trusted music partners | **True**, and not patchable |
| Spotify works because of its package name + manifest declarations | **True** — and the package-name impersonation works (no signature check on the media routing path) |

The Spotify URL we receive (`https://open.spotify.com/track/<id>` or `/artist/<id>`) is *unusable* — different catalog from Deezer. But Gemini also ships **structured extras** (`android.intent.extra.artist`, `.title`, `.album`, `.focus`) on the same intent, and those *are* usable. We ignore the URL and search Deezer with the structured fields.

For Android Auto specifically, Gemini sends `playFromUri` (not `playFromSearch`) — and times out after ~10s if `PlaybackState` doesn't transition to `PLAYING`. The shim must implement `onPlayFromUri` and actually play audio fast enough.

## Project layout

```
.
├── AndroidManifest.xml      — claims `com.spotify.music`, all the music-app filters
├── build.sh                 — manual aapt2 + javac + d8 build (no Gradle)
├── res/
│   ├── values/strings.xml
│   └── xml/automotive_app_desc.xml
└── src/com/spotify/music/
    ├── DeezerClient.java         — protocol client: auth, search, get_url, decrypt
    ├── StreamingMediaDataSource.java — MediaDataSource that streams + decrypts on-the-fly
    ├── ShimMediaBrowserService.java  — MBS + MediaSession + MediaPlayer; the AA-mode player
    ├── MainActivity.java         — phone-mode: receives open.spotify.com intents,
    │                                hands off to Deezer's app via deep link
    ├── DeezerTestActivity.java   — adb-driven test runner; also used to set the ARL
    └── ShimCarAppService.java    — empty stub; only its manifest declaration matters
```

## How to build

Prerequisites on macOS:
- Android SDK `build-tools;34.0.0` and `platforms;android-34` (installed via `sdkmanager`)
- OpenJDK 17+
- `adb` to install

```bash
./build.sh
# → build/shim-unsigned.apk
```

Then sign with [uber-apk-signer](https://github.com/patrickfav/uber-apk-signer):

```bash
java -jar uber-apk-signer.jar --apks build/shim-unsigned.apk --allowResign
```

## How to install + configure

```bash
# Uninstall the real Spotify (cannot coexist with shim — same package name)
adb uninstall com.spotify.music

# Install the signed shim
adb install build/shim-unsigned-aligned-debugSigned.apk

# Make sure Deezer is also installed (Play Store version is fine; only used for
# the phone-mode UI hand-off)
adb shell pm path deezer.android.app

# Configure the Deezer ARL cookie (your Deezer session token; extract from
# logged-in browser via DevTools → Cookies → www.deezer.com → arl value)
export DEEZER_ARL='<your arl>'
adb shell "am start -n com.spotify.music/.DeezerTestActivity --es arl '$DEEZER_ARL'"

# Phone test (also works as a sanity check):
adb shell am startservice -n com.spotify.music/.ShimMediaBrowserService \
    --es query "bohemian rhapsody queen"
# → music plays

# Then trigger via voice:
# "Hey Google, play [song] on Spotify"
```

## Limitations

- **Voice phrasing is "Spotify"**, not "Deezer." Cognitively jarring but works.
- **Real Spotify can't be installed alongside** (same package name).
- **No Play Store update path** for the shim (re-signed APK).
- **Requires Deezer Premium / HiFi for full-quality streams**. Free accounts only get 30s previews through the public API.
- **Personal use only.** The ARL is your account credential; treat it like a password. Don't redistribute the resulting APK.
- **ARL eventually expires** (typical: months). Re-run the configure step with a fresh ARL when it does.
- **Currently single-track**: artist-radio queue / album / playlist handling are TODO.

## Legal / use

This works around a routing decision in Google's voice assistant by claiming a package name that belongs to Spotify AB, and accesses Deezer streams via their internal protocol. It doesn't ship any Spotify or Deezer code; it doesn't modify either app; it doesn't bypass any DRM or paywall beyond what your Deezer Premium subscription already entitles you to. **Don't redistribute.**

## Acknowledgements

- Deezer's protocol details from the open-source community (deemix, dzr-rs, etc.)
- Google for shipping enough structured query metadata that this is even possible
