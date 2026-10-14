package com.nuvio.tv.core.image

import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.network.HttpException
import coil3.request.Options
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Failures where the saved copy is still the right picture: no network, a timeout or a busy server. */
internal fun isTransientImageFailure(error: Throwable): Boolean = when (error) {
    is java.io.IOException -> true
    is HttpException -> error.response.code >= 500 || error.response.code == 408 || error.response.code == 429
    else -> false
}

/** Coil drops a saved copy whose online check fails; this shows it instead of an empty tile. */
class SavedCopyOnErrorFetcher internal constructor(
    private val delegate: Fetcher,
    private val openSavedCopy: () -> FetchResult?,
) : Fetcher {
    override suspend fun fetch(): FetchResult? = try {
        delegate.fetch()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (!isTransientImageFailure(e)) throw e
        currentCoroutineContext().ensureActive()
        val saved = runCatching { openSavedCopy() }.getOrNull() ?: throw e
        savedCopiesServed.incrementAndGet()
        saved
    }

    class Factory(private val network: Fetcher.Factory<Uri>) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val fetcher = network.create(data, options, imageLoader) ?: return null
            if (!options.diskCachePolicy.readEnabled) return fetcher
            val url = data.toString()
            val key = options.diskCacheKey ?: url
            return SavedCopyOnErrorFetcher(fetcher) {
                imageLoader.diskCache?.let { cache ->
                    cache.openSnapshot(key)?.let { snapshot ->
                        SourceFetchResult(
                            source = ImageSource(snapshot.data, cache.fileSystem, key, snapshot),
                            mimeType = null,
                            dataSource = DataSource.DISK,
                        )
                    }
                }
            }
        }
    }

    companion object {
        val savedCopiesServed = AtomicInteger()
    }
}
