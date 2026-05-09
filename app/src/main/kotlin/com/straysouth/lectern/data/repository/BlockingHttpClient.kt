package com.straysouth.lectern.data.repository

import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.http.HttpClient
import org.readium.r2.shared.util.http.HttpError
import org.readium.r2.shared.util.http.HttpRequest
import org.readium.r2.shared.util.http.HttpStreamResponse

/**
 * [HttpClient] that unconditionally rejects every request.
 *
 * Lectern V1 sources publications exclusively from local [content://] URIs via
 * ACTION_OPEN_DOCUMENT. Readium calls [HttpClient] only when an EPUB manifest
 * or container.xml references a remote HTTP/HTTPS resource — a non-standard
 * pattern not used by any well-formed EPUB in V1.
 *
 * Passing this client instead of [org.readium.r2.shared.util.http.DefaultHttpClient]
 * prevents Readium from making outbound HTTPS calls at import time, closing the
 * tracking-beacon surface described in RED-TEAM.md §B.8.
 *
 * Blast radius: none. [AssetRetriever.retrieve] for [content://] URIs uses the
 * ContentResolver path, not the HTTP path. The WebView content server
 * (WebViewServer.shouldInterceptRequest) is entirely independent of this client.
 */
internal object BlockingHttpClient : HttpClient {
    override suspend fun stream(
        request: HttpRequest,
    ): Try<HttpStreamResponse, HttpError> =
        Try.failure(
            HttpError.IO(Exception("Network access disabled by BlockingHttpClient: ${request.url}")),
        )
}
