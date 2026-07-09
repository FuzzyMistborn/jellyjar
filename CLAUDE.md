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
            │       ├── SettingsRepository.kt
            │       └── SettingsExt.kt
            ├── di/AppModule.kt             # Hilt, OkHttpClient injection
            ├── model/Models.kt             # All @SerializedName PascalCase for Jellyfin JSON
            ├── ui/
            │   ├── screens/
            │   │   ├── LibraryScreen.kt    # Home screen with library tiles + Downloads tile
            │   │   ├── DetailScreen.kt     # Movie/Series detail, inline seasons+episodes, download controls
            │   │   ├── SeasonsScreen.kt    # Standalone seasons browser (not used in main flow)
            │   │   ├── AdminScreen.kt      # Settings, PIN gate, folder picker for download path
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
| `/jobs` | GET | Lists all jobs |
| `/jobs/{id}` | GET | Gets job status + progress |
| `/download/{id}` | GET | Streams completed MP4 file |
| `/jobs/{id}` | DELETE | Deletes job + output file |

**TranscodeRequest body:**
```json
{ "source_path": "/mnt/kids/movie.mkv", "preset": "1080p", "output_filename": "optional.mp4" }
```
- `source_path` is the **absolute path** as returned by Jellyfin's `MediaSources[].Path`
- Press accepts absolute paths directly (no MEDIA_ROOT prepending when path is absolute)

## Press Docker Volumes
Media mounts mirror Jellyfin's container paths exactly:
```yaml
volumes:
  - /mnt/Media/Movies:/mnt/movies:ro
  - /mnt/Media/TV Shows:/mnt/tv:ro
  - jellyjar-output:/output
environment:
  MEDIA_ROOT: /mnt
  OUTPUT_ROOT: /output
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
- ✅ Library tile artwork loading (backdrop → primary image fallback)

## In Progress / Not Yet Tested
- ⏳ Full download→file→playback pipeline end-to-end
- ⏳ Offline playback from Downloads home tile
- ⏳ Download progress polling / DownloadWorker integration
- ⏳ Season download progress tracking in UI

## Server Details
- Jellyfin: `http://192.168.50.24:8096`
- Press: `http://192.168.50.24:8090`
- Test device: Android tablet emulator (API 35)

## Build Notes
- `buildToolsVersion = "36.0.0"` required — build-tools 34.0.0 is corrupted on this machine
- KSP warnings about `No dependencies reported for generated source` are harmless (Hilt codegen bug, filed upstream)
- Configuration cache is enabled (`org.gradle.configuration-cache=true`)
- Run on device: `ujust` or standard Android Studio deploy
