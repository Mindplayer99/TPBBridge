#!/usr/bin/env python3
"""Credential-free smoke test for the public TPB Stremio protocol.

It validates only protocol shape used by TPBBridge: manifest -> recent catalog ->
meta -> stream. It never prints media titles or stream URLs.
"""
from __future__ import annotations

import base64
import json
import sys
import urllib.parse
import urllib.request

HOST = "https://tpb-adult-addon.click"
TIMEOUT = 35


def get_json(url: str) -> dict:
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
        return json.loads(response.read().decode("utf-8"))


def enc_path(value: str) -> str:
    return urllib.parse.quote(value, safe="")


def main() -> int:
    # No API/debrid keys. yesporn is a direct-play tube source supported by the
    # public TPB backend source. A tiny result limit keeps CI traffic minimal.
    config = {
        "sources": ["yesporn"],
        "maxResults": 2,
        "minSeeders": 0,
        "enabledSorts": [],
    }
    raw = json.dumps(config, separators=(",", ":")).encode("utf-8")
    token = base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")
    base = f"{HOST}/{token}"

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

    streams = get_json(f"{base}/stream/{enc_path(item_type)}/{enc_path(item_id)}.json").get("streams") or []
    if not streams:
        raise RuntimeError("stream endpoint returned zero streams")

    direct = [s for s in streams if s.get("url")]
    if not direct:
        raise RuntimeError("stream endpoint returned no direct URL stream")

    headers_present = any(
        ((s.get("behaviorHints") or {}).get("proxyHeaders") or {}).get("request")
        or (s.get("behaviorHints") or {}).get("headers")
        for s in direct
    )

    print(
        "TPB protocol smoke OK:",
        f"version={manifest.get('version', '?')}",
        f"catalog={cat_id}",
        f"metas={len(metas)}",
        f"streams={len(streams)}",
        f"proxy_headers={bool(headers_present)}",
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"TPB protocol smoke FAILED: {type(exc).__name__}: {exc}", file=sys.stderr)
        raise
