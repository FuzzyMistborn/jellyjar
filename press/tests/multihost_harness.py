"""Multi-host Press test harness: a coordinator and workers as real processes, stub ffmpeg.

Exercises the distributed-transcoding failure paths that are hard to hit by hand: workers
killed or frozen mid-encode, coordinator restarts and freezes, deletes of remote encodes, and a
worker whose media mount is missing. Each scenario checks that every job ends complete with a
valid checksum and that no temp or untracked files are left in the output directory.

Needs Linux (SIGSTOP/SIGCONT; the broken-mount scenario uses `unshare -rm`, i.e. unprivileged
user namespaces) and Press's requirements installed in the running interpreter. ffmpeg itself
is not needed — tests/stubs stand in for it.

    python -m venv .venv && .venv/bin/pip install -r press/requirements.txt
    .venv/bin/python press/tests/multihost_harness.py                 # all scenarios
    .venv/bin/python press/tests/multihost_harness.py s_frozen_worker # just one

PRESS_DIR runs the scenarios against another copy of Press (e.g. an older revision, to confirm
a scenario actually reproduces the bug it guards against). Ports 18090-18103 must be free.
"""
import hashlib
import json
import os
import shutil
import signal
import subprocess
import sys
import tempfile
import time
from pathlib import Path

import httpx

HERE = Path(__file__).resolve().parent
PRESS = Path(os.environ.get("PRESS_DIR", HERE.parent))
UVICORN = (sys.executable, "-m", "uvicorn")
WORK = Path(tempfile.mkdtemp(prefix="press-multihost-"))
COORD = "http://127.0.0.1:18090"
LEASE = "6"

procs: list[subprocess.Popen] = []


def env(**extra):
    e = dict(os.environ)
    e["PATH"] = f"{HERE / 'stubs'}:{e['PATH']}"
    e.update(ENCODER="libx264", WORKER_LEASE_SECONDS=LEASE, **{k: str(v) for k, v in extra.items()})
    return e


def fresh(name):
    root = WORK / name
    shutil.rmtree(root, ignore_errors=True)
    for d in ("media", "output", "config"):
        (root / d).mkdir(parents=True)
    for i in range(4):
        (root / "media" / f"ep{i}.mkv").write_bytes(b"src")
    return root


def start(root, name, port, cmd_prefix=(), **extra):
    log = open(root / f"{name}.log", "w")
    p = subprocess.Popen(
        [*cmd_prefix, *UVICORN, "main:app", "--host", "127.0.0.1", "--port", str(port)],
        cwd=PRESS, stdout=log, stderr=subprocess.STDOUT,
        env=env(MEDIA_ROOT=root / "media", OUTPUT_ROOT=root / "output", CONFIG_ROOT=root / "config", **extra),
    )
    procs.append(p)
    return p


def wait_up(url):
    for _ in range(100):
        try:
            if httpx.get(url + "/health", timeout=1).status_code == 200:
                return
        except httpx.HTTPError:
            pass
        time.sleep(0.2)
    raise RuntimeError(f"{url} never came up")


def coordinator(root, max_workers=0, role="coordinator", **extra):
    p = start(root, "coord", 18090, PRESS_ROLE=role, MAX_WORKERS=max_workers, **extra)
    wait_up(COORD)
    return p


def worker(root, name, port, cmd_prefix=(), **extra):
    p = start(root, name, port, cmd_prefix, PRESS_ROLE="worker", COORDINATOR_URL=COORD,
              WORKER_NAME=name, **extra)
    wait_up(f"http://127.0.0.1:{port}")
    return p


def submit(root, n):
    ids = []
    for i in range(n):
        r = httpx.post(COORD + "/transcode", json={"source_path": str(root / "media" / f"ep{i}.mkv"), "preset": "720p"})
        r.raise_for_status()
        ids.append(r.json()["job_id"])
    return ids


def jobs():
    return {j["job_id"]: j for j in httpx.get(COORD + "/jobs", timeout=5).json()}


def wait_done(ids, timeout=90):
    end = time.time() + timeout
    while time.time() < end:
        try:
            js = jobs()
        except httpx.HTTPError:
            time.sleep(0.5)
            continue
        if all(js.get(i, {}).get("status") in ("complete", "failed") for i in ids):
            return js
        time.sleep(0.5)
    raise RuntimeError(f"timeout: {[(i[:8], jobs().get(i, {}).get('status')) for i in ids]}")


def wait_status(job_id, status, timeout=30):
    end = time.time() + timeout
    while time.time() < end:
        j = jobs().get(job_id)
        if j and j["status"] == status:
            return j
        time.sleep(0.3)
    raise RuntimeError(f"{job_id[:8]} never reached {status}: {jobs().get(job_id)}")


def check_clean(root, js, expect_complete):
    ok = True
    for jid, j in js.items():
        if jid not in expect_complete:
            continue
        if j["status"] != "complete":
            print(f"   FAIL {jid[:8]} status={j['status']} error={j['error']}")
            ok = False
            continue
        data = Path(j["output_path"]).read_bytes()
        if hashlib.sha256(data).hexdigest() != j["output_sha256"]:
            print(f"   FAIL {jid[:8]} checksum mismatch")
            ok = False
    parts = list((root / "output").glob("*.part"))
    if parts:
        print(f"   FAIL leftover temp files: {[p.name for p in parts]}")
        ok = False
    tracked = {Path(j["output_path"]).name for j in js.values() if j.get("output_path")}
    orphans = [p.name for p in (root / "output").iterdir() if p.name not in tracked]
    if orphans:
        print(f"   FAIL untracked files in output: {orphans}")
        ok = False
    return ok


def stop_all():
    for p in procs:
        if p.poll() is None:
            try:
                os.kill(p.pid, signal.SIGCONT)
            except ProcessLookupError:
                pass
            p.terminate()
    for p in procs:
        try:
            p.wait(5)
        except subprocess.TimeoutExpired:
            p.kill()
    procs.clear()
    time.sleep(0.5)


def grep(root, name, text):
    return text in (root / f"{name}.log").read_text()


# ─── Scenarios ────────────────────────────────────────────────────────────────

def s_basic():
    root = fresh("basic")
    coordinator(root)
    worker(root, "w1", 18101)
    worker(root, "w2", 18102)
    ids = submit(root, 4)
    js = wait_done(ids)
    spread = {js[i]["worker"] for i in ids}
    print(f"   workers used: {sorted(spread)}")
    return check_clean(root, js, ids) and spread == {"w1", "w2"}


def s_standalone():
    root = fresh("standalone")
    coordinator(root, max_workers=1, role="standalone")
    ids = submit(root, 2)
    js = wait_done(ids)
    return check_clean(root, js, ids) and all(js[i]["worker"] == "local" for i in ids)


def s_worker_killed():
    root = fresh("killed")
    coordinator(root)
    w1 = worker(root, "w1", 18101, STUB_SECONDS=20)
    ids = submit(root, 1)
    wait_status(ids[0], "running")
    time.sleep(2)
    w1.kill()                                   # dies mid-encode; its .part is left behind
    worker(root, "w2", 18102)
    js = wait_done(ids)
    print(f"   finished on {js[ids[0]]['worker']}")
    return check_clean(root, js, ids) and js[ids[0]]["worker"] == "w2"


def s_coordinator_restart_with_local_slot():
    """Issue #1/#2: after a restart a local slot re-claims immediately while the old worker is
    still encoding the same job. Used to share one .part and fail the job."""
    root = fresh("restart")
    c = coordinator(root, max_workers=1, STUB_SECONDS=15)
    # Keep the local slot busy so the job goes to the worker first.
    busy = submit(root, 1)
    wait_status(busy[0], "running")
    worker(root, "w1", 18101, STUB_SECONDS=12)
    ids = [httpx.post(COORD + "/transcode", json={"source_path": str(root / "media/ep1.mkv"), "preset": "720p"}).json()["job_id"]]
    j = wait_status(ids[0], "running")
    assert j["worker"] == "w1", j
    httpx.delete(f"{COORD}/jobs/{busy[0]}").raise_for_status()   # free the local slot
    time.sleep(3)
    c.terminate(); c.wait(5); procs.remove(c)
    coordinator(root, max_workers=1)            # local slot grabs the re-queued job at once
    js = wait_done(ids)
    print(f"   job finished on {js[ids[0]]['worker']}; worker cancelled: {grep(root, 'w1', 'Claimed job')}")
    return check_clean(root, js, ids)


def s_frozen_worker():
    """A worker that is frozen past its lease (SIGSTOP), then resumes: the job must be taken over
    and the stale worker must neither clobber nor orphan anything."""
    root = fresh("frozen")
    coordinator(root)
    w1 = worker(root, "w1", 18101, STUB_SECONDS=12)
    ids = submit(root, 1)
    wait_status(ids[0], "running")
    time.sleep(2)
    # Freeze the worker and its stub ffmpeg child.
    kids = subprocess.run(["pgrep", "-P", str(w1.pid)], capture_output=True, text=True).stdout.split()
    for pid in [w1.pid, *map(int, kids)]:
        os.kill(pid, signal.SIGSTOP)
    worker(root, "w2", 18102, STUB_SECONDS=12)
    time.sleep(float(LEASE) + 8)                # lease lapses, w2 takes over
    for pid in [w1.pid, *map(int, kids)]:
        os.kill(pid, signal.SIGCONT)
    js = wait_done(ids)
    time.sleep(6)                               # let w1 notice and clean up
    js = jobs()
    print(f"   finished on {js[ids[0]]['worker']}")
    return check_clean(root, js, ids) and js[ids[0]]["worker"] == "w2"


def s_coordinator_unreachable_self_fence():
    """Coordinator frozen past the lease: the worker must abandon the encode on its own."""
    root = fresh("fence")
    c = coordinator(root)
    worker(root, "w1", 18101, STUB_SECONDS=30)
    ids = submit(root, 1)
    wait_status(ids[0], "running")
    time.sleep(2)
    os.kill(c.pid, signal.SIGSTOP)
    time.sleep(float(LEASE) + 14)               # heartbeat timeouts (10s) + lease
    fenced = grep(root, "w1", "abandoning job")
    os.kill(c.pid, signal.SIGCONT)
    js = wait_done(ids, timeout=120)
    print(f"   worker self-fenced: {fenced}")
    return fenced and check_clean(root, js, ids)


def s_delete_remote():
    root = fresh("delete")
    coordinator(root)
    worker(root, "w1", 18101, STUB_SECONDS=20)
    ids = submit(root, 1)
    wait_status(ids[0], "running")
    time.sleep(2)
    httpx.delete(f"{COORD}/jobs/{ids[0]}").raise_for_status()
    time.sleep(5)
    leftovers = list((root / "output").iterdir())
    print(f"   output dir after delete: {[p.name for p in leftovers]}")
    return not leftovers


def s_broken_worker_releases():
    """Issue #3: a worker whose media mount is missing must hand jobs back, not fail the queue."""
    root = fresh("broken")
    coordinator(root)
    empty = root / "empty"; empty.mkdir()
    # Private mount namespace: this worker sees an empty dir where the media lives.
    prefix = ("unshare", "-rm", "--", "sh", "-c",
              f'mount --bind "{empty}" "{root / "media"}" && exec "$0" "$@"')
    worker(root, "broken", 18103, prefix)
    ids = submit(root, 3)
    time.sleep(4)
    js = jobs()
    failed_early = [i for i in ids if js[i]["status"] == "failed"]
    print(f"   failed while only the broken worker existed: {len(failed_early)}")
    worker(root, "w1", 18101)
    js = wait_done(ids)
    return not failed_early and check_clean(root, js, ids) and grep(root, "coord", "handed back job")


SCENARIOS = [s_basic, s_standalone, s_worker_killed, s_coordinator_restart_with_local_slot,
             s_frozen_worker, s_coordinator_unreachable_self_fence, s_delete_remote,
             s_broken_worker_releases]

if __name__ == "__main__":
    wanted = sys.argv[1:]
    results = {}
    for s in SCENARIOS:
        if wanted and s.__name__ not in wanted:
            continue
        print(f"== {s.__name__}", flush=True)
        try:
            results[s.__name__] = bool(s())
        except Exception as e:
            print(f"   ERROR {e!r}")
            results[s.__name__] = False
        finally:
            stop_all()
        print(f"   {'PASS' if results[s.__name__] else 'FAIL'}", flush=True)
    print(json.dumps(results, indent=1))
    print(f"logs: {WORK}")
    sys.exit(0 if results and all(results.values()) else 1)
