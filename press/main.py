import os
import uuid
import asyncio
import glob
import hashlib
import json
import logging
import re
import shutil
import socket
import subprocess
import time
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any, Optional
from datetime import datetime, timedelta, timezone

import httpx
from fastapi import FastAPI, HTTPException, Request, Response
from fastapi.responses import FileResponse, JSONResponse, RedirectResponse, StreamingResponse
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

VAAPI_DEVICE = "/dev/dri/renderD128"
# Decode + scale on the GPU too for h264_nvenc / h264_vaapi, not just encode (see
# build_ffmpeg_command). On by default; HW_DECODE=0 goes straight to the CPU-decode pipeline,
# e.g. if a driver produces bad output rather than failing outright.
HW_DECODE = os.environ.get("HW_DECODE", "1").strip().lower() not in ("0", "false", "no", "off")
_HW_DECODE_ENCODERS = ("h264_nvenc", "h264_vaapi")


async def detect_encoder() -> str:
    """Probe hardware encoders in priority order; fall back to libx264."""
    candidates = [
        ("h264_nvenc", [
            "ffmpeg", "-y", "-f", "lavfi", "-i", "color=black:s=128x128:d=0.1",
            "-c:v", "h264_nvenc", "-f", "null", "-",
        ]),
        ("h264_vaapi", [
            "ffmpeg", "-y",
            "-vaapi_device", VAAPI_DEVICE,
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


# standalone (default): one container does everything, exactly as before.
# coordinator: same as standalone, but also hands queued jobs to remote `worker` containers.
# worker: no API of its own — polls a coordinator for jobs and runs ffmpeg on this host's hardware.
ROLE = os.environ.get("PRESS_ROLE", "standalone").strip().lower()
if ROLE not in ("standalone", "coordinator", "worker"):
    raise RuntimeError(f"PRESS_ROLE must be standalone, coordinator or worker (got {ROLE!r})")

# Concurrent local ffmpeg slots. A coordinator may set 0 to dispatch only and leave all the
# encoding to workers; everywhere else at least one slot is needed for anything to run.
MAX_WORKERS = max(0 if ROLE == "coordinator" else 1, int(os.environ.get("MAX_WORKERS", "1")))

LOCAL_WORKER_ID = "local"
# A remote worker's claim on a job lapses if it stops heartbeating for this long; the job is
# then re-queued for someone else. Also how long an idle worker stays listed as alive (x2).
LEASE_SECONDS = float(os.environ.get("WORKER_LEASE_SECONDS", "30"))
LEASE_CHECK_SECONDS = 5
# A job whose worker vanishes this many times is failed rather than re-queued forever (e.g. a
# source that OOM-kills every worker that touches it).
MAX_JOB_ATTEMPTS = 3

# Worker-role settings.
COORDINATOR_URL = os.environ.get("COORDINATOR_URL", "").strip().rstrip("/")
WORKER_NAME = os.environ.get("WORKER_NAME", "").strip() or socket.gethostname()
WORKER_ID = str(uuid.uuid4())
WORKER_HEARTBEAT_SECONDS = float(os.environ.get("WORKER_HEARTBEAT_SECONDS", "2"))
WORKER_POLL_SECONDS = float(os.environ.get("WORKER_POLL_SECONDS", "3"))

# Days a completed job's output is kept before automatic cleanup. 0 (default) disables cleanup.
CLEANUP_AFTER_DAYS = float(os.environ.get("CLEANUP_AFTER_DAYS", "0"))
CLEANUP_INTERVAL_SECONDS = 3600

# Processes currently running, keyed by job_id, so DELETE /jobs/{id} can kill an in-progress transcode.
_active_processes: dict[str, asyncio.subprocess.Process] = {}
# Refcounts in-flight GET /download/{job_id} streams so cleanup_loop doesn't unlink a completed
# job's output file out from under a client that's actively reading it.
_active_downloads: dict[str, int] = {}

# Keeps strong references to background tasks (asyncio only holds weak ones).
_bg_tasks: set[asyncio.Task] = set()

# Set whenever a job is queued so idle local slots pick it up immediately instead of on their
# next poll.
_queue_event = asyncio.Event()

# Remote workers seen recently, keyed by worker_id. In-memory only: workers re-announce
# themselves on every claim, so nothing needs to survive a coordinator restart.
_workers: dict[str, dict] = {}

# Worker side: after handing a job back because this host can't run it (media or output mount
# missing), stop claiming for a while instead of grabbing — and bouncing — the rest of the queue.
WORKER_UNHEALTHY_BACKOFF_SECONDS = 60.0
_worker_unhealthy_until = 0.0


def _tmp_path(output: str, claim_id: str) -> str:
    """Where one claim's encode is written before it's moved to `output`.

    Per claim, not per job: after a restart or a lapsed lease, the old claimant can still be
    running (and later cleaning up) while a new one encodes the same job. With a shared name
    the stale one deleted — or, via ffmpeg's +faststart reopen, read and renamed — the live
    one's file.
    """
    return f"{output}.{claim_id}.part"


def _remove_part_files(output: str) -> None:
    """Delete every claim's temp file for this output, plus the legacy pre-claim-id name."""
    out = Path(output)
    Path(output + ".part").unlink(missing_ok=True)
    for part in out.parent.glob(glob.escape(out.name) + ".*.part"):
        part.unlink(missing_ok=True)

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
    """Check jobs that were queued/running when the service last stopped.

    load_jobs() has already put them back in the queue, and the local slots / workers pick them
    up from there. This only refreshes durations and fails the ones that can no longer run.
    """
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
        # Runs before any local slot starts or any worker request is served, so everything
        # matched here belongs to a claim that died with (or was stripped by) the restart. A
        # worker still encoding under a stripped claim is told to cancel on its next heartbeat,
        # and couldn't publish its result anyway (see worker_complete).
        if job.get("output_path"):
            _remove_part_files(job["output_path"])
        duration_us = await get_duration_us(source)
        job["duration_seconds"] = (duration_us / 1_000_000) if duration_us else None
        resumed += 1
    if changed:
        print(f"[Press] Re-queued {resumed} interrupted transcode job(s)", flush=True)
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
    global _active_encoder
    forced = os.environ.get("ENCODER", "").strip()
    if forced:
        print(f"[Press] Encoder forced via env: {forced}", flush=True)
        _active_encoder = forced
    else:
        _active_encoder = await detect_encoder()

    if ROLE == "worker":
        if not COORDINATOR_URL:
            raise RuntimeError("COORDINATOR_URL must be set when PRESS_ROLE=worker")
        print(f"[Press] Worker '{WORKER_NAME}' -> {COORDINATOR_URL} "
              f"({MAX_WORKERS} slot(s), encoder {_active_encoder})", flush=True)
        worker_task = asyncio.create_task(run_worker())
        yield
        worker_task.cancel()
        return

    print(f"[Press] Role: {ROLE}; local transcode slots: {MAX_WORKERS}", flush=True)
    await resume_interrupted_jobs()
    if CLEANUP_AFTER_DAYS > 0:
        print(f"[Press] Output cleanup: jobs older than {CLEANUP_AFTER_DAYS} day(s) will be removed", flush=True)
    tasks = [asyncio.create_task(cleanup_loop()), asyncio.create_task(lease_loop())]
    tasks += [asyncio.create_task(local_slot()) for _ in range(MAX_WORKERS)]
    yield
    for task in tasks:
        task.cancel()


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
                # Any worker still encoding this holds a claim that's now stale.
                for key in ("worker_id", "worker", "claim_id", "lease_expires_at", "finalizing"):
                    job.pop(key, None)
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
    worker: Optional[str] = None               # which host is (or last was) encoding this job


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
# ISO 639-1 (two-letter) and 639-2/B ("bibliographic") codes -> 639-2/T, which is what MKV/MP4
# track tags mostly use. Lets AUDIO_LANGUAGES say "en" or "eng", and matches a file tagged "fre"
# against one tagged "fra". Codes not listed pass through unchanged, so any 639-2 code still works.
_LANGUAGE_ALIASES = {
    "ar": "ara", "bg": "bul", "bn": "ben", "ca": "cat", "cs": "ces", "cze": "ces", "cy": "cym",
    "wel": "cym", "da": "dan", "de": "deu", "ger": "deu", "el": "ell", "gre": "ell", "en": "eng",
    "es": "spa", "et": "est", "eu": "eus", "baq": "eus", "fa": "fas", "per": "fas", "fi": "fin",
    "fr": "fra", "fre": "fra", "ga": "gle", "gl": "glg", "he": "heb", "hi": "hin",
    "hr": "hrv", "hu": "hun", "hy": "hye", "arm": "hye", "id": "ind", "is": "isl", "ice": "isl",
    "it": "ita", "ja": "jpn", "ka": "kat", "geo": "kat", "ko": "kor", "lt": "lit", "lv": "lav",
    "mk": "mkd", "mac": "mkd", "ms": "msa", "may": "msa", "nb": "nob", "nl": "nld", "dut": "nld",
    "nn": "nno", "no": "nor", "pl": "pol", "pt": "por", "ro": "ron", "rum": "ron", "ru": "rus",
    "sk": "slk", "slo": "slk", "sl": "slv", "sq": "sqi", "alb": "sqi", "sr": "srp", "sv": "swe",
    "ta": "tam", "te": "tel", "th": "tha", "tl": "tgl", "tr": "tur", "uk": "ukr", "ur": "urd",
    "vi": "vie", "zh": "zho", "chi": "zho",
}


def _normalize_language(tag: str) -> str:
    """"en", "eng", "EN-us" -> "eng". Region/script subtags (BCP 47) are dropped."""
    base = tag.strip().lower().replace("_", "-").split("-")[0]
    return _LANGUAGE_ALIASES.get(base, base)


# Audio tracks to keep in downloads, as language codes — two-letter ("en") or three-letter
# ("eng") both work, e.g. "en,ja". The source's default track is always kept on top of these.
# Empty keeps every track. Each kept track costs a decode + AAC encode on the CPU — the GPU
# pipeline doesn't touch audio — so on a Blu-ray remux with a lossless 7.1 main track and
# several dubs, audio is most of the CPU load.
AUDIO_LANGUAGES = [
    _normalize_language(lang) for lang in os.environ.get("AUDIO_LANGUAGES", "").split(",") if lang.strip()
]
# Commentary is never kept (unless it's somehow the default track): it's rarely wanted on a tablet
# and it's a whole extra audio encode. Matched by disposition or, since many files don't set that,
# by track title.
_COMMENTARY_RE = re.compile(r"commentary", re.IGNORECASE)

TEXT_SUBTITLE_CODECS = {"subrip", "srt", "ass", "ssa", "mov_text", "text", "webvtt", "subviewer"}


async def probe_text_subtitle_count(source: str) -> int:
    """Number of leading text-based subtitle streams that can be muxed into MP4.

    Returns a count rather than a list of indices because the `-map 0:s:N` selector is indexed
    within the subtitle streams only, in file order. Any image-based subtitle stream truncates
    the run: mapping past it would renumber the remaining picks onto the wrong streams.
    """
    count, _ = await probe_subtitle_plan(source)
    return count


async def probe_subtitle_plan(source: str) -> tuple[int, list[str]]:
    """Leading text-subtitle count plus each of those tracks' disposition, by output index.

    The dispositions are applied explicitly via `-disposition:s:N` in build_ffmpeg_command, as
    "default", "forced", "default+forced" or "0", because re-encoding SRT -> mov_text doesn't
    reliably carry the source's flags through on its own. Writing "0" for an unflagged track
    stops ffmpeg from inventing a `default` on it — except that the MP4 muxer always enables at
    least one track per type, so a file whose only subtitle track is unflagged still reads back
    as default=1.

    Note that the app's player doesn't act on any of this: Media3's MP4 extractor never reads
    track selection flags, so offline playback picks audio by device language and leaves
    subtitles off unless they match the system captioning language. The flags are kept
    accurate for other players, and in case the app ever reads them itself.
    """
    try:
        proc = await asyncio.create_subprocess_exec(
            "ffprobe", "-v", "quiet", "-print_format", "json",
            "-select_streams", "s",
            "-show_entries", "stream=codec_name:stream_disposition=default,forced",
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
        dispositions: list[str] = []
        for stream in streams:
            if stream.get("codec_name") not in TEXT_SUBTITLE_CODECS:
                break
            flags = stream.get("disposition", {})
            dispositions.append(
                "+".join(f for f in ("default", "forced") if flags.get(f) == 1) or "0"
            )
        return len(dispositions), dispositions
    except Exception:
        return 0, []


async def probe_audio_plan(source: str, languages: list[str]) -> Optional[dict]:
    """Which audio streams to keep, and how to handle each.

    Returns {"tracks": [{"index", "language", "copy", "downmix"}, ...], "default": output index | None}, where
    `index` is the stream's position among the source's audio streams (for `-map 0:a:N`) and the
    tracks are in source order, so a track's position in the list is its output audio index.
    Returns None when the probe fails, and build_ffmpeg_command then keeps every track.

    Kept: the default track (the first track when none is flagged — that's what players pick)
    plus any track whose language is in `languages` or is untagged, minus commentary. Empty `languages` keeps
    everything but commentary. Every track is downmixed to stereo — downloads are for tablets
    and phones — and a track that's already stereo-or-less AAC is stream-copied, not re-encoded.
    """
    try:
        proc = await asyncio.create_subprocess_exec(
            "ffprobe", "-v", "quiet", "-print_format", "json",
            "-select_streams", "a",
            "-show_entries", "stream=codec_name,channels:stream_tags=language,title"
                             ":stream_disposition=default,comment",
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
    except Exception:
        return None
    if not streams:
        return None

    default_index = next(
        (i for i, st in enumerate(streams) if st.get("disposition", {}).get("default") == 1), 0,
    )

    def is_commentary(st: dict) -> bool:
        return (st.get("disposition", {}).get("comment") == 1
                or bool(_COMMENTARY_RE.search(st.get("tags", {}).get("title", ""))))

    tracks = []
    default_out = None
    for i, st in enumerate(streams):
        language = _normalize_language(st.get("tags", {}).get("language", ""))
        # Untagged ("" / "und") tracks are kept: there's no telling they aren't a wanted language.
        unwanted_language = languages and language not in ("", "und") and language not in languages
        if i != default_index and (is_commentary(st) or unwanted_language):
            continue
        if i == default_index:
            default_out = len(tracks)
        channels = st.get("channels") or 0
        tracks.append({
            "index": i,
            # Written back as metadata: MP4 only stores three-letter codes, so a track tagged
            # "fr" in the source would otherwise lose its language entirely.
            "language": language if len(language) == 3 else None,
            "copy": st.get("codec_name") == "aac" and 0 < channels <= 2,
            # Unknown channel count: downmix anyway; -ac 2 on a mono track is harmless.
            "downmix": channels != 1 and channels != 2,
        })
    return {"tracks": tracks, "default": default_out}


def build_ffmpeg_command(
    source: str,
    output: str,
    preset: dict,
    encoder: str | None = None,
    subtitle_count: int = 0,
    subtitle_dispositions: list[str] | None = None,
    audio_plan: Optional[dict] = None,
    hw_decode: bool = False,
) -> list[str]:
    """Build the ffmpeg argv for one encode attempt.

    `hw_decode` (h264_nvenc / h264_vaapi only) keeps the whole video pipeline on the GPU:
    decode into GPU memory, then scale + pad with the GPU filters, so frames never round-trip
    through system RAM. It fails outright — rather than silently running slower — when the GPU
    can't decode the source (ffmpeg falls back to software decode and the GPU filters then
    reject the CPU frames), which is what lets execute_ffmpeg retry with CPU decoding.
    """
    enc = encoder if encoder is not None else _active_encoder
    scale = preset["scale"]
    sw_vf = (
        f"scale={scale}:force_original_aspect_ratio=decrease,"
        f"pad={scale}:(ow-iw)/2:(oh-ih)/2"
    )
    width, height = scale.split(":")
    # Same letterbox as sw_vf. 10-bit sources decode to p010, which h264_nvenc/h264_vaapi can't
    # take, hence format=nv12. Dimensions and offsets are kept even for the 4:2:0 chroma planes
    # (the software pad filter rounds these itself; the GPU ones aren't documented to).
    hw_fit = f"w={width}:h={height}:force_original_aspect_ratio=decrease:force_divisible_by=2:format=nv12"
    hw_pad = f"w={width}:h={height}:x=trunc((ow-iw)/4)*2:y=trunc((oh-ih)/4)*2"
    # Without explicit maps, ffmpeg's default stream selection keeps exactly one video and one
    # audio stream and drops every subtitle — so downloads lost alternate audio tracks and all
    # subtitles, and the player's track picker had nothing to offer offline.
    stream_map = ["-map", "0:v:0"]
    if audio_plan is None:
        # Probe failed: keep every track, re-encoded and downmixed, rather than guess.
        stream_map += ["-map", "0:a?"]
        common_audio = ["-c:a", "aac", "-b:a", preset["audio_bitrate"], "-ac", "2"]
    else:
        common_audio = []
        for out, track in enumerate(audio_plan["tracks"]):
            stream_map += ["-map", f"0:a:{track['index']}"]
            if track["copy"]:
                common_audio += [f"-c:a:{out}", "copy"]
            else:
                common_audio += [f"-c:a:{out}", "aac", f"-b:a:{out}", preset["audio_bitrate"]]
                if track["downmix"]:
                    common_audio += [f"-ac:a:{out}", "2"]
            if track["language"]:
                common_audio += [f"-metadata:s:a:{out}", f"language={track['language']}"]
            # Re-encoding (not stream-copying) audio doesn't reliably carry the source's `default`
            # disposition through to the output track. The player has no configured preferred-
            # audio-language, so with nothing marked default it just falls back to output track
            # 0 — if the source's default track (e.g. English, dubbed after the original-language
            # track) isn't first, downloaded playback silently starts on the wrong language. Set
            # every output track's disposition explicitly, so exactly the source default is
            # flagged, mirroring the same fix for forced subtitles below.
            if len(audio_plan["tracks"]) > 1:
                flag = "default" if out == audio_plan["default"] else "0"
                common_audio += [f"-disposition:a:{out}", flag]
    for index in range(subtitle_count):
        stream_map += ["-map", f"0:s:{index}"]

    if subtitle_count:
        common_audio += ["-c:s", "mov_text"]
        # Stamp each track's source flags back on explicitly (see probe_subtitle_plan), per
        # output subtitle index — matches the -map order above, so index N here is the same
        # stream as -map 0:s:N.
        for index, disposition in enumerate(subtitle_dispositions or []):
            common_audio += [f"-disposition:s:{index}", disposition]
    # The temp file is always "<...>.mp4.<claim_id>.part" (see _tmp_path) so the finished encode can be
    # atomically renamed into place — but ffmpeg picks a muxer from the filename's last
    # extension, and ".part" isn't one it knows. Forcing the container explicitly means output
    # format selection no longer depends on what the temp filename happens to end in.
    common_audio += ["-f", "mp4", "-movflags", "+faststart", "-progress", "pipe:1"]

    if enc == "h264_vaapi" and hw_decode:
        return [
            "ffmpeg", "-y",
            "-init_hw_device", f"vaapi=va:{VAAPI_DEVICE}",
            "-hwaccel", "vaapi", "-hwaccel_device", "va", "-hwaccel_output_format", "vaapi",
            "-filter_hw_device", "va",
            "-i", source,
            *stream_map,
            "-vf", f"scale_vaapi={hw_fit},pad_vaapi={hw_pad}",
            "-c:v", "h264_vaapi",
            "-b:v", preset["video_bitrate"],
            *common_audio, output,
        ]
    if enc == "h264_vaapi":
        return [
            "ffmpeg", "-y",
            "-vaapi_device", VAAPI_DEVICE,
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
    if enc == "h264_nvenc" and hw_decode:
        return [
            "ffmpeg", "-y",
            "-hwaccel", "cuda", "-hwaccel_output_format", "cuda",
            "-i", source,
            *stream_map,
            "-vf", f"scale_cuda={hw_fit},pad_cuda={hw_pad}",
            "-c:v", "h264_nvenc",
            "-cq", preset["crf"],
            "-preset", "p4",
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


ProgressCallback = Any  # Callable[[float, Optional[float], Optional[float], Optional[float]], None]


async def stream_progress(
    process: asyncio.subprocess.Process,
    duration_us: Optional[float],
    on_progress: ProgressCallback,
    is_cancelled: Any,
) -> None:
    """Read ffmpeg's `-progress pipe:1` output and report progress/fps/speed/eta via on_progress."""
    current_us = None
    fps = None
    speed = None
    while True:
        line = await process.stdout.readline()
        if not line:
            break
        if is_cancelled():
            # Job was deleted mid-transcode. Whoever cancelled normally kills ffmpeg too, but a
            # stopped progress reader with a live process would otherwise run to completion.
            if process.returncode is None:
                process.kill()
            break

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
                fps = float(decoded.split("=")[1])
            except ValueError:
                pass
        elif decoded.startswith("speed="):
            raw = decoded.split("=")[1].strip().rstrip("x")
            try:
                speed = float(raw) if raw else None
            except ValueError:
                speed = None

        if duration_us and current_us and duration_us > 0:
            progress = round(min((current_us / duration_us) * 100, 100), 1)
            eta = None
            if speed and speed > 0:
                remaining_us = max(duration_us - current_us, 0)
                eta = round(remaining_us / 1_000_000 / speed, 1)
            on_progress(progress, fps, speed, eta)


async def _run_ffmpeg_once(
    cmd: list[str],
    duration_us: Optional[float],
    on_progress: ProgressCallback,
    is_cancelled: Any,
    on_process: Any,
) -> tuple[int, str]:
    process = await asyncio.create_subprocess_exec(
        *cmd,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    on_process(process)
    try:
        _, stderr_bytes = await asyncio.gather(
            stream_progress(process, duration_us, on_progress, is_cancelled),
            _drain_stderr(process),
        )
        await process.wait()
    finally:
        on_process(None)
    return process.returncode, stderr_bytes.decode(errors="replace")[-1000:]


def _describe_attempt(encoder: str, hw_decode: bool) -> str:
    if encoder == "libx264":
        return encoder
    return f"{encoder} ({'GPU' if hw_decode else 'CPU'} decode)"


async def execute_ffmpeg(
    label: str,
    source: str,
    output: str,
    tmp_output: str,
    preset: dict,
    duration_us: Optional[float],
    on_progress: ProgressCallback,
    is_cancelled: Any,
    on_process: Any,
    publish: bool = True,
    audio_languages: Optional[list[str]] = None,
) -> tuple[str, Optional[str]]:
    """Probe, encode (with the GPU -> CPU-decode -> libx264 fallback chain) and, if `publish`, move the result into place.

    Shared by the local slots and by remote workers, so an encode behaves identically wherever
    it runs. Returns ("ok" | "failed" | "cancelled", error). On "ok" the finished file is at
    `output` — or still at `tmp_output` when publish=False, for a remote worker, whose result
    only the coordinator may move into place. In every other case nothing is left behind at
    `tmp_output`, and `output` is untouched.
    """
    subtitle_count, subtitle_dispositions = await probe_subtitle_plan(source)
    audio_plan = await probe_audio_plan(
        source,
        AUDIO_LANGUAGES if audio_languages is None else [_normalize_language(l) for l in audio_languages],
    )

    # The job can be deleted while probing (a blocking ffprobe call), and without this check
    # ffmpeg would still start and produce an untracked file.
    if is_cancelled():
        return "cancelled", None

    # ffmpeg writes to a claim-specific temp file and only the final os.replace() makes a
    # fully-encoded file appear at `output` — a killed/failed encode never leaves a partial or
    # invalid file at the path other code treats as "this job's output".
    #
    # Each attempt is (encoder, hw_decode), tried in order until one succeeds: the full GPU
    # pipeline, then the same hardware encoder fed by CPU decode + scale (covers sources the
    # GPU's decoder doesn't support, e.g. 10-bit H.264 or AV1 on older cards), then libx264.
    attempts: list[tuple[str, bool]] = []
    if _active_encoder in _HW_DECODE_ENCODERS and HW_DECODE:
        attempts.append((_active_encoder, True))
    if _active_encoder != "libx264":
        attempts.append((_active_encoder, False))
    attempts.append(("libx264", False))

    try:
        for attempt, (encoder, hw_decode) in enumerate(attempts):
            if attempt:
                print(f"[Press] {_describe_attempt(*attempts[attempt - 1])} failed (job {label}, "
                      f"rc={returncode}), retrying with {_describe_attempt(encoder, hw_decode)}:\n"
                      f"{error_text}", flush=True)
                Path(tmp_output).unlink(missing_ok=True)
            cmd = build_ffmpeg_command(
                source, tmp_output, preset, encoder=encoder, hw_decode=hw_decode,
                subtitle_count=subtitle_count, subtitle_dispositions=subtitle_dispositions,
                audio_plan=audio_plan,
            )
            returncode, error_text = await _run_ffmpeg_once(cmd, duration_us, on_progress, is_cancelled, on_process)
            if returncode == 0 or is_cancelled():
                break

        if returncode != 0 and not is_cancelled():
            print(f"[Press] transcode FAILED (job {label}, rc={returncode}):\n{error_text}", flush=True)

        if is_cancelled():
            Path(tmp_output).unlink(missing_ok=True)
            return "cancelled", None  # the canceller already removed the output file
        if returncode == 0:
            if publish:
                os.replace(tmp_output, output)
            return "ok", None
        Path(tmp_output).unlink(missing_ok=True)
        return "failed", error_text
    except Exception as e:
        Path(tmp_output).unlink(missing_ok=True)
        return ("cancelled", None) if is_cancelled() else ("failed", str(e))


# ─── Scheduling (coordinator side) ───────────────────────────────────────────────────────
# Every job — whether a local slot or a remote worker ends up encoding it — goes through the same
# path: queued -> _claim_next_job() -> running -> complete/failed. Remote claims carry a
# claim_id and a lease that heartbeats keep alive, so a worker that dies mid-job is noticed and
# its job handed to someone else (see lease_loop).


def _spawn(coro) -> None:
    task = asyncio.create_task(coro)
    _bg_tasks.add(task)
    task.add_done_callback(_bg_tasks.discard)


def _lease_deadline() -> str:
    return (datetime.now(timezone.utc) + timedelta(seconds=LEASE_SECONDS)).isoformat()


def _claim_next_job(worker_id: str, worker_name: str) -> Optional[dict]:
    """Hand the oldest queued job to a worker. Synchronous on purpose: with no await between
    picking and marking it running, two claimers can never be given the same job."""
    changed = False
    try:
        while True:
            queued = [j for j in jobs.values() if j["status"] == "queued"]
            if not queued:
                return None
            job = min(queued, key=lambda j: j["created_at"])

            problem = None
            if job["preset"] not in PRESETS:
                problem = f"Preset '{job['preset']}' no longer exists"
            elif not Path(job["source_path"]).exists():
                problem = f"Source file not found: {job['source_path']}"
            if problem:
                job["status"] = "failed"
                job["error"] = problem
                job["updated_at"] = _now_iso()
                changed = True
                _publish(job["job_id"])
                continue

            job["status"] = "running"
            job["progress"] = 0.0
            job["fps"] = job["speed"] = job["eta_seconds"] = None
            job["error"] = None
            job["worker_id"] = worker_id
            job["worker"] = worker_name
            job["claim_id"] = str(uuid.uuid4())
            job["lease_expires_at"] = None if worker_id == LOCAL_WORKER_ID else _lease_deadline()
            job["updated_at"] = _now_iso()
            changed = True
            _publish(job["job_id"])
            return job
    finally:
        if changed:
            _save_jobs()


def _claimed_job(job_id: str, claim_id: str) -> Optional[dict]:
    """The job, if this claim still owns it. None means it was deleted, re-queued after a lapsed
    lease, or claimed by someone else — the caller should stop working on it."""
    job = jobs.get(job_id)
    if job and job["status"] == "running" and job.get("claim_id") == claim_id:
        return job
    return None


def _apply_progress(job: dict, progress: Optional[float], fps: Optional[float],
                    speed: Optional[float], eta: Optional[float]) -> None:
    if progress is not None:
        job["progress"] = progress
    job["fps"] = fps
    job["speed"] = speed
    if eta is not None:
        job["eta_seconds"] = eta
    job["updated_at"] = _now_iso()
    if job.get("lease_expires_at"):
        job["lease_expires_at"] = _lease_deadline()
    _publish(job["job_id"])


async def _complete_job(job_id: str) -> None:
    """Hash the finished output and only then mark the job complete, so nobody ever sees a
    complete job without its checksum."""
    job = jobs.get(job_id)
    if job is None:
        return
    try:
        sha256 = await asyncio.to_thread(_sha256_file, job["output_path"])
    except OSError as e:
        if job_id in jobs:
            _fail_job(job_id, f"Output file unreadable: {e}", job["output_path"])
            job["updated_at"] = _now_iso()
            _save_jobs()
            _publish(job_id)
        return
    if job_id not in jobs:
        return
    job["status"] = "complete"
    job["output_sha256"] = sha256
    job["progress"] = 100.0
    job["eta_seconds"] = 0.0
    job["lease_expires_at"] = None
    job["finalizing"] = False
    job["updated_at"] = _now_iso()
    _save_jobs()
    _publish(job_id)


def _requeue_job(job: dict) -> None:
    """Take a running job away from its claimant and put it back in the queue."""
    if job.get("claim_id"):
        Path(_tmp_path(job["output_path"], job["claim_id"])).unlink(missing_ok=True)
    for key in ("worker_id", "worker", "claim_id", "lease_expires_at"):
        job[key] = None
    job["status"] = "queued"
    job["error"] = None
    job["progress"] = 0.0
    job["fps"] = job["speed"] = job["eta_seconds"] = None
    job["updated_at"] = _now_iso()
    _queue_event.set()
    _publish(job["job_id"])


def _requeue_or_fail_lost_job(job: dict) -> None:
    job_id = job["job_id"]
    lost_worker = job.get("worker")
    # Counts lost workers only — not claims — so coordinator restarts and handed-back jobs
    # don't eat into the budget. (Key renamed from "attempts", which counted every claim.)
    job["worker_losses"] = job.get("worker_losses", 0) + 1
    _requeue_job(job)
    if job["worker_losses"] >= MAX_JOB_ATTEMPTS:
        job["status"] = "failed"
        job["error"] = f"Worker '{lost_worker}' stopped responding; gave up after losing a worker {MAX_JOB_ATTEMPTS} times"
        print(f"[Press] Job {job_id} failed: lost its worker {MAX_JOB_ATTEMPTS} times", flush=True)
        _publish(job_id)
    else:
        print(f"[Press] Worker '{lost_worker}' lost job {job_id}; re-queued", flush=True)


async def lease_loop():
    """Re-queue jobs whose remote worker stopped heartbeating, and forget workers long gone."""
    while True:
        await asyncio.sleep(LEASE_CHECK_SECONDS)
        now = datetime.now(timezone.utc)
        changed = False
        for job in list(jobs.values()):
            if job["status"] != "running" or job.get("finalizing"):
                continue
            if not job.get("worker_id") or job["worker_id"] == LOCAL_WORKER_ID:
                continue
            try:
                lapsed = _parse_ts(job["lease_expires_at"]) < now
            except (KeyError, TypeError, ValueError):
                lapsed = True
            if lapsed:
                _requeue_or_fail_lost_job(job)
                changed = True
        if changed:
            _save_jobs()
        for worker_id, worker in list(_workers.items()):
            if time.monotonic() - worker["last_seen"] > 600:
                del _workers[worker_id]


def _live_workers() -> list[dict]:
    return [w for w in _workers.values() if time.monotonic() - w["last_seen"] <= LEASE_SECONDS * 2]


async def local_slot():
    """One local encode slot: repeatedly claims the next queued job and runs ffmpeg on it here."""
    while True:
        job = _claim_next_job(LOCAL_WORKER_ID, "local")
        if job is None:
            try:
                await asyncio.wait_for(_queue_event.wait(), timeout=5)
            except asyncio.TimeoutError:
                pass
            _queue_event.clear()
            continue
        try:
            await _run_local_job(job)
        except Exception as e:
            print(f"[Press] local slot error on job {job['job_id']}: {e!r}", flush=True)
            if job["job_id"] in jobs and jobs[job["job_id"]]["status"] == "running":
                _fail_job(job["job_id"], str(e), job["output_path"])
                _save_jobs()
                _publish(job["job_id"])


async def _run_local_job(job: dict) -> None:
    job_id = job["job_id"]
    claim_id = job["claim_id"]
    duration_seconds = job.get("duration_seconds")
    duration_us = duration_seconds * 1_000_000 if duration_seconds else None

    def on_progress(progress, fps, speed, eta):
        if job_id in jobs:
            _apply_progress(jobs[job_id], progress, fps, speed, eta)

    def on_process(process):
        if process is None:
            _active_processes.pop(job_id, None)
        else:
            _active_processes[job_id] = process

    result, error = await execute_ffmpeg(
        job_id, job["source_path"], job["output_path"], _tmp_path(job["output_path"], claim_id),
        PRESETS[job["preset"]], duration_us,
        on_progress, lambda: _claimed_job(job_id, claim_id) is None, on_process,
    )
    if job_id not in jobs or result == "cancelled":
        return
    if result == "ok":
        await _complete_job(job_id)
        return
    _fail_job(job_id, error or "Transcode failed", job["output_path"])
    job["updated_at"] = _now_iso()
    _save_jobs()
    _publish(job_id)


# ─── Worker side ──────────────────────────────────────────────────────────────────────────
# A worker owns no job state. It announces itself with every claim, so a coordinator restart
# needs no re-registration, and it treats "the coordinator says this claim is stale" as the
# signal to kill its ffmpeg — which is also how DELETE /jobs/{id} reaches a remote encode.


async def _report_to_coordinator(client: httpx.AsyncClient, path: str, body: dict) -> bool:
    """Deliver a terminal result, retrying through a coordinator restart or network blip.

    Returns whether the coordinator accepted it: False for a stale claim, or if it gave up.
    """
    for _ in range(20):
        try:
            resp = await client.post(path, json=body)
            if resp.status_code < 500:
                try:
                    return resp.status_code == 200 and bool(resp.json().get("ok"))
                except ValueError:
                    return False
        except httpx.HTTPError:
            pass
        await asyncio.sleep(3)
    print(f"[Press] Gave up reporting to coordinator: {path}", flush=True)
    return False


async def _release_job(client: httpx.AsyncClient, job_id: str, claim_id: str, reason: str) -> None:
    """Hand a job back because this host can't run it, and stop claiming for a while."""
    global _worker_unhealthy_until
    _worker_unhealthy_until = time.monotonic() + WORKER_UNHEALTHY_BACKOFF_SECONDS
    print(f"[Press] Can't run job {job_id} on this worker: {reason} "
          f"Handing it back; not claiming for {WORKER_UNHEALTHY_BACKOFF_SECONDS:.0f}s.", flush=True)
    await _report_to_coordinator(client, f"/internal/jobs/{job_id}/release", {
        "claim_id": claim_id, "error": reason,
    })


async def _run_remote_job(client: httpx.AsyncClient, spec: dict) -> None:
    job_id = spec["job_id"]
    claim_id = spec["claim_id"]
    source = spec["source_path"]
    output = spec["output_path"]
    tmp_output = _tmp_path(output, claim_id)
    lease_seconds = float(spec.get("lease_seconds") or LEASE_SECONDS)
    duration_seconds = spec.get("duration_seconds")
    duration_us = duration_seconds * 1_000_000 if duration_seconds else None
    print(f"[Press] Claimed job {job_id}: {source}", flush=True)

    # These two mean the shared-mount setup is wrong on *this* host, not that the job is bad —
    # the coordinator has already checked the source exists. Failing the job would let one
    # host with a dropped mount claim and fail the entire queue in seconds; hand it back instead.
    if not Path(source).exists():
        await _release_job(client, job_id, claim_id,
                           f"source not visible on worker '{WORKER_NAME}': {source}. "
                           "Media must be mounted at the same path on every host.")
        return
    if not Path(output).parent.is_dir():
        await _release_job(client, job_id, claim_id,
                           f"output directory not visible on worker '{WORKER_NAME}': {Path(output).parent}. "
                           "The output volume must be a shared mount at the same path on every host.")
        return

    state: dict[str, Any] = {
        "cancelled": False, "process": None, "progress": {}, "last_ack": time.monotonic(),
    }

    def on_progress(progress, fps, speed, eta):
        state["progress"] = {"progress": progress, "fps": fps, "speed": speed, "eta_seconds": eta}

    def on_process(process):
        state["process"] = process

    def cancel() -> None:
        state["cancelled"] = True
        process = state["process"]
        if process and process.returncode is None:
            process.kill()

    async def heartbeat():
        while True:
            await asyncio.sleep(WORKER_HEARTBEAT_SECONDS)
            try:
                resp = await client.post(
                    f"/internal/jobs/{job_id}/progress",
                    json={"worker_id": WORKER_ID, "claim_id": claim_id, **state["progress"]},
                )
                if resp.status_code == 200:
                    if resp.json().get("cancel"):
                        cancel()
                        return
                    state["last_ack"] = time.monotonic()
            except (httpx.HTTPError, ValueError):
                pass  # coordinator restarting or briefly unreachable; the lease covers a short gap
            # Past the lease the coordinator has re-queued this job (or will the moment it's
            # back), so it may already be encoding elsewhere — stop rather than burn the GPU on
            # a result that can't be accepted.
            if time.monotonic() - state["last_ack"] > lease_seconds:
                print(f"[Press] No heartbeat acknowledged for {lease_seconds:.0f}s; "
                      f"abandoning job {job_id}", flush=True)
                cancel()
                return

    # Kept running until the result is delivered, not just until ffmpeg exits: reporting can
    # take a while through a coordinator blip, and a lapsed lease there would throw away a
    # finished encode.
    heartbeat_task = asyncio.create_task(heartbeat())
    try:
        result, error = await execute_ffmpeg(
            job_id, source, output, tmp_output, spec["preset"], duration_us,
            on_progress, lambda: state["cancelled"], on_process, publish=False,
            audio_languages=spec.get("audio_languages"),
        )
        if result == "ok":
            accepted = await _report_to_coordinator(
                client, f"/internal/jobs/{job_id}/complete", {"claim_id": claim_id})
            if not accepted:
                # Stale claim (or coordinator gone): the file can never become this job's
                # output, and nothing else will clean it up.
                Path(tmp_output).unlink(missing_ok=True)
        elif result == "failed":
            await _report_to_coordinator(client, f"/internal/jobs/{job_id}/fail", {
                "claim_id": claim_id, "error": error or "Transcode failed",
            })
        # "cancelled": the coordinator already knows (or will re-queue it); nothing to report.
    finally:
        heartbeat_task.cancel()


async def _worker_slot(client: httpx.AsyncClient) -> None:
    last_logged = 0.0
    identity = {"worker_id": WORKER_ID, "name": WORKER_NAME, "encoder": _active_encoder, "max_workers": MAX_WORKERS}
    while True:
        backoff = _worker_unhealthy_until - time.monotonic()
        if backoff > 0:
            await asyncio.sleep(backoff)
            continue
        try:
            resp = await client.post("/internal/claim", json=identity)
            if resp.status_code == 200:
                await _run_remote_job(client, resp.json())
                continue
            if resp.status_code != 204:
                resp.raise_for_status()
        except httpx.HTTPError as e:
            if time.monotonic() - last_logged > 30:
                print(f"[Press] Coordinator unreachable ({COORDINATOR_URL}): {e!r}", flush=True)
                last_logged = time.monotonic()
        except Exception as e:
            print(f"[Press] worker slot error: {e!r}", flush=True)
        await asyncio.sleep(WORKER_POLL_SECONDS)


async def run_worker() -> None:
    async with httpx.AsyncClient(base_url=COORDINATOR_URL, timeout=10) as client:
        await asyncio.gather(*(_worker_slot(client) for _ in range(MAX_WORKERS)))


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


async def _create_job(req: TranscodeRequest, source: Path) -> JobStatus:
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

    _queue_event.set()  # wake an idle local slot; remote workers find it on their next poll

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
async def start_transcode(req: TranscodeRequest):
    source, _ = _validate_transcode_request(req)
    return await _create_job(req, source)


class BatchTranscodeRequest(BaseModel):
    items: list[TranscodeRequest]


@app.post("/transcode/batch", response_model=list[JobStatus])
async def start_batch_transcode(req: BatchTranscodeRequest):
    """Queue multiple transcodes (e.g. a whole season) in one call. Bad items are recorded as
    failed jobs rather than aborting the rest of the batch."""
    results = []
    for item in req.items:
        try:
            source, _ = _validate_transcode_request(item)
        except HTTPException as e:
            results.append(_failed_job_status(item, str(e.detail)))
            continue
        results.append(await _create_job(item, source))
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

    remote_capacity = sum(w["max_workers"] for w in _live_workers())
    capacity = MAX_WORKERS + remote_capacity
    total_remaining = running_remaining + (queued_work / max(capacity, 1))

    return {
        "max_workers": capacity,
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


# ─── Distributed transcoding: worker-facing API ──────────────────────────────────────────
# Like the rest of Press this is unauthenticated and meant for a trusted LAN only.


class WorkerInfo(BaseModel):
    worker_id: str
    name: str
    encoder: str = "libx264"
    max_workers: int = 1


class ProgressReport(BaseModel):
    worker_id: str
    claim_id: str
    progress: Optional[float] = None
    fps: Optional[float] = None
    speed: Optional[float] = None
    eta_seconds: Optional[float] = None


class ClaimReport(BaseModel):
    claim_id: str
    error: Optional[str] = None


@app.post("/internal/claim", response_model=None)
async def worker_claim(info: WorkerInfo):
    """A worker with a free slot asks for the next queued job. 204 means nothing to do."""
    _workers[info.worker_id] = {**info.model_dump(), "last_seen": time.monotonic()}
    job = _claim_next_job(info.worker_id, info.name)
    if job is None:
        return Response(status_code=204)
    duration = job.get("duration_seconds")
    return {
        "job_id": job["job_id"],
        "claim_id": job["claim_id"],
        "source_path": job["source_path"],
        "output_path": job["output_path"],
        "preset": PRESETS[job["preset"]],
        # The coordinator's setting, so a job gets the same tracks whichever host encodes it.
        "audio_languages": AUDIO_LANGUAGES,
        "duration_seconds": duration,
        # The worker stops on its own once it's gone this long without a heartbeat getting
        # through — by then this claim has lapsed and the job may be running elsewhere.
        "lease_seconds": LEASE_SECONDS,
    }


@app.post("/internal/jobs/{job_id}/progress")
async def worker_progress(job_id: str, report: ProgressReport):
    """Progress report and lease heartbeat in one. `cancel: true` tells the worker to kill its
    ffmpeg: the job was deleted, or this claim is no longer the one that owns it."""
    worker = _workers.get(report.worker_id)
    if worker:
        worker["last_seen"] = time.monotonic()
    job = _claimed_job(job_id, report.claim_id)
    if job is None:
        return {"cancel": True}
    _apply_progress(job, report.progress, report.fps, report.speed, report.eta_seconds)
    return {"cancel": False}


@app.post("/internal/jobs/{job_id}/complete")
async def worker_complete(job_id: str, report: ClaimReport):
    """The worker's encode is done and sitting at its claim's temp path. Moving it to the job's
    output happens here, and only for the claim that currently owns the job — so a worker whose
    claim went stale (lapsed lease, coordinator restart, job deleted) can never overwrite or
    orphan an output. `ok: false` tells it to delete its temp file."""
    job = _claimed_job(job_id, report.claim_id)
    if job is None:
        return {"ok": False}
    if job.get("finalizing"):
        # A retried report whose first attempt did land. Must not answer false: the worker
        # would delete the temp file out from under the rename in progress.
        return {"ok": True}
    tmp_output = _tmp_path(job["output_path"], report.claim_id)
    if not Path(tmp_output).exists():
        _fail_job(job_id, "Worker reported success but its output file isn't visible to Press. "
                          "The output volume must be a shared mount on every host.", job["output_path"])
        job["updated_at"] = _now_iso()
        _save_jobs()
        _publish(job_id)
        return {"ok": True}
    # The rename and the hash both touch a network mount; do them off the worker's request.
    job["finalizing"] = True
    _spawn(_finalize_remote_job(job_id, tmp_output))
    return {"ok": True}


async def _finalize_remote_job(job_id: str, tmp_output: str) -> None:
    job = jobs.get(job_id)
    if job is None:
        Path(tmp_output).unlink(missing_ok=True)
        return
    output = job["output_path"]
    try:
        await asyncio.to_thread(os.replace, tmp_output, output)
    except OSError as e:
        Path(tmp_output).unlink(missing_ok=True)
        if job_id in jobs:
            _fail_job(job_id, f"Couldn't move the finished encode into place: {e}", output)
            jobs[job_id]["updated_at"] = _now_iso()
            _save_jobs()
            _publish(job_id)
        return
    if job_id not in jobs:
        # Deleted while the rename was in flight; DELETE already ran, so clean up after it.
        Path(output).unlink(missing_ok=True)
        return
    await _complete_job(job_id)


@app.post("/internal/jobs/{job_id}/release")
async def worker_release(job_id: str, report: ClaimReport):
    """A worker that can't run a job on its host (its media or output mount is missing) hands it
    back rather than failing it — the job is fine, the host isn't. Not counted as a lost worker."""
    job = _claimed_job(job_id, report.claim_id)
    if job is None or job.get("finalizing"):
        return {"ok": False}
    print(f"[Press] Worker '{job.get('worker')}' handed back job {job_id}: {report.error}", flush=True)
    _requeue_job(job)
    _save_jobs()
    return {"ok": True}


@app.post("/internal/jobs/{job_id}/fail")
async def worker_fail(job_id: str, report: ClaimReport):
    job = _claimed_job(job_id, report.claim_id)
    if job is None:
        return {"ok": False}
    _fail_job(job_id, report.error or "Transcode failed", job["output_path"])
    job["updated_at"] = _now_iso()
    _save_jobs()
    _publish(job_id)
    return {"ok": True}


@app.get("/api/workers")
async def list_workers():
    """Everything that can currently encode: this host's local slots plus live remote workers."""
    running = [j for j in jobs.values() if j["status"] == "running"]
    result = []
    if MAX_WORKERS > 0:
        result.append({
            "worker_id": LOCAL_WORKER_ID,
            "name": "local",
            "encoder": _active_encoder,
            "max_workers": MAX_WORKERS,
            "active_jobs": sum(1 for j in running if j.get("worker_id") == LOCAL_WORKER_ID),
            "local": True,
        })
    for w in _live_workers():
        result.append({
            "worker_id": w["worker_id"],
            "name": w["name"],
            "encoder": w["encoder"],
            "max_workers": w["max_workers"],
            "active_jobs": sum(1 for j in running if j.get("worker_id") == w["worker_id"]),
            "local": False,
        })
    return result


@app.middleware("http")
async def _worker_role_guard(request: Request, call_next):
    # A worker holds no job state, so anything sent to its API (a misdirected /transcode, say)
    # would be accepted and then silently never run.
    if ROLE == "worker" and request.url.path != "/health":
        return JSONResponse(
            status_code=404,
            content={"detail": f"This is a Press worker. Use the coordinator at {COORDINATOR_URL}"},
        )
    return await call_next(request)


@app.get("/health")
async def health():
    return {
        "status": "ok",
        "version": PRESS_VERSION,
        "media_root": MEDIA_ROOT,
        "output_root": OUTPUT_ROOT,
        "encoder": _active_encoder,
        "role": ROLE,
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
