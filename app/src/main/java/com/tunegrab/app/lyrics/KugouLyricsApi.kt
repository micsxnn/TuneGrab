package com.tunegrab.app.lyrics

import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.InflaterInputStream

@Serializable
data class KugouSearchResponse(
    val status: Int = 0,
    val errcode: Int = 0,
    val error: String = "",
    val candidates: List<KugouCandidate> = emptyList(),
)

@Serializable
data class KugouCandidate(
    val id: String = "",
    val accesskey: String = "",
    val song: String = "",
    val singer: String = "",
    val duration: Long = 0L,
    @SerialName("score") val score: Int = 0,
)

@Serializable
data class KugouDownloadResponse(
    val status: Int = 0,
    val errcode: Int = 0,
    val error: String = "",
    val content: String? = null,
    val fmt: String = "krc",
)

/**
 * KUGOU — letra no formato KRC, que é o karaokê do QQ Music.
 *
 * O KRC é um LRC melhorado: a linha começa com `[inicio,duracao]` em
 * milissegundos
 * e cada palavra vem marcada com `<deslocamento,duracao,?>`. O arquivo vem
 * criptografado: Base64, com 4 bytes de cabeçalho (`krc1`), XOR com uma
 * chave fixa de 16 bytes e depois compactado com ZLIB.
 *
 * A chave e o formato são públicos e estáveis há anos no cliente oficial do
 * Kugou, então a decifragem é feita aqui mesmo, no aparelho.
 */
class KugouLyricsApi : LyricsSourceApi {

    override val source: LyricsSource = LyricsSource.KUGOU

    /** A chave XOR do KRC (16 bytes, a mesma do cliente oficial). */
    private val krcKey = byteArrayOf(
        0x40.toByte(), 0x47.toByte(), 0x61.toByte(), 0x77.toByte(),
        0x5e.toByte(), 0x32.toByte(), 0x74.toByte(), 0x47.toByte(),
        0x51.toByte(), 0x36.toByte(), 0x31.toByte(), 0x2d.toByte(),
        0xce.toByte(), 0xd2.toByte(), 0x6e.toByte(), 0x69.toByte(),
    )

    private val krcOffsetRegex = Regex("""^\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)
    private val krcLineRegex = Regex("""^\[(\d+),(\d+)](.*)$""")
    private val krcSyllableRegex = Regex("""<(\d+),(\d+),\d+>([^<]*)""")
    private val creditLineRegex = Regex(
        """(?i)^\s*(?:lyrics\s*by|written\s*by|composed\s*by|produced\s*by|arranged\s*by|recorded\s*(?:at|by)|mixed\s*by|remixed\s*by|mixing\s*(?:assistant|engineer)?|mastered\s*by|mastering|drums|guitar|bass|keyboards|strings|vocals?)\s*[:：]"""
    )

    override suspend fun fetch(query: LyricsQuery): SourceLyrics? = withContext(Dispatchers.IO) {
        val title = query.title.trim()
        val artist = query.artist.trim()
        if (title.isBlank() || artist.isBlank()) return@withContext null

        try {
            val cleanTitle = LyricsMatchers.cleanTrackTitle(title)
            val cleanArtist = LyricsMatchers.cleanArtistName(artist)
            val searchTitle = LyricsMatchers.stripLeadingArtistPrefix(cleanTitle).ifBlank { cleanTitle }

            val searchUrl = "https://lyrics.kugou.com/search".toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("ver", "1")
                ?.addQueryParameter("man", "yes")
                ?.addQueryParameter("client", "pc")
                ?.addQueryParameter("keyword", "$cleanArtist - $searchTitle")
                ?.addQueryParameter(
                    "duration",
                    if (query.durationSec != null && query.durationSec > 0) "${query.durationSec * 1000}" else "",
                )
                ?.addQueryParameter("hash", "")
                ?.build() ?: return@withContext null

            val searchRequest = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", KUGOU_USER_AGENT)
                .get()
                .build()

            val searchBody = LyricsNetwork.httpQuick.newCall(searchRequest).awaitBodyOrNull()
                ?: return@withContext null
            val searchResult = runCatching {
                LyricsNetwork.json.decodeFromString<KugouSearchResponse>(searchBody)
            }.getOrNull() ?: return@withContext null
            if (searchResult.candidates.isEmpty()) return@withContext null

            fun durationDelta(candidate: KugouCandidate): Long =
                if (query.durationSec != null && query.durationSec > 0 && candidate.duration > 0) {
                    kotlin.math.abs(candidate.duration - (query.durationSec * 1000L))
                } else {
                    0L
                }

            fun textMatches(candidate: KugouCandidate): Boolean {
                // Nunca servir a gravação errada (ao vivo, remix, cover).
                if (!LyricsMatchers.sameVersion(title, candidate.song)) return false
                val candSinger = LyricsMatchers.cleanArtistName(candidate.singer)
                val candSong = LyricsMatchers.cleanTrackTitle(candidate.song)
                if (!LyricsMatchers.artistMatches(candSinger, cleanArtist)) return false
                return LyricsMatchers.titlesMatch(candSong, cleanTitle) ||
                    LyricsMatchers.titlesMatch(candSong, searchTitle)
            }

            val textMatched = searchResult.candidates.filter(::textMatches).sortedBy(::durationDelta)

            // O casamento de texto é obrigatório: a versão antiga aceitava
            // qualquer música com a mesma duração, o que entregava letra
            // errada.
            val candidate = textMatched.firstOrNull { durationDelta(it) <= 8_000L }
                ?: textMatched.firstOrNull { durationDelta(it) <= 30_000L }
                ?: textMatched.firstOrNull()
                ?: return@withContext null

            val downloadUrl = "https://lyrics.kugou.com/download".toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("ver", "1")
                ?.addQueryParameter("client", "pc")
                ?.addQueryParameter("id", candidate.id)
                ?.addQueryParameter("accesskey", candidate.accesskey)
                ?.addQueryParameter("fmt", "krc")
                ?.addQueryParameter("charset", "utf8")
                ?.build() ?: return@withContext null

            val downloadRequest = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", KUGOU_USER_AGENT)
                .get()
                .build()

            val downloadBody = LyricsNetwork.httpQuick.newCall(downloadRequest).awaitBodyOrNull()
                ?: return@withContext null
            val downloadResult = runCatching {
                LyricsNetwork.json.decodeFromString<KugouDownloadResponse>(downloadBody)
            }.getOrNull() ?: return@withContext null
            val rawBase64 = downloadResult.content ?: return@withContext null

            val decrypted = decryptKrc(rawBase64) ?: return@withContext null
            val lines = parseKrc(decrypted).takeIf { it.isNotEmpty() } ?: return@withContext null
            SourceLyrics(
                lines = lines,
                isSynced = true,
                isWordSynced = true,
                plainLyrics = lines.joinToString("\n") { it.text },
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
    }

    /** Base64 → tira o cabeçalho `krc1` → XOR → ZLIB → texto. */
    private fun decryptKrc(base64Content: String): String? {
        return try {
            val encBytes = Base64.decode(base64Content, Base64.DEFAULT)
            if (encBytes.size <= 4) return null
            val xorBytes = ByteArray(encBytes.size - 4)
            for (i in 4 until encBytes.size) {
                val keyByte = krcKey[(i - 4) % krcKey.size]
                xorBytes[i - 4] = (encBytes[i].toInt() xor keyByte.toInt()).toByte()
            }
            val inflaterStream = InflaterInputStream(ByteArrayInputStream(xorBytes))
            val outputStream = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            var bytesRead: Int
            while (inflaterStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
            }
            inflaterStream.close()
            outputStream.toString("UTF-8")
        } catch (_: Exception) {
            null
        }
    }

    /** Lê o KRC: `[inicio,duracao]` na linha e `<offset,duracao,?>na palavra`. */
    private fun parseKrc(krcText: String): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()
        var globalOffsetMs = 0L
        var isFirstRawLine = true

        for (rawLine in krcText.lines()) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() || !trimmed.startsWith("[")) continue

            val offsetMatch = krcOffsetRegex.matchEntire(trimmed)
            if (offsetMatch != null) {
                globalOffsetMs = offsetMatch.groupValues[1].toLongOrNull() ?: 0L
                continue
            }

            val match = krcLineRegex.matchEntire(trimmed) ?: continue
            val rawLineStartMs = match.groupValues[1].toLongOrNull() ?: continue
            val lineDurationMs = match.groupValues[2].toLongOrNull() ?: 0L
            val syllablesContent = match.groupValues[3]

            val lineStartMs = (rawLineStartMs + globalOffsetMs).coerceAtLeast(0L)

            val syllables = mutableListOf<LyricSyllable>()
            val lineTextBuilder = StringBuilder()
            for (sylMatch in krcSyllableRegex.findAll(syllablesContent)) {
                val offsetMs = sylMatch.groupValues[1].toLongOrNull() ?: 0L
                val durMs = sylMatch.groupValues[2].toLongOrNull() ?: 0L
                val sylText = sylMatch.groupValues[3]

                if (sylText.isBlank()) {
                    if (syllables.isNotEmpty()) {
                        val last = syllables.last()
                        if (!last.text.endsWith(" ")) {
                            syllables[syllables.lastIndex] = last.copy(text = last.text + " ")
                        }
                    }
                    lineTextBuilder.append(sylText)
                    continue
                }

                val sylTimeMs = (lineStartMs + offsetMs).coerceAtLeast(0L)
                syllables += LyricSyllable(
                    timeMs = sylTimeMs,
                    durationMs = durMs,
                    text = sylText,
                    isBackground = false,
                )
                lineTextBuilder.append(sylText)
            }

            val fullLineText = lineTextBuilder.toString().trim()
            if (fullLineText.isNotEmpty() && syllables.isNotEmpty()) {
                val isCredit = creditLineRegex.containsMatchIn(fullLineText) ||
                    (isFirstRawLine && (fullLineText.contains(" - ") || fullLineText.contains(" – ")))
                isFirstRawLine = false
                if (isCredit) continue
                lines += LyricLine(
                    timeMs = lineStartMs,
                    durationMs = lineDurationMs,
                    text = fullLineText,
                    syllables = syllables,
                )
            }
        }
        return lines.sortedBy { it.timeMs }
    }

    private companion object {
        /** O Kugou recusa cliente que não seja o do desktop. */
        const val KUGOU_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    }
}
