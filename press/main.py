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
from fastapi.responses import FileResponse
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


@asynccontextmanager
async def lifespan(app_: FastAPI):
    global _active_encoder
    forced = os.environ.get("ENCODER", "").strip()
    if forced:
        print(f"[Press] Encoder forced via env: {forced}", flush=True)
        _active_encoder = forced
    else:
        _active_encoder = await detect_encoder()
    yield


app = FastAPI(title="JellyJar Press", version="1.0.0", lifespan=lifespan)

# In-memory job store (fine for single-instance use)
jobs: dict[str, dict] = {}

MEDIA_ROOT = os.environ.get("MEDIA_ROOT", "/media")
OUTPUT_ROOT = os.environ.get("OUTPUT_ROOT", "/output")

PRESETS = {
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


class TranscodeRequest(BaseModel):
    source_path: str          # Path relative to MEDIA_ROOT
    preset: str               # "1080p" or "720p"
    output_filename: Optional[str] = None


class JobStatus(BaseModel):
    job_id: str
    status: str               # queued | running | complete | failed
    progress: Optional[float] = None
    output_path: Optional[str] = None
    error: Optional[str] = None
    created_at: str
    updated_at: str


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


async def run_transcode(job_id: str, source: str, output: str, preset: dict):
    jobs[job_id]["status"] = "running"
    jobs[job_id]["updated_at"] = datetime.utcnow().isoformat()

    duration_us = await get_duration_us(source)
    cmd = build_ffmpeg_command(source, output, preset)

    try:
        process = await asyncio.create_subprocess_exec(
            *cmd,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )

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

            if duration_us and current_us and duration_us > 0:
                progress = min((current_us / duration_us) * 100, 100)
                jobs[job_id]["progress"] = round(progress, 1)
                jobs[job_id]["updated_at"] = datetime.utcnow().isoformat()

        await process.wait()

        stderr_bytes = await process.stderr.read()
        if process.returncode == 0:
            jobs[job_id]["status"] = "complete"
            jobs[job_id]["output_path"] = output
            jobs[job_id]["progress"] = 100.0
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
                while True:
                    line = await sw_process.stdout.readline()
                    if not line:
                        break
                    decoded = line.decode().strip()
                    if decoded.startswith("out_time_us="):
                        try:
                            val = int(decoded.split("=")[1])
                            if val >= 0 and duration_us and duration_us > 0:
                                jobs[job_id]["progress"] = round(min((val / duration_us) * 100, 100), 1)
                                jobs[job_id]["updated_at"] = datetime.utcnow().isoformat()
                        except ValueError:
                            pass
                await sw_process.wait()
                sw_stderr = (await sw_process.stderr.read()).decode()[-1000:]
                if sw_process.returncode == 0:
                    jobs[job_id]["status"] = "complete"
                    jobs[job_id]["output_path"] = output
                    jobs[job_id]["progress"] = 100.0
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

    now = datetime.utcnow().isoformat()
    jobs[job_id] = {
        "job_id": job_id,
        "status": "queued",
        "progress": 0.0,
        "output_path": None,
        "error": None,
        "created_at": now,
        "updated_at": now,
    }

    preset = PRESETS[req.preset]
    background_tasks.add_task(run_transcode, job_id, str(source), output, preset)

    return JobStatus(**jobs[job_id])


@app.get("/jobs/{job_id}", response_model=JobStatus)
async def get_job(job_id: str):
    if job_id not in jobs:
        raise HTTPException(status_code=404, detail="Job not found")
    return JobStatus(**jobs[job_id])


@app.get("/jobs", response_model=list[JobStatus])
async def list_jobs():
    return [JobStatus(**j) for j in jobs.values()]


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
