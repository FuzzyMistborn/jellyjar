# JellyJar — Claude Code Handoff

## What This Is
JellyJar is a two-component system for offline Jellyfin tablet playback:

- **JellyJar** — Android app (`com.fuzzymistborn.jellyjar`) that browses a Jellyfin library, streams content directly, and queues transcoding jobs for offline download
- **Press** — FastAPI + ffmpeg Docker service that transcodes Jellyfin media to MP4 and serves the output for download

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
**No authentication, by design.** Press is meant to run LAN-only on a trusted home network; any device on that network can enumerate the library and start/delete jobs. This is a deliberate tradeoff, not a gap — don't propose adding auth. Never port-forward Press or run it on a shared/guest network.

| Endpoint | Method | Description |
|---|---|---|
| `/health` | GET | Returns `{"status":"ok","version":...,"media_root":...,"output_root":...,"encoder":...,"role":"standalone\|coordinator\|worker"}`. The only endpoint a `worker` answers |
| `/presets` | GET | Returns `["1080p","720p"]` |
| `/transcode` | POST | Starts transcode job, returns `JobStatus` |
| `/transcode/batch` | POST | Starts multiple transcode jobs in one call (e.g. a whole season); returns `list[JobStatus]`. Bad items are recorded as `failed` jobs rather than aborting the batch |
| `/jobs` | GET | Lists all jobs |
| `/jobs/{id}` | GET | Gets job status + progress |
| `/download/{id}` | GET | Streams completed MP4 file |
| `/jobs/{id}` | DELETE | Deletes job + output file; kills the ffmpeg process first if the job is still running (a remote worker's ffmpeg is killed on its next heartbeat) |
| `/api/workers` | GET | Local slots plus live remote workers: `worker_id`, `name`, `encoder`, `max_workers`, `active_jobs`, `local` |
| `/internal/claim`, `/internal/jobs/{id}/progress\|complete\|fail\|release` | POST | Worker-facing only (see Press Distributed Transcoding); the app never calls these |

**TranscodeRequest body:**
```json
{ "source_path": "/mnt/kids/movie.mkv", "preset": "1080p", "output_filename": "optional.mp4" }
```
- `source_path` is the **absolute path** as returned by Jellyfin's `MediaSources[].Path`
- Press accepts absolute paths directly (no MEDIA_ROOT prepending when path is absolute)

**BatchTranscodeRequest body:** `{ "items": [ <TranscodeRequest>, ... ] }`

## Press Job Persistence & Cleanup
- Jobs are persisted to `$CONFIG_ROOT/jobs.json` on every status transition (not on every progress tick), so job history survives container restarts
- Jobs persist `source_path` and `preset`, so any job still `queued`/`running` at startup (its ffmpeg process died with the container) is put back in the queue by `load_jobs()` and picked up again by a local slot or worker; `resume_interrupted_jobs()` (lifespan) only refreshes durations and fails jobs that can no longer run. If the preset was deleted or the source file is gone, it's marked `failed` with a specific error instead. Legacy jobs saved before `source_path`/`preset` existed fail with `"Interrupted by service restart"`
- `CLEANUP_AFTER_DAYS` (env var, default `0` = disabled) automatically deletes completed jobs and their output files once older than N days; checked hourly

## Press Distributed Transcoding
- `PRESS_ROLE` = `standalone` (default) | `coordinator` | `worker`. Standalone and coordinator run identical code; a coordinator just also has remote workers claiming jobs. The Android app only ever talks to the coordinator — no app changes.
- **Every job goes through one queue**: `queued` → `_claim_next_job()` → `running` → `complete`/`failed`. Local slots (`local_slot()`, `MAX_WORKERS` of them; `0` is allowed on a coordinator = dispatch only) and remote workers use the same claim function. It is deliberately synchronous — no `await` between picking and marking a job running is what makes double-claims impossible. `background_tasks`/the old semaphore are gone
- **Pull model**: workers `POST /internal/claim` (body doubles as registration; 204 = nothing to do), heartbeat via `POST /internal/jobs/{id}/progress` (also renews the lease; `{"cancel": true}` in the reply means kill ffmpeg — job deleted or claim stale), then `/complete`, `/fail` or `/release`. Coordinator never dials out. `GET /api/workers` lists local slots + live workers
- Each claim gets a `claim_id`; endpoints reject a stale one. **Temp files are per claim** (`_tmp_path()` → `<output>.<claim_id>.part`), because after a restart or a lapsed lease the old claimant can still be running while a new one encodes the same job — with one shared `.part` name the stale one's cleanup deleted the live one's file and failed the job (reproduced before the fix). **Only the coordinator publishes a remote result**: a worker encodes with `publish=False` and `/complete` does the `os.replace` from that claim's temp file only if the claim is still current, so a stale worker can never overwrite or orphan an output; `{"ok": false}` tells it to delete its temp file (a repeat `/complete` while finalizing answers `ok: true` so a retried report can't delete the file mid-rename). `lease_loop()` re-queues a remote job whose lease (`WORKER_LEASE_SECONDS`, 30) lapsed, deleting that claim's temp file; after `MAX_JOB_ATTEMPTS` (3) *lost workers* (`worker_losses` — restarts and hand-backs don't count) it fails instead. Workers **self-fence**: no acknowledged heartbeat for `lease_seconds` (sent in the claim response) → kill ffmpeg. The heartbeat keeps running until `/complete` is delivered, not just until ffmpeg exits. After a coordinator restart `load_jobs()` re-queues running jobs and strips their claim, and `resume_interrupted_jobs()` deletes every leftover `*.part` for them before any slot starts; a surviving worker gets `cancel` on its next heartbeat. (The old ~15s remote-claim hold-off is gone — it didn't cover local slots and was timed before the startup probes, and per-claim temp files make it unnecessary)
- A worker that can't see the source or output dir **releases** the job (`/internal/jobs/{id}/release` → back in the queue, not counted) and stops claiming for 60s (`WORKER_UNHEALTHY_BACKOFF_SECONDS`). Previously it failed the job, so one host with a dropped mount could fail the whole queue in seconds
- `execute_ffmpeg()` is the single encode routine (probe → ffmpeg → GPU→CPU-decode→libx264 fallback chain, see Press Hardware Pipeline → atomic `os.replace`, skipped for workers), used by local slots and workers alike. The worker probes subtitles/audio itself (it has the mount), so the claim only carries source/output path, preset dict and duration
- **Shared storage is a hard requirement**: media and `OUTPUT_ROOT` must be mounted at *identical* container paths on every host (output on NFS/SMB, not a local volume). Workers fail a job with an explicit message if they can't see the source or output dir; `/complete` fails it if the coordinator can't see the output. The coordinator hashes (`_complete_job`) after a worker reports success, off the request, and the job only flips to `complete` once `output_sha256` is set
- Worker env: `COORDINATOR_URL` (required), `WORKER_NAME` (default hostname), `MAX_WORKERS`, `ENCODER`, `HW_DECODE`; `WORKER_HEARTBEAT_SECONDS` (2), `WORKER_POLL_SECONDS` (3). A worker answers only `/health` (404 elsewhere). `/internal/*` is unauthenticated, same LAN-only stance as the rest of Press
- Verified only with stub ffmpeg/ffprobe scripts on a dev box: job spread across 2 workers, standalone regression, worker killed mid-job, coordinator restart while a worker encodes (local slot re-claims), worker frozen past its lease then resumed, coordinator frozen past the lease (self-fence), DELETE of a remote encode, and a worker with a missing media mount (private mount namespace) handing jobs back. Re-run with `press/tests/multihost_harness.py` (see its docstring; `PRESS_DIR` points it at another revision to confirm a scenario reproduces the bug). **Not yet run against real GPUs / a real NFS mount**

## Press Hardware Pipeline
- Image uses **jellyfin-ffmpeg8** from `repo.jellyfin.org/debian` (not Debian's ffmpeg), symlinked into `/usr/local/bin` so code still calls plain `ffmpeg`/`ffprobe`. Chosen because it ships `scale_cuda`/`pad_cuda`/`scale_vaapi`/`pad_vaapi` (Debian's build isn't guaranteed to) and bundles libva + the iHD/i965/radeonsi drivers, so the non-free `intel-media-va-driver` install is gone
- `execute_ffmpeg()` tries an **attempt chain** of `(encoder, hw_decode)`: for `h264_nvenc`/`h264_vaapi` it's full GPU (`-hwaccel cuda|vaapi`, frames stay in GPU memory, `scale_*` + `pad_*` with `format=nv12` so 10-bit sources work) → same encoder with CPU decode/scale (the old command) → `libx264`. The GPU-decode attempt fails outright on sources the GPU can't decode (ffmpeg's silent software-decode fallback hands CPU frames to the GPU filters, which reject them), which is what triggers the retry. QSV skips the first step. `HW_DECODE=0` disables the GPU-decode attempt
- Letterboxing is preserved on the GPU path (pad offsets rounded to even for 4:2:0). No HDR tone-mapping on any path
- Filter option names were checked against the actual jellyfin-ffmpeg 8.1.2 binary's `-h filter=` output; the fallback chain was tested with a stub ffmpeg. **Not yet run on a real NVIDIA or VAAPI GPU**, and the Dockerfile change hasn't been built (no Docker in the authoring environment)

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
- DB schema is now **version 10** (v10 adds the `pending_playback_sync` table — see "Offline playback sync" below — and is the first bump with a real migration, `MIGRATION_9_10` in `Database.kt`, registered ahead of the destructive fallback so it doesn't wipe downloads; v8 added `localUri` to `DownloadEntity` — the SAF document URI the file was written to, so deletion doesn't have to re-find it by name; v7 added `played`, which `DownloadDao.updatePlayed()`/`MetadataRefreshWorker` write but was missing from the entity, breaking CI's KSP query validation with `no such column: played`). Any future bump *without* an explicit migration still falls back to wiping download records — a deliberate pre-distribution choice, documented at the `fallbackToDestructiveMigration` call in `AppModule.kt`. `exportSchema = true` now writes schemas to `android/app/schemas/` (via `room.schemaLocation` in `build.gradle.kts`) so a real migration chain can be written when the app ships
- **Subtitles + audio tracks in downloads**: Press explicitly maps `0:v:0`, the selected audio tracks (see audio bullet below) and every *text* subtitle stream, encoding subs as `mov_text`. Previously ffmpeg's default stream selection silently kept one video + one audio and dropped all subtitles, so offline playback had no subs and no alternate audio at all. `probe_text_subtitle_count()` (ffprobe) counts only the *leading run* of text-based subtitle streams — image subs (PGS/VobSub) can't go into MP4 and would fail the whole encode, and mapping past one would renumber the `0:s:N` picks onto the wrong streams. Image-sub sources therefore still download without subtitles; burn-in would be the fix if that becomes a problem
- **Download audio (Press)**: `probe_audio_plan()` picks the tracks: the source default (first track if none flagged) + any untagged (`""`/`und`) track + any track whose language is in `AUDIO_LANGUAGES` (coordinator env, empty = all; `_normalize_language()` maps 639-1/639-2B/BCP 47 to 639-2/T, so `en`, `eng`, `en-US` all match a track tagged `eng`, and the normalized tag is written back since MP4 can't store two-letter codes), never commentary (disposition `comment` or "commentary" in the title) unless it's the default. Each kept track is downmixed to stereo AAC (`-ac:a:N 2`); stereo-or-less AAC is stream-copied. Every output track's `default` disposition is set explicitly. The claim response carries the coordinator's `audio_languages`, so a job gets the same tracks on any host. A failed probe falls back to all tracks, re-encoded to stereo. Motivation: after the GPU pipeline landed, audio (lossless multi-channel decode + one AAC encode per track, running at the GPU's 10–20× speed) was most of the remaining CPU load. Verified with a real ffmpeg 8.1 on a synthetic 5-track MKV
- **Subtitle/audio dispositions in downloads are cosmetic for the app**: Press mirrors the source's `default`/`forced` subtitle flags (`probe_subtitle_plan()`) and flags the default audio track, but Media3's MP4 extractor never reads track selection flags. Offline playback therefore picks audio by device locale and leaves subtitles off unless they match the system captioning language — forced subtitles do **not** auto-enable offline. Fixing that would be app-side (e.g. store the source's default/forced tracks on `DownloadEntity` and apply a `TrackSelectionOverride`). The MP4 muxer also always enables one track per type, so a lone unflagged subtitle reads back as default=1 regardless
- **Player gestures**: double-tap left/right half to seek ∓10s, vertical swipe on the left half for brightness and the right half for volume, with a transient centre readout. The gesture surface is only composed while the controls are *hidden* — it sits above the `PlayerView` and would otherwise swallow taps meant for the seek bar. Toggle: Admin → Playback → "Playback Gestures" (`playbackGesturesEnabled`, default on) — intended to be turned **off** on a kids' tablet so a stray palm can't scrub or dim the screen
- **Auto-play "Up next" countdown**: `UpNextCard` appears *during the outro* — at the credits marker when the server has one, else `UP_NEXT_LEAD_MS` (30s) before the end — showing the resolved episode ("S02E04 · Title", from `NextEpisodeTarget.title`). The countdown tracks the real time remaining rather than a fixed 10s, so a card raised at the start of a 90-second credits roll says 90, not 10. Plus Play now / Cancel. Resolution and the "nothing next, so leave" exit share **one** `LaunchedEffect(jellyfinId)` that awaits a `snapshotFlow`; the first version keyed the effect on the trigger condition and set that condition inside its own body, cancelling itself mid-lookup so the card never appeared and every episode just exited to Detail. Every value in the `snapshotFlow` lambda must be read *inside* it — a condition computed outside captures one stale value. Deliberately not behind its own setting — the existing "Auto-play Next Episode" toggle still governs whether anything is resolved at all
- **Player exits when playback finishes with nothing queued** (auto-play off, series finale, a movie): `onBack()` instead of sitting on a frozen last frame. Cancelling the countdown is treated as an explicit "stay here" and suppresses it
- **Streamed audio/subtitle tracks**: a Jellyfin transcode delivers *one* audio track and no embedded subtitles, so ExoPlayer's own track list is a subset of the source's and can't drive the picker. `JellyfinRepository.resolveStream()` now returns a `StreamResolution` with the server's full `MediaStreams` list. Audio switching on a transcoded stream re-negotiates PlaybackInfo with `AudioStreamIndex` and reloads at the current position; the picker only falls back to ExoPlayer's own track selection when the server list is no bigger than what ExoPlayer sees (i.e. direct play, where `AudioStreamIndex` is ignored by the server anyway). Subtitles: `DeviceProfile.SubtitleProfiles` requests **External** delivery for text formats, and those are side-loaded as `MediaItem.SubtitleConfiguration`s (id `jf-sub-<index>`) so every text track stays selectable with no reload; image subs (PGS/VobSub) can only be **Encode**d, so picking one re-negotiates and is labelled "(burned in)". Side-loaded subtitle URLs carry `api_key` in the query string — ExoPlayer's data source sends no Authorization header
- **Kid Mode** (`kidModeEnabled`, Admin → Kid Mode): one flag that both applies a preset and gates UI. `SettingsRepository.setKidMode()` writes the whole preset — gestures/stats/trickplay/genre-chips/Recently-Added/My-List off, auto-play + intro-skip + Wi-Fi-only on — in a **single** `dataStore.edit` block so it lands as one emission instead of a dozen recompositions. Turning it *off* clears only the flag and deliberately does not revert the preset (the prior values aren't stored, so restoring them would mean guessing). Gated surfaces: Library's gear + search icons (long-press the "JellyJar" title to reach the PIN gate instead — the only way back into Settings), Detail's download/progress/remove/retry control and tech-spec chips, `EpisodeRow`/`EpisodeThumb`'s download controls (`kidMode` param, also passed from `SeasonScreen`), and Downloads' whole management surface — the in-progress/queue/failed sections are dropped wholesale rather than rendered inert, and hiding the Storage icon is what makes `StorageScreen` (Delete Everything / Delete Oldest) unreachable, since that's its only route. **Kid Mode does not filter content** — that's the job of the Jellyfin user the app signs in as (library access + `MaxParentalRating`, enforced server-side). Profile switching was considered and rejected: every Room table is keyed on item id with no user column, so two accounts would share the offline cache, favorites and resume positions. Exit is unguarded when no PIN is set, since `PinGateScreen` auto-skips — the Admin card shows a "Set a PIN" chip in that case
- **Download folder is now required**: `requireDownloadFolder()` in `queueTranscode`/`retryTranscode`. `getDeviceStorageInfo()`/`downloadFile()` both silently fall back to internal or external storage when the path is blank, so a download with no configured destination used to run all the way through Press and land somewhere the user never picked
- **Brightness override is released on exit**: the swipe gesture sets a window-level `screenBrightness`, which otherwise persists for the rest of the app's lifetime — the player's dispose now restores `BRIGHTNESS_OVERRIDE_NONE`
- **Offline playback sync**: every Jellyfin playback report used to be fire-and-forget (`runCatching`, result dropped), so viewing a download offline never reached the server — and `MetadataRefreshWorker` would then pull the stale server `played=false` and overwrite the correct local flag. `PlayerViewModel.reportStopped()`/`markFinished()` now go through `PlaybackSyncRepository` (`Repositories.kt`): a failed stop/played report is upserted into `pending_playback_sync` (one row per item, latest state wins, `updatedAt` = when the viewing happened) and `PlaybackSyncWorker` (one-time, CONNECTED, exponential backoff, `retry()` while the LAN server is unreachable) is enqueued. `flush()` replays each row via `POST UserItems/{id}/UserData` (falls back to the legacy `Users/{uid}/Items/{id}/UserData` on 404) with `LastPlayedDate` set to the original time — no fake playback session. **Conflict rule**: if the server's `UserData.LastPlayedDate` is newer than the row (played on another client since), the server wins and the row is dropped; a partial offline rewatch never un-marks an already-played item. Deletes are conditional on `updatedAt` so a flush can't drop a row rewritten mid-flush. `MetadataRefreshWorker` flushes first and skips `updatePlayed()` for ids still pending. Also enqueued from `JellyJarApp.onCreate`
- **Kid Mode screen-time limits** (Admin → Kid Mode, only shown/enforced while Kid Mode is on): "Episodes in a Row" (`episodeStreakLimit`, Off/1/2/3/5) and "Daily Screen Time" (`dailyLimitMinutes`, Off/30m–3h). `ScreenTimeManager` (`@Singleton`) holds the streak in memory — a manual start resets it, an auto-advance extends it; player routes carry an `auto` nav arg set only by `navigateToNextEpisode()`. Daily usage lives in DataStore (`screen_time_date/used_ms/bonus_ms/unlimited`), rolled over to the new day inside the same `edit` as any write. PlayerScreen counts only time actually playing (1s sampling, flushed every 10s + on dispose), warns once at 5 min left, and **never cuts a title off mid-play**: the limit is checked at the Up Next decision point (inside the existing single `LaunchedEffect(jellyfinId)` — not a new effect) and the next episode is held back; `TimesUpOverlay` appears when playback ends. A title opened with the budget already spent never starts (`playWhenReady` now defaults false until the check passes) and never opens a Jellyfin session (`playbackAllowed` gates start/progress/stop reports). Overlay has a big "Done" plus a "Grown-ups: more time" link → admin PIN → +1 episode / +30 min / +1 h / No limit today; hidden without a PIN (Admin shows a warning). Uses the device calendar day, so changing the clock resets it — accepted for a home tablet
- ⚠️ Offline sync and screen-time limits are **not yet build-verified or run on device** (no Android tooling in the authoring environment)
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
- Do not add "not build-verified" / "no Android build tooling in this environment" disclaimers to commit messages. Caveat like this in your conversation reply to the user if relevant, never in the commit itself.
