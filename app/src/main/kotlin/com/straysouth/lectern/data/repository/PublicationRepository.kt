package com.straysouth.lectern.data.repository

import android.content.Context
import android.net.Uri
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser

class PublicationRepository(context: Context) {

    private val appContext = context.applicationContext
    private val httpClient = DefaultHttpClient()
    private val assetRetriever = AssetRetriever(appContext.contentResolver, httpClient)
    private val publicationOpener = PublicationOpener(
        publicationParser = DefaultPublicationParser(
            appContext,
            httpClient = httpClient,
            assetRetriever = assetRetriever,
            pdfFactory = null,
        ),
    )

    sealed class OpenError {
        data class AssetError(val message: String) : OpenError()
        data class ParseError(val message: String) : OpenError()
    }

    suspend fun open(uri: Uri): Result<Publication> {
        val url = AbsoluteUrl(uri.toString())
            ?: return Result.failure(Exception("Invalid URI: $uri"))

        return assetRetriever.retrieve(url).fold(
            onSuccess = { asset ->
                publicationOpener
                    .open(asset, allowUserInteraction = false)
                    .fold(
                        onSuccess = { Result.success(it) },
                        onFailure = { Result.failure(Exception(it.toString())) },
                    )
            },
            onFailure = { Result.failure(Exception("Failed to retrieve asset: $uri")) },
        )
    }
}
