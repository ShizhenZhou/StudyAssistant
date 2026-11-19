"""Read explicit distribution profiles shared with Gradle product flavors."""
from pathlib import Path
import re


def properties(path):
    result = {}
    for line in Path(path).read_text(encoding="utf-8-sig").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator or key.strip() in result:
            raise ValueError("Invalid or duplicate distribution property")
        result[key.strip()] = value.strip()
    return result


def source_distribution(root, selected="main"):
    root = Path(root)
    name = selected
    if name not in ("main", "compat"):
        raise ValueError("Invalid distribution")
    profile = properties(root / f"config/distributions/{name}.properties")
    expected = {"distribution", "versionName", "versionCode", "minSdk", "maxSdk", "androidLabel", "apkPrefix", "tagPrefix"}
    if set(profile) != expected or profile["distribution"] != name:
        raise ValueError("Invalid distribution profile")
    for key in ("versionCode", "minSdk", "maxSdk"):
        profile[key] = int(profile[key])
    if not re.fullmatch(r"[0-9]+(?:\.[0-9]+){2,3}(?:_beta(?:[1-9][0-9]*)?)?(?:_Compat)?", profile["versionName"]) or profile["versionCode"] <= 0:
        raise ValueError("Invalid distribution version")
    if name == "main" and profile["versionName"].endswith("_Compat"):
        raise ValueError("Main profile must not carry Compat suffix")
    identity = (profile["minSdk"], profile["maxSdk"], profile["apkPrefix"], profile["tagPrefix"], profile["androidLabel"])
    compat_prefix = "SA" if profile["versionName"].endswith("_Compat") else "SACompat"
    if identity != ((36, 0, "SA", "v", "16+") if name == "main" else (28, 35, compat_prefix, "compat_v", "9–15")):
        raise ValueError("Invalid distribution identity")
    return profile


def build_flavor(distribution):
    return {"main": "modern", "compat": "compat"}[distribution]
