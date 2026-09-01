# TPBBridge

Experimental CloudStream pre-release plugin for Stremio-compatible TPB manifests.

## Goal

Use one TPB configuration while getting:

- one combined CloudStream Home provider containing the enabled `Source · Recent` rows;
- separate CloudStream global-search providers for each discovered `Source · Search` catalog;
- support for TPB split manifests by pasting every generated manifest URL;
- direct HTTP/HLS playback with Stremio `behaviorHints.proxyHeaders.request` / `headers` preserved.

The combined Home provider deliberately returns no global-search results, preventing the same TPB configuration from appearing once as a giant merged search bucket and again under each source-specific provider.

## Target

The repository builds against `com.lagradost:cloudstream3:pre-release` and the plugin is marked beta-only (`status = 3`). Red/pre-release CloudStream is therefore the primary target.

## Security

Never commit a configured TPB manifest URL to GitHub. A configured manifest path can contain encoded service credentials. TPBBridge stores manifest URLs only in CloudStream's local SharedPreferences.

## First device setup

1. Install TPBBridge from this repository in CloudStream pre-release.
2. Open TPBBridge plugin settings.
3. Paste all TPB split manifest URLs, one per line.
4. Press **Discover sources + save**.
5. Fully close and reopen CloudStream so the discovered per-source providers are registered.
6. Select the combined TPB Home provider for browsing; use CloudStream global search for the separately named source providers.

For the intended tube-only layout, keep `Recent` and `Search` enabled for each source and disable `Studio`, `Tag`, and `Performer` catalogs unless you specifically want those filtered catalogs.

## Scope

The first stable target is direct URL/HLS/MP4 tube playback. Raw `infoHash`/magnet playback is intentionally outside the initial scope. The TPB backend remains responsible for catalog, metadata, and stream resolution; TPBBridge is the CloudStream UI/protocol bridge.

## Attribution and license

The Stremio-to-CloudStream protocol approach is derived from GPL bridge work by Hexated/phisher98 and compatible forks. TPBBridge is intended to be distributed under GPL-3.0-or-later. CloudStream's TestPlugins repository is used as the build template.
