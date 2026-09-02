# TPBBridge

CloudStream Red/pre-release bridge for Stremio-compatible TPB manifests.

## v11: independent profiles

TPBBridge can now keep different manifest groups completely separate without duplicating the playback engine.

A **profile** contains one or more manifest URLs and owns only its browsing/search configuration:

- Home provider name
- optional Search prefix
- discovered sources
- source enable/disable state
- Home catalogue order
- optional combined Search through that profile's Home name
- optional Studio / Performer / Tag search providers

This means one setup can be `Valley` while another can be `Archive`, each with different manifests, source order, disabled sources, Search prefix and filter settings. Sources with the same name in different profiles do not share routes or enable/disable state.

Several split manifest URLs can still be placed inside one profile when they are meant to behave as one setup. Same-source rows/routes inside that profile retain the existing TPBBridge merge/deduplication behavior.

### Automatic v10 migration

On first v11 load, an existing v10 configuration is converted into exactly one profile. The migration preserves the existing manifest input, Home name, Search prefix, discovered routes, Home order, disabled-source state and optional Search/filter toggles. The v11 profile is committed before legacy keys are removed.

Profile state also keeps one last known-good local backup. A valid current profile list always wins—including an intentionally empty list—so removing profiles cannot accidentally resurrect them from the backup.

## Setup

1. Install/update TPBBridge from the existing CloudStream repository.
2. Open TPBBridge settings.
3. Tap **+ Add profile**.
4. Paste one or more manifest URLs, one per line. Put split manifests in the same profile only when they should share one Home/Search setup.
5. Choose that profile's **Home name** and optional **Search prefix**.
6. Optionally enable combined Home-name Search and/or Studio / Performer / Tag.
7. Tap **Save + refresh**. The profile is discovered and registered without restarting CloudStream.
8. Reopen the profile and use **Manage sources** to enable/disable sources or move Home rows up/down, then **Save + refresh**.
9. Add more profiles when you want another independent manifest group.

Saving one profile fetches/discovers only that profile's manifests. Other profiles keep their already-saved route/source configuration and are not rediscovered.

## Home and source management

Each profile gets one combined Home provider. Normal Recent catalogues become clean per-source rows. Required-extra Studio/Performer/Tag catalogues stay out of Home.

The source manager controls two independent things:

- **On/Off:** a disabled source is removed before its Home catalogue request and before individual Search, combined Search, and facet-provider registration.
- **Order:** up/down controls only the sequence of Home rows; it does not alter metadata, stream resolution, debrid, headers, subtitles or playback.

Source ordering keeps temporarily unavailable sources in their remembered slots. Newly discovered sources are enabled by default and appended. Resetting order does not re-enable disabled sources.

Within one profile, same-name sources from split manifests are merged as before. Across different profiles, they remain independent. When two providers would otherwise have exactly the same visible CloudStream name, TPBBridge adds a profile label to avoid a provider-name collision.

## Search

Every enabled discovered Search catalogue becomes an individual CloudStream Search provider for that profile. The profile's optional prefix is applied only to those individual providers.

**Search through Home name** adds an aggregate Search path to that profile's Home provider. It fans out only across that profile's enabled source routes, uses `skip` only when the manifest advertises it, isolates individual route failures, and deduplicates merged results.

Studio / Performer / Tag remain optional Search-only providers. They use only the option lists advertised by TPB. Exact matches are preferred and fuzzy fan-out is bounded to avoid exploding broad queries into hundreds of requests.

Selecting a profile's combined Home Search and its individual source providers at the same time can intentionally show duplicate results because CloudStream is being asked to search the same source through two paths.

## Playback and protocol behavior

v11 does **not** create separate playback engines per profile. The existing shared bridge core remains responsible for all profiles:

- Stremio metadata and catalogue fallback
- `/stream/<type>/<id>.json`
- direct HTTP/HLS/MP4/DASH links
- TPB-resolved debrid links
- request headers, proxy request headers and Referer
- quality mapping
- subtitles
- `infoHash` + trackers -> MAGNET fallback
- direct-stream and torrent-pointer deduplication

TPBBridge does not call TorBox, Real-Debrid or another debrid API itself. It consumes whatever TPB returns. If TPB resolves a direct debrid URL, TPBBridge passes it through; if TPB returns only a torrent hash, TPBBridge provides the magnet fallback.

Stremio `fileIdx` is used in torrent-pointer deduplication, but generic CloudStream magnet handling cannot guarantee exact Stremio-style file selection for every multi-file torrent. Resolved direct debrid URLs remain preferred for exact-file playback.

## Failure isolation

- A failing Home catalogue does not have to destroy the other Home rows.
- A failing individual Search route is isolated from other aggregate Search routes.
- Saving/refreshing a profile validates and rediscovers that profile before replacing the working configuration. If discovery fails, the existing saved providers remain active.
- Profile persistence uses synchronous success checks for destructive/important writes.
- The plugin keeps one known-good profile-state backup for recovery from malformed primary profile JSON.

## Remove one profile vs wipe everything

**Remove this profile** deletes only that profile's manifests, Home/Search providers, order, disabled-source state and filter settings. Other profiles remain configured.

**Delete all TPBBridge data** is the clean-removal action to use before uninstalling. It clears all TPBBridge profiles/preferences/cache and active TPBBridge providers, and removes their stale names from CloudStream's saved Search/Home selection. It deliberately does not delete CloudStream history/bookmarks, player/debrid settings or other extensions.

Uninstalling the extension without using the wipe action may leave TPBBridge preferences in CloudStream app storage, allowing the configuration to return after reinstall.

## Privacy

Configured manifest URLs remain in TPBBridge's local CloudStream preferences. They must never be committed to the repository. Provider `mainUrl` values use non-secret TPBBridge scope identifiers rather than configured manifest URLs, and current result/history payloads use short references instead of embedding private manifest bases.

## Target

Built against `com.lagradost:cloudstream3:pre-release`, Kotlin compatible with the current Red/pre-release build environment, and marked beta-only (`status = 3`).

## Attribution and license

Stremio-to-CloudStream protocol handling follows GPL bridge work by Hexated/phisher98 and compatible forks. TPBBridge is GPL-3.0-or-later. CloudStream TestPlugins is used as the build template.
