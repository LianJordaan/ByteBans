"""Evidence-gated ByteBans 1.1.0 Modrinth release; dry-run by default.

Only the frozen ByteBans JAR may be uploaded. This tool never edits or removes
the existing 1.0.0 version. Without a human public-eligibility review, the
substantial AI-assisted update is uploaded unlisted with verified disclosures.
"""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import sys

import summarize
import purpur_release_evidence


HERE = Path(__file__).resolve().parent
REPO = HERE.parent
WORKSPACE = REPO.parents[1]
PROJECT_ID = "ysEBBeJv"
PRIOR_VERSION_ID = "9oClMk0K"
PRIOR_VERSION_SHA512 = (
    "7fdb3ae585ad0c3576638b73647f2615195901af361f6d14422aaac89309110ecd79e8460308aecc26dde745c006e3e3d0a0be440da1e70626496e16048d5313"
)
PRIOR_GAME_VERSIONS = (
    "1.21", "1.21.1", "1.21.2", "1.21.3", "1.21.4", "1.21.5",
    "1.21.6", "1.21.7", "1.21.8", "1.21.9", "1.21.10", "1.21.11",
)
PRIOR_LOADERS = ("bukkit", "paper", "purpur", "spigot")
VERSION_NUMBER = "1.1.0"
VERSION_NAME = "ByteBans 1.1.0 beta"
PAPER_EXCEPTION_NUMBER = "1.1.0+paper.26.1.1"
PAPER_EXCEPTION_NAME = "ByteBans 1.1.0 beta (Paper 26.1.1)"
SOURCE_REVISION = "70d3daff4b0af93338d95b207ee596c5e52a43fc"
CANDIDATE_SHA512 = (
    "300a632568e37a22bd8d16ef311625f8bc53db9ce87bab4f2571400952b061df5da3e9aabb56142e62360874c0c2d15696b8afb755054608f8e373106eac15a8"
)
PROBE_SHA512 = (
    "d6029de86b401b0f350802b1e38a43a025e9b2ae509fe07e9446980f8ed70687978cbfbb95e59353770cd7a984392f1b716dca0b061fd3d3725afeafb6b39a26"
)
MATRIX_SHA256 = "350ecab8c78baaf7f9b7ec643e48d62c9f3a8f7b18b2f166e1c3bb5f619bb8b2"
PAPER_1214_SHA256 = "5ee4f542f628a14c644410b08c94ea42e772ef4d29fe92973636b6813d4eaffc"
CLIENT_VERSIONS = ("1.21", "1.21.4", "1.21.11")
CLIENT_SCRIPT_SHA256 = "b794fb13e5d43fdc565cf6db0ccd4503a54f2ecfdd522260fe600d24eb08dbef"
VERSION_ID = re.compile(r"[A-Za-z0-9]{8,16}\Z")
INSTANCE_ID = re.compile(r"mw-[a-f0-9]{16}\Z")
HEX64 = re.compile(r"[a-f0-9]{64}\Z")
EXPECTED_WEB_CHECKS = frozenset({
    "loopback_login_page", "host_restriction", "wrong_token_denied",
    "authenticated_login", "csrf_rejection_no_write",
    "note_create_with_actor", "note_undo_with_audit",
})
EXPECTED_CLIENT_CHECKS = frozenset({
    "real_offline_join", "ip_ban_kicks_existing_client",
    "ip_ban_rejects_shared_address", "ip_unban_restores_join",
    "ip_mute_blocks_shared_address_chat", "freeze_holds_real_client",
    "unfreeze_restores_real_movement",
})


def digest(path: Path, algorithm="sha512") -> str:
    hasher = hashlib.new(algorithm)
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            hasher.update(block)
    return hasher.hexdigest()


def safe_json(path: Path):
    if path.is_symlink() or not path.is_file():
        raise ValueError("Missing or symbolic-link release evidence: " + str(path))
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError("Release evidence is not an object: " + str(path))
    return value


def _latest_receipt(folder: Path, pattern: str, candidate_sha: str):
    matches = []
    for path in folder.glob(pattern):
        try:
            row = safe_json(path)
        except (OSError, ValueError):
            continue
        if row.get("candidate_sha512") == candidate_sha:
            timestamp = row.get("finished_at") or row.get("recorded_at") or ""
            matches.append((timestamp, path, row))
    return max(matches, key=lambda item: item[0]) if matches else None


def _client_issues(row: dict, target: dict) -> list[str]:
    issues = []
    if (row.get("status") != "pass" or row.get("stopped") is not True
            or row.get("source_revision") != SOURCE_REVISION
            or row.get("candidate_sha512") != CANDIDATE_SHA512
            or row.get("probe_sha512") != PROBE_SHA512
            or row.get("client_sha256") != CLIENT_SCRIPT_SHA256
            or row.get("version") != target["version"]
            or row.get("paper_build") != target["paper_build"]
            or row.get("paper_sha256_actual") != target["paper_sha256"]
            or row.get("java") != target["java"]
            or row.get("connection_mode") != "standalone_offline"
            or row.get("port") != 27242
            or not INSTANCE_ID.fullmatch(str(row.get("instance_id", "")))):
        issues.append("Real offline-client run does not match the frozen candidate and Paper pin")
    checks = row.get("checks") or {}
    if not isinstance(checks, dict) or not EXPECTED_CLIENT_CHECKS.issubset(checks):
        issues.append("Real offline-client checklist is incomplete")
    elif (not all(isinstance(checks[name], dict) for name in (
              "ip_ban_kicks_existing_client", "ip_ban_rejects_shared_address",
              "ip_mute_blocks_shared_address_chat"))
          or checks["real_offline_join"] is not True
          or checks["ip_unban_restores_join"] is not True
          or any(checks[name].get("event") != "kicked" for name in (
              "ip_ban_kicks_existing_client", "ip_ban_rejects_shared_address"))
          or checks["ip_mute_blocks_shared_address_chat"].get("event") != "message"):
        issues.append("Real offline-client login, IP ban, or IP mute did not pass")
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


def _web_issues(row: dict) -> list[str]:
    checks = row.get("checks")
    if (row.get("status") != "pass" or row.get("server_stopped") is not True
            or row.get("config_restored_disabled") is not True
            or row.get("plugin_initialized") is not True
            or row.get("panel_bound_loopback") is not True
            or row.get("source_revision") != SOURCE_REVISION
            or row.get("candidate_sha512") != CANDIDATE_SHA512
            or row.get("version") != "1.21.4" or row.get("paper_build") != 232
            or row.get("paper_sha256") != PAPER_1214_SHA256 or row.get("java") != 21
            or not INSTANCE_ID.fullmatch(str(row.get("instance_id", "")))
            or not isinstance(checks, list)
            or not all(isinstance(check, str) for check in checks)
            or set(checks) != EXPECTED_WEB_CHECKS
            or not HEX64.fullmatch(str(row.get("script_sha256", "")))):
        return ["Admin web HTTP/SQLite smoke is missing or differs from the frozen candidate"]
    return []


def _ai_review_issues(row: dict, candidate_sha: str) -> list[str]:
    """Require a truthful human choice of listed or explicitly authorized unlisted."""
    if (row.get("candidate_sha512") != candidate_sha
            or row.get("project_id") != PROJECT_ID
            or row.get("substantial_ai_generated_code") is not True
            or row.get("ai_written_release_text") is not True
            or row.get("modrinth_ai_code_disclosure_verified") is not True
            or row.get("modrinth_ai_text_disclosure_verified") is not True
            or row.get("opt_in_discord_data_disclosure_verified") is not True
            or not isinstance(row.get("reviewer"), str) or len(row["reviewer"].strip()) < 3
            or not isinstance(row.get("review_basis"), str) or len(row["review_basis"].strip()) < 80):
        return ["Human AI eligibility/disclosure review is incomplete or contradicts known provenance"]
    visibility = row.get("requested_version_status")
    if visibility == "listed":
        if (row.get("primarily_ai_derived") is not False
                or row.get("significant_human_original_content_confirmed") is not True):
            return ["Public listing requires a human finding of significant original work and a project that is not primarily AI-derived"]
    elif visibility == "unlisted":
        if (row.get("owner_authorized_unlisted") is not True
                or type(row.get("primarily_ai_derived")) is not bool):
            return ["An unlisted version requires the owner's explicit choice and an honest AI derivation assessment"]
    else:
        return ["Human review must choose listed or unlisted version visibility"]
    try:
        reviewed = datetime.fromisoformat(row["reviewed_at"].replace("Z", "+00:00"))
    except (KeyError, AttributeError, ValueError):
        return ["Human AI eligibility review has no valid timestamp"]
    if reviewed.tzinfo is None or reviewed > datetime.now(timezone.utc):
        return ["Human AI eligibility review timestamp is invalid"]
    return []


def _synthetic_issues(row: dict, target: dict) -> list[str]:
    issues = []
    if summarize.verified_status(row, target, CANDIDATE_SHA512, PROBE_SHA512) != "pass":
        issues.append("Latest matching attempt lacks complete passing event/hash/startup evidence")
    if (row.get("source_revision") != SOURCE_REVISION
            or row.get("candidate_sha512") != CANDIDATE_SHA512
            or row.get("probe_sha512") != PROBE_SHA512
            or row.get("target") != target
            or not INSTANCE_ID.fullmatch(str(row.get("instance_id", "")))
            or not isinstance(row.get("port"), int) or not 27200 <= row["port"] <= 27299
            or not row.get("started_at") or not row.get("finished_at")):
        issues.append("Attempt identity, source revision or allocated server is not pinned")
    probe = row.get("probe") or {}
    if (not isinstance(probe, dict) or probe.get("probe_revision") != "1"
            or probe.get("bytebans_version") != "1.1.0"
            or target["version"] not in str(probe.get("server", ""))):
        issues.append("Live probe revision or plugin runtime identity is missing")
    logs = row.get("logs")
    if (not isinstance(logs, str)
            or "[ByteBans] ByteBans was successfully initialized." not in logs
            or "Error occurred while enabling ByteBans" in logs
            or re.search(r"Could not pass event .+ to ByteBans", logs)
            or re.search(r"\[ByteBans\].*\b(?:ERROR|SEVERE)\b", logs)):
        issues.append("Saved server diagnostic is missing or contains ByteBans errors")
    return issues


def _matching_matrix_receipt(folder: Path, target: dict):
    matches = []
    for path in folder.glob("*/" + target["id"] + ".json"):
        try:
            row = safe_json(path)
        except (OSError, ValueError):
            continue
        if (row.get("target") == target
                and row.get("candidate_sha512") == CANDIDATE_SHA512
                and row.get("probe_sha512") == PROBE_SHA512):
            matches.append((row.get("finished_at", ""), path, row))
    return max(matches, key=lambda item: item[0]) if matches else None


def audit(*, base: Path | None = None, ai_review_path: Path | None = None,
          changelog_path: Path | None = None) -> dict:
    """Return a complete read-only plan. Missing or failed targets block release."""
    base = Path(base or HERE)
    runs = base / "runs"
    ai_review_path = Path(ai_review_path or runs / "ai-review.json")
    changelog_path = Path(changelog_path or base / "release-changelog.md")
    candidate = base / "frozen/release/ByteBans-1.1.0.jar"
    helper = base / "frozen/release/bytebans-private-probe-1.0.0.jar"
    manifest_path = base / "frozen/release/manifest.json"
    matrix_path = base / "matrix.json"
    issues: list[str] = []
    target_issues: dict[str, list[str]] = {}
    receipt_paths: dict[str, str] = {}
    for label, path, expected, algorithm in (
            ("Frozen ByteBans JAR", candidate, CANDIDATE_SHA512, "sha512"),
            ("Private probe JAR", helper, PROBE_SHA512, "sha512"),
            ("Pinned matrix", matrix_path, MATRIX_SHA256, "sha256")):
        if path.is_symlink() or not path.is_file() or digest(path, algorithm) != expected:
            issues.append(label + " is missing or differs from its frozen hash")
    try:
        manifest = safe_json(manifest_path)
    except (OSError, ValueError) as error:
        manifest = {}
        issues.append("Frozen candidate manifest is missing or unreadable: " + str(error))
    if (manifest.get("sha512") != CANDIDATE_SHA512
            or manifest.get("source_revision") != SOURCE_REVISION):
        issues.append("Frozen candidate manifest does not identify this source and JAR")
    try:
        matrix = safe_json(matrix_path)
        targets = matrix.get("targets")
        if matrix.get("revision") != 2 or not isinstance(targets, list) or len(targets) != 30:
            raise ValueError("Expected revision 2 and exactly 30 pinned targets")
        keys = [(t.get("version"), t.get("connection_mode")) for t in targets]
        versions = list(dict.fromkeys(t["version"] for t in targets))
        if (len(versions) != 15 or len(set(keys)) != 30 or any(
                t.get("id") != f'paper-{t.get("version")}-'
                    + ("online" if t.get("connection_mode") == "standalone_online" else "offline")
                    + "-synthetic"
                or t.get("connection_mode") not in ("standalone_online", "standalone_offline")
                or t.get("java") not in (21, 25) or not isinstance(t.get("paper_build"), int)
                or not HEX64.fullmatch(str(t.get("paper_sha256", ""))) for t in targets)
                or any({mode for version, mode in keys if version == v}
                       != {"standalone_online", "standalone_offline"} for v in versions)):
            raise ValueError("Pinned Paper target matrix is incomplete or inconsistent")
    except (OSError, ValueError, KeyError, TypeError) as error:
        targets, versions = [], []
        issues.append("Pinned 30-target matrix is invalid: " + str(error))

    qualified_modes = set()
    for target in targets:
        found = _matching_matrix_receipt(runs, target)
        if found is None:
            target_issues[target["id"]] = ["No attempt for this frozen JAR and helper"]
            continue
        _, path, row = found
        receipt_paths[target["id"]] = str(path)
        problems = _synthetic_issues(row, target)
        if problems:
            target_issues[target["id"]] = problems
        else:
            qualified_modes.add((target["version"], target["connection_mode"]))
    if target_issues:
        issues.append(f"{len(target_issues)} of 30 pinned Paper targets lack complete passing evidence")
    qualified_versions = [version for version in versions if all(
        (version, mode) in qualified_modes for mode in ("standalone_online", "standalone_offline"))]
    if len(qualified_versions) != 15:
        issues.append("Not all 15 Paper versions passed in both direct server modes")

    for version in CLIENT_VERSIONS:
        target = next((item for item in targets if item["version"] == version
                       and item["connection_mode"] == "standalone_offline"), None)
        client = _latest_receipt(runs, f"*-local-client-{version}/result.json", CANDIDATE_SHA512)
        if target is None or client is None:
            issues.append(f"Paper {version} real offline Minecraft client receipt is missing")
        else:
            _, path, row = client
            receipt_paths["real_offline_client_" + version] = str(path)
            issues.extend(f"Paper {version}: {problem}" for problem in _client_issues(row, target))
    web = _latest_receipt(runs, "*-local-admin-web/result.json", CANDIDATE_SHA512)
    if web is None:
        issues.append("Authenticated admin web HTTP/SQLite receipt is missing")
    else:
        _, path, row = web
        receipt_paths["admin_web"] = str(path)
        issues.extend(_web_issues(row))

    purpur = purpur_release_evidence.audit(
        base, CANDIDATE_SHA512, PROBE_SHA512,
        SOURCE_REVISION, CLIENT_SCRIPT_SHA256,
    )
    issues.extend(purpur["issues"])
    target_issues.update(purpur["target_issues"])
    receipt_paths.update(purpur["receipts"])
    purpur_versions = purpur["qualified_versions"]
    if set(purpur_versions) != set(qualified_versions) - {"26.1.1"}:
        issues.append("Paper/Purpur overlap is not the exact 14-version shared set")

    ai_review_sha256 = None
    reviewed_status = "unlisted"
    if ai_review_path.exists() or ai_review_path.is_symlink():
        try:
            review = safe_json(ai_review_path)
            ai_review_sha256 = digest(ai_review_path, "sha256")
            review_issues = _ai_review_issues(review, CANDIDATE_SHA512)
            issues.extend(review_issues)
            if not review_issues:
                reviewed_status = review["requested_version_status"]
            else:
                reviewed_status = None
        except (OSError, ValueError):
            issues.append("Human AI eligibility review exists but is invalid")
            reviewed_status = None
    changelog = ""
    if not changelog_path.is_symlink() and changelog_path.is_file():
        changelog = changelog_path.read_text(encoding="utf-8").strip()
    if (len(changelog) < 80 or "AI-generated" not in changelog
            or "Paper" not in changelog or "Purpur" not in changelog
            or "26.1.1" not in changelog):
        issues.append("Release changelog is missing or lacks compatibility and AI provenance disclosure")

    payloads = []
    if (len(qualified_versions) == 15 and len(purpur_versions) == 14
            and not issues and changelog and reviewed_status):
        common = {"project_id": PROJECT_ID, "changelog": changelog,
                  "version_type": "beta", "featured": False,
                  "status": reviewed_status, "environment": "dedicated_server_only",
                  "dependencies": [], "file_parts": ["primary"], "primary_file": "primary"}
        payloads = [
            {**common, "name": VERSION_NAME, "version_number": VERSION_NUMBER,
             "game_versions": purpur_versions, "loaders": ["paper", "purpur"]},
            {**common, "name": PAPER_EXCEPTION_NAME,
             "version_number": PAPER_EXCEPTION_NUMBER,
             "game_versions": ["26.1.1"], "loaders": ["paper"]},
        ]
    evidence_hashes = {}
    for name, name_path in receipt_paths.items():
        path = Path(name_path)
        if path.is_symlink() or not path.is_file():
            issues.append("Release evidence disappeared or became a symbolic link: " + name)
        else:
            evidence_hashes[name] = digest(path, "sha256")
    return {"ready": not issues and len(payloads) == 2, "issues": issues,
            "target_issues": target_issues, "qualified_versions": qualified_versions,
            "purpur_qualified_versions": purpur_versions,
            "planned_versions": versions, "candidate_file": str(candidate),
            "candidate_sha512": CANDIDATE_SHA512, "source_revision": SOURCE_REVISION,
            "probe_sha512": PROBE_SHA512, "matrix_sha256": MATRIX_SHA256,
            "purpur_matrix_sha256": purpur["matrix_sha256"],
            "ai_review_sha256": ai_review_sha256,
            "changelog_sha256": hashlib.sha256(changelog.encode("utf-8")).hexdigest() if changelog else None,
            "receipts": receipt_paths, "evidence_sha256": evidence_hashes,
            "payloads": payloads}


class ModrinthClient:
    """Only this client can create the dedicated ByteBans version."""

    def __init__(self, token: str | None = None):
        sys.path.insert(0, str(WORKSPACE))
        from dashboard import workspace as ws
        self.ws = ws
        self.token = token or ws.load_token()
        if not self.token:
            raise ValueError("MODRINTH_TOKEN is missing")

    def get_project(self):
        from dashboard.services.modrinth import get_json
        return get_json(self.ws.API + "/project/" + PROJECT_ID, self.token)

    def get_disclosures(self):
        import requests
        response = requests.get(
            "https://api.modrinth.com/v3/project/" + PROJECT_ID + "/disclosures",
            headers={"Authorization": self.token, "User-Agent": self.ws.AGENT},
            timeout=30,
        )
        if not response.ok:
            raise RuntimeError(f"Modrinth ByteBans disclosure read failed (HTTP {response.status_code})")
        return response.json().get("disclosures", [])

    def list_versions(self):
        from dashboard.services.modrinth import get_json
        return get_json(self.ws.API + "/project/" + PROJECT_ID + "/version", self.token)

    def get_version(self, version_id: str):
        if not VERSION_ID.fullmatch(version_id):
            raise ValueError("Unexpected Modrinth version ID")
        from dashboard.services.modrinth import get_json
        return get_json(self.ws.API + "/version/" + version_id, self.token)

    def create_version(self, payload: dict, jar: Path) -> str:
        import requests
        content = jar.read_bytes()
        if hashlib.sha512(content).hexdigest() != CANDIDATE_SHA512:
            raise ValueError("ByteBans JAR changed before Modrinth upload")
        response = requests.post(
            self.ws.API + "/version",
            headers={"Authorization": self.token, "User-Agent": self.ws.AGENT},
            files={"data": (None, json.dumps(payload), "application/json"),
                   "primary": ("ByteBans-1.1.0.jar", content, "application/java-archive")},
            timeout=120,
        )
        if not response.ok:
            raise RuntimeError(f"Modrinth ByteBans upload failed (HTTP {response.status_code})")
        version_id = response.json().get("id")
        if not isinstance(version_id, str) or not VERSION_ID.fullmatch(version_id):
            raise RuntimeError("Modrinth returned an invalid ByteBans version ID")
        return version_id


def _primary(version: dict):
    files = version.get("files")
    if not isinstance(files, list) or len(files) != 1:
        return None
    item = files[0]
    return item if isinstance(item, dict) and item.get("primary") is True else None


def _prior_signature(version: dict):
    file = _primary(version)
    if (version.get("id") != PRIOR_VERSION_ID or version.get("project_id") != PROJECT_ID
            or version.get("version_number") != "1.0.0" or file is None
            or (file.get("hashes") or {}).get("sha512") != PRIOR_VERSION_SHA512
            or version.get("status") != "listed"
            or version.get("environment") != "unknown"
            or sorted(version.get("loaders") or []) != sorted(PRIOR_LOADERS)
            or sorted(version.get("game_versions") or []) != sorted(PRIOR_GAME_VERSIONS)):
        raise ValueError("Existing ByteBans 1.0.0 release differs from its pinned identity")
    return {"id": version["id"], "project_id": version["project_id"],
            "version_number": version["version_number"], "status": version.get("status"),
            "loaders": sorted(version.get("loaders") or []),
            "game_versions": sorted(version.get("game_versions") or []),
            "primary_sha512": file["hashes"]["sha512"]}


def _verify_new_version(version: dict, payload: dict, jar: Path) -> bool:
    file = _primary(version)
    hashes = file.get("hashes") if file else None
    versions = version.get("game_versions")
    loaders = version.get("loaders")
    return bool(
        isinstance(version.get("id"), str) and VERSION_ID.fullmatch(version["id"])
        and version.get("project_id") == PROJECT_ID
        and version.get("version_number") == payload["version_number"]
        and version.get("name") == payload["name"]
        and version.get("version_type") == payload["version_type"]
        and version.get("status") == payload["status"]
        and version.get("environment") == payload["environment"]
        and version.get("changelog") == payload["changelog"]
        and isinstance(versions, list) and len(versions) == len(payload["game_versions"])
        and set(versions) == set(payload["game_versions"])
        and isinstance(loaders, list) and len(loaders) == len(payload["loaders"])
        and set(loaders) == set(payload["loaders"])
        and isinstance(hashes, dict)
        and hashes.get("sha512") == CANDIDATE_SHA512
        and hashes.get("sha1") == digest(jar, "sha1")
    )


def _atomic_json(path: Path, value: dict):
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.is_symlink():
        raise ValueError("Publication receipt cannot be a symbolic link")
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    temporary.replace(path)


def publish(*, user_requested_upload=False, base: Path | None = None,
            ai_review_path: Path | None = None, changelog_path: Path | None = None,
            client=None) -> dict:
    """Create or verify two disjoint metadata rectangles for one frozen JAR."""
    if not user_requested_upload:
        raise ValueError("ByteBans publication requires the user's later upload instruction")
    base = Path(base or HERE)
    plan = audit(base=base, ai_review_path=ai_review_path, changelog_path=changelog_path)
    if not plan["ready"]:
        raise ValueError("ByteBans release gate is closed: " + "; ".join(plan["issues"]))
    review_path = Path(ai_review_path or Path(base) / "runs/ai-review.json")
    if plan["ai_review_sha256"] is not None and (
            review_path.is_symlink() or not review_path.is_file()
            or digest(review_path, "sha256") != plan["ai_review_sha256"]):
        raise ValueError("Human AI review changed after release audit")
    jar = Path(plan["candidate_file"])
    if jar.is_symlink() or digest(jar) != CANDIDATE_SHA512:
        raise ValueError("Frozen ByteBans JAR changed after release audit")
    for name, name_path in plan["receipts"].items():
        path = Path(name_path)
        if (path.is_symlink() or not path.is_file()
                or digest(path, "sha256") != plan["evidence_sha256"].get(name)):
            raise ValueError("Release evidence changed after audit: " + name)
    changelog_file = Path(changelog_path or base / "release-changelog.md")
    if (changelog_file.is_symlink() or not changelog_file.is_file()
            or hashlib.sha256(changelog_file.read_text(encoding="utf-8").strip().encode("utf-8")).hexdigest()
                != plan["changelog_sha256"]):
        raise ValueError("Reviewed ByteBans release changelog changed after audit")
    client = client or ModrinthClient()
    project = client.get_project()
    if project.get("id") != PROJECT_ID or project.get("status") != "approved":
        raise ValueError("Modrinth ByteBans project identity or approval changed")
    page = (HERE / "modrinth-project-body.md").read_text(encoding="utf-8")
    if project.get("body") != page:
        raise ValueError("Update and verify the accurate ByteBans project page before upload")
    disclosures = client.get_disclosures()
    ai = next((item for item in disclosures if item.get("type") == "ai_content"), None)
    telemetry = next((item for item in disclosures if item.get("type") == "telemetry"), None)
    if not ai or not {"code", "text"}.issubset(set(ai.get("uses") or [])):
        raise ValueError("Modrinth ByteBans AI code/text disclosure is missing")
    if (not telemetry or telemetry.get("consent") != "opt_in"
            or not telemetry.get("data_collected")):
        raise ValueError("Modrinth ByteBans opt-in Discord data disclosure is missing")
    existing = client.list_versions()
    if not isinstance(existing, list) or not any(v.get("id") == PRIOR_VERSION_ID for v in existing):
        raise ValueError("Existing ByteBans 1.0.0 release is missing")
    prior_before = _prior_signature(client.get_version(PRIOR_VERSION_ID))
    payloads = plan["payloads"]
    expected = {payload["version_number"]: payload for payload in payloads}
    if len(expected) != 2:
        raise ValueError("ByteBans split version numbers are not unique")
    matching: dict[str, dict] = {}
    for version in existing:
        number = version.get("version_number")
        same_artifact = any(
                isinstance(item, dict)
                and (item.get("hashes") or {}).get("sha512") == CANDIDATE_SHA512
                for item in version.get("files") or [])
        if number not in expected and not same_artifact:
            continue
        if number not in expected or number in matching:
            raise ValueError("Unexpected or duplicate ByteBans version conflicts with the frozen candidate")
        full = client.get_version(version["id"])
        if not _verify_new_version(full, expected[number], jar):
            raise ValueError("A ByteBans version or artifact already exists with different metadata")
        matching[number] = full
    receipt_path = Path(base) / "runs/publication.json"
    if receipt_path.is_symlink() or receipt_path.parent.is_symlink():
        raise ValueError("Publication receipt path cannot be a symbolic link")
    previous = None
    if receipt_path.exists():
        previous = safe_json(receipt_path)
        prior_versions = previous.get("versions")
        if (not isinstance(prior_versions, list) or len(prior_versions) != 2
                or any(not isinstance(item, dict)
                       or not isinstance(item.get("version_number"), str)
                       for item in prior_versions)
                or {item["version_number"] for item in prior_versions} != set(expected)
                or set(matching) != set(expected)
                or any(item.get("version_id") != matching.get(item["version_number"], {}).get("id")
                       or item.get("name") != expected[item["version_number"]]["name"]
                       or item.get("loaders") != expected.get(item.get("version_number"), {}).get("loaders")
                       or item.get("game_versions") != expected.get(item.get("version_number"), {}).get("game_versions")
                       or item.get("primary_sha512") != CANDIDATE_SHA512
                       for item in prior_versions)
                or previous.get("candidate_sha512") != CANDIDATE_SHA512
                or previous.get("probe_sha512") != PROBE_SHA512
                or previous.get("project_id") != PROJECT_ID
                or previous.get("source_revision") != SOURCE_REVISION
                or previous.get("matrix_sha256") != MATRIX_SHA256
                or previous.get("purpur_matrix_sha256") != purpur_release_evidence.MATRIX_SHA256
                or previous.get("changelog_sha256") != plan["changelog_sha256"]
                or previous.get("ai_review_sha256") != plan["ai_review_sha256"]
                or previous.get("evidence_sha256") != plan["evidence_sha256"]
                or previous.get("prior_version") != prior_before
                or previous.get("status") != payloads[0]["status"]):
            raise ValueError("Existing local ByteBans publication receipt conflicts with Modrinth")
    published_versions = []
    for payload in payloads:
        number = payload["version_number"]
        if number in matching:
            version_id = matching[number]["id"]
            action = "verified_existing"
        else:
            version_id = client.create_version(payload, jar)
            action = "created"
        published = client.get_version(version_id)
        if not _verify_new_version(published, payload, jar):
            raise RuntimeError("ByteBans upload hash, loader, Minecraft versions or metadata verification failed")
        if _prior_signature(client.get_version(PRIOR_VERSION_ID)) != prior_before:
            raise RuntimeError("Existing ByteBans 1.0.0 metadata changed during publication")
        published_versions.append({"version_id": version_id,
                                   "version_number": number, "name": payload["name"],
                                   "loaders": payload["loaders"],
                                   "game_versions": payload["game_versions"],
                                   "primary_sha512": CANDIDATE_SHA512,
                                   "action": action})
    # A retry may discover the first version after a previous upload stopped
    # before the second. Any third version carrying this JAR is a conflict.
    after = client.list_versions()
    if not isinstance(after, list):
        raise RuntimeError("Modrinth ByteBans version list changed unexpectedly")
    expected_ids = {item["version_id"] for item in published_versions}
    seen_ids = set()
    for version in after:
        same_artifact = any(isinstance(item, dict)
                            and (item.get("hashes") or {}).get("sha512") == CANDIDATE_SHA512
                            for item in version.get("files") or [])
        if version.get("version_number") in expected or same_artifact:
            seen_ids.add(version.get("id"))
    if seen_ids != expected_ids:
        raise RuntimeError("Unexpected ByteBans version shares the release number or JAR hash")
    prior_after = _prior_signature(client.get_version(PRIOR_VERSION_ID))
    if prior_after != prior_before:
        raise RuntimeError("Existing ByteBans 1.0.0 metadata changed during publication")
    if plan["ai_review_sha256"] is not None and (
            review_path.is_symlink() or not review_path.is_file()
            or digest(review_path, "sha256") != plan["ai_review_sha256"]):
        raise RuntimeError("Human AI review changed during publication")
    if (changelog_file.is_symlink() or not changelog_file.is_file()
            or hashlib.sha256(changelog_file.read_text(encoding="utf-8").strip().encode("utf-8")).hexdigest()
                != plan["changelog_sha256"]):
        raise RuntimeError("Reviewed ByteBans release changelog changed during publication")
    if previous is not None:
        return previous  # Keep the publication evidence frozen on an idempotent retry.
    receipt_hashes = {}
    for name, name_path in plan["receipts"].items():
        path = Path(name_path)
        if (path.is_symlink() or not path.is_file()
                or digest(path, "sha256") != plan["evidence_sha256"].get(name)):
            raise RuntimeError("Release evidence changed during publication: " + name)
        receipt_hashes[name] = plan["evidence_sha256"][name]
    receipt = {"project_id": PROJECT_ID, "versions": published_versions,
               "source_revision": SOURCE_REVISION,
               "candidate_sha512": CANDIDATE_SHA512, "probe_sha512": PROBE_SHA512,
               "matrix_sha256": MATRIX_SHA256,
               "purpur_matrix_sha256": purpur_release_evidence.MATRIX_SHA256,
               "version_type": "beta", "status": payloads[0]["status"],
               "changelog_sha256": plan["changelog_sha256"],
               "ai_review_sha256": plan["ai_review_sha256"],
               "evidence_sha256": receipt_hashes, "prior_version": prior_after,
               "verified_at": datetime.now(timezone.utc).isoformat()}
    _atomic_json(receipt_path, receipt)
    return receipt


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--dry-run", action="store_true", help="Read local evidence and print blockers (default)")
    mode.add_argument("--upload", action="store_true", help="Create or verify the Modrinth version")
    parser.add_argument("--user-approved-upload", action="store_true",
                        help="Required with --upload after the owner's explicit publication instruction")
    parser.add_argument("--ai-review", type=Path, help="Human AI eligibility/disclosure review JSON")
    parser.add_argument("--changelog", type=Path, help="Reviewed release changelog Markdown")
    args = parser.parse_args(argv)
    if args.upload:
        if not args.user_approved_upload:
            parser.error("--upload requires --user-approved-upload after explicit user authorization")
        receipt = publish(user_requested_upload=True, ai_review_path=args.ai_review,
                          changelog_path=args.changelog)
        print(json.dumps(receipt, indent=2))
        return 0
    plan = audit(ai_review_path=args.ai_review, changelog_path=args.changelog)
    output = HERE / "runs/release-dry-run.json"
    _atomic_json(output, plan)
    print("ByteBans release gate:", "READY" if plan["ready"] else "BLOCKED")
    print("Qualified Paper versions:", ", ".join(plan["qualified_versions"]) or "none")
    print("Qualified Purpur versions:", ", ".join(plan["purpur_qualified_versions"]) or "none")
    for issue in plan["issues"]:
        print("-", issue)
    print("Full dry-run:", output)
    return 0 if plan["ready"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
