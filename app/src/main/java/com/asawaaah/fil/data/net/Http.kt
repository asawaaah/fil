package com.asawaaah.fil.data.net

import com.asawaaah.fil.data.feeds.NotAFeedException
import com.asawaaah.fil.data.feeds.OpmlException
import com.asawaaah.fil.data.feeds.RedditUrls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface FetchResult {
    class Ok(
        val bytes: ByteArray,
        val charset: String?,
        val contentType: String?,
        val finalUrl: String,
        val etag: String?,
        val lastModified: String?,
    ) : FetchResult {
        val isHtml: Boolean get() = contentType?.contains("html", ignoreCase = true) == true
    }

    data object NotModified : FetchResult
}

class HttpStatusException(val code: Int) : IOException("HTTP $code")
class TooLargeException : IOException("Réponse trop volumineuse")

class Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** Downloads [url] fully (up to [maxBytes]). Sends conditional headers when given. */
    suspend fun fetch(
        url: String,
        etag: String? = null,
        lastModified: String? = null,
        forPage: Boolean = false,
        maxBytes: Int = 15 * 1024 * 1024,
        connectTimeoutMs: Int? = null,
        agent: Agent = Agent.DEFAULT,
    ): FetchResult {
        val callClient = if (connectTimeoutMs == null) client
        else client.newBuilder().connectTimeout(connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS).build()
        val request = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                when (agent) {
                    Agent.DEFAULT -> userAgentFor(url, forPage)
                    Agent.PLAIN -> PLAIN_UA
                    Agent.BROWSER -> PAGE_UA
                },
            )
            .header(
                "Accept",
                if (forPage) "text/html,application/xhtml+xml,*/*;q=0.8"
                else "application/rss+xml, application/atom+xml, application/xml;q=0.9, text/xml;q=0.9, */*;q=0.8",
            )
            .header("Accept-Language", "fr,en;q=0.8,*;q=0.5")
            .apply {
                etag?.let { header("If-None-Match", it) }
                lastModified?.let { header("If-Modified-Since", it) }
            }
            .build()
        val response = callClient.newCall(request).await()
        return response.use { r ->
            if (r.code == 304) return@use FetchResult.NotModified
            if (!r.isSuccessful) throw HttpStatusException(r.code)
            val body = r.body
            val bytes = withContext(Dispatchers.IO) { readLimited(body.byteStream(), maxBytes) }
            val mediaType = body.contentType()
            FetchResult.Ok(
                bytes = bytes,
                charset = mediaType?.charset()?.name(),
                contentType = mediaType?.toString(),
                finalUrl = r.request.url.toString(),
                etag = r.header("ETag"),
                lastModified = r.header("Last-Modified"),
            )
        }
    }

    private fun readLimited(input: java.io.InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > maxBytes) throw TooLargeException()
        }
        return out.toByteArray()
    }

    /** How the app identifies itself; some sites only accept one kind of client. */
    enum class Agent { DEFAULT, PLAIN, BROWSER }

    companion object {
        const val REDDIT_UA = "android:com.asawaaah.fil:v0.1 (personal RSS reader)"
        const val FEED_UA = "Mozilla/5.0 (compatible; Fil/0.1; RSS reader; Android)"
        const val PLAIN_UA = "Fil/0.1 (RSS reader; Android)"
        const val PAGE_UA =
            "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0 Mobile Safari/537.36"

        fun userAgentFor(url: String, forPage: Boolean): String = when {
            RedditUrls.isReddit(url) -> REDDIT_UA
            forPage -> PAGE_UA
            else -> FEED_UA
        }
    }
}

suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response) { _, value, _ -> value.close() }
            }
        },
    )
    cont.invokeOnCancellation { cancel() }
}

/** French, user-facing explanation of a failure. */
object ErrorMessages {
    fun describe(e: Throwable): String = when (e) {
        is UnknownHostException -> "Site introuvable : le nom de domaine n'existe plus, ou pas de connexion"
        is SocketTimeoutException -> "Le site met trop de temps à répondre"
        is java.net.ConnectException -> "Le site refuse la connexion (serveur arrêté ?)"
        is java.net.UnknownServiceException -> "Connexion non sécurisée (http) refusée par le téléphone"
        is SSLException -> "Problème de certificat de sécurité du site"
        is HttpStatusException -> when (e.code) {
            401, 403 -> "Accès refusé par le site (erreur ${e.code})"
            404 -> "Flux introuvable (erreur 404)"
            410 -> "Ce flux a été supprimé (erreur 410)"
            429 -> "Trop de requêtes, le site demande d'attendre (erreur 429)"
            in 500..599 -> "Le site a un problème (erreur ${e.code})"
            else -> "Erreur du site (code ${e.code})"
        }
        is TooLargeException -> "Le fichier du flux est trop volumineux"
        is NotAFeedException -> e.message ?: "Ce n'est pas un flux valide"
        is OpmlException -> e.message ?: "Fichier OPML illisible"
        is IOException -> "Problème de connexion (${e.message ?: e.javaClass.simpleName})"
        else -> e.message ?: e.javaClass.simpleName
    }
}
