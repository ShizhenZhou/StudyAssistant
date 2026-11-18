"""Read the same checked-in distribution selector/profiles as Gradle; never infer from branch names."""
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


def source_distribution(root, selected=None):
    root = Path(root)
    selector = properties(root / "config/distribution.properties")
    name = selected if selected is not None else selector.get("distribution")
    if set(selector) != {"distribution"} or name not in ("main", "compat"):
        raise ValueError("Invalid distribution selector")
    profile = properties(root / f"config/distributions/{name}.properties")
    expected = {"distribution", "versionName", "versionCode", "minSdk", "maxSdk", "androidLabel", "apkPrefix", "tagPrefix"}
    if set(profile) != expected or profile["distribution"] != name:
        raise ValueError("Invalid distribution profile")
    for key in ("versionCode", "minSdk", "maxSdk"):
        profile[key] = int(profile[key])
    if not re.fullmatch(r"[0-9]+(?:\.[0-9]+){2,3}(?:_beta(?:[1-9][0-9]*)?)?", profile["versionName"]) or profile["versionCode"] <= 0:
        raise ValueError("Invalid distribution version")
    identity = (profile["minSdk"], profile["maxSdk"], profile["apkPrefix"], profile["tagPrefix"], profile["androidLabel"])
    if identity != ((36, 0, "SA", "v", "16+") if name == "main" else (28, 35, "SACompat", "compat_v", "9–15")):
        raise ValueError("Invalid distribution identity")
    return profile
