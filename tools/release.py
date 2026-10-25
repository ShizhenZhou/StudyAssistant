"""Resumable local coordinator: basic check -> main CI -> local signing -> publication.
No action runs merely by importing this module. Signing secrets never enter GitHub requests.
"""
import argparse
from datetime import datetime, timezone, timedelta
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import uuid
from release_common import VERSION, apk_identity, apk_payload, extract_candidate, sha256, validate_manifest

ROOT = Path(__file__).resolve().parents[1]
REPO = "ShizhenZhou/StudyAssistant"
WORKFLOW = "build.yml"

class ReleaseError(RuntimeError): pass

def command(args, *, root=ROOT, data=None, log=None, timeout=120, check=True):
    result = subprocess.run([str(a) for a in args], cwd=root, input=data, capture_output=True, timeout=timeout)
    if log:
        Path(log).parent.mkdir(parents=True, exist_ok=True)
        Path(log).write_bytes(result.stdout + result.stderr)
    if check and result.returncode:
        raise ReleaseError(f"{'Local signing' if any(str(a).endswith('sign-release.ps1') for a in args) else Path(str(args[0])).name} failed; local exit code {result.returncode}. Inspect its local log; command arguments are withheld.")
    return result

class GitHub:
    def __init__(self, root=ROOT):
        self.root = root
        self.gh = shutil.which("gh") or r"D:\Program Files\GitHub CLI\bin\gh.exe"
    def call(self, *args, data=None):
        return command([self.gh, *args], root=self.root, data=data).stdout
    def api(self, path, value=None, method=None, missing=False):
        args = [self.gh, "api", f"repos/{REPO}/{path}"]
        if value is not None:
            args += ["--method", method or "POST", "--input", "-"]
        result = command(args, root=self.root, data=None if value is None else json.dumps(value).encode(), check=False)
        if result.returncode:
            if missing and b"HTTP 404" in result.stderr: return None
            raise ReleaseError("GitHub request failed; its outcome must be checked before retrying.")
        return json.loads(result.stdout) if result.stdout.strip() else None
    def download(self, path, binary=False):
        return self.call("api", path, "-H", "Accept: " + ("application/octet-stream" if binary else "application/json"))

def update_notes(text):
    marker = "## 更新内容"
    if text.count(marker) != 1: raise ReleaseError("Release notes need one 更新内容 section.")
    body = text.split(marker, 1)[1].split("\n## ", 1)[0].strip()
    if not body: raise ReleaseError("Release notes are empty.")
    return body

def recoverable_draft(release, state, body):
    return bool(state.get("draftAttempted") and release.get("draft") and
        release.get("tag_name") == "v" + state["version"] and release.get("target_commitish") == state["commit"] and
        release.get("body") == body and not release.get("assets"))

def local_artifact(root, relative):
    path = (Path(root) / relative).resolve()
    if not path.is_relative_to((Path(root) / ".local-notes/release-state").resolve()):
        raise ReleaseError("Local release state points outside its artifact directory.")
    return path

def bind_state(path, version, commit):
    if not VERSION.fullmatch(version) or not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ReleaseError("Invalid release identity.")
    if path.exists():
        state = json.loads(path.read_text(encoding="utf-8"))
        if state.get("schema") != 1 or (state.get("version"), state.get("commit")) != (version, commit):
            raise ReleaseError("This version is already bound to a different source. State was not overwritten.")
        return state
    return dict(schema=1, version=version, commit=commit)

class Release:
    def __init__(self, root=ROOT, github=None):
        self.root = Path(root)
        self.github = github or GitHub(self.root)
        self.state = None
        self.path = None
    def bind(self, version, commit):
        self.path = self.root / ".local-notes/release-state" / (version + ".json")
        self.state = bind_state(self.path, version, commit)
        self.save()
    def save(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temp = self.path.with_suffix(".tmp")
        temp.write_text(json.dumps(self.state, indent=2)+"\n", encoding="utf-8")
        temp.replace(self.path)
    def git(self, *args, check=True):
        return command(["git", *args], root=self.root, check=check)
    def remote(self, branch):
        return self.github.api("git/ref/heads/" + branch)["object"]["sha"]
    def push(self, branch, commit):
        if self.remote(branch) == commit: return
        result = self.git("push", "origin", f"{commit}:refs/heads/{branch}", check=False)
        if self.remote(branch) == commit: return
        transport = result.stderr.lower()
        if any(word in transport for word in [b"connection reset", b"timed out", b"failed to connect", b"http/2"]):
            self.git("-c", "http.version=HTTP/1.1", "push", "origin", f"{commit}:refs/heads/{branch}", check=False)
            if self.remote(branch) == commit: return
        raise ReleaseError("Push was not confirmed. Nothing was forced.")
    def start(self, version):
        if self.git("status", "--porcelain").stdout.strip(): raise ReleaseError("Commit/review local changes first.")
        commit = self.git("rev-parse", "HEAD").stdout.decode().strip()
        self.bind(version, commit)
        if self.state.get("dispatchAttempted"): return self.status()
        source = (self.root / "app/build.gradle.kts").read_text(encoding="utf-8")
        if re.search(r'val appVersionName = "([^" ]+)"', source)[1] != version:
            raise ReleaseError("Requested version differs from source.")
        if self.github.api("git/ref/tags/v" + version, missing=True):
            raise ReleaseError("Tag already exists. Existing tags/releases are never replaced automatically.")
        if b"[skip ci]" not in self.git("log", "-1", "--format=%B").stdout:
            raise ReleaseError("Use a reviewed release-preparation commit with [skip ci] to avoid duplicate development CI.")
        if not self.state.get("basicChecked"):
            command(["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", self.root / "tools/check-project.ps1"],
                root=self.root, log=self.path.parent / (version + "-basic.log"), timeout=600)
            self.state["basicChecked"] = True; self.save()
        for branch in ("main", "Develop"):
            head = self.remote(branch)
            if self.git("cat-file", "-e", head + "^{commit}", check=False).returncode:
                self.git("fetch", "origin", branch)
            if self.git("merge-base", "--is-ancestor", head, commit, check=False).returncode:
                raise ReleaseError(branch + " diverged; resolve locally before release.")
        self.git("switch", "main"); self.git("merge", "--ff-only", commit)
        self.push("main", commit); self.push("Develop", commit)
        self.state.update(dispatchAttempted=True, requestedAt=datetime.now(timezone.utc).isoformat())
        self.save()  # Persist before the request: a timeout must never trigger a blind second CI.
        self.github.api(f"actions/workflows/{WORKFLOW}/dispatches", {"ref":"main", "inputs":{"mode":"release", "expected_sha":commit}})
        return self.status()
    def status(self):
        if not self.state.get("runId") and self.state.get("dispatchAttempted"):
            runs = self.github.api(f"actions/workflows/{WORKFLOW}/runs?event=workflow_dispatch&branch=main&per_page=30")["workflow_runs"]
            threshold = datetime.fromisoformat(self.state["requestedAt"]) - timedelta(seconds=5)
            matches = [r for r in runs if r["head_sha"] == self.state["commit"] and r.get("display_title", "").startswith("release:")
                and datetime.fromisoformat(r["created_at"].replace("Z", "+00:00")) >= threshold]
            if len(matches) > 1: raise ReleaseError("Multiple runs match; select one explicitly with verify --run.")
            if matches: self.state["runId"] = matches[0]["id"]; self.save()
        if self.state.get("runId"):
            run = self.github.api(f"actions/runs/{self.state['runId']}")
            return dict(version=self.state["version"], commit=self.state["commit"], runId=run["id"], status=run["status"], conclusion=run["conclusion"], phase=self.state.get("phase", "ci"))
        return dict(version=self.state["version"], status="dispatch outcome pending; query status again, do not redispatch")

    def assert_source(self, commit):
        if self.remote("main") != commit:
            raise ReleaseError("main changed since CI. Do not publish a different source.")
        if self.git("rev-parse", "HEAD").stdout.decode().strip() != commit or self.git("status", "--porcelain").stdout.strip():
            raise ReleaseError("Local source must be clean and match the verified CI commit.")
    def signed_identity(self, path, manifest):
        sdk = Path(os.environ.get("ANDROID_HOME", "")) / "build-tools/36.0.0"
        result = command([os.environ.get("COMSPEC", "cmd.exe"), "/d", "/c", sdk / "apksigner.bat", "verify", "--verbose", "--print-certs", "--min-sdk-version", str(manifest["minSdk"]), path], root=self.root)
        text = result.stdout.decode(errors="replace")
        certificates = re.findall(r"Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-f]+)", text.lower(), re.IGNORECASE)
        if certificates != [manifest["expectedSigner"]] or "Verified using v3 scheme (APK Signature Scheme v3): true" not in text:
            raise ReleaseError("Signing identity or v3 lineage validation failed.")
        payload, stamp = apk_payload(path)
        if (payload, stamp) != (manifest["payloadSha256"], manifest["buildUtc"]):
            raise ReleaseError("Signing changed compiled code/resources. Nothing will be uploaded.")
    def verify(self, run_id):
        run = self.github.api(f"actions/runs/{run_id}")
        if run["event"] != "workflow_dispatch" or run["head_branch"] != "main" or run["conclusion"] != "success" or not run.get("display_title", "").startswith("release:"):
            raise ReleaseError("A successful main release-candidate run is required.")
        jobs = self.github.api(f"actions/runs/{run_id}/jobs")["jobs"]
        if not jobs or any(j["conclusion"] != "success" for j in jobs) or not any(j["name"] == "Android emulator tests" for j in jobs):
            raise ReleaseError("The full test workflow, including Android, must succeed.")
        commit = run["head_sha"]
        self.assert_source(commit)
        artifacts = self.github.api(f"actions/runs/{run_id}/artifacts?per_page=100")["artifacts"]
        found = [a for a in artifacts if a["name"] == "release-candidate" and not a["expired"]]
        if len(found) != 1: raise ReleaseError("Expected one unexpired candidate artifact.")
        artifact = found[0]
        work = self.root / ".local-notes/release-state/artifacts" / str(run_id)
        cache = work / "release-manifest.json"
        manifest = None
        if cache.exists():
            previous = validate_manifest(json.loads(cache.read_text(encoding="utf-8")))
            state_path = self.root / ".local-notes/release-state" / (previous.get("version", "invalid") + ".json")
            if VERSION.fullmatch(previous.get("version", "")) and state_path.exists():
                previous_state = json.loads(state_path.read_text(encoding="utf-8"))
                if previous_state.get("artifactId") == artifact["id"] and previous_state.get("manifestSha256") == sha256(cache):
                    candidate = work / previous["candidate"]
                    if candidate.exists() and sha256(candidate) == previous["candidateSha256"]:
                        manifest = previous
        if manifest is None:
            data = self.github.download(artifact["archive_download_url"])
            manifest, candidate = extract_candidate(data, work)
        if manifest["commit"] != commit: raise ReleaseError("Manifest and CI source mismatch.")
        self.bind(manifest["version"], commit)
        self.state.update(runId=run_id, artifactId=artifact["id"], manifestSha256=sha256(cache), manifest=manifest)
        self.save()
        signed = local_artifact(self.root, self.state["signedPath"]) if self.state.get("signedPath") else None
        if signed and signed.exists():
            if sha256(signed) != self.state["signedSha256"]: raise ReleaseError("Local signed package changed.")
            self.signed_identity(signed, manifest)
        else:
            folder = work / ("sign-" + uuid.uuid4().hex)
            folder.mkdir(parents=True)
            command(["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", self.root / "tools/sign-release.ps1",
                "-InApk", candidate, "-OutDir", folder], root=self.root, log=folder / "signing.log", timeout=120)
            signed = folder / f"StudyAssistant-{manifest['version']}-{manifest['buildUtc']}.apk"
            self.signed_identity(signed, manifest)
            self.state.update(signedPath=str(signed.relative_to(self.root)), signedSha256=sha256(signed), phase="signed")
            self.save()
        return dict(version=manifest["version"], commit=commit, runId=run_id, phase=self.state.get("phase"), apk=signed.name,
            sha256=self.state["signedSha256"], payloadUnchanged=True, published=bool(self.state.get("published")))
    def publish(self, run_id, notes=None):
        self.verify(run_id)
        manifest = self.state["manifest"]
        version = manifest["version"]
        tag = "v" + version
        signed = local_artifact(self.root, self.state["signedPath"])
        body = update_notes((Path(notes) if notes else self.root / f".local-notes/releases/RELEASE-v{version}.md").read_text(encoding="utf-8"))
        import importlib.util
        spec = importlib.util.spec_from_file_location("release_secret_scan", self.root / "tools/check-secrets.py")
        scanner = importlib.util.module_from_spec(spec); spec.loader.exec_module(scanner)
        if any(pattern.search(body) for pattern in scanner.CONTENT_PATTERNS):
            raise ReleaseError("Possible credential in release notes. Nothing will be published.")
        notes_sha = hashlib.sha256(body.encode()).hexdigest()
        if self.state.get("notesSha256", notes_sha) != notes_sha: raise ReleaseError("Release notes changed; inspect the saved draft before proceeding.")
        self.state["notesSha256"] = notes_sha; self.save()
        releases = self.github.api("releases?per_page=100")
        found = [r for r in releases if r["tag_name"] == tag]
        if found and self.state.get("releaseId") != found[0]["id"]:
            if recoverable_draft(found[0], self.state, body):
                self.state["releaseId"] = found[0]["id"]; self.save()
            else:
                raise ReleaseError("This version already has a release. Replacement requires a separate explicit procedure.")
        remote_tag = self.github.api("git/ref/tags/" + tag, missing=True)
        if remote_tag:
            if self.github.api("commits/" + tag)["sha"] != manifest["commit"]:
                raise ReleaseError("Existing tag points elsewhere. Tags are never moved.")
        else:
            local = self.git("rev-parse", "--verify", "--quiet", "refs/tags/" + tag + "^{commit}", check=False)
            if local.returncode == 0 and local.stdout.decode().strip() != manifest["commit"]: raise ReleaseError("Local tag collision.")
            if local.returncode: self.git("tag", "-a", tag, manifest["commit"], "-m", tag)
            result = self.git("push", "origin", "refs/tags/" + tag, check=False)
            if self.github.api("commits/" + tag)["sha"] != manifest["commit"]: raise ReleaseError("Tag push not confirmed.")
        if not found:
            self.state["draftAttempted"] = True; self.save()
            release = self.github.api("releases", {"tag_name":tag,"target_commitish":manifest["commit"],"name":tag,"body":body,"draft":True,"prerelease":False})
            self.state["releaseId"] = release["id"]; self.save()
        else: release = found[0]
        assets = release["assets"]
        if not assets:
            self.github.call("release", "upload", tag, str(signed), "--repo", REPO)
            release = self.github.api(f"releases/{release['id']}"); assets = release["assets"]
        if len(assets) != 1 or assets[0]["name"] != signed.name: raise ReleaseError("Unexpected release assets; nothing was overwritten.")
        asset = assets[0]
        if asset.get("digest") != "sha256:" + self.state["signedSha256"]: raise ReleaseError("Uploaded package digest mismatch.")
        if release["draft"]:
            release = self.github.api(f"releases/{release['id']}", {"draft":False,"prerelease":False,"make_latest":"true"}, method="PATCH")
        self.state.update(published=True, phase="published", assetId=asset["id"]); self.save()
        public = self.github.download(f"repos/{REPO}/releases/assets/{asset['id']}?download=1", binary=True)
        if hashlib.sha256(public).hexdigest() != self.state["signedSha256"]: raise ReleaseError("Public download differs from the local signed package.")
        archive = self.root / "history_apk" / signed.name
        archive.parent.mkdir(parents=True, exist_ok=True)
        if archive.exists() and sha256(archive) != self.state["signedSha256"]: raise ReleaseError("Archive collision; existing file was not changed.")
        archive.write_bytes(public)
        self.state.update(phase="archived", archived=str(archive.relative_to(self.root))); self.save()
        return dict(version=version, phase="archived", url=release["html_url"], sha256=self.state["signedSha256"])

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="operation", required=True)
    for operation in ("start", "status"):
        item = sub.add_parser(operation); item.add_argument("--version", required=True)
    for operation in ("verify", "publish"):
        item = sub.add_parser(operation); item.add_argument("--run", required=True, type=int)
        if operation == "publish": item.add_argument("--notes")
    args = parser.parse_args()
    coordinator = Release()
    try:
        if args.operation == "start": result = coordinator.start(args.version)
        elif args.operation == "status":
            if not VERSION.fullmatch(args.version): raise ReleaseError("Invalid version")
            path = ROOT / ".local-notes/release-state" / (args.version + ".json")
            state = json.loads(path.read_text(encoding="utf-8"))
            coordinator.bind(args.version, state["commit"]); result = coordinator.status()
        elif args.operation == "verify": result = coordinator.verify(args.run)
        else: result = coordinator.publish(args.run, args.notes)
        print(json.dumps(result, ensure_ascii=False)); return 0
    except ReleaseError as error:
        # ReleaseError messages are controlled templates; never print native stderr, arguments or OS exceptions.
        print("Release step stopped safely: " + str(error), file=sys.stderr)
        print("Resume the same run after resolving the cause; do not redispatch CI.", file=sys.stderr)
        return 1
    except (OSError, ValueError, KeyError, subprocess.TimeoutExpired) as error:
        category = "timeout" if isinstance(error, subprocess.TimeoutExpired) else type(error).__name__
        print("Release step stopped safely (" + category + "). Inspect local state/logs; no exception values or credentials were printed.", file=sys.stderr)
        return 1
if __name__ == "__main__": sys.exit(main())
