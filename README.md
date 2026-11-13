# StreamVault — IPTV for Android TV

Library organization: Movies and Series group language/quality versions under clean titles. Use Categories for provider tiles and genres, Filters & sort for the current view, and Versions on a title to select a stream. Settings → Optimize library organization controls this view, preferred language, preferred maximum quality, and original-name display. Unknown provider and metadata values are kept explicit; original streams remain available.

Provider icon assets are bundled locally from [Simple Icons](https://github.com/simple-icons/simple-icons) v11.15.0, with the Hulu icon from v8.0.0. The Disney+ asset comes from the [Disney+ press library](https://press.disneyplus.com/about/disney-plus-logo-2024).

A Netflix-style IPTV player for Android TV, Google TV, Fire TV and Android boxes.
Add an M3U link and your channels, movies and series are organised into a proper TV library.

## Features

**Library (Netflix / Prime / Disney+ style)**
- Add one or more M3U / M3U8 playlist links (Xtream `get.php?...&type=m3u_plus` links work)
- Auto-sorts into **Live TV**, **Movies** and **Series** (series are grouped into seasons and episodes from `S01E02` titles)
- Home screen with a big hero preview of whatever you're focused on, and rows per category
- **Continue Watching** row that resumes where you stopped, and jumps to the next episode when you finished one
- **My List**: hold OK on anything (or use the button) to add movies, series or channels
- Recently watched channels, favourite channels
- Detail pages: Resume / Start over / My List, "More like this", season picker and episode list with progress
- Search across everything
- Local profiles with initials avatars, separate Continue Watching / My List / history / viewing preferences, and shared playlists
- Compact collapsing sidebar and dark translucent panels with muted teal highlights
- **Phone remote**: scan the QR in the sidebar to browse organized movies, series, and live TV in your phone browser, then play and control them on the TV
- **Add from your phone**: scan a QR code on the TV and paste the playlist link on your phone instead of typing with the remote

**Player**
- ExoPlayer (Media3): HLS, MPEG-TS, DASH, MP4/MKV; custom User-Agent / Referer from the playlist
- Netflix-style controls that auto-hide; hold left/right on the progress bar to scrub faster
- **Live TV**: Up/Down zaps channels, Left opens the channel list, channel banner on every switch, auto-reconnect when a stream drops
- **Autoplay next episode** with a 10s countdown, plus a "Next episode" button near the end credits
- Audio track picker (languages, 5.1, Dolby), playback speed, picture mode (Fit / Zoom / Stretch)
- Media keys and channel up/down on the remote

**Subtitles (like your web caster)**
- Search & download from **OpenSubtitles.com** while watching (title, language, season/episode pre-filled)
- Load any `.srt` / `.vtt` from a link
- **Timing adjustment**: shift subtitles ±0.1s / ±1s live while watching
- Subtitles embedded in the stream are listed too
- Size (S/M/L/XL) and background/outline style

## Profiles

Select the initials avatar at the top of the TV sidebar to switch profiles or add one using just a name. Manage profiles lets you rename or remove profiles; removing one requires confirmation. Up to eight local profiles are supported, and at least one is kept. These are local profiles, without passwords or cloud accounts.

Existing watch history, My List, recent channels, and viewing preferences migrate into a default **You** profile. You can rename it. Each profile has its own viewing state and preferences; playlists, catalog caches, and subtitle-service credentials stay shared. Switching profiles resets the browsing screen and revokes paired phone sessions, so scan the QR again for the new profile. Local profiles do not restrict access to the shared library or settings.

## Browse and control the TV from your phone

1. Open **Phone remote** in the TV sidebar.
2. Connect your phone to the same Wi-Fi and scan the QR with its camera.
3. Browse Movies, Series, or Live TV; use provider, genre, language, quality, and search filters.
4. Open a title, choose a language/quality version and season/episode, then play it on the TV. On a movie, series, or live channel’s details page, tap the **heart** to choose which playlists to save it in. **Liked** is always available by default. A **filled heart** means the item is saved in at least one playlist; tap it to manage membership, including removing it from selected playlists. Watch progress is retained.
5. Use the bottom playback bar to pause/resume, seek, stop, or move to the next/previous episode or channel. Seeking depends on the stream.

### Personal playlists

Open **My List** in the TV sidebar or phone tabs to find **Liked** and your custom playlists of movies, whole series and live channels. On TV, each playlist has its own horizontal browse row with up to 15 previews and **View all**; **Liked** appears first. Use **New playlist** or **Manage playlists** at the top. Existing My List items are preserved in Liked, which cannot be renamed or deleted. Tap **My List / the heart** on a title’s details page (or hold OK on a tile on TV) to choose one or more playlists; tap a selected playlist again to remove the item. On TV, **Add titles** also lets you search all three content types, including live channels.

Playlists belong to the active profile and remain saved across app and TV restarts. You can rename or delete a playlist and remove individual entries without changing other playlists or watch progress. A missing provider title stays removable. These collections reference your library; they do not download videos, duplicate the catalogue, or play an automatic mixed-content queue. Up to 30 custom playlists and 300 titles per custom playlist are supported within a 512 KiB storage budget. Existing Liked items retain their previous storage and are preserved without truncation.

No phone app or backend is needed. The TV serves a local browser interface while StreamVault is in the foreground, including during playback. Wi-Fi client isolation (common on guest networks) can prevent access. Backgrounding the TV app closes the server and revokes sessions; reopen Phone remote and scan again to reconnect.

Pairing codes are random, single-use, and valid for two minutes. Refresh QR generates a new code; Disconnect phones revokes every paired session; Turn off phone remote closes the listener. Up to four phones can pair, with sessions expiring after an hour of inactivity. Any paired phone can control current playback.

Use a trusted local network: the companion uses HTTP, not internet-facing HTTPS. Requests require a paired HttpOnly/SameSite cookie, same-origin checks, and a validated Host header. The browser receives paged catalog metadata and opaque title/version/episode IDs, never M3U credentials or stream URLs. Artwork is fetched only from catalog entries, checked for private-network destinations, resized, and cached with a 20 MB disk budget. Video stays on the TV; the phone sends playback commands only.

## Put it on GitHub and build the APK

You don't need Android Studio. GitHub builds the app for you.

1. Create a new **public** repository on GitHub (e.g. `streamvault`). Public is needed so your TV can download the APK without logging in.
2. Upload this folder:
   ```bash
   cd StreamVault
   git init
   git add .
   git commit -m "StreamVault"
   git branch -M main
   git remote add origin https://github.com/<your-username>/streamvault.git
   git push -u origin main
   ```
3. Open the **Actions** tab. The "Build APK" workflow runs automatically (about 5–8 minutes).
4. When it's green, a new release appears under **Releases** with `StreamVault.apk` attached.

Every push to `main` builds a new version that installs as an update over the previous one.

## Install it on your TV

**Easiest: the Downloader app**
1. On the TV, install **Downloader** (by AFTVnews) from the Play Store / Amazon Appstore.
2. Allow it to install unknown apps: *Settings → Apps → Security & restrictions → Unknown sources → Downloader* (wording varies by TV; on Fire TV: *My Fire TV → Developer options → Install unknown apps*).
3. In Downloader, enter:
   ```
   https://github.com/<your-username>/<repo>/releases/latest/download/StreamVault.apk
   ```
   Tip: shorten it with a link shortener so it's easier to type with the remote.
4. Install, then open **StreamVault** from your apps row.

**Alternative: ADB over Wi-Fi**
```bash
adb connect <tv-ip>:5555
adb install -r StreamVault.apk
```

## First run

1. Open the app and either type your playlist link, or scan the QR code with your phone and paste it there.
2. For subtitles: create a free account at opensubtitles.com, go to your profile → **API consumers**, create a key, and paste it in **Settings → Subtitles** (or send it from your phone via the QR page). Username/password are optional and raise the daily download limit.

## Remote cheat sheet

| Where | Button | Action |
|---|---|---|
| Anywhere | Hold OK | Add/remove from My List / favourites |
| Live TV playing | Up / Down | Previous / next channel |
| Live TV playing | Left | Channel list |
| Movie/episode playing | Left / Right | Rewind / forward 10s (hold to go faster) |
| Player | OK | Show controls |
| Player | Back | Hide controls, then exit |

## Signing (optional)

The repo includes a signing key in `keystore/` so every GitHub build has the same signature and updates install over each other. Because it's in a public repo, anyone could sign an app with it. If you want your own private key:

```bash
keytool -genkeypair -keystore my.jks -alias streamvault -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 my.jks   # copy the output
```
Add these repository secrets (*Settings → Secrets and variables → Actions*): `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`.
Note: switching keys means uninstalling the old app once before installing the new one.

## Building locally (optional)

Open the folder in Android Studio, or run `./gradlew assembleRelease` with JDK 17 and the Android SDK installed. The APK lands in `app/build/outputs/apk/release/`.

## Tech

Kotlin, Jetpack Compose (TV-focus aware custom components), Media3 ExoPlayer, OkHttp, Coil, ZXing. No backend: all data lives on the TV.
