package com.straysouth.lectern.ui.reader

import android.view.KeyEvent
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
 * passes through — Readium uses these for inline content injection.
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
    ): Boolean = delegate.shouldOverrideUrlLoading(view, request)

    override fun onPageFinished(view: WebView, url: String) {
        delegate.onPageFinished(view, url)
    }

    override fun shouldOverrideKeyEvent(
        view: WebView,
        event: KeyEvent,
    ): Boolean = delegate.shouldOverrideKeyEvent(view, event)

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
