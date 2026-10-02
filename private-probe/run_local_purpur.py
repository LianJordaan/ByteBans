"""Pin and probe Purpur boundary versions separately from the Paper release gate."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import secrets
import shutil
import subprocess
import time
from urllib.request import Request, urlopen

from run_local_matrix import (CANDIDATE, HELPER, INSTANCES, JAVA, RUNS,
                              SOURCE_REVISION, USER_AGENT, seed_mojang_cache, sha)
import run_matrix


WORKSPACE = Path(__file__).resolve().parents[3]
CACHE = WORKSPACE / "testing/bytebans/cache"
PINS = WORKSPACE / "testing/bytebans/purpur-pins.json"
VERSIONS = ("1.21", "1.21.11")


def official_json(url: str) -> dict:
    with urlopen(Request(url, headers={"User-Agent": USER_AGENT}), timeout=30) as response:
        return json.load(response)


def pin(version: str) -> dict:
    if PINS.is_file():
        entries = json.loads(PINS.read_text(encoding="utf-8"))
    else:
        entries = {}
    if version in entries:
        return entries[version]
    base = f"https://api.purpurmc.org/v2/purpur/{version}"
    latest = official_json(base)["builds"]["latest"]
    metadata = official_json(base + "/" + latest)
    if metadata.get("result") != "SUCCESS" or not metadata.get("md5"):
        raise ValueError(f"Purpur {version} build {latest} was not successful")
    entry = {"version": version, "build": int(latest), "md5": metadata["md5"],
             "publisher_build_url": base + "/" + latest,
             "download_url": base + "/" + latest + "/download",
             "pinned_at": datetime.now(timezone.utc).isoformat()}
    entries[version] = entry
    PINS.parent.mkdir(parents=True, exist_ok=True)
    PINS.write_text(json.dumps(entries, indent=2) + "\n", encoding="utf-8")
    return entry


def jar_for(entry: dict) -> Path:
    local = CACHE / f"purpur-{entry['version']}-{entry['build']}.jar"
    if local.is_file():
        if sha(local, "md5") != entry["md5"]:
            raise ValueError("Cached Purpur MD5 differs from publisher pin")
    else:
        temporary = local.with_suffix(".partial")
        try:
            with urlopen(Request(entry["download_url"], headers={"User-Agent": USER_AGENT}),
                         timeout=180) as response, temporary.open("wb") as output:
                shutil.copyfileobj(response, output)
            if sha(temporary, "md5") != entry["md5"]:
                raise ValueError("Downloaded Purpur MD5 differs from publisher pin")
            temporary.replace(local)
        finally:
            temporary.unlink(missing_ok=True)
    actual_sha256 = sha(local, "sha256")
    if "sha256" in entry:
        if actual_sha256 != entry["sha256"]:
            raise ValueError("Cached Purpur SHA-256 differs from frozen local pin")
    else:
        entries = json.loads(PINS.read_text(encoding="utf-8"))
        entries[entry["version"]]["sha256"] = actual_sha256
        PINS.write_text(json.dumps(entries, indent=2) + "\n", encoding="utf-8")
        entry["sha256"] = actual_sha256
    return local


def run(entry: dict, mode: str, folder: Path) -> dict:
    version = entry["version"]
    jar = jar_for(entry)
    target = {"version": version, "java": 21}
    identity = "mw-" + secrets.token_hex(8)
    instance = INSTANCES / identity
    instance.mkdir(parents=True)
    plugins = instance / "plugins"
    plugins.mkdir()
    shutil.copy2(jar, instance / jar.name)
    seed_mojang_cache(version, instance)
    shutil.copy2(CANDIDATE, plugins / "000-300a632568e3.jar")
    shutil.copy2(HELPER, plugins / "001-d6029de86b40.jar")
    (instance / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (instance / "server.properties").write_text(
        "server-ip=127.0.0.1\nserver-port=27244\n"
        + "online-mode=" + ("true" if mode == "online" else "false") + "\n"
        + "enforce-secure-profile=false\nwhite-list=true\n"
        + "enable-rcon=false\nspawn-protection=0\n"
        + "view-distance=4\nsimulation-distance=4\nlevel-name=world\nmax-players=4\n",
        encoding="utf-8",
    )
    receipt = {"loader": "purpur", "minecraft": version, "purpur_build": entry["build"],
               "publisher_md5": entry["md5"], "publisher_build_url": entry["publisher_build_url"],
               "purpur_sha256_expected": entry["sha256"],
               "purpur_sha256_actual": sha(instance / jar.name, "sha256"),
               "source_revision": SOURCE_REVISION, "candidate_sha512": sha(CANDIDATE, "sha512"),
               "candidate_sha512_actual": sha(plugins / "000-300a632568e3.jar", "sha512"),
               "probe_sha512": sha(HELPER, "sha512"),
               "probe_sha512_actual": sha(plugins / "001-d6029de86b40.jar", "sha512"),
               "java": 21, "java_executable": str(JAVA[21]),
               "connection_mode": "standalone_" + mode,
               "instance_id": identity, "instance_path": str(instance),
               "execution_host": "local-windows-loopback", "port": 27244,
               "started_at": datetime.now(timezone.utc).isoformat(), "status": "error"}
    process = None
    log_path = instance / "server.log"
    try:
        with log_path.open("w", encoding="utf-8", errors="replace") as log:
            process = subprocess.Popen(
                [str(JAVA[21]), "-Xms1G", "-Xmx4G", "-XX:ActiveProcessorCount=4",
                 "-jar", jar.name, "nogui"],
                cwd=instance, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT,
                text=True, encoding="utf-8", errors="replace",
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            )
            result_file = plugins / "ByteBansPrivateProbe/results.json"
            deadline = time.monotonic() + 420
            while time.monotonic() < deadline:
                if result_file.is_file():
                    receipt["probe"] = json.loads(result_file.read_text(encoding="utf-8"))
                    receipt["probe_diagnostics"] = run_matrix.probe_diagnostics(receipt["probe"], target)
                    receipt["status"] = ("pass" if receipt["probe"].get("passed") is True
                                         and all(receipt["probe_diagnostics"].values()) else "fail")
                    break
                if process.poll() is not None:
                    receipt["status"] = "server_exited_before_probe"
                    break
                time.sleep(2)
            else:
                receipt["status"] = "timeout"
        receipt["logs"] = log_path.read_text(encoding="utf-8", errors="replace")
        receipt["startup_diagnostics"] = run_matrix.startup_diagnostics(receipt["logs"])
        if receipt["status"] == "pass" and (
            not receipt["startup_diagnostics"]["initialized"]
            or receipt["startup_diagnostics"]["fatal_lines"]
        ):
            receipt["status"] = "startup_diagnostic_failed"
    except Exception as error:
        receipt["status"] = "error"
        receipt["error"] = repr(error)
    finally:
        if process is not None:
            if process.poll() is None:
                try:
                    process.stdin.write("stop\n")
                    process.stdin.flush()
                    process.wait(timeout=90)
                except Exception as error:
                    receipt["stop_error"] = repr(error)
                    process.kill()
                    process.wait(timeout=30)
            receipt["stopped"] = process.returncode == 0
            receipt["server_exit_code"] = process.returncode
        else:
            receipt["stopped"] = True
        receipt["finished_at"] = datetime.now(timezone.utc).isoformat()
        name = f"purpur-{version}-{mode}-synthetic.json"
        (folder / name).write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    print(name, receipt["status"], "stopped=" + str(receipt["stopped"]), flush=True)
    return receipt


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", action="append", choices=VERSIONS)
    parser.add_argument("--mode", action="append", choices=("online", "offline"))
    parser.add_argument("--pin-only", action="store_true")
    args = parser.parse_args()
    versions = args.version or list(VERSIONS)
    modes = args.mode or ["online", "offline"]
    pins = [pin(version) for version in versions]
    for entry in pins:
        jar = jar_for(entry)
        print("pinned", entry["version"], entry["build"], sha(jar, "sha256"), flush=True)
    if args.pin_only:
        return
    folder = RUNS / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ-purpur")
    folder.mkdir(parents=True, exist_ok=False)
    rows = [run(entry, mode, folder) for entry in pins for mode in modes]
    print("evidence", folder, flush=True)
    if any(row["status"] != "pass" or row["stopped"] is not True for row in rows):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
