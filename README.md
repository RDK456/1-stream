# 1-stream

A Windows desktop media player that streams from **plugins you install yourself**.
1-stream ships with no content sources; what you add and watch is your responsibility.

Based on [CloudStream](https://github.com/recloudstream/cloudstream) (GPL-3.0). See [NOTICE](NOTICE).

## Features

- Netflix-style interface: top navigation, billboard, poster rows with hover-grow; scales and scrolls at any window size (down to 420 px wide)
- Search across all installed providers, browse provider home pages, filter by language and type
- Title pages with episodes, **Library** (add and edit), **History** with resume, and **Downloads** with a quality picker (direct files and HLS, AES-128 supported)
- Built-in player (libVLC): HLS/DASH/MP4, `Referer` and `User-Agent` passthrough; playback settings for speed, quality, source, audio track, subtitles and delay, volume boost, mute; an 85-second skip for anime; full keyboard control (press `H` in the player)
- Plugins are plain JVM jars loaded in isolated class loaders; one broken plugin never takes the app down
- Plugin repositories are HTTPS JSON indexes; every download is SHA-256 verified before it is installed

## Install

Download `1-stream-<version>.exe` (Setup) or `.msi` from Releases and run it. VLC is bundled.

Silent install (both upgrade in place):

```powershell
msiexec /i 1-stream-1.0.1.msi /qn /norestart
1-stream-1.0.1.exe /qn
```

Installers are unsigned, so Windows SmartScreen may warn on first run.

## Updates

The app reads an `update.json` feed (build with `-Pupdate.url=https://host/update.json`, or set `ONESTREAM_UPDATE_URL`).
It downloads only the changed files (a delta patch, usually well under 1 MB), verifies SHA-256, and applies it when you
close the app or click **Restart to update**. If the install has drifted it falls back to a silent full MSI.
Toggle under **Settings > Updates**. No feed URL means updates stay off.

Publish a feed (build releases with `-Papp.version=1.0.1`), then upload the output folder next to `update.json`:

```powershell
scripts\make-update.ps1 -Version 1.0.1 -NewDir <new app-image> -Old '1.0.0=<old app-image>' -Msi <msi> -Setup <exe> -Out feed
```

## Plugins

A repository index looks like:

```json
{
  "name": "My repo",
  "plugins": [
    { "name": "Example", "internalName": "example", "version": 1, "apiVersion": 1,
      "url": "example.jar", "sha256": "<hex sha-256 of the jar>", "description": "optional" }
  ]
}
```

A plugin jar contains `manifest.json` (`name`, `pluginClassName`, `version`, `apiVersion`) and a class extending
`BasePlugin` that calls `registerMainAPI(...)`. `plugins/blender-demo` is a complete working example
(Creative Commons films from the Blender Foundation) and is also what the end-to-end tests use.

To try it: build it (`gradle :plugins:blender-demo:jar`) and copy `plugins/blender-demo/build/libs/blender-demo.jar`
into `%APPDATA%\1-stream\plugins`, or use **Plugins > Open plugins folder**.

### Default plugins

On first run, after you accept the notice, 1-stream installs every plugin from `https://raw.githubusercontent.com/RVRBEAST76/allforu-repo/builds/repo.json` that can run on desktop (the list is `DEFAULT_PLUGIN_REPOS` in `Repositories.kt`). Plugins that need the Android app are skipped. Remove any of them and it stays removed. The plugins are downloaded from their authors, not bundled with 1-stream.

### Automatic updates for repositories and plugins

At start and every 6 hours 1-stream re-reads your repositories and, if a plugin it installed has a newer version there, downloads, verifies and swaps it in. A bad download or a version that fails to load never replaces the working one. Toggle it, or press **Check now**, under **Plugins > Installed plugins**. Plugins you dropped in by hand are never updated. Updated Android `.cs3` plugins are re-pinned automatically, so only turn this on for repositories you trust.

### Discover plugins

**Plugins > Discover plugins** lists everything the added repositories offer. **Scan all repositories** refreshes every
index; then search by name and filter by language and type (adult plugins stay hidden unless you pick NSFW). The Home
screen has the same language and type filters for your installed providers.

### CloudStream repositories and `.cs3` plugins

Paste a `cloudstreamrepo://...` or `https://...repo.json` link under **Plugins** to add it as a repository. 31 well-known
CloudStream repositories are pre-added (remove any you don't want). Android `.cs3` plugins are converted to JVM jars on
install (dex2jar plus small stand-ins for Android classes). This is best effort: plugins that need real Android APIs
(WebView, resources) will not run.

`.cs3` files carry no hash and run as code, so installing one shows its source and SHA-256 and asks you to confirm.
The app pins that exact file and refuses to run it if it changes. Nothing is installed automatically.

## Build

Requirements: JDK 17, internet access for Gradle dependencies.

```powershell
powershell -ExecutionPolicy Bypass -File scripts\fetch-vlc.ps1   # one-time: bundles VLC
gradle :desktopApp:run                                           # run
gradle :desktopApp:jvmTest                                       # tests (renders screenshots to build/ui-shots)
gradle :desktopApp:packageMsi                                    # installer in desktopApp/build/compose/binaries/main/msi
```

Self-check without a window: `1-stream.exe --selftest path\to\plugin.jar` loads the plugin, searches, resolves links and
decodes real video through libVLC, then exits 0.

## Licence

GPL-3.0. Video playback by VLC / libVLC (LGPL-2.1+).