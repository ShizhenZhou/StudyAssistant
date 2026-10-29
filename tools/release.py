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
import io
import zipfile
import tempfile
from release_common import VERSION, apk_identity, apk_payload, extract_candidate, sha256, validate_manifest
from verification import make_plan, validate_ci_evidence

ROOT = Path(__file__).resolve().parents[1]
REPO = "ShizhenZhou/StudyAssistant"
WORKFLOW = "build.yml"

class ReleaseError(RuntimeError): pass

def published_identity(published):
    """Release and asset identity, excluding counters and mutable presentation fields."""
    value={key:published[key] for key in ('id','tag_name','draft','prerelease')}
    value['assets']=sorted(({key:asset[key] for key in ('id','name','size','digest')}
        for asset in published['assets']),key=lambda asset:(asset['id'],asset['name']))
    return value

def update_manifest(apk, identity, candidate):
    if any(identity.get(key) != candidate.get(key) for key in ("package", "version", "versionCode", "minSdk", "targetSdk")):
        raise ReleaseError("Signed APK identity differs from the CI manifest.")
    # Existing clients compare the numeric version core; code + digest still bind the exact beta APK.
    return dict(schema=1, version=identity["version"].removesuffix("_beta"), versionCode=identity["versionCode"],
                minSdk=identity["minSdk"], apkName=apk.name, apkSha256=sha256(apk), apkSize=apk.stat().st_size)

def release_body(notes, metadata):
    """Keep readable notes intact; old clients retain the independent schema-1 asset."""
    marker = "<!-- studyassistant-update-manifest\n"
    if "<!-- studyassistant-update-manifest" in notes:
        raise ReleaseError("Release notes must not contain a pre-existing update metadata block")
    channel = metadata.get("channel")
    if channel is not None:
        if channel not in ("stable", "beta"): raise ReleaseError("Unknown release channel")
        label = "本版本为测试版，功能可能不稳定。" if channel == "beta" else "本版本为正式版。"
        lines = notes.rstrip().splitlines()
        if not lines: raise ReleaseError("Release notes are empty")
        requirement = re.sub(r"\s*本版本为(?:正式版。|测试版，功能可能不稳定。)\s*$", "", lines[-1])
        lines[-1] = requirement.rstrip() + " " + label
        notes = "\n".join(lines)
    encoded = json.dumps(metadata, ensure_ascii=True, separators=(",", ":"))
    encoded = encoded.replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")
    return notes.rstrip() + "\n\n" + marker + encoded + "\n-->"

def validate_release_assets(assets, files, complete=False):
    names = [asset["name"] for asset in assets]
    if len(names) != len(set(names)) or set(names) - set(files) or (complete and set(names) != set(files)):
        raise ReleaseError("Unexpected release assets; nothing was overwritten.")
    for asset in assets:
        path = files[asset["name"]]
        if asset.get("digest") != "sha256:" + sha256(path) or asset.get("size") != path.stat().st_size:
            raise ReleaseError("Uploaded release asset digest or size mismatch.")

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
        elif method:
            args += ["--method", method]
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


def read_verification_artifact(data):
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        entries = archive.infolist()
        if len(entries) != 1 or entries[0].filename != "verification.json" or entries[0].file_size > 2_000_000:
            raise ReleaseError("Unexpected verification artifact contents")
        return json.loads(archive.read(entries[0]))


def required_jobs(jobs, apis, modern=False):
    latest = {}
    for job in jobs:
        if job["name"] not in latest or job.get("id", 0) > latest[job["name"]].get("id", 0):
            latest[job["name"]] = job
    required = {f"Android emulator tests (API {api})" for api in apis}
    if modern: required |= {"build", "Windows tool tests", "Verification gate"}
    if not required.issubset(latest) or any(latest[n].get("conclusion") != "success" for n in required):
        raise ReleaseError("Required verification jobs must succeed")
    # A later failure/cancellation must never be hidden by an older pass.
    if any(j.get("conclusion") not in ("success", "skipped") for j in latest.values()):
        raise ReleaseError("A verification job is incomplete or failed")
    return latest

class Release:
    def __init__(self, root=ROOT, github=None):
        self.root = Path(root)
        self.github = github or GitHub(self.root)
        self.state = None
        self.path = None
    def bind(self, version, commit):
        self.path = self.root / ".local-notes/release-state" / (version + "-" + commit + "-pending.json")
        self.state = bind_state(self.path, version, commit)
        self.save()
    def bind_build(self, version, commit, run_id, build_utc):
        if type(run_id) is not int or run_id < 1 or not re.fullmatch(r"[0-9]{14}", build_utc):
            raise ReleaseError("Invalid build identity")
        identity = dict(version=version, commit=commit, runId=run_id, buildUtc=build_utc)
        folder = self.root / ".local-notes/release-state"
        for path in folder.glob("*-build.json"):
            previous = json.loads(path.read_text(encoding="utf-8"))
            if previous.get("runId") == run_id and previous.get("buildIdentity") != identity:
                raise ReleaseError("CI run build identity changed; cached signing cannot be reused")
        self.path = folder / f"{version}-{commit}-{run_id}-{build_utc}-build.json"
        self.state = bind_state(self.path, version, commit)
        if self.state.get("buildIdentity", identity) != identity: raise ReleaseError("Build state identity mismatch")
        self.state.update(buildIdentity=identity, runId=run_id)
        self.save()

    def replace_assets(self, release, files, inspect_only=False):
        """Only the explicit replacement command creates the immutable old-asset snapshot."""
        state = self.state["replacement"]
        if release["id"] != state["releaseId"]: raise ReleaseError("Replacement release changed")
        old = {a["name"]:a for a in state["oldAssets"]}
        if len(old) != len(state["oldAssets"]): raise ReleaseError("Duplicate original assets")
        def same(a,b):
            return a is not None and b is not None and all(a.get(k)==b.get(k) for k in ("id","name","digest","size"))
        def refresh():
            current = self.github.api(f"releases/{release['id']}")
            if current.get("id") != state["releaseId"] or current.get("draft") or current.get("prerelease") or current.get("tag_name") != "v"+self.state["version"]:
                raise ReleaseError("Replacement release identity changed")
            assets = current["assets"]
            names = [a["name"] for a in assets]
            if len(names) != len(set(names)): raise ReleaseError("Duplicate remote asset")
            for name in old:
                allowed = state.get("switchAttempted") if name == "update-manifest.json" else state.get("cleanupAttempted")
                if name not in names and not allowed: raise ReleaseError("Original asset disappeared unexpectedly")
            for asset in assets:
                name = asset["name"]
                expected = old.get(name)
                if same(asset,expected): continue
                if name not in files: raise ReleaseError("Unexpected replacement asset")
                validate_release_assets([asset], {name:files[name]})
            return assets
        def check_download(asset):
            path = files[asset["name"]]
            data = self.github.download(f"repos/{REPO}/releases/assets/{asset['id']}?download=1", binary=True)
            if len(data) != path.stat().st_size or hashlib.sha256(data).hexdigest() != sha256(path):
                raise ReleaseError("Replacement public download mismatch")
        def upload(path):
            self.github.call("release", "upload", "v"+self.state["version"], str(path), "--repo", REPO)
        apk = next(path for name,path in files.items() if name.endswith(".apk"))
        if apk.name in old and (old[apk.name]["digest"] != "sha256:"+sha256(apk)):
            raise ReleaseError("Replacement APK name collides with the original")
        assets = refresh()
        if inspect_only: return assets
        if apk.name not in {a["name"] for a in assets}: upload(apk); assets=refresh()
        check_download(next(a for a in assets if a["name"] == apk.name))
        metadata = files["update-manifest.json"]
        current = next((a for a in assets if a["name"] == metadata.name),None)
        if same(current,old.get(metadata.name)) or current is None:
            if current is None and not state.get("switchAttempted"):
                raise ReleaseError("Original update manifest disappeared unexpectedly")
            state["switchAttempted"] = True; self.save()
            if current:
                self.github.api(f"releases/assets/{current['id']}",method="DELETE")
            current = None
        if current is None:
            if not state.get("switchAttempted"): raise ReleaseError("Update manifest missing without an owned switch")
            upload(metadata); assets=refresh()
            current=next(a for a in assets if a["name"]==metadata.name)
        validate_release_assets([current],{metadata.name:metadata})
        check_download(current)
        state["cleanupAttempted"] = True; self.save()
        for name,asset in old.items():
            if name in files: continue
            assets=refresh()
            present=next((a for a in assets if a["name"]==name),None)
            if present:
                self.github.api(f"releases/assets/{present['id']}",method="DELETE")
        assets=refresh(); validate_release_assets(assets,files,complete=True)
        return assets
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
    def published_baseline(self, published):
        """Exact APK + successful CI proof; otherwise conservative immutable tag/full."""
        if published.get("draft") or published.get("prerelease"): return None
        try:
            return self.proven_published_source(published)
        except (ReleaseError, OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile):
            tag = self.github.api("commits/" + published["tag_name"])["sha"]
            return tag if not self.git("merge-base", "--is-ancestor", tag, "HEAD", check=False).returncode else None

    def proven_published_source(self, published, frozen=None, capture=None):
        if published.get('draft') or published.get('prerelease'):raise ReleaseError('Original baseline must be a public release')
        assets = published["assets"]
        metadata_assets = [a for a in assets if a["name"] == "update-manifest.json"]
        if len(metadata_assets) != 1: raise ReleaseError("No published source metadata")
        def asset_bytes(asset):
            if asset.get("size", 0) <= 0 or asset["size"] > 100_000_000:
                raise ReleaseError("Unexpected public asset size")
            if frozen is not None:
                record=frozen[str(asset['id'])]
                path=local_artifact(self.root,record['path'])
                if sha256(path)!=record['sha256']: raise ReleaseError("Original baseline proof file changed")
                data=path.read_bytes()
            else:
                data = self.github.download(f"repos/{REPO}/releases/assets/{asset['id']}?download=1", binary=True)
            if len(data) != asset["size"] or asset.get("digest") != "sha256:" + hashlib.sha256(data).hexdigest():
                raise ReleaseError("Public asset digest mismatch")
            if capture is not None:
                folder=local_artifact(self.root,capture['directory']);folder.mkdir(parents=True,exist_ok=True)
                path=folder/(str(asset['id'])+'.bin')
                if path.exists() and path.read_bytes()!=data:raise ReleaseError("Original baseline proof cannot be overwritten")
                path.write_bytes(data)
                capture['files'][str(asset['id'])]=dict(path=str(path.relative_to(self.root)),sha256=sha256(path))
            return data
        if metadata_assets[0]["size"] > 65536: raise ReleaseError("Oversized public metadata")
        metadata = json.loads(asset_bytes(metadata_assets[0]))
        apk_assets = [a for a in assets if a["name"] == metadata["apkName"]]
        if len(apk_assets) != 1 or metadata.get("schema") != 1 or published["tag_name"] != "v" + metadata["version"]:
            raise ReleaseError("Published APK identity mismatch")
        apk = asset_bytes(apk_assets[0])
        apk_sha = hashlib.sha256(apk).hexdigest()
        if metadata["apkSha256"] != apk_sha or metadata["apkSize"] != len(apk):
            raise ReleaseError("Published manifest differs from exact APK")
        provenance = metadata.get("provenance")
        if not provenance:
            matches = []
            for path in (self.root / ".local-notes/release-state").glob("*.json"):
                state = json.loads(path.read_text(encoding="utf-8"))
                if (state.get("published") is True and state.get("phase") == "archived" and
                    state.get("releaseId") == published["id"] and state.get("version") == metadata["version"] and
                    state.get("signedSha256") == apk_sha):
                    matches.append(dict(sourceCommit=state["commit"], runId=state["runId"]))
            unique = {(item["sourceCommit"],item["runId"]) for item in matches}
            if len(unique) != 1: raise ReleaseError("No unique validated replacement state")
            commit,run_id = unique.pop()
            provenance = dict(sourceCommit=commit,runId=run_id)
        commit, run_id = provenance["sourceCommit"], provenance["runId"]
        if not re.fullmatch(r"[0-9a-f]{40}", commit) or type(run_id) is not int or run_id < 1:
            raise ReleaseError("Invalid source provenance")
        run = self.github.api(f"actions/runs/{run_id}")
        if (run.get("head_sha") != commit or run.get("conclusion") != "success" or run.get("head_branch") != "main" or
            run.get("event") != "workflow_dispatch" or not run.get("display_title", "").startswith("release:") or
            not run.get("path", "").startswith(".github/workflows/" + WORKFLOW)):
            raise ReleaseError("Published source lacks successful release CI")
        artifacts = self.github.api(f"actions/runs/{run_id}/artifacts?per_page=100")["artifacts"]
        def artifact(name):
            found = [a for a in artifacts if a["name"] == name and not a["expired"]]
            if len(found) != 1: raise ReleaseError("Published CI proof unavailable")
            return self.github.download(found[0]["archive_download_url"])
        folder = self.root / ".local-notes/release-state/baseline-proof"
        folder.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=folder) as tmp:
            manifest, _ = extract_candidate(artifact("release-candidate"), Path(tmp) / "candidate")
            if (manifest["schema"] != 2 or manifest["commit"] != commit or
                any(metadata.get(k) != manifest.get(k) for k in ("version", "versionCode", "minSdk")) or
                apk_payload(io.BytesIO(apk)) != (manifest["payloadSha256"], manifest["buildUtc"])):
                raise ReleaseError("Published APK does not match CI payload/source")
            validate_ci_evidence(read_verification_artifact(artifact("verification-evidence")), manifest, run_id, run.get("run_attempt", 1))
        if self.git("merge-base", "--is-ancestor", commit, "HEAD", check=False).returncode:
            raise ReleaseError("Published source is not an ancestor")
        return commit

    def replacement_baseline(self, published, base):
        proof=self.state.get('originalBaselineProof')
        if self.state.get('replacement'):
            replacement=self.state['replacement']
            if (not proof or proof.get('buildIdentity')!=self.state['buildIdentity'] or proof.get('base')!=base or
                proof['published']['id']!=published['id'] or replacement['releaseId']!=published['id']):
                raise ReleaseError('Replacement lacks its immutable original baseline proof')
            def identities(assets):return sorted((a['id'],a['name'],a['size'],a['digest']) for a in assets)
            if identities(proof['published']['assets'])!=identities(replacement['oldAssets']):
                raise ReleaseError('Original replacement assets differ from the baseline proof')
            signed=local_artifact(self.root,self.state['signedPath'])
            if sha256(signed)!=self.state['signedSha256']:raise ReleaseError('Local signed package changed')
            metadata=self.signed_identity(signed,self.state['manifest'])
            metadata['provenance']=dict(sourceCommit=self.state['commit'],runId=self.state['runId'])
            if 'channel' in self.state: metadata['channel']=self.state['channel']
            path=signed.parent/'update-manifest.json'
            if json.loads(path.read_text(encoding='utf-8'))!=metadata:raise ReleaseError('Replacement update manifest changed')
            self.replace_assets(published,{signed.name:signed,path.name:path},inspect_only=True)
            # Public assets can now be absent/replaced. The original exact APK is still
            # checked against freshly queried successful CI and its candidate/evidence.
            return self.proven_published_source(proof['published'],frozen=proof['files'])
        capture=dict(directory=str((self.root/'.local-notes/release-state/artifacts'/str(self.state['runId'])/'original-baseline').relative_to(self.root)),files={})
        commit=self.proven_published_source(published,capture=capture)
        value=dict(buildIdentity=self.state['buildIdentity'],base=base,
            published=published_identity(published),files=capture['files'])
        if proof is not None:
            normalized=dict(proof,published=published_identity(proof['published']))
            if normalized!=value:raise ReleaseError('Original baseline proof identity changed')
        else:
            self.state['originalBaselineProof']=value
        self.state['verificationBaselineReleaseId']=published['id'];self.save()
        return commit

    def start(self, version, replacement=False, channel="stable", full_verification=False):
        if self.git("status", "--porcelain").stdout.strip(): raise ReleaseError("Commit/review local changes first.")
        commit = self.git("rev-parse", "HEAD").stdout.decode().strip()
        self.bind(version, commit)
        if self.state.get("replacementRequested") and not replacement:
            raise ReleaseError("Resume this explicitly requested replacement with start --replace")
        if channel not in ("stable", "beta"): raise ReleaseError("Unknown release channel")
        if replacement and channel == "beta": raise ReleaseError("Beta replacement requires a new prerelease version; do not change a stable tag's channel")
        if replacement and full_verification: raise ReleaseError("Replacement requires its exact original baseline proof; use full verification only for a new version")
        if self.state.get("channel", channel) != channel: raise ReleaseError("Resume with the original release channel")
        if self.state.get("fullVerification", full_verification) != full_verification:
            raise ReleaseError("Resume with the original verification mode")
        if self.state.get("dispatchAttempted"): return self.status()
        self.state.update(channel=channel, fullVerification=full_verification); self.save()
        source = (self.root / "app/build.gradle.kts").read_text(encoding="utf-8")
        if re.search(r'val appVersionName = "([^" ]+)"', source)[1] != version:
            raise ReleaseError("Requested version differs from source.")
        tag = self.github.api("git/ref/tags/v" + version, missing=True)
        if tag and not replacement:
            raise ReleaseError("Tag already exists. Existing tags/releases are never replaced automatically.")
        if replacement:
            if not tag: raise ReleaseError("Explicit replacement requires the existing immutable tag")
            previous=self.github.api("releases/tags/v"+version,missing=True)
            if not previous or previous.get("draft") or previous.get("prerelease"):
                raise ReleaseError("Explicit replacement requires an existing public release")
            self.proven_published_source(previous)
            self.state["replacementRequested"]=True; self.save()
        if b"[skip ci]" not in self.git("log", "-1", "--format=%B").stdout:
            raise ReleaseError("Use a reviewed release-preparation commit with [skip ci] to avoid duplicate development CI.")
        # The entry validates/reuses content-bound evidence; a historical boolean is not proof.
        command(["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", self.root / "tools/check-project.ps1"],
            root=self.root, log=self.path.parent / (version + "-basic.log"), timeout=600)
        self.state["basicChecked"] = True
        # No historical evidence is reused in explicit full mode; CI must validate the entire inventory.
        previous = None if full_verification else self.github.api("releases/latest", missing=True)
        base = self.published_baseline(previous) if previous else None
        self.state["verificationBase"] = base
        self.state["verificationBaselineReleaseId"] = previous["id"] if previous else None
        self.save()
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
        self.github.api(f"actions/workflows/{WORKFLOW}/dispatches", {"ref":"main", "inputs":{"mode":"release", "expected_sha":commit, "base_sha":base or ""}})
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

    def load_status(self, version, run_id=None):
        if not VERSION.fullmatch(version): raise ReleaseError("Invalid version")
        folder=self.root/".local-notes/release-state"
        states=[]
        for path in folder.glob(version+"*.json"):
            state=json.loads(path.read_text(encoding="utf-8"))
            if state.get("version")==version and (run_id is None or state.get("runId")==run_id):
                states.append((path,state))
        modern={(state.get("commit"),state.get("runId")) for path,state in states if path.name!=version+".json"}
        states=[(path,state) for path,state in states if path.name!=version+".json" or
            (state.get("commit"),state.get("runId")) not in modern]
        builds=[item for item in states if item[1].get("buildIdentity")]
        if builds:
            identities={(state.get("commit"),state.get("runId")) for _,state in builds}
            states=builds+[item for item in states if not item[1].get("buildIdentity") and
                (item[1].get("commit"),item[1].get("runId")) not in identities]
        if len(states)!=1: raise ReleaseError("No unique release state; select status --version VERSION --run RUN")
        path,state=states[0]
        if path.name==version+".json":
            self.bind(version,state["commit"]); self.state.update(state); self.save()
        else:
            self.path,self.state=path,state
        return self.status()

    def assert_source(self, commit):
        if self.remote("main") != commit:
            raise ReleaseError("main changed since CI. Do not publish a different source.")
        if self.git("rev-parse", "HEAD").stdout.decode().strip() != commit or self.git("status", "--porcelain").stdout.strip():
            raise ReleaseError("Local source must be clean and match the verified CI commit.")
    def signed_identity(self, path, manifest):
        sdk = Path(os.environ.get("ANDROID_HOME", "")) / "build-tools/36.0.0"
        identity = apk_identity(command([sdk / "aapt.exe", "dump", "badging", path], root=self.root).stdout.decode(errors="replace"))
        metadata = update_manifest(path, identity, manifest)
        result = command([os.environ.get("COMSPEC", "cmd.exe"), "/d", "/c", sdk / "apksigner.bat", "verify", "--verbose", "--print-certs", "--min-sdk-version", str(manifest["minSdk"]), path], root=self.root)
        text = result.stdout.decode(errors="replace")
        certificates = re.findall(r"Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-f]+)", text.lower(), re.IGNORECASE)
        if certificates != [manifest["expectedSigner"]] or "Verified using v3 scheme (APK Signature Scheme v3): true" not in text:
            raise ReleaseError("Signing identity or v3 lineage validation failed.")
        payload, stamp = apk_payload(path)
        if (payload, stamp) != (manifest["payloadSha256"], manifest["buildUtc"]):
            raise ReleaseError("Signing changed compiled code/resources. Nothing will be uploaded.")
        return metadata
    def verify(self, run_id, replacement=False):
        run = self.github.api(f"actions/runs/{run_id}")
        if run["event"] != "workflow_dispatch" or run["head_branch"] != "main" or run["conclusion"] != "success" or not run.get("display_title", "").startswith("release:"):
            raise ReleaseError("A successful main release-candidate run is required.")
        jobs = []
        page = 1
        while True:
            batch = self.github.api(f"actions/runs/{run_id}/jobs?filter=all&per_page=100&page={page}")["jobs"]
            jobs.extend(batch)
            if len(batch) < 100: break
            page += 1
        modern = any(j["name"] == "Verification gate" for j in jobs)
        required_jobs(jobs, [] if modern else [35, 36], modern)
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
            for state_path in (self.root / ".local-notes/release-state").glob("*.json"):
                previous_state = json.loads(state_path.read_text(encoding="utf-8"))
                if (previous_state.get("commit") == commit and previous_state.get("runId") == run_id and
                    previous_state.get("artifactId") == artifact["id"] and previous_state.get("manifestSha256") == sha256(cache)):
                    candidate = work / previous["candidate"]
                    if candidate.exists() and sha256(candidate) == previous["candidateSha256"]:
                        manifest = previous
        if manifest is None:
            data = self.github.download(artifact["archive_download_url"])
            manifest, candidate = extract_candidate(data, work)
        if manifest["commit"] != commit: raise ReleaseError("Manifest and CI source mismatch.")
        self.bind_build(manifest["version"], commit, run_id, manifest["buildUtc"])
        if self.state.get("artifactId", artifact["id"]) != artifact["id"] or self.state.get("manifestSha256", sha256(cache)) != sha256(cache):
            raise ReleaseError("Candidate artifact changed for an existing build identity")
        if "manifest" not in self.state:
            # Read legacy/pending state without ever modifying its original file.
            for name in (manifest["version"]+".json", manifest["version"]+"-"+commit+"-pending.json"):
                old_path = self.path.parent / name
                if not old_path.exists(): continue
                old = json.loads(old_path.read_text(encoding="utf-8"))
                if old.get("commit") != commit: continue
                if old.get("runId") == run_id and old.get("manifest") == manifest:
                    self.state.update({k:v for k,v in old.items() if k not in ("buildIdentity",)})
                else:
                    self.state.update({k:old[k] for k in ("verificationBase","verificationBaselineReleaseId","replacementRequested","channel","fullVerification") if k in old})
        if manifest["schema"] == 2:
            if not modern: raise ReleaseError("Verification gate is missing")
            evidence_artifacts = [a for a in artifacts if a["name"] == "verification-evidence" and not a["expired"]]
            if len(evidence_artifacts) != 1: raise ReleaseError("Expected one verification artifact")
            evidence = read_verification_artifact(self.github.download(evidence_artifacts[0]["archive_download_url"]))
            validate_ci_evidence(evidence, manifest, run_id, run.get("run_attempt", 1))
            plan = evidence["plan"]
            base = plan.get("base")
            if self.state.get("fullVerification") and base is not None:
                raise ReleaseError("Explicit full verification requires a baseline-free CI plan")
            if base:
                release_id = self.state.get("verificationBaselineReleaseId")
                previous = self.github.api(f"releases/{release_id}") if release_id else self.github.api("releases/latest", missing=True)
                if (replacement or self.state.get('replacement')) and previous and previous.get('tag_name')=='v'+manifest['version']:
                    approved_base=self.replacement_baseline(previous,base)
                else:
                    approved_base = self.published_baseline(previous) if previous else None
                if base != approved_base: raise ReleaseError("Plan base is not the previous published release")
            current_plan = make_plan(self.root, base)
            if "mode" in plan: current_plan["mode"] = "release"
            if current_plan != plan: raise ReleaseError("CI plan differs from current source policy")
            required_jobs(jobs, [r["api"] for r in plan["devices"]], True)
            self.state.update(verificationBase=base, verificationArtifactId=evidence_artifacts[0]["id"],
                              verificationPlan=plan, verificationAttempt=evidence["attempt"])
        else:
            required_jobs(jobs, [35, 36])  # Preserve legacy archived releases without inventing new evidence.
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
    def publish(self, run_id, notes=None, replacement=False):
        self.verify(run_id,replacement=True) if replacement else self.verify(run_id)
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
        legacy_notes = self.state.get("notesSha256") == hashlib.sha256(body.encode()).hexdigest()
        channel = self.state.get("channel", "stable")
        if channel not in ("stable", "beta"): raise ReleaseError("Unknown release channel")
        if replacement and channel == "beta": raise ReleaseError("Beta replacement requires a new prerelease version")
        # Persist exact metadata before any durable replacement ownership marker.
        files = {signed.name: signed}
        # Previously published legacy releases retain their single-APK layout.
        if tuple(map(int, version.removesuffix("_beta").split("."))) > (0, 6, 7, 8):
            metadata = self.signed_identity(signed, manifest)
            update_path = signed.parent / "update-manifest.json"
            # Preserve byte-for-byte legacy metadata when resuming an existing state.
            legacy = update_path.exists() and json.loads(update_path.read_text(encoding="utf-8")) == metadata
            if manifest["schema"] == 2 and (replacement or not legacy):
                metadata["provenance"] = dict(sourceCommit=manifest["commit"], runId=self.state["runId"])
            if "channel" in self.state: metadata["channel"] = channel
            encoded = (json.dumps(metadata, indent=2) + "\n").encode("utf-8")
            if update_path.exists() and update_path.read_bytes() != encoded:
                raise ReleaseError("Local update manifest changed; nothing was overwritten.")
            update_path.write_bytes(encoded)
            files[update_path.name] = update_path
            if not legacy_notes:
                body = release_body(body, metadata)
        notes_sha = hashlib.sha256(body.encode()).hexdigest()
        if self.state.get("notesSha256", notes_sha) != notes_sha: raise ReleaseError("Release notes changed; inspect the saved draft before proceeding.")
        self.state["notesSha256"] = notes_sha; self.save()
        releases = self.github.api("releases?per_page=100")
        found = [r for r in releases if r["tag_name"] == tag]
        if len(found)>1: raise ReleaseError("Multiple releases use the requested tag")
        if (self.state.get("replacement") or self.state.get("replacementRequested")) and not replacement:
            raise ReleaseError("Resume this explicit replacement with replace --run")
        if replacement:
            if manifest["schema"] != 2 or len(found)!=1 or found[0].get("draft") or found[0].get("prerelease"):
                raise ReleaseError("Replacement requires an existing public release and verified modern CI")
            remote_tag = self.github.api("git/ref/tags/"+tag, missing=True)
            if not remote_tag: raise ReleaseError("Replacement requires the existing immutable tag")
            tag_commit=self.github.api("commits/"+tag)["sha"]
            if self.git("merge-base","--is-ancestor",tag_commit,manifest["commit"],check=False).returncode:
                raise ReleaseError("Replacement source does not descend from the immutable tag")
            if not self.state.get("replacement"):
                # Validate exact old APK/CI provenance before retaining its immutable snapshot.
                if not self.proven_published_source(found[0]): raise ReleaseError("Original release provenance is unavailable")
                old_assets=found[0]["assets"]
                if len(old_assets)!=2 or sum(a["name"]=="update-manifest.json" for a in old_assets)!=1 or sum(a["name"].endswith(".apk") for a in old_assets)!=1:
                    raise ReleaseError("Unexpected original release layout")
                self.state["replacement"]=dict(releaseId=found[0]["id"],oldAssets=json.loads(json.dumps(old_assets)),tagCommit=tag_commit)
                self.state["releaseId"]=found[0]["id"]; self.save()
            if self.state["replacement"]["tagCommit"]!=tag_commit: raise ReleaseError("Immutable tag changed")
        if not replacement and found and self.state.get("releaseId") != found[0]["id"]:
            if recoverable_draft(found[0], self.state, body):
                self.state["releaseId"] = found[0]["id"]; self.save()
            else:
                raise ReleaseError("This version already has a release. Replacement requires a separate explicit procedure.")
        remote_tag = self.github.api("git/ref/tags/" + tag, missing=True)
        if remote_tag and not replacement:
            if self.github.api("commits/" + tag)["sha"] != manifest["commit"]:
                raise ReleaseError("Existing tag points elsewhere. Tags are never moved.")
        elif not replacement:
            local = self.git("rev-parse", "--verify", "--quiet", "refs/tags/" + tag + "^{commit}", check=False)
            if local.returncode == 0 and local.stdout.decode().strip() != manifest["commit"]: raise ReleaseError("Local tag collision.")
            if local.returncode: self.git("tag", "-a", tag, manifest["commit"], "-m", tag)
            result = self.git("push", "origin", "refs/tags/" + tag, check=False)
            if self.github.api("commits/" + tag)["sha"] != manifest["commit"]: raise ReleaseError("Tag push not confirmed.")
        if not found:
            self.state["draftAttempted"] = True; self.save()
            release = self.github.api("releases", {"tag_name":tag,"target_commitish":manifest["commit"],"name":tag,"body":body,"draft":True,"prerelease":channel == "beta"})
            self.state["releaseId"] = release["id"]; self.save()
        else: release = found[0]
        assets = release["assets"]
        if replacement:
            assets=self.replace_assets(release,files)
            # Body changes only after both new public assets have passed download verification.
            release=self.github.api(f"releases/{release['id']}",{"body":body},method="PATCH")
        else:
            validate_release_assets(assets, files)
        for name, path in (() if replacement else files.items()):
            if name not in {asset["name"] for asset in assets}:
                if not release["draft"]: raise ReleaseError("Published release is missing an expected asset.")
                self.github.call("release", "upload", tag, str(path), "--repo", REPO)
                release = self.github.api(f"releases/{release['id']}")
                assets = release["assets"]
                validate_release_assets(assets, files)
        validate_release_assets(assets, files, complete=True)
        if release["draft"]:
            release = self.github.api(f"releases/{release['id']}", {"body":body,"draft":False,"prerelease":channel == "beta","make_latest":"false" if channel == "beta" else "true"}, method="PATCH")
        self.state.update(published=True, phase="published", assetId=next(a["id"] for a in assets if a["name"] == signed.name)); self.save()
        archives = {}
        for asset in assets:
            path = files[asset["name"]]
            public = self.github.download(f"repos/{REPO}/releases/assets/{asset['id']}?download=1", binary=True)
            if len(public) != path.stat().st_size or hashlib.sha256(public).hexdigest() != sha256(path):
                raise ReleaseError("Public download differs from the local release asset.")
            archive_name = path.name if path == signed else signed.stem + "-update-manifest.json"
            archive = self.root / "history_apk" / archive_name
            archive.parent.mkdir(parents=True, exist_ok=True)
            if archive.exists() and sha256(archive) != sha256(path): raise ReleaseError("Archive collision; existing file was not changed.")
            archive.write_bytes(public)
            archives[path.name] = str(archive.relative_to(self.root))
        self.state.update(phase="archived", archived=archives[signed.name], archivedAssets=archives); self.save()
        return dict(version=version, phase="archived", url=release["html_url"], sha256=self.state["signedSha256"])

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="operation", required=True)
    for operation in ("start", "status"):
        item = sub.add_parser(operation); item.add_argument("--version", required=True)
        if operation == "start": item.add_argument("--replace",action="store_true",help="Explicitly prepare replacement CI for an existing version; preserves its tag")
        if operation == "start": item.add_argument("--channel", choices=("stable", "beta"), default="stable", help="Beta is an optional GitHub prerelease and never latest")
        if operation == "start": item.add_argument("--full-verification", action="store_true", help="For a new version, validate every CI test without relying on prior published evidence")
        if operation == "status": item.add_argument("--run",type=int,help="Select an exact build when a version has multiple states")
    for operation in ("verify", "publish", "replace"):
        item = sub.add_parser(operation,help="Explicitly replace the same version's public assets; never moves its tag" if operation=="replace" else None)
        item.add_argument("--run", required=True, type=int)
        if operation in ("publish","replace"): item.add_argument("--notes")
    args = parser.parse_args()
    coordinator = Release()
    try:
        if args.operation == "start": result = coordinator.start(args.version,replacement=args.replace,channel=args.channel,full_verification=args.full_verification)
        elif args.operation == "status":
            result=coordinator.load_status(args.version,args.run)
        elif args.operation == "verify": result = coordinator.verify(args.run)
        else: result = coordinator.publish(args.run, args.notes, replacement=args.operation=="replace")
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
