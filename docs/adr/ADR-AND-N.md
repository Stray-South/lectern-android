# ADR-AND-N: EPUB WebView firewall — `EpubBlockingWebViewClient` is the sole gated surface

**Status:** Accepted
**Date:** 2026-05-16
**Sprint:** 24 (backfill)

## Context

iOS ADR-N restricts WebKit usage to one file: `WebArticleIngester.swift`,
which uses `WKWebView` strictly for Readability.js + DOMPurify
extraction and tears the WebView down after each request. All other
document rendering (EPUB, PDF, plain text) reaches TextKit 2 or PDFKit
without crossing WebKit.

On Android, Readium's EPUB navigator is itself a WebView host. There
is no equivalent "no WebKit" position available — the EPUB rendering
surface *is* a WebView. The threat model therefore shifts from
"forbid WebKit" to "lock the WebView down such that it cannot reach
the network, the device, or arbitrary JS injection."

## Decision

EPUB rendering uses Readium's `EpubNavigatorFragment`, which hosts an
Android `WebView`. That WebView is gated by `EpubBlockingWebViewClient`
on every load:

1. **Predicate-based host gate.** Only Readium's internal host
   (`https://readium/`) and the registered intercept predicate may
   resolve. All other hosts are blocked.
2. **Strict scheme allowlist.** `shouldOverrideUrlLoading` returns true
   (consume + drop) for any scheme outside the explicit allowlist
   (`ALLOWED_NAVIGATION_SCHEMES = setOf("https", "http")` in
   `EpubBlockingWebViewClient.kt`).
3. **Registration guard.** The blocking client must be registered
   *before* any URL is loaded; tested by source assertion ordering.
4. **`allowContentAccess = false` unconditional.** Applied to every
   wrapped WebView with no conditional path.
5. **No direct `evaluateJavascript`.** No file in `app/src/main/kotlin/`
   may call `WebView.evaluateJavascript(...)` directly — Readium's
   internal calls are out of our control; ours are zero.
6. **`BlockingHttpClient` wired into Readium.** Both Readium network
   entry points use `BlockingHttpClient`, not Readium's default HTTP
   client. The blocking client refuses all outbound URLs.
7. **No external link listener.** `EpubBlockingWebViewClient` does
   not declare a listener interface for external links — closing the
   "open this in browser" exfil channel.
8. **Locator serialisation via `toJSON`.** EPUB locators round-trip
   through `Locator.toJSON()`, not string interpolation, to prevent
   injection via crafted publication metadata.

## Pinned by (all in `GroupASecurityTest`)

| Guarantee | Test |
|---|---|
| Predicate-based strict host check | `epub_blockingWebViewClient_predicate_strictHostCheck` |
| Registration guard ordering | `epub_blockingWebViewClient_registrationGuard_setsAfterRegister` |
| `shouldOverrideUrlLoading` scheme allowlist (https/http only) | `epub_blockingWebViewClient_shouldOverrideUrlLoading_schemeDenylist` |
| `allowContentAccess = false` unconditional | `epub_wrapWebViews_allowContentAccess_false_unconditional` |
| No direct `evaluateJavascript` in main sources | `epub_noDirectEvaluateJavascript_inMainSources` |
| `BlockingHttpClient` wired, not default | `epub_publicationRepository_blockingHttpClient_noDefaultHttpClient` |
| No external link listener interface declared | `epub_noExternalLinkListener_classDeclaresNoListenerInterface` |
| Locator serialisation uses `toJSON` not interpolation | `epub_locatorSerialization_usesToJson_notStringInterpolation` |
| Reader overlay z-order added after navigator container | `epub_overlay_addedAfterNavigatorContainer_zOrderCorrect` |

## Code markers

- `app/src/main/kotlin/com/straysouth/lectern/ui/reader/EpubBlockingWebViewClient.kt`
- `app/src/main/kotlin/com/straysouth/lectern/ui/reader/EpubReaderFragment.kt`
- `app/src/main/kotlin/com/straysouth/lectern/ui/reader/ReaderScreen.kt`
- `app/src/main/kotlin/com/straysouth/lectern/ui/reader/ReaderOverlay.kt`
- `app/src/main/kotlin/com/straysouth/lectern/data/repository/BlockingHttpClient.kt`
- `app/src/main/kotlin/com/straysouth/lectern/data/repository/PublicationRepository.kt`

## Known gap (CLOSED — Sprint 25 Set 3 PR-G, `106e8b9`)

~~`WebView.addJavascriptInterface` is not currently pinned by a test.
Grep confirms zero usages in main sources today, but a future addition
would not fail CI.~~

**Status:** CLOSED by `tests/no-javascript-interface` (`106e8b9`).
`GroupASecurityTest.epub_noJavascriptInterface_inMainSources` walks
`app/src/main/kotlin` and asserts zero `addJavascriptInterface(` call
sites with comment-stripping (mirrors the
`epub_noDirectEvaluateJavascript_inMainSources` pattern).

> **Cross-branch note:** The referenced test lives on the Track C
> stack (`tests/no-javascript-interface` onward). This closure
> citation becomes reachable on trunk only after the Track C stack
> merges. Pre-merge order: Track C stack → this PR (this PR is
> built on `docs/adr-and-backfill` and carries Track A's 14 ADRs
> together with the 3 closure notes).

## Consequences

- The WebView cannot reach the internet, the device filesystem
  outside Readium's intercepted scheme, or arbitrary JS injection.
- A future Readium upgrade that changes the EPUB navigation contract
  may require this ADR to be re-validated against the new surface.
- PDF and CBZ rendering paths do **not** use a WebView and are not
  governed by this ADR.
