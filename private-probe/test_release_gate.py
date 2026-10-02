"""Isolated release-gate tests. No test sends a Modrinth request or uploads a JAR."""

import copy
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import release_gate as gate


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


class FakeClient:
    def __init__(self, jar):
        self.jar = jar
        self.companion = jar.with_name("ByteBans-1.1.0-paper-26.1.1.jar")
        self.created = 0
        self.prior = {
            "id": gate.PRIOR_VERSION_ID, "project_id": gate.PROJECT_ID,
            "version_number": "1.0.0", "status": "listed",
            "project_types": ["plugin"], "loaders": list(gate.PRIOR_LOADERS),
            "game_versions": list(gate.PRIOR_GAME_VERSIONS),
            "files": [{"primary": True, "hashes": {"sha512": gate.PRIOR_VERSION_SHA512}}],
        }
        self.new = []
        self.fail_second_once = False

    def get_project(self):
        return {"id": gate.PROJECT_ID, "status": "approved",
                "body": (gate.HERE / "modrinth-project-body.md").read_text(encoding="utf-8")}

    def get_disclosures(self):
        return [{"type": "ai_content", "uses": ["code", "text"]},
                {"type": "telemetry", "consent": "opt_in", "data_collected": ["Discord webhook data"]}]

    def list_versions(self):
        return [copy.deepcopy(self.prior)] + copy.deepcopy(self.new)

    def get_version(self, version_id):
        if version_id == gate.PRIOR_VERSION_ID:
            return copy.deepcopy(self.prior)
        for version in self.new:
            if version_id == version["id"]:
                return copy.deepcopy(version)
        raise AssertionError("Unknown fake version")

    def create_version(self, payload, jar):
        assert "environment" not in payload
        if self.fail_second_once and len(self.new) == 1:
            self.fail_second_once = False
            raise RuntimeError("Simulated second-version upload interruption")
        self.created += 1
        assert jar == (self.companion if payload["version_number"] == gate.PAPER_EXCEPTION_NUMBER
                       else self.jar)
        value = {
            **{key: payload[key] for key in ("project_id", "name", "version_number",
                                          "changelog", "game_versions", "loaders",
                                          "version_type", "status")},
            "project_types": ["plugin"],
            "id": "BBTest" + str(11 + len(self.new)), "files": [{"primary": True, "hashes": {
                "sha512": hashlib.sha512(jar.read_bytes()).hexdigest(),
                "sha1": hashlib.sha1(jar.read_bytes()).hexdigest(),
            }}],
        }
        self.new.append(value)
        return value["id"]


class ReleaseGateTests(unittest.TestCase):
    def test_authenticated_v3_version_reads_do_not_use_v2_helper(self):
        client = gate.ModrinthClient.__new__(gate.ModrinthClient)
        client.token = "test-token"
        client.ws = type("Workspace", (), {"AGENT": "ByteBans-test"})()
        requested = []

        class Response:
            ok = True
            status_code = 200

            def json(self):
                return []

        def get(url, *, headers, timeout):
            requested.append((url, headers, timeout))
            return Response()

        with patch("requests.get", side_effect=get):
            self.assertEqual([], client.list_versions())
            self.assertEqual([], client.get_version("BBTest11"))
        self.assertEqual([
            gate.API_V3 + "/project/" + gate.PROJECT_ID + "/version",
            gate.API_V3 + "/version/BBTest11",
        ], [url for url, _, _ in requested])
        self.assertTrue(all(headers == {"Authorization": "test-token",
                                        "User-Agent": "ByteBans-test"}
                            for _, headers, _ in requested))

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.jar = self.base / "frozen/release/ByteBans-1.1.0.jar"
        self.companion = self.jar.with_name("ByteBans-1.1.0-paper-26.1.1.jar")
        self.helper = self.base / "frozen/release/bytebans-private-probe-1.0.0.jar"
        self.jar.parent.mkdir(parents=True)
        self.helper.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(self.jar, "w") as archive:
            archive.writestr("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\r\n\r\n")
            archive.writestr("plugin.yml", b"name: ByteBans\n")
        with zipfile.ZipFile(self.companion, "w") as archive:
            archive.writestr("META-INF/MANIFEST.MF", gate.make_paper_companion.expected_manifest(
                b"Manifest-Version: 1.0\r\n\r\n"))
            archive.writestr("plugin.yml", b"name: ByteBans\n")
        self.primary_bytes = self.jar.read_bytes()
        self.helper.write_bytes(b"test-only probe JAR bytes")
        self.candidate_sha = hashlib.sha512(self.jar.read_bytes()).hexdigest()
        self.companion_sha = hashlib.sha512(self.companion.read_bytes()).hexdigest()
        self.helper_sha = hashlib.sha512(self.helper.read_bytes()).hexdigest()
        self.source = "a" * 40
        self.patches = [
            patch.object(gate, "CANDIDATE_SHA512", self.candidate_sha),
            patch.object(gate, "COMPANION_SHA512", self.companion_sha),
            patch.object(gate, "PROBE_SHA512", self.helper_sha),
            patch.object(gate, "SOURCE_REVISION", self.source),
        ]
        for item in self.patches:
            item.start()
            self.addCleanup(item.stop)
        write_json(self.base / "frozen/release/manifest.json", {
            "source_revision": self.source, "sha512": self.candidate_sha,
        })
        write_json(self.base / "frozen/release/paper-companion-attestation.json", {
            "source_revision": self.source,
            "primary_sha512": self.candidate_sha,
            "companion_sha512": self.companion_sha,
            "entry_count": 2,
            "changed_entries": [gate.make_paper_companion.MANIFEST],
            "runtime_entries_equal": True,
            "manifest_marker": gate.make_paper_companion.MARKER.decode("ascii").strip(),
        })
        shutil.copyfile(gate.HERE / "matrix.json", self.base / "matrix.json")
        shutil.copyfile(gate.HERE / "run_local_mysql_network.py",
                        self.base / "run_local_mysql_network.py")
        matrix = json.loads((self.base / "matrix.json").read_text(encoding="utf-8"))
        self.targets = matrix["targets"]
        self.matrix_folder = self.base / "runs/20261001T220000Z"
        self.matrix_folder.mkdir(parents=True)
        for index, target in enumerate(self.targets):
            self._write_target(index, target)
        self.companion_folder = self.base / "runs/20261001T220001Z-paper-companion"
        self.companion_folder.mkdir(parents=True)
        for target in (item for item in self.targets if item["version"] == "26.1.1"):
            original = json.loads((self.matrix_folder / (target["id"] + ".json")).read_text())
            original.update({"candidate_sha512": self.companion_sha,
                             "candidate_sha512_actual": self.companion_sha,
                             "port": 27300})
            write_json(self.companion_folder / (target["id"] + ".json"), original)
        self._write_client()
        self._write_web()
        self._write_network()
        shutil.copyfile(gate.HERE / "purpur-matrix.json",
                        self.base / "purpur-matrix.json")
        self.purpur_pins = json.loads((self.base / "purpur-matrix.json").read_text(
            encoding="utf-8"))["versions"]
        self.purpur_folder = self.base / "runs/20261001T230000Z-purpur"
        for index, pin in enumerate(self.purpur_pins):
            for mode in ("online", "offline"):
                self._write_purpur_target(index, pin, mode)
        self._write_purpur_clients()
        self._write_purpur_web()
        shutil.copyfile(gate.HERE / "release-changelog.md",
                        self.base / "release-changelog.md")
        write_json(self.base / "runs/ai-review.json", {
            "project_id": gate.PROJECT_ID, "candidate_sha512": self.candidate_sha,
            "substantial_ai_generated_code": True,
            "ai_written_release_text": True,
            "requested_version_status": "listed",
            "primarily_ai_derived": False,
            "significant_human_original_content_confirmed": True,
            "owner_authorized_unlisted": None,
            "modrinth_ai_code_disclosure_verified": True,
            "modrinth_ai_text_disclosure_verified": True,
            "opt_in_discord_data_disclosure_verified": True,
            "reviewer": "Fixture Reviewer",
            "reviewed_at": "2026-10-01T00:00:00+00:00",
            "review_basis": "Synthetic test fixture only: a human assessment would explain retained original code, new code, and why the published project remains eligible.",
        })

    def _write_target(self, index, target):
        version = target["version"]
        probe = {"probe_revision": "1", "minecraft": version,
                 "server": "Paper " + version, "java": str(target["java"]) + ".0.1",
                 "bytebans_version": "1.1.0", "passed": True,
                 "cases": {case: True for case in gate.summarize.REQUIRED_CASES}}
        row = {"target": target, "source_revision": self.source,
               "candidate_sha512": self.candidate_sha, "candidate_sha512_actual": self.candidate_sha,
               "probe_sha512": self.helper_sha, "probe_sha512_actual": self.helper_sha,
               "paper_sha256_actual": target["paper_sha256"],
               "instance_id": f"mw-{index:016x}", "port": 27200 + index,
               "started_at": "2026-10-01T22:00:00+00:00",
               "finished_at": "2026-10-01T22:05:00+00:00",
               "status": "pass", "stopped": True, "probe": probe,
               "startup_diagnostics": {"initialized": True, "fatal_lines": []},
               "probe_diagnostics": {"minecraft_matches": True, "java_matches": True,
                                     "server_mentions_version": True,
                                     "all_required_cases_pass": True},
               "logs": "[00:00 INFO]: [ByteBans] ByteBans was successfully initialized.\n"}
        write_json(self.matrix_folder / (target["id"] + ".json"), row)

    def _write_client(self):
        checks = {"real_offline_join": True, "ip_unban_restores_join": True,
                  "ip_ban_kicks_existing_client": {"event": "kicked"},
                  "ip_ban_rejects_shared_address": {"event": "kicked"},
                  "ip_mute_blocks_shared_address_chat": {"event": "message"},
                  "freeze_holds_real_client": {
                      "server_horizontal_blocks": 0.0,
                      "server_before": {"freeze_id": 42, "bypass": False}},
                  "unfreeze_restores_real_movement": {
                      "server_horizontal_blocks": 2.0,
                      "server_before": {"bypass": False}}}
        for index, version in enumerate(gate.CLIENT_VERSIONS):
            target = next(item for item in self.targets if item["version"] == version
                          and item["connection_mode"] == "standalone_offline")
            write_json(self.base / f"runs/20261001T22010{index}Z-local-client-{version}/result.json", {
                "source_revision": self.source, "candidate_sha512": self.candidate_sha,
                "probe_sha512": self.helper_sha, "client_sha256": gate.CLIENT_SCRIPT_SHA256,
                "status": "pass", "stopped": True, "port": 27242,
                "instance_id": "mw-1111111111111111", "version": version,
                "paper_build": target["paper_build"],
                "paper_sha256_actual": target["paper_sha256"],
                "java": target["java"], "connection_mode": "standalone_offline",
                "finished_at": "2026-10-01T22:05:00+00:00", "checks": checks,
            })

    def _write_web(self):
        write_json(self.base / "runs/20261001T220200Z-local-admin-web/result.json", {
            "source_revision": self.source, "candidate_sha512": self.candidate_sha,
            "status": "pass", "server_stopped": True,
            "config_restored_disabled": True, "plugin_initialized": True,
            "panel_bound_loopback": True, "instance_id": "mw-1111111111111111",
            "version": "1.21.4", "paper_build": 232,
            "paper_sha256": gate.PAPER_1214_SHA256, "java": 21,
            "script_sha256": "b" * 64, "recorded_at": "2026-10-01T22:06:00+00:00",
            "checks": sorted(gate.EXPECTED_WEB_CHECKS),
        })

    def _write_network(self):
        self.network_path = (self.base /
                             "runs/20261001T220300Z-local-mysql-network/result.json")
        alpha = self.base / "live/mw-aaaaaaaaaaaaaaaa"
        beta = self.base / "live/mw-bbbbbbbbbbbbbbbb"
        alpha.mkdir(parents=True)
        beta.mkdir(parents=True)
        diagnostic = "[ByteBans] ByteBans was successfully initialized.\n"
        logs = {"alpha_server.log_sha256": alpha / "server.log",
                "beta_server.log_sha256": beta / "server.log",
                "beta_server-restart.log_sha256": beta / "server-restart.log"}
        for path in logs.values():
            path.write_text(diagnostic, encoding="utf-8")
        write_json(self.network_path, {
            "source_revision": self.source,
            "candidate_sha512": self.candidate_sha,
            "runner_sha256": gate.NETWORK_RUNNER_SHA256,
            "client_sha256": gate.CLIENT_SCRIPT_SHA256,
            "status": "pass", "started_at": "2026-10-01T22:00:00+00:00",
            "finished_at": "2026-10-01T22:10:00+00:00",
            "paper_version": "1.21.4", "paper_build": 232,
            "paper_sha256": gate.PAPER_1214_SHA256, "java_major": 21,
            "mariadb_port": 27314,
            "mariadb_data_path": "/tmp/bytebans-network-111111111111",
            "mariadb_stopped": True, "mariadb_exit_code": 0,
            "checks": {key: True for key in gate.EXPECTED_NETWORK_CHECKS},
            "observations": {
                "scoped_ban_id": 1, "global_ban_id": 3,
                "scoped_mute_id": 5, "global_mute_id": 7,
                **{key: hashlib.sha256(path.read_bytes()).hexdigest()
                   for key, path in logs.items()},
            },
            "servers": [
                {"name": "alpha", "instance_id": "mw-aaaaaaaaaaaaaaaa",
                 "path": str(alpha),
                 "game_port": 27310, "rcon_port": 27312,
                 "candidate_sha512_actual": self.candidate_sha},
                {"name": "beta", "instance_id": "mw-bbbbbbbbbbbbbbbb",
                 "path": str(beta),
                 "game_port": 27311, "rcon_port": 27313,
                 "candidate_sha512_actual": self.candidate_sha},
            ],
            "stops": [
                {"label": "beta-before-restart", "stopped": True, "exit_code": 0},
                {"label": "alpha", "stopped": True, "exit_code": 0},
                {"label": "beta", "stopped": True, "exit_code": 0},
            ],
        })

    def _write_purpur_target(self, index, pin, mode):
        version = pin["version"]
        target_id = f"purpur-{version}-{mode}-synthetic"
        probe = {"probe_revision": "1", "minecraft": version,
                 "server": "Purpur " + version, "java": str(pin["java"]) + ".0.1",
                 "bytebans_version": "1.1.0", "passed": True,
                 "cases": {case: True for case in gate.summarize.REQUIRED_CASES}}
        row = {"loader": "purpur", "minecraft": version,
               "purpur_build": pin["build"], "publisher_md5": pin["md5"],
               "publisher_build_url": pin["publisher_build_url"],
               "purpur_sha256_expected": pin["sha256"],
               "purpur_sha256_actual": pin["sha256"],
               "source_revision": self.source,
               "candidate_sha512": self.candidate_sha,
               "candidate_sha512_actual": self.candidate_sha,
               "probe_sha512": self.helper_sha,
               "probe_sha512_actual": self.helper_sha,
               "java": pin["java"],
               "connection_mode": "standalone_" + mode,
               "instance_id": f"mw-{index:016x}", "port": 27244,
               "execution_host": "local-windows-loopback",
               "started_at": "2026-10-01T23:00:00+00:00",
               "finished_at": "2026-10-01T23:05:00+00:00",
               "status": "pass", "stopped": True, "probe": probe,
               "startup_diagnostics": {"initialized": True, "fatal_lines": []},
               "probe_diagnostics": {"minecraft_matches": True,
                                     "java_matches": True,
                                     "server_mentions_version": True,
                                     "all_required_cases_pass": True},
               "logs": "[ByteBans] ByteBans was successfully initialized.\n"}
        write_json(self.purpur_folder / (target_id + ".json"), row)

    def _write_purpur_clients(self):
        checks = {"real_offline_join": True, "ip_unban_restores_join": True,
                  "ip_ban_kicks_existing_client": {"event": "kicked"},
                  "ip_ban_rejects_shared_address": {"event": "kicked"},
                  "ip_mute_blocks_shared_address_chat": {"event": "message"},
                  "freeze_holds_real_client": {
                      "server_horizontal_blocks": 0.0,
                      "server_before": {"freeze_id": 42, "bypass": False}},
                  "unfreeze_restores_real_movement": {
                      "server_horizontal_blocks": 2.0,
                      "server_before": {"bypass": False}}}
        by_version = {pin["version"]: pin for pin in self.purpur_pins}
        for index, version in enumerate(gate.purpur_release_evidence.CLIENT_VERSIONS):
            pin = by_version[version]
            write_json(self.base / f"runs/20261001T23010{index}Z-local-purpur-client-{version}/result.json", {
                "source_revision": self.source,
                "candidate_sha512": self.candidate_sha,
                "probe_sha512": self.helper_sha,
                "client_sha256": gate.CLIENT_SCRIPT_SHA256,
                "loader": "purpur", "version": version,
                "status": "pass", "stopped": True,
                "port": 27242, "instance_id": "mw-1111111111111111",
                "purpur_build": pin["build"],
                "purpur_sha256_expected": pin["sha256"],
                "purpur_sha256_actual": pin["sha256"],
                "java": pin["java"], "connection_mode": "standalone_offline",
                "finished_at": "2026-10-01T23:05:00+00:00", "checks": checks,
            })

    def _write_purpur_web(self):
        pin = next(item for item in self.purpur_pins if item["version"] == "26.2")
        write_json(self.base / "runs/20261001T230200Z-local-purpur-admin-web/result.json", {
            "source_revision": self.source, "candidate_sha512": self.candidate_sha,
            "candidate_sha512_actual": self.candidate_sha,
            "status": "pass", "server_stopped": True,
            "config_restored_disabled": True, "plugin_initialized": True,
            "panel_bound_loopback": True, "instance_id": "mw-1111111111111111",
            "loader": "purpur", "version": "26.2",
            "purpur_build": pin["build"],
            "publisher_md5": pin["md5"],
            "purpur_sha256_expected": pin["sha256"],
            "purpur_sha256_actual": pin["sha256"],
            "java": pin["java"],
            "execution_host": "local-windows-loopback", "port": 27246,
            "panel_port": 27247,
            "script_sha256": gate.purpur_release_evidence.ADMIN_SCRIPT_SHA256,
            "recorded_at": "2026-10-01T23:06:00+00:00",
            "checks": sorted(gate.EXPECTED_WEB_CHECKS),
        })

    def test_complete_exact_evidence_forms_two_non_overclaiming_payloads(self):
        plan = gate.audit(base=self.base)
        self.assertTrue(plan["ready"], plan["issues"])
        self.assertEqual(15, len(plan["qualified_versions"]))
        self.assertEqual(14, len(plan["purpur_qualified_versions"]))
        shared, exception = plan["payloads"]
        self.assertEqual(["paper", "purpur"], shared["loaders"])
        self.assertEqual(plan["purpur_qualified_versions"], shared["game_versions"])
        self.assertEqual(["paper"], exception["loaders"])
        self.assertEqual(["26.1.1"], exception["game_versions"])
        self.assertNotEqual(shared["version_number"], exception["version_number"])
        self.assertNotEqual(plan["candidate_sha512"], plan["companion_sha512"])
        self.assertEqual(2, len([key for key in plan["receipts"]
                                 if key.startswith("companion-paper-")]))
        self.assertEqual("listed", shared["status"])
        self.assertEqual(30, len([key for key in plan["receipts"] if key.startswith("paper-")]))
        self.assertEqual(28, len([key for key in plan["receipts"] if key.startswith("purpur-")]))
        self.assertEqual(3, len([key for key in plan["receipts"]
                                 if key.startswith("real_offline_client_")]))
        self.assertIn("mysql_network", plan["receipts"])

    def test_missing_or_failed_mysql_network_proof_blocks_release(self):
        row = json.loads(self.network_path.read_text(encoding="utf-8"))
        self.network_path.unlink()
        self.assertFalse(gate.audit(base=self.base)["ready"])
        write_json(self.network_path, {**row, "status": "fail"})
        self.assertFalse(gate.audit(base=self.base)["ready"])
        write_json(self.network_path, {**row, "mariadb_stopped": False})
        self.assertFalse(gate.audit(base=self.base)["ready"])

    def test_missing_failed_and_mismatched_targets_fail_closed(self):
        target = self.targets[0]
        path = self.matrix_folder / (target["id"] + ".json")
        row = json.loads(path.read_text())
        path.unlink()
        plan = gate.audit(base=self.base)
        self.assertFalse(plan["ready"])
        self.assertIn(target["id"], plan["target_issues"])
        write_json(path, {**row, "status": "fail"})
        plan = gate.audit(base=self.base)
        self.assertFalse(plan["ready"])
        write_json(path, {**row, "candidate_sha512_actual": "0" * 128})
        plan = gate.audit(base=self.base)
        self.assertFalse(plan["ready"])

    def test_companion_missing_or_failed_mode_blocks_release(self):
        target = next(item for item in self.targets if item["version"] == "26.1.1"
                      and item["connection_mode"] == "standalone_online")
        path = self.companion_folder / (target["id"] + ".json")
        original = json.loads(path.read_text(encoding="utf-8"))
        path.unlink()
        plan = gate.audit(base=self.base)
        self.assertFalse(plan["ready"])
        self.assertIn("companion-" + target["id"], plan["target_issues"])
        write_json(path, {**original, "status": "fail"})
        self.assertFalse(gate.audit(base=self.base)["ready"])

    def test_companion_runtime_entry_change_blocks_release_even_with_matching_hash_proof(self):
        with zipfile.ZipFile(self.companion, "w") as archive:
            archive.writestr("META-INF/MANIFEST.MF", gate.make_paper_companion.expected_manifest(
                b"Manifest-Version: 1.0\r\n\r\n"))
            archive.writestr("plugin.yml", b"name: ChangedByteBans\n")
        changed_hash = hashlib.sha512(self.companion.read_bytes()).hexdigest()
        proof_path = self.base / "frozen/release/paper-companion-attestation.json"
        proof = json.loads(proof_path.read_text(encoding="utf-8"))
        proof["companion_sha512"] = changed_hash
        write_json(proof_path, proof)
        with patch.object(gate, "COMPANION_SHA512", changed_hash):
            plan = gate.audit(base=self.base)
        self.assertFalse(plan["ready"])
        self.assertTrue(any("changes a class or resource" in issue for issue in plan["issues"]))

    def test_changed_candidate_fails_and_missing_human_review_defaults_unlisted(self):
        self.jar.write_bytes(b"different JAR")
        self.assertFalse(gate.audit(base=self.base)["ready"])
        self.jar.write_bytes(self.primary_bytes)
        (self.base / "runs/ai-review.json").unlink()
        plan = gate.audit(base=self.base)
        self.assertTrue(plan["ready"], plan["issues"])
        self.assertTrue(all(payload["status"] == "unlisted" for payload in plan["payloads"]))
        self.assertIsNone(plan["ai_review_sha256"])
        client = FakeClient(self.jar)
        receipt = gate.publish(base=self.base, client=client, user_requested_upload=True)
        self.assertEqual("unlisted", receipt["status"])

    def test_present_but_invalid_human_review_blocks_release(self):
        path = self.base / "runs/ai-review.json"
        path.write_text("{not json}", encoding="utf-8")
        self.assertFalse(gate.audit(base=self.base)["ready"])

    def test_ai_eligibility_or_disclosure_not_confirmed_blocks_release(self):
        path = self.base / "runs/ai-review.json"
        original = json.loads(path.read_text(encoding="utf-8"))
        for field, value in (("primarily_ai_derived", True),
                             ("modrinth_ai_code_disclosure_verified", False),
                             ("modrinth_ai_text_disclosure_verified", False)):
            with self.subTest(field=field):
                write_json(path, {**original, field: value})
                self.assertFalse(gate.audit(base=self.base)["ready"])

    def test_explicit_unlisted_review_can_plan_private_visibility(self):
        path = self.base / "runs/ai-review.json"
        review = json.loads(path.read_text(encoding="utf-8"))
        review.update({"requested_version_status": "unlisted",
                       "primarily_ai_derived": True,
                       "significant_human_original_content_confirmed": False,
                       "owner_authorized_unlisted": True})
        write_json(path, review)
        plan = gate.audit(base=self.base)
        self.assertTrue(plan["ready"], plan["issues"])
        self.assertTrue(all(payload["status"] == "unlisted" for payload in plan["payloads"]))
        client = FakeClient(self.jar)
        receipt = gate.publish(base=self.base, client=client, user_requested_upload=True)
        self.assertEqual("unlisted", receipt["status"])
        self.assertEqual("listed", client.prior["status"])

    def test_unlisted_requires_explicit_owner_choice(self):
        path = self.base / "runs/ai-review.json"
        review = json.loads(path.read_text(encoding="utf-8"))
        review.update({"requested_version_status": "unlisted",
                       "primarily_ai_derived": True,
                       "owner_authorized_unlisted": False})
        write_json(path, review)
        plan = gate.audit(base=self.base)
        self.assertFalse(plan["ready"])
        self.assertEqual([], plan["payloads"])

    def test_latest_failed_retry_supersedes_earlier_pass(self):
        target = self.targets[0]
        older = self.matrix_folder / (target["id"] + ".json")
        row = json.loads(older.read_text(encoding="utf-8"))
        row["finished_at"] = "2026-10-01T23:05:00+00:00"
        row["status"] = "fail"
        write_json(self.base / "runs/20261001T230000Z" / older.name, row)
        plan = gate.audit(base=self.base)
        self.assertFalse(plan["ready"])
        self.assertIn(target["id"], plan["target_issues"])

    def test_purpur_missing_changed_pin_and_latest_failure_block_release(self):
        target_id = "purpur-26.3-offline-synthetic"
        path = self.purpur_folder / (target_id + ".json")
        row = json.loads(path.read_text(encoding="utf-8"))
        path.unlink()
        plan = gate.audit(base=self.base)
        self.assertFalse(plan["ready"])
        self.assertIn(target_id, plan["target_issues"])
        write_json(path, row)
        row["finished_at"] = "2026-10-01T23:06:00+00:00"
        row["purpur_sha256_actual"] = "0" * 64
        write_json(self.base / "runs/20261001T230600Z-purpur" / path.name, row)
        plan = gate.audit(base=self.base)
        self.assertFalse(plan["ready"])
        self.assertIn(target_id, plan["target_issues"])
        self.assertEqual([], plan["payloads"])

    def test_purpur_real_client_or_admin_failure_blocks_release(self):
        client_path = (self.base
                       / "runs/20261001T230100Z-local-purpur-client-1.21/result.json")
        row = json.loads(client_path.read_text(encoding="utf-8"))
        row["checks"]["freeze_holds_real_client"]["server_horizontal_blocks"] = 1.0
        write_json(client_path, row)
        plan = gate.audit(base=self.base)
        self.assertFalse(plan["ready"])
        self.assertTrue(any("Purpur 1.21" in issue for issue in plan["issues"]))
        self._write_purpur_clients()
        web_path = self.base / "runs/20261001T230200Z-local-purpur-admin-web/result.json"
        web = json.loads(web_path.read_text(encoding="utf-8"))
        web["panel_bound_loopback"] = False
        write_json(web_path, web)
        plan = gate.audit(base=self.base)
        self.assertFalse(plan["ready"])
        self.assertTrue(any("Purpur admin web" in issue for issue in plan["issues"]))

    def test_dry_run_never_constructs_upload_client(self):
        with patch.object(gate, "HERE", self.base), patch.object(
                gate, "ModrinthClient", side_effect=AssertionError("unexpected network client")):
            self.assertEqual(0, gate.main(["--dry-run"]))
        self.assertTrue((self.base / "runs/release-dry-run.json").is_file())

    def test_publish_requires_user_instruction_and_is_idempotent(self):
        client = FakeClient(self.jar)
        prior = copy.deepcopy(client.prior)
        with self.assertRaisesRegex(ValueError, "user's later upload instruction"):
            gate.publish(base=self.base, client=client)
        first = gate.publish(base=self.base, client=client, user_requested_upload=True)
        self.assertEqual(["created", "created"], [v["action"] for v in first["versions"]])
        self.assertEqual(2, client.created)
        self.assertEqual(prior, client.prior)
        second = gate.publish(base=self.base, client=client, user_requested_upload=True)
        self.assertEqual(first, second)
        self.assertEqual(2, client.created)

    def test_interrupted_second_upload_resumes_without_duplicate_first(self):
        client = FakeClient(self.jar)
        client.fail_second_once = True
        with self.assertRaisesRegex(RuntimeError, "second-version upload interruption"):
            gate.publish(base=self.base, client=client, user_requested_upload=True)
        self.assertEqual(1, client.created)
        self.assertEqual(1, len(client.new))
        self.assertFalse((self.base / "runs/publication.json").exists())
        receipt = gate.publish(base=self.base, client=client, user_requested_upload=True)
        self.assertEqual(2, client.created)
        self.assertEqual(["verified_existing", "created"],
                         [v["action"] for v in receipt["versions"]])
        self.assertEqual(2, len({v["version_id"] for v in receipt["versions"]}))

    def test_existing_paper_exception_cannot_gain_purpur_loader(self):
        client = FakeClient(self.jar)
        for payload in gate.audit(base=self.base)["payloads"]:
            artifact = self.jar if payload["version_number"] == gate.VERSION_NUMBER else self.companion
            client.create_version(payload, artifact)
        client.created = 0
        client.new[1]["loaders"] = ["paper", "purpur"]
        with self.assertRaisesRegex(ValueError, "different metadata"):
            gate.publish(base=self.base, client=client, user_requested_upload=True)
        self.assertEqual(0, client.created)

    def test_plugin_project_type_is_required_for_existing_and_new_versions(self):
        client = FakeClient(self.jar)
        client.prior["project_types"] = ["mod"]
        with self.assertRaisesRegex(ValueError, "1.0.0 release differs"):
            gate.publish(base=self.base, client=client, user_requested_upload=True)
        self.assertEqual(0, client.created)

        client = FakeClient(self.jar)
        payload = gate.audit(base=self.base)["payloads"][0]
        client.create_version(payload, self.jar)
        client.created = 0
        client.new[0]["project_types"] = ["mod"]
        with self.assertRaisesRegex(ValueError, "different metadata"):
            gate.publish(base=self.base, client=client, user_requested_upload=True)
        self.assertEqual(0, client.created)

    def test_unexpected_third_version_with_same_hash_blocks_upload(self):
        client = FakeClient(self.jar)
        payload = dict(gate.audit(base=self.base)["payloads"][0])
        payload["version_number"] = "1.1.0-unexpected"
        client.create_version(payload, self.jar)
        client.created = 0
        with self.assertRaisesRegex(ValueError, "Unexpected or duplicate"):
            gate.publish(base=self.base, client=client, user_requested_upload=True)
        self.assertEqual(0, client.created)

    def test_changed_local_publication_receipt_blocks_retry(self):
        client = FakeClient(self.jar)
        gate.publish(base=self.base, client=client, user_requested_upload=True)
        path = self.base / "runs/publication.json"
        receipt = json.loads(path.read_text(encoding="utf-8"))
        receipt["versions"][1]["loaders"] = ["paper", "purpur"]
        write_json(path, receipt)
        with self.assertRaisesRegex(ValueError, "local ByteBans publication receipt conflicts"):
            gate.publish(base=self.base, client=client, user_requested_upload=True)
        self.assertEqual(2, client.created)

    def test_missing_remote_disclosures_or_outdated_page_never_uploads(self):
        client = FakeClient(self.jar)
        client.get_disclosures = lambda: []
        with self.assertRaisesRegex(ValueError, "AI code/text disclosure"):
            gate.publish(base=self.base, client=client, user_requested_upload=True)
        self.assertEqual(0, client.created)
        client = FakeClient(self.jar)
        client.get_project = lambda: {"id": gate.PROJECT_ID, "status": "approved", "body": "Old feature list"}
        with self.assertRaisesRegex(ValueError, "accurate ByteBans project page"):
            gate.publish(base=self.base, client=client, user_requested_upload=True)
        self.assertEqual(0, client.created)

    def test_conflicting_existing_version_never_uploads(self):
        client = FakeClient(self.jar)
        payload = gate.audit(base=self.base)["payloads"][0]
        client.create_version(payload, self.jar)
        client.created = 0
        client.new[0]["loaders"] = ["paper", "spigot"]
        with self.assertRaisesRegex(ValueError, "different metadata"):
            gate.publish(base=self.base, client=client, user_requested_upload=True)
        self.assertEqual(0, client.created)
        self.assertFalse((self.base / "runs/publication.json").exists())

    def test_failed_post_upload_verification_writes_no_receipt(self):
        client = FakeClient(self.jar)
        original = client.get_version

        def wrong_loader(version_id):
            result = original(version_id)
            if version_id != gate.PRIOR_VERSION_ID:
                result["loaders"] = ["paper", "spigot"]
            return result

        client.get_version = wrong_loader
        with self.assertRaisesRegex(RuntimeError, "verification failed"):
            gate.publish(base=self.base, client=client, user_requested_upload=True)
        self.assertEqual(1, client.created)
        self.assertFalse((self.base / "runs/publication.json").exists())

    def test_changed_published_versions_or_hashes_write_no_receipt(self):
        for field in ("game_versions", "files", "changelog", "status"):
            with self.subTest(field=field):
                client = FakeClient(self.jar)
                original = client.get_version

                def wrong_metadata(version_id):
                    result = original(version_id)
                    if version_id != gate.PRIOR_VERSION_ID:
                        if field == "game_versions":
                            result[field] = result[field][:-1]
                        elif field == "files":
                            result[field][0]["hashes"]["sha512"] = "0" * 128
                        elif field == "status":
                            result[field] = "unlisted"
                        else:
                            result[field] = "Modified after upload"
                    return result

                client.get_version = wrong_metadata
                with self.assertRaisesRegex(RuntimeError, "verification failed"):
                    gate.publish(base=self.base, client=client, user_requested_upload=True)
                self.assertEqual(1, client.created)
                self.assertFalse((self.base / "runs/publication.json").exists())

    def test_changed_existing_release_blocks_upload(self):
        client = FakeClient(self.jar)
        client.prior["game_versions"] = client.prior["game_versions"][:-1]
        with self.assertRaisesRegex(ValueError, "1.0.0 release differs"):
            gate.publish(base=self.base, client=client, user_requested_upload=True)
        self.assertEqual(0, client.created)


if __name__ == "__main__":
    unittest.main()
