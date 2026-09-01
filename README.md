# TPBBridge

CloudStream pre-release bridge for Stremio-compatible TPB manifests.

## What v5 does

- One combined Home provider with clean per-source rows (for example `Hotleak`, not `Hotleak · Recent`).
- One search-only CloudStream provider per discovered Stremio Search catalog.
- Multiple/split TPB manifests, one URL per line.
- One **Save + apply now** action: providers are safely replaced in memory and CloudStream UI is refreshed; the plugin does not hot-reload its own `.cs3`.
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
5. Press **Save + apply now**. No app restart is required.
6. Select the combined Home provider for browsing. In CloudStream Search filters, select the automatically discovered per-source providers you want to search.

For a clean tube layout, enable `Recent` and `Search` for the desired TPB sources and leave `Studio`, `Tag`, and `Performer` off unless those catalogs are specifically wanted.

## Debrid and torrent behavior

TPBBridge does not call a debrid API itself. TPB remains responsible for resolving TorBox/Real-Debrid/etc. If TPB returns a direct debrid URL, TPBBridge passes it to CloudStream with the required headers. If TPB instead returns an `infoHash` P2P fallback, TPBBridge builds a magnet link from the hash and Stremio tracker sources and lets CloudStream's torrent handling take over.

Stremio `fileIdx` is parsed for deduplication, but CloudStream's `ExtractorLink` does not provide a stable Stremio-style file-index field. Exact multi-file torrent selection is therefore left to CloudStream's torrent handler; direct debrid URLs are the preferred path for exact-file playback.

## Privacy and safety

Configured manifest URLs stay in CloudStream local preferences and must never be committed to GitHub. TPBBridge also avoids exposing configured manifest URLs as provider `mainUrl` values or embedding them in new result/history payloads. Legacy v4 item payloads are accepted only when their exact manifest is still configured.

The plugin intentionally does **not** invoke CloudStream's internal plugin hot-reload functions; current CloudStream source explicitly warns extensions not to do that because it can recurse, leak, or crash. Live settings application replaces only TPBBridge's registered providers and fires CloudStream's normal provider-refresh event.

## Attribution and license

Stremio-to-CloudStream protocol handling follows GPL bridge work by Hexated/phisher98 and compatible forks. TPBBridge is GPL-3.0-or-later. CloudStream TestPlugins is used as the build template.
