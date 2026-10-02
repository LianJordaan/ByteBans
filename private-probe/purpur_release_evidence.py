"""Verify the frozen ByteBans JAR's separate Purpur live-test evidence."""

from __future__ import annotations

from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re


MATRIX_SHA256 = "71dd968ef6f61067032453d711f8cf1b86e732b8d1ffccb87238bbf28c402bc9"
ADMIN_SCRIPT_SHA256 = "d7617df46d0fae8a597e3eda705d1abea04c5ed18006e6218df51e0ace8e1321"
EXPECTED_VERSIONS = (
    "1.21", "1.21.1", "1.21.3", "1.21.4", "1.21.5", "1.21.6",
    "1.21.7", "1.21.8", "1.21.9", "1.21.10", "1.21.11",
    "26.1.2", "26.2", "26.3",
)
CLIENT_VERSIONS = ("1.21", "1.21.11")
REQUIRED_CASES = frozenset({
    "ip_ban_login_and_operator_bypass", "ip_ban_scope_exclusion",
    "ip_mute_chat_and_operator_bypass", "freeze_movement_and_operator_bypass",
})
CLIENT_CHECKS = frozenset({
    "real_offline_join", "ip_ban_kicks_existing_client",
    "ip_ban_rejects_shared_address", "ip_unban_restores_join",
    "ip_mute_blocks_shared_address_chat", "freeze_holds_real_client",
    "unfreeze_restores_real_movement",
})
WEB_CHECKS = frozenset({
    "loopback_login_page", "host_restriction", "wrong_token_denied",
    "authenticated_login", "csrf_rejection_no_write",
    "note_create_with_actor", "note_undo_with_audit",
})
HEX64 = re.compile(r"[a-f0-9]{64}\Z")
HEX32 = re.compile(r"[a-f0-9]{32}\Z")
INSTANCE_ID = re.compile(r"mw-[a-f0-9]{16}\Z")


def _digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _read(path: Path) -> dict:
    if path.is_symlink() or not path.is_file():
        raise ValueError("Missing or symbolic-link Purpur evidence: " + str(path))
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError("Purpur evidence is not an object: " + str(path))
    return value


def _timestamp(row: dict) -> datetime:
    raw = row.get("finished_at") or row.get("recorded_at")
    try:
        value = datetime.fromisoformat(raw.replace("Z", "+00:00"))
        return value.astimezone(timezone.utc) if value.tzinfo else datetime.min.replace(tzinfo=timezone.utc)
    except (AttributeError, TypeError, ValueError):
        return datetime.min.replace(tzinfo=timezone.utc)


def _latest(runs: Path, pattern: str, candidate_sha: str, *, version=None, mode=None):
    matches = []
    for path in runs.glob(pattern):
        try:
            row = _read(path)
        except (OSError, ValueError):
            continue
        if row.get("candidate_sha512") != candidate_sha:
            continue
        if version is not None and (row.get("minecraft") or row.get("version")) != version:
            continue
        if mode is not None and row.get("connection_mode") != "standalone_" + mode:
            continue
        matches.append((_timestamp(row), path, row))
    return max(matches, key=lambda item: item[0]) if matches else None


def _matrix(base: Path) -> tuple[list[dict], list[str]]:
    path = base / "purpur-matrix.json"
    issues = []
    try:
        if _digest(path) != MATRIX_SHA256:
            raise ValueError("frozen SHA-256 differs")
        matrix = _read(path)
        rows = matrix.get("versions")
        if (matrix.get("revision") != 1 or matrix.get("loader") != "purpur"
                or matrix.get("unavailable") != ["26.1.1"]
                or not isinstance(rows, list) or len(rows) != len(EXPECTED_VERSIONS)
                or tuple(item.get("version") for item in rows) != EXPECTED_VERSIONS):
            raise ValueError("version list, loader or unavailable build differs")
        for row in rows:
            version = row["version"]
            expected_java = 25 if version.startswith("26.") else 21
            if (set(row) != {"version", "build", "java", "md5", "sha256", "publisher_build_url"}
                    or not isinstance(row["build"], int) or row["build"] < 1
                    or row["java"] != expected_java
                    or not HEX32.fullmatch(str(row["md5"]))
                    or not HEX64.fullmatch(str(row["sha256"]))
                    or row["publisher_build_url"]
                        != f"https://api.purpurmc.org/v2/purpur/{version}/{row['build']}"):
                raise ValueError("invalid pinned Purpur build for " + version)
        return rows, issues
    except (OSError, ValueError, KeyError, TypeError) as error:
        issues.append("Frozen Purpur 14-version matrix is invalid: " + str(error))
        return [], issues


def _synthetic_issues(row: dict, pin: dict, mode: str, candidate_sha: str,
                      probe_sha: str, source_revision: str) -> list[str]:
    problems = []
    version = pin["version"]
    if (row.get("status") != "pass" or row.get("stopped") is not True
            or row.get("loader") != "purpur" or row.get("minecraft") != version
            or row.get("connection_mode") != "standalone_" + mode
            or row.get("source_revision") != source_revision
            or row.get("candidate_sha512") != candidate_sha
            or row.get("candidate_sha512_actual") != candidate_sha
            or row.get("probe_sha512") != probe_sha
            or row.get("probe_sha512_actual") != probe_sha
            or row.get("purpur_build") != pin["build"]
            or row.get("publisher_md5") != pin["md5"]
            or row.get("publisher_build_url") != pin["publisher_build_url"]
            or row.get("purpur_sha256_expected") != pin["sha256"]
            or row.get("purpur_sha256_actual") != pin["sha256"]
            or row.get("java") != pin["java"]
            or row.get("execution_host") != "local-windows-loopback"
            or row.get("port") != 27244
            or not INSTANCE_ID.fullmatch(str(row.get("instance_id", "")))
            or _timestamp(row) == datetime.min.replace(tzinfo=timezone.utc)
            or not row.get("started_at")):
        problems.append("Attempt identity, frozen artifact, build pin, Java or stopped state differs")
    probe = row.get("probe") or {}
    diagnostics = row.get("probe_diagnostics") or {}
    startup = row.get("startup_diagnostics") or {}
    if (not isinstance(probe, dict) or probe.get("probe_revision") != "1"
            or probe.get("minecraft") != version
            or str(probe.get("java", "")).split(".")[0] != str(pin["java"])
            or probe.get("bytebans_version") != "1.1.0"
            or version not in str(probe.get("server", ""))
            or probe.get("passed") is not True
            or not isinstance(probe.get("cases"), dict)
            or not all(probe["cases"].get(name) is True for name in REQUIRED_CASES)
            or not isinstance(diagnostics, dict) or not diagnostics
            or not all(value is True for value in diagnostics.values())
            or not isinstance(startup, dict) or startup.get("initialized") is not True
            or startup.get("fatal_lines") != []):
        problems.append("Live synthetic event/startup checks are incomplete")
    logs = row.get("logs")
    if (not isinstance(logs, str)
            or "[ByteBans] ByteBans was successfully initialized." not in logs
            or "Error occurred while enabling ByteBans" in logs
            or re.search(r"Could not pass event .+ to ByteBans", logs)
            or re.search(r"\[ByteBans\].*\b(?:ERROR|SEVERE)\b", logs)):
        problems.append("Saved Purpur server diagnostics are missing or contain ByteBans errors")
    return problems


def _client_issues(row: dict, pin: dict, candidate_sha: str, probe_sha: str,
                   source_revision: str, client_sha256: str) -> list[str]:
    issues = []
    if (row.get("status") != "pass" or row.get("stopped") is not True
            or row.get("loader") != "purpur" or row.get("version") != pin["version"]
            or row.get("source_revision") != source_revision
            or row.get("candidate_sha512") != candidate_sha
            or row.get("probe_sha512") != probe_sha
            or row.get("client_sha256") != client_sha256
            or row.get("purpur_build") != pin["build"]
            or row.get("purpur_sha256_expected") != pin["sha256"]
            or row.get("purpur_sha256_actual") != pin["sha256"]
            or row.get("java") != pin["java"]
            or row.get("connection_mode") != "standalone_offline"
            or row.get("port") != 27242
            or not INSTANCE_ID.fullmatch(str(row.get("instance_id", "")))):
        issues.append("Real offline-client run does not match the frozen candidate and Purpur pin")
    checks = row.get("checks") or {}
    if not isinstance(checks, dict) or not CLIENT_CHECKS.issubset(checks):
        issues.append("Real offline-client checklist is incomplete")
    elif (checks["real_offline_join"] is not True
          or checks["ip_unban_restores_join"] is not True
          or any(not isinstance(checks[name], dict)
                 or checks[name].get("event") != "kicked"
                 for name in ("ip_ban_kicks_existing_client", "ip_ban_rejects_shared_address"))
          or not isinstance(checks["ip_mute_blocks_shared_address_chat"], dict)
          or checks["ip_mute_blocks_shared_address_chat"].get("event") != "message"):
        issues.append("Real offline-client login, IP ban or IP mute did not pass")
    else:
        held = checks["freeze_holds_real_client"]
        free = checks["unfreeze_restores_real_movement"]
        if (not isinstance(held, dict) or not isinstance(free, dict)
                or not isinstance(held.get("server_horizontal_blocks"), (int, float))
                or not isinstance(free.get("server_horizontal_blocks"), (int, float))
                or held["server_horizontal_blocks"] >= 0.3
                or free["server_horizontal_blocks"] <= 0.75
                or (held.get("server_before") or {}).get("freeze_id") is None
                or (held.get("server_before") or {}).get("bypass") is not False
                or (free.get("server_before") or {}).get("freeze_id") is not None):
            issues.append("Authoritative freeze/unfreeze movement proof is incomplete")
    return issues


def _web_issues(row: dict, pin: dict, candidate_sha: str,
                source_revision: str) -> list[str]:
    checks = row.get("checks")
    if (row.get("status") != "pass" or row.get("server_stopped") is not True
            or row.get("config_restored_disabled") is not True
            or row.get("plugin_initialized") is not True
            or row.get("panel_bound_loopback") is not True
            or row.get("source_revision") != source_revision
            or row.get("candidate_sha512") != candidate_sha
            or row.get("candidate_sha512_actual") != candidate_sha
            or row.get("loader") != "purpur" or row.get("version") != pin["version"]
            or row.get("purpur_build") != pin["build"]
            or row.get("publisher_md5") != pin["md5"]
            or row.get("purpur_sha256_expected") != pin["sha256"]
            or row.get("purpur_sha256_actual") != pin["sha256"]
            or row.get("java") != pin["java"]
            or row.get("execution_host") != "local-windows-loopback"
            or row.get("port") != 27246 or row.get("panel_port") != 27247
            or row.get("script_sha256") != ADMIN_SCRIPT_SHA256
            or not INSTANCE_ID.fullmatch(str(row.get("instance_id", "")))
            or not isinstance(checks, list)
            or not all(isinstance(check, str) for check in checks)
            or set(checks) != WEB_CHECKS):
        return ["Purpur admin web HTTP/SQLite smoke is missing or differs from the frozen candidate"]
    return []


def audit(base: Path, candidate_sha: str, probe_sha: str, source_revision: str,
          client_sha256: str) -> dict:
    """Validate all 28 live Purpur modes plus two client and one admin boundary run."""
    pins, issues = _matrix(base)
    runs = base / "runs"
    target_issues = {}
    receipts = {}
    qualified_modes = set()
    for pin in pins:
        version = pin["version"]
        for mode in ("online", "offline"):
            target_id = f"purpur-{version}-{mode}-synthetic"
            found = _latest(runs, "*/" + target_id + ".json", candidate_sha,
                            version=version, mode=mode)
            if found is None:
                target_issues[target_id] = ["No attempt for this frozen JAR"]
                continue
            _, path, row = found
            receipts[target_id] = str(path)
            problems = _synthetic_issues(row, pin, mode, candidate_sha,
                                         probe_sha, source_revision)
            if problems:
                target_issues[target_id] = problems
            else:
                qualified_modes.add((version, mode))
    if target_issues:
        issues.append(f"{len(target_issues)} of 28 pinned Purpur targets lack complete passing evidence")
    qualified = [pin["version"] for pin in pins if all(
        (pin["version"], mode) in qualified_modes for mode in ("online", "offline"))]
    if len(qualified) != len(EXPECTED_VERSIONS):
        issues.append("Not all 14 Purpur versions passed in both direct server modes")

    by_version = {pin["version"]: pin for pin in pins}
    for version in CLIENT_VERSIONS:
        found = _latest(runs, f"*-local-purpur-client-{version}/result.json",
                        candidate_sha, version=version, mode="offline")
        if found is None or version not in by_version:
            issues.append(f"Purpur {version} real offline Minecraft client receipt is missing")
            continue
        _, path, row = found
        receipts["purpur_real_offline_client_" + version] = str(path)
        issues.extend(f"Purpur {version}: {problem}" for problem in _client_issues(
            row, by_version[version], candidate_sha, probe_sha,
            source_revision, client_sha256))
    web = _latest(runs, "*-local-purpur-admin-web/result.json", candidate_sha,
                  version="26.2")
    if web is None or "26.2" not in by_version:
        issues.append("Purpur authenticated admin web HTTP/SQLite receipt is missing")
    else:
        _, path, row = web
        receipts["purpur_admin_web"] = str(path)
        issues.extend(_web_issues(row, by_version["26.2"],
                                  candidate_sha, source_revision))
    return {"issues": issues, "target_issues": target_issues,
            "qualified_versions": qualified,
            "planned_versions": [pin["version"] for pin in pins],
            "matrix_sha256": MATRIX_SHA256, "receipts": receipts}
