"""Verify and list the two already-uploaded ByteBans 1.1.0 versions.

Read-only by default. This never creates a version or changes the original 1.0.0.
The owner confirmed that they created and coded the original project; the
substantial AI-generated 1.1.0 additions and page text remain disclosed.
"""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import subprocess

import release_gate as gate
import set_disclosures


HERE = Path(__file__).resolve().parent
BASELINE = "553c48bcbd7d327cfab7f7fd9e24bae27c4f3277"
INITIAL = "4b118351360e58b33c978e361e40d8298f006ccf"
PINNED_IDS = {
    gate.VERSION_NUMBER: "OwWYT5Gk",
    gate.PAPER_EXCEPTION_NUMBER: "yDNstXxC",
}
EXPECTED_PROVENANCE = (3177, 4830)  # pre-update lines, all Java lines at frozen source revision


def _git(*args: str) -> str:
    result = subprocess.run(["git", *args], cwd=gate.REPO, text=True,
                            encoding="utf-8", capture_output=True, check=False)
    if result.returncode:
        raise ValueError("ByteBans provenance history is unavailable: " + " ".join(args[:2]))
    return result.stdout


def provenance_summary() -> dict:
    """Pin the surviving original source; Git metadata alone is not human proof."""
    ancestors = set(_git("rev-list", BASELINE).splitlines())
    if (INITIAL not in ancestors or BASELINE not in
            set(_git("rev-list", gate.SOURCE_REVISION).splitlines())):
        raise ValueError("Original ByteBans history is not an ancestor of the release source")
    paths = [path for path in _git("ls-tree", "-r", "--name-only", gate.SOURCE_REVISION,
                                   "src/main/java").splitlines() if path.endswith(".java")]
    old = total = 0
    for path in paths:
        for line in _git("blame", "-l", "--line-porcelain", gate.SOURCE_REVISION,
                         "--", path).splitlines():
            match = re.fullmatch(r"([0-9a-f]{40}) \d+ \d+(?: \d+)?", line)
            if match:
                total += 1
                old += match.group(1) in ancestors
    if (old, total) != EXPECTED_PROVENANCE:
        raise ValueError("Frozen ByteBans original-code provenance differs from the reviewed history")
    return {"initial_revision": INITIAL, "pre_update_revision": BASELINE,
            "frozen_source_revision": gate.SOURCE_REVISION,
            "pre_update_java_lines": old, "total_java_lines": total}


class Client(gate.ModrinthClient):
    def set_listed(self, version_id: str) -> None:
        """PATCH only a pinned version's status; readback happens separately."""
        if version_id not in PINNED_IDS.values():
            raise ValueError("Unexpected ByteBans version ID for public promotion")
        import requests
        response = requests.patch(
            "https://api.modrinth.com/v2/version/" + version_id,
            headers={"Authorization": self.token, "User-Agent": self.ws.AGENT},
            json={"status": "listed"}, timeout=30)
        if not response.ok:
            raise RuntimeError(f"Modrinth ByteBans status update failed (HTTP {response.status_code})")


def _verify_version(version: dict, payload: dict, jar: Path, status: str) -> None:
    if status not in {"listed", "unlisted"}:
        raise ValueError("Unexpected ByteBans version status")
    if not gate._verify_new_version(version, {**payload, "status": status}, jar):
        raise ValueError("ByteBans version hash, loaders, Minecraft versions or changelog differ")


def _preflight(base: Path, client, owner_confirmed: bool) -> dict:
    if not owner_confirmed:
        raise ValueError("Owner confirmation of the original ByteBans code is required")
    plan = gate.audit(base=base)
    if not plan["ready"]:
        raise ValueError("Frozen ByteBans release evidence failed: " + "; ".join(plan["issues"]))
    if (plan["candidate_sha512"] != gate.CANDIDATE_SHA512
            or plan["companion_sha512"] != gate.COMPANION_SHA512
            or plan["source_revision"] != gate.SOURCE_REVISION):
        raise ValueError("ByteBans release plan does not match the frozen source and JARs")
    provenance = provenance_summary()
    publication_path = base / "runs/publication.json"
    publication = gate.safe_json(publication_path)
    expected_hashes = {gate.VERSION_NUMBER: gate.CANDIDATE_SHA512,
                       gate.PAPER_EXCEPTION_NUMBER: gate.COMPANION_SHA512}
    payloads = {row["version_number"]: row for row in plan["payloads"]}
    receipt_versions = publication.get("versions")
    receipt_by_number = {row.get("version_number"): row for row in receipt_versions
                         if isinstance(row, dict)} if isinstance(receipt_versions, list) else {}
    if (publication.get("project_id") != gate.PROJECT_ID
            or publication.get("source_revision") != gate.SOURCE_REVISION
            or publication.get("candidate_sha512") != gate.CANDIDATE_SHA512
            or publication.get("companion_sha512") != gate.COMPANION_SHA512
            or publication.get("status") != "unlisted"
            or publication.get("changelog_sha256") != plan["changelog_sha256"]
            or publication.get("evidence_sha256") != plan["evidence_sha256"]
            or len(receipt_versions) != len(PINNED_IDS)
            or len(receipt_by_number) != len(PINNED_IDS)
            or set(payloads) != set(PINNED_IDS)):
        raise ValueError("ByteBans publication receipt does not match the tested release")
    jars = {gate.VERSION_NUMBER: Path(plan["candidate_file"]),
            gate.PAPER_EXCEPTION_NUMBER: Path(plan["companion_file"])}
    for number, version_id in PINNED_IDS.items():
        row = receipt_by_number[number]
        payload = payloads[number]
        jar = jars[number]
        if (jar.is_symlink() or not jar.is_file()
                or gate.digest(jar) != expected_hashes[number]
                or row.get("version_id") != version_id
                or row.get("name") != payload["name"]
                or row.get("loaders") != payload["loaders"]
                or row.get("game_versions") != payload["game_versions"]
                or row.get("primary_sha512") != expected_hashes[number]):
            raise ValueError("ByteBans published version or local JAR differs from its frozen receipt")
    project = client.get_project()
    if project.get("id") != gate.PROJECT_ID or project.get("status") != "approved":
        raise ValueError("ByteBans Modrinth project is not the approved original")
    page = (base / "modrinth-project-body.md").read_text(encoding="utf-8")
    if project.get("body") != page:
        raise ValueError("ByteBans project page differs from the reviewed page")
    disclosures = client.get_disclosures()
    for desired in set_disclosures.DESIRED:
        actual = next((row for row in disclosures if row.get("type") == desired["type"]), None)
        if not set_disclosures.matches(actual, desired):
            raise ValueError("ByteBans truthful AI/telemetry disclosures are missing or outdated")
    old_signature = gate._prior_signature(client.get_version(gate.PRIOR_VERSION_ID))
    if old_signature != publication.get("prior_version"):
        raise ValueError("Original ByteBans 1.0.0 release differs from its publication receipt")
    versions = {}
    for number, version_id in PINNED_IDS.items():
        version = client.get_version(version_id)
        status = version.get("status")
        _verify_version(version, payloads[number], jars[number], status)
        found = client.find_version_by_hash(expected_hashes[number])
        if not isinstance(found, dict) or found.get("id") != version_id:
            raise ValueError("ByteBans uploaded JAR hash resolves to a different version")
        _verify_version(found, payloads[number], jars[number], status)
        versions[number] = version
    for row in client.list_versions():
        number = row.get("version_number")
        file_hashes = [(item.get("hashes") or {}).get("sha512") for item in row.get("files") or []
                       if isinstance(item, dict)]
        if ((number in PINNED_IDS or any(sha in expected_hashes.values() for sha in file_hashes))
                and row.get("id") != PINNED_IDS.get(number)):
            raise ValueError("A conflicting ByteBans version has the same number or JAR hash")
    receipt_path = base / "runs/public-promotion.json"
    if receipt_path.parent.is_symlink() or receipt_path.is_symlink():
        raise ValueError("ByteBans public-promotion receipt path is a symbolic link")
    if receipt_path.exists():
        previous = gate.safe_json(receipt_path)
        if (previous.get("project_id") != gate.PROJECT_ID
                or previous.get("version_ids") != PINNED_IDS
                or previous.get("candidate_sha512") != gate.CANDIDATE_SHA512
                or previous.get("companion_sha512") != gate.COMPANION_SHA512
                or previous.get("publication_receipt_sha256") != gate.digest(publication_path, "sha256")
                or any(row.get("status") != "listed" for row in versions.values())):
            raise ValueError("Existing ByteBans public-promotion receipt conflicts with Modrinth")
    return {"plan": plan, "publication": publication,
            "publication_path": publication_path, "jars": jars,
            "payloads": payloads, "versions": versions,
            "prior_signature": old_signature, "provenance": provenance,
            "receipt_path": receipt_path}


def promote(*, apply: bool, owner_confirmed: bool, base: Path = HERE, client=None) -> dict:
    base = Path(base)
    client = client or Client()
    state = _preflight(base, client, owner_confirmed)
    current = {number: row["status"] for number, row in state["versions"].items()}
    if not apply:
        return {"ready": True, "versions": current,
                "actions": [number for number, status in current.items() if status != "listed"],
                "provenance": state["provenance"]}
    for number, version_id in PINNED_IDS.items():
        if current[number] == "unlisted":
            client.set_listed(version_id)
        remote = client.get_version(version_id)
        _verify_version(remote, state["payloads"][number], state["jars"][number], "listed")
        found = client.find_version_by_hash(
            gate.CANDIDATE_SHA512 if number == gate.VERSION_NUMBER else gate.COMPANION_SHA512)
        if not isinstance(found, dict) or found.get("id") != version_id:
            raise RuntimeError("Listed ByteBans version is not discoverable by its exact JAR hash")
        _verify_version(found, state["payloads"][number], state["jars"][number], "listed")
    listed = {row.get("id") for row in client.list_versions()}
    if not set(PINNED_IDS.values()).issubset(listed):
        raise RuntimeError("ByteBans public versions are absent from the listed project versions")
    if gate._prior_signature(client.get_version(gate.PRIOR_VERSION_ID)) != state["prior_signature"]:
        raise RuntimeError("Original ByteBans 1.0.0 release changed during promotion")
    project = client.get_project()
    if (project.get("id") != gate.PROJECT_ID or project.get("status") != "approved"
            or project.get("body") != (base / "modrinth-project-body.md").read_text(encoding="utf-8")):
        raise RuntimeError("ByteBans project identity or page changed during promotion")
    for desired in set_disclosures.DESIRED:
        actual = next((row for row in client.get_disclosures()
                       if row.get("type") == desired["type"]), None)
        if not set_disclosures.matches(actual, desired):
            raise RuntimeError("ByteBans disclosures changed during promotion")
    receipt = {"project_id": gate.PROJECT_ID, "status": "listed",
               "version_ids": PINNED_IDS, "candidate_sha512": gate.CANDIDATE_SHA512,
               "companion_sha512": gate.COMPANION_SHA512,
               "publication_receipt_sha256": gate.digest(state["publication_path"], "sha256"),
               "owner_original_code_confirmed": True,
               "provenance": state["provenance"],
               "ai_disclosure": set_disclosures.DESIRED[0],
               "verified_at": datetime.now(timezone.utc).isoformat()}
    path = state["receipt_path"]
    if path.exists():
        return gate.safe_json(path)
    gate._atomic_json(path, receipt)
    return receipt


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="List the two verified existing versions")
    parser.add_argument("--owner-confirmed-original-code", action="store_true",
                        help="Owner confirms they wrote the original ByteBans plugin")
    args = parser.parse_args()
    try:
        result = promote(apply=args.apply,
                         owner_confirmed=args.owner_confirmed_original_code)
    except (RuntimeError, ValueError) as error:
        parser.exit(1, "BLOCKED: " + str(error) + "\n")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
