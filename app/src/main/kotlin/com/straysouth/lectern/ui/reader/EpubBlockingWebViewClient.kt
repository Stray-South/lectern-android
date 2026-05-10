package com.straysouth.lectern.ui.reader

import android.net.http.SslError
import android.view.KeyEvent
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.WebViewClientCompat
import java.io.ByteArrayInputStream

/**
 * Wraps Readium's internal WebViewClient to block all resource requests whose host is not
 * "readium" (Readium's local publication server — `https://readium/publication/` and
 * `https://readium/assets/`).
 *
 * WHY: [WebViewServer.shouldInterceptRequest] returns null for any host other than "readium",
 * which allows EPUB HTML to fetch external images, scripts, and fonts over the network.
 * A malicious EPUB could include tracking beacons, exfiltrate reading position, or load
 * third-party JS. Returning a blocking response here closes that surface without modifying
 * or forking Readium internals.
 *
 * HOW: [R2EpubPageFragment] sets its own [WebViewClientCompat] during [onCreateView]. We
 * register a [FragmentManager.FragmentLifecycleCallbacks] (once, guarded by
 * [EpubReaderFragment.blockingCallbackRegistered]) that wraps each page fragment's client
 * after [onViewCreated] completes. The delegate receives all other calls unchanged —
 * [onPageFinished], [shouldOverrideUrlLoading], [shouldOverrideKeyEvent] — preserving
 * Readium's navigation and page-ready behaviour. Extending [WebViewClientCompat] ensures
 * Chromium's compat bridge methods (onReceivedError compat path, onSafeBrowsingHit) are
 * routed through the delegate via the overrides below.
 *
 * ALLOWED hosts: only "readium" (Readium's local loopback server). All other hosts are
 * blocked unconditionally regardless of scheme. Null host (data: URIs, about: frames)
 * passes through intentionally — Readium uses data: URIs for inline content injection
 * (CSS overrides, MathML polyfills). Top-frame navigations to data:/blob: go through
 * [shouldOverrideUrlLoading] → Readium's handler, not through [shouldInterceptRequest].
 * Subframe data: fetches are already within the JS execution sandbox (A.1 surface).
 *
 * SSL errors: forwarded to the delegate so Readium can handle the https://readium virtual
 * host's TLS setup (Chromium may fire onReceivedSslError for non-standard TLS hosts even
 * when intercepted via shouldInterceptRequest). Base class default calls handler.cancel(),
 * which is safe, but dropping the delegate's override would silently break page loads.
 *
 * See RED-TEAM.md §A.2.
 */
internal class EpubBlockingWebViewClient(
    private val delegate: WebViewClientCompat,
) : WebViewClientCompat() {

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        val host = request.url.host
        if (host != null && host != "readium") {
            return blockedResponse()
        }
        return delegate.shouldInterceptRequest(view, request)
    }

    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest,
    ): Boolean {
        // Explicit scheme allowlist: block intent://, market://, content://, file://,
        // javascript:, and any other non-http scheme before delegating to Readium.
        // The null-listener pattern (A.3) already prevents startActivity() from firing
        // via onExternalLinkActivated; this is defence-in-depth covering JS-initiated
        // top-frame navigations (window.location = "intent://...") which may not reach
        // the Readium null-listener path on all Android versions.
        // Readium internal links resolve to https://readium/... — scheme "https" — and pass.
        val scheme = request.url.scheme?.lowercase()
        if (scheme != null && scheme !in ALLOWED_NAVIGATION_SCHEMES) {
            return true  // consumed — no navigation, no Intent
        }
        return delegate.shouldOverrideUrlLoading(view, request)
    }

    override fun onPageFinished(view: WebView, url: String) {
        delegate.onPageFinished(view, url)
    }

    override fun shouldOverrideKeyEvent(
        view: WebView,
        event: KeyEvent,
    ): Boolean = delegate.shouldOverrideKeyEvent(view, event)

    // Forward SSL errors to the delegate. Base class calls handler.cancel() (correct security
    // default). Forwarding ensures Readium can handle https://readium virtual-host TLS callbacks
    // if its client overrides this — dropping the call would silently blank pages.
    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        delegate.onReceivedSslError(view, handler, error)
    }

    companion object {
        // Readium serves all internal resources at https://readium/... — "https" covers all
        // internal chapter links. "http" included for completeness; network_security_config.xml
        // blocks cleartext independently at the OS level.
        private val ALLOWED_NAVIGATION_SCHEMES = setOf("https", "http")
    }

    // HTTP 403 with empty body — clean, debuggable block visible in Network DevTools.
    // Status 0 (the 3-arg constructor default) shows as net::ERR_FAILED and is
    // indistinguishable from a connection failure; the 6-arg form avoids that noise.
    @Suppress("MagicNumber")
    private fun blockedResponse(): WebResourceResponse =
        WebResourceResponse(
            "text/plain",
            "UTF-8",
            403,
            "Blocked",
            emptyMap(),
            ByteArrayInputStream(ByteArray(0)),
        )
}
