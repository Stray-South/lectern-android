package com.straysouth.lectern.ui.reader

import android.view.KeyEvent
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

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
 * HOW: [R2EpubPageFragment] sets its own WebViewClient during [onCreateView]. We register a
 * [FragmentManager.FragmentLifecycleCallbacks] in [EpubReaderFragment.setupNavigator] that
 * wraps each page fragment's client after [onViewCreated] completes. The delegate receives
 * all other calls unchanged — [onPageFinished], [shouldOverrideUrlLoading],
 * [shouldOverrideKeyEvent] — preserving Readium's navigation and page-ready behaviour.
 *
 * ALLOWED hosts: only "readium" (Readium's local loopback server). All other hosts are
 * blocked unconditionally regardless of scheme.
 *
 * See RED-TEAM.md §A.2.
 */
internal class EpubBlockingWebViewClient(
    private val delegate: WebViewClient,
) : WebViewClient() {

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        val host = request.url.host
        // Null host (e.g. data: URIs) passes through — Readium uses them for inline content.
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

    // Empty body — blocks the resource silently with no content or error page.
    private fun blockedResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "UTF-8", null)
}
