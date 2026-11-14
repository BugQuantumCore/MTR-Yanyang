"""Release verification and draft creation. Run only in GitHub Actions."""

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import sys
from urllib.error import HTTPError
from urllib.parse import quote, urlparse
from urllib.request import HTTPRedirectHandler, Request, build_opener, urlopen
from zipfile import ZIP_DEFLATED, ZipFile


WORKFLOW_PATH = ".github/workflows/native_1.21.x.yml"
PROJECTS = ("1.21.1-fabric", "1.21.1-neoforge", "1.21.4-fabric", "1.21.4-neoforge")
LIBRARIES = {
    "windows-x64": "wrvk.dll", "windows-arm64": "wrvk.dll",
    "linux-x64": "libwrvk.so", "linux-arm64": "libwrvk.so",
    "macos-arm64": "libwrvk.dylib", "android-arm64": "libwrvk.so",
    "ios-arm64": "libwrvk.dylib", "ipados-arm64": "libwrvk.dylib",
}
SHADERS = tuple(f"{name}.{stage}.spv" for name in ("entity_cutout", "entity_translucent", "beacon_beam") for stage in ("vert", "frag"))
BUNDLE_TO_JAR = {f"natives/{platform}/{library}": f"native/{platform}/{library}" for platform, library in LIBRARIES.items()}
BUNDLE_TO_JAR.update({f"native/shaders/spirv/{name}": f"shaders/spirv/{name}" for name in SHADERS})
ARTIFACT_NAMES = ("YYMTR-release-jars", "wrvk-natives")
MAX_ARCHIVE_BYTES = 4 * 1024**3
MAX_EXTRACTED_BYTES = 8 * 1024**3


def require(condition, message):
    if not condition:
        raise ValueError(message)


def validate_config(config):
    require(isinstance(config, dict), "Release configuration must be an object")
    run_id = str(config.get("run_id", ""))
    source_sha = config.get("source_sha", "")
    tag = config.get("tag", "")
    title = config.get("title", "")
    require(re.fullmatch(r"[1-9][0-9]*", run_id), "run_id must be a positive integer")
    require(isinstance(source_sha, str) and re.fullmatch(r"[0-9a-f]{40}", source_sha), "source_sha must be a full lowercase commit SHA")
    require(isinstance(tag, str) and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+-]{0,127}", tag), "Invalid release tag")
    require(".." not in tag and not tag.endswith((".", ".lock")), "Invalid release tag")
    require(isinstance(title, str) and 0 < len(title) <= 500 and not any(ord(c) < 32 or ord(c) == 127 for c in title), "Invalid release title")
    require(isinstance(config.get("prerelease", False), bool), "prerelease must be a boolean")
    require(isinstance(config.get("notes", ""), str), "notes must be text")
    require(isinstance(config.get("notes_file", ""), str), "notes_file must be text")
    return {**config, "run_id": int(run_id), "source_sha": source_sha, "tag": tag, "title": title, "prerelease": config.get("prerelease", False)}


def validate_run(run, workflow, config, repository):
    require(run.get("id") == config["run_id"], "Unexpected build run ID")
    require(run.get("repository", {}).get("full_name") == repository, "Build run belongs to another repository")
    require(run.get("head_repository", {}).get("full_name") == repository, "Build run uses a different source repository")
    require(workflow.get("path") == WORKFLOW_PATH and run.get("path") == WORKFLOW_PATH and run.get("workflow_id") == workflow.get("id"), "Build run is not the native release workflow")
    require(run.get("status") == "completed" and run.get("conclusion") == "success", "Build run must have completed successfully")
    require(run.get("event") == "push" and run.get("head_branch") == "1.21.x", "Release requires a formal push build on 1.21.x")
    require(run.get("head_sha") == config["source_sha"], "Build run does not match source_sha")


def validate_ancestor(comparison, source_sha):
    require(comparison.get("status") in ("ahead", "identical") and comparison.get("merge_base_commit", {}).get("sha") == source_sha and comparison.get("base_commit", {}).get("sha") == source_sha,
            "source_sha must be an ancestor of the release configuration commit")


def validate_build_configuration(properties, workflow_source, config):
    versions = re.findall(r"(?m)^\s*mod\.version\s*=\s*([^\r\n]+?)\s*$", properties)
    require(len(versions) == 1 and versions[0] == config["tag"], "Release tag must equal source gradle.properties mod.version")
    core_refs = re.findall(r"(?m)^\s*ref:\s*['\"]?([0-9a-f]{40})['\"]?\s*\n\s*path:\s*Transport-Simulation-Core\s*$", workflow_source)
    require(len(core_refs) == 1, "Native workflow must pin Transport-Simulation-Core to one immutable commit")
    return {"mod_version": versions[0], "transport_simulation_core_sha": core_refs[0]}


def validate_jobs(jobs):
    required = (set(LIBRARIES) - {"ipados-arm64"}) | {"compile-spirv", "assemble", "build-mod"}
    for name in required:
        matches = [job for job in jobs if job.get("name") == name]
        require(len(matches) == 1 and matches[0].get("conclusion") == "success", f"Required build job did not succeed: {name}")
    build = next(job for job in jobs if job.get("name") == "build-mod")
    for name in ("Verify the packaged natives (all four targets, all eight platforms)", "Upload mod jars"):
        require(any(step.get("name") == name and step.get("conclusion") == "success" for step in build.get("steps", [])), f"Required packaging step did not succeed: {name}")


def select_artifacts(artifacts, run):
    selected = {}
    repository_id = run["repository"]["id"]
    for name in ARTIFACT_NAMES:
        matches = [artifact for artifact in artifacts if artifact.get("name") == name]
        require(len(matches) == 1, f"Expected exactly one artifact named {name}")
        artifact = matches[0]
        origin = artifact.get("workflow_run", {})
        require(not artifact.get("expired", True), f"Artifact has expired: {name}")
        require(origin.get("id") == run["id"] and origin.get("head_sha") == run["head_sha"] and origin.get("head_branch") == "1.21.x" and origin.get("repository_id") == repository_id and origin.get("head_repository_id") == repository_id,
                f"Artifact provenance does not match the build run: {name}")
        require(isinstance(artifact.get("id"), int) and artifact["id"] > 0, f"Invalid artifact ID: {name}")
        require(isinstance(artifact.get("size_in_bytes"), int) and 0 < artifact["size_in_bytes"] <= MAX_ARCHIVE_BYTES, f"Invalid artifact size: {name}")
        require(re.fullmatch(r"sha256:[0-9a-f]{64}", artifact.get("digest") or ""), f"Artifact is missing a SHA-256 archive digest: {name}")
        selected[name] = artifact
    return selected


def hash_stream(stream):
    digest = hashlib.sha256()
    size = 0
    for chunk in iter(lambda: stream.read(1024 * 1024), b""):
        digest.update(chunk)
        size += len(chunk)
    return {"sha256": digest.hexdigest(), "size": size}


def hash_file(path):
    with path.open("rb") as stream:
        return hash_stream(stream)


def verify_archive_digest(path, artifact):
    result = hash_file(path)
    require("sha256:" + result["sha256"] == artifact["digest"], f"Downloaded artifact archive digest mismatch: {artifact['name']}")
    return result


def checked_members(archive):
    members = archive.infolist()
    names = [entry.filename for entry in members]
    require(len(names) == len(set(names)), "ZIP contains duplicate entries")
    total = 0
    for entry in members:
        name = entry.filename.rstrip("/") if entry.is_dir() else entry.filename
        parts = name.split("/")
        require(name and "\\" not in name and ":" not in name and not any(part in ("", ".", "..") for part in parts), "ZIP contains an unsafe path")
        require(not any(ord(c) < 32 or ord(c) == 127 for c in name), "ZIP path contains control characters")
        mode = entry.external_attr >> 16
        require(not stat.S_ISLNK(mode), "ZIP contains a symbolic link")
        require(stat.S_IFMT(mode) in (0, stat.S_IFREG, stat.S_IFDIR), "ZIP contains a special file")
        require(not entry.flag_bits & 1, "Encrypted ZIP entries are not supported")
        total += entry.file_size
        require(total <= MAX_EXTRACTED_BYTES, "ZIP expands beyond the release size budget")
    return members


def extract_archive(path, destination):
    destination.mkdir(parents=True, exist_ok=False)
    with ZipFile(path) as archive:
        for entry in checked_members(archive):
            if entry.is_dir():
                continue
            target = destination.joinpath(*PurePosixPath(entry.filename).parts)
            target.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(entry) as source, target.open("xb") as output:
                shutil.copyfileobj(source, output, 1024 * 1024)


def verify_payloads(jars_directory, natives_directory, mod_version):
    native_files = {path.relative_to(natives_directory).as_posix(): path for path in natives_directory.rglob("*") if path.is_file()}
    require(set(native_files) == set(BUNDLE_TO_JAR), "Native bundle must contain exactly the eight libraries and six named shaders")
    expected = {jar_name: hash_file(native_files[bundle_name]) for bundle_name, jar_name in BUNDLE_TO_JAR.items()}
    require(all(value["size"] > 0 for value in expected.values()), "Native libraries and shaders must not be empty")
    require(expected["native/ios-arm64/libwrvk.dylib"] == expected["native/ipados-arm64/libwrvk.dylib"], "iPadOS library must equal the iOS library")
    all_files = [path for path in jars_directory.rglob("*") if path.is_file()]
    require(all(path.suffix == ".jar" and len(path.relative_to(jars_directory).parts) == 2 and path.parent.name in PROJECTS for path in all_files), "Unexpected file or directory in the JAR artifact")
    jars = []
    for project in PROJECTS:
        files = [path for path in all_files if path.parent.name == project]
        sources = [path for path in files if path.name.endswith("-sources.jar")]
        binaries = [path for path in files if not path.name.endswith("-sources.jar")]
        require(len(binaries) == 1 and (project.endswith("neoforge") or len(sources) == 1), f"Expected one binary and, for Fabric, one sources JAR for {project}")
        binary = binaries[0]
        suffix = "-all" if project.endswith("neoforge") else ""
        require(binary.name == f"mtr-{mod_version}+{project}{suffix}.jar", f"JAR filename does not match the source version and target: {project}")
        with ZipFile(binary) as archive:
            members = checked_members(archive)
            names = {entry.filename for entry in members if not entry.is_dir()}
            metadata = "META-INF/neoforge.mods.toml" if project.endswith("neoforge") else "fabric.mod.json"
            require({"META-INF/MANIFEST.MF", "org/mtr/MTR.class", metadata} <= names, f"Incomplete mod JAR: {project}")
            embedded = {name for name in names if name.startswith(("native/", "shaders/spirv/"))}
            require(embedded == set(expected), f"Missing or unexpected native/shader entries: {project}")
            for name, value in expected.items():
                with archive.open(name) as stream:
                    require(hash_stream(stream) == value, f"Native/shader content mismatch: {project}:{name}")
            require(archive.testzip() is None, f"Corrupt JAR entry: {project}")
        for source in sources:
            with ZipFile(source) as archive:
                members = checked_members(archive)
                require(not any(entry.filename.startswith(("native/", "shaders/spirv/")) for entry in members), f"Binary data leaked into sources JAR: {project}")
                require(archive.testzip() is None, f"Corrupt sources JAR: {project}")
        jars.append({"project": project, "name": binary.name, "path": str(binary), **hash_file(binary)})
    require(len({jar["name"] for jar in jars}) == len(PROJECTS), "Release asset names collide")
    return jars, expected


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        return None


class GitHub:
    def __init__(self):
        self.repository = os.environ["GITHUB_REPOSITORY"]
        self.base = os.environ.get("GITHUB_API_URL", "https://api.github.com").rstrip("/")
        self.token = os.environ["GH_TOKEN"]
        require(urlparse(self.base).scheme == "https", "GitHub API must use HTTPS")

    def request(self, endpoint, payload=None):
        return Request(self.base + "/repos/" + self.repository + "/" + endpoint,
                       data=None if payload is None else json.dumps(payload).encode("utf-8"),
                       headers={"Authorization": "Bearer " + self.token, "Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28", "Content-Type": "application/json"},
                       method="GET" if payload is None else "POST")

    def api(self, endpoint, payload=None, missing_ok=False):
        try:
            with urlopen(self.request(endpoint, payload), timeout=60) as response:
                return json.load(response)
        except HTTPError as error:
            if missing_ok and error.code == 404:
                return None
            raise ValueError(f"GitHub API request failed ({error.code}): {endpoint}") from None

    def pages(self, endpoint, key):
        output = []
        for page in range(1, 101):
            separator = "&" if "?" in endpoint else "?"
            response = self.api(f"{endpoint}{separator}per_page=100&page={page}")
            result = response if key is None else response[key]
            require(isinstance(result, list), "GitHub pagination did not return a list")
            output.extend(result)
            if len(result) < 100:
                return output
        raise ValueError("GitHub pagination exceeded the release budget")

    def download(self, artifact, path):
        # Never forward the repository token to GitHub's signed blob URL.
        try:
            response = build_opener(NoRedirect()).open(self.request(f"actions/artifacts/{artifact['id']}/zip"), timeout=60)
        except HTTPError as error:
            if error.code not in (301, 302, 303, 307, 308):
                raise ValueError(f"Artifact download failed ({error.code}): {artifact['name']}") from None
            location = error.headers.get("Location", "")
            error.close()
            require(urlparse(location).scheme == "https", "Artifact download redirect must use HTTPS")
            response = urlopen(Request(location), timeout=120)
        total = 0
        with response, path.open("xb") as output:
            for chunk in iter(lambda: response.read(1024 * 1024), b""):
                total += len(chunk)
                require(total <= MAX_ARCHIVE_BYTES, "Artifact download exceeds the size budget")
                output.write(chunk)
        verify_archive_digest(path, artifact)


def no_existing_release(github, tag):
    encoded_tag = quote(tag, safe="")
    # The by-tag REST endpoint omits drafts. Authenticated listing includes
    # drafts, including one whose git tag has not been created yet.
    require(not any(item.get("tag_name") == tag for item in github.pages("releases", None)), "A release or draft already exists for this tag; refusing to overwrite it")
    require(github.api(f"git/ref/tags/{encoded_tag}", missing_ok=True) is None, "A tag already exists; refusing to reuse or move it")


def source_file(github, path, source_sha):
    result = github.api(f"contents/{path}?ref={source_sha}")
    require(result.get("type") == "file" and result.get("path") == path and result.get("encoding") == "base64", f"Cannot read immutable source file: {path}")
    require(0 < result.get("size", 0) < 1024 * 1024, f"Unexpected source file size: {path}")
    return base64.b64decode("".join(result["content"].split()), validate=True).decode("utf-8")


def verify_source(github, config):
    release_sha = os.environ["GITHUB_SHA"]
    require(re.fullmatch(r"[0-9a-f]{40}", release_sha), "Invalid release workflow commit")
    require(os.environ.get("GITHUB_EVENT_NAME") == "push" and os.environ.get("GITHUB_REF") == "refs/heads/1.21.x", "Release verifier must run on a 1.21.x push")
    workflow = github.api("actions/workflows/native_1.21.x.yml")
    run = github.api(f"actions/runs/{config['run_id']}")
    validate_run(run, workflow, config, github.repository)
    validate_ancestor(github.api(f"compare/{config['source_sha']}...{release_sha}"), config["source_sha"])
    build_configuration = validate_build_configuration(source_file(github, "gradle.properties", config["source_sha"]), source_file(github, WORKFLOW_PATH, config["source_sha"]), config)
    validate_jobs(github.pages(f"actions/runs/{config['run_id']}/jobs?filter=latest", "jobs"))
    artifacts = select_artifacts(github.pages(f"actions/runs/{config['run_id']}/artifacts", "artifacts"), run)
    return run, artifacts, build_configuration


def release_notes(config):
    name = config.get("notes_file", "")
    if not name:
        return config.get("notes", "")
    directory = Path(".github/release").resolve()
    path = Path(name) if name.startswith(".github/release/") else directory / name
    path = path.resolve()
    require(path.is_relative_to(directory) and path.is_file(), "notes_file must be inside .github/release")
    return path.read_text(encoding="utf-8")


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def verify(config_path, staging):
    config = validate_config(json.loads(config_path.read_text(encoding="utf-8")))
    github = GitHub()
    run, artifacts, build_configuration = verify_source(github, config)
    no_existing_release(github, config["tag"])
    staging.mkdir(parents=True, exist_ok=False)
    for name, artifact in artifacts.items():
        path = staging / (name + ".zip")
        github.download(artifact, path)
        extract_archive(path, staging / name)
    jars, embedded = verify_payloads(staging / "YYMTR-release-jars", staging / "wrvk-natives", build_configuration["mod_version"])
    assets = staging / "assets"
    assets.mkdir()
    for jar in jars:
        shutil.copyfile(jar["path"], assets / jar["name"])
        del jar["path"]
    with ZipFile(assets / "wrvk-natives-all-platforms.zip", "w", compression=ZIP_DEFLATED) as archive:
        for name in sorted(BUNDLE_TO_JAR):
            archive.write(staging / "wrvk-natives" / name, name)
    payload_assets = {path.name: hash_file(path) for path in sorted(assets.iterdir())}
    provenance = {
        "schema_version": 1, "repository": github.repository,
        "source_sha": config["source_sha"], "tag": config["tag"],
        "build_configuration": build_configuration,
        "source_run": {key: run.get(key) for key in ("id", "run_attempt", "html_url", "event", "head_branch", "head_sha", "workflow_id", "path", "created_at", "updated_at")},
        "release_workflow": {"run_id": os.environ["GITHUB_RUN_ID"], "run_attempt": os.environ.get("GITHUB_RUN_ATTEMPT", "1"), "sha": os.environ["GITHUB_SHA"]},
        "input_artifacts": {name: {key: artifact.get(key) for key in ("id", "name", "digest", "size_in_bytes", "created_at", "workflow_run")} for name, artifact in artifacts.items()},
        "checks": {"archive_sha256_verified": True, "four_jar_payloads_verified": True, "source_is_release_commit_ancestor": True, "duplicate_zip_entries_rejected": True},
        "embedded_files": embedded, "jars": jars, "assets": payload_assets,
        "validation_boundary": "Build/protocol tests and artifact integrity only; no claim of real-device Vulkan, Radiance, mobile gameplay or FPS validation.",
    }
    write_json(assets / "build-provenance.json", provenance)
    checksums = {path.name: hash_file(path) for path in sorted(assets.iterdir())}
    (assets / "SHA256SUMS").write_text("".join(f"{value['sha256']}  {name}\n" for name, value in checksums.items()), encoding="utf-8")
    (staging / "notes.md").write_text(release_notes(config), encoding="utf-8")
    write_json(staging / "verified-release.json", {"config": config, "build_configuration": build_configuration, "artifact_ids": {name: artifact["id"] for name, artifact in artifacts.items()}, "assets": {path.name: hash_file(path) for path in sorted(assets.iterdir())}})
    print("Verified formal source build, both archive digests, and four JARs with eight libraries and six shaders each.")


def draft(staging):
    state = json.loads((staging / "verified-release.json").read_text(encoding="utf-8"))
    config = validate_config(state["config"])
    github = GitHub()
    _, artifacts, build_configuration = verify_source(github, config)
    require(build_configuration == state["build_configuration"], "Build configuration changed after verification")
    require({name: artifact["id"] for name, artifact in artifacts.items()} == state["artifact_ids"], "Build artifacts changed after verification")
    assets = staging / "assets"
    require({path.name: hash_file(path) for path in assets.iterdir()} == state["assets"], "Release assets changed after verification")
    no_existing_release(github, config["tag"])
    # A concurrent tag creation is an API error, never an implicit --target
    # override or force-update. Failed draft creation leaves a tag for review.
    github.api("git/refs", {"ref": "refs/tags/" + config["tag"], "sha": config["source_sha"]})
    command = ["gh", "release", "create", config["tag"], "--repo", github.repository, "--draft", "--verify-tag", "--target", config["source_sha"], "--title", config["title"], "--notes-file", str(staging / "notes.md")]
    if config["prerelease"]:
        command.append("--prerelease")
    command.extend(str(assets / name) for name in sorted(state["assets"]))
    subprocess.run(command, check=True)
    matching_releases = [item for item in github.pages("releases", None) if item.get("tag_name") == config["tag"]]
    require(len(matching_releases) == 1, "Cannot identify the unique newly created draft; do not publish")
    release = github.api(f"releases/{matching_releases[0]['id']}")
    ref = github.api("git/ref/tags/" + quote(config["tag"], safe=""))
    require(release.get("draft") is True and ref.get("object", {}).get("type") == "commit" and ref["object"].get("sha") == config["source_sha"], "Draft tag or release state changed unexpectedly; do not publish")
    uploaded = release.get("assets", [])
    require(len(uploaded) == len(state["assets"]) and {asset["name"] for asset in uploaded} == set(state["assets"]), "Draft release asset set is incomplete; do not publish")
    for asset in uploaded:
        expected = state["assets"][asset["name"]]
        require(asset.get("size") == expected["size"], "Draft release asset size mismatch; do not publish")
        if asset.get("digest"):
            require(asset["digest"] == "sha256:" + expected["sha256"], "Draft release asset digest mismatch; do not publish")
    print(json.dumps({"release": release["html_url"], "draft": True, "source_sha": config["source_sha"], "assets": sorted(state["assets"])}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("verify", "draft"))
    parser.add_argument("--config", type=Path, default=Path(".github/release/config.json"))
    parser.add_argument("--staging", type=Path, default=Path(".release-staging"))
    arguments = parser.parse_args()
    try:
        if arguments.mode == "verify":
            verify(arguments.config, arguments.staging)
        else:
            draft(arguments.staging)
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        print(f"Release verification failed: {error}", file=sys.stderr)
        sys.exit(1)
