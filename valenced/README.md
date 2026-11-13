# LyricRPC for Valenced

The Valenced (Android Discord client mod) build of LyricRPC: it puts the Spotify line you are
hearing right now into your Discord custom status. This folder is a complete Valenced plugin
repository, and the branch it publishes to is what your phone reads.

**Repository URL — paste this into Valenced:**

```
https://jimmeh-cmyk.github.io/lyricrpc-mobile/index.json
```

Valenced → Settings → Advanced → **Repository URL** → add it → Plugins → install **LyricRPC** →
enable it. Nothing else to install: the plugin is JavaScript only, and it runs inside Discord.

## What it does

- Reads the Spotify session your Discord account already holds — no token, no login, no second
  account, nothing to type.
- Timed lines come from [LRCLIB](https://lrclib.net). When LRCLIB has the words but no timestamps,
  they are spread evenly over the song.
- Writes at most once every 15 seconds, because Discord counts every status change as a presence
  update. Each write carries an expiry, so a frozen or backgrounded app stops showing a stale line
  instead of holding the last one forever.
- Leaves a status you set yourself alone until you say otherwise.
- Decoration, casing, prefix, suffix, emoji and a censor list, with a live preview. The line is
  trimmed inside Discord's 128-character budget without ever splitting an emoji in half.
- The settings screen shows what it read, what it wrote, and why anything was held back. That panel
  exists because the only feedback from a phone is a screenshot, so a silent failure is invisible.

Needs Spotify Premium and Discord's own Spotify connection (Settings → Connections). Discord has to
be in the foreground for the status to keep up, and Spotify has to be playing on the account linked
to Discord.

## Layout

```
├── plugins/lyricrpc/
│   ├── manifest.json          # id, metadata, dist.script
│   ├── tsconfig.json          # extends ../../tsconfig.base.json
│   └── js/
│       ├── index.ts           # the plugin: watches Spotify, decides, writes the status
│       ├── core.ts            # the whole decision, with no Valenced imports, so it is testable
│       ├── playback.ts        # reads a track out of whatever shape Spotify handed over
│       ├── spotify.ts         # store + flux wiring only
│       ├── status.ts          # Discord's own custom-status setter
│       ├── lyrics.ts          # LRCLIB lookup, LRU cache, synced and unsynced parsing
│       ├── style.ts           # decoration, casing, censor, the 128-unit budget
│       ├── state.ts           # the diagnostics the settings screen reads
│       ├── Settings.tsx       # the settings screen
│       └── types.ts           # shared types and defaults
├── tools/
│   ├── verify.ts              # 98 checks over the pure modules, plus two live LRCLIB calls
│   ├── verify-bundle.ts       # loads the built bundle and drives it against a fake Valenced
│   ├── package.ts             # manifest + bundle -> build/dist/<id>@<version>.zip
│   └── publish.ts             # copies that artifact onto the pool branch and regenerates index.json
├── repo.config.json           # what the repository calls itself
└── published/                 # the pool branch during a publish (git-ignored)
```

`core.ts` holds every rule in one pure function, `decide()`, so the order of the checks — off, no
track, paused, lyric still loading, your own status, cadence — is something you can read once and
test exhaustively instead of discovering on a phone.

## Development

[Bun](https://bun.com) is the only requirement. No JDK, no Gradle, no Android SDK.

```sh
bun install
bun run build             # plugins/lyricrpc/js -> plugins/lyricrpc/build/js/index.js
bun run package           # -> build/dist/jimmeh.lyricrpc@1.0.0.zip
bun run lint              # biome
bun run lint:types        # tsc
bun tools/verify.ts       # 98 checks
bun tools/verify-bundle.ts # 36 checks against the shipped bundle
```

`tools/package.ts` refuses a manifest with native code or an `assets/` folder, so a plugin that
would need the template's Gradle path fails loudly instead of shipping half an artifact.

## Releasing

```sh
bun run build && bun run package
bun tools/verify.ts && bun tools/verify-bundle.ts
bun run publish -- --dry-run   # then without --dry-run
```

`publish.ts` clones the pool branch (`gh-pages`) into `published/`, copies the artifact into
`published/pool/`, regenerates `index.json` from that directory alone, and commits both in one
commit, so the index can never point at a file the branch does not have. The ZIP is
byte-deterministic: an unchanged build keeps its sha256, so a publish that changes nothing says so
and pushes nothing.

Bump `plugins/lyricrpc/manifest.json` and `VERSION` in `js/index.ts` together; the settings screen
prints the latter. A version already in the pool cannot be overwritten — `generate-index` reads the
manifest inside every ZIP and refuses a mismatch, so publish a higher version instead.

To unpublish a version, delete `pool/<id>@<version>.zip` from the pool branch and regenerate:

```sh
bun run generate-index -- --dist published/pool \
  --base-url https://jimmeh-cmyk.github.io/lyricrpc-mobile/pool --out published/index.json
```

### Test from your phone before publishing

```sh
bun run build && bun run package
bun run serve -- --base-url http://127.0.0.1:8080
adb reverse tcp:8080 tcp:8080
```

Then add `http://127.0.0.1:8080/index.json` as the repository URL in Valenced. Without ADB, put your
machine's LAN address into `--base-url` instead.

## Licence and terms

GPL-3.0 (see `LICENSE`), the licence of the
[Valenced plugin template](https://github.com/Valenced/valenced-plugin-template) this folder is built
on — that is why this subtree carries its own licence while the Android app above it is MIT. The two
share no code. See that template for the repository and channel model in more detail.

Lyric text belongs to its writers and to LRCLIB; nothing here redistributes it. This plugin does not
use a Discord token, and it will not: the status write goes through Discord's own in-client setter,
from inside the client you are already logged into.
