"""Dry-run or apply ByteBans' prepared Modrinth project-page update."""

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
URL = f"https://api.modrinth.com/v2/project/{PROJECT}"
BODY = HERE / "modrinth-project-body.md"
RECEIPT = HERE / "runs/modrinth-project-page.json"


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
        raise RuntimeError(f"Modrinth {method} project returned HTTP {error.code}: {detail}") from None


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    token = load_token()
    if not token:
        raise RuntimeError("MODRINTH_TOKEN is missing")
    current = call("GET", token)
    if current.get("id") != PROJECT:
        raise RuntimeError("Modrinth project identity mismatch")
    desired = {
        "description": "Moderation records and cross-server punishments with SQLite or MySQL storage.",
        "body": BODY.read_text(encoding="utf-8"),
        "source_url": "https://github.com/LianJordaan/ByteBans",
        "issues_url": "https://github.com/LianJordaan/ByteBans/issues",
    }
    changes = {key: value for key, value in desired.items() if current.get(key) != value}
    print("ByteBans project page fields to update: " + (", ".join(changes) if changes else "none"))
    if not args.apply:
        return
    if changes:
        call("PATCH", token, changes)
    after = call("GET", token)
    if any(after.get(key) != value for key, value in desired.items()):
        raise RuntimeError("ByteBans project page verification failed")
    RECEIPT.parent.mkdir(parents=True, exist_ok=True)
    receipt = {"project_id": PROJECT, "updated_utc": datetime.now(timezone.utc).isoformat(),
               "changed_fields": list(changes),
               "previous": {key: current.get(key) for key in desired}, "current": desired}
    RECEIPT.write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    print("VERIFIED: ByteBans Modrinth project page")


if __name__ == "__main__":
    main()
