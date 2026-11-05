# LyricRPC Mobile

Synced lyrics for the music already playing on your phone: a floating line on screen, a live
notification, and one-tap copy so you can paste the current line into Discord's mobile status.

It reads Spotify's playback off Android's media session and resolves the lyric from community
lyric APIs. It plays no audio, logs into nothing, stores no account anywhere, and needs no root and
no Discord token.

**Status.** The lyric core is verified: 84 headless checks pass and five of the six sources return
the same line for a real song near the same timestamp. The Android layer compiles, packages and
signs cleanly, but it has not yet been run on a device, so treat the first install as the real test.

## Install

Requires Android 8.0 (API 26) or newer. A debug-signed build is committed as
`LyricRPC-1.0.0.apk`; rebuild it yourself if you would rather not trust a binary.

1. Copy the APK to the phone and open it. Allow "install unknown apps" for that browser or file app.
2. Open the app and tap **Notification access**, then enable LyricRPC. This is what lets the app see
   which song another app is playing. It is called *notification* access because that is the API
   Android gates media sessions behind; no notification content is read, posted or stored.
3. Tap **Draw over apps** and allow it for the floating lyric. Skip it if you only want the
   notification.
4. Allow notifications when asked, then press **Start lyrics** and play something.

## Using it with Discord on mobile

Discord's phone app exposes no status API to other apps, so the handoff is deliberately manual:

- Tap the floating lyric, or press **Copy** on the notification, to put the current line on the
  clipboard.
- In Discord: your avatar → **Set Status** → paste.

**Copy whole song** puts the full lyric on the clipboard in LRC format.

## Lyric sources

Off any of them in **Lyric sources**; at least one must stay on. They are tried in order and the
first one that returns synced lines wins. All of them are community-maintained, so coverage varies
by song and a song with no synced lyric anywhere simply reports that and moves on.

| Source | What it is |
| --- | --- |
| RMM Revival | TTML lyrics database |
| AMLL TTML DB | Android Music Playback Library's TTML database |
| lrc.red | TTML and LRC archive |
| Unison | aggregated lyric API |
| LRCMux | LRC with word-level timing |
| LRCLIB | the largest community synced-lyric database |

## Layout

```
src/com/lyricrpc/mobile/core/   plain Java: parsing, providers, timing, presentation
src/com/lyricrpc/mobile/        the Android shell: engine, service, notification, overlay, UI
res/                            icons and strings
AndroidManifest.xml             permissions and components
build.sh                        a Gradle-free APK build
```

`core/` deliberately uses no Android classes, so it can be run and tested on any JVM.

## Build

You need a JDK 17 and Android platform + build-tools 34. Nothing else: no Gradle, no Android Studio.

```sh
export ANDROID_BUILD_TOOLS=/path/to/android-build   # default: C:/Users/USER/tools/android-build
bash build.sh                                       # -> LyricRPC-1.0.0.apk, signed with debug.keystore
```

`debug.keystore` is generated on first build and is git-ignored. The build needs a Windows host as
written (it calls `aapt2.exe`, `d8`, `zipalign.exe`, `apksigner`); the same pipeline on Linux is a
matter of dropping the `.exe` suffixes and running `d8`/`apksigner` from your own JDK.

## Test the lyric core

```sh
javac -encoding UTF-8 -d outcore $(find src/com/lyricrpc/mobile/core -name '*.java')
java -cp outcore com.lyricrpc.mobile.core.CoreTest            # includes live provider calls
java -cp outcore com.lyricrpc.mobile.core.CoreTest --offline   # no network
```

## Credits and terms

MIT licensed (see `LICENSE`). Lyrics come from the community databases above and remain whatever
they are licensed as upstream; this project stores nothing and redistributes no lyric file. Not
affiliated with Spotify, Discord, or the owners of those services.
