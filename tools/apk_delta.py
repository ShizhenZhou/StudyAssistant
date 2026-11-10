"""Exact signed-APK deltas using GDIFF v4 commands inside a gzip stream.

Wire format: https://www.w3.org/TR/NOTE-gdiff-19970901
No ZIP rewriting or signing takes place: applying a delta restores every APK byte.
"""
import argparse
import gzip
import hashlib
import io
import json
from pathlib import Path
import struct

MAGIC = bytes.fromhex("d1ffd1ff04")
FORMAT = "gdiff4-gzip"
MAX_APK_SIZE = 100_000_000


def digest(data):
    return hashlib.sha256(data).hexdigest()


def encode(base, target, block_size=32):
    """Index aligned base blocks, then scan target bytewise to tolerate insertions."""
    if not 0 < len(base) <= MAX_APK_SIZE or not 0 < len(target) <= MAX_APK_SIZE:
        raise ValueError("APK size is outside the supported range")
    index = {}
    for offset in range(0, len(base) - block_size + 1, block_size):
        index.setdefault(base[offset:offset + block_size], offset)
    output = io.BytesIO(); output.write(MAGIC)
    cursor = literal = 0

    def data(start, end):
        length = end - start
        if not length: return
        if length <= 246: output.write(bytes([length]))
        elif length <= 65535: output.write(b"\xf7" + struct.pack(">H", length))
        else: output.write(b"\xf8" + struct.pack(">i", length))
        output.write(target[start:end])

    while cursor + block_size <= len(target):
        source = index.get(target[cursor:cursor + block_size])
        if source is None:
            cursor += 1
            continue
        length = block_size
        limit = min(len(base) - source, len(target) - cursor)
        # Large equal runs are checked in C, without a Python loop per APK byte.
        while length + 65536 <= limit and base[source + length:source + length + 65536] == target[cursor + length:cursor + length + 65536]:
            length += 65536
        low, high = length, min(length + 65536, limit)
        while low < high:
            middle = (low + high + 1) // 2
            if base[source + length:source + middle] == target[cursor + length:cursor + middle]: low = middle
            else: high = middle - 1
        length = low
        data(literal, cursor)
        output.write(b"\xfe" + struct.pack(">ii", source, length))
        cursor += length
        literal = cursor
    data(literal, len(target)); output.write(b"\x00")
    return gzip.compress(output.getvalue(), compresslevel=9, mtime=0)


def apply(base, patch, target_size):
    """Strict independent decoder used to verify artifacts before publication."""
    if not 0 < target_size <= MAX_APK_SIZE: raise ValueError("Invalid target size")
    source = gzip.GzipFile(fileobj=io.BytesIO(patch))
    def read(size):
        result = source.read(size)
        if len(result) != size: raise ValueError("Truncated GDIFF stream")
        return result
    def number(fmt): return struct.unpack(fmt, read(struct.calcsize(fmt)))[0]
    if read(5) != MAGIC: raise ValueError("Invalid GDIFF header")
    output = bytearray()
    copy_types = {249: (">H", ">B"), 250: (">H", ">H"), 251: (">H", ">i"),
                  252: (">i", ">B"), 253: (">i", ">H"), 254: (">i", ">i"), 255: (">q", ">i")}
    while True:
        opcode = read(1)[0]
        if opcode == 0: break
        offset = None
        if opcode <= 246: length = opcode
        elif opcode == 247: length = number(">H")
        elif opcode == 248: length = number(">i")
        else:
            position_type, length_type = copy_types[opcode]
            offset, length = number(position_type), number(length_type)
        if length <= 0 or length > target_size - len(output): raise ValueError("Invalid GDIFF output length")
        if offset is None: output.extend(read(length))
        else:
            if offset < 0 or offset > len(base) - length: raise ValueError("GDIFF copy outside base APK")
            output.extend(base[offset:offset + length])
    if len(output) != target_size or source.read(1): raise ValueError("GDIFF output length or trailing data mismatch")
    return bytes(output)


def read_base(apk):
    apk = Path(apk).resolve()
    metadata = json.loads(apk.with_name(apk.stem + "-update-manifest.json").read_text(encoding="utf-8"))
    data = apk.read_bytes()
    if (metadata.get("schema") != 1 or metadata.get("channel", "stable") != "stable" or
        metadata.get("apkName") != apk.name or metadata.get("apkSha256") != digest(data) or
        metadata.get("apkSize") != len(data) or type(metadata.get("versionCode")) is not int):
        raise ValueError("Base APK differs from its archived stable release manifest")
    return dict(path=str(apk), baseSha256=digest(data), baseSize=len(data), baseVersionCode=metadata["versionCode"])


def build(base_record, target_apk, target_version_code, output_dir, expected=None):
    base = Path(base_record["path"]).read_bytes()
    target_apk, output_dir = Path(target_apk), Path(output_dir)
    target = target_apk.read_bytes()
    if digest(base) != base_record["baseSha256"] or len(base) != base_record["baseSize"]:
        raise ValueError("Base APK changed since release start")
    if base_record["baseVersionCode"] >= target_version_code: raise ValueError("Delta base must be older than target")
    patch = encode(base, target)
    if apply(base, patch, len(target)) != target: raise ValueError("Delta did not exactly reconstruct signed APK")
    if len(patch) >= len(target): raise ValueError("Delta is not smaller than the complete APK; omit --delta-base")
    name = target_apk.stem + f"-from-{base_record['baseVersionCode']}.gdiff.gz"
    metadata = dict(format=FORMAT, baseSha256=base_record["baseSha256"], baseSize=len(base),
                    baseVersionCode=base_record["baseVersionCode"], patchName=name, patchSha256=digest(patch), patchSize=len(patch))
    if expected is not None and metadata != expected:
        raise ValueError("Restored delta differs from the frozen release metadata")
    path = output_dir / name
    output_dir.mkdir(parents=True, exist_ok=True)
    if path.exists() and path.read_bytes() != patch: raise ValueError("Delta artifact already exists with different bytes")
    path.write_bytes(patch)
    return metadata


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", required=True, type=Path)
    parser.add_argument("--target", required=True, type=Path)
    parser.add_argument("--target-version-code", required=True, type=int)
    parser.add_argument("--output-dir", required=True, type=Path)
    args = parser.parse_args()
    result = build(read_base(args.base), args.target, args.target_version_code, args.output_dir)
    print(json.dumps(result, indent=2))


if __name__ == "__main__": main()
