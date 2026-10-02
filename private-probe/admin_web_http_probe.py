"""Probe the ByteBans admin page on a retained remote or local test server.

Remote usage: nsenter -t <verified-container-pid> -n python3 - <managed-id> <jar-sha512>
The script reads the private token file locally and never prints it.
"""

import hashlib
import http.cookiejar
import json
from pathlib import Path
import re
import sqlite3
import stat
import sys
import uuid
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import HTTPCookieProcessor, ProxyHandler, Request, build_opener


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def main(instance_id, expected_sha512, *, plugin_folder=None,
         base="http://127.0.0.1:8765", check_posix_permissions=True):
    check(re.fullmatch(r"mw-[a-f0-9]{16}", instance_id) is not None, "Invalid managed ID")
    check(re.fullmatch(r"[a-f0-9]{128}", expected_sha512) is not None, "Invalid JAR hash")
    folder = (Path(plugin_folder) if plugin_folder is not None
              else Path("/opt/modrinth-workspace/instances") / instance_id / "data/plugins")
    check(folder.is_dir() and not folder.is_symlink(), "Instance plugin folder missing")
    matching = [path for path in folder.glob("*.jar")
                if path.is_file() and not path.is_symlink()
                and hashlib.sha512(path.read_bytes()).hexdigest() == expected_sha512]
    check(len(matching) == 1, "Frozen candidate JAR hash did not match")
    token_file = folder / "ByteBans/admin-web-token.txt"
    check(token_file.is_file() and not token_file.is_symlink(), "Admin token file missing")
    if check_posix_permissions:
        check(stat.S_IMODE(token_file.stat().st_mode) == 0o600, "Admin token is not mode 0600")
    token = token_file.read_text(encoding="utf-8").strip()
    check(len(token) >= 43, "Admin token is too short")

    opener = build_opener(ProxyHandler({}), HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def request(path, fields=None, headers=None):
        data = urlencode(fields).encode("utf-8") if fields is not None else None
        request_headers = dict(headers or {})
        if data is not None:
            request_headers["Origin"] = base
            request_headers["Content-Type"] = "application/x-www-form-urlencoded"
        item = Request(base + path, data=data, headers=request_headers)
        try:
            with opener.open(item, timeout=10) as response:
                return response.status, response.read().decode("utf-8")
        except HTTPError as error:
            return error.code, error.read().decode("utf-8")

    status, login_page = request("/")
    check(status == 200 and "Sign in" in login_page, "Local sign-in page unavailable")
    status, _ = request("/", headers={"Host": "evil.example"})
    check(status == 421, "Unexpected Host was accepted")
    status, _ = request("/login", {"actor": "SmokeAdmin", "token": "wrong"})
    check(status == 401, "Wrong token was accepted")
    status, dashboard = request("/login", {"actor": "SmokeAdmin", "token": token})
    check(status == 200 and "New action" in dashboard, "Correct token could not sign in")
    match = re.search(r"name='csrf' value='([^']+)'", dashboard)
    check(match is not None, "Dashboard form token missing")
    csrf = match.group(1)

    subject = str(uuid.uuid4())
    marker = "Private admin web smoke " + uuid.uuid4().hex[:10]
    form = {"action": "note", "subject": subject, "scope": "*", "reason": marker}
    status, _ = request("/action", {**form, "csrf": "wrong"})
    check(status == 403, "Wrong form token was accepted")
    database = folder / "ByteBans/database.db"
    check(database.is_file(), "Default SQLite database missing")
    with sqlite3.connect(database) as connection:
        count = connection.execute(
            "SELECT COUNT(*) FROM bytebans_punishments WHERE subject = ?", (subject,)
        ).fetchone()[0]
    check(count == 0, "Rejected form changed database")

    status, created = request("/action", {**form, "csrf": csrf})
    check(status == 200 and "Saved note" in created, "Web note creation failed")
    with sqlite3.connect(database) as connection:
        rows = connection.execute(
            "SELECT id, type, active, punisher_uuid FROM bytebans_punishments "
            "WHERE subject = ? ORDER BY id DESC", (subject,)
        ).fetchall()
    check(len(rows) == 1 and rows[0][1:] == ("note", 1, "WEB:SmokeAdmin"),
          "Saved note lacks correct active state or audit actor")
    note_id = rows[0][0]
    status, removed = request("/action", {
        "action": "removenote", "subject": subject, "id": str(note_id),
        "reason": "Private admin web smoke cleanup", "csrf": csrf,
    })
    check(status == 200 and "Removed note" in removed, "Web note undo failed")
    with sqlite3.connect(database) as connection:
        rows = connection.execute(
            "SELECT type, active, punisher_uuid FROM bytebans_punishments "
            "WHERE subject = ? ORDER BY id", (subject,)
        ).fetchall()
    check(rows == [("note", 0, "WEB:SmokeAdmin"),
                   ("removenote", 0, "WEB:SmokeAdmin")],
          "Undo did not atomically close the note and retain its audit entry")

    result = {"status": "pass", "instance_id": instance_id,
              "candidate_sha512": expected_sha512,
              "checks": ["loopback_login_page", "host_restriction", "wrong_token_denied",
                         "authenticated_login", "csrf_rejection_no_write",
                         "note_create_with_actor", "note_undo_with_audit"],
              "subject": subject, "note_id": note_id}
    print(json.dumps(result, sort_keys=True))
    return result


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("Usage: admin_web_http_probe.py <managed-id> <jar-sha512>")
    main(sys.argv[1], sys.argv[2])
