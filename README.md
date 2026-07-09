# JellyJar

A clean, minimal Android media player that bridges Jellyfin with offline playback via
transcoded local copies.

## Architecture

```
Jellyfin Server  ──►  JellyJar Android App  ──►  local ExoPlayer playback
                              │
                         Press (ffmpeg)
                              │
                    shared Docker volume (same media as Jellyfin)
```

## Quick Start

### 1. Configure Press

Edit `docker-compose.yml` — point the volume at your Jellyfin media root:

```yaml
volumes:
  - /your/actual/media/path:/media:ro
```

Start Press:

```bash
docker compose up -d jellyjar-press
```

Verify it's running:

```bash
curl http://localhost:8090/health
```

By default Press transcodes one job at a time (most hardware encoders only support a single
session anyway). To allow more concurrent jobs, set `MAX_WORKERS` in `docker-compose.yml`:

```yaml
environment:
  MAX_WORKERS: 1   # concurrent transcode jobs
```

### Web UI

Press serves a small built-in dashboard at `http://<press-host>:8090/` — no extra setup needed.
It shows:

- **Now Encoding** — jobs currently transcoding, with live progress, FPS, encode speed, and ETA
- **Queued** — jobs waiting for a free worker, with position and estimated duration
- **History** — completed/failed jobs, with download and delete
- A summary of running/queued counts and estimated total time remaining for the queue
- **Presets** — view, edit, add, or delete transcode presets (scale, video/audio bitrate, CRF) at runtime

Preset edits persist across container restarts — they're written to `presets.json` on the
`jellyjar-config` volume (mounted at `/config`, override with `CONFIG_ROOT`). Job history is
still in-memory only and resets when the Press container restarts.

### 2. Build the Android APK

Open the `android/` folder in Android Studio. Let Gradle sync, then:

- **Run on device**: connect your tablet via USB and hit Run
- **Build APK**: Build → Build Bundle(s) / APK(s) → Build APK(s)

The APK will be at `android/app/build/outputs/apk/debug/app-debug.apk`.

### 3. Sideload to your tablet

Enable "Install from unknown sources" on the tablet, then:

```bash
adb install android/app/build/outputs/apk/debug/app-debug.apk
```

Or copy the APK to the tablet and open it in a file manager.

### 4. First-run setup in the app

1. Tap the ⚙ gear icon (bottom-right of library screen)
2. No PIN is set by default — you'll go straight to Settings
3. Enter your Jellyfin URL (e.g. `http://192.168.1.10:8096`) and credentials
4. Enter your Press URL (e.g. `http://192.168.1.10:8090`)
5. Set a download path (e.g. `/sdcard/JellyJar`)
6. Optionally set an admin PIN
7. Tap **Save Settings**

## Usage

- **Browse**: Tap any poster to open the detail screen
- **Download**: (Admin unlocked) tap **Download**, choose 1080p or 720p
- **Play**: Once downloaded, tap **Play** — plays locally via ExoPlayer
- **Offline**: When not connected to your network, only downloaded files are shown

## Press API

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/transcode` | Start a transcode job |
| GET | `/jobs` | List all jobs (queued/running/complete/failed) |
| GET | `/jobs/{id}` | Poll job status, progress, fps, speed, ETA |
| GET | `/download/{id}` | Download completed file |
| DELETE | `/jobs/{id}` | Cancel job + delete output |
| GET | `/health` | Health check |
| GET | `/presets` | List available preset names (used by the Android app) |
| GET | `/api/presets` | Full preset configs (scale, bitrate, CRF) |
| PUT | `/api/presets/{name}` | Add or update a preset |
| DELETE | `/api/presets/{name}` | Delete a preset |
| GET | `/api/queue/stats` | Aggregate queue stats (running/queued counts, est. time remaining) |
| GET | `/` | Web UI (queue, history, preset editor) |

## Project Structure

```
jellyjar/
├── docker-compose.yml
├── press/
│   ├── Dockerfile
│   ├── main.py          # FastAPI ffmpeg wrapper
│   ├── requirements.txt
│   └── static/
│       └── index.html   # Web UI (queue, history, preset editor)
└── android/
    ├── build.gradle.kts
    ├── settings.gradle.kts
    ├── gradle/libs.versions.toml
    └── app/src/main/
        ├── AndroidManifest.xml
        └── kotlin/com/fuzzymistborn/jellyjar/
            ├── JellyJarApp.kt
            ├── MainActivity.kt      # Navigation host
            ├── api/                 # Retrofit service interfaces
            ├── data/
            │   ├── local/           # Room database
            │   └── repository/      # Business logic
            ├── di/                  # Hilt modules
            ├── model/               # Data classes
            ├── ui/
            │   ├── screens/         # Compose screens
            │   ├── theme/           # Colors, typography
            │   └── viewmodel/       # ViewModels
            ├── util/                # NetworkMonitor
            └── worker/              # WorkManager download worker
```
