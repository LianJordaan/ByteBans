"""Dry-run or apply ByteBans' honest Modrinth AI and webhook disclosures."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import sys
from urllib.error import HTTPError
from urllib.request import Request, urlopen

HERE = Path(__file__).resolve().parent
WORKSPACE = HERE.parents[2]
sys.path.insert(0, str(WORKSPACE))
from dashboard.workspace import load_token  # noqa: E402

PROJECT = "ysEBBeJv"
URL = f"https://api.modrinth.com/v3/project/{PROJECT}/disclosures"
RECEIPT = HERE / "runs/modrinth-disclosures.json"
DESIRED = [
    {"type": "ai_content", "uses": ["code", "text"],
     "note": ("Substantial ByteBans 1.1.0 code, tests, documentation and project-page "
              "text were made with generative AI. The earlier 1.0.0 release predates this update.")},
    {"type": "telemetry", "consent": "opt_in",
     "data_collected": [
         "If an administrator enables Discord notifications and configures a webhook, moderation actions, player names and reasons are sent to that Discord endpoint.",
         "IP punishments may include player IP addresses. Staff notes are excluded unless the administrator enables them separately.",
         "The administrator and members of the configured Discord channel can access those messages; Discord handles storage under its own policies.",
     ]},
]


def call(method: str, token: str, data: dict | None = None):
    payload = json.dumps(data).encode("utf-8") if data is not None else None
    headers = {"Authorization": token, "User-Agent": "ModrinthWorkspace/1.0"}
    if payload is not None:
        headers["Content-Type"] = "application/json"
    try:
        with urlopen(Request(URL, data=payload, headers=headers, method=method), timeout=30) as response:
            return json.load(response) if response.status != 204 else None
    except HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")[:400]
        raise RuntimeError(f"Modrinth {method} disclosure returned HTTP {error.code}: {detail}") from None


def matches(current: dict | None, desired: dict) -> bool:
    return current is not None and all(current.get(key) == value for key, value in desired.items())


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    token = load_token()
    if not token:
        raise RuntimeError("MODRINTH_TOKEN is missing")
    before = call("GET", token)["disclosures"]
    updates = [desired for desired in DESIRED if not matches(
        next((item for item in before if item.get("type") == desired["type"]), None), desired)]
    print("ByteBans disclosures to set: " + (", ".join(row["type"] for row in updates) if updates else "none"))
    if not args.apply:
        return
    if updates:
        call("PATCH", token, {"set": updates, "remove": []})
    after = call("GET", token)["disclosures"]
    for desired in DESIRED:
        actual = next((item for item in after if item.get("type") == desired["type"]), None)
        if not matches(actual, desired):
            raise RuntimeError("ByteBans disclosure verification failed for " + desired["type"])
    RECEIPT.parent.mkdir(parents=True, exist_ok=True)
    RECEIPT.write_text(json.dumps({
        "project_id": PROJECT, "verified_utc": datetime.now(timezone.utc).isoformat(),
        "changed": bool(updates), "disclosures": DESIRED,
    }, indent=2) + "\n", encoding="utf-8")
    print("VERIFIED: ByteBans AI and opt-in Discord data disclosures")


if __name__ == "__main__":
    main()
