"""Exercise the frozen ByteBans admin page and SQLite audit on local Purpur."""

from __future__ import annotations

from datetime import datetime, timezone
import json
from pathlib import Path
import re
import secrets
import shutil
import socket
import subprocess
import time

import admin_web_http_probe
from run_local_matrix import (CANDIDATE, EXPECTED_CANDIDATE_SHA512,
                              INSTANCES, JAVA, RUNS, SOURCE_REVISION,
                              seed_mojang_cache, sha)
from run_local_purpur import pin, jar_for


HERE = Path(__file__).resolve().parent
CONFIG = HERE.parent / "src/main/resources/config.yml"


def wait_ready(process: subprocess.Popen, token_file: Path) -> None:
    deadline = time.monotonic() + 300
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"Purpur exited before ByteBans admin page was ready: {process.returncode}")
        if token_file.is_file():
            try:
                with socket.create_connection(("127.0.0.1", 27247), timeout=2):
                    return
            except OSError:
                pass
        time.sleep(2)
    raise TimeoutError("ByteBans admin page did not start")


def run() -> dict:
    entry = pin("26.2")
    purpur = jar_for(entry)
    java = 25
    identity = "mw-" + secrets.token_hex(8)
    instance = INSTANCES / identity
    plugins = instance / "plugins"
    data = plugins / "ByteBans"
    data.mkdir(parents=True)
    folder = RUNS / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ-local-purpur-admin-web")
    folder.mkdir(parents=True, exist_ok=False)
    shutil.copy2(purpur, instance / purpur.name)
    seed_mojang_cache(entry["version"], instance)
    candidate_path = plugins / "000-300a632568e3.jar"
    shutil.copy2(CANDIDATE, candidate_path)
    original_config = CONFIG.read_text(encoding="utf-8")
    configured, replacements = re.subn(
        r"(?m)^admin_web:\n  enabled: false\n  port: 8765$",
        "admin_web:\n  enabled: true\n  port: 27247",
        original_config,
    )
    if replacements != 1:
        raise ValueError("Expected exactly one disabled admin_web configuration block")
    (data / "config.yml").write_text(configured, encoding="utf-8")
    (instance / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (instance / "server.properties").write_text(
        "server-ip=127.0.0.1\nserver-port=27246\n"
        "online-mode=false\nwhite-list=true\nenable-rcon=false\n"
        "view-distance=4\nsimulation-distance=4\nlevel-name=world\n",
        encoding="utf-8",
    )
    receipt = {
        "source_revision": SOURCE_REVISION,
        "candidate_sha512": sha(CANDIDATE, "sha512"),
        "candidate_sha512_actual": sha(candidate_path, "sha512"),
        "script_sha256": sha(HERE / "admin_web_http_probe.py", "sha256"),
        "runner_sha256": sha(Path(__file__), "sha256"),
        "instance_id": identity, "instance_path": str(instance),
        "execution_host": "local-windows-loopback", "port": 27246,
        "loader": "purpur", "version": "26.2", "purpur_build": entry["build"],
        "publisher_md5": entry["md5"],
        "purpur_sha256_expected": entry["sha256"],
        "purpur_sha256_actual": sha(instance / purpur.name, "sha256"),
        "java": java, "java_executable": str(JAVA[java]),
        "panel_port": 27247, "started_at": datetime.now(timezone.utc).isoformat(),
        "status": "error",
    }
    process = None
    log_path = instance / "server.log"
    try:
        if receipt["candidate_sha512"] != EXPECTED_CANDIDATE_SHA512:
            raise ValueError("Frozen ByteBans candidate hash changed")
        if receipt["purpur_sha256_actual"] != entry["sha256"]:
            raise ValueError("Pinned Purpur SHA-256 changed")
        with socket.socket() as probe_socket:
            probe_socket.bind(("127.0.0.1", 27247))
        with log_path.open("w", encoding="utf-8", errors="replace") as log:
            process = subprocess.Popen(
                [str(JAVA[java]), "-Xms1G", "-Xmx4G", "-XX:ActiveProcessorCount=4",
                 "-jar", purpur.name, "nogui"],
                cwd=instance, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT,
                text=True, encoding="utf-8", errors="replace",
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            )
            wait_ready(process, data / "admin-web-token.txt")
            result = admin_web_http_probe.main(
                identity, receipt["candidate_sha512"], plugin_folder=plugins,
                base="http://127.0.0.1:27247", check_posix_permissions=False,
            )
            receipt["checks"] = result["checks"]
            receipt["subject"] = result["subject"]
            receipt["note_id"] = result["note_id"]
            receipt["status"] = "pass"
    except Exception as error:
        receipt["status"] = "fail"
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
            receipt["server_exit_code"] = process.returncode
            receipt["server_stopped"] = process.returncode == 0
        else:
            receipt["server_stopped"] = True
        (data / "config.yml").write_text(original_config, encoding="utf-8")
        receipt["config_restored_disabled"] = (
            "admin_web:\n  enabled: false\n  port: 8765" in (data / "config.yml").read_text(encoding="utf-8")
        )
        receipt["logs"] = log_path.read_text(encoding="utf-8", errors="replace") if log_path.exists() else ""
        receipt["plugin_initialized"] = (
            "[ByteBans] ByteBans was successfully initialized." in receipt["logs"]
            and "Error occurred while enabling ByteBans" not in receipt["logs"]
        )
        receipt["panel_bound_loopback"] = (
            "Local admin web panel listening on 127.0.0.1:27247" in receipt["logs"]
        )
        if receipt["status"] == "pass" and not all((
            receipt["server_stopped"], receipt["config_restored_disabled"],
            receipt["plugin_initialized"], receipt["panel_bound_loopback"],
        )):
            receipt["status"] = "verification_failed"
        receipt["token_permissions_check"] = "not checked: Windows ACL semantics differ from POSIX mode 0600"
        receipt["recorded_at"] = datetime.now(timezone.utc).isoformat()
        (folder / "result.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
        print("local Purpur admin web", receipt["status"], folder, flush=True)
    return receipt


if __name__ == "__main__":
    result = run()
    if result["status"] != "pass":
        raise SystemExit(1)
