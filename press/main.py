import os
import uuid
import asyncio
import hashlib
import json
import logging
import re
import shutil
import subprocess
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any, Optional
from datetime import datetime, timedelta, timezone

from fastapi import FastAPI, HTTPException, BackgroundTasks
from fastapi.responses import FileResponse, RedirectResponse, StreamingResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, ValidationError, field_validator
from starlette.background import BackgroundTask


def _now_iso() -> str:
    """Current time as a timezone-aware ISO string. All persisted timestamps use this."""
    return datetime.now(timezone.utc).isoformat()


def _parse_ts(value: str) -> datetime:
    """Parse a persisted timestamp, treating legacy naive values as UTC.

    Timestamps written before the move off datetime.utcnow() have no offset. Comparing one of
    those to an aware datetime raises TypeError, which would silently kill the cleanup loop.
    """
    parsed = datetime.fromisoformat(value)
    return parsed.replace(tzinfo=timezone.utc) if parsed.tzinfo is None else parsed


def _atomic_write_json(path: Path, data: Any) -> None:
    """Write JSON via a temp file + rename so a crash mid-write can't truncate the original.

    The temp file must live in the target's own directory for os.replace to be atomic — a
    rename across filesystems is not.
    """
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(data, indent=2))
    os.replace(tmp, path)


# Set at Docker build time from the release tag (see .github/workflows/build-press.yml), so this
# always matches the JellyJar app version released alongside it. "dev" for local/unreleased builds.
PRESS_VERSION = os.environ.get("PRESS_VERSION", "dev")

# The web UI polls these on a timer; they'd otherwise flood the access log every couple seconds.
_QUIET_PATHS = ("GET /jobs ", "GET /api/queue/stats ", "GET /health ")


class _QuietPollingFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        return not any(p in record.getMessage() for p in _QUIET_PATHS)


logging.getLogger("uvicorn.access").addFilter(_QuietPollingFilter())

_active_encoder: str = "libx264"


async def detect_encoder() -> str:
    """Probe hardware encoders in priority order; fall back to libx264."""
    candidates = [
        ("h264_nvenc", [
            "ffmpeg", "-y", "-f", "lavfi", "-i", "color=black:s=128x128:d=0.1",
            "-c:v", "h264_nvenc", "-f", "null", "-",
        ]),
        ("h264_vaapi", [
            "ffmpeg", "-y",
            "-vaapi_device", "/dev/dri/renderD128",
            "-f", "lavfi", "-i", "color=black:s=128x128:d=0.1",
            "-vf", "format=nv12,hwupload",
            "-c:v", "h264_vaapi", "-f", "null", "-",
        ]),
        ("h264_qsv", [
            "ffmpeg", "-y", "-f", "lavfi", "-i", "color=black:s=128x128:d=0.1",
            "-c:v", "h264_qsv", "-f", "null", "-",
        ]),
    ]
    for name, cmd in candidates:
        try:
            proc = await asyncio.create_subprocess_exec(
                *cmd,
                stdout=asyncio.subprocess.DEVNULL,
                stderr=asyncio.subprocess.DEVNULL,
            )
            await proc.wait()
            if proc.returncode == 0:
                print(f"[Press] Hardware encoder: {name}", flush=True)
                return name
        except Exception:
            pass
    print("[Press] No hardware encoder available, using libx264", flush=True)
    return "libx264"


MAX_WORKERS = max(1, int(os.environ.get("MAX_WORKERS", "1")))
_worker_semaphore: asyncio.Semaphore

# Days a completed job's output is kept before automatic cleanup. 0 (default) disables cleanup.
CLEANUP_AFTER_DAYS = float(os.environ.get("CLEANUP_AFTER_DAYS", "0"))
CLEANUP_INTERVAL_SECONDS = 3600

# Processes currently running, keyed by job_id, so DELETE /jobs/{id} can kill an in-progress transcode.
_active_processes: dict[str, asyncio.subprocess.Process] = {}
# Refcounts in-flight GET /download/{job_id} streams so cleanup_loop doesn't unlink a completed
# job's output file out from under a client that's actively reading it.
_active_downloads: dict[str, int] = {}

# Keeps strong references to transcode tasks restarted at startup (asyncio only holds weak ones).
_resume_tasks: list[asyncio.Task] = []

# Per-job SSE subscribers, so /jobs/{id}/stream can push updates the moment a job changes
# instead of clients polling on a timer.
_subscribers: dict[str, list[asyncio.Queue]] = {}


def _publish(job_id: str) -> None:
    for q in _subscribers.get(job_id, []):
        q.put_nowait(None)


def _fail_job(job_id: str, error: str, output: str | None) -> None:
    """Mark a job failed and remove whatever partial output ffmpeg left behind.

    A failed encode still leaves a truncated .mp4 on disk, which sits in /output consuming
    space until someone deletes the job by hand. Only call this on *final* failure — the
    hardware-encoder fallback retries to the same path, so the partial file from the first
    attempt has to survive until that retry has had its turn.
    """
    if output and Path(output).exists():
        Path(output).unlink(missing_ok=True)
    jobs[job_id]["status"] = "failed"
    jobs[job_id]["error"] = error


async def resume_interrupted_jobs():
    """Restart transcodes that were queued/running when the service last stopped."""
    changed = 0
    resumed = 0
    for job in list(jobs.values()):
        if job["status"] != "queued":
            continue
        changed += 1
        source = job["source_path"]
        preset_name = job["preset"]
        if preset_name not in PRESETS:
            job["status"] = "failed"
            job["error"] = f"Preset '{preset_name}' no longer exists"
            job["updated_at"] = _now_iso()
            continue
        if not Path(source).exists():
            job["status"] = "failed"
            job["error"] = f"Source file not found: {source}"
            job["updated_at"] = _now_iso()
            continue
        duration_us = await get_duration_us(source)
        job["duration_seconds"] = (duration_us / 1_000_000) if duration_us else None
        _resume_tasks.append(asyncio.create_task(
            run_transcode(job["job_id"], source, job["output_path"], PRESETS[preset_name], duration_us)
        ))
        resumed += 1
    if changed:
        print(f"[Press] Restarted {resumed} interrupted transcode job(s)", flush=True)
        _save_jobs()


async def cleanup_loop():
    """Periodically remove completed jobs (and their output files) older than CLEANUP_AFTER_DAYS."""
    if CLEANUP_AFTER_DAYS <= 0:
        return
    while True:
        cutoff = datetime.now(timezone.utc) - timedelta(days=CLEANUP_AFTER_DAYS)
        removed = 0
        for job_id, job in list(jobs.items()):
            if job["status"] != "complete":
                continue
            if _active_downloads.get(job_id):
                continue  # a client is actively streaming this job's output right now
            try:
                completed_at = _parse_ts(job["updated_at"])
            except (TypeError, ValueError):
                continue
            if completed_at < cutoff:
                output_path = job.get("output_path")
                if output_path and Path(output_path).exists():
                    Path(output_path).unlink()
                del jobs[job_id]
                removed += 1
        if removed:
            print(f"[Press] Cleanup: removed {removed} job(s) older than {CLEANUP_AFTER_DAYS} day(s)", flush=True)
            _save_jobs()
        await asyncio.sleep(CLEANUP_INTERVAL_SECONDS)


@asynccontextmanager
async def lifespan(app_: FastAPI):
    global _active_encoder, _worker_semaphore
    _worker_semaphore = asyncio.Semaphore(MAX_WORKERS)
    forced = os.environ.get("ENCODER", "").strip()
    if forced:
        print(f"[Press] Encoder forced via env: {forced}", flush=True)
        _active_encoder = forced
    else:
        _active_encoder = await detect_encoder()
    print(f"[Press] Max concurrent transcode workers: {MAX_WORKERS}", flush=True)
    await resume_interrupted_jobs()
    if CLEANUP_AFTER_DAYS > 0:
        print(f"[Press] Output cleanup: jobs older than {CLEANUP_AFTER_DAYS} day(s) will be removed", flush=True)
    cleanup_task = asyncio.create_task(cleanup_loop())
    yield
    cleanup_task.cancel()


app = FastAPI(title="JellyJar Press", version=PRESS_VERSION, lifespan=lifespan)

MEDIA_ROOT = os.environ.get("MEDIA_ROOT", "/media")
OUTPUT_ROOT = os.environ.get("OUTPUT_ROOT", "/output")
CONFIG_ROOT = os.environ.get("CONFIG_ROOT", "/config")
PRESETS_FILE = Path(CONFIG_ROOT) / "presets.json"
JOBS_FILE = Path(CONFIG_ROOT) / "jobs.json"

DEFAULT_PRESETS = {
    "1080p": {
        "video_bitrate": "4000k",
        "audio_bitrate": "192k",
        "scale": "1920:1080",
        "crf": "18",
    },
    "720p": {
        "video_bitrate": "2000k",
        "audio_bitrate": "128k",
        "scale": "1280:720",
        "crf": "22",
    },
}


# Preset values are interpolated straight into ffmpeg's argv and -vf filter graph (see
# build_ffmpeg_command), so a malformed one doesn't fail validation somewhere useful — it
# breaks every subsequent encode with a filter-graph parse error, and persists to disk.
_SCALE_RE = re.compile(r"^\d{1,5}:\d{1,5}$")
_BITRATE_RE = re.compile(r"^\d{1,7}[kKmM]?$")


class PresetConfig(BaseModel):
    video_bitrate: str
    audio_bitrate: str
    scale: str
    crf: str

    @field_validator("scale")
    @classmethod
    def _check_scale(cls, v: str) -> str:
        if not _SCALE_RE.match(v.strip()):
            raise ValueError("scale must be WIDTH:HEIGHT, e.g. 1920:1080")
        return v.strip()

    @field_validator("video_bitrate", "audio_bitrate")
    @classmethod
    def _check_bitrate(cls, v: str) -> str:
        # Kept in sync with parseBitrateBps() in the Android app's Repositories.kt, which
        # estimates output size from these and only understands the same k/M suffixes.
        if not _BITRATE_RE.match(v.strip()):
            raise ValueError("bitrate must be digits with an optional k/M suffix, e.g. 4000k")
        return v.strip()

    @field_validator("crf")
    @classmethod
    def _check_crf(cls, v: str) -> str:
        try:
            crf = int(v.strip())
        except ValueError:
            raise ValueError("crf must be an integer between 0 and 51")
        if not 0 <= crf <= 51:
            raise ValueError("crf must be an integer between 0 and 51")
        return str(crf)


def load_presets() -> dict:
    try:
        raw = json.loads(PRESETS_FILE.read_text())
    except FileNotFoundError:
        return dict(DEFAULT_PRESETS)
    except Exception as e:
        print(f"[Press] Failed to read {PRESETS_FILE}, using defaults: {e}", flush=True)
        return dict(DEFAULT_PRESETS)

    # Drop anything that no longer validates rather than letting a bad preset survive a
    # restart and break encodes long after whatever wrote it.
    valid = {}
    for name, config in raw.items():
        try:
            valid[name] = PresetConfig(**config).model_dump()
        except (ValidationError, TypeError) as e:
            print(f"[Press] Ignoring invalid preset '{name}': {e}", flush=True)
    return valid or dict(DEFAULT_PRESETS)


def save_presets() -> None:
    _atomic_write_json(PRESETS_FILE, PRESETS)


PRESETS = load_presets()


def load_jobs() -> dict:
    try:
        raw = json.loads(JOBS_FILE.read_text())
    except FileNotFoundError:
        return {}
    except Exception as e:
        print(f"[Press] Failed to read {JOBS_FILE}, starting with no jobs: {e}", flush=True)
        return {}

    # Jobs that were mid-transcode when the service stopped were killed with it.
    # Ones with a persisted source_path/preset are re-queued and restarted at
    # startup (see resume_interrupted_jobs); older jobs saved before those
    # fields existed can't be retried automatically.
    now = _now_iso()
    for job in raw.values():
        if job["status"] in ("queued", "running"):
            if job.get("source_path") and job.get("preset"):
                job["status"] = "queued"
                job["progress"] = 0.0
                job["error"] = None
                job["fps"] = None
                job["speed"] = None
                job["eta_seconds"] = None
            else:
                job["status"] = "failed"
                job["error"] = "Interrupted by service restart"
            job["updated_at"] = now
    return raw


def _save_jobs() -> None:
    _atomic_write_json(JOBS_FILE, jobs)


jobs: dict[str, dict] = load_jobs()


class TranscodeRequest(BaseModel):
    source_path: str          # Path relative to MEDIA_ROOT
    preset: str               # "1080p" or "720p"
    output_filename: Optional[str] = None
    display_name: Optional[str] = None   # e.g. "The Matrix" or "Breaking Bad · S01E03 · Title"


class JobStatus(BaseModel):
    job_id: str
    status: str               # queued | running | complete | failed
    progress: Optional[float] = None
    output_path: Optional[str] = None
    output_sha256: Optional[str] = None
    error: Optional[str] = None
    created_at: str
    updated_at: str
    display_name: Optional[str] = None
    duration_seconds: Optional[float] = None   # source media duration
    fps: Optional[float] = None
    speed: Optional[float] = None              # ffmpeg encode speed, e.g. 2.5 = 2.5x realtime
    eta_seconds: Optional[float] = None        # estimated time remaining for this job
    queue_position: Optional[int] = None       # 1-based position among queued (waiting) jobs


PROBE_TIMEOUT_SECONDS = 30


async def get_duration_us(source: str) -> Optional[float]:
    try:
        proc = await asyncio.create_subprocess_exec(
            "ffprobe", "-v", "quiet", "-print_format", "json", "-show_format", source,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.DEVNULL,
        )
        try:
            stdout, _ = await asyncio.wait_for(proc.communicate(), timeout=PROBE_TIMEOUT_SECONDS)
        except asyncio.TimeoutError:
            proc.kill()
            await proc.wait()
            return None
        info = json.loads(stdout)
        return float(info["format"]["duration"]) * 1_000_000
    except Exception:
        return None


# mov_text is the only subtitle codec an MP4 container can carry, and it is text-only. Image
# subtitles (PGS/VobSub, i.e. most Blu-ray and DVD rips) cannot be converted to it — asking
# ffmpeg to try fails the whole encode, so those streams are deliberately left out.
TEXT_SUBTITLE_CODECS = {"subrip", "srt", "ass", "ssa", "mov_text", "text", "webvtt", "subviewer"}


async def probe_text_subtitle_count(source: str) -> int:
    """Number of leading text-based subtitle streams that can be muxed into MP4.

    Returns a count rather than a list of indices because the `-map 0:s:N` selector is indexed
    within the subtitle streams only, in file order. Any image-based subtitle stream truncates
    the run: mapping past it would renumber the remaining picks onto the wrong streams.
    """
    count, _ = await probe_subtitle_plan(source)
    return count


async def probe_subtitle_plan(source: str) -> tuple[int, list[int]]:
    """Leading text-subtitle count plus which of those (by output index) are forced.

    Re-encoding SRT -> mov_text doesn't reliably carry the source's `forced`/`default`
    disposition through to the output track on its own — without it, nothing tells the player
    to auto-select a forced track (e.g. the "translate the aliens" captions), so it silently
    plays with no subtitles even though the track exists in the file. The forced index is
    re-applied explicitly via `-disposition:s:N forced` in build_ffmpeg_command.
    """
    try:
        proc = await asyncio.create_subprocess_exec(
            "ffprobe", "-v", "quiet", "-print_format", "json",
            "-select_streams", "s",
            "-show_entries", "stream=codec_name:stream_disposition=forced",
            source,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.DEVNULL,
        )
        try:
            stdout, _ = await asyncio.wait_for(proc.communicate(), timeout=PROBE_TIMEOUT_SECONDS)
        except asyncio.TimeoutError:
            proc.kill()
            await proc.wait()
            return 0, []
        streams = json.loads(stdout).get("streams", [])
        count = 0
        forced_indices: list[int] = []
        for stream in streams:
            if stream.get("codec_name") not in TEXT_SUBTITLE_CODECS:
                break
            if stream.get("disposition", {}).get("forced") == 1:
                forced_indices.append(count)
            count += 1
        return count, forced_indices
    except Exception:
        return 0, []


async def probe_default_audio_index(source: str) -> int | None:
    """Which audio stream (0-based, among audio streams only) is flagged `default` in the source.

    `-map 0:a?` keeps every audio stream in source order, so this index lines up directly with
    the output stream's `-disposition:a:N`. Returns None if nothing is flagged (nothing to
    override — ffmpeg/the player's own fallback applies) or the source has only one audio
    stream (nothing to distinguish).
    """
    try:
        proc = await asyncio.create_subprocess_exec(
            "ffprobe", "-v", "quiet", "-print_format", "json",
            "-select_streams", "a",
            "-show_entries", "stream_disposition=default",
            source,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.DEVNULL,
        )
        try:
            stdout, _ = await asyncio.wait_for(proc.communicate(), timeout=PROBE_TIMEOUT_SECONDS)
        except asyncio.TimeoutError:
            proc.kill()
            await proc.wait()
            return None
        streams = json.loads(stdout).get("streams", [])
        if len(streams) <= 1:
            return None
        for index, stream in enumerate(streams):
            if stream.get("disposition", {}).get("default") == 1:
                return index
        return None
    except Exception:
        return None


def build_ffmpeg_command(
    source: str,
    output: str,
    preset: dict,
    encoder: str | None = None,
    subtitle_count: int = 0,
    forced_subtitle_indices: list[int] | None = None,
    default_audio_index: int | None = None,
) -> list[str]:
    enc = encoder if encoder is not None else _active_encoder
    scale = preset["scale"]
    sw_vf = (
        f"scale={scale}:force_original_aspect_ratio=decrease,"
        f"pad={scale}:(ow-iw)/2:(oh-ih)/2"
    )
    # Without explicit maps, ffmpeg's default stream selection keeps exactly one video and one
    # audio stream and drops every subtitle — so downloads lost alternate audio tracks and all
    # subtitles, and the player's track picker had nothing to offer offline.
    stream_map = ["-map", "0:v:0", "-map", "0:a?"]
    for index in range(subtitle_count):
        stream_map += ["-map", f"0:s:{index}"]

    common_audio = ["-c:a", "aac", "-b:a", preset["audio_bitrate"]]
    # Re-encoding (not stream-copying) audio doesn't reliably carry the source's `default`
    # disposition through to the output track. The player has no configured preferred-audio-
    # language, so with nothing marked default it just falls back to output track 0 — if the
    # source's default/preferred track (e.g. English, dubbed after the original-language track)
    # isn't first in file order, downloaded playback silently starts on the wrong language even
    # though every track made it into the file. `-map 0:a?` preserves source order, so output
    # audio index N is source audio stream N — stamp the disposition back explicitly rather than
    # relying on ffmpeg to infer it, mirroring the same fix for forced subtitles below.
    if default_audio_index is not None:
        common_audio += [f"-disposition:a:{default_audio_index}", "default"]
    if subtitle_count:
        common_audio += ["-c:s", "mov_text"]
        # Re-encoding SRT -> mov_text doesn't reliably preserve the source's `forced`
        # disposition on its own, so a forced track (e.g. "aliens speaking" captions) can end
        # up in the file but never auto-selected by the player. Stamp it back on explicitly,
        # per output subtitle index (matches the -map order above, so index N here is the same
        # stream as -map 0:s:N).
        for index in forced_subtitle_indices or []:
            common_audio += [f"-disposition:s:{index}", "forced"]
    # The temp file is always "<...>.mp4.part" (see run_transcode) so the finished encode can be
    # atomically renamed into place — but ffmpeg picks a muxer from the filename's last
    # extension, and ".part" isn't one it knows. Forcing the container explicitly means output
    # format selection no longer depends on what the temp filename happens to end in.
    common_audio += ["-f", "mp4", "-movflags", "+faststart", "-progress", "pipe:1"]

    if enc == "h264_vaapi":
        return [
            "ffmpeg", "-y",
            "-vaapi_device", "/dev/dri/renderD128",
            "-i", source,
            *stream_map,
            "-vf", f"{sw_vf},format=nv12,hwupload",
            "-c:v", "h264_vaapi",
            "-b:v", preset["video_bitrate"],
            *common_audio, output,
        ]
    if enc == "h264_qsv":
        return [
            "ffmpeg", "-y",
            "-i", source,
            *stream_map,
            "-vf", sw_vf,
            "-c:v", "h264_qsv",
            "-global_quality", preset["crf"],
            "-b:v", preset["video_bitrate"],
            *common_audio, output,
        ]
    if enc == "h264_nvenc":
        return [
            "ffmpeg", "-y",
            "-i", source,
            *stream_map,
            "-vf", sw_vf,
            "-c:v", "h264_nvenc",
            "-cq", preset["crf"],
            "-preset", "p4",
            "-b:v", preset["video_bitrate"],
            *common_audio, output,
        ]
    # libx264 software fallback
    return [
        "ffmpeg", "-y",
        "-i", source,
        *stream_map,
        "-vf", sw_vf,
        "-c:v", "libx264",
        "-crf", preset["crf"],
        "-preset", "fast",
        "-b:v", preset["video_bitrate"],
        *common_audio, output,
    ]


async def _drain_stderr(process: asyncio.subprocess.Process) -> bytes:
    """Continuously reads ffmpeg's stderr while it runs and keeps only the tail.

    ffmpeg writes verbose per-frame status to stderr by default. If nothing reads that
    pipe while the process runs, the OS pipe buffer fills and ffmpeg blocks on the write —
    which also freezes the `-progress pipe:1` stdout stream the progress bar depends on.
    """
    tail = b""
    while True:
        chunk = await process.stderr.read(8192)
        if not chunk:
            break
        tail = (tail + chunk)[-2000:]
    return tail


async def stream_progress(process: asyncio.subprocess.Process, job_id: str, duration_us: Optional[float]) -> None:
    """Read ffmpeg's `-progress pipe:1` output and update progress/fps/speed/eta on the job."""
    current_us = None
    while True:
        line = await process.stdout.readline()
        if not line:
            break
        if job_id not in jobs:
            break  # job was cancelled/deleted mid-transcode

        decoded = line.decode().strip()

        if decoded.startswith("out_time_us="):
            try:
                val = int(decoded.split("=")[1])
                if val >= 0:
                    current_us = val
            except ValueError:
                pass
        elif decoded.startswith("fps="):
            try:
                jobs[job_id]["fps"] = float(decoded.split("=")[1])
            except ValueError:
                pass
        elif decoded.startswith("speed="):
            raw = decoded.split("=")[1].strip().rstrip("x")
            try:
                jobs[job_id]["speed"] = float(raw) if raw else None
            except ValueError:
                jobs[job_id]["speed"] = None

        if duration_us and current_us and duration_us > 0:
            progress = min((current_us / duration_us) * 100, 100)
            jobs[job_id]["progress"] = round(progress, 1)

            speed = jobs[job_id].get("speed")
            if speed and speed > 0:
                remaining_us = max(duration_us - current_us, 0)
                jobs[job_id]["eta_seconds"] = round(remaining_us / 1_000_000 / speed, 1)

            jobs[job_id]["updated_at"] = _now_iso()
            _publish(job_id)


async def run_transcode(job_id: str, source: str, output: str, preset: dict, duration_us: Optional[float]):
    async with _worker_semaphore:
        if job_id not in jobs:
            return  # cancelled while queued, before it got a chance to start

        jobs[job_id]["status"] = "running"
        jobs[job_id]["updated_at"] = _now_iso()
        _save_jobs()
        _publish(job_id)

        subtitle_count, forced_subtitle_indices = await probe_subtitle_plan(source)
        default_audio_index = await probe_default_audio_index(source)

        # The job can be deleted while probing (a blocking ffprobe call with no lock held on
        # `jobs`) — without this check ffmpeg would still start and produce an untracked file.
        if job_id not in jobs:
            return

        # ffmpeg writes to a job-specific temp file and only the final os.replace() below makes
        # a fully-encoded file appear at `output` — a killed/failed encode never leaves a partial
        # or invalid file at the path other code treats as "this job's output".
        tmp_output = output + ".part"
        cmd = build_ffmpeg_command(
            source, tmp_output, preset,
            subtitle_count=subtitle_count, forced_subtitle_indices=forced_subtitle_indices,
            default_audio_index=default_audio_index,
        )

        try:
            process = await asyncio.create_subprocess_exec(
                *cmd,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
            )
            _active_processes[job_id] = process
            _, stderr_bytes = await asyncio.gather(
                stream_progress(process, job_id, duration_us),
                _drain_stderr(process),
            )
            await process.wait()
            _active_processes.pop(job_id, None)

            if job_id not in jobs:
                Path(tmp_output).unlink(missing_ok=True)
                return  # cancelled mid-transcode; delete_job already removed the output file

            if process.returncode == 0:
                os.replace(tmp_output, output)
                jobs[job_id]["status"] = "complete"
                jobs[job_id]["output_path"] = output
                jobs[job_id]["progress"] = 100.0
                jobs[job_id]["eta_seconds"] = 0.0
            else:
                error_text = stderr_bytes.decode(errors="replace")[-1000:]
                Path(tmp_output).unlink(missing_ok=True)
                if _active_encoder != "libx264":
                    # Hardware encoder failed at runtime — fall back to libx264
                    print(f"[Press] {_active_encoder} failed (rc={process.returncode}), retrying with libx264", flush=True)
                    sw_cmd = build_ffmpeg_command(
                        source, tmp_output, preset, encoder="libx264",
                        subtitle_count=subtitle_count, forced_subtitle_indices=forced_subtitle_indices,
                        default_audio_index=default_audio_index,
                    )
                    sw_process = await asyncio.create_subprocess_exec(
                        *sw_cmd,
                        stdout=asyncio.subprocess.PIPE,
                        stderr=asyncio.subprocess.PIPE,
                    )
                    _active_processes[job_id] = sw_process
                    _, sw_stderr_bytes = await asyncio.gather(
                        stream_progress(sw_process, job_id, duration_us),
                        _drain_stderr(sw_process),
                    )
                    await sw_process.wait()
                    _active_processes.pop(job_id, None)

                    if job_id not in jobs:
                        Path(tmp_output).unlink(missing_ok=True)
                        return  # cancelled during fallback retry

                    sw_stderr = sw_stderr_bytes.decode(errors="replace")[-1000:]
                    if sw_process.returncode == 0:
                        os.replace(tmp_output, output)
                        jobs[job_id]["status"] = "complete"
                        jobs[job_id]["output_path"] = output
                        jobs[job_id]["progress"] = 100.0
                        jobs[job_id]["eta_seconds"] = 0.0
                    else:
                        print(f"[Press] libx264 fallback also failed:\n{sw_stderr}", flush=True)
                        Path(tmp_output).unlink(missing_ok=True)
                        _fail_job(job_id, sw_stderr, output)
                else:
                    print(f"[Press] transcode FAILED (job {job_id}, rc={process.returncode}):\n{error_text}", flush=True)
                    _fail_job(job_id, error_text, output)

        except Exception as e:
            _active_processes.pop(job_id, None)
            Path(tmp_output).unlink(missing_ok=True)
            if job_id in jobs:
                _fail_job(job_id, str(e), output)

        if job_id in jobs and jobs[job_id]["status"] == "complete":
            jobs[job_id]["output_sha256"] = await asyncio.to_thread(_sha256_file, jobs[job_id]["output_path"])

        if job_id in jobs:
            jobs[job_id]["updated_at"] = _now_iso()
            _save_jobs()
            _publish(job_id)


def _sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def _validate_transcode_request(req: TranscodeRequest) -> tuple[Path, dict]:
    """Resolve + validate a request. Raises HTTPException on bad preset or missing source file."""
    if req.preset not in PRESETS:
        raise HTTPException(status_code=400, detail=f"Unknown preset '{req.preset}'. Use: {list(PRESETS.keys())}")

    # pathlib: absolute right-hand path overrides the left, so absolute Jellyfin paths work as-is
    media_root = Path(MEDIA_ROOT).resolve()
    source = (Path(MEDIA_ROOT) / req.source_path).resolve()
    print(f"[Press] transcode request: source_path={req.source_path!r}  resolved={source}", flush=True)
    if media_root != source and media_root not in source.parents:
        raise HTTPException(status_code=400, detail="source_path must resolve within the configured media root")
    if not source.exists():
        print(f"[Press] 404 — file not found at {source}", flush=True)
        raise HTTPException(status_code=404, detail=f"Source file not found: {source}")

    return source, PRESETS[req.preset]


async def _create_job(
    req: TranscodeRequest, background_tasks: BackgroundTasks, source: Path, preset: dict
) -> JobStatus:
    job_id = str(uuid.uuid4())
    # .name strips any directory components (and neutralizes an absolute override or ../
    # traversal), so a client-supplied output_filename can never write outside OUTPUT_ROOT.
    requested_filename = Path(req.output_filename or f"{source.stem}_{req.preset}.mp4").name
    if not requested_filename:
        raise HTTPException(status_code=400, detail="Invalid output_filename")
    # Prefixed with job_id so two jobs can never resolve to the same on-disk path — Press has no
    # auth (LAN-only by design), so any client can pick output_filename, and a collision would
    # let one job's os.replace() silently clobber another job's completed/in-flight output out
    # from under a concurrent /download/{id} stream. The caller's requested name is kept as the
    # download's Content-Disposition filename (see download_file) so this is invisible to them.
    output_filename = f"{job_id}_{requested_filename}"
    output = str(Path(OUTPUT_ROOT) / output_filename)

    duration_us = await get_duration_us(str(source))

    now = _now_iso()
    jobs[job_id] = {
        "job_id": job_id,
        "status": "queued",
        "progress": 0.0,
        "source_path": str(source),
        "preset": req.preset,
        "output_path": output,
        "download_filename": requested_filename,
        "error": None,
        "created_at": now,
        "updated_at": now,
        "display_name": req.display_name,
        "duration_seconds": (duration_us / 1_000_000) if duration_us else None,
        "fps": None,
        "speed": None,
        "eta_seconds": None,
    }
    _save_jobs()

    background_tasks.add_task(run_transcode, job_id, str(source), output, preset, duration_us)

    return _to_job_status(jobs[job_id])


def _failed_job_status(req: TranscodeRequest, detail: str) -> JobStatus:
    """Record a job that couldn't even be started (e.g. batch item with a bad source path)."""
    job_id = str(uuid.uuid4())
    now = _now_iso()
    jobs[job_id] = {
        "job_id": job_id,
        "status": "failed",
        "progress": None,
        "output_path": None,
        "download_filename": None,
        "error": detail,
        "created_at": now,
        "updated_at": now,
        "display_name": req.display_name,
        "duration_seconds": None,
        "fps": None,
        "speed": None,
        "eta_seconds": None,
    }
    return _to_job_status(jobs[job_id])


@app.post("/transcode", response_model=JobStatus)
async def start_transcode(req: TranscodeRequest, background_tasks: BackgroundTasks):
    source, preset = _validate_transcode_request(req)
    return await _create_job(req, background_tasks, source, preset)


class BatchTranscodeRequest(BaseModel):
    items: list[TranscodeRequest]


@app.post("/transcode/batch", response_model=list[JobStatus])
async def start_batch_transcode(req: BatchTranscodeRequest, background_tasks: BackgroundTasks):
    """Queue multiple transcodes (e.g. a whole season) in one call. Bad items are recorded as
    failed jobs rather than aborting the rest of the batch."""
    results = []
    for item in req.items:
        try:
            source, preset = _validate_transcode_request(item)
        except HTTPException as e:
            results.append(_failed_job_status(item, str(e.detail)))
            continue
        results.append(await _create_job(item, background_tasks, source, preset))
    _save_jobs()
    return results


def _queue_position(job_id: str) -> Optional[int]:
    if jobs[job_id]["status"] != "queued":
        return None
    queued = sorted(
        (j for j in jobs.values() if j["status"] == "queued"),
        key=lambda j: j["created_at"],
    )
    for idx, j in enumerate(queued):
        if j["job_id"] == job_id:
            return idx + 1
    return None


def _to_job_status(job: dict) -> JobStatus:
    return JobStatus(**job, queue_position=_queue_position(job["job_id"]))


@app.get("/jobs/{job_id}", response_model=JobStatus)
async def get_job(job_id: str):
    if job_id not in jobs:
        raise HTTPException(status_code=404, detail="Job not found")
    return _to_job_status(jobs[job_id])


@app.get("/jobs", response_model=list[JobStatus])
async def list_jobs():
    return [_to_job_status(j) for j in jobs.values()]


@app.get("/api/queue/stats")
async def queue_stats():
    """Very rough aggregate estimate — not a precise scheduler simulation."""
    running = [j for j in jobs.values() if j["status"] == "running"]
    queued = [j for j in jobs.values() if j["status"] == "queued"]

    running_remaining = sum(
        j["eta_seconds"] if j.get("eta_seconds") is not None else (j.get("duration_seconds") or 0)
        for j in running
    )
    queued_work = sum(j.get("duration_seconds") or 0 for j in queued)

    total_remaining = running_remaining + (queued_work / MAX_WORKERS)

    return {
        "max_workers": MAX_WORKERS,
        "running_count": len(running),
        "queued_count": len(queued),
        "queue_remaining_seconds": round(total_remaining, 1) if (running or queued) else 0.0,
    }


@app.get("/download/{job_id}")
async def download_file(job_id: str):
    if job_id not in jobs:
        raise HTTPException(status_code=404, detail="Job not found")

    job = jobs[job_id]
    if job["status"] != "complete":
        raise HTTPException(status_code=400, detail=f"Job is not complete (status: {job['status']})")

    output_path = job["output_path"]
    if not output_path or not Path(output_path).exists():
        raise HTTPException(status_code=404, detail="Output file not found on disk")

    _active_downloads[job_id] = _active_downloads.get(job_id, 0) + 1

    def _release_download() -> None:
        remaining = _active_downloads.get(job_id, 1) - 1
        if remaining <= 0:
            _active_downloads.pop(job_id, None)
        else:
            _active_downloads[job_id] = remaining

    return FileResponse(
        path=output_path,
        media_type="video/mp4",
        # download_filename is the caller's requested name; the on-disk name is job_id-prefixed
        # for uniqueness (see _create_job) and shouldn't leak into what the client saves as.
        # Legacy jobs saved before this field existed fall back to the on-disk name.
        filename=job.get("download_filename") or Path(output_path).name,
        background=BackgroundTask(_release_download),
    )


@app.delete("/jobs/{job_id}")
async def delete_job(job_id: str):
    if job_id not in jobs:
        raise HTTPException(status_code=404, detail="Job not found")

    job = jobs[job_id]

    # Kill the in-progress ffmpeg process, if any, so it doesn't keep writing to an orphaned file.
    process = _active_processes.get(job_id)
    if process and process.returncode is None:
        process.kill()

    output_path = job.get("output_path")
    del jobs[job_id]
    _save_jobs()
    _publish(job_id)

    if output_path and Path(output_path).exists():
        Path(output_path).unlink()

    return {"deleted": job_id}


@app.delete("/jobs")
async def delete_jobs(status: str | None = None):
    """Bulk-delete jobs (optionally filtered by status, e.g. 'complete' or 'failed').

    Does the same per-job cleanup as DELETE /jobs/{job_id} but writes jobs.json once for the
    whole batch instead of once per job, so clearing a large history doesn't do O(N) redundant
    disk rewrites.
    """
    target_ids = [
        job_id for job_id, job in jobs.items()
        if status is None or job.get("status") == status
    ]

    deleted: list[str] = []
    for job_id in target_ids:
        job = jobs[job_id]

        process = _active_processes.get(job_id)
        if process and process.returncode is None:
            process.kill()

        output_path = job.get("output_path")
        del jobs[job_id]
        deleted.append(job_id)

        if output_path and Path(output_path).exists():
            Path(output_path).unlink()

    if deleted:
        _save_jobs()
        for job_id in deleted:
            _publish(job_id)

    return {"deleted": deleted}


@app.get("/jobs/{job_id}/stream")
async def stream_job(job_id: str):
    """Server-Sent Events stream of job status, pushed the moment it changes (progress, fps,
    speed, status transitions) instead of making clients poll on a timer."""
    if job_id not in jobs:
        raise HTTPException(status_code=404, detail="Job not found")

    async def event_gen():
        q: asyncio.Queue = asyncio.Queue()
        _subscribers.setdefault(job_id, []).append(q)
        try:
            yield f"data: {json.dumps(_to_job_status(jobs[job_id]).model_dump())}\n\n"
            while True:
                try:
                    await asyncio.wait_for(q.get(), timeout=15)
                except asyncio.TimeoutError:
                    yield ": ping\n\n"  # keep-alive so proxies/NAT don't drop the idle connection
                    continue
                if job_id not in jobs:
                    break
                yield f"data: {json.dumps(_to_job_status(jobs[job_id]).model_dump())}\n\n"
                if jobs[job_id]["status"] in ("complete", "failed"):
                    break
        finally:
            # .get(job_id, []) hands back a throwaway list once the key is gone, and .remove()
            # on it raises ValueError out of this cleanup. Prune the key when it empties so
            # _subscribers doesn't accumulate an empty list per completed job either.
            subs = _subscribers.get(job_id)
            if subs is not None:
                try:
                    subs.remove(q)
                except ValueError:
                    pass
                if not subs:
                    _subscribers.pop(job_id, None)

    return StreamingResponse(event_gen(), media_type="text/event-stream")


@app.get("/health")
async def health():
    return {
        "status": "ok",
        "version": PRESS_VERSION,
        "media_root": MEDIA_ROOT,
        "output_root": OUTPUT_ROOT,
        "encoder": _active_encoder,
    }


@app.get("/api/disk")
async def disk_usage():
    """Usage of the volume backing OUTPUT_ROOT, plus how much of that is JellyJar's own output."""
    usage = shutil.disk_usage(OUTPUT_ROOT)
    output_bytes = sum(f.stat().st_size for f in Path(OUTPUT_ROOT).rglob("*") if f.is_file())
    return {
        "total": usage.total,
        "used": usage.used,
        "free": usage.free,
        "output_bytes": output_bytes,
    }


@app.get("/presets")
async def get_presets():
    return list(PRESETS.keys())


# ─── Web UI + preset management ──────────────────────────────────────────────
# Preset edits persist to $CONFIG_ROOT/presets.json (see save_presets), same as jobs.json.

app.mount("/static", StaticFiles(directory=Path(__file__).parent / "static"), name="static")


@app.get("/", include_in_schema=False)
async def ui_root():
    return RedirectResponse(url="/static/index.html")


@app.get("/api/presets", response_model=dict[str, PresetConfig])
async def get_preset_details():
    return PRESETS


@app.put("/api/presets/{name}", response_model=dict[str, PresetConfig])
async def upsert_preset(name: str, config: PresetConfig):
    PRESETS[name] = config.model_dump()
    save_presets()
    return PRESETS


@app.delete("/api/presets/{name}")
async def delete_preset(name: str):
    if name not in PRESETS:
        raise HTTPException(status_code=404, detail="Preset not found")
    if len(PRESETS) == 1:
        raise HTTPException(status_code=400, detail="Cannot delete the last remaining preset")
    del PRESETS[name]
    save_presets()
    return {"deleted": name}
