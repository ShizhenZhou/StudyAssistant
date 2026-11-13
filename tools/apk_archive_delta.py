"""APK-aware GDIFF: inflate selected DEX payloads, retain every other signed byte.

The gzip body is SAZ1, a big-endian uint32 JSON size, the UTF-8 header,
then one raw GDIFF4 stream. ZIP paths are never extracted to the filesystem.
"""
import gzip
import io
import json
from pathlib import Path
import re
import struct
import zipfile
import zlib

import apk_delta

FORMAT = "apk-gdiff1-gzip"
MAGIC = b"SAZ1"
MAX_HEADER = 65536
MAX_RANGES = 16
MAX_RAW = 64 * 1024 * 1024
MAX_VIRTUAL = 256 * 1024 * 1024
CHUNK = 65536


class NotApplicable(ValueError):
    """No bounded, exactly reproducible compressed DEX is available."""


def deflate(raw, level):
    compressor = zlib.compressobj(level, zlib.DEFLATED, -15)
    result = bytearray()
    for offset in range(0, len(raw), CHUNK):
        result.extend(compressor.compress(raw[offset:offset + CHUNK]))
    result.extend(compressor.flush())
    return bytes(result)


def inflate(payload, raw_size):
    inflater = zlib.decompressobj(-15)
    result = bytearray()
    try:
        for offset in range(0, len(payload), CHUNK):
            result.extend(inflater.decompress(payload[offset:offset + CHUNK], raw_size - len(result) + 1))
            if len(result) > raw_size or inflater.unconsumed_tail or inflater.unused_data:
                raise ValueError("Invalid DEX compressed segment")
        if not inflater.eof or len(result) != raw_size:
            raise ValueError("DEX compressed segment length mismatch")
    except zlib.error as error:
        raise ValueError("Invalid DEX deflate stream") from error
    return bytes(result)


def validate_ranges(ranges, size, target=False):
    if not isinstance(ranges, list) or len(ranges) > MAX_RANGES:
        raise ValueError("Too many APK transform ranges")
    end = 0
    expanded = size
    for item in ranges:
        keys = ("offset", "compressedSize", "rawSize") + (("level",) if target else ())
        if not isinstance(item, dict) or any(type(item.get(key)) is not int for key in keys):
            raise ValueError("Invalid APK range fields")
        offset, compressed, raw = (item[key] for key in keys[:3])
        if offset < end or compressed <= 0 or offset > size - compressed or not 0 < raw <= MAX_RAW:
            raise ValueError("Invalid APK transform range")
        if target and not 1 <= item["level"] <= 9: raise ValueError("Invalid deflate level")
        end = offset + compressed
        expanded += raw - compressed
    if not 0 < expanded <= MAX_VIRTUAL: raise ValueError("APK virtual stream is too large")
    return expanded


def dex_ranges(apk):
    """Read central-directory sizes and local-header payload coordinates only."""
    found = {}
    try:
        with zipfile.ZipFile(io.BytesIO(apk)) as archive:
            for entry in archive.infolist():
                if not re.fullmatch(r"classes(?:[0-9]+)?\.dex", entry.filename): continue
                if entry.filename in found: raise ValueError("Duplicate DEX entry")
                if entry.compress_type != zipfile.ZIP_DEFLATED or entry.flag_bits & 1: continue
                if not 0 < entry.file_size <= MAX_RAW: continue
                start = entry.header_offset
                if start < 0 or start > len(apk) - 30 or apk[start:start + 4] != b"PK\x03\x04":
                    raise ValueError("Invalid DEX local header")
                if struct.unpack_from("<H", apk, start + 8)[0] != zipfile.ZIP_DEFLATED:
                    raise ValueError("DEX compression differs between ZIP headers")
                name_size, extra_size = struct.unpack_from("<HH", apk, start + 26)
                offset = start + 30 + name_size + extra_size
                record = dict(offset=offset, compressedSize=entry.compress_size, rawSize=entry.file_size)
                validate_ranges([record], len(apk))
                found[entry.filename] = record
    except zipfile.BadZipFile as error:
        raise NotApplicable("Not a supported APK ZIP") from error
    return found


def virtualize(apk, ranges):
    validate_ranges(ranges, len(apk))
    output = bytearray()
    cursor = 0
    for item in ranges:
        offset, size = item["offset"], item["compressedSize"]
        output.extend(apk[cursor:offset])
        output.extend(inflate(apk[offset:offset + size], item["rawSize"]))
        cursor = offset + size
    output.extend(apk[cursor:])
    return bytes(output)


def prepare(base, target):
    if not 0 < len(base) <= apk_delta.MAX_APK_SIZE or not 0 < len(target) <= apk_delta.MAX_APK_SIZE:
        raise ValueError("APK size is outside the supported range")
    base_entries, target_entries = dex_ranges(base), dex_ranges(target)
    base_ranges, target_ranges = [], []
    for name, item in sorted(target_entries.items(), key=lambda pair: pair[1]["offset"]):
        if len(target_ranges) == MAX_RANGES: break
        payload = target[item["offset"]:item["offset"] + item["compressedSize"]]
        raw = inflate(payload, item["rawSize"])
        level = next((level for level in range(1, 10) if deflate(raw, level) == payload), None)
        if level is None: continue
        candidate_target = target_ranges + [dict(item, level=level)]
        candidate_base = base_ranges + ([base_entries[name]] if name in base_entries else [])
        candidate_base.sort(key=lambda record: record["offset"])
        try:
            validate_ranges(candidate_base, len(base))
            validate_ranges(candidate_target, len(target), target=True)
        except ValueError:
            continue
        base_ranges, target_ranges = candidate_base, candidate_target
    if not target_ranges: raise NotApplicable("No exactly reproducible compressed DEX")
    header = dict(baseSize=len(base), targetSize=len(target), baseRanges=base_ranges, targetRanges=target_ranges)
    return header, virtualize(base, base_ranges), virtualize(target, target_ranges)


def encode_candidates(base, target):
    header, virtual_base, virtual_target = prepare(base, target)
    encoded = json.dumps(header, separators=(",", ":")).encode("utf-8")
    if not 0 < len(encoded) <= MAX_HEADER: raise ValueError("APK delta header too large")
    prefix = MAGIC + struct.pack(">I", len(encoded)) + encoded
    for block_size in (16, 32):
        raw = apk_delta.encode_matched(virtual_base, virtual_target, block_size, raw=True, max_size=MAX_VIRTUAL)
        yield gzip.compress(prefix + raw, compresslevel=9, mtime=0)


def encode(base, target):
    return min(encode_candidates(base, target), key=len)


def apply(base, patch, target_size):
    if not 0 < len(base) <= apk_delta.MAX_APK_SIZE or not 0 < target_size <= apk_delta.MAX_APK_SIZE:
        raise ValueError("Invalid APK size")
    with gzip.GzipFile(fileobj=io.BytesIO(patch)) as source:
        prefix = source.read(8)
        if len(prefix) != 8 or prefix[:4] != MAGIC: raise ValueError("Invalid APK delta header")
        header_size = struct.unpack(">I", prefix[4:])[0]
        if not 0 < header_size <= MAX_HEADER: raise ValueError("Invalid APK delta header length")
        encoded = source.read(header_size)
        if len(encoded) != header_size: raise ValueError("Truncated APK delta header")
        header = json.loads(encoded.decode("utf-8"))
        if (not isinstance(header, dict) or type(header.get("baseSize")) is not int or
                type(header.get("targetSize")) is not int or header["baseSize"] != len(base) or header["targetSize"] != target_size):
            raise ValueError("APK delta size mismatch")
        validate_ranges(header.get("baseRanges"), len(base))
        virtual_size = validate_ranges(header.get("targetRanges"), target_size, target=True)
        virtual_base = virtualize(base, header["baseRanges"])
        virtual_target = apk_delta.apply_raw(virtual_base, source, virtual_size, max_size=MAX_VIRTUAL)
    output = bytearray()
    cursor = 0
    for item in header["targetRanges"]:
        unchanged = item["offset"] - len(output)
        output.extend(virtual_target[cursor:cursor + unchanged])
        cursor += unchanged
        compressed = deflate(virtual_target[cursor:cursor + item["rawSize"]], item["level"])
        if len(compressed) != item["compressedSize"]: raise ValueError("Recompressed DEX size mismatch")
        output.extend(compressed)
        cursor += item["rawSize"]
    output.extend(virtual_target[cursor:])
    if len(output) != target_size: raise ValueError("Reconstructed APK size mismatch")
    return bytes(output)


def build(base_record, target_apk, target_version_code, output_dir, expected=None, allow_same_version=False):
    target_apk, output_dir = Path(target_apk), Path(output_dir)
    base, target = Path(base_record["path"]).read_bytes(), target_apk.read_bytes()
    if apk_delta.digest(base) != base_record["baseSha256"] or len(base) != base_record["baseSize"]:
        raise ValueError("Base APK changed since release start")
    base_code = base_record["baseVersionCode"]
    if base_code > target_version_code or (base_code == target_version_code and not allow_same_version):
        raise ValueError("Delta base must be older than target unless explicitly replacing the same version")
    if base == target: raise ValueError("Delta target is identical to its base")
    if expected is None:
        patch = encode(base, target)
    else:
        patch = next((candidate for candidate in encode_candidates(base, target)
                      if len(candidate) == expected.get("patchSize") and apk_delta.digest(candidate) == expected.get("patchSha256")), None)
        if patch is None: raise ValueError("Restored delta differs from the frozen release metadata")
    if apply(base, patch, len(target)) != target: raise ValueError("Delta did not exactly reconstruct signed APK")
    if len(patch) >= len(target): raise NotApplicable("Archive delta is not smaller than complete APK")
    name = target_apk.stem + f"-zip-from-{base_code}.gdiff.gz"
    metadata = dict(format=FORMAT, baseSha256=base_record["baseSha256"], baseSize=len(base),
                    baseVersionCode=base_code, patchName=name, patchSha256=apk_delta.digest(patch), patchSize=len(patch))
    if expected is not None and metadata != expected: raise ValueError("Restored delta differs from the frozen release metadata")
    output_dir.mkdir(parents=True, exist_ok=True)
    path = output_dir / name
    if path.exists() and path.read_bytes() != patch: raise ValueError("Delta artifact already exists with different bytes")
    path.write_bytes(patch)
    return metadata
