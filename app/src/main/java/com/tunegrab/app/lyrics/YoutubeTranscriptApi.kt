package com.tunegrab.app.lyrics

import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * O parâmetro protobuf do endpoint de transcript do YouTube Music.
 *
 * O InnerTube manda o ID do vídeo como um protobuf de UM campo: o campo 1,
 * tipo "length-delimited" (a tag 0x0A). É exatamente o que
 * `kotlinx-serialization-protobuf` gera, então a biblioteca entra aqui com
 * trabalho de verdade em vez de ficar declarada à toa.
 */
@Serializable
data class TranscriptParams(
    @ProtoNumber(1) val videoId: String,
)

/**
 * ADAPTADOR DO INNERTUBEX (a parte que a v0.22.16 prometeu e não entregou).
 *
 * O InnerTubeX é o cliente InnerTube completo (o mesmo do Metrolist), e ele
 * traz o endpoint `get_transcript` do YouTube Music, que devolve as LEGENDAS
 * com tempo do vídeo — a letra que o próprio YouTube usa na aba "Letra".
 *
 * Por que reflexão: o artefato publicado é compilado com um metadado de
 * Kotlin mais novo que o do app, então ele não pode ser importado pelo
 * compilador. Ele fica como `runtimeOnly` e é chamado por reflexão, que é
 * exatamente o que o LastWave faz com a mesma biblioteca.
 */
object InnerTubeXTranscript {

    private const val TAG = "TuneGrabInnerTubeX"

    /** O cliente InnerTubeX pronto (criado uma vez e reaproveitado). */
    private val runtime: RuntimeStack? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching { createRuntime() }
            .onFailure { android.util.Log.w(TAG, "InnerTubeX indisponível", it) }
            .getOrNull()
    }

    /** Verdadeiro quando a biblioteca está no classpath e foi montada. */
    val available: Boolean get() = runtime != null

    /**
     * As legendas com tempo do vídeo. Devolve null quando a biblioteca não
     * está no aparelho (o APK mínimo não traz ela) ou o vídeo não tem
     * legenda.
     */
    suspend fun cues(videoId: String): List<LyricCue>? = withContext(Dispatchers.IO) {
        val stack = runtime ?: return@withContext null
        val id = videoId.trim()
        if (!VIDEO_ID.matches(id)) return@withContext null
        try {
            val client = companionCall("com.metrolist.innertubex.models.YouTubeClient", "getWEB_REMIX")
            val method = Class.forName("com.metrolist.innertubex.CaptionsKt").methods.firstOrNull {
                it.name == "transcriptCues" && it.parameterCount == 4
            } ?: return@withContext null
            val raw = invokeSuspend(stack.innerTube, method, arrayOf(client, id)) as? List<*> ?: return@withContext null
            raw.mapNotNull { cue ->
                if (cue == null) return@mapNotNull null
                val text = readString(cue, "getText")?.trim()?.trim(' ', '\n', '\t', '♪').orEmpty()
                if (text.isEmpty()) return@mapNotNull null
                val start = (readValue(cue, "getStartMs") as? Number)?.toLong() ?: return@mapNotNull null
                val duration = (readValue(cue, "getDurationMs") as? Number)?.toLong() ?: 0L
                LyricCue(startMs = start, durationMs = duration, text = text)
            }.sortedBy { it.startMs }.takeIf { it.isNotEmpty() }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: LinkageError) {
            android.util.Log.w(TAG, "InnerTubeX incompatível: ${error.javaClass.simpleName}")
            null
        } catch (_: Exception) {
            null
        }
    }

    /** Monta o InnerTubeX com um cliente HTTP do Ktor (via reflexão). */
    private fun createRuntime(): RuntimeStack {
        val httpClient = createKtorHttpClient()
        val innerTube = constructWithDefaults(
            "com.metrolist.innertubex.InnerTube",
            arrayOf(httpClient),
            defaultMask = 6,
        )
        return RuntimeStack(innerTube = innerTube)
    }

    /**
     * O InnerTubeX fala Ktor. O engine do OkHttp é o mesmo caminho de TLS
     * que o resto do app já usa com sucesso; o CIO é a reserva (ele falha em
     * Android quando a app tem `network_security_config` com cleartext
     * liberado, porque o trust manager do Android recusa a checagem sem
     * hostname do CIO).
     */
    private fun createKtorHttpClient(): Any {
        runCatching { createEngineClient("io.ktor.client.engine.okhttp.OkHttp") }
            .onSuccess { return it }
            .onFailure {
                android.util.Log.w(TAG, "engine OkHttp do Ktor indisponível: ${it.javaClass.simpleName}")
            }
        runCatching { createEngineClient("io.ktor.client.engine.cio.CIO") }
            .onSuccess { return it }
        throw IllegalStateException("nenhum engine do Ktor disponível")
    }

    private fun createEngineClient(engineClassName: String): Any {
        val engine = Class.forName(engineClassName).getField("INSTANCE").get(null)
        val factory = Class.forName("io.ktor.client.HttpClientKt").methods.first {
            it.name == "HttpClient" && it.parameterCount == 2 &&
                it.parameterTypes[0].name == "io.ktor.client.engine.HttpClientEngineFactory"
        }
        // Configuração vazia: o HttpClient do Ktor aceita uma função; um
        // no-op serve, porque o InnerTubeX não precisa de timeout customizado.
        val noConfig: (Any?) -> Unit = { }
        return factory.invoke(null, engine, noConfig)!!
    }

    /** Chama um método `suspend` via reflexão (a continuação vai no fim). */
    private suspend fun invokeSuspend(
        receiver: Any,
        method: Method,
        arguments: Array<Any?>,
    ): Any? = suspendCancellableCoroutine { continuation ->
        val completion = object : Continuation<Any?> {
            override val context: CoroutineContext = continuation.context

            override fun resumeWith(result: Result<Any?>) {
                continuation.resumeWith(result)
            }
        }
        try {
            val result = method.invoke(receiver, *(arguments + completion))
            if (result !== COROUTINE_SUSPENDED && continuation.isActive) continuation.resume(result)
        } catch (error: InvocationTargetException) {
            if (continuation.isActive) continuation.resumeWith(Result.failure(error.targetException))
        } catch (error: Throwable) {
            if (continuation.isActive) continuation.resumeWith(Result.failure(error))
        }
    }

    /** Chama um método do `companion object` de uma classe do InnerTubeX. */
    private fun companionCall(className: String, methodName: String): Any {
        val companion = runCatching {
            Class.forName(className).getField("Companion").get(null)
        }.recoverCatching {
            val companionClass = Class.forName("$className\$Companion")
            val field = companionClass.declaredFields.firstOrNull {
                it.name == "INSTANCE" || it.name == "\$\$INSTANCE" || it.name == "Companion"
            } ?: companionClass.getField("INSTANCE")
            field.isAccessible = true
            field.get(null)
        }.getOrThrow()
        return companion.javaClass.getMethod(methodName).invoke(companion)
    }

    /**
     * Constrói uma classe com os PARÂMETROS OBRIGATÓRIOS e o resto no
     * padrão (o construtor sintético do Kotlin recebe uma máscara de
     * defaults no penúltimo argumento).
     */
    private fun constructWithDefaults(
        className: String,
        required: Array<Any?>,
        defaultMask: Int,
    ): Any {
        val type = Class.forName(className)
        val constructor = type.declaredConstructors.firstOrNull { candidate ->
            candidate.parameterCount >= required.size + 2 &&
                candidate.parameterTypes[candidate.parameterCount - 2] == Int::class.javaPrimitiveType &&
                candidate.parameterTypes.last().name == "kotlin.jvm.internal.DefaultConstructorMarker" &&
                required.withIndex().all { (index, value) ->
                    value == null || candidate.parameterTypes[index].isAssignableFrom(value.javaClass)
                }
        } ?: error("Nenhum construtor compatível para $className")
        constructor.isAccessible = true
        val originalParameterCount = constructor.parameterCount - 2
        val arguments = arrayOfNulls<Any?>(constructor.parameterCount)
        required.copyInto(arguments)
        arguments[originalParameterCount] = defaultMask
        return constructor.newInstance(*arguments)
    }

    private fun readValue(instance: Any, getter: String): Any? =
        instance.javaClass.methods.firstOrNull { it.name == getter && it.parameterCount == 0 }?.invoke(instance)

    private fun readString(instance: Any, getter: String): String? = readValue(instance, getter) as? String

    private data class RuntimeStack(val innerTube: Any)

    private val VIDEO_ID = Regex("[A-Za-z0-9_-]{1,64}")
}

/** Uma legenda com tempo (a mesma forma do transcript do YouTube). */
data class LyricCue(
    val startMs: Long,
    val durationMs: Long,
    val text: String,
)

/**
 * LEGENDAS DO YOUTUBE — as legendas com tempo do próprio vídeo.
 *
 * Duas rotas:
 *
 *  1. **InnerTubeX** (a biblioteca `runtimeOnly`): o cliente InnerTube
 *     completo do Metrolist, que já sabe falar com o YouTube Music.
 *  2. **Chamada direta** ao endpoint `get_transcript`, com o parâmetro
 *     protobuf montado pelo `kotlinx-serialization-protobuf`. É a reserva
 *     quando o InnerTubeX não está no aparelho.
 *
 * Serve de letra quando a faixa é o vídeo que está tocando (a letra que o
 * YouTube mesmo mostra na aba "Letra"), e de legenda quando o vídeo tem
 * fala em vez de música.
 */
class YoutubeTranscriptApi : LyricsSourceApi {

    override val source: LyricsSource = LyricsSource.YOUTUBE_CAPTIONS

    override suspend fun fetch(query: LyricsQuery): SourceLyrics? {
        val videoId = query.videoId?.trim().orEmpty()
        if (videoId.isBlank()) return null
        val cues = InnerTubeXTranscript.cues(videoId)?.takeIf { it.isNotEmpty() }
            ?: fetchDirect(videoId)?.takeIf { it.isNotEmpty() }
            ?: return null
        val lines = cues.map { cue ->
            LyricLine(
                timeMs = cue.startMs.coerceAtLeast(0L),
                durationMs = cue.durationMs.coerceAtLeast(0L),
                text = cue.text,
            )
        }
        return SourceLyrics(
            lines = lines,
            isSynced = true,
            isWordSynced = false,
            plainLyrics = lines.joinToString("\n") { it.text },
        )
    }

    /**
     * Chamada direta ao InnerTube do YouTube Music. O `params` é o ID do
     * vídeo dentro de um protobuf de um campo, codificado em Base64.
     */
    private suspend fun fetchDirect(videoId: String): List<LyricCue>? = withContext(Dispatchers.IO) {
        val params = try {
            val bytes = ProtoBuf.encodeToByteArray(TranscriptParams.serializer(), TranscriptParams(videoId))
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (_: Exception) {
            return@withContext null
        }
        val body = """
            {"context":{"client":{"clientName":"WEB_REMIX","clientVersion":"$CLIENT_VERSION","hl":"en","gl":"US"}},"params":"$params"}
        """.trimIndent()
        val request = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/get_transcript?key=$API_KEY&prettyPrint=false")
            .header("Content-Type", "application/json")
            .header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .header("User-Agent", LyricsNetwork.BROWSER_USER_AGENT)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val text = try {
            LyricsNetwork.http.newCall(request).awaitBodyOrNull()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        } ?: return@withContext null
        runCatching { parseCues(text) }.getOrNull()
    }

    /** Lê o transcriptCueRenderer da resposta do InnerTube. */
    internal fun parseCues(raw: String): List<LyricCue> {
        val root = LyricsNetwork.json.parseToJsonElement(raw).jsonObject
        val actions = root["actions"] as? JsonArray ?: return emptyList()
        val cues = mutableListOf<LyricCue>()
        actions.forEach { action ->
            val panel = (action as? JsonObject)?.get("updateEngagementPanelAction") as? JsonObject ?: return@forEach
            val content = panel["content"] as? JsonObject ?: return@forEach
            val renderer = content["transcriptRenderer"] as? JsonObject ?: return@forEach
            val body = renderer["body"] as? JsonObject ?: return@forEach
            val bodyRenderer = body["transcriptBodyRenderer"] as? JsonObject ?: return@forEach
            val groups = bodyRenderer["cueGroups"] as? JsonArray ?: return@forEach
            groups.forEach { group ->
                val groupRenderer = (group as? JsonObject)?.get("transcriptCueGroupRenderer") as? JsonObject ?: return@forEach
                val list = groupRenderer["cues"] as? JsonArray ?: return@forEach
                list.forEach { cueItem ->
                    val cue = (cueItem as? JsonObject)?.get("transcriptCueRenderer") as? JsonObject ?: return@forEach
                    val start = (cue["startOffsetMs"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: return@forEach
                    val duration = (cue["durationMs"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L
                    val cueText = (cue["cue"] as? JsonObject)?.get("simpleText") as? JsonPrimitive
                    val text = cueText?.contentOrNull?.trim(' ', '\n', '\t', '♪').orEmpty()
                    if (text.isNotEmpty()) cues += LyricCue(start, duration, text)
                }
            }
        }
        return cues.sortedBy { it.startMs }
    }

    private companion object {
        /** A chave pública do cliente web do YouTube Music (não é credencial). */
        const val API_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"

        /** Versão do cliente web do YouTube Music. */
        const val CLIENT_VERSION = "1.20250101.01.00"
    }
}
