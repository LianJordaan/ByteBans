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

import release_gate as gate


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


class FakeClient:
    def __init__(self, jar):
        self.jar = jar
        self.created = 0
        self.prior = {
            "id": gate.PRIOR_VERSION_ID, "project_id": gate.PROJECT_ID,
            "version_number": "1.0.0", "status": "listed",
            "environment": "unknown", "loaders": list(gate.PRIOR_LOADERS),
            "game_versions": list(gate.PRIOR_GAME_VERSIONS),
            "files": [{"primary": True, "hashes": {"sha512": gate.PRIOR_VERSION_SHA512}}],
        }
        self.new = None

    def get_project(self):
        return {"id": gate.PROJECT_ID, "status": "approved",
                "body": (gate.HERE / "modrinth-project-body.md").read_text(encoding="utf-8")}

    def get_disclosures(self):
        return [{"type": "ai_content", "uses": ["code", "text"]},
                {"type": "telemetry", "consent": "opt_in", "data_collected": ["Discord webhook data"]}]

    def list_versions(self):
        return [copy.deepcopy(self.prior)] + ([copy.deepcopy(self.new)] if self.new else [])

    def get_version(self, version_id):
        if version_id == gate.PRIOR_VERSION_ID:
            return copy.deepcopy(self.prior)
        if self.new and version_id == self.new["id"]:
            return copy.deepcopy(self.new)
        raise AssertionError("Unknown fake version")

    def create_version(self, payload, jar):
        self.created += 1
        assert jar == self.jar
        self.new = {
            **{key: payload[key] for key in ("project_id", "name", "version_number",
                                          "changelog", "game_versions", "loaders",
                                          "version_type", "status", "environment")},
            "id": "BBTest11", "files": [{"primary": True, "hashes": {
                "sha512": gate.CANDIDATE_SHA512,
                "sha1": hashlib.sha1(jar.read_bytes()).hexdigest(),
            }}],
        }
        return self.new["id"]


class ReleaseGateTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.jar = self.base / "frozen/release/ByteBans-1.1.0.jar"
        self.helper = self.base / "frozen/release/bytebans-private-probe-1.0.0.jar"
        self.jar.parent.mkdir(parents=True)
        self.helper.parent.mkdir(parents=True, exist_ok=True)
        self.jar.write_bytes(b"test-only candidate JAR bytes")
        self.helper.write_bytes(b"test-only probe JAR bytes")
        self.candidate_sha = hashlib.sha512(self.jar.read_bytes()).hexdigest()
        self.helper_sha = hashlib.sha512(self.helper.read_bytes()).hexdigest()
        self.source = "a" * 40
        self.patches = [
            patch.object(gate, "CANDIDATE_SHA512", self.candidate_sha),
            patch.object(gate, "PROBE_SHA512", self.helper_sha),
            patch.object(gate, "SOURCE_REVISION", self.source),
        ]
        for item in self.patches:
            item.start()
            self.addCleanup(item.stop)
        write_json(self.base / "frozen/release/manifest.json", {
            "source_revision": self.source, "sha512": self.candidate_sha,
        })
        shutil.copyfile(gate.HERE / "matrix.json", self.base / "matrix.json")
        matrix = json.loads((self.base / "matrix.json").read_text(encoding="utf-8"))
        self.targets = matrix["targets"]
        self.matrix_folder = self.base / "runs/20261001T220000Z"
        self.matrix_folder.mkdir(parents=True)
        for index, target in enumerate(self.targets):
            self._write_target(index, target)
        self._write_client()
        self._write_web()
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
        write_json(self.base / "runs/20261001T220200Z-admin-web/result.json", {
            "source_revision": self.source, "candidate_sha512": self.candidate_sha,
            "status": "pass", "server_stopped": True,
            "config_restored_disabled": True, "plugin_initialized": True,
            "panel_bound_loopback": True, "instance_id": "mw-1111111111111111",
            "version": "1.21.4", "paper_build": 232,
            "paper_sha256": gate.PAPER_1214_SHA256, "java": 21,
            "script_sha256": "b" * 64, "recorded_at": "2026-10-01T22:06:00+00:00",
            "checks": sorted(gate.EXPECTED_WEB_CHECKS),
        })

    def test_complete_exact_evidence_can_form_paper_only_plan(self):
        plan = gate.audit(base=self.base)
        self.assertTrue(plan["ready"], plan["issues"])
        self.assertEqual(15, len(plan["qualified_versions"]))
        self.assertEqual(["paper"], plan["payload"]["loaders"])
        self.assertEqual("listed", plan["payload"]["status"])
        self.assertEqual(30, len([key for key in plan["receipts"] if key.startswith("paper-")]))
        self.assertEqual(3, len([key for key in plan["receipts"]
                                 if key.startswith("real_offline_client_")]))

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

    def test_changed_candidate_or_missing_human_review_fails_closed(self):
        self.jar.write_bytes(b"different JAR")
        self.assertFalse(gate.audit(base=self.base)["ready"])
        self.jar.write_bytes(b"test-only candidate JAR bytes")
        (self.base / "runs/ai-review.json").unlink()
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
        self.assertEqual("unlisted", plan["payload"]["status"])
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
        self.assertIsNone(plan["payload"])

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
        self.assertEqual("created", first["action"])
        self.assertEqual(1, client.created)
        self.assertEqual(prior, client.prior)
        second = gate.publish(base=self.base, client=client, user_requested_upload=True)
        self.assertEqual(first, second)
        self.assertEqual(1, client.created)

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
        payload = gate.audit(base=self.base)["payload"]
        client.create_version(payload, self.jar)
        client.created = 0
        client.new["loaders"] = ["paper", "spigot"]
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
