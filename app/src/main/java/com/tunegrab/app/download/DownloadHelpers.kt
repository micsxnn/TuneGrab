package com.tunegrab.app.download

import java.io.File

/**
 * Lógica pura do download, tirada do [DownloadService] (que passou de 1.300
 * linhas) para poder ser testada sem aparelho. Nada aqui depende de Android.
 */
object DownloadHelpers {

    private val INVALID_CHARS = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

    /** "at 2.35MiB/s" na linha de progresso do yt-dlp (velocidade do plano A). */
    private val SPEED_REGEX = Regex("at\\s+([\\d.]+)\\s*([KMGT]?)iB/s")

    /**
     * Nome de arquivo seguro: troca os caracteres proibidos (e de controle) por
     * "_", e nunca devolve vazio nem só pontos (".." apontaria pro diretório pai).
     */
    fun sanitizeFileName(name: String): String {
        val clean = name.replace(INVALID_CHARS, "_").trim()
        return if (clean.isBlank() || clean.all { it == '.' }) "audio" else clean
    }

    /** Se "x.mp3" já existe, devolve "x (1).mp3", "x (2).mp3"... */
    fun uniqueFile(dir: File, fileName: String): File {
        val safeName = sanitizeFileName(fileName)
        var candidate = File(dir, safeName)
        if (!candidate.exists()) return candidate
        val base = safeName.substringBeforeLast('.')
        val ext = safeName.substringAfterLast('.', "")
        var i = 1
        while (candidate.exists()) {
            val name = if (ext.isBlank()) "$base ($i)" else "$base ($i).$ext"
            candidate = File(dir, name)
            i++
        }
        return candidate
    }

    /** Porcentagem 0..100; total desconhecido (<= 0) vira 0. */
    fun percent(done: Long, total: Long): Int =
        if (total > 0) ((done.coerceIn(0, total).toDouble() / total) * 100).toInt().coerceIn(0, 100) else 0

    /**
     * Velocidade em bytes/s lida da linha do yt-dlp:
     * "[download] 45.3% of 123MiB at 2.35MiB/s ETA 01:23". Null se não houver.
     */
    fun parseSpeedBytesPerSec(line: String?): Long? {
        if (line.isNullOrBlank()) return null
        val m = SPEED_REGEX.find(line) ?: return null
        val v = m.groupValues[1].toDoubleOrNull() ?: return null
        if (v <= 0.0) return null
        val mult = when (m.groupValues[2]) {
            "K" -> 1024.0
            "M" -> 1024.0 * 1024.0
            "G" -> 1024.0 * 1024.0 * 1024.0
            "T" -> 1024.0 * 1024.0 * 1024.0 * 1024.0
            else -> 1.0
        }
        return (v * mult).toLong()
    }
}
