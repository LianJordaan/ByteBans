"""Probe frozen ByteBans across two Paper servers and an isolated WSL MariaDB.

All listeners bind to local/test ports; the database and its random credentials
exist only for this run. No production database or Discord endpoint is used.
"""

from __future__ import annotations

from datetime import datetime, timezone
import json
from pathlib import Path
import re
import secrets
import shutil
import socket
import struct
import subprocess
import time

from run_local_matrix import (CANDIDATE, EXPECTED_CANDIDATE_SHA512,
                              INSTANCES, JAVA, MATRIX, RUNS, SOURCE_REVISION,
                              get_paper, seed_mojang_cache, sha)
from run_offline import Client


HERE = Path(__file__).resolve().parent
CONFIG = HERE.parent / "src/main/resources/config.yml"
MYSQL_PORT = 27314
SERVERS = (("alpha", 27310, 27312), ("beta", 27311, 27313))


def now() -> str:
    return datetime.now(timezone.utc).isoformat()


def rcon(port: int, password: str, command: str) -> str:
    def receive(connection: socket.socket) -> tuple[int, str]:
        length_bytes = connection.recv(4)
        if len(length_bytes) != 4:
            raise OSError("RCON returned no packet")
        length = struct.unpack("<i", length_bytes)[0]
        data = bytearray()
        while len(data) < length:
            block = connection.recv(length - len(data))
            if not block:
                raise OSError("RCON response truncated")
            data.extend(block)
        return struct.unpack("<i", data[:4])[0], bytes(data[8:-2]).decode(errors="replace")

    def send(connection: socket.socket, request_id: int, kind: int, value: str) -> None:
        body = struct.pack("<ii", request_id, kind) + value.encode() + b"\0\0"
        connection.sendall(struct.pack("<i", len(body)) + body)

    with socket.create_connection(("127.0.0.1", port), timeout=5) as connection:
        connection.settimeout(5)
        send(connection, 1, 3, password)
        if receive(connection)[0] != 1:
            raise OSError("RCON authentication failed")
        send(connection, 2, 2, command)
        return receive(connection)[1]


def wait_until(test, timeout: float, description: str):
    deadline = time.monotonic() + timeout
    latest = None
    while time.monotonic() < deadline:
        try:
            latest = test()
            if latest:
                return latest
        except (OSError, RuntimeError, TimeoutError) as error:
            latest = repr(error)
        time.sleep(0.5)
    raise TimeoutError(f"{description}; latest={latest!r}")


def wsl(*args: str, timeout: int = 30, root: bool = False) -> str:
    command = ["wsl.exe"] + (["--user", "root"] if root else []) + ["--exec", *args]
    completed = subprocess.run(command, capture_output=True,
                               text=True, encoding="utf-8", errors="replace", timeout=timeout)
    if completed.returncode:
        raise RuntimeError(f"WSL command failed ({args[0]}): {completed.stderr[-1200:]}")
    return completed.stdout


def write_config(path: Path, name: str, host: str, database: str,
                 user: str, password: str) -> None:
    config = CONFIG.read_text(encoding="utf-8")
    changes = (
        ('name: "hub"', f'name: "{name}"'),
        ('type: sqlite', 'type: mysql'),
        ('host: "localhost"', f'host: "{host}"'),
        ('port: 3306', f'port: {MYSQL_PORT}'),
        ('database: "bytebans"', f'database: "{database}"'),
        ('username: ""', f'username: "{user}"'),
        ('password: ""', f'password: "{password}"'),
    )
    for old, new in changes:
        if config.count(old) != 1:
            raise ValueError(f"Expected one config setting: {old}")
        config = config.replace(old, new, 1)
    path.write_text(config, encoding="utf-8")


def stop(process: subprocess.Popen | None, label: str) -> dict:
    if process is None:
        return {"label": label, "stopped": True, "exit_code": None}
    if process.poll() is None:
        try:
            process.stdin.write("stop\n")
            process.stdin.flush()
            process.wait(timeout=90)
        except Exception:
            process.kill()
            process.wait(timeout=30)
    return {"label": label, "stopped": process.poll() is not None,
            "exit_code": process.returncode}


def start_server(info: dict, paper: Path, log_name: str = "server.log") -> subprocess.Popen:
    log = (info["path"] / log_name).open("w", encoding="utf-8", errors="replace")
    process = subprocess.Popen(
        [str(JAVA[21]), "-Xms1G", "-Xmx4G", "-XX:ActiveProcessorCount=4",
         "-jar", paper.name, "nogui"], cwd=info["path"], stdin=subprocess.PIPE,
        stdout=log, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace",
        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
    )
    log.close()
    try:
        wait_until(lambda: rcon(info["rcon_port"], info["rcon_password"], "list"),
                   300, f"{info['name']} Paper/RCON did not start")
        version = rcon(info["rcon_port"], info["rcon_password"], "version ByteBans")
        if "ByteBans" not in version or "1.1.0" not in version:
            raise RuntimeError(f"{info['name']} did not load ByteBans 1.1.0: {version}")
    except Exception:
        stop(process, info["name"] + "-startup-failed")
        raise
    return process


def history(info: dict) -> str:
    return rcon(info["rcon_port"], info["rcon_password"], "history ip:127.0.0.1")


def command(info: dict, value: str) -> None:
    # New moderation commands save asynchronously. RCON returns before the
    # completion message, so the synchronous response can legitimately be empty.
    response = rcon(info["rcon_port"], info["rcon_password"], value)
    if "error" in response.lower() or "invalid" in response.lower():
        raise AssertionError(f"{info['name']} rejected {value}: {response}")


def create(info: dict, value: str, kind: str, reason: str) -> int:
    command(info, value)

    def find_id():
        match = re.search(rf"#([0-9]+) {re.escape(kind)} \[active\] {re.escape(reason)}",
                          history(info))
        return int(match.group(1)) if match else None

    return wait_until(find_id, 20, f"{kind} was not saved on {info['name']}")


def client_spawn(port: int, name: str) -> Client:
    client = Client(port, name, "1.21.4")
    event = client.expect({"spawn", "kicked", "error"}, 45)
    if event["event"] != "spawn":
        client.close()
        raise AssertionError(f"{name} did not join: {event}")
    return client


def run() -> dict:
    target = next(row for row in MATRIX["targets"] if row["version"] == "1.21.4"
                  and row["connection_mode"] == "standalone_offline")
    paper = get_paper(target, download_missing=False)
    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-local-mysql-network"
    folder = RUNS / run_id
    folder.mkdir(parents=True, exist_ok=False)
    random_id = secrets.token_hex(6)
    data_path = f"/tmp/bytebans-network-{random_id}"
    db_name = "bbn_" + random_id
    db_user = "bbn_" + secrets.token_hex(5)
    db_password = secrets.token_urlsafe(30)
    root_socket = data_path + "/mysql.sock"
    receipt = {
        "run_id": run_id, "started_at": now(), "status": "error",
        "source_revision": SOURCE_REVISION,
        "runner_sha256": sha(Path(__file__), "sha256"),
        "client_sha256": sha(HERE / "client/probe_client.js", "sha256"),
        "candidate_sha512": sha(CANDIDATE, "sha512"),
        "paper_version": "1.21.4", "paper_build": target["paper_build"],
        "paper_sha256": sha(paper, "sha256"),
        "java_executable": str(JAVA[21]), "java_major": 21,
        "mariadb_port": MYSQL_PORT, "mariadb_data_path": data_path,
        "database_name": db_name, "database_user": db_user,
        "checks": {}, "observations": {}, "servers": [], "stops": [],
    }
    processes: dict[str, subprocess.Popen] = {}
    clients: list[Client] = []
    db_process = None
    db_log = (folder / "mariadb-launch.log").open("w", encoding="utf-8", errors="replace")
    infos: dict[str, dict] = {}
    try:
        if receipt["candidate_sha512"] != EXPECTED_CANDIDATE_SHA512:
            raise ValueError("Frozen ByteBans JAR hash changed")
        if receipt["paper_sha256"] != target["paper_sha256"]:
            raise ValueError("Pinned Paper JAR hash changed")
        wsl("mkdir", "-m", "700", data_path)
        wsl("mariadb-install-db", "--no-defaults", f"--datadir={data_path}",
            "--skip-test-db", "--auth-root-authentication-method=socket", timeout=120)
        db_process = subprocess.Popen(
            ["wsl.exe", "--exec", "/usr/sbin/mariadbd", "--no-defaults",
             f"--datadir={data_path}", f"--socket={root_socket}",
             f"--pid-file={data_path}/mysql.pid", f"--log-error={data_path}/mysql.log",
             f"--port={MYSQL_PORT}", "--bind-address=127.0.0.1", "--skip-name-resolve"],
            stdout=db_log, stderr=subprocess.STDOUT,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
        wait_until(lambda: wsl("mariadb-admin", "--no-defaults", "--protocol=socket",
                               f"--socket={root_socket}", "--user=root", "ping",
                               timeout=8, root=True)
                   if db_process.poll() is None else False,
                   60, "isolated MariaDB did not start")
        with socket.create_connection(("127.0.0.1", MYSQL_PORT), timeout=5):
            db_host = "127.0.0.1"
        sql = (f"CREATE DATABASE `{db_name}`; "
               f"CREATE USER '{db_user}'@'%' IDENTIFIED BY '{db_password}'; "
               f"GRANT ALL PRIVILEGES ON `{db_name}`.* TO '{db_user}'@'%'; FLUSH PRIVILEGES;")
        wsl("mariadb", "--no-defaults", "--protocol=socket", f"--socket={root_socket}",
            "--user=root", "--execute=" + sql, root=True)
        receipt["checks"]["isolated_mariadb_started"] = True

        for name, port, rcon_port in SERVERS:
            instance_id = "mw-" + secrets.token_hex(8)
            path = INSTANCES / instance_id
            data = path / "plugins/ByteBans"
            data.mkdir(parents=True)
            info = {"name": name, "id": instance_id, "path": path,
                    "port": port, "rcon_port": rcon_port,
                    "rcon_password": secrets.token_urlsafe(24)}
            infos[name] = info
            shutil.copy2(paper, path / paper.name)
            seed_mojang_cache("1.21.4", path)
            installed = path / "plugins/000-300a632568e3.jar"
            shutil.copy2(CANDIDATE, installed)
            if sha(installed, "sha512") != EXPECTED_CANDIDATE_SHA512:
                raise ValueError(f"Installed ByteBans JAR hash changed on {name}")
            write_config(data / "config.yml", name, db_host, db_name, db_user, db_password)
            (path / "eula.txt").write_text("eula=true\n", encoding="utf-8")
            (path / "server.properties").write_text(
                f"server-ip=127.0.0.1\nserver-port={port}\n"
                f"enable-rcon=true\nrcon.port={rcon_port}\n"
                f"rcon.password={info['rcon_password']}\n"
                "online-mode=false\nenforce-secure-profile=false\n"
                "white-list=false\nspawn-protection=0\n"
                "view-distance=4\nsimulation-distance=4\nlevel-name=world\n",
                encoding="utf-8",
            )
            receipt["servers"].append({"name": name, "instance_id": instance_id,
                                       "path": str(path), "game_port": port,
                                       "rcon_port": rcon_port,
                                       "candidate_sha512_actual": sha(installed, "sha512")})

        for name in ("alpha", "beta"):
            processes[name] = start_server(infos[name], paper)
        receipt["checks"]["two_paper_servers_started"] = True
        assert "No punishments" in history(infos["alpha"])
        assert "No punishments" in history(infos["beta"])
        receipt["checks"]["initial_histories_empty"] = True

        # A scoped ban issued on alpha must be visible and enforced on beta only.
        beta_client = client_spawn(infos["beta"]["port"], "BBNetBeta")
        clients.append(beta_client)
        scoped_id = create(infos["alpha"],
            "ipban ip:127.0.0.1 reason:NetScopedBan scope:beta", "ipban", "NetScopedBan")
        receipt["observations"]["scoped_ban_id"] = scoped_id
        wait_until(lambda: f"#{scoped_id} ipban [active]" in history(infos["beta"]),
                   20, "scoped ban did not propagate to beta history")
        beta_client.expect({"kicked"}, 20)
        clients.remove(beta_client)
        beta_client.close()
        alpha_client = client_spawn(infos["alpha"]["port"], "BBNetAlpha")
        clients.append(alpha_client)
        receipt["checks"]["scoped_ban_beta_only"] = True
        command(infos["beta"], f"ipunban id:{scoped_id} reason:NetworkProbeCleanup")
        wait_until(lambda: f"#{scoped_id} ipban [inactive]" in history(infos["alpha"]),
                   20, "scoped unban did not propagate to alpha")
        receipt["checks"]["cross_server_unban_history"] = True

        # Global ban must kick existing players and reject fresh joins on both.
        beta_client = client_spawn(infos["beta"]["port"], "BBNetBeta2")
        clients.append(beta_client)
        global_id = create(infos["alpha"],
            "ipban ip:127.0.0.1 reason:NetGlobalBan scope:*", "ipban", "NetGlobalBan")
        receipt["observations"]["global_ban_id"] = global_id
        wait_until(lambda: f"#{global_id} ipban [active]" in history(infos["beta"]),
                   20, "global ban did not reach beta")
        beta_client.expect({"kicked"}, 20)
        alpha_client.expect({"kicked"}, 20)
        for old in (alpha_client, beta_client):
            clients.remove(old)
            old.close()
        blocked = Client(infos["beta"]["port"], "BBNetBlocked", "1.21.4")
        clients.append(blocked)
        denial = blocked.expect({"spawn", "kicked", "error"}, 30)
        if denial["event"] != "kicked" or "ban" not in str(denial).lower():
            raise AssertionError(f"Global ban did not reject a beta join: {denial}")
        clients.remove(blocked)
        blocked.close()
        receipt["checks"]["global_ban_both_servers"] = True

        # A restart should load the shared database and preserve enforcement.
        receipt["stops"].append(stop(processes.pop("beta"), "beta-before-restart"))
        processes["beta"] = start_server(infos["beta"], paper, "server-restart.log")
        wait_until(lambda: f"#{global_id} ipban [active]" in history(infos["beta"]),
                   20, "global ban was missing after beta restart")
        blocked = Client(infos["beta"]["port"], "BBNetRestart", "1.21.4")
        clients.append(blocked)
        denial = blocked.expect({"spawn", "kicked", "error"}, 30)
        if denial["event"] != "kicked" or "ban" not in str(denial).lower():
            raise AssertionError(f"Ban was not enforced after restart: {denial}")
        clients.remove(blocked)
        blocked.close()
        receipt["checks"]["restart_loads_shared_ban"] = True
        command(infos["beta"], f"ipunban id:{global_id} reason:NetworkProbeCleanup")
        wait_until(lambda: f"#{global_id} ipban [inactive]" in history(infos["alpha"]),
                   20, "global unban did not return to alpha")

        # Scoped mute is visible everywhere, but only beta suppresses chat.
        alpha_client = client_spawn(infos["alpha"]["port"], "BBNetAlpha3")
        beta_client = client_spawn(infos["beta"]["port"], "BBNetBeta3")
        clients.extend((alpha_client, beta_client))
        mute_id = create(infos["alpha"],
            "ipmute ip:127.0.0.1 reason:NetScopedMute scope:beta", "ipmute", "NetScopedMute")
        receipt["observations"]["scoped_mute_id"] = mute_id
        wait_until(lambda: f"#{mute_id} ipmute [active]" in history(infos["beta"]),
                   20, "scoped mute did not reach beta")
        beta_client.send("chat", message="muted-network-probe")
        beta_client.expect_message("You are muted", 20)
        alpha_client.send("chat", message="alpha-unmuted-network-probe")
        wait_until(lambda: "<BBNetAlpha3> alpha-unmuted-network-probe" in
                   (infos["alpha"]["path"] / "server.log").read_text(
                       encoding="utf-8", errors="replace"),
                   10, "beta-scoped mute also suppressed alpha chat")
        receipt["checks"]["scoped_mute_beta_only"] = True
        command(infos["beta"], f"ipunmute id:{mute_id} reason:NetworkProbeCleanup")
        wait_until(lambda: f"#{mute_id} ipmute [inactive]" in history(infos["alpha"]),
                   20, "unmute did not reach alpha")
        beta_client.send("chat", message="beta-unmuted-network-probe")
        wait_until(lambda: "<BBNetBeta3> beta-unmuted-network-probe" in
                   (infos["beta"]["path"] / "server-restart.log").read_text(
                       encoding="utf-8", errors="replace"),
                   10, "cross-server unmute did not restore beta chat")
        receipt["checks"]["cross_server_unmute_history"] = True

        # An active global mute issued by beta should suppress alpha too.
        global_mute_id = create(infos["beta"],
            "ipmute ip:127.0.0.1 reason:NetGlobalMute scope:*", "ipmute", "NetGlobalMute")
        receipt["observations"]["global_mute_id"] = global_mute_id
        wait_until(lambda: f"#{global_mute_id} ipmute [active]" in history(infos["alpha"]),
                   20, "global mute did not reach alpha")
        alpha_client.send("chat", message="global-mute-network-probe")
        alpha_client.expect_message("You are muted", 20)
        command(infos["alpha"], f"ipunmute id:{global_mute_id} reason:NetworkProbeCleanup")
        wait_until(lambda: f"#{global_mute_id} ipmute [inactive]" in history(infos["beta"]),
                   20, "global unmute did not reach beta")
        alpha_client.send("chat", message="alpha-global-unmuted-network-probe")
        wait_until(lambda: "<BBNetAlpha3> alpha-global-unmuted-network-probe" in
                   (infos["alpha"]["path"] / "server.log").read_text(
                       encoding="utf-8", errors="replace"),
                   10, "global unmute did not restore alpha chat")
        receipt["checks"]["global_mute_alpha_enforced"] = True
        receipt["status"] = "pass"
    except Exception as error:
        receipt["status"] = "fail"
        receipt["error"] = repr(error)
    finally:
        for client in clients:
            client.close()
        for name, process in list(processes.items()):
            receipt["stops"].append(stop(process, name))
        for info in infos.values():
            for log_name in ("server.log", "server-restart.log"):
                path = info["path"] / log_name
                if path.is_file():
                    receipt["observations"][f"{info['name']}_{log_name}_sha256"] = sha(path, "sha256")
        if db_process is not None:
            try:
                wsl("mariadb-admin", "--no-defaults", "--protocol=socket",
                    f"--socket={root_socket}", "--user=root", "shutdown",
                    timeout=15, root=True)
                db_process.wait(timeout=20)
            except Exception as error:
                receipt["mariadb_stop_error"] = repr(error)
                if db_process.poll() is None:
                    db_process.kill()
                    db_process.wait(timeout=10)
            receipt["mariadb_exit_code"] = db_process.returncode
            receipt["mariadb_stopped"] = db_process.poll() is not None
            try:
                (folder / "mariadb.log").write_text(
                    wsl("cat", f"{data_path}/mysql.log", timeout=10), encoding="utf-8")
            except Exception:
                pass
        db_log.close()
        receipt["checks"]["services_stopped_cleanly"] = (
            all(stop_row["stopped"] and stop_row["exit_code"] == 0
                for stop_row in receipt["stops"])
            and receipt.get("mariadb_stopped") is True
            and receipt.get("mariadb_exit_code") == 0
        )
        if receipt["status"] == "pass" and not receipt["checks"]["services_stopped_cleanly"]:
            receipt["status"] = "fail"
            receipt["error"] = "A test service did not stop cleanly"
        receipt["finished_at"] = now()
        (folder / "result.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
        lines = ["# ByteBans two-server MySQL probe", "",
                 f"Status: **{receipt['status']}**", "",
                 f"Frozen JAR SHA-512: `{receipt['candidate_sha512']}`", "",
                 f"Paper: {receipt['paper_version']} build {receipt['paper_build']}", "",
                 "| Check | Result |", "| --- | --- |"]
        lines += [f"| {key} | {'pass' if value else 'fail'} |"
                  for key, value in receipt["checks"].items()]
        if receipt.get("error"):
            lines += ["", "Failure: `" + receipt["error"] + "`"]
        (folder / "report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(folder / "report.md")
    print(receipt["status"], receipt["checks"])
    return receipt


if __name__ == "__main__":
    result = run()
    raise SystemExit(0 if result["status"] == "pass" else 1)
