# TPBBridge

CloudStream pre-release bridge for Stremio-compatible TPB manifests.

## What v9 does

- One combined Home provider with clean per-source Recent rows (for example `Hotleak`, not `Hotleak · Recent`).
- **Source management:** each discovered source can be enabled/disabled locally. Disabled sources are removed before Home catalogue requests and before Search/filter provider registration, so a broken source does not clutter or slow normal TPBBridge browsing/search.
- **Custom Home catalogue ordering** with simple up/down controls. Ordering is stored locally, duplicate source rows remain merged, newly discovered sources are appended, and temporarily unavailable sources keep their remembered position.
- Disabled state is reversible and remembered independently from order. Resetting order does not re-enable disabled sources.
- One search-only CloudStream provider per enabled discovered Stremio Search catalog.
- Optional **Search all sources through the Home name** switch. When enabled, selecting the parent/Home provider in CloudStream Search fans out across every enabled source Search catalog.
- Optional **Studio**, **Performer**, and **Tag** switches. These create search-only filter providers such as `Valley • Studio`; disabled sources are excluded and these filters never create Studio/Performer/Tag Home rows.
- Filter search uses only option values TPB advertises in each required `genre` catalog. Exact matches are preferred; narrow partial matches are supported with a bounded fanout.
- Multiple/split TPB manifests, one URL per line, with exact duplicate manifest URLs removed automatically.
- One **Save + refresh** action: Search/filter/Home routes are re-discovered and providers are safely replaced in memory; the plugin does not hot-reload its own `.cs3`.
- Direct HTTP/HLS/MP4/DASH and debrid URLs with Stremio request headers/referer preserved.
- Stremio `infoHash` + tracker sources converted to a CloudStream MAGNET link for TPB P2P fallback.
- `posterShape` aware Home rows so landscape catalogs use CloudStream's horizontal-card layout.
- Duplicate Home rows from split manifests are merged instead of one part hiding another.
- Search pagination only when the manifest advertises the `skip` extra.
- Stream/result deduplication and cleaner source/quality labels.
- Five-minute manifest cache to reduce repeated manifest requests.

## Target

Built against `com.lagradost:cloudstream3:pre-release` and marked beta-only (`status = 3`). Use Red/pre-release CloudStream.

## Setup

1. Install TPBBridge from this CloudStream repository.
2. Open TPBBridge settings.
3. Paste the TPB manifest URL(s), one per line.
4. Choose a Home name and optionally a Search prefix.
5. Optionally enable parent/all-source Search and/or Studio/Performer/Tag filter search.
6. Press **Save + refresh**. No app restart is required.
7. Open **Manage sources** to enable/disable sources and move Home rows up/down. Press **Save + refresh** to apply the changes.
8. Select the combined Home provider for browsing. In CloudStream Search filters, select either the parent provider (if parent Search is enabled), individual source providers, or one of the optional filter providers.

For a clean tube Home layout, `Recent` should be enabled in TPB. Per-source Search requires `Search` enabled in TPB. Optional Studio/Performer/Tag support requires those catalogs to be enabled in TPB too, but TPBBridge keeps them out of Home so they cannot recreate the old duplicate-row layout.

## Source management and Home ordering

The source manager controls two independent things:

- **On/Off:** turning a source off removes its Home row, its individual Search provider, its contribution to combined Home-name Search, and its routes from Studio/Performer/Tag aggregate searches. Home catalogs for that disabled source are filtered before their content endpoint is requested.
- **Order:** up/down changes only the sequence of Home rows sent to CloudStream. It does not touch catalog contents, metadata, stream resolution, debrid, headers, subtitles, or playback.

Source state uses normalized source names as local keys. Same-source rows from split manifests are merged before ordering, so one source has one position/state. New sources are enabled by default and appended without overwriting the existing order. A temporarily unavailable source keeps both its remembered position and disabled state, so it returns consistently if it later reappears. **Reset order** changes positions only; it never re-enables disabled sources.

Disabling a source does not rewrite the remote TPB manifest and does not erase old CloudStream history/bookmarks already stored by the app. It only stops TPBBridge from exposing/requesting that source through its active Home/Search/filter surfaces. Permanent removal from the upstream manifest should be done in TPB itself.

## Optional filter behavior

TPB currently exposes tube Studio/Performer/Tag catalogs as required Stremio `genre` catalogs with stable `_studio`, `_performer`, and `_tag` ids and an advertised option list. TPBBridge discovers only those exact filter families. It does not treat arbitrary Stremio genre catalogs as Studio/Performer/Tag.

When a filter provider is enabled, the text typed in CloudStream Search is matched against TPB's advertised options. An exact match is used directly. Partial matching is deliberately bounded so a broad one-letter query cannot explode into hundreds of upstream requests.

## Debrid and torrent behavior

TPBBridge does not call a debrid API itself. TPB remains responsible for resolving TorBox/Real-Debrid/etc. If TPB returns a direct debrid URL, TPBBridge passes it to CloudStream with the required headers. If TPB instead returns an `infoHash` P2P fallback, TPBBridge builds a magnet link from the hash and Stremio tracker sources and lets CloudStream's torrent handling take over.

TPBBridge preserves TPB's stream name/quality information when creating CloudStream mirrors. Cache-state icons or labels are therefore only reliable when TPB itself includes that state in the Stremio stream object; TPBBridge does not invent a cached/uncached badge from a direct URL.

Stremio `fileIdx` is parsed for deduplication, but CloudStream's `ExtractorLink` does not provide a stable Stremio-style file-index field. Exact multi-file torrent selection is therefore left to CloudStream's torrent handler; direct debrid URLs are the preferred path for exact-file playback.

## Privacy and safety

Configured manifest URLs stay in CloudStream local preferences and must never be committed to GitHub. TPBBridge also avoids exposing configured manifest URLs as provider `mainUrl` values or embedding them in new result/history payloads. Legacy v4 item payloads are accepted only when their exact manifest is still configured.

The plugin intentionally does **not** invoke CloudStream's internal plugin hot-reload functions; current CloudStream source explicitly warns extensions not to do that because it can recurse, leak, or crash. Live settings application replaces only TPBBridge's registered providers and fires CloudStream's normal provider-refresh event.

## Attribution and license

Stremio-to-CloudStream protocol handling follows GPL bridge work by Hexated/phisher98 and compatible forks. TPBBridge is GPL-3.0-or-later. CloudStream TestPlugins is used as the build template.
