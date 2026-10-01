"""Summarize exact-JAR ByteBans Paper probe receipts without contacting the host."""

import argparse
import hashlib
import json
from pathlib import Path


HERE = Path(__file__).resolve().parent
REQUIRED_CASES = {
    "ip_ban_login_and_operator_bypass", "ip_ban_scope_exclusion",
    "ip_mute_chat_and_operator_bypass", "freeze_movement_and_operator_bypass",
}


def digest(path):
    return hashlib.sha512(path.read_bytes()).hexdigest()


def verified_status(receipt, target, candidate_sha, probe_sha):
    status = receipt.get("status", "unknown")
    if status != "pass":
        return status
    probe = receipt.get("probe", {})
    startup = receipt.get("startup_diagnostics", {})
    diagnostics = receipt.get("probe_diagnostics", {})
    if not all(isinstance(value, dict) for value in (probe, startup, diagnostics)):
        return "invalid_pass_receipt"
    valid = (
        receipt.get("stopped") is True
        and receipt.get("candidate_sha512_actual") == candidate_sha
        and receipt.get("probe_sha512_actual") == probe_sha
        and receipt.get("paper_sha256_actual") == target["paper_sha256"]
        and startup.get("initialized") is True
        and startup.get("fatal_lines") == []
        and probe.get("passed") is True
        and probe.get("minecraft") == target["version"]
        and str(probe.get("java", "")).split(".")[0] == str(target["java"])
        and isinstance(probe.get("cases"), dict)
        and all(probe["cases"].get(name) is True for name in REQUIRED_CASES)
        and diagnostics and all(value is True for value in diagnostics.values())
    )
    return "pass" if valid else "invalid_pass_receipt"


def summarize(candidate, helper):
    candidate_sha, probe_sha = digest(candidate), digest(helper)
    matrix = json.loads((HERE / "matrix.json").read_text(encoding="utf-8"))
    entries = []
    for target in matrix["targets"]:
        receipts = []
        for path in (HERE / "runs").glob("*/" + target["id"] + ".json"):
            try:
                receipt = json.loads(path.read_text(encoding="utf-8"))
            except (OSError, ValueError):
                continue
            if (receipt.get("target") == target
                    and receipt.get("candidate_sha512") == candidate_sha
                    and receipt.get("probe_sha512") == probe_sha):
                receipts.append((receipt.get("finished_at", ""), path, receipt))
        if not receipts:
            entries.append({"target": target, "status": "untested"})
            continue
        _, path, receipt = max(receipts, key=lambda item: item[0])
        entries.append({"target": target,
                        "status": verified_status(receipt, target, candidate_sha, probe_sha),
                        "receipt": str(path.relative_to(HERE))})
    return {"candidate_sha512": candidate_sha, "probe_sha512": probe_sha,
            "matrix_revision": matrix["revision"], "test_kind": "synthetic Bukkit events on live Paper; online targets have no authenticated client",
            "targets": entries}


def markdown(summary):
    entries = summary["targets"]
    counts = {status: sum(row["status"] == status for row in entries)
              for status in sorted({row["status"] for row in entries})}
    lines = ["# ByteBans Paper compatibility probes", "",
             "Frozen JAR SHA-512: `" + summary["candidate_sha512"] + "`", "",
             "These are separate synthetic Bukkit-event checks on live Paper servers. "
             "The online-mode rows do not include an authenticated client login.", "",
             "Targets: " + ", ".join(f"{count} {status}" for status, count in counts.items()) + ".", "",
             "| Minecraft | Paper build | Java | Online mode | Offline mode |",
             "| --- | ---: | ---: | --- | --- |"]
    by_version = {}
    for entry in entries:
        target = entry["target"]
        by_version.setdefault(target["version"], {})[target["connection_mode"]] = entry
    for version, modes in by_version.items():
        sample = next(iter(modes.values()))["target"]
        label = version + (" (experimental)" if sample["experimental"] else "")
        online = modes["standalone_online"]["status"]
        offline = modes["standalone_offline"]["status"]
        lines.append(f"| {label} | {sample['paper_build']} | {sample['java']} | {online} | {offline} |")
    lines.extend(["", "Only exact-JAR passing rows establish the specific synthetic checks shown. "
                  "Missing, failed and invalid receipts remain unverified.", ""])
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=HERE / "frozen/feature/ByteBans-1.1.0-SNAPSHOT.jar")
    parser.add_argument("--probe-jar", type=Path, default=HERE / "target/bytebans-private-probe-1.0.0.jar")
    args = parser.parse_args()
    summary = summarize(args.jar, args.probe_jar)
    output = HERE / "runs" / ("summary-" + summary["candidate_sha512"][:12])
    output.with_suffix(".json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    output.with_suffix(".md").write_text(markdown(summary), encoding="utf-8")
    print(output.with_suffix(".md"))
    print({status: sum(row["status"] == status for row in summary["targets"])
           for status in sorted({row["status"] for row in summary["targets"]})})


if __name__ == "__main__":
    main()
