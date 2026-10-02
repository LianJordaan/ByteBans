"""Offline tests for the two-version ByteBans public-promotion gate."""

from __future__ import annotations

import copy
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch

import promote_public as public
import release_gate as gate
import set_disclosures


class FakeClient:
    def __init__(self, plan: dict, publication: dict, body: str):
        self.project = {"id": gate.PROJECT_ID, "status": "approved", "body": body}
        self.disclosures = copy.deepcopy(set_disclosures.DESIRED)
        prior = publication["prior_version"]
        self.old = {**prior, "project_types": ["plugin"],
                    "files": [{"primary": True, "hashes": {
                        "sha512": gate.PRIOR_VERSION_SHA512}}]}
        self.versions = {}
        for payload in plan["payloads"]:
            number = payload["version_number"]
            jar = Path(plan["candidate_file"] if number == gate.VERSION_NUMBER
                       else plan["companion_file"])
            self.versions[public.PINNED_IDS[number]] = {
                "id": public.PINNED_IDS[number], "project_id": gate.PROJECT_ID,
                "project_types": ["plugin"], "version_number": number,
                "name": payload["name"], "version_type": payload["version_type"],
                "status": "unlisted", "changelog": payload["changelog"],
                "game_versions": payload["game_versions"],
                "loaders": payload["loaders"],
                "files": [{"primary": True, "hashes": {
                    "sha512": gate.digest(jar), "sha1": gate.digest(jar, "sha1")}}]}
        self.patches = []
        self.fail_id = None

    def get_project(self):
        return copy.deepcopy(self.project)

    def get_disclosures(self):
        return copy.deepcopy(self.disclosures)

    def get_version(self, version_id):
        return copy.deepcopy(self.old if version_id == gate.PRIOR_VERSION_ID
                             else self.versions[version_id])

    def find_version_by_hash(self, sha512):
        return next((copy.deepcopy(row) for row in self.versions.values()
                     if row["files"][0]["hashes"]["sha512"] == sha512), None)

    def list_versions(self):
        return [copy.deepcopy(self.old)] + [copy.deepcopy(row)
                                            for row in self.versions.values()
                                            if row["status"] == "listed"]

    def set_listed(self, version_id):
        if version_id == self.fail_id:
            raise RuntimeError("simulated Modrinth failure")
        self.patches.append(version_id)
        self.versions[version_id]["status"] = "listed"


class PublicPromotionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.plan = gate.audit()
        assert cls.plan["ready"], cls.plan["issues"]
        cls.publication = gate.safe_json(public.HERE / "runs/publication.json")
        cls.body = (public.HERE / "modrinth-project-body.md").read_text(encoding="utf-8")
        cls.provenance = public.provenance_summary()

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        (self.base / "runs").mkdir()
        shutil.copyfile(public.HERE / "runs/publication.json", self.base / "runs/publication.json")
        (self.base / "modrinth-project-body.md").write_text(self.body, encoding="utf-8")
        self.client = FakeClient(self.plan, self.publication, self.body)
        self.audit_patch = patch.object(gate, "audit", return_value=self.plan)
        self.provenance_patch = patch.object(public, "provenance_summary",
                                             return_value=self.provenance)
        self.audit_patch.start()
        self.provenance_patch.start()
        self.addCleanup(self.audit_patch.stop)
        self.addCleanup(self.provenance_patch.stop)

    def run_promotion(self, apply=False, owner=True):
        return public.promote(apply=apply, owner_confirmed=owner,
                              base=self.base, client=self.client)

    def test_dry_run_is_read_only_and_owner_confirmation_is_required(self):
        with self.assertRaisesRegex(ValueError, "Owner confirmation"):
            self.run_promotion(owner=False)
        result = self.run_promotion()
        self.assertTrue(result["ready"])
        self.assertEqual(set(public.PINNED_IDS), set(result["actions"]))
        self.assertEqual([], self.client.patches)
        self.assertFalse((self.base / "runs/public-promotion.json").exists())

    def test_lists_both_once_and_preserves_original(self):
        old = self.client.get_version(gate.PRIOR_VERSION_ID)
        receipt = self.run_promotion(apply=True)
        self.assertEqual("listed", receipt["status"])
        self.assertEqual(set(public.PINNED_IDS.values()), set(self.client.patches))
        self.assertEqual(old, self.client.get_version(gate.PRIOR_VERSION_ID))
        second = self.run_promotion(apply=True)
        self.assertEqual(receipt, second)
        self.assertEqual(2, len(self.client.patches))

    def test_partial_remote_success_retries_only_remaining_version(self):
        self.client.fail_id = public.PINNED_IDS[gate.PAPER_EXCEPTION_NUMBER]
        with self.assertRaisesRegex(RuntimeError, "simulated"):
            self.run_promotion(apply=True)
        self.assertEqual("listed", self.client.versions[
            public.PINNED_IDS[gate.VERSION_NUMBER]]["status"])
        self.assertFalse((self.base / "runs/public-promotion.json").exists())
        self.client.fail_id = None
        self.run_promotion(apply=True)
        self.assertEqual(2, len(self.client.patches))

    def test_wrong_jar_metadata_or_disclosure_blocks_all_patches(self):
        main_id = public.PINNED_IDS[gate.VERSION_NUMBER]
        self.client.versions[main_id]["loaders"] = ["paper", "purpur", "spigot"]
        with self.assertRaisesRegex(ValueError, "hash, loaders"):
            self.run_promotion(apply=True)
        self.assertEqual([], self.client.patches)
        self.client.versions[main_id]["loaders"] = ["paper", "purpur"]
        self.client.disclosures[0]["note"] = "outdated"
        with self.assertRaisesRegex(ValueError, "disclosures"):
            self.run_promotion(apply=True)
        self.assertEqual([], self.client.patches)

    def test_conflicting_receipt_and_hash_lookup_block(self):
        main_id = public.PINNED_IDS[gate.VERSION_NUMBER]
        self.client.versions[main_id]["files"][0]["hashes"]["sha512"] = "a" * 128
        with self.assertRaisesRegex(ValueError, "hash, loaders"):
            self.run_promotion(apply=True)
        self.assertEqual([], self.client.patches)
        self.client = FakeClient(self.plan, self.publication, self.body)
        path = self.base / "runs/publication.json"
        row = json.loads(path.read_text(encoding="utf-8"))
        row["versions"][0]["version_id"] = "other123"
        path.write_text(json.dumps(row), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "publication receipt|published version"):
            self.run_promotion(apply=True)
        self.assertEqual([], self.client.patches)


if __name__ == "__main__":
    unittest.main()
