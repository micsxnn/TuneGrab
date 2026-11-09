package com.tunegrab.app.lyrics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * O corpo da resposta, ou null se deu ruim (404, timeout, JSON quebrado é
 * problema do chamador). Cancelamento SEMPRE sobe: uma fonte que falhou não
 * pode matar a busca inteira.
 */
suspend fun okhttp3.Call.awaitBodyOrNull(): String? = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : okhttp3.Callback {
        override fun onFailure(call: okhttp3.Call, error: IOException) {
            if (continuation.isActive) continuation.resumeWith(Result.success(null))
        }

        override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
            val body = try {
                response.use { if (it.isSuccessful) it.body?.string() else null }
            } catch (_: Exception) {
                null
            }
            if (continuation.isActive) continuation.resumeWith(Result.success(body))
        }
    })
}

/**
 * A REDE DAS LETRAS (a parte que a v0.22.16 deixou prometida).
 *
 * Duas camadas, de propósito:
 *
 *  - **Retrofit + kotlinx.serialization** para as fontes com API de verdade
 *    (LRCLIB, BetterLyrics, Unison, Kugou, Syllable-Sync, iTunes, YouLy+):
 *    a interface do serviço declara o endpoint e o DTO declara o JSON, então
 *    o parser some e a fonte fica legível.
 *  - **OkHttp cru** para as fontes sem API estável (raspagem de HTML do
 *    Genius, espelhos do LyricsPlus, lrc.red pelo ISRC): o corpo volta como
 *    texto e o parser é nosso.
 *
 * O cliente HTTP é UM só, compartilhado com o resto do app: uma conexão
 * TLS, um pool, um timeout. E nada de servidor no meio: cada pedido sai do
 * aparelho direto pro catálogo da letra.
 */
object LyricsNetwork {

    /**
     * O JSON do app. `ignoreUnknownKeys` porque nenhum catálogo garante que
     * o campo de amanhã já existe; `coerceInputValues` porque alguns mandam
     * string no lugar de número.
     */
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    /**
     * User-Agent honesto: os catálogos pedem identificação e alguns
     * (LRCLIB) recusam requisição sem ela.
     */
    const val USER_AGENT: String =
        "TuneGrab/${com.tunegrab.app.BuildConfig.VERSION_NAME} (+https://github.com/micsxnn/TuneGrab)"

    /** User-Agent de navegador, pras fontes que bloqueiam cliente genérico. */
    const val BROWSER_USER_AGENT: String =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36 TuneGrab"

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** Cliente curto pras fontes lentas (Kugou): não segura a fila da corrida. */
    val httpQuick: OkHttpClient by lazy {
        http.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(7, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    private val jsonMediaType = "application/json".toMediaType()

    /** Monta um serviço Retrofit com o conversor de kotlinx.serialization. */
    fun <T> service(baseUrl: String, type: Class<T>): T =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(http)
            .addConverterFactory(json.asConverterFactory(jsonMediaType))
            .build()
            .create(type)

    /**
     * O corpo da resposta, ou null se deu ruim (404, timeout, JSON quebrado
     * é problema do chamador).
     */
    suspend fun getText(url: String, userAgent: String = USER_AGENT): String? {
        val httpUrl = url.toHttpUrlOrNull() ?: return null
        val request = Request.Builder()
            .url(httpUrl)
            .header("User-Agent", userAgent)
            .build()
        return try {
            http.newCall(request).awaitBodyOrNull()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
    }

    /** GET num endereço com cabeçalho de aceitação específico. */
    suspend fun getText(
        url: String,
        userAgent: String,
        accept: String,
        client: OkHttpClient = http,
    ): String? {
        val httpUrl = url.toHttpUrlOrNull() ?: return null
        val request = Request.Builder()
            .url(httpUrl)
            .header("User-Agent", userAgent)
            .header("Accept", accept)
            .build()
        return try {
            client.newCall(request).awaitBodyOrNull()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
    }

    /** Busca um documento JSON com o Retrofit e devolve o corpo cru. */
    suspend fun rawJson(url: String, userAgent: String = USER_AGENT): String? =
        getText(url, userAgent, "application/json")

    /** Roda um bloco de rede fora da thread principal, sem matar o cancelamento. */
    suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
}
