"""Synthetic fixtures only: no network, game, compiler or release writes."""

import importlib.util
import io
import json
from pathlib import Path
import stat
import tempfile
import unittest
from unittest import mock
import warnings
from zipfile import ZipFile, ZipInfo


SPEC = importlib.util.spec_from_file_location("release_verify", Path(__file__).parents[1] / "release_verify.py")
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)

SHA = "a" * 40
REPOSITORY = "example/project"
CONFIG = {"run_id": 123, "source_sha": SHA, "tag": "1.2.3-test.1", "title": "Release fixture"}
BUILD_CONFIGURATION = {"mod_version": CONFIG["tag"], "transport_simulation_core_sha": "6" * 40}
WORKFLOW = {"id": 456, "path": release.WORKFLOW_PATH}
RUN = {
    "id": 123, "workflow_id": 456, "path": release.WORKFLOW_PATH,
    "repository": {"id": 789, "full_name": REPOSITORY},
    "head_repository": {"id": 789, "full_name": REPOSITORY},
    "status": "completed", "conclusion": "success", "event": "push",
    "head_branch": "1.21.x", "head_sha": SHA,
}


def artifact_fixtures():
    return [{
        "id": index + 1000, "name": name, "expired": False, "size_in_bytes": 10,
        "digest": "sha256:" + "1" * 64,
        "workflow_run": {"id": 123, "head_sha": SHA, "head_branch": "1.21.x", "repository_id": 789, "head_repository_id": 789},
    } for index, name in enumerate(release.ARTIFACT_NAMES)]


class SourceValidationTest(unittest.TestCase):
    def test_accepts_formal_build_and_full_configuration(self):
        config = release.validate_config({**CONFIG, "run_id": "123"})
        self.assertEqual(config["run_id"], 123)
        release.validate_run(RUN, WORKFLOW, config, REPOSITORY)
        release.validate_ancestor({"status": "ahead", "base_commit": {"sha": SHA}, "merge_base_commit": {"sha": SHA}}, SHA)
        self.assertEqual(set(release.select_artifacts(artifact_fixtures(), RUN)), set(release.ARTIFACT_NAMES))

    def test_rejects_wrong_or_unsuccessful_build_identity(self):
        for field, value in (("head_sha", "b" * 40), ("conclusion", "failure"), ("conclusion", "cancelled"), ("status", "in_progress"), ("event", "pull_request"), ("event", "workflow_dispatch"), ("head_branch", "topic"), ("workflow_id", 999), ("path", ".github/workflows/build_1.21.x.yml"), ("id", 999), ("head_repository", {"full_name": "other/fork"})):
            with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                release.validate_run({**RUN, field: value}, WORKFLOW, CONFIG, REPOSITORY)

    def test_rejects_invalid_configuration(self):
        for field, value in (("source_sha", "a" * 7), ("source_sha", ""), ("run_id", 0), ("run_id", True), ("tag", "../escape"), ("tag", "name.lock"), ("tag", "-option"), ("title", "line\nbreak"), ("prerelease", "false")):
            with self.subTest(field=field), self.assertRaises(ValueError):
                release.validate_config({**CONFIG, field: value})

    def test_rejects_diverged_or_wrong_ancestor(self):
        for comparison in ({"status": "diverged", "base_commit": {"sha": SHA}, "merge_base_commit": {"sha": SHA}}, {"status": "ahead", "base_commit": {"sha": SHA}, "merge_base_commit": {"sha": "b" * 40}}):
            with self.subTest(comparison=comparison), self.assertRaises(ValueError):
                release.validate_ancestor(comparison, SHA)

    def test_tag_matches_source_version_and_records_immutable_core_pin(self):
        workflow = "          ref: '" + "6" * 40 + "'\n          path: Transport-Simulation-Core\n"
        self.assertEqual(release.validate_build_configuration("mod.version=" + CONFIG["tag"] + "\n", workflow, CONFIG), BUILD_CONFIGURATION)
        with self.assertRaisesRegex(ValueError, "mod.version"):
            release.validate_build_configuration("mod.version=old-version\n", workflow, CONFIG)
        with self.assertRaisesRegex(ValueError, "immutable"):
            release.validate_build_configuration("mod.version=" + CONFIG["tag"] + "\n", "ref: tsc-1.21.x\npath: Transport-Simulation-Core\n", CONFIG)

    def test_rejects_artifacts_from_other_revision_or_expired(self):
        for field, value in (("head_sha", "b" * 40), ("id", 999), ("head_branch", "topic"), ("head_repository_id", 999)):
            artifacts = artifact_fixtures()
            artifacts[0]["workflow_run"][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                release.select_artifacts(artifacts, RUN)
        for field, value in (("expired", True), ("digest", None), ("size_in_bytes", 0)):
            artifacts = artifact_fixtures()
            artifacts[0][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                release.select_artifacts(artifacts, RUN)
        with self.assertRaises(ValueError):
            release.select_artifacts(artifact_fixtures()[:1], RUN)
        with self.assertRaises(ValueError):
            release.select_artifacts(artifact_fixtures() + artifact_fixtures()[:1], RUN)

    def test_requires_all_jobs_and_packaging_steps(self):
        names = (set(release.LIBRARIES) - {"ipados-arm64"}) | {"compile-spirv", "assemble", "build-mod"}
        jobs = [{"name": name, "conclusion": "success", "steps": []} for name in names]
        build = next(job for job in jobs if job["name"] == "build-mod")
        build["steps"] = [{"name": name, "conclusion": "success"} for name in ("Verify the packaged natives (all four targets, all eight platforms)", "Upload mod jars")]
        release.validate_jobs(jobs)
        with self.assertRaises(ValueError):
            release.validate_jobs([job for job in jobs if job["name"] != "macos-arm64"])
        build["steps"][0]["conclusion"] = "skipped"
        with self.assertRaises(ValueError):
            release.validate_jobs(jobs)

    def test_existing_release_or_tag_is_rejected(self):
        for occupied in ("draft", "tag"):
            github = mock.Mock()
            github.pages.return_value = [{"tag_name": CONFIG["tag"], "draft": True}] if occupied == "draft" else []
            github.api.return_value = {} if occupied == "tag" else None
            with self.subTest(occupied=occupied), self.assertRaises(ValueError):
                release.no_existing_release(github, CONFIG["tag"])


class PayloadValidationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.jars = self.root / "jars"
        self.natives = self.root / "natives-artifact"
        self.payloads = {}
        for bundle_name, jar_name in release.BUNDLE_TO_JAR.items():
            data = b"ios-shared-bytes" if "/ios-arm64/" in bundle_name or "/ipados-arm64/" in bundle_name else ("fixture:" + jar_name).encode()
            path = self.natives / bundle_name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
            self.payloads[jar_name] = data
        self.binaries = {}
        self.sources = {}
        for project in release.PROJECTS:
            directory = self.jars / project
            directory.mkdir(parents=True)
            suffix = "-all" if project.endswith("neoforge") else ""
            binary = directory / f"mtr-9.9+{project}{suffix}.jar"
            sources = directory / f"mtr-9.9+{project}-sources.jar"
            metadata = "META-INF/neoforge.mods.toml" if project.endswith("neoforge") else "fabric.mod.json"
            with ZipFile(binary, "w") as archive:
                for name, data in {**self.payloads, "META-INF/MANIFEST.MF": b"Manifest-Version: 1.0\n", "org/mtr/MTR.class": b"class-fixture", metadata: b"metadata-fixture"}.items():
                    archive.writestr(name, data)
            if project.endswith("fabric"):
                with ZipFile(sources, "w") as archive:
                    archive.writestr("org/mtr/MTR.java", "// source fixture")
            self.binaries[project] = binary
            self.sources[project] = sources

    def rewrite(self, path, remove=(), replace=None):
        with ZipFile(path) as archive:
            values = {entry.filename: archive.read(entry) for entry in archive.infolist() if entry.filename not in remove}
        values.update(replace or {})
        with ZipFile(path, "w") as archive:
            for name, data in values.items():
                archive.writestr(name, data)

    def verify(self):
        return release.verify_payloads(self.jars, self.natives, "9.9")

    def test_accepts_all_four_complete_targets(self):
        jars, embedded = self.verify()
        self.assertEqual({jar["project"] for jar in jars}, set(release.PROJECTS))
        self.assertEqual(len(embedded), 14)
        self.assertEqual(embedded["native/ios-arm64/libwrvk.dylib"], embedded["native/ipados-arm64/libwrvk.dylib"])

    def test_rejects_missing_library(self):
        self.rewrite(self.binaries["1.21.1-fabric"], remove=("native/windows-arm64/wrvk.dll",))
        with self.assertRaisesRegex(ValueError, "Missing or unexpected"):
            self.verify()

    def test_rejects_shader_hash_mismatch(self):
        self.rewrite(self.binaries["1.21.4-neoforge"], replace={"shaders/spirv/entity_cutout.frag.spv": b"stale-shader"})
        with self.assertRaisesRegex(ValueError, "content mismatch"):
            self.verify()

    def test_rejects_duplicate_jar_entry(self):
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with ZipFile(self.binaries["1.21.1-fabric"], "a") as archive:
                archive.writestr("native/linux-x64/libwrvk.so", b"duplicate")
        with self.assertRaisesRegex(ValueError, "duplicate"):
            self.verify()

    def test_rejects_extra_library_or_incomplete_mod(self):
        path = self.binaries["1.21.4-fabric"]
        self.rewrite(path, replace={"native/unexpected/libwrvk.so": b"extra"})
        with self.assertRaises(ValueError):
            self.verify()
        self.rewrite(path, remove=("native/unexpected/libwrvk.so", "org/mtr/MTR.class"))
        with self.assertRaisesRegex(ValueError, "Incomplete"):
            self.verify()

    def test_rejects_missing_or_extra_bundle_file(self):
        path = self.natives / "natives/linux-x64/libwrvk.so"
        data = path.read_bytes()
        path.unlink()
        with self.assertRaises(ValueError):
            self.verify()
        path.write_bytes(data)
        (self.natives / "unexpected.txt").write_text("extra")
        with self.assertRaises(ValueError):
            self.verify()

    def test_rejects_different_ipados_binary(self):
        (self.natives / "natives/ipados-arm64/libwrvk.dylib").write_bytes(b"different")
        with self.assertRaisesRegex(ValueError, "iPadOS"):
            self.verify()

    def test_rejects_missing_target_and_binary_leak_into_sources(self):
        self.rewrite(self.sources["1.21.1-fabric"], replace={"native/windows-x64/wrvk.dll": b"binary"})
        with self.assertRaisesRegex(ValueError, "leaked"):
            self.verify()
        self.rewrite(self.sources["1.21.1-fabric"], remove=("native/windows-x64/wrvk.dll",))
        self.binaries["1.21.4-neoforge"].unlink()
        with self.assertRaises(ValueError):
            self.verify()

    def test_optional_neoforge_sources_are_verified_if_present(self):
        path = self.sources["1.21.1-neoforge"]
        with ZipFile(path, "w") as archive:
            archive.writestr("org/mtr/MTR.java", "// optional source fixture")
        self.verify()
        self.rewrite(path, replace={"shaders/spirv/entity_cutout.vert.spv": b"leaked"})
        with self.assertRaisesRegex(ValueError, "leaked"):
            self.verify()

    def test_rejects_correct_target_with_wrong_version_filename(self):
        self.binaries["1.21.1-fabric"].rename(self.jars / "1.21.1-fabric/mtr-0.1+1.21.1-fabric.jar")
        with self.assertRaisesRegex(ValueError, "source version"):
            self.verify()

    def test_archive_digest_must_match_api_value(self):
        path = self.root / "archive.zip"
        path.write_bytes(b"fixture archive bytes")
        metadata = {"name": "fixture", "digest": "sha256:" + release.hash_file(path)["sha256"]}
        release.verify_archive_digest(path, metadata)
        path.write_bytes(b"changed")
        with self.assertRaisesRegex(ValueError, "digest mismatch"):
            release.verify_archive_digest(path, metadata)

    def test_rejects_unsafe_outer_zip_paths_and_symlinks(self):
        for name in ("../escape", "/absolute", "a/../b", "a\\b", "a//b"):
            buffer = io.BytesIO()
            with ZipFile(buffer, "w") as archive:
                archive.writestr(name, b"bad")
            with ZipFile(buffer) as archive, self.subTest(name=name), self.assertRaises(ValueError):
                release.checked_members(archive)
        buffer = io.BytesIO()
        with ZipFile(buffer, "w") as archive:
            entry = ZipInfo("link")
            entry.create_system = 3
            entry.external_attr = (stat.S_IFLNK | 0o777) << 16
            archive.writestr(entry, "outside")
        with ZipFile(buffer) as archive, self.assertRaisesRegex(ValueError, "symbolic link"):
            release.checked_members(archive)

    def test_safe_outer_archive_extracts_without_flattening(self):
        path = self.root / "outer.zip"
        with ZipFile(path, "w") as archive:
            archive.writestr("1.21.1-fabric/file.jar", b"fixture")
        output = self.root / "extracted"
        release.extract_archive(path, output)
        self.assertEqual((output / "1.21.1-fabric/file.jar").read_bytes(), b"fixture")


class DraftCreationTest(unittest.TestCase):
    def test_creates_only_exact_source_tag_and_draft_after_rechecking_assets(self):
        with tempfile.TemporaryDirectory() as directory:
            staging = Path(directory)
            assets = staging / "assets"
            assets.mkdir()
            payload = assets / "fixture.jar"
            payload.write_bytes(b"verified payload")
            (staging / "notes.md").write_text("Verified release notes")
            hashes = {payload.name: release.hash_file(payload)}
            artifacts = release.select_artifacts(artifact_fixtures(), RUN)
            state = {"config": CONFIG, "build_configuration": BUILD_CONFIGURATION, "artifact_ids": {name: item["id"] for name, item in artifacts.items()}, "assets": hashes}
            (staging / "verified-release.json").write_text(json.dumps(state))
            github = mock.Mock(repository=REPOSITORY)
            created = {"tag": False, "release": False}

            def api(endpoint, payload=None, missing_ok=False):
                if endpoint == "git/refs":
                    self.assertEqual(payload, {"ref": "refs/tags/" + CONFIG["tag"], "sha": SHA})
                    created["tag"] = True
                    return {}
                if endpoint.startswith("git/ref/tags/"):
                    return {"object": {"type": "commit", "sha": SHA}} if created["tag"] else None
                if endpoint == "releases/17":
                    return {"draft": True, "html_url": "fixture", "assets": [{"name": name, "size": item["size"], "digest": "sha256:" + item["sha256"]} for name, item in hashes.items()]} if created["release"] else None
                self.fail("Unexpected API call: " + endpoint)

            def command(arguments, check):
                self.assertTrue(created["tag"])
                self.assertIn("--draft", arguments)
                self.assertIn("--verify-tag", arguments)
                self.assertEqual(arguments[arguments.index("--target") + 1], SHA)
                self.assertNotIn("--clobber", arguments)
                created["release"] = True

            github.api.side_effect = api
            github.pages.side_effect = lambda endpoint, key: [{"id": 17, "tag_name": CONFIG["tag"], "draft": True}] if created["release"] else []
            with mock.patch.object(release, "GitHub", return_value=github), mock.patch.object(release, "verify_source", return_value=(RUN, artifacts, BUILD_CONFIGURATION)), mock.patch.object(release.subprocess, "run", side_effect=command):
                release.draft(staging)
            self.assertTrue(created["release"])
            payload.write_bytes(b"tampered after verification")
            with mock.patch.object(release, "GitHub", return_value=github), mock.patch.object(release, "verify_source", return_value=(RUN, artifacts, BUILD_CONFIGURATION)), mock.patch.object(release.subprocess, "run") as execution, self.assertRaisesRegex(ValueError, "changed after verification"):
                release.draft(staging)
            execution.assert_not_called()


if __name__ == "__main__":
    unittest.main()
