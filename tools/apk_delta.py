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


def encode_matched(base, target, block_size=16):
    """Bounded alternative matcher; emits only commands already supported by old clients."""
    if not 0 < len(base) <= MAX_APK_SIZE or not 0 < len(target) <= MAX_APK_SIZE:
        raise ValueError("APK size is outside the supported range")
    if block_size not in (16, 32): raise ValueError("Unsupported matching block size")
    # Bound index entries for larger APKs. Repeated seeds retain both ends so an
    # early short match does not hide a later, much longer identical region.
    stride = block_size * max(1, (len(base) + block_size * 500_000 - 1) // (block_size * 500_000))
    index = {}
    for offset in range(0, len(base) - block_size + 1, stride):
        seed = base[offset:offset + block_size]
        previous = index.get(seed)
        index[seed] = offset if previous is None else (previous if isinstance(previous, int) else previous[0], offset)
    output = io.BytesIO(); output.write(MAGIC)
    cursor = literal = commands = 0
    continuation = -1

    def data(start, end):
        if end == start: return
        length = end - start
        if length <= 246: output.write(bytes([length]))
        elif length <= 65535: output.write(b"\xf7" + struct.pack(">H", length))
        else: output.write(b"\xf8" + struct.pack(">i", length))
        output.write(target[start:end])

    def extent(source, at):
        length = block_size
        limit = min(len(base) - source, len(target) - at)
        while length + 65536 <= limit and base[source + length:source + length + 65536] == target[at + length:at + length + 65536]:
            length += 65536
        low, high = length, min(length + 65536, limit)
        while low < high:
            middle = (low + high + 1) // 2
            if base[source + length:source + middle] == target[at + length:at + middle]: low = middle
            else: high = middle - 1
        return low

    while cursor + block_size <= len(target) and commands < 999_998:
        seed = target[cursor:cursor + block_size]
        positions = index.get(seed)
        candidates = [] if positions is None else [positions] if isinstance(positions, int) else list(positions)
        if continuation >= 0 and base[continuation:continuation + block_size] == seed and continuation not in candidates:
            candidates.append(continuation)
        if not candidates:
            cursor += 1
            if continuation >= 0: continuation += 1
            continue
        source, length = max(((p, extent(p, cursor)) for p in candidates), key=lambda pair: pair[1])
        # Include equal bytes before the aligned seed, without crossing a prior
        # emitted command. At most one index stride of look-behind per match.
        back = 0
        while back < min(source, cursor - literal, stride) and base[source - back - 1] == target[cursor - back - 1]:
            back += 1
        source -= back; cursor -= back; length += back
        if cursor > literal: commands += 1
        data(literal, cursor)
        # Compact GDIFF offset/length widths reduce command overhead, not format compatibility.
        offset_format, opcode = (">H", 249) if source <= 65535 else (">i", 252)
        length_format, extra = (">B", 0) if length <= 255 else (">H", 1) if length <= 65535 else (">i", 2)
        output.write(bytes([opcode + extra]) + struct.pack(offset_format, source) + struct.pack(length_format, length))
        commands += 1
        cursor += length; literal = cursor; continuation = source + length
    data(literal, len(target)); output.write(b"\x00")
    return gzip.compress(output.getvalue(), compresslevel=9, mtime=0)


def encode_candidates(base, target):
    """Generate sequentially so indexes and all candidate patches do not accumulate."""
    yield encode(base, target)
    for block_size in (16, 32):
        yield encode_matched(base, target, block_size)


def encode_best(base, target):
    """Compare three deterministic candidates; never exceed the previous encoder's size."""
    return min(encode_candidates(base, target), key=len)


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
    if (metadata.get("schema") != 1 or metadata.get("channel", "stable") not in ("stable", "beta") or
        metadata.get("apkName") != apk.name or metadata.get("apkSha256") != digest(data) or
        metadata.get("apkSize") != len(data) or type(metadata.get("versionCode")) is not int):
        raise ValueError("Base APK differs from its archived release manifest")
    return dict(path=str(apk), baseSha256=digest(data), baseSize=len(data), baseVersionCode=metadata["versionCode"])


def build(base_record, target_apk, target_version_code, output_dir, expected=None, allow_same_version=False):
    base = Path(base_record["path"]).read_bytes()
    target_apk, output_dir = Path(target_apk), Path(output_dir)
    target = target_apk.read_bytes()
    if digest(base) != base_record["baseSha256"] or len(base) != base_record["baseSize"]:
        raise ValueError("Base APK changed since release start")
    base_code = base_record["baseVersionCode"]
    if base_code > target_version_code or (base_code == target_version_code and not allow_same_version):
        raise ValueError("Delta base must be older than target unless explicitly replacing the same version")
    if base == target: raise ValueError("Delta target is identical to its base")
    # A resumed release must restore its exact frozen artifact, not silently
    # replace it with today's smaller encoding. Stop as soon as its encoder matches.
    if expected is None:
        patch = encode_best(base, target)
    else:
        patch = next((candidate for candidate in encode_candidates(base, target)
                      if len(candidate) == expected.get("patchSize") and digest(candidate) == expected.get("patchSha256")), None)
        if patch is None: raise ValueError("Restored delta differs from the frozen release metadata")
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
