"""Real offline-mode TCP checks at pinned old/new Paper boundaries, on localhost."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import secrets
import shutil
import socket
import struct
import subprocess
import time

from run_local_matrix import (CANDIDATE, HELPER, INSTANCES, JAVA, MATRIX, RUNS,
                              SOURCE_REVISION, get_paper, seed_mojang_cache, sha)
from run_offline import Client, assert_true, horizontal_displacement


def send_packet(connection: socket.socket, request_id: int, packet_type: int, value: str) -> None:
    body = struct.pack("<ii", request_id, packet_type) + value.encode() + b"\0\0"
    connection.sendall(struct.pack("<i", len(body)) + body)


def receive_packet(connection: socket.socket) -> tuple[int, str]:
    raw_length = connection.recv(4)
    if len(raw_length) != 4:
        raise RuntimeError("RCON closed before returning a packet")
    length = struct.unpack("<i", raw_length)[0]
    data = bytearray()
    while len(data) < length:
        chunk = connection.recv(length - len(data))
        if not chunk:
            raise RuntimeError("RCON response was truncated")
        data.extend(chunk)
    return struct.unpack("<i", data[:4])[0], bytes(data[8:-2]).decode(errors="replace")


def rcon(password: str, command: str) -> str:
    with socket.create_connection(("127.0.0.1", 27243), timeout=5) as connection:
        connection.settimeout(5)
        send_packet(connection, 1, 3, password)
        request_id, _ = receive_packet(connection)
        if request_id != 1:
            raise RuntimeError("RCON authentication failed")
        send_packet(connection, 2, 2, command)
        return receive_packet(connection)[1]


def wait_ready(password: str, process: subprocess.Popen) -> None:
    deadline = time.monotonic() + 300
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"Paper exited before RCON was ready: {process.returncode}")
        try:
            rcon(password, "list")
            return
        except (OSError, RuntimeError):
            time.sleep(2)
    raise TimeoutError("Paper did not start RCON")


def position(password: str, name: str) -> dict:
    response = rcon(password, "bbprobe position " + name)
    prefix = "BBPROBE "
    if prefix not in response:
        raise RuntimeError("Private server-side position command did not respond: " + response)
    result = json.loads(response.split(prefix, 1)[1].strip())
    if result.get("online") is not True:
        raise RuntimeError(name + " is not online during the position check")
    return result


def run(version: str) -> dict:
    target = next(target for target in MATRIX["targets"]
                  if target["version"] == version and target["connection_mode"] == "standalone_offline")
    paper = get_paper(target, download_missing=False)
    identity = "mw-" + secrets.token_hex(8)
    instance = INSTANCES / identity
    instance.mkdir(parents=True)
    folder = RUNS / (datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
                     + "-local-client-" + version)
    folder.mkdir(parents=True, exist_ok=False)
    password = secrets.token_urlsafe(24)
    evidence = {
        "source_revision": SOURCE_REVISION,
        "candidate_sha512": sha(CANDIDATE, "sha512"),
        "probe_sha512": sha(HELPER, "sha512"),
        "client_sha256": sha(Path(__file__).parent / "client/probe_client.js", "sha256"),
        "version": version,
        "paper_build": target["paper_build"],
        "paper_sha256_expected": target["paper_sha256"],
        "paper_sha256_actual": sha(paper, "sha256"),
        "java": target["java"],
        "java_executable": str(JAVA[target["java"]]),
        "connection_mode": "standalone_offline",
        "execution_host": "local-windows-loopback",
        "instance_id": identity,
        "instance_path": str(instance),
        "port": 27242,
        "started_at": datetime.now(timezone.utc).isoformat(),
        "checks": {},
    }
    shutil.copy2(paper, instance / paper.name)
    seed_mojang_cache(version, instance)
    plugin_dir = instance / "plugins"
    plugin_dir.mkdir()
    shutil.copy2(CANDIDATE, plugin_dir / "000-300a632568e3.jar")
    shutil.copy2(HELPER, plugin_dir / "001-d6029de86b40.jar")
    assert_true(sha(plugin_dir / "000-300a632568e3.jar", "sha512") == evidence["candidate_sha512"],
                "Installed ByteBans hash changed")
    assert_true(sha(plugin_dir / "001-d6029de86b40.jar", "sha512") == evidence["probe_sha512"],
                "Installed private helper hash changed")
    (instance / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (instance / "server.properties").write_text(
        "server-ip=127.0.0.1\nserver-port=27242\n"
        "online-mode=false\nenforce-secure-profile=false\nwhite-list=false\n"
        "enable-rcon=true\nrcon.port=27243\nrcon.password=" + password + "\n"
        "spawn-protection=0\nview-distance=4\nsimulation-distance=4\n"
        "level-name=world\nmax-players=4\n",
        encoding="utf-8",
    )
    process = None
    clients: list[Client] = []
    log_path = instance / "server.log"
    try:
        with log_path.open("w", encoding="utf-8", errors="replace") as log:
            process = subprocess.Popen(
                [str(JAVA[target["java"]]), "-Xms1G", "-Xmx4G", "-XX:ActiveProcessorCount=4",
                 "-jar", paper.name, "nogui"],
                cwd=instance, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT,
                text=True, encoding="utf-8", errors="replace",
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            )
            wait_ready(password, process)
            probe_result = plugin_dir / "ByteBansPrivateProbe/results.json"
            probe_deadline = time.monotonic() + 30
            while not probe_result.is_file() and time.monotonic() < probe_deadline:
                time.sleep(1)
            assert_true(probe_result.is_file(), "Private helper did not finish its startup checks")
            first = Client(27242, "BBRealA", version)
            clients.append(first)
            assert_true(first.expect({"spawn", "kicked", "error"}, 60)["event"] == "spawn",
                        "First real offline client could not join")
            evidence["checks"]["real_offline_join"] = True
            time.sleep(3)
            rcon(password, "ipban user:BBRealA reason:Private TCP probe scope:*")
            kicked = first.expect({"kicked"}, 25)
            assert_true("ban" in json.dumps(kicked).lower(), "Existing client kick was not a ban")
            evidence["checks"]["ip_ban_kicks_existing_client"] = kicked
            rejected = Client(27242, "BBRealB", version)
            clients.append(rejected)
            denial = rejected.expect({"spawn", "kicked", "error"}, 30)
            assert_true(denial["event"] == "kicked" and "ban" in json.dumps(denial).lower(),
                        "Shared-IP client was not denied by the ban")
            evidence["checks"]["ip_ban_rejects_shared_address"] = denial
            rcon(password, "ipunban user:BBRealA reason:Private TCP cleanup")
            time.sleep(3)
            second = Client(27242, "BBRealB", version)
            clients.append(second)
            assert_true(second.expect({"spawn", "kicked", "error"}, 60)["event"] == "spawn",
                        "Unbanned real client could not rejoin")
            evidence["checks"]["ip_unban_restores_join"] = True
            # A marked flat area makes forward movement measurable independent of
            # random world-spawn terrain. No movement event is intercepted here.
            fixture_commands = [
                "execute in minecraft:overworld run fill 0 69 0 20 69 20 minecraft:stone",
                "execute in minecraft:overworld run fill 0 70 0 20 76 20 minecraft:air",
                "tp BBRealB 10.5 70 10.5 0 0",
            ]
            evidence["fixture_commands"] = fixture_commands
            for command in fixture_commands:
                rcon(password, command)
            time.sleep(1)
            prepared_position = position(password, "BBRealB")
            assert_true(abs(prepared_position["x"] - 10.5) < 0.5
                        and abs(prepared_position["z"] - 10.5) < 0.5,
                        "Real client did not reach the open movement fixture")
            rcon(password, "ipmute user:BBRealA reason:Private TCP probe scope:*")
            time.sleep(2)
            second.send("chat", message="ByteBans blocked chat probe")
            second.expect({"chat_sent"}, 5)
            muted = second.expect_message("muted", 15)
            evidence["checks"]["ip_mute_blocks_shared_address_chat"] = muted
            rcon(password, "ipunmute user:BBRealA reason:Private TCP cleanup")
            rcon(password, "freeze user:BBRealB reason:Private TCP probe scope:*")
            time.sleep(2)
            frozen_before = position(password, "BBRealB")
            assert_true(frozen_before["freeze_id"] is not None and not frozen_before["bypass"],
                        "Private server probe found no active freeze")
            second.send("move", duration_ms=2000)
            moved_client = second.expect({"moved"}, 8)
            time.sleep(1)
            frozen_after = position(password, "BBRealB")
            held = horizontal_displacement(frozen_before, frozen_after)
            assert_true(held < 0.3, f"Frozen real client moved {held:.2f} blocks on server")
            evidence["checks"]["freeze_holds_real_client"] = {
                "server_before": frozen_before, "server_after": frozen_after,
                "server_horizontal_blocks": held, "client_prediction": moved_client,
            }
            rcon(password, "unfreeze user:BBRealB reason:Private TCP cleanup")
            time.sleep(2)
            free_before = position(password, "BBRealB")
            assert_true(free_before.get("freeze_id") is None, "Freeze remains after unfreeze")
            second.send("move", duration_ms=2000)
            free_client = second.expect({"moved"}, 8)
            time.sleep(1)
            free_after = position(password, "BBRealB")
            distance = horizontal_displacement(free_before, free_after)
            assert_true(distance > 0.75, f"Unfrozen real client moved only {distance:.2f} blocks")
            evidence["checks"]["unfreeze_restores_real_movement"] = {
                "server_before": free_before, "server_after": free_after,
                "server_horizontal_blocks": distance, "client_prediction": free_client,
            }
            evidence["status"] = "pass"
    except Exception as error:
        evidence["status"] = "fail"
        evidence["error"] = repr(error)
    finally:
        evidence["clients"] = {client.name + "-" + str(i): client.all_events
                               for i, client in enumerate(clients)}
        evidence["client_stderr"] = {client.name + "-" + str(i): client.stderr_lines
                                     for i, client in enumerate(clients)}
        for client in clients:
            client.close()
        if process is not None:
            if process.poll() is None:
                try:
                    process.stdin.write("stop\n")
                    process.stdin.flush()
                    process.wait(timeout=90)
                except Exception as error:
                    evidence["stop_error"] = repr(error)
                    process.kill()
                    process.wait(timeout=30)
            evidence["stopped"] = process.returncode == 0
            evidence["server_exit_code"] = process.returncode
        else:
            evidence["stopped"] = True
        evidence["logs"] = log_path.read_text(encoding="utf-8", errors="replace") if log_path.exists() else ""
        evidence["finished_at"] = datetime.now(timezone.utc).isoformat()
        (folder / "result.json").write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
        check_lines = "".join("- " + name.replace("_", " ") + ": pass\n"
                              for name in evidence["checks"])
        (folder / "report.md").write_text(
            "# ByteBans real offline-client probe\n\n"
            + f"Paper {version} b{target['paper_build']} / Java {target['java']} / offline mode\n\n"
            + f"Status: **{evidence['status']}**. Instance `{identity}` is stopped and retained: "
            + str(evidence["stopped"]) + ".\n\n"
            + "Frozen ByteBans SHA-512: `" + evidence["candidate_sha512"] + "`\n\n"
            + "Pinned Paper SHA-256: `" + evidence["paper_sha256_actual"] + "`\n\n"
            + "Checks:\n\n" + check_lines + "\n"
            + ("Failure: `" + evidence["error"] + "`\n\n" if evidence.get("error") else "")
            + "The login, punishment and movement checks used real loopback Minecraft TCP clients. "
            + "Online-mode authenticated account login remains untested.\n",
            encoding="utf-8",
        )
        print(version, evidence["status"], folder, flush=True)
    return evidence


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("version", choices=("1.21", "1.21.4", "1.21.11"))
    args = parser.parse_args()
    result = run(args.version)
    if result["status"] != "pass" or result["stopped"] is not True:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
