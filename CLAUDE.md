# JellyJar — Claude Code Handoff

## What This Is
JellyJar is a two-component system for offline Jellyfin tablet playback:

- **JellyJar** — Android app (`com.fuzzymistborn.jellyjar`) that browses a Jellyfin library, streams content directly, and queues transcoding jobs for offline download
- **Press** — FastAPI + ffmpeg Docker service that transcodes Jellyfin media to MP4 and serves the output for download

## Repository Layout
```
jellyjar/
├── docker-compose.yml          # Press service definition
├── press/
│   ├── Dockerfile
│   ├── main.py                 # FastAPI app, port 8090
│   └── requirements.txt
└── android/
    ├── build.gradle.kts        # compileSdk=37, buildToolsVersion="36.0.0", minSdk=35
    ├── settings.gradle.kts
    ├── gradle/libs.versions.toml
    └── app/src/main/
        ├── AndroidManifest.xml         # networkSecurityConfig referenced here
        ├── res/xml/network_security_config.xml   # allows HTTP to 192.168.50.24
        └── kotlin/com/fuzzymistborn/jellyjar/
            ├── JellyJarApp.kt
            ├── MainActivity.kt         # Nav host, rememberSaveable isAdminUnlocked
            ├── api/
            │   ├── JellyfinApiService.kt   # Retrofit, Authorization header, streamUrl helper
            │   └── ShimApiService.kt       # Press /health, /transcode, /jobs, /download
            ├── data/
            │   ├── local/Database.kt       # Room: DownloadEntity, CachedItemEntity
            │   └── repository/
            │       ├── Repositories.kt     # JellyfinRepository, DownloadRepository, dynamic URL building
            │       ├── DownloadQueueManager.kt  # Promotes QUEUED items to Press, enforces concurrency + pause
            │       ├── SettingsRepository.kt
            │       └── SettingsExt.kt
            ├── di/AppModule.kt             # Hilt, OkHttpClient injection
            ├── model/Models.kt             # All @SerializedName PascalCase for Jellyfin JSON
            ├── ui/
            │   ├── screens/
            │   │   ├── LibraryScreen.kt    # Home screen with library tiles + Downloads tile
            │   │   ├── DetailScreen.kt     # Movie/Series detail, inline seasons+episodes, download controls
            │   │   ├── AdminScreen.kt      # Settings, PIN gate, folder picker for download path
            │   │   ├── StorageScreen.kt    # Storage management: usage breakdown, bulk delete, sort
            │   │   └── PlayerScreen.kt     # ExoPlayer for local or stream URLs
            │   ├── theme/
            │   └── viewmodel/ViewModels.kt # All ViewModels in one file
            └── worker/DownloadWorker.kt
```

## Tech Stack (Android)
- Kotlin + Jetpack Compose
- Hilt (DI)
- Room (local DB for download tracking)
- Retrofit + OkHttp (API calls)
- Coil (image loading)
- AndroidX Palette (per-title dynamic color extraction from backdrop art, Detail screen)
- Media3/ExoPlayer (playback)
- DataStore (settings persistence)
- WorkManager (background download jobs)
- Navigation Compose

## Architecture Decisions
- **Dynamic base URLs**: Retrofit instances use placeholder base URLs at DI time; actual Jellyfin and Press URLs are read from DataStore at call time and injected via OkHttpClient
- **@SerializedName**: All model fields use PascalCase annotations to match Jellyfin's JSON
- **HTTP allowed**: `network_security_config.xml` permits cleartext to `192.168.50.24` (LAN IP); `android:networkSecurityConfig` is set in `AndroidManifest.xml`
- **`http://` auto-prepend**: `ensureScheme()` in AdminViewModel prepends `http://` if no scheme present, applied to both URLs on save/authenticate/test
- **PIN gate timing fix**: `AdminState.settingsLoaded = true` is set on first DataStore emit; `PinGateScreen` waits for this before calling `onSkip()` to avoid race condition with default `isPinEnabled = false`
- **Admin unlock**: `isAdminUnlocked` in MainActivity uses `rememberSaveable` to survive navigation; Download button is now always visible (not gated by admin)
- **Client-side download queue**: `queueTranscode()` only inserts a `QUEUED` DownloadEntity (with `queuePosition`); `DownloadQueueManager` (started in `JellyJarApp.onCreate`) observes downloads+settings and promotes queued items to Press via `startQueuedItem()` until `maxConcurrentDownloads` (1–2, Admin setting) are in flight. Pause flag (`downloadQueuePaused` in DataStore) stops promotions only — in-flight items finish. Reorder = swap `queuePosition`; prioritize = min-1; retry re-queues at the end. A failed promotion (Press unreachable) marks that item FAILED and backs off 30s before trying the next

## Navigation Flow
```
Library (home tiles) → [tap library] → Media grid → [tap item] → Detail
Detail (movie) → Stream (ExoPlayer) | Download → transcode via Press
Detail (series) → inline season posters → tap season → episode row
Episode row → Stream | Download per episode | Download whole season
Gear icon → PIN gate → Admin/Settings
```

## Press API (port 8090)
| Endpoint | Method | Description |
|---|---|---|
| `/health` | GET | Returns `{"status":"ok","media_root":...,"output_root":...}` |
| `/presets` | GET | Returns `["1080p","720p"]` |
| `/transcode` | POST | Starts transcode job, returns `JobStatus` |
| `/transcode/batch` | POST | Starts multiple transcode jobs in one call (e.g. a whole season); returns `list[JobStatus]`. Bad items are recorded as `failed` jobs rather than aborting the batch |
| `/jobs` | GET | Lists all jobs |
| `/jobs/{id}` | GET | Gets job status + progress |
| `/download/{id}` | GET | Streams completed MP4 file |
| `/jobs/{id}` | DELETE | Deletes job + output file; kills the ffmpeg process first if the job is still running |

**TranscodeRequest body:**
```json
{ "source_path": "/mnt/kids/movie.mkv", "preset": "1080p", "output_filename": "optional.mp4" }
```
- `source_path` is the **absolute path** as returned by Jellyfin's `MediaSources[].Path`
- Press accepts absolute paths directly (no MEDIA_ROOT prepending when path is absolute)

**BatchTranscodeRequest body:** `{ "items": [ <TranscodeRequest>, ... ] }`

## Press Job Persistence & Cleanup
- Jobs are persisted to `$CONFIG_ROOT/jobs.json` on every status transition (not on every progress tick), so job history survives container restarts
- Jobs persist `source_path` and `preset`, so any job still `queued`/`running` at startup (its ffmpeg process died with the container) is re-queued and its transcode restarted automatically (`resume_interrupted_jobs()` in lifespan). If the preset was deleted or the source file is gone, it's marked `failed` with a specific error instead. Legacy jobs saved before `source_path`/`preset` existed fail with `"Interrupted by service restart"`
- `CLEANUP_AFTER_DAYS` (env var, default `0` = disabled) automatically deletes completed jobs and their output files once older than N days; checked hourly

## Press Docker Volumes
Media mounts mirror Jellyfin's container paths exactly:
```yaml
volumes:
  - /mnt/Media/Movies:/mnt/movies:ro
  - /mnt/Media/TV Shows:/mnt/tv:ro
  - jellyjar-output:/output
  - jellyjar-config:/config
environment:
  MEDIA_ROOT: /mnt
  OUTPUT_ROOT: /output
  CONFIG_ROOT: /config
  CLEANUP_AFTER_DAYS: 0      # 0 disables auto-cleanup of old completed jobs/output
```

## Known Working State
- ✅ Home screen with library tiles (backdrop art from Jellyfin)
- ✅ Movies grid with infinite scroll pagination (50 items/page)
- ✅ TV Shows grid (Series only, no seasons at top level)
- ✅ Series detail with inline season posters + episode row
- ✅ Stream button on movies/episodes → ExoPlayer
- ✅ Download button on movies → queues transcode via Press
- ✅ Download button on individual episodes
- ✅ Download whole season button on season poster cards
- ✅ Settings screen: Jellyfin auth, Press URL test, folder picker for download path
- ✅ Admin PIN gate with race condition fix
- ✅ Press connectivity (HTTP to LAN IP working)
- ✅ Press path resolution (absolute Jellyfin paths work)
- ✅ Downloads screen: active/completed/failed sections, retry (single + all), bulk delete, watched-item cleanup
- ✅ Wi-Fi-only downloads toggle (Admin → Downloads)
- ✅ Season download progress rollup on season poster cards ("3/8 downloaded" / "downloading" / "Downloaded")
- ✅ Tap-to-open notification on download completion/failure → jumps to Downloads screen
- ✅ Device storage info (free/used space + per-item file size) in Admin → Downloads and Downloads screen
- ✅ Library tile artwork loading (backdrop → primary image fallback)

## Newer Features
- **Skip intro/credits**: `JellyfinRepository.getSkipSegments()` tries the native MediaSegments API (Jellyfin 10.9+) then falls back to the Intro Skipper plugin endpoint (`/Episode/{id}/IntroSkipperSegments`). Segments are captured at queue time into `DownloadEntity.segmentsJson` so the skip button also works for offline playback. PlayerScreen polls position (2 Hz) and shows a "Skip Intro"/"Skip Credits" button during segments. Toggle: Admin → Playback → "Skip Intro / Credits" (`introSkipEnabled`)
- **Trickplay scrub previews**: streaming-only (tiles come from the server). `PlayerViewModel.loadTrickplay()` picks the resolution nearest 320px from the item's `Trickplay` field; PlayerScreen attaches a `TimeBar.OnScrubListener` to `exo_progress` and crops the thumbnail out of the fetched sprite-sheet tile (LruCache of 6 tiles). Toggle: Admin → Playback → "Scrubbing Previews" (`trickplayEnabled`)
- **Genre filter**: server-side (`Genres` query param on `/Users/{id}/Items` — client-side filtering would break pagination). Genre list fetched per-library from `/Genres?ParentId=`; chips render below the sort chips in the library grid. Changing genre reloads the library
- DB schema is now **version 7** (`played` added to `DownloadEntity` in `Database.kt` — `DownloadDao.updatePlayed()`/`MetadataRefreshWorker` write it, but the column was missing from the entity, which broke CI's KSP query validation with `no such column: played`); destructive migration wipes existing download records on upgrade
- **Subtitles + all audio tracks in downloads**: Press explicitly maps `0:v:0`, all audio (`0:a?`) and every *text* subtitle stream, encoding subs as `mov_text`. Previously ffmpeg's default stream selection silently kept one video + one audio and dropped all subtitles, so offline playback had no subs and no alternate audio at all. `probe_text_subtitle_count()` (ffprobe) counts only the *leading run* of text-based subtitle streams — image subs (PGS/VobSub) can't go into MP4 and would fail the whole encode, and mapping past one would renumber the `0:s:N` picks onto the wrong streams. Image-sub sources therefore still download without subtitles; burn-in would be the fix if that becomes a problem
- **Player gestures**: double-tap left/right half to seek ∓10s, vertical swipe on the left half for brightness and the right half for volume, with a transient centre readout. The gesture surface is only composed while the controls are *hidden* — it sits above the `PlayerView` and would otherwise swallow taps meant for the seek bar. Toggle: Admin → Playback → "Playback Gestures" (`playbackGesturesEnabled`, default on) — intended to be turned **off** on a kids' tablet so a stray palm can't scrub or dim the screen
- **Auto-play "Up next" countdown**: `UpNextCard` appears *during the outro* — at the credits marker when the server has one, else `UP_NEXT_LEAD_MS` (30s) before the end — showing the resolved episode ("S02E04 · Title", from `NextEpisodeTarget.title`). The countdown tracks the real time remaining rather than a fixed 10s, so a card raised at the start of a 90-second credits roll says 90, not 10. Plus Play now / Cancel. Deliberately not behind its own setting — the existing "Auto-play Next Episode" toggle still governs whether anything is resolved at all
- **Player exits when playback finishes with nothing queued** (auto-play off, series finale, a movie): `onBack()` instead of sitting on a frozen last frame. Cancelling the countdown is treated as an explicit "stay here" and suppresses it
- **Streamed audio/subtitle tracks**: a Jellyfin transcode delivers *one* audio track and no embedded subtitles, so ExoPlayer's own track list is a subset of the source's and can't drive the picker. `JellyfinRepository.resolveStream()` now returns a `StreamResolution` with the server's full `MediaStreams` list. Audio switching on a transcoded stream re-negotiates PlaybackInfo with `AudioStreamIndex` and reloads at the current position; the picker only falls back to ExoPlayer's own track selection when the server list is no bigger than what ExoPlayer sees (i.e. direct play, where `AudioStreamIndex` is ignored by the server anyway). Subtitles: `DeviceProfile.SubtitleProfiles` requests **External** delivery for text formats, and those are side-loaded as `MediaItem.SubtitleConfiguration`s (id `jf-sub-<index>`) so every text track stays selectable with no reload; image subs (PGS/VobSub) can only be **Encode**d, so picking one re-negotiates and is labelled "(burned in)". Side-loaded subtitle URLs carry `api_key` in the query string — ExoPlayer's data source sends no Authorization header
- **Download folder is now required**: `requireDownloadFolder()` in `queueTranscode`/`retryTranscode`. `getDeviceStorageInfo()`/`downloadFile()` both silently fall back to internal or external storage when the path is blank, so a download with no configured destination used to run all the way through Press and land somewhere the user never picked
- **Brightness override is released on exit**: the swipe gesture sets a window-level `screenBrightness`, which otherwise persists for the rest of the app's lifetime — the player's dispose now restores `BRIGHTNESS_OVERRIDE_NONE`
- ✅ Full download→file→playback pipeline, offline playback from Downloads home tile, download queue (reorder/pause/resume/prioritize/concurrency), and the Storage management screen have all been run and confirmed working on device

## UI Polish

Implemented, based on a design review pass (see "Good to Do" below for the rest of that review):
- Richer backdrops: vignette darkening (`vignetteScrim()` in `Theme.kt`) layered over the existing scrims on both the Library featured backdrop and Detail hero backdrop; featured backdrop blur increased 20dp → 28dp
- Poster depth: shared `PosterImage` composable now casts a subtle shadow (`Elevation.poster`, 4dp) instead of reading as a flat rounded rectangle
- More breathing room: bigger vertical gaps between home-screen sections (Continue Watching/Recently Added/My List/Libraries), and before the Seasons row on Detail
- Typography hierarchy: Detail screen movie title bumped 36sp→40sp, metadata row (year/runtime/rating) 12sp→14sp, overview text 14sp→16sp, and all default button labels (`labelLarge`) 13sp→16sp so primary actions read with more weight; genre/sort chip labels 11sp→12sp
- Download badges: the poster grid's download-status badge now shows a live percentage next to the icon while downloading, plus a thin progress bar across the bottom of the poster (mirrors the pattern already used for playback-resume progress)
- Skeleton loaders: already implemented pre-review (`SkeletonGrid`/`SkeletonCard` in `LibraryScreen.kt`, shimmer via `rememberShimmerAlpha`) — no changes needed there
- Downloads screen artwork: the in-progress/queued/failed rows now carry a compact 48×72 `PosterImage` (`DownloadRowPoster`), matching the completed rows' 80×120 poster — they were the last icon-and-text lists left in the app
- Download notifications: each worker posts progress under a per-job id derived from its Press job id (was a hardcoded `1`, so two concurrent downloads overwrote each other's progress bar) and titles the notification with the item name; progress and terminal notifications are grouped
- Empty states: `EmptyState` (`UiComponents.kt`) gained an optional action button; the Downloads screen's empty state now reuses the shared composable (previously hand-rolled and inconsistent with Library's) with a subtitle and a "Browse Library" action
- Detail Screen tech-spec chips: resolution/HDR/audio-format chips derived from the item's first media source's video/audio streams (`DetailScreen.kt`, ~line 395-422)
- Detail Screen hero redesign: poster now shown next to the title on all screen sizes (not tablet-only), width-conditional sizing (poster 130dp→220dp, overview text unclamped) above 600dp, widened hero backdrop vignette blending into the metadata section. **Logo art was tried and deliberately reverted in favor of plain title text** — not a gap, a closed decision; don't re-add without discussing first
- **Tablet/landscape two-pane Detail layout**: at ≥840dp width, `DetailStackedContent`/`DetailTwoPaneContent` in `DetailScreen.kt` branch to a fixed left pane (poster/title/metadata/tech specs/actions) beside a right pane that scrolls its own overview + seasons content independently, so actions stay visible while browsing seasons. Below 840dp it's still the single stacked column. Not yet verified on an actual tablet/emulator — build tooling isn't available in this environment, so this landed reviewed-but-uncompiled
- **Dynamic color accents from backdrop art**: `rememberDynamicAccentColor()` (`ui/theme/DynamicAccent.kt`, new `androidx.palette` dependency) extracts a per-title vibrant/muted swatch from the backdrop image, clamped to a legible range, with a theme-blue fallback while loading/on failure. Wired into Detail screen only (Resume/Play buttons, star icon, watched-icon tint, download-progress spinner, tech-spec chips) — Library's featured backdrop/chips still use the fixed blue, a natural follow-up if wanted
- **Shared element / animated transitions**: `MainActivity.kt`'s `JellyJarNavHost` wraps the `NavHost` in a `SharedTransitionLayout`; `LibraryScreen.kt`'s `MediaCard` and `DetailScreen.kt`'s poster (`detailPosterModifier()`) share the key `"poster-$itemId"`, so tapping a poster in the main library grid morphs it into the Detail screen's poster instead of a hard cut. Scoped to the primary grid → Detail flow only — Continue Watching/Recently Added/My List rows and Season-screen episode taps aren't wired (same pattern would extend there). **Not build-verified** — no Android build tooling in this environment
- **Artwork-first library navigation**: confirmed already substantially done pre-existing (featured hero backdrop + `LibraryTile` backdrop art + poster rows). Closed the one remaining gap: `DownloadsTile` now shows a mosaic of up to 4 recent download thumbnails (`LibraryState.downloadThumbnails`) instead of a flat icon card, falling back to the icon card when nothing's downloaded yet
- **Admin dashboard aesthetic**: light pass, not a redesign (per its own "lowest priority" note) — each `SettingsCard` now has a small tinted icon badge next to its title (Jellyfin/Press/Downloads/Home/Playback/PIN/Active Jobs), and the Settings header gained a one-line subtitle

### Good to Do (from the same review, not yet implemented)
- **Mixed poster sizes** for hierarchy (large Continue Watching cards, medium Recently Added, landscape banners for collections) — hold until the two-pane tablet layout extends to Library, or it'll get redone
- **Floating/visually-separated download action**, distinct from the Play button stack — discussed 2026-07-18, not yet implemented. Leaning toward promoting Download into the primary action row (next to Play/Resume) rather than a true FAB, to reuse the existing state-aware button logic, but not decided
- **Brand personality touches** (splash animation beyond the current static `androidx.core:core-splashscreen`, organic corner treatments instead of uniform `RoundedCornerShape`) — intentionally vague/cosmetic, revisit once core flows are done

## Server Details
- Jellyfin: `http://192.168.50.24:8096`
- Press: `http://192.168.50.24:8090`
- Test device: Android tablet emulator (API 35)

## Build Notes
- `buildToolsVersion = "36.0.0"` required — build-tools 34.0.0 is corrupted on this machine
- KSP warnings about `No dependencies reported for generated source` are harmless (Hilt codegen bug, filed upstream)
- Configuration cache is enabled (`org.gradle.configuration-cache=true`)
- Run on device: `ujust` or standard Android Studio deploy
