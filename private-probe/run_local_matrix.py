"""Run the frozen ByteBans synthetic probe on isolated local Paper servers.

This is an alternative to run_matrix.py while the remote test host is busy. It
records the same exact-JAR receipt shape and keeps each stopped world on disk.
"""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys
import time
from urllib.request import Request, urlopen

import run_matrix
import summarize


HERE = Path(__file__).resolve().parent
WORKSPACE = HERE.parents[2]
MATRIX = json.loads((HERE / "matrix.json").read_text(encoding="utf-8"))
CANDIDATE = HERE / "frozen/feature/ByteBans-1.1.0-SNAPSHOT.jar"
HELPER = HERE / "target/bytebans-private-probe-1.0.0.jar"
CACHE = WORKSPACE / "testing/bytebans/cache"
INSTANCES = WORKSPACE / "testing/bytebans/live"
RUNS = HERE / "runs"
SOURCE_REVISION = "bb2e229d581a56549b774aa92e11fefa703a431a"
USER_AGENT = "ModrinthWorkspace/1.0 (https://github.com/LianJordaan/ByteBans)"
EXPECTED_CANDIDATE_SHA512 = "00eb2430259370247f16a67624ee917a6e8f94a2a5995a77aab4e0d982010235330e814a0b8d08b9e2f0261be276967bc166ef5066ceccba7b7c0a4a7193e5bc"
EXPECTED_HELPER_SHA512 = "8d326289e3b410afbf7565b8c41b720552c20e8d2d7996a6a6e43c59ad4d2dce41a213fdd73a0a956c16fbf5c2abe49dabef94926e5c102bfc80f2ed887284dc"
JAVA = {
    21: Path(r"C:\Program Files\Java\jdk-21\bin\java.exe"),
    25: Path(r"C:\Users\lianj\.gradle\jdks\eclipse_adoptium-25-amd64-windows.2\bin\java.exe"),
}


def sha(path: Path, algorithm: str) -> str:
    hasher = hashlib.new(algorithm)
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            hasher.update(block)
    return hasher.hexdigest()


def get_paper(target: dict, download_missing: bool) -> Path:
    version, build = target["version"], target["paper_build"]
    local = CACHE / f"paper-{version}-{build}.jar"
    local.parent.mkdir(parents=True, exist_ok=True)
    if local.is_file() and sha(local, "sha256") == target["paper_sha256"]:
        return local
    if local.exists():
        raise ValueError(f"Paper cache has wrong SHA-256: {local}")
    sibling = WORKSPACE / "testing/notabackdoor/cache" / local.name
    if sibling.is_file() and sha(sibling, "sha256") == target["paper_sha256"]:
        shutil.copy2(sibling, local)
        return local
    if not download_missing:
        raise FileNotFoundError(f"Pinned Paper build is not cached: {local}")
    endpoint = f"https://fill.papermc.io/v3/projects/paper/versions/{version}/builds"
    with urlopen(Request(endpoint, headers={"User-Agent": USER_AGENT}), timeout=30) as response:
        builds = json.load(response)
    pinned = next((row for row in builds if row.get("id") == build), None)
    if not pinned:
        raise ValueError(f"Paper {version} build {build} absent from publisher catalog")
    url = pinned["downloads"]["server:default"]["url"]
    request = Request(url, headers={"User-Agent": USER_AGENT})
    temporary = local.with_suffix(".partial")
    try:
        with urlopen(request, timeout=180) as response, temporary.open("wb") as output:
            shutil.copyfileobj(response, output)
        actual = sha(temporary, "sha256")
        if actual != target["paper_sha256"]:
            raise ValueError(f"Paper {version} b{build} SHA-256 mismatch: {actual}")
        temporary.replace(local)
    finally:
        temporary.unlink(missing_ok=True)
    return local


def run(target: dict, folder: Path, download_missing: bool) -> dict:
    candidate_sha = sha(CANDIDATE, "sha512")
    helper_sha = sha(HELPER, "sha512")
    instance_id = "mw-" + secrets.token_hex(8)
    instance = INSTANCES / instance_id
    started_at = datetime.now(timezone.utc).isoformat()
    result = {
        "target": target,
        "source_revision": SOURCE_REVISION,
        "candidate_sha512": candidate_sha,
        "probe_sha512": helper_sha,
        "started_at": started_at,
        "instance_id": instance_id,
        "instance_path": str(instance.relative_to(WORKSPACE)),
        "execution_host": "local-windows-loopback",
        "port": 27240,
        "java_executable": str(JAVA[target["java"]]),
        "status": "error",
    }
    process = None
    instance.mkdir(parents=True)
    try:
        paper = get_paper(target, download_missing)
        result["paper_sha256_actual"] = sha(paper, "sha256")
        shutil.copy2(paper, instance / paper.name)
        plugins = instance / "plugins"
        plugins.mkdir()
        installed_candidate = plugins / "000-00eb24302593.jar"
        installed_helper = plugins / "001-8d326289e3b4.jar"
        shutil.copy2(CANDIDATE, installed_candidate)
        shutil.copy2(HELPER, installed_helper)
        result["candidate_sha512_actual"] = sha(installed_candidate, "sha512")
        result["probe_sha512_actual"] = sha(installed_helper, "sha512")
        (instance / "eula.txt").write_text("eula=true\n", encoding="utf-8")
        (instance / "server.properties").write_text(
            "server-ip=127.0.0.1\nserver-port=27240\n"
            + "online-mode=" + ("true" if target["connection_mode"] == "standalone_online" else "false") + "\n"
            + "enforce-secure-profile=false\nwhite-list=true\n"
            + "enable-rcon=false\nspawn-protection=0\n"
            + "view-distance=4\nsimulation-distance=4\n"
            + "level-name=world\nmax-players=4\n",
            encoding="utf-8",
        )
        log_path = instance / "server.log"
        with log_path.open("w", encoding="utf-8", errors="replace") as log:
            process = subprocess.Popen(
                [str(JAVA[target["java"]]), "-Xms1G", "-Xmx4G", "-XX:ActiveProcessorCount=4",
                 "-jar", paper.name, "nogui"],
                cwd=instance, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT,
                text=True, encoding="utf-8", errors="replace",
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            )
            deadline = time.monotonic() + 420
            probe_file = plugins / "ByteBansPrivateProbe/results.json"
            while time.monotonic() < deadline:
                if probe_file.is_file():
                    result["probe"] = json.loads(probe_file.read_text(encoding="utf-8"))
                    result["probe_diagnostics"] = run_matrix.probe_diagnostics(result["probe"], target)
                    result["status"] = (
                        "pass" if result["probe"].get("passed") is True
                        and all(result["probe_diagnostics"].values()) else "fail"
                    )
                    break
                if process.poll() is not None:
                    result["status"] = "server_exited_before_probe"
                    break
                time.sleep(2)
            else:
                result["status"] = "timeout"
        result["logs"] = log_path.read_text(encoding="utf-8", errors="replace")
        result["startup_diagnostics"] = run_matrix.startup_diagnostics(result["logs"])
        if result["status"] == "pass" and (
            not result["startup_diagnostics"]["initialized"]
            or result["startup_diagnostics"]["fatal_lines"]
            or re.search(r"Could not pass event .+ to ByteBans", result["logs"])
        ):
            result["status"] = "startup_diagnostic_failed"
    except Exception as error:
        result["status"] = "error"
        result["error"] = repr(error)
    finally:
        if process is not None:
            if process.poll() is None:
                try:
                    process.stdin.write("stop\n")
                    process.stdin.flush()
                    process.wait(timeout=90)
                except Exception as error:
                    result["stop_error"] = repr(error)
                    process.kill()
                    process.wait(timeout=30)
            result["server_exit_code"] = process.returncode
            result["stopped"] = process.poll() is not None and process.returncode == 0
        else:
            result["stopped"] = True
        result["finished_at"] = datetime.now(timezone.utc).isoformat()
        (folder / (target["id"] + ".json")).write_text(
            json.dumps(result, indent=2) + "\n", encoding="utf-8"
        )
    print(target["id"], result["status"], "stopped=" + str(result["stopped"]), flush=True)
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", action="append", help="Run only this pinned target ID; repeatable")
    parser.add_argument("--remaining", action="store_true", help="Select targets without a verified passing receipt")
    parser.add_argument("--download-missing", action="store_true", help="Fetch missing pinned JARs from PaperMC")
    parser.add_argument("--download-only", action="store_true", help="Cache one verified JAR per selected Paper version")
    parser.add_argument("--list", action="store_true")
    args = parser.parse_args()
    if sha(CANDIDATE, "sha512") != EXPECTED_CANDIDATE_SHA512:
        raise SystemExit("Frozen ByteBans JAR SHA-512 changed")
    if sha(HELPER, "sha512") != EXPECTED_HELPER_SHA512:
        raise SystemExit("Private probe JAR SHA-512 changed")
    targets = MATRIX["targets"]
    if args.target:
        selected = set(args.target)
        targets = [target for target in targets if target["id"] in selected]
        if len(targets) != len(selected):
            raise SystemExit("Unknown target ID")
    if args.remaining:
        passed = {entry["target"]["id"] for entry in summarize.summarize(CANDIDATE, HELPER)["targets"]
                  if entry["status"] == "pass"}
        targets = [target for target in targets if target["id"] not in passed]
    if args.list:
        for target in targets:
            print(target["id"], "cached=" + str((CACHE / f"paper-{target['version']}-{target['paper_build']}.jar").exists()))
        return
    if args.download_only:
        seen = set()
        for target in targets:
            key = (target["version"], target["paper_build"])
            if key in seen:
                continue
            seen.add(key)
            paper = get_paper(target, download_missing=True)
            print("cached", key[0], key[1], sha(paper, "sha256"), flush=True)
        return
    folder = RUNS / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ-local")
    folder.mkdir(parents=True, exist_ok=False)
    rows = [run(target, folder, args.download_missing) for target in targets]
    print("evidence", folder, flush=True)
    if any(row["status"] != "pass" or not row["stopped"] for row in rows):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
