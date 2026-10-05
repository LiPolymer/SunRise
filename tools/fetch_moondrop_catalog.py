#!/usr/bin/env python3
"""Explicitly fetch a complete, readable MOONDROP product and response offline catalog.

This maintenance tool is deliberately not invoked by application builds or startup.
Unsupported frequency-response text is retained unchanged for the shared codec to
report as unavailable; missing downloads or invalid structure abort the operation.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import tempfile
from datetime import datetime, timezone
from http.client import HTTPException
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import HTTPRedirectHandler, Request, build_opener


FORMAT = "sunrise-moondrop-catalog"
SCHEMA_VERSION = 3
CATALOGUE_URL = "https://cdn-service.moondroplab.tech/api/v1/products/all"
RESPONSE_LIBRARY_URL = "https://cdn-service.moondroplab.tech/api/v1/responselib/allwithtag"
CDN_BASE_URLS = {
    "china": "https://cdn.moondroplab.tech/",
    "overseas": "https://kaigai.cdn.moondroplab.tech/",
}
MAX_SNAPSHOT_BYTES = 64 * 1024 * 1024
MAX_ASSET_BYTES = 2 * 1024 * 1024
MAX_TOTAL_ASSET_BYTES = 48 * 1024 * 1024
HTTP_TIMEOUT_SECONDS = 30
READ_CHUNK_BYTES = 64 * 1024
DEFAULT_OUTPUT = (
    Path(__file__).resolve().parent.parent
    / "shared/composeResources/files/moondrop-catalog.snapshot.json"
)
UUID_PATTERN = re.compile(
    r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
    r"[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\Z"
)
HASH_PATTERN = re.compile(r"[0-9a-f]{64}\Z")


class CatalogError(ValueError):
    """A download or document cannot be used as a complete snapshot."""


class NoRedirects(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def reject_json_constant(value: str) -> None:
    raise CatalogError(f"Non-JSON numeric constant: {value}")


def parse_json(bytes_: bytes, label: str) -> Any:
    try:
        return json.loads(
            bytes_.decode("utf-8-sig", errors="strict"),
            parse_constant=reject_json_constant,
        )
    except (UnicodeError, json.JSONDecodeError, RecursionError) as error:
        raise CatalogError(f"{label} is not valid UTF-8 JSON: {error}") from error


def require_object(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise CatalogError(f"{label} must be an object")
    return value


def require_string(value: Any, label: str) -> str:
    if not isinstance(value, str):
        raise CatalogError(f"{label} must be a string")
    return value


def optional_string(product: dict[str, Any], key: str, label: str) -> str | None:
    value = product.get(key)
    if value is None:
        return None
    return require_string(value, f"{label}.{key}")


def validate_response_path(value: Any) -> str:
    path = require_string(value, "frequency-response path")
    if not path.strip():
        raise CatalogError("Frequency-response path must not be blank")
    if (
        path.startswith("/")
        or any(character in path for character in "\\:?#")
        or any(
            ord(character) < 32
            or 127 <= ord(character) <= 159
            or 0xD800 <= ord(character) <= 0xDFFF
            for character in path
        )
        or any(segment in (".", "..") for segment in path.split("/"))
    ):
        raise CatalogError(f"Unsafe frequency-response path: {path!r}")
    return path


def parse_catalogue(bytes_: bytes) -> tuple[list[dict[str, Any]], list[str]]:
    if len(bytes_) > MAX_ASSET_BYTES:
        raise CatalogError("Catalogue exceeds the 2 MiB limit")
    catalogue = require_object(parse_json(bytes_, "Catalogue"), "Catalogue")
    code = catalogue.get("code")
    if type(code) is not int or code != 0:
        raise CatalogError("Catalogue must have integer code=0")
    products = catalogue.get("data")
    if not isinstance(products, list) or not products:
        raise CatalogError("Catalogue data must be a nonempty array")
    uuids: set[str] = set()
    paths: set[str] = set()
    for index, value in enumerate(products):
        label = f"Catalogue data[{index}]"
        product = require_object(value, label)
        uuid = require_string(product.get("uuid"), f"{label}.uuid")
        if not UUID_PATTERN.fullmatch(uuid):
            raise CatalogError(f"{label}.uuid is not a valid UUID")
        if uuid.lower() in uuids:
            raise CatalogError(f"Duplicate product UUID: {uuid}")
        uuids.add(uuid.lower())
        name = require_string(product.get("name"), f"{label}.name")
        if not name.strip():
            raise CatalogError(f"{label}.name must not be blank")
        product_type = require_string(product.get("type"), f"{label}.type")
        if not product_type.strip():
            raise CatalogError(f"{label}.type must not be blank")
        optional_string(product, "model", label)
        optional_string(product, "languageType", label)
        path = optional_string(product, "freqResponse", label)
        if path is not None and path.strip():
            paths.add(validate_response_path(path))
    return products, sorted(paths)


def parse_response_library(bytes_: bytes) -> tuple[list[dict[str, Any]], list[str]]:
    if len(bytes_) > MAX_ASSET_BYTES:
        raise CatalogError("Response library exceeds the 2 MiB limit")
    library = require_object(parse_json(bytes_, "Response library"), "Response library")
    code = library.get("code")
    if type(code) is not int or code != 0:
        raise CatalogError("Response library must have integer code=0")
    entries = library.get("data")
    if not isinstance(entries, list):
        raise CatalogError("Response library data must be an array")
    uuids: set[str] = set()
    paths: set[str] = set()
    for index, value in enumerate(entries):
        label = f"Response library data[{index}]"
        entry = require_object(value, label)
        uuid = require_string(entry.get("uuid"), f"{label}.uuid")
        if not UUID_PATTERN.fullmatch(uuid):
            raise CatalogError(f"{label}.uuid is not a valid UUID")
        if uuid.lower() in uuids:
            raise CatalogError(f"Duplicate response library UUID: {uuid}")
        uuids.add(uuid.lower())
        name = require_string(entry.get("name"), f"{label}.name")
        if not name.strip():
            raise CatalogError(f"{label}.name must not be blank")
        paths.add(validate_response_path(entry.get("file")))
        tags = entry.get("tags")
        if not isinstance(tags, list) or any(not isinstance(tag, str) for tag in tags):
            raise CatalogError(f"{label}.tags must be an array of strings")
    return entries, sorted(paths)


def merge_products(
    products: list[dict[str, Any]], library: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    uuids = {product["uuid"].lower() for product in products}
    merged = list(products)
    for entry in library:
        uuid = entry["uuid"]
        if uuid.lower() in uuids:
            raise CatalogError(f"Conflicting product and response library UUID: {uuid}")
        uuids.add(uuid.lower())
        merged.append({
            **entry,
            "type": "Response",
            "model": None,
            "languageType": None,
            "freqResponse": entry["file"],
        })
    return merged


def fetch_bytes(opener, url: str, max_bytes: int) -> bytes:
    request = Request(
        url,
        headers={
            "Accept": "*/*",
            "Accept-Encoding": "identity",
            "User-Agent": "SunRise-offline-catalog/1",
        },
        method="GET",
    )
    try:
        with opener.open(request, timeout=HTTP_TIMEOUT_SECONDS) as response:
            if response.status != 200:
                raise CatalogError(f"HTTP {response.status} for {url}")
            content_length = response.headers.get("Content-Length")
            expected_length = None
            if content_length is not None:
                try:
                    expected_length = int(content_length)
                except ValueError as error:
                    raise CatalogError(f"Invalid Content-Length for {url}") from error
                if expected_length < 0 or expected_length > max_bytes:
                    raise CatalogError(f"Download exceeds byte limit ({max_bytes}): {url}")
            result = bytearray()
            while True:
                chunk = response.read(min(READ_CHUNK_BYTES, max_bytes - len(result) + 1))
                if not chunk:
                    break
                result.extend(chunk)
                if len(result) > max_bytes:
                    raise CatalogError(f"Download exceeds byte limit ({max_bytes}): {url}")
            if expected_length is not None and len(result) != expected_length:
                raise CatalogError(f"Incomplete download for {url}")
            return bytes(result)
    except HTTPError as error:
        error.close()
        raise CatalogError(f"HTTP {error.code} for {url}; redirects are not followed") from error
    except (URLError, OSError, HTTPException) as error:
        raise CatalogError(f"Download failed for {url}: {error}") from error


def encode_asset(bytes_: bytes) -> dict[str, Any]:
    try:
        text = bytes_.decode("utf-8", errors="strict")
        encoding = "utf-8"
    except UnicodeDecodeError:
        # Reversible byte mapping; do not guess the vendor's original character set.
        text = bytes_.decode("iso-8859-1")
        encoding = "iso-8859-1"
    return {
        "sha256": hashlib.sha256(bytes_).hexdigest(),
        "encoding": encoding,
        "lines": text.split("\n"),
    }


def decode_asset(value: Any, label: str) -> bytes:
    asset = require_object(value, label)
    hash_ = require_string(asset.get("sha256"), f"{label}.sha256")
    if not HASH_PATTERN.fullmatch(hash_):
        raise CatalogError(f"{label}.sha256 must be 64 lowercase hexadecimal characters")
    encoding = require_string(asset.get("encoding"), f"{label}.encoding")
    if encoding not in ("utf-8", "iso-8859-1"):
        raise CatalogError(f"{label} has unsupported encoding: {encoding}")
    lines = asset.get("lines")
    if not isinstance(lines, list) or not lines:
        raise CatalogError(f"{label}.lines must be a nonempty array of strings")
    text_length = len(lines) - 1
    for line in lines:
        if not isinstance(line, str) or "\n" in line:
            raise CatalogError(f"{label}.lines must contain strings without LF")
        text_length += len(line)
        if text_length > MAX_ASSET_BYTES:
            raise CatalogError(f"{label} exceeds the 2 MiB decoded limit")
    try:
        bytes_ = "\n".join(lines).encode(encoding, errors="strict")
    except UnicodeError as error:
        raise CatalogError(f"{label} text cannot be represented as {encoding}") from error
    if len(bytes_) > MAX_ASSET_BYTES:
        raise CatalogError(f"{label} exceeds the 2 MiB decoded limit")
    if hashlib.sha256(bytes_).hexdigest() != hash_:
        raise CatalogError(f"{label} SHA-256 mismatch")
    return bytes_


def validate_snapshot(document_bytes: bytes) -> tuple[int, int, int, int]:
    """Validate the actual serialized exchange bytes before touching the output."""
    if len(document_bytes) > MAX_SNAPSHOT_BYTES:
        raise CatalogError("Snapshot exceeds the 64 MiB limit")
    snapshot = require_object(parse_json(document_bytes, "Snapshot"), "Snapshot")
    if snapshot.get("format") != FORMAT:
        raise CatalogError(f"Snapshot format is not {FORMAT}")
    version = snapshot.get("schemaVersion")
    if type(version) is not int or version != SCHEMA_VERSION:
        raise CatalogError("Unsupported schemaVersion; a compatible application is required")
    retrieved_at = require_string(snapshot.get("retrievedAt"), "Snapshot.retrievedAt")
    try:
        timestamp = datetime.fromisoformat(
            retrieved_at[:-1] + "+00:00" if retrieved_at.endswith("Z") else retrieved_at
        )
    except ValueError as error:
        raise CatalogError("Snapshot.retrievedAt must be a UTC ISO-8601 timestamp") from error
    if timestamp.utcoffset() != timezone.utc.utcoffset(timestamp):
        raise CatalogError("Snapshot.retrievedAt must be a UTC ISO-8601 timestamp")
    if snapshot.get("catalogueUrl") != CATALOGUE_URL:
        raise CatalogError("Snapshot must use the official catalogue URL")
    if snapshot.get("responseLibraryUrl") != RESPONSE_LIBRARY_URL:
        raise CatalogError("Snapshot must use the official response library URL")
    if snapshot.get("cdnBaseUrl") not in CDN_BASE_URLS.values():
        raise CatalogError("Snapshot must use the selected official CDN")
    catalogue = require_object(snapshot.get("catalogue"), "Snapshot.catalogue")
    catalogue_bytes = json.dumps(catalogue, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8")
    products, catalogue_paths = parse_catalogue(catalogue_bytes)
    library = require_object(snapshot.get("responseLibrary"), "Snapshot.responseLibrary")
    library_bytes = json.dumps(library, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8")
    entries, library_paths = parse_response_library(library_bytes)
    products = merge_products(products, entries)
    expected_paths = set(catalogue_paths) | set(library_paths)
    assets = snapshot.get("responseFiles")
    if not isinstance(assets, list):
        raise CatalogError("Snapshot.responseFiles must be an array")
    found_paths: set[str] = set()
    total_bytes = len(catalogue_bytes) + len(library_bytes)
    for index, value in enumerate(assets):
        label = f"Snapshot.responseFiles[{index}]"
        asset = require_object(value, label)
        path = validate_response_path(asset.get("path"))
        if path in found_paths:
            raise CatalogError(f"Duplicate responseFiles path: {path!r}")
        found_paths.add(path)
        total_bytes += len(decode_asset(asset, label))
        if total_bytes > MAX_TOTAL_ASSET_BYTES:
            raise CatalogError("Decoded assets exceed the 48 MiB total limit")
    if found_paths != expected_paths:
        missing = sorted(expected_paths - found_paths)
        extra = sorted(found_paths - expected_paths)
        raise CatalogError(f"Response asset set mismatch; missing={missing!r}, extra={extra!r}")
    names = {" ".join(product["name"].split()).lower() for product in products}
    return len(products), len(names), len(found_paths), total_bytes


def write_atomically(output: Path, document_bytes: bytes) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary_path = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="wb", dir=output.parent, prefix=f".{output.name}.", suffix=".tmp", delete=False
        ) as temporary:
            temporary_path = Path(temporary.name)
            temporary.write(document_bytes)
            temporary.flush()
            os.fsync(temporary.fileno())
        os.replace(temporary_path, output)
        temporary_path = None
    finally:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)


def fetch_snapshot(cdn: str, output: Path) -> None:
    opener = build_opener(NoRedirects())
    print(f"Fetching product catalogue: {CATALOGUE_URL}", flush=True)
    catalogue_bytes = fetch_bytes(opener, CATALOGUE_URL, MAX_ASSET_BYTES)
    products, catalogue_paths = parse_catalogue(catalogue_bytes)
    print(f"Fetching response library: {RESPONSE_LIBRARY_URL}", flush=True)
    library_bytes = fetch_bytes(opener, RESPONSE_LIBRARY_URL, MAX_ASSET_BYTES)
    entries, library_paths = parse_response_library(library_bytes)
    merge_products(products, entries)
    paths = sorted(set(catalogue_paths) | set(library_paths))
    base_url = CDN_BASE_URLS[cdn]
    total_bytes = len(catalogue_bytes) + len(library_bytes)
    response_files = []
    for index, path in enumerate(paths, start=1):
        print(f"Fetching response {index}/{len(paths)}: {path}", flush=True)
        # Encode the raw relative path exactly once, including any literal '%'.
        url = base_url + quote(path, safe="/")
        remaining = MAX_TOTAL_ASSET_BYTES - total_bytes
        bytes_ = fetch_bytes(opener, url, min(MAX_ASSET_BYTES, remaining))
        total_bytes += len(bytes_)
        response_files.append({"path": path, **encode_asset(bytes_)})
    snapshot = {
        "format": FORMAT,
        "schemaVersion": SCHEMA_VERSION,
        "retrievedAt": datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
        "catalogueUrl": CATALOGUE_URL,
        "responseLibraryUrl": RESPONSE_LIBRARY_URL,
        "cdnBaseUrl": base_url,
        "catalogue": parse_json(catalogue_bytes, "Catalogue"),
        "responseLibrary": parse_json(library_bytes, "Response library"),
        "responseFiles": response_files,
    }
    document_bytes = (json.dumps(snapshot, ensure_ascii=False, indent=4, allow_nan=False) + "\n").encode("utf-8")
    records, names, assets, decoded_bytes = validate_snapshot(document_bytes)
    write_atomically(output, document_bytes)
    print(f"Saved complete snapshot: {output}")
    print(f"Retrieved at: {snapshot['retrievedAt']}; CDN: {cdn} ({base_url})")
    print(f"Catalog records: {records}; normalized names: {names}; downloaded response assets: {assets}")
    print(f"Catalogue bytes: {len(catalogue_bytes)}; response library bytes: {len(library_bytes)}")
    print(f"Total decoded metadata and asset bytes: {decoded_bytes}")
    print(f"Snapshot bytes: {len(document_bytes)}; SHA-256: {hashlib.sha256(document_bytes).hexdigest()}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--cdn", choices=tuple(CDN_BASE_URLS), default="china",
        help="CDN used for response assets (default: china; no automatic fallback)",
    )
    parser.add_argument(
        "--output", type=Path, default=DEFAULT_OUTPUT,
        help="snapshot output path (default: bundled resource, relative to this script)",
    )
    args = parser.parse_args()
    try:
        fetch_snapshot(args.cdn, args.output)
    except (CatalogError, OSError, ValueError, RecursionError) as error:
        print(f"Catalog snapshot not replaced: {error}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("Catalog snapshot operation interrupted.", file=sys.stderr)
        return 130
    return 0


if __name__ == "__main__":
    sys.exit(main())
