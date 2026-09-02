#!/usr/bin/env python3
"""Credential-free smoke test for the public TPB Stremio protocol.

Validates the core tube protocol plus manifest shapes TPBBridge depends on for
live cams and compact P2P catalogs. It never prints media titles, stream URLs,
or credentials.
"""
from __future__ import annotations

import base64
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

HOST = "https://tpb-adult-addon.click"
TIMEOUT = 35
ATTEMPTS = 3


def get_json(url: str) -> dict:
    last_error: Exception | None = None
    for attempt in range(1, ATTEMPTS + 1):
        try:
            req = urllib.request.Request(
                url,
                headers={
                    "Accept": "application/json",
                    "User-Agent": "TPBBridge-CI/1.0",
                },
            )
            with urllib.request.urlopen(req, timeout=TIMEOUT) as response:
                if response.status < 200 or response.status >= 300:
                    raise RuntimeError(f"HTTP {response.status}")
                value = json.loads(response.read().decode("utf-8"))
                if not isinstance(value, dict):
                    raise RuntimeError("response is not a JSON object")
                return value
        except (urllib.error.URLError, TimeoutError, RuntimeError, json.JSONDecodeError) as exc:
            last_error = exc
            if attempt == ATTEMPTS:
                break
            time.sleep(attempt * 2)
    assert last_error is not None
    raise last_error


def enc_path(value: str) -> str:
    return urllib.parse.quote(value, safe="")


def addon_base(config: dict) -> str:
    raw = json.dumps(config, separators=(",", ":")).encode("utf-8")
    token = base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")
    return f"{HOST}/{token}"


def verify_live_manifest() -> tuple[str, int]:
    # Stripchat is public and needs no account/debrid key. We test manifest
    # shape only: live model availability changes constantly and must not make
    # a release depend on a model being online at the instant CI runs.
    base = addon_base(
        {
            "sources": ["stripchat"],
            "maxResults": 2,
            "minSeeders": 0,
            "enabledSorts": [],
        }
    )
    manifest = get_json(f"{base}/manifest.json")
    catalogs = manifest.get("catalogs") or []
    live = [
        c
        for c in catalogs
        if isinstance(c, dict)
        and (
            str(c.get("id", "")).lower().startswith("sc_")
            or "stripchat" in str(c.get("name", "")).lower()
        )
    ]
    if not live:
        raise RuntimeError("Stripchat manifest exposed no live catalogs")
    return str(manifest.get("version", "?")), len(live)


def verify_compact_p2p_manifest() -> tuple[str, int]:
    # Credential-free manifest generation is enough to verify that TPB still
    # accepts compact studio configuration. Do not fetch torrent results here:
    # indexer availability is independent of manifest compatibility.
    base = addon_base(
        {
            "sources": ["piratebay"],
            "maxResults": 2,
            "minSeeders": 0,
            "enabledSorts": ["recent"],
            "enabledCatalogs": ["xxx_studio_vixen"],
            "compactStudios": True,
        }
    )
    manifest = get_json(f"{base}/manifest.json")
    catalogs = manifest.get("catalogs") or []
    vixen = [
        c
        for c in catalogs
        if isinstance(c, dict)
        and "vixen" in f"{c.get('id', '')} {c.get('name', '')}".lower()
    ]
    if not vixen:
        raise RuntimeError("compact P2P studio manifest exposed no configured studio catalog")
    return str(manifest.get("version", "?")), len(vixen)


def main() -> int:
    # No API/debrid keys. yesporn is a direct-play tube source supported by the
    # public TPB backend. A tiny result limit keeps CI traffic minimal.
    config = {
        "sources": ["yesporn"],
        "maxResults": 2,
        "minSeeders": 0,
        "enabledSorts": [],
    }
    base = addon_base(config)

    manifest = get_json(f"{base}/manifest.json")
    resources = set(manifest.get("resources") or [])
    missing = {"catalog", "meta", "stream"} - resources
    if missing:
        raise RuntimeError(f"manifest missing resources: {sorted(missing)}")

    catalogs = manifest.get("catalogs") or []
    recent = next(
        (c for c in catalogs if str(c.get("id", "")).endswith("_recent")),
        None,
    )
    search = next(
        (c for c in catalogs if str(c.get("id", "")).endswith("_search")),
        None,
    )
    if not recent or not search:
        raise RuntimeError("tube recent/search catalogs not found")

    cat_type = str(recent.get("type") or "Porn")
    cat_id = str(recent["id"])
    catalog = get_json(f"{base}/catalog/{enc_path(cat_type)}/{enc_path(cat_id)}.json")
    metas = catalog.get("metas") or []
    if not metas:
        raise RuntimeError("recent catalog returned zero metas")

    item = metas[0]
    item_id = str(item.get("id") or "")
    item_type = str(item.get("type") or cat_type)
    if not item_id:
        raise RuntimeError("catalog item has no id")

    meta = get_json(f"{base}/meta/{enc_path(item_type)}/{enc_path(item_id)}.json")
    if not (meta.get("meta") or (meta.get("metas") or [])):
        raise RuntimeError("meta endpoint returned no metadata")

    stream_response = get_json(f"{base}/stream/{enc_path(item_type)}/{enc_path(item_id)}.json")
    streams = stream_response.get("streams") or []
    if not streams:
        raise RuntimeError("stream endpoint returned zero streams")

    direct = [s for s in streams if isinstance(s, dict) and s.get("url")]
    p2p = [s for s in streams if isinstance(s, dict) and s.get("infoHash")]
    if not direct and not p2p:
        raise RuntimeError("stream endpoint returned neither direct URL nor infoHash stream")

    headers_present = any(
        ((s.get("behaviorHints") or {}).get("proxyHeaders") or {}).get("request")
        or (s.get("behaviorHints") or {}).get("headers")
        for s in direct
    )

    live_version, live_catalog_count = verify_live_manifest()
    compact_version, compact_catalog_count = verify_compact_p2p_manifest()

    print(
        "TPB protocol smoke OK:",
        f"version={manifest.get('version', '?')}",
        f"catalog={cat_id}",
        f"metas={len(metas)}",
        f"streams={len(streams)}",
        f"direct={len(direct)}",
        f"p2p={len(p2p)}",
        f"proxy_headers={bool(headers_present)}",
        f"live_version={live_version}",
        f"live_catalogs={live_catalog_count}",
        f"compact_version={compact_version}",
        f"compact_catalogs={compact_catalog_count}",
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"TPB protocol smoke FAILED: {type(exc).__name__}: {exc}", file=sys.stderr)
        raise
