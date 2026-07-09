import os
import uuid
import asyncio
import json
import subprocess
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Optional
from datetime import datetime

from fastapi import FastAPI, HTTPException, BackgroundTasks
from fastapi.responses import FileResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

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
    yield


app = FastAPI(title="JellyJar Press", version="1.0.0", lifespan=lifespan)

# In-memory job store (fine for single-instance use)
jobs: dict[str, dict] = {}

MEDIA_ROOT = os.environ.get("MEDIA_ROOT", "/media")
OUTPUT_ROOT = os.environ.get("OUTPUT_ROOT", "/output")
CONFIG_ROOT = os.environ.get("CONFIG_ROOT", "/config")
PRESETS_FILE = Path(CONFIG_ROOT) / "presets.json"

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


def load_presets() -> dict:
    try:
        return json.loads(PRESETS_FILE.read_text())
    except FileNotFoundError:
        return dict(DEFAULT_PRESETS)
    except Exception as e:
        print(f"[Press] Failed to read {PRESETS_FILE}, using defaults: {e}", flush=True)
        return dict(DEFAULT_PRESETS)


def save_presets() -> None:
    PRESETS_FILE.parent.mkdir(parents=True, exist_ok=True)
    PRESETS_FILE.write_text(json.dumps(PRESETS, indent=2))


PRESETS = load_presets()


class TranscodeRequest(BaseModel):
    source_path: str          # Path relative to MEDIA_ROOT
    preset: str               # "1080p" or "720p"
    output_filename: Optional[str] = None


class PresetConfig(BaseModel):
    video_bitrate: str
    audio_bitrate: str
    scale: str
    crf: str


class JobStatus(BaseModel):
    job_id: str
    status: str               # queued | running | complete | failed
    progress: Optional[float] = None
    output_path: Optional[str] = None
    error: Optional[str] = None
    created_at: str
    updated_at: str
    duration_seconds: Optional[float] = None   # source media duration
    fps: Optional[float] = None
    speed: Optional[float] = None              # ffmpeg encode speed, e.g. 2.5 = 2.5x realtime
    eta_seconds: Optional[float] = None        # estimated time remaining for this job
    queue_position: Optional[int] = None       # 1-based position among queued (waiting) jobs


async def get_duration_us(source: str) -> Optional[float]:
    try:
        proc = await asyncio.create_subprocess_exec(
            "ffprobe", "-v", "quiet", "-print_format", "json", "-show_format", source,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.DEVNULL,
        )
        stdout, _ = await proc.communicate()
        info = json.loads(stdout)
        return float(info["format"]["duration"]) * 1_000_000
    except Exception:
        return None


def build_ffmpeg_command(source: str, output: str, preset: dict, encoder: str | None = None) -> list[str]:
    enc = encoder if encoder is not None else _active_encoder
    scale = preset["scale"]
    sw_vf = (
        f"scale={scale}:force_original_aspect_ratio=decrease,"
        f"pad={scale}:(ow-iw)/2:(oh-ih)/2"
    )
    common_audio = ["-c:a", "aac", "-b:a", preset["audio_bitrate"],
                    "-movflags", "+faststart", "-progress", "pipe:1"]

    if enc == "h264_vaapi":
        return [
            "ffmpeg", "-y",
            "-vaapi_device", "/dev/dri/renderD128",
            "-i", source,
            "-vf", f"{sw_vf},format=nv12,hwupload",
            "-c:v", "h264_vaapi",
            "-b:v", preset["video_bitrate"],
            *common_audio, output,
        ]
    if enc == "h264_qsv":
        return [
            "ffmpeg", "-y",
            "-i", source,
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
        "-vf", sw_vf,
        "-c:v", "libx264",
        "-crf", preset["crf"],
        "-preset", "fast",
        "-b:v", preset["video_bitrate"],
        *common_audio, output,
    ]


async def stream_progress(process: asyncio.subprocess.Process, job_id: str, duration_us: Optional[float]) -> None:
    """Read ffmpeg's `-progress pipe:1` output and update progress/fps/speed/eta on the job."""
    current_us = None
    while True:
        line = await process.stdout.readline()
        if not line:
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

            jobs[job_id]["updated_at"] = datetime.utcnow().isoformat()


async def run_transcode(job_id: str, source: str, output: str, preset: dict, duration_us: Optional[float]):
    async with _worker_semaphore:
        jobs[job_id]["status"] = "running"
        jobs[job_id]["updated_at"] = datetime.utcnow().isoformat()

        cmd = build_ffmpeg_command(source, output, preset)

        try:
            process = await asyncio.create_subprocess_exec(
                *cmd,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
            )
            await stream_progress(process, job_id, duration_us)
            await process.wait()

            stderr_bytes = await process.stderr.read()
            if process.returncode == 0:
                jobs[job_id]["status"] = "complete"
                jobs[job_id]["output_path"] = output
                jobs[job_id]["progress"] = 100.0
                jobs[job_id]["eta_seconds"] = 0.0
            else:
                error_text = stderr_bytes.decode()[-1000:]
                if _active_encoder != "libx264":
                    # Hardware encoder failed at runtime — fall back to libx264
                    print(f"[Press] {_active_encoder} failed (rc={process.returncode}), retrying with libx264", flush=True)
                    sw_cmd = build_ffmpeg_command(source, output, preset, encoder="libx264")
                    sw_process = await asyncio.create_subprocess_exec(
                        *sw_cmd,
                        stdout=asyncio.subprocess.PIPE,
                        stderr=asyncio.subprocess.PIPE,
                    )
                    await stream_progress(sw_process, job_id, duration_us)
                    await sw_process.wait()
                    sw_stderr = (await sw_process.stderr.read()).decode()[-1000:]
                    if sw_process.returncode == 0:
                        jobs[job_id]["status"] = "complete"
                        jobs[job_id]["output_path"] = output
                        jobs[job_id]["progress"] = 100.0
                        jobs[job_id]["eta_seconds"] = 0.0
                    else:
                        print(f"[Press] libx264 fallback also failed:\n{sw_stderr}", flush=True)
                        jobs[job_id]["status"] = "failed"
                        jobs[job_id]["error"] = sw_stderr
                else:
                    print(f"[Press] transcode FAILED (job {job_id}, rc={process.returncode}):\n{error_text}", flush=True)
                    jobs[job_id]["status"] = "failed"
                    jobs[job_id]["error"] = error_text

        except Exception as e:
            jobs[job_id]["status"] = "failed"
            jobs[job_id]["error"] = str(e)

        jobs[job_id]["updated_at"] = datetime.utcnow().isoformat()


@app.post("/transcode", response_model=JobStatus)
async def start_transcode(req: TranscodeRequest, background_tasks: BackgroundTasks):
    if req.preset not in PRESETS:
        raise HTTPException(status_code=400, detail=f"Unknown preset '{req.preset}'. Use: {list(PRESETS.keys())}")

    # pathlib: absolute right-hand path overrides the left, so absolute Jellyfin paths work as-is
    source = Path(MEDIA_ROOT) / req.source_path
    print(f"[Press] transcode request: source_path={req.source_path!r}  resolved={source}", flush=True)
    if not source.exists():
        print(f"[Press] 404 — file not found at {source}", flush=True)
        raise HTTPException(status_code=404, detail=f"Source file not found: {source}")

    job_id = str(uuid.uuid4())
    output_filename = req.output_filename or f"{source.stem}_{req.preset}.mp4"
    output = str(Path(OUTPUT_ROOT) / output_filename)

    duration_us = await get_duration_us(str(source))

    now = datetime.utcnow().isoformat()
    jobs[job_id] = {
        "job_id": job_id,
        "status": "queued",
        "progress": 0.0,
        "output_path": None,
        "error": None,
        "created_at": now,
        "updated_at": now,
        "duration_seconds": (duration_us / 1_000_000) if duration_us else None,
        "fps": None,
        "speed": None,
        "eta_seconds": None,
    }

    preset = PRESETS[req.preset]
    background_tasks.add_task(run_transcode, job_id, str(source), output, preset, duration_us)

    return _to_job_status(jobs[job_id])


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

    return FileResponse(
        path=output_path,
        media_type="video/mp4",
        filename=Path(output_path).name,
    )


@app.delete("/jobs/{job_id}")
async def delete_job(job_id: str):
    if job_id not in jobs:
        raise HTTPException(status_code=404, detail="Job not found")

    job = jobs[job_id]
    if job["output_path"] and Path(job["output_path"]).exists():
        Path(job["output_path"]).unlink()

    del jobs[job_id]
    return {"deleted": job_id}


@app.get("/health")
async def health():
    return {"status": "ok", "media_root": MEDIA_ROOT, "output_root": OUTPUT_ROOT, "encoder": _active_encoder}


@app.get("/presets")
async def get_presets():
    return list(PRESETS.keys())


# ─── Web UI + preset management ──────────────────────────────────────────────
# In-memory only, like `jobs` — edits reset on container restart.

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
