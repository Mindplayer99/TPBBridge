# TPBBridge

CloudStream Red/pre-release bridge for Stremio-compatible TPB manifests.

## v12: broader TPB catalog/live compatibility

v12 keeps the v11 profile architecture and playback/debrid behavior, while adding narrow compatibility for TPB catalog forms that are not ordinary `Recent` tube rows:

- Stripchat/Chaturbate-style live-cam catalogs can appear on Home without enabling unrelated filter catalogs.
- Extensionless TPB HLS proxy URLs such as `/stripchat/hls/...` are explicitly sent to CloudStream as HLS instead of being mis-inferred as ordinary video.
- Compact standalone P2P studio/performer/creator catalogs are accepted as Home catalogs.
- Known TPB P2P `Top` catalogs are used as a fallback when a matching `Recent` catalog is not present, avoiding an empty Home for a deliberate Top-only setup while still preferring Recent when both exist.
- Required-extra Search/Studio/Performer/Tag catalogs remain search-only and are not dumped onto Home.

TPDB/StashDB remain upstream metadata/catalog services. They can enrich the metadata TPBBridge receives, but torrent media cache state is still determined by TPB's configured debrid provider (TorBox/Real-Debrid/etc.), not by TPDB/StashDB.

## v11: independent profiles

TPBBridge treats a **profile** as one independent setup. A profile may contain one manifest URL or several split manifest URLs that belong together.

Each profile has its own:

- Home provider name
- optional Search prefix
- discovered source list
- source enable/disable state
- Home catalogue order
- optional combined Search through the Home name
- optional Studio / Performer / Tag search providers

Profiles do not share source state. If the same upstream source appears in two different profiles, the two instances remain independent. Within one profile, same-source rows/routes from split manifests can still be merged as before.

Existing v10 installations are migrated automatically into one v11+ profile with the existing manifest URLs, Home name, Search prefix, source order, disabled-source state, Search routes and optional filter settings preserved.

## Setup

1. Install or update TPBBridge from the existing CloudStream repository.
2. Open **TPBBridge settings**.
3. Existing v10 users will see their old setup as one profile automatically.
4. Tap a profile to configure it, or tap **+ Add profile**.
5. Add one or more manifest URLs, one URL per line.
6. Choose that profile's **Home name** and optional **Search prefix**.
7. Optionally enable **Search through Home name** and/or Studio, Performer and Tag searches.
8. Press **Save + refresh**. No CloudStream restart is required.
9. After sources have been discovered, use **Manage sources** to enable/disable sources and arrange Home rows. Press **Save + refresh** to apply those source changes.

For a clean Home layout, enable only the TPB browse/live/P2P catalogs you actually want upstream. Per-source Search requires Search catalogs in the manifest. Studio/Performer/Tag filter support requires matching catalogs/options upstream; TPBBridge keeps those optional required-extra filters out of Home.

## Profile behavior

A profile's manifest URLs are resolved only inside that profile. This is important when two manifest groups have different TPB or debrid configuration: results opened from one profile are resolved against that profile's manifest bases, not another profile's.

Provider names must remain unique in CloudStream. If two profiles would create the same Home/Search/filter provider name, TPBBridge refuses the save and asks for a different Home name or Search prefix instead of silently registering an ambiguous provider.

Removing one profile removes only that profile's active Home/Search/filter providers and stored profile configuration. Other TPBBridge profiles remain unchanged.

## Source management and Home ordering

**Disable source** is local to the profile. A disabled source is removed before Home catalogue requests and before Search/filter registration, so a broken source does not remain hidden in the background adding normal Home/Search work.

**Order** changes only that profile's Home row sequence. It does not alter metadata, stream URLs, debrid behavior, request headers, subtitles or playback.

Same-source rows from split manifests inside a profile are merged before ordering. New sources are enabled by default and appended without destroying the saved order. Temporarily unavailable sources retain their remembered position and disabled state. Resetting order never re-enables disabled sources.

Disabling a source does not rewrite or delete anything from the remote TPB manifest. Upstream removal must still be done in TPB itself.

## Search

Every enabled discovered Search catalog can become an individual CloudStream Search provider using that profile's Search prefix.

If **Search through Home name** is enabled, selecting that profile's Home provider in CloudStream Search fans out across that profile's enabled Search routes and merges/deduplicates the results. It never searches sources disabled in that profile and it never crosses into another profile.

Selecting the combined Home provider and its individual source providers at the same time can intentionally request the same source twice through different CloudStream selections, so duplicate-looking results are possible in that case.

Studio, Performer and Tag are optional search-only providers. TPBBridge uses only matching required Stremio genre catalogs/options advertised by the manifest. Exact option matches are preferred and partial matching is bounded to avoid uncontrolled request fanout.

Search pagination uses `skip` only when the manifest advertises support for it.

## Home/catalogue behavior

For every profile, Home processing is:

1. read that profile's configured manifests
2. identify eligible Recent, supported live, compact P2P, or Top-only fallback catalogs
3. keep required-extra search/filter catalogs off Home
4. exclude disabled sources before their Home content fetch
5. isolate individual source failures
6. merge same-source rows inside the profile
7. deduplicate items
8. apply the profile's saved Home order
9. return the rows to CloudStream

A failing source therefore does not need to destroy the other profile rows.

## Streams, metadata and debrid

The v12 compatibility work does not replace the existing playback engine.

TPBBridge supports direct HTTP/HLS/MP4/DASH/debrid URLs and preserves Stremio request headers, proxy request headers and Referer where provided. Direct variants remain separate CloudStream mirrors and quality labels such as 2160p/1440p/1080p/720p are mapped when TPB exposes enough information. TPB live/direct HLS paths are explicitly recognized even when the proxy URL does not end in `.m3u8`.

TPBBridge does not call a debrid API itself. TPB remains responsible for resolving TorBox/Real-Debrid/etc. A resolved HTTPS/debrid stream is passed through. When TPB instead returns an `infoHash` plus tracker/source information, TPBBridge provides the P2P magnet fallback to CloudStream.

Subtitles are forwarded and deduplicated. Metadata uses the Stremio meta route with catalog metadata as fallback and keeps poster/background/year/genres/description data where available. Landscape `posterShape` catalogs can use CloudStream's horizontal-card layout.

Stremio `fileIdx` is used where useful for stream deduplication, but CloudStream's generic magnet link path does not expose the same reliable exact-file-index semantics as a resolved Stremio/debrid URL. Resolved direct URLs remain preferred for exact multi-file playback.

TPBBridge does not invent a universal cached/uncached badge when TPB has not supplied reliable cache state.

## Privacy, removal and wipe

Manifest URLs can contain private configuration or API keys. They are stored in TPBBridge's local CloudStream preferences and must not be committed to the repository.

TPBBridge keeps configured manifest URLs out of normal provider `mainUrl` values and out of new result/history payloads. Result payloads use short internal references and are resolved only against the manifest bases owned by the provider/profile that created them.

**Remove profile** deletes only one profile.

**Delete all TPBBridge data** is the full wipe-before-uninstall action. After confirmation it clears all TPBBridge profiles and legacy TPBBridge settings, cleans TPBBridge provider names from CloudStream's saved Search/Home selection where applicable, invalidates TPBBridge's manifest cache and unregisters active TPBBridge providers. It deliberately does not delete CloudStream history/bookmarks, player settings, debrid settings or other extensions.

The wipe is not placed in automatic plugin unload/update handling because CloudStream also unloads plugins during normal update/reload operations; automatically erasing configuration there would risk destroying a valid setup.

## Build target

Built against `com.lagradost:cloudstream3:pre-release`, marked beta-only (`status = 3`) and intended for Red/pre-release CloudStream.

Release CI compiles the plugin, verifies the generated `.cs3` and published metadata/hash/size, runs a public TPB protocol smoke test (including live-manifest and compact-P2P manifest checks), and only then publishes the `builds` branch.

## Attribution and license

Stremio-to-CloudStream protocol handling follows GPL bridge work by Hexated/phisher98 and compatible forks. TPBBridge is GPL-3.0-or-later. CloudStream TestPlugins is used as the build template.
