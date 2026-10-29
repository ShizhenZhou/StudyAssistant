"""Public release metadata and APK payload verification. No credentials or network access."""
import hashlib
import json
from pathlib import Path
import re
import zipfile

PACKAGE = "com.zsz.studyassistant"
VERSION = re.compile(r"[0-9]+(?:\.[0-9]+){2,3}(?:_beta)?")
SIGNATURE_ENTRY = re.compile(r"META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))", re.IGNORECASE)

def sha256(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()

def apk_payload(path):
    digest = hashlib.sha256()
    with zipfile.ZipFile(path) as archive:
        names = [i.filename for i in archive.infolist() if not i.is_dir()]
        if len(names) != len(set(names)):
            raise ValueError("Duplicate APK entries")
        for name in sorted(names):
            if name.startswith(("/", "\\")) or ".." in name.replace("\\", "/").split("/"):
                raise ValueError("Unsafe APK entry")
            if SIGNATURE_ENTRY.fullmatch(name):
                continue
            encoded = name.encode("utf-8")
            digest.update(len(encoded).to_bytes(4, "big"))
            digest.update(encoded)
            digest.update(hashlib.sha256(archive.read(name)).digest())
        stamp = archive.read("assets/build-time-utc.txt").decode("ascii").strip()
        if not re.fullmatch(r"[0-9]{14}", stamp):
            raise ValueError("Invalid UTC build identity")
    return digest.hexdigest(), stamp

def apk_identity(badging):
    match = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging, re.MULTILINE)
    if not match or match[1] != PACKAGE or not VERSION.fullmatch(match[3]):
        raise ValueError("Unexpected APK identity")
    if "application-debuggable" in badging:
        raise ValueError("Debuggable release candidate")
    minimum = re.search(r"^sdkVersion:'([0-9]+)'", badging, re.MULTILINE)
    target = re.search(r"^targetSdkVersion:'([0-9]+)'", badging, re.MULTILINE)
    if not minimum or not target or not 1 <= int(minimum[1]) <= int(target[1]) <= 10000:
        raise ValueError("Unexpected SDK identity")
    return {"package": match[1], "versionCode": int(match[2]), "version": match[3], "minSdk": int(minimum[1]), "targetSdk": int(target[1])}

def validate_manifest(value):
    allowed = {"schema", "package", "versionCode", "version", "minSdk", "targetSdk", "commit", "buildUtc", "candidate", "candidateSha256", "payloadSha256", "expectedSigner", "jvm"}
    if value.get("schema") == 2: allowed.add("planSha256")
    if (set(value) - allowed or type(value.get("minSdk")) is not int or type(value.get("targetSdk")) is not int
            or not 1 <= value["minSdk"] <= value["targetSdk"] <= 10000):
        raise ValueError("Unexpected public manifest fields")
    if value.get("schema") not in (1, 2) or value.get("package") != PACKAGE:
        raise ValueError("Unsupported release manifest")
    if not VERSION.fullmatch(value.get("version", "")) or int(value.get("versionCode", 0)) <= 0:
        raise ValueError("Invalid release version")
    for key, length in [("commit", 40), ("candidateSha256", 64), ("payloadSha256", 64), ("expectedSigner", 64)]:
        if not re.fullmatch("[0-9a-f]{" + str(length) + "}", value.get(key, "")):
            raise ValueError("Invalid release identity: " + key)
    if value["schema"] == 2:
        if not re.fullmatch(r"[0-9a-f]{64}", value.get("planSha256", "")):
            raise ValueError("Missing verification plan identity")
        jvm = value.get("jvm", {})
        if not isinstance(jvm.get("tests"), int) or jvm["tests"] < 1 or any(jvm.get(k) != 0 for k in ("failures", "errors", "skipped")):
            raise ValueError("Invalid full JVM result")
    name = f"StudyAssistant-{value['version']}-{value['buildUtc']}-candidate.apk"
    if value.get("candidate") != name or not re.fullmatch(r"[0-9]{14}", value.get("buildUtc", "")):
        raise ValueError("Invalid candidate filename")
    return value

def extract_candidate(data, folder):
    import io
    folder = Path(folder)
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        files = [i for i in archive.infolist() if not i.is_dir()]
        if len(files) != 2 or any('/' in i.filename or '\\' in i.filename or i.file_size > 100_000_000 for i in files):
            raise ValueError("Unexpected candidate artifact contents")
        manifest = validate_manifest(json.loads(archive.read("release-manifest.json")))
        if {i.filename for i in files} != {"release-manifest.json", manifest["candidate"]}:
            raise ValueError("Artifact is not the candidate allowlist")
        apk = archive.read(manifest["candidate"])
        if hashlib.sha256(apk).hexdigest() != manifest["candidateSha256"]:
            raise ValueError("Candidate digest mismatch")
        folder.mkdir(parents=True, exist_ok=True)
        path = folder / manifest["candidate"]
        path.write_bytes(apk)
        (folder / "release-manifest.json").write_text(json.dumps(manifest, indent=2)+"\n", encoding="utf-8")
    payload, stamp = apk_payload(path)
    if (payload, stamp) != (manifest["payloadSha256"], manifest["buildUtc"]):
        raise ValueError("Candidate payload mismatch")
    return manifest, path
