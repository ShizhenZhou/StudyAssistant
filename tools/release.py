"""Build-only release coordinator: reviewed main -> CI build -> local signing -> publication.
No action runs merely by importing this module. Signing secrets never enter GitHub requests.
"""
from release_common import android_build_tools
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
import zlib
import tempfile
from http.client import HTTPException
from urllib.parse import unquote, urlsplit
from urllib.request import HTTPRedirectHandler, build_opener
import apk_delta
import apk_archive_delta
from distribution_config import source_distribution
from release_common import (VERSION, COMPAT_BRANCH, apk_identity, apk_payload, apk_distribution,
                            distribution_identity, release_branch_allowed, release_tags, version_from_tag, extract_candidate, numeric_version, sha256, validate_manifest)
from verification import (make_plan, validate_ci_evidence,
                          validate_promotion, pre_main_gate)

ROOT = Path(__file__).resolve().parents[1]
REPO = "ShizhenZhou/StudyAssistant"
WORKFLOW = "build.yml"
RELEASE_ASSET_KEYS = ("id", "name", "size", "digest")

class ReleaseError(RuntimeError): pass


class DispatchRejected(ReleaseError):
    def __init__(self, status):
        self.status = status
        super().__init__(f"GitHub rejected workflow dispatch (HTTP {status}).")


def atomic_replace(source, target):
    """Keep state atomic while tolerating only brief Windows access/sharing locks."""
    for attempt in range(4):
        try:
            source.replace(target)
            return
        except PermissionError as error:
            if getattr(error, "winerror", None) not in (5, 32, 33) or attempt == 3:
                raise
            time.sleep(0.05 * (2 ** attempt))

def run_description(version, message):
    """A short display label only; source identity still comes from expected_sha."""
    subject = message.splitlines()[0] if message.splitlines() else ""
    subject = " ".join(subject.replace("[skip ci]", "").split())
    return ("v" + version + (" · " + subject if subject else ""))[:200]

def published_identity(published):
    """Release and asset identity, excluding counters and mutable presentation fields."""
    value={key:published[key] for key in ('id','tag_name','draft','prerelease')}
    value['assets']=sorted(({key:asset[key] for key in RELEASE_ASSET_KEYS}
        for asset in published['assets']),key=lambda asset:(asset['id'],asset['name']))
    return value

def update_manifest(apk, identity, candidate):
    if any(identity.get(key) != candidate.get(key) for key in ("package", "version", "versionCode", "minSdk", "targetSdk")):
        raise ReleaseError("Signed APK identity differs from the CI manifest.")
    # Existing clients compare the normalized numeric release version; the APK
    # identity retains its suffix and GitHub prerelease/channel carries the channel.
    distribution = distribution_identity(candidate)
    if distribution and apk_distribution(apk) != distribution:
        raise ReleaseError("Signed APK distribution differs from the CI manifest.")
    return dict(schema=2 if distribution else 1, **distribution,
                version=numeric_version(identity["version"]), versionCode=identity["versionCode"],
                minSdk=identity["minSdk"], apkName=apk.name, apkSha256=sha256(apk), apkSize=apk.stat().st_size)

def validate_delta_distribution(bases, distribution):
    for base in bases:
        path = Path(base["path"])
        metadata = json.loads(path.with_name(path.stem + "-update-manifest.json").read_text(encoding="utf-8"))
        distribution_identity(metadata)
        if metadata.get("distribution", "main") != distribution:
            raise ReleaseError("Delta bases must belong to the target distribution")

def make_latest(state):
    return "false" if state.get("channel") == "beta" or state.get("distribution") == "compat" else "true"

def release_body(notes, metadata):
    """Keep readable notes intact; old clients retain the independent schema-1 asset."""
    marker = "<!-- studyassistant-update-manifest\n"
    if "<!-- studyassistant-update-manifest" in notes:
        raise ReleaseError("Release notes must not contain a pre-existing update metadata block")
    channel = metadata.get("channel")
    mandatory = mandatory_release(metadata)
    if channel is not None:
        if channel not in ("stable", "beta"): raise ReleaseError("Unknown release channel")
        label = "本版本为测试版，功能可能不稳定。" if channel == "beta" else "本版本为正式版。"
        lines = notes.rstrip().splitlines()
        if not lines: raise ReleaseError("Release notes are empty")
        requirement = re.sub(r"\s*本版本为(?:正式版。|测试版，功能可能不稳定。)(?:\s*本次更新极其重要，是强制更新。?)?\s*$", "", lines[-1])
        lines[-1] = requirement.rstrip() + " " + label
        if mandatory: lines[-1] += "本次更新极其重要，是强制更新"
        notes = "\n".join(lines)
    encoded = json.dumps(metadata, ensure_ascii=True, separators=(",", ":"))
    encoded = encoded.replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")
    return notes.rstrip() + "\n\n" + marker + encoded + "\n-->"


def mandatory_release(state):
    value = state.get("mandatory", False)
    if type(value) is not bool: raise ReleaseError("Mandatory flag must be a JSON boolean")
    if value and state.get("channel", "stable") != "stable":
        raise ReleaseError("Only stable releases may be mandatory")
    return value


def published_apk_name(version, stamp, mandatory=False):
    # Historical releases keep their original names when resumed.
    modern = tuple(map(int, numeric_version(version).split("."))) >= (0, 7, 5)
    if modern:
        return f"SA{'-ENF' if mandatory else ''}-{version}-{stamp}.apk"
    return f"StudyAssistant-{version}-{stamp}{'-ENF' if mandatory else ''}.apk"


def bind_mandatory(state, requested):
    """An omitted flag resumes the original decision; historical states stay unchanged."""
    current = mandatory_release(state)
    if requested is not None and type(requested) is not bool:
        raise ReleaseError("Mandatory flag must be a boolean")
    if "mandatory" in state or state.get("dispatchAttempted") or state.get("signedPath") or state.get("published"):
        if requested is not None and requested != current:
            raise ReleaseError("Cannot change the mandatory flag of an existing release state")
        return
    state["mandatory"] = bool(requested)
    mandatory_release(state)


def distribution_metadata(metadata, state):
    if "channel" in state: metadata["channel"] = state["channel"]
    if "mandatory" in state: metadata["mandatory"] = mandatory_release(state)
    if state.get("deltas"): metadata["deltas"] = state["deltas"]
    return metadata


def published_asset_names(metadata):
    names = {"update-manifest.json", metadata["apkName"]}
    if len(metadata.get("deltas", [])) > 8: raise ReleaseError("Too many published deltas for legacy clients")
    for delta in metadata.get("deltas", []):
        if delta.get("format") not in (apk_delta.FORMAT, apk_archive_delta.FORMAT) or not re.fullmatch(r"[A-Za-z0-9_.-]+\.gdiff\.gz", delta.get("patchName", "")):
            raise ReleaseError("Unexpected published delta format or name")
        if delta["patchName"] in names: raise ReleaseError("Duplicate published delta asset")
        names.add(delta["patchName"])
    return names


def delta_files(signed, state):
    names = published_asset_names(dict(apkName=signed.name, deltas=state.get("deltas", [])))
    return {name: signed.parent / name for name in names - {signed.name, "update-manifest.json"}}


def build_deltas(bases, signed, version_code, allow_same_version=False, legacy_codes=()):
    """APK-aware by default; explicit user-selected bases may use the historical format."""
    if not 0 < len(bases) <= 8: raise ReleaseError("Support one to eight exact delta bases")
    if not set(legacy_codes).issubset({base["baseVersionCode"] for base in bases}):
        raise ReleaseError("Legacy delta codes must refer to an exact selected base")
    try:
        return [(apk_delta if base["baseVersionCode"] in legacy_codes else apk_archive_delta).build(
                    base, signed, version_code, signed.parent, allow_same_version=allow_same_version) for base in bases]
    except apk_archive_delta.NotApplicable as error:
        raise ReleaseError("An exact base has no beneficial APK-aware delta; inspect before publishing") from error


def frozen_delta_pairs(bases, deltas):
    """Match frozen artifacts by identity AND format, independent of list order."""
    if not 0 < len(bases) <= 8 or not len(bases) <= len(deltas) <= 8:
        raise ReleaseError("Delta artifacts differ from frozen bases")
    key_fields = ("baseSha256", "baseSize", "baseVersionCode")
    by_identity = {tuple(base[key] for key in key_fields): base for base in bases}
    if len(by_identity) != len(bases): raise ReleaseError("Duplicate frozen delta base")
    seen, result = set(), []
    for delta in deltas:
        identity = tuple(delta.get(key) for key in key_fields)
        format_name = delta.get("format")
        key = identity + (format_name,)
        if identity not in by_identity or format_name not in (apk_delta.FORMAT, apk_archive_delta.FORMAT) or key in seen:
            raise ReleaseError("Delta artifact differs from its frozen base or format")
        seen.add(key)
        result.append((by_identity[identity], delta))
    if any(not any(key[:3] == identity for key in seen) for identity in by_identity):
        raise ReleaseError("Every frozen base must retain a delta")
    return result


def delta_runtime():
    version = zlib.ZLIB_RUNTIME_VERSION
    if sys.version_info >= (3, 11) and not hasattr(zlib, "ZLIBNG_VERSION") and "zlib-ng" not in version.lower():
        return dict(executable=sys.executable, python=sys.version.split()[0], zlib=version)
    return None


def select_delta_python():
    override = os.environ.get("STUDYASSISTANT_RELEASE_PYTHON")
    if override:
        candidates = [Path(override)]
    else:
        names = ("python.exe",) if os.name == "nt" else ("python3", "python")
        folders = (Path(folder.strip('"') if os.name == "nt" else folder) for folder in os.get_exec_path())
        candidates = [Path(sys.executable), *(folder / name for folder in folders if folder.is_absolute() for name in names)]
    flags = ["-E", "-s", "-B", str(Path(__file__).resolve())]
    for candidate in dict.fromkeys(candidates):
        if not candidate.is_absolute():
            continue
        try:
            result = subprocess.run([candidate, *flags, "--delta-runtime"], capture_output=True, timeout=10)
            runtime = json.loads(result.stdout) if result.returncode == 0 else None
            if runtime and Path(runtime["executable"]).is_absolute():
                return runtime
        except (OSError, ValueError, TypeError, KeyError, subprocess.TimeoutExpired):
            continue
    raise ReleaseError("APK delta generation needs classic zlib. Set STUDYASSISTANT_RELEASE_PYTHON to the absolute path of a Python 3.11+ executable using classic zlib.")


def run_delta_builder(signed, version_code, allow_same_version, **request):
    runtime = select_delta_python()
    print(f"Release delta Python: {runtime['executable']} (Python {runtime['python']}, classic zlib {runtime['zlib']})",
          file=sys.stderr)
    result = subprocess.run([runtime["executable"], "-E", "-s", "-B", str(Path(__file__).resolve()), "--delta-worker"],
        input=json.dumps(dict(signed=str(signed), version_code=version_code,
                              allow_same_version=allow_same_version, **request)).encode("utf-8"),
        capture_output=True, timeout=1800)
    if result.returncode:
        raise ReleaseError("APK delta generation could not start or finish under the selected Python.")
    response = json.loads(result.stdout)
    if "error" in response:
        raise ReleaseError(response["error"])
    return response["deltas"]


def delta_worker():
    """Encode patches locally, independently of the release command and its remote state."""
    try:
        if not delta_runtime():
            raise ReleaseError("The selected APK delta Python is missing classic zlib.")
        request = json.load(sys.stdin)
        signed = Path(request["signed"])
        version_code, allow_same = request["version_code"], request["allow_same_version"]
        if "frozen" in request:
            deltas = []
            for base, delta in request["frozen"]:
                builder = apk_delta if delta["format"] == apk_delta.FORMAT else apk_archive_delta
                deltas.append(builder.build(base, signed, version_code, signed.parent, expected=delta,
                                            allow_same_version=allow_same))
        else:
            deltas = build_deltas(request["bases"], signed, version_code, allow_same, request["legacy_codes"])
        result = dict(deltas=deltas)
    except ReleaseError as error:
        result = dict(error=str(error))
    except (OSError, ValueError, KeyError):
        result = dict(error="APK delta generation failed. Inspect the base, signed APK and any frozen patch SHA-256. "
                            "For exact recovery, set STUDYASSISTANT_RELEASE_PYTHON to the original classic-zlib interpreter.")
    print(json.dumps(result))


def has_download_identity(object_id, size, digest):
    return (type(object_id) is int and object_id > 0 and type(size) is int and size > 0 and
            isinstance(digest, str) and re.fullmatch(r"sha256:[0-9a-f]{64}", digest) is not None)


def matches_download(data, size, digest):
    return len(data) == size and ("sha256:" + hashlib.sha256(data).hexdigest()) == digest


def release_asset_identity(asset):
    asset_id, name, size, digest = (asset.get(key) for key in RELEASE_ASSET_KEYS)
    if (not has_download_identity(asset_id, size, digest) or not isinstance(name, str) or
            not re.fullmatch(r"[^/\\\x00-\x1f\x7f]+", name)):
        raise ReleaseError("Invalid public release asset identity.")
    return asset_id, name, size, digest


def validate_release_assets(assets, files, complete=False):
    names = [asset["name"] for asset in assets]
    if len(names) != len(set(names)) or set(names) - set(files) or (complete and set(names) != set(files)):
        raise ReleaseError("Unexpected release assets; nothing was overwritten.")
    for asset in assets:
        _, name, size, digest = release_asset_identity(asset)
        path = files[name]
        if digest != "sha256:" + sha256(path) or size != path.stat().st_size:
            raise ReleaseError("Uploaded release asset digest or size mismatch.")

def command(args, *, root=ROOT, data=None, log=None, timeout=120, check=True):
    result = subprocess.run([str(a) for a in args], cwd=root, input=data, capture_output=True, timeout=timeout)
    if log:
        Path(log).parent.mkdir(parents=True, exist_ok=True)
        Path(log).write_bytes(result.stdout + result.stderr)
    if check and result.returncode:
        raise ReleaseError(f"{'Local signing' if any(str(a).endswith('sign-release.ps1') for a in args) else Path(str(args[0])).name} failed; local exit code {result.returncode}. Inspect its local log; command arguments are withheld.")
    return result

class HTTPSAssetRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if urlsplit(newurl).scheme != "https":
            raise ReleaseError("Unexpected public asset redirect protocol.")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


class GitHub:
    def __init__(self, root=ROOT):
        self.root = root
        self.gh = shutil.which("gh") or r"D:\Program Files\GitHub CLI\bin\gh.exe"
    def call(self, *args, data=None):
        return command([self.gh, *args], root=self.root, data=data).stdout
    def require_authentication(self):
        try:
            result = command([self.gh, "api", "user", "--hostname", "github.com"],
                             root=self.root, timeout=20, check=False)
        except (OSError, subprocess.TimeoutExpired) as error:
            raise ReleaseError("GitHub CLI authentication check could not complete. Resolve the cause before running start again.") from error
        if result.returncode:
            raise ReleaseError("GitHub CLI authentication check failed. Restore GitHub CLI access before running start again.")
    def api(self, path, value=None, method=None, missing=False):
        args = [self.gh, "api", f"repos/{REPO}/{path}"]
        if value is not None:
            args += ["--method", method or "POST", "--input", "-"]
        elif method:
            args += ["--method", method]
        result = command(args, root=self.root, data=None if value is None else json.dumps(value).encode(), check=False)
        if result.returncode:
            if missing and b"HTTP 404" in result.stderr: return None
            if path == f"actions/workflows/{WORKFLOW}/dispatches" and value is not None and method in (None, "POST"):
                rejection = re.search(rb"(?m)^gh: [^\r\n]+ \(HTTP (401|403|404|422)\)\r?$", result.stderr)
                if rejection:
                    raise DispatchRejected(int(rejection[1]))
            raise ReleaseError("GitHub request failed; its outcome must be checked before retrying.")
        return json.loads(result.stdout) if result.stdout.strip() else None
    def download(self, path, binary=False):
        return self.call("api", path, "-H", "Accept: " + ("application/octet-stream" if binary else "application/json"))

    def download_release_asset(self, asset):
        """Verify public bytes against an existing release asset identity."""
        identity = release_asset_identity(asset)
        asset_id, name, size, digest = identity
        asset_path = f"releases/assets/{asset_id}"
        path = f"repos/{REPO}/{asset_path}?download=1"
        curl = shutil.which("curl.exe") or shutil.which("curl")
        args = [curl, "--disable", "--silent", "--show-error", "--http1.1", "--ipv4", "--globoff",
                "--fail", "--location", "--connect-timeout", "15", "--max-time", "120",
                "--max-filesize", str(size + 1),
                "--proto", "=https", "--proto-redir", "=https", "-H", "Accept: application/octet-stream"]
        try:
            if curl:
                result = command(args + ["https://api.github.com/" + path], root=self.root, timeout=130, check=False)
                data = result.stdout if result.returncode == 0 else b""
            else:
                data = self.download(path, binary=True)
            if matches_download(data, size, digest):
                return data
        except (ReleaseError, OSError, subprocess.TimeoutExpired):
            pass

        url = asset.get("browser_download_url")
        if not url:
            current = self.api(asset_path)
            if not isinstance(current, dict) or release_asset_identity(current) != identity:
                raise ReleaseError("Public release asset identity changed before the alternate download.")
            url = current.get("browser_download_url")
        if (not isinstance(url, str) or
                not re.fullmatch(r"https://github\.com/" + re.escape(REPO) +
                                 r"/releases/download/[^/?#\x00-\x20\x7f]+/[^/?#\x00-\x20\x7f]+", url) or
                unquote(url.rsplit("/", 1)[-1]) != name):
            raise ReleaseError("Unexpected public asset download URL.")
        try:
            if curl:
                data = command(args + [url], root=self.root, timeout=130).stdout
            else:
                with build_opener(HTTPSAssetRedirect()).open(url, timeout=120) as response:
                    data = response.read(size + 1)
        except (ReleaseError, OSError, HTTPException, subprocess.TimeoutExpired) as error:
            raise ReleaseError("Public release asset download failed on the official alternate.") from error
        if not matches_download(data, size, digest):
            raise ReleaseError("Public release asset download differs from its expected size or SHA-256.")
        return data

def update_notes(text):
    marker = "## 更新内容"
    if text.count(marker) != 1: raise ReleaseError("Release notes need one 更新内容 section.")
    body = text.split(marker, 1)[1].split("\n## ", 1)[0].strip()
    if not body: raise ReleaseError("Release notes are empty.")
    return body

def state_tag(state):
    tags = release_tags(state["version"], state.get("distribution", "main"))
    tag = state.get("releaseTag", tags[0])  # Older state always used the original spelling.
    if tag not in tags:
        raise ReleaseError("Saved tag does not match the release version and distribution")
    return tag

def recoverable_draft(release, state, body):
    return bool(state.get("draftAttempted") and release.get("draft") and
        release.get("tag_name") == state_tag(state) and release.get("target_commitish") == state["commit"] and
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
    def verified_download(self, obj, kind):
        """Cache downloaded bytes only when current remote metadata binds their identity.

        Callers must freshly query remote status/expiry and rerun every provenance check.
        Public post-upload checks intentionally bypass this cache.
        """
        release_asset = kind == "release-asset"
        size = obj.get("size") if release_asset else obj.get("size_in_bytes")
        checksum = obj.get("digest")
        if release_asset:
            release_asset_identity(obj)
        elif not has_download_identity(obj.get("id"), size, checksum):
            return self.github.download(obj["archive_download_url"])
        key = hashlib.sha256(json.dumps([REPO, kind, obj["id"], size, checksum]).encode()).hexdigest()
        folder = self.root / ".local-notes/release-state/download-cache"
        path = folder / (key + ".bin")
        if path.is_file():
            data = path.read_bytes()
            if matches_download(data, size, checksum):
                return data
        if release_asset:
            data = self.github.download_release_asset(obj)
        else:
            data = self.github.download(obj["archive_download_url"])
            if not matches_download(data, size, checksum):
                raise ReleaseError("Downloaded bytes differ from current remote object identity")
        folder.mkdir(parents=True, exist_ok=True)
        temporary = folder / (key + "-" + uuid.uuid4().hex + ".tmp")
        try:
            temporary.write_bytes(data)
            atomic_replace(temporary, path)
        finally:
            temporary.unlink(missing_ok=True)
        return data

    def download_uploaded_asset(self, asset, path):
        data = self.github.download_release_asset(asset)
        validate_release_assets([asset], {path.name: path})
        return data

    def bind(self, version, commit, distribution="main"):
        suffix = "-compat" if distribution == "compat" else ""
        self.path = self.root / ".local-notes/release-state" / (version + "-" + commit + suffix + "-pending.json")
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
            return a is not None and b is not None and all(a.get(k)==b.get(k) for k in RELEASE_ASSET_KEYS)
        def refresh():
            current = self.github.api(f"releases/{release['id']}")
            if (current.get("id") != state["releaseId"] or current.get("draft") or
                bool(current.get("prerelease")) != (self.state.get("channel", "stable") == "beta") or
                current.get("tag_name") != state_tag(self.state)):
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
        def upload(path):
            self.github.call("release", "upload", state_tag(self.state), str(path), "--repo", REPO)
        apk = next(path for name,path in files.items() if name.endswith(".apk"))
        if apk.name in old and (old[apk.name]["digest"] != "sha256:"+sha256(apk)):
            raise ReleaseError("Replacement APK name collides with the original")
        assets = refresh()
        if inspect_only: return assets
        for name, path in files.items():
            if name == "update-manifest.json": continue
            if name in old and old[name]["digest"] != "sha256:" + sha256(path):
                raise ReleaseError("Replacement asset name collides with the original")
            if name not in {a["name"] for a in assets}: upload(path); assets=refresh()
            self.download_uploaded_asset(next(a for a in assets if a["name"] == name), path)
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
        self.download_uploaded_asset(current, metadata)
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
        atomic_replace(temp, self.path)
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

    def proven_published_source(self, published, frozen=None, capture=None, allow_beta=False):
        is_beta = bool(published.get('prerelease'))
        if published.get('draft') or (is_beta and not allow_beta):raise ReleaseError('Original baseline must be an authorized public release')
        assets = published["assets"]
        metadata_assets = [a for a in assets if a["name"] == "update-manifest.json"]
        if len(metadata_assets) != 1: raise ReleaseError("No published source metadata")
        def asset_bytes(asset):
            asset_id, _, size, digest = release_asset_identity(asset)
            if size > 100_000_000:
                raise ReleaseError("Unexpected public asset size")
            if frozen is not None:
                record=frozen[str(asset_id)]
                path=local_artifact(self.root,record['path'])
                data=path.read_bytes()
                if "sha256:" + record["sha256"] != digest or not matches_download(data, size, digest):
                    raise ReleaseError("Original baseline proof file changed")
            else:
                data = self.verified_download(asset, "release-asset")
            if capture is not None:
                folder=local_artifact(self.root,capture['directory']);folder.mkdir(parents=True,exist_ok=True)
                path=folder/(str(asset['id'])+'.bin')
                if path.exists() and path.read_bytes()!=data:raise ReleaseError("Original baseline proof cannot be overwritten")
                path.write_bytes(data)
                capture['files'][str(asset['id'])]=dict(path=str(path.relative_to(self.root)),sha256=sha256(path))
            return data
        if metadata_assets[0]["size"] > 65536: raise ReleaseError("Oversized public metadata")
        metadata = json.loads(asset_bytes(metadata_assets[0]))
        distribution_identity(metadata)
        asset_names = [a["name"] for a in assets]
        if len(asset_names) != len(set(asset_names)) or set(asset_names) != published_asset_names(metadata):
            raise ReleaseError("Unexpected original release layout")
        apk_assets = [a for a in assets if a["name"] == metadata["apkName"]]
        if (len(apk_assets) != 1 or metadata.get("schema") != (2 if metadata.get("distribution") == "compat" else 1) or
            numeric_version(version_from_tag(published["tag_name"])) != metadata["version"] or
            (is_beta and metadata.get("channel") != "beta")):
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
                    state.get("releaseId") == published["id"] and state.get("version") == version_from_tag(published["tag_name"]) and
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
        if (run.get("head_sha") != commit or run.get("conclusion") != "success" or run.get("head_branch") not in ("main", "Develop", COMPAT_BRANCH) or
            run.get("event") != "workflow_dispatch" or not run.get("display_title", "").startswith("release:") or
            not run.get("path", "").startswith(".github/workflows/" + WORKFLOW)):
            raise ReleaseError("Published source lacks successful release CI")
        artifacts = self.github.api(f"actions/runs/{run_id}/artifacts?per_page=100")["artifacts"]
        def artifact(name):
            found = [a for a in artifacts if a["name"] == name and not a["expired"]]
            if len(found) != 1: raise ReleaseError("Published CI proof unavailable")
            return self.verified_download(found[0], "action-artifact")
        folder = self.root / ".local-notes/release-state/baseline-proof"
        folder.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=folder) as tmp:
            manifest, _ = extract_candidate(artifact("release-candidate"), Path(tmp) / "candidate")
            if (manifest["schema"] not in (2, 3) or manifest["commit"] != commit or
                published["tag_name"] not in release_tags(manifest["version"], manifest.get("distribution", "main")) or
                metadata["version"] != numeric_version(manifest["version"]) or
                any(metadata.get(k) != manifest.get(k) for k in ("versionCode", "minSdk")) or
                distribution_identity(metadata) != distribution_identity(manifest) or
                apk_payload(io.BytesIO(apk)) != (manifest["payloadSha256"], manifest["buildUtc"])):
                raise ReleaseError("Published APK does not match CI payload/source")
            if manifest["schema"] == 2:  # Read-only compatibility for archived releases.
                evidence = read_verification_artifact(artifact("verification-evidence"))
                validate_ci_evidence(evidence, manifest, run_id, run.get("run_attempt", 1))
                self.validate_run_promotion(run, evidence)
            else:
                if not release_branch_allowed(run.get("head_branch"), "beta" if is_beta else "stable", manifest.get("distribution", "main"), historical=True) or required_jobs(self.run_jobs(run_id), []).get("build", {}).get("conclusion") != "success":
                    raise ReleaseError("Published build did not succeed")
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
            distribution_metadata(metadata, self.state)
            path=signed.parent/'update-manifest.json'
            if json.loads(path.read_text(encoding='utf-8'))!=metadata:raise ReleaseError('Replacement update manifest changed')
            files = {signed.name:signed,path.name:path}
            files.update(delta_files(signed, self.state))
            self.replace_assets(published,files,inspect_only=True)
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

    def start(self, version, replacement=False, channel="stable", mandatory=None, delta_base=None, legacy_delta_base=None, distribution="main"):
        # Merge/review happens before this command; it never starts tests or merges branches.
        if self.git("status", "--porcelain").stdout.strip():
            raise ReleaseError("Commit/review local changes first.")
        branch = self.git("branch", "--show-current").stdout.decode().strip()
        if not release_branch_allowed(branch, channel, distribution):
            raise ReleaseError("Distribution/channel requires its exact authorized source branch.")
        commit = self.git("rev-parse", "HEAD").stdout.decode().strip()
        if self.remote(branch) != commit:
            raise ReleaseError("Push the reviewed source branch before requesting its build.")
        if channel not in ("stable", "beta"): raise ReleaseError("Unknown release channel")
        mandatory_release(dict(channel=channel, mandatory=False if mandatory is None else mandatory))
        if "_beta" in version and channel != "beta": raise ReleaseError("Beta versions require the beta channel")
        profile = source_distribution(self.root, distribution)
        if profile["versionName"] != version:
            raise ReleaseError("Requested version differs from source.")
        self.bind(version, commit, distribution)
        if self.state.get("testBeforeMain"):
            raise ReleaseError("Retired staged-release state; do not resume its promotion workflow.")
        if self.state.get("channel", channel) != channel:
            raise ReleaseError("Resume with the original release channel")
        if self.state.get("distribution", "main" if self.state.get("dispatchAttempted") else distribution) != distribution:
            raise ReleaseError("Resume with the original release distribution")
        if distribution == "compat": self.state["distribution"] = distribution
        bind_mandatory(self.state, mandatory)
        if delta_base is not None or legacy_delta_base is not None:
            paths = (delta_base if isinstance(delta_base, (list, tuple)) else [delta_base]) if delta_base is not None else []
            old_paths = (legacy_delta_base if isinstance(legacy_delta_base, (list, tuple)) else [legacy_delta_base]) if legacy_delta_base is not None else []
            old_bases = [apk_delta.read_base(path) for path in old_paths]
            legacy_codes = sorted(base["baseVersionCode"] for base in old_bases)
            bases = sorted([apk_delta.read_base(path) for path in paths] + old_bases, key=lambda base: base["baseVersionCode"])
            if not 0 < len(bases) <= 8: raise ReleaseError("Support one to eight exact delta bases")
            validate_delta_distribution(bases, distribution)
            if len({base["baseVersionCode"] for base in bases}) != len(bases):
                raise ReleaseError("Specify only one exact delta base per versionCode")
            previous = self.state.get("deltaBases", [self.state["deltaBase"]] if self.state.get("deltaBase") else [])
            if (self.state.get("dispatchAttempted") or self.state.get("signedPath") or previous) and previous != bases:
                raise ReleaseError("Cannot change delta bases of an existing release state")
            if (self.state.get("dispatchAttempted") or self.state.get("signedPath") or previous) and self.state.get("legacyDeltaBaseCodes", []) != legacy_codes:
                raise ReleaseError("Cannot change frozen delta formats")
            self.state["deltaBases"] = bases
            self.state["legacyDeltaBaseCodes"] = legacy_codes
        if self.state.get("replacementRequested") and not replacement:
            raise ReleaseError("Resume this explicitly requested replacement with start --replace")
        retrying = self.state.get("dispatchAttempted", False)
        if retrying:
            status = self.status()
            if self.state.get("runId") or not self.state.get("dispatchRejectedStatus") or self.state.get("dispatchAttempts") != 1:
                return status
            if self.state["dispatchBranch"] != branch or bool(self.state.get("replacementRequested")) != replacement:
                raise ReleaseError("Resume the original source branch and replacement choice.")
        tags = release_tags(version, distribution)
        existing = [tag for tag in tags if self.github.api("git/ref/tags/" + tag, missing=True)]
        if existing and not replacement: raise ReleaseError("Tag exists; replacement must be explicitly requested.")
        tag = profile["tagPrefix"] + version
        if replacement:
            if len(existing) != 1: raise ReleaseError("Replacement requires one unambiguous existing tag")
            tag = existing[0]
            previous = self.github.api("releases/tags/" + tag, missing=True)
            if not previous or previous.get("draft") or bool(previous.get("prerelease")) != (channel == "beta"):
                raise ReleaseError("Replacement requires an existing public release in the same channel")
            self.state["replacementRequested"] = True
        if retrying:
            if tag != state_tag(self.state):
                raise ReleaseError("Resume with the original release tag.")
            description = self.state["runDescription"]
        else:
            message = self.git("log", "-1", "--format=%s").stdout.decode("utf-8").strip()
            if not re.match(r"^(feat|fix|build|ci|docs|test|refactor|chore|perf|revert)(?:\([^)]+\))?!?: .+", message):
                raise ReleaseError("Use a Conventional Commit title for the reviewed main commit.")
            description = run_description(version, message) + (" [compat]" if distribution == "compat" else "")
        inputs = {"expected_sha":commit, "run_description":description, "release_channel":channel}
        if branch == "Develop": inputs["release_channel"] = "beta"
        if distribution == "compat": inputs.update(release_channel=channel, release_distribution=distribution)

        self.github.require_authentication()
        if not retrying:
            self.state.update(channel=channel, releaseTag=tag, dispatchBranch=branch, runDescription=description,
                              requestedAt=datetime.now(timezone.utc).isoformat())
        self.state.update(dispatchAttempted=True, dispatchAttempts=2 if retrying else 1)
        self.state.pop("dispatchRejectedStatus", None)
        self.save()  # An interruption or ambiguous response leaves the attempt pending.
        try:
            self.github.api(f"actions/workflows/{WORKFLOW}/dispatches", {"ref":branch, "inputs":inputs})
        except DispatchRejected as error:
            self.state["dispatchRejectedStatus"] = error.status
            self.save()
            raise ReleaseError(self.dispatch_rejection_message()) from error
        return self.status()

    def dispatch_rejection_message(self):
        message = f"GitHub rejected workflow dispatch (HTTP {self.state['dispatchRejectedStatus']})."
        if self.state.get("dispatchAttempts") == 1:
            return message + " Resolve the cause and run start once to check for a matching run before retrying."
        return message + " The single retry has been used."

    def status(self):
        if not self.state.get("runId") and self.state.get("dispatchAttempted"):
            runs = self.github.api(f"actions/workflows/{WORKFLOW}/runs?event=workflow_dispatch&branch={self.state.get('dispatchBranch', 'main')}&per_page=30")["workflow_runs"]
            threshold = datetime.fromisoformat(self.state["requestedAt"]) - timedelta(seconds=5)
            matches = [r for r in runs if r["head_sha"] == self.state["commit"] and r.get("display_title", "").startswith("release:")
                and (not self.state.get("runDescription") or r.get("display_title") == "release: " + self.state["runDescription"])
                and datetime.fromisoformat(r["created_at"].replace("Z", "+00:00")) >= threshold]
            if len(matches) > 1: raise ReleaseError("Multiple runs match; select one explicitly with verify --run.")
            if matches: self.state["runId"] = matches[0]["id"]; self.save()
        if self.state.get("runId"):
            run = self.github.api(f"actions/runs/{self.state['runId']}")
            return dict(version=self.state["version"], commit=self.state["commit"], runId=run["id"], status=run["status"], conclusion=run["conclusion"], phase=self.state.get("phase", "ci"))
        if self.state.get("dispatchRejectedStatus"):
            return dict(version=self.state["version"], status=self.dispatch_rejection_message())
        if not self.state.get("dispatchAttempted"):
            return dict(version=self.state["version"], status="not dispatched; run start after resolving the preflight failure")
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
            self.bind(version,state["commit"],state.get("distribution", "main")); self.state.update(state); self.save()
        else:
            self.path,self.state=path,state
        return self.status()

    def assert_source(self, commit, branch="main"):
        if self.remote(branch) != commit:
            raise ReleaseError("Source branch changed since CI. Do not publish a different source.")
        if self.git("rev-parse", "HEAD").stdout.decode().strip() != commit or self.git("status", "--porcelain").stdout.strip():
            raise ReleaseError("Local source must be clean and match the verified CI commit.")

    def run_jobs(self, run_id):
        jobs = []
        for page in range(1, 101):
            batch = self.github.api(f"actions/runs/{run_id}/jobs?filter=all&per_page=100&page={page}")["jobs"]
            jobs.extend(batch)
            if len(batch) < 100: return jobs
        raise ReleaseError("Job pagination exceeds bound")

    def promotion_statuses(self, commit):
        statuses = []
        for page in range(1, 101):
            batch = self.github.api(f"commits/{commit}/statuses?per_page=100&page={page}")
            statuses.extend(batch)
            if len(batch) < 100: return statuses
        raise ReleaseError("Promotion status pagination exceeds bound")

    def validate_run_promotion(self, run, evidence, jobs=None):
        """Historical main runs remain valid; Develop requires the exact durable promotion proof."""
        if run.get("head_branch") == "main": return
        if run.get("head_branch") != "Develop" or "preMain" not in evidence or "promotion" not in evidence:
            raise ReleaseError("Develop release requires verified test-before-main promotion")
        if not run.get("path", "").startswith(".github/workflows/" + WORKFLOW):
            raise ReleaseError("Staged run has the wrong workflow source")
        promotion, pre_main = evidence["promotion"], evidence["preMain"]
        validate_promotion(promotion, pre_main, run["id"], run.get("run_attempt", 1), run["head_sha"])
        if pre_main_gate(jobs if jobs is not None else self.run_jobs(run["id"]), pre_main) != promotion["gate"]:
            raise ReleaseError("Current pre-main gate differs from promotion proof")
        statuses = [s for s in self.promotion_statuses(run["head_sha"]) if s.get("context") == promotion["status"]["context"]]
        if not statuses or {key: max(statuses, key=lambda row: row["id"]).get(key) for key in promotion["status"]} != promotion["status"]:
            raise ReleaseError("Remote promotion status changed or is missing")

    def signed_identity(self, path, manifest):
        sdk = android_build_tools(os.environ.get("ANDROID_HOME"), self.root)
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
        if run["event"] != "workflow_dispatch" or run["head_branch"] not in ("main", "Develop", COMPAT_BRANCH) or run["conclusion"] != "success" or not run.get("display_title", "").startswith("release:"):
            raise ReleaseError("A successful release-candidate run is required.")
        jobs = []
        page = 1
        while True:
            batch = self.github.api(f"actions/runs/{run_id}/jobs?filter=all&per_page=100&page={page}")["jobs"]
            jobs.extend(batch)
            if len(batch) < 100: break
            page += 1
        modern = any(j["name"] == "Verification gate" for j in jobs)
        commit = run["head_sha"]
        self.assert_source(commit, run["head_branch"])
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
            suffix = "-compat" if manifest.get("distribution") == "compat" else ""
            for name in (manifest["version"]+".json", manifest["version"]+"-"+commit+"-pending.json",
                         manifest["version"]+"-"+commit+suffix+"-pending.json"):
                old_path = self.path.parent / name
                if not old_path.exists(): continue
                old = json.loads(old_path.read_text(encoding="utf-8"))
                if (old.get("commit") != commit or
                    old.get("distribution", "main") != manifest.get("distribution", "main") or
                    old.get("runId") not in (None, run_id)): continue
                if old.get("runId") == run_id and old.get("manifest") == manifest:
                    self.state.update({k:v for k,v in old.items() if k not in ("buildIdentity",)})
                else:
                    self.state.update({k:old[k] for k in ("verificationBase","verificationBaselineReleaseId","replacementRequested","channel","distribution","releaseTag","mandatory","deltaBase","deltaBases","legacyDeltaBaseCodes","dispatchBranch","fullVerification") if k in old})
        if manifest["schema"] == 3:
            branch = run.get("head_branch")
            if "_beta" in manifest["version"] and self.state.get("channel") != "beta":
                raise ReleaseError("Beta candidates require an explicitly requested beta release")
            distribution = manifest.get("distribution", "main")
            if (not release_branch_allowed(branch, self.state.get("channel", "stable"), distribution) or
                    self.state.get("distribution", "main") != distribution or
                    (branch != "main" and self.state.get("dispatchBranch") != branch) or
                    run.get("path", "").split("@")[0] != ".github/workflows/" + WORKFLOW):
                raise ReleaseError("Candidate requires an explicitly requested matching distribution build")
            latest = required_jobs(jobs, [])
            if latest.get("build", {}).get("conclusion") != "success":
                raise ReleaseError("Release build did not succeed")
        elif manifest["schema"] == 2:
            if not modern: raise ReleaseError("Verification gate is missing")
            evidence_artifacts = [a for a in artifacts if a["name"] == "verification-evidence" and not a["expired"]]
            if len(evidence_artifacts) != 1: raise ReleaseError("Expected one verification artifact")
            evidence = read_verification_artifact(self.verified_download(evidence_artifacts[0], "action-artifact"))
            validate_ci_evidence(evidence, manifest, run_id, run.get("run_attempt", 1))
            self.validate_run_promotion(run, evidence, jobs)
            plan = evidence["plan"]
            base = plan.get("base")
            if self.state.get("fullVerification") and base is not None:
                raise ReleaseError("Explicit full verification requires a baseline-free CI plan")
            if base:
                release_id = self.state.get("verificationBaselineReleaseId")
                previous = self.github.api(f"releases/{release_id}") if release_id else self.github.api("releases/latest", missing=True)
                if (replacement or self.state.get('replacement')) and previous and previous.get('tag_name')==state_tag(self.state):
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
            if run["head_branch"] != "main": raise ReleaseError("Legacy release candidates require main")
            required_jobs(jobs, [35, 36])  # Preserve legacy archived releases without inventing new evidence.
        self.state.update(runId=run_id, artifactId=artifact["id"], manifestSha256=sha256(cache), manifest=manifest)
        self.save()
        signed = local_artifact(self.root, self.state["signedPath"]) if self.state.get("signedPath") else None
        if signed and signed.exists():
            if sha256(signed) != self.state["signedSha256"]: raise ReleaseError("Local signed package changed.")
            if (signed.name.endswith("-ENF.apk") or signed.name.startswith("SA-ENF-")) != mandatory_release(self.state):
                raise ReleaseError("Signed APK filename differs from mandatory release state")
            self.signed_identity(signed, manifest)
        else:
            folder = work / ("sign-" + uuid.uuid4().hex)
            folder.mkdir(parents=True)
            command(["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", self.root / "tools/sign-release.ps1",
                "-InApk", candidate, "-OutDir", folder], root=self.root, log=folder / "signing.log", timeout=120)
            compat = manifest.get("distribution") == "compat"
            prefix = "SACompat" if compat else "StudyAssistant"
            signed = folder / f"{prefix}-{manifest['version']}-{manifest['buildUtc']}.apk"
            legacy_compat_name = compat and not manifest["version"].endswith("_Compat")
            name = (signed.stem + ("-ENF" if mandatory_release(self.state) else "") + ".apk") if legacy_compat_name else published_apk_name(manifest['version'], manifest['buildUtc'], mandatory_release(self.state))
            public_path = signed.with_name(name)
            if public_path != signed:
                signed.rename(public_path)
                signed = public_path
            self.signed_identity(signed, manifest)
            self.state.update(signedPath=str(signed.relative_to(self.root)), signedSha256=sha256(signed), phase="signed")
            self.save()
        self.prepare_deltas(signed, manifest)
        return dict(version=manifest["version"], commit=commit, runId=run_id, phase=self.state.get("phase"), apk=signed.name,
            sha256=self.state["signedSha256"], payloadUnchanged=True, published=bool(self.state.get("published")))

    def prepare_deltas(self, signed, manifest):
        bases = self.state.get("deltaBases", [self.state["deltaBase"]] if self.state.get("deltaBase") else [])
        if manifest.get("distribution") == "compat": validate_delta_distribution(bases, "compat")
        if not bases:
            return
        allow_same = bool(self.state.get("replacementRequested"))
        version_code = manifest["versionCode"]
        deltas = self.state.get("deltas")
        freeze = not deltas
        if freeze:
            deltas = run_delta_builder(signed, version_code, allow_same, bases=bases,
                                       legacy_codes=self.state.get("legacyDeltaBaseCodes", []))
        paths = delta_files(signed, {"deltas": deltas})
        missing = []
        for base, delta in frozen_delta_pairs(bases, deltas):
            path = paths[delta["patchName"]]
            if not freeze and not path.exists() and not path.is_symlink():
                missing.append((base, delta))
            elif not path.is_file() or path.stat().st_size != delta["patchSize"] or sha256(path) != delta["patchSha256"]:
                raise ReleaseError("Local delta artifact changed")
        if missing:
            run_delta_builder(signed, version_code, allow_same, frozen=missing)
        if freeze:
            self.state["deltas"] = deltas
            self.save()

    def publish(self, run_id, notes=None, replacement=False):
        self.verify(run_id,replacement=True) if replacement else self.verify(run_id)
        manifest = self.state["manifest"]
        version = manifest["version"]
        tag = state_tag(self.state)
        signed = local_artifact(self.root, self.state["signedPath"])
        body = update_notes((Path(notes) if notes else self.root / f".local-notes/releases/RELEASE-{tag}.md").read_text(encoding="utf-8"))
        import importlib.util
        spec = importlib.util.spec_from_file_location("release_secret_scan", self.root / "tools/check-secrets.py")
        scanner = importlib.util.module_from_spec(spec); spec.loader.exec_module(scanner)
        if any(pattern.search(body) for pattern in scanner.CONTENT_PATTERNS):
            raise ReleaseError("Possible credential in release notes. Nothing will be published.")
        legacy_notes = self.state.get("notesSha256") == hashlib.sha256(body.encode()).hexdigest()
        channel = self.state.get("channel", "stable")
        if channel not in ("stable", "beta"): raise ReleaseError("Unknown release channel")
        # Persist exact metadata before any durable replacement ownership marker.
        files = {signed.name: signed}
        files.update(delta_files(signed, self.state))
        # Previously published legacy releases retain their single-APK layout.
        if tuple(map(int, numeric_version(version).split("."))) > (0, 6, 7, 8):
            metadata = self.signed_identity(signed, manifest)
            update_path = signed.parent / "update-manifest.json"
            # Preserve byte-for-byte legacy metadata when resuming an existing state.
            legacy = update_path.exists() and json.loads(update_path.read_text(encoding="utf-8")) == metadata
            if manifest["schema"] in (2, 3) and (replacement or not legacy):
                metadata["provenance"] = dict(sourceCommit=manifest["commit"], runId=self.state["runId"])
            distribution_metadata(metadata, self.state)
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
        for alternate in release_tags(version, self.state.get("distribution", "main")):
            if alternate != tag and self.github.api("git/ref/tags/" + alternate, missing=True):
                raise ReleaseError("The other spelling of this compatibility tag already exists")
        if self.state.get("releaseId"):
            bound = self.github.api(f"releases/{self.state['releaseId']}")
            if bound["tag_name"] != tag: raise ReleaseError("Saved release now has a different tag")
            found = [bound]
        elif replacement:
            bound = self.github.api("releases/tags/" + tag, missing=True)
            found = [bound] if bound else []
        else:
            releases = self.github.api("releases?per_page=100")
            found = [r for r in releases if r["tag_name"] == tag]
        if len(found)>1: raise ReleaseError("Multiple releases use the requested tag")
        if (self.state.get("replacement") or self.state.get("replacementRequested")) and not replacement:
            raise ReleaseError("Resume this explicit replacement with replace --run")
        if replacement:
            if (manifest["schema"] not in (2, 3) or len(found)!=1 or found[0].get("draft") or
                bool(found[0].get("prerelease")) != (channel == "beta")):
                raise ReleaseError("Replacement requires an existing public release and verified modern CI")
            remote_tag = self.github.api("git/ref/tags/"+tag, missing=True)
            if not remote_tag: raise ReleaseError("Replacement requires the existing immutable tag")
            tag_commit=self.github.api("commits/"+tag)["sha"]
            if self.git("merge-base","--is-ancestor",tag_commit,manifest["commit"],check=False).returncode:
                raise ReleaseError("Replacement source does not descend from the immutable tag")
            if not self.state.get("replacement"):
                # Validate exact old APK/CI provenance before retaining its immutable snapshot.
                if not self.proven_published_source(found[0], allow_beta=channel == "beta"): raise ReleaseError("Original release provenance is unavailable")
                old_assets=found[0]["assets"]
                # proven_published_source already checked the complete APK/manifest/delta layout.
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
            release = self.github.api("releases", {"tag_name":tag,"target_commitish":manifest["commit"],"name":f"v{version}","body":body,"draft":True,"prerelease":channel == "beta"})
            self.state["releaseId"] = release["id"]; self.save()
        else: release = found[0]
        assets = release["assets"]
        if replacement:
            assets=self.replace_assets(release,files)
            # Body changes only after all new public assets have passed download verification.
            changes = {"body": body}
            if self.state.get("distribution") == "compat": changes["make_latest"] = "false"
            release=self.github.api(f"releases/{release['id']}",changes,method="PATCH")
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
            release = self.github.api(f"releases/{release['id']}", {"body":body,"draft":False,"prerelease":channel == "beta","make_latest":make_latest(self.state)}, method="PATCH")
        self.state.update(published=True, phase="published", assetId=next(a["id"] for a in assets if a["name"] == signed.name)); self.save()
        archives = {}
        for asset in assets:
            path = files[asset["name"]]
            public = self.download_uploaded_asset(asset, path)
            archive_name = signed.stem + "-update-manifest.json" if path.name == "update-manifest.json" else path.name
            archive = self.root / "history_apk" / archive_name
            archive.parent.mkdir(parents=True, exist_ok=True)
            if archive.exists() and sha256(archive) != sha256(path): raise ReleaseError("Archive collision; existing file was not changed.")
            archive.write_bytes(public)
            archives[path.name] = str(archive.relative_to(self.root))
        self.state.update(phase="archived", archived=archives[signed.name], archivedAssets=archives); self.save()
        return dict(version=version, phase="archived", url=release["html_url"], sha256=self.state["signedSha256"])

def main():
    if sys.argv[1:] == ["--delta-runtime"]:
        runtime = delta_runtime()
        if not runtime: return 1
        print(json.dumps(runtime))
        return 0
    if sys.argv[1:] == ["--delta-worker"]:
        delta_worker()
        return 0
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="operation", required=True)
    for operation in ("start", "status"):
        item = sub.add_parser(operation); item.add_argument("--version", required=True)
        if operation == "start": item.add_argument("--replace",action="store_true",help="Explicitly prepare replacement CI for an existing version; preserves its tag")
        if operation == "start": item.add_argument("--channel", choices=("stable", "beta"), default="stable", help="Beta is an optional GitHub prerelease and never latest")
        if operation == "start": item.add_argument("--distribution", choices=("main", "compat"), default="main", help="Build distribution from main (or explicitly requested Develop beta); compat is never latest")
        if operation == "start": item.add_argument("--mandatory", action="store_true", default=None, help="Explicit stable-only forced update; adds -ENF to the APK filename")
        if operation == "start": item.add_argument("--delta-base", type=Path, action="append", help="Exact archived stable/beta APK with companion manifest; repeat for multiple versionCodes")
        if operation == "start": item.add_argument("--legacy-delta-base", type=Path, action="append", help="Explicit exception: build an old-format patch for this exact base instead of APK-aware")
        if operation == "status": item.add_argument("--run",type=int,help="Select an exact build when a version has multiple states")
    for operation in ("verify", "publish", "replace"):
        item = sub.add_parser(operation,help="Explicitly replace the same version's public assets; never moves its tag" if operation=="replace" else None)
        item.add_argument("--run", required=True, type=int)
        if operation in ("publish","replace"): item.add_argument("--notes")
    args = parser.parse_args()
    coordinator = Release()
    try:
        if args.operation == "start": result = coordinator.start(args.version,replacement=args.replace,channel=args.channel,mandatory=args.mandatory,delta_base=args.delta_base,legacy_delta_base=args.legacy_delta_base,distribution=args.distribution)
        elif args.operation == "status":
            result=coordinator.load_status(args.version,args.run)
        elif args.operation == "verify": result = coordinator.verify(args.run)
        else: result = coordinator.publish(args.run, args.notes, replacement=args.operation=="replace")
        print(json.dumps(result, ensure_ascii=False)); return 0
    except ReleaseError as error:
        # ReleaseError messages are controlled templates; never print native stderr, arguments or OS exceptions.
        print("Release step stopped safely: " + str(error), file=sys.stderr)
        if args.operation != "start" or (coordinator.state and coordinator.state.get("dispatchAttempted")
                                        and not coordinator.state.get("dispatchRejectedStatus")):
            print("Resume the same run after resolving the cause; do not redispatch CI.", file=sys.stderr)
        return 1
    except (OSError, ValueError, KeyError, subprocess.TimeoutExpired) as error:
        category = "timeout" if isinstance(error, subprocess.TimeoutExpired) else type(error).__name__
        print("Release step stopped safely (" + category + "). Inspect local state/logs; no exception values or credentials were printed.", file=sys.stderr)
        return 1
if __name__ == "__main__": sys.exit(main())
