package com.tunegrab.app.download

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.22.18: os jobs de download em andamento gravados em DISCO.
 *
 * Por que existe: o [DownloadService] devolve `START_STICKY` — se o Android
 * matar o processo (memória), o serviço é recriado e o `onStartCommand` chega
 * com intent NULO (o STICKY não reentrega o intent). Sem este registro em
 * disco, o serviço renasceria sem saber o que retomar e o download morreria
 * pela metade.
 *
 * COMO FUNCIONA: cada job é gravado quando entra na fila e apagado quando sai
 * (concluído, falhou ou cancelado). Na retomada, o serviço lê o registro e
 * relança os jobs — os parciais em disco (.part/.src no cache ou o diretório
 * estável do yt-dlp) são reencontrados, então o download continua de onde
 * parou.
 *
 * A FILA inteira é guardada (não só o job ativo): o app baixa uma música por
 * vez, mas a playlist pode ter várias na fila — se o processo morresse, todas
 * as pendentes precisam voltar.
 *
 * É só um REGISTRO dos pedidos (URLs, formato, nome do arquivo) — nada do
 * arquivo baixado em si.
 */
object ActiveJobStore {

    private const val TAG = "TuneGrab"
    private const val PREFS = "tunegrab_active_job"
    private const val KEY_JOBS = "jobs"

    /**
     * Grava (ou substitui) um job na fila persistida.
     * Falha de escrita NÃO derruba o download — é só uma rede de segurança.
     */
    @Synchronized
    fun save(ctx: Context, job: ActiveJob) {
        try {
            val jobs = loadAll(ctx).toMutableList()
            // mesmo nome de arquivo = mesmo job (não duplica)
            jobs.removeAll { it.fileName == job.fileName }
            jobs.add(job)
            writeAll(ctx, jobs)
        } catch (t: Throwable) {
            Log.w(TAG, "não consegui gravar o job ativo", t)
        }
    }

    /** Remove um job da fila persistida (saiu da fila: acabou/falhou/cancelou). */
    @Synchronized
    fun remove(ctx: Context, fileName: String) {
        try {
            val jobs = loadAll(ctx).filterNot { it.fileName == fileName }
            writeAll(ctx, jobs)
        } catch (t: Throwable) {
            Log.w(TAG, "não consegui limpar o job ativo", t)
        }
    }

    /** Apaga TUDO (nenhum trabalho pendente). */
    @Synchronized
    fun clear(ctx: Context) {
        try {
            ctx.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_JOBS)
                .apply()
        } catch (t: Throwable) {
            Log.w(TAG, "não consegui limpar o job ativo", t)
        }
    }

    /** A fila persistida, na ordem em que os jobs entraram. */
    @Synchronized
    fun loadAll(ctx: Context): List<ActiveJob> {
        try {
            val raw = ctx.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_JOBS, null)
                ?: return emptyList()
            val array = JSONArray(raw)
            val out = ArrayList<ActiveJob>(array.length())
            for (idx in 0 until array.length()) {
                val json = array.optJSONObject(idx) ?: continue
                val job = parse(json) ?: continue
                out.add(job)
            }
            return out
        } catch (t: Throwable) {
            Log.w(TAG, "não consegui ler os jobs ativos", t)
            return emptyList()
        }
    }

    /** O primeiro job da fila persistida (o que estava rodando). */
    @Synchronized
    fun load(ctx: Context): ActiveJob? = loadAll(ctx).firstOrNull()

    private fun parse(json: JSONObject): ActiveJob? {
        val fileName = json.optString("file").takeIf { it.isNotBlank() } ?: return null
        val url = json.optString("url").takeIf { it.isNotBlank() }
        val videoUrl = if (json.isNull("videoUrl")) null
            else json.optString("videoUrl").takeIf { it.isNotBlank() }
        // sem nenhuma URL não há o que retomar
        if (url == null && videoUrl == null) return null
        return ActiveJob(
            url = url,
            fileName = fileName,
            title = json.optString("title").takeIf { it.isNotBlank() } ?: fileName,
            mime = json.optString("mime").takeIf { it.isNotBlank() } ?: "audio/mp4",
            mode = json.optString("mode").takeIf { it.isNotBlank() } ?: DownloadService.MODE_DIRECT,
            bitrate = json.optInt("bitrate", 320),
            videoUrl = videoUrl,
            engineFormat = if (json.isNull("engineFormat")) null
                else json.optString("engineFormat").takeIf { it.isNotBlank() },
            maxHeight = json.optInt("maxHeight", 0)
        )
    }

    private fun writeAll(ctx: Context, jobs: List<ActiveJob>) {
        val array = JSONArray()
        jobs.forEach { job ->
            array.put(
                JSONObject().apply {
                    put("url", job.url ?: JSONObject.NULL)
                    put("file", job.fileName)
                    put("title", job.title)
                    put("mime", job.mime)
                    put("mode", job.mode)
                    put("bitrate", job.bitrate)
                    put("videoUrl", job.videoUrl ?: JSONObject.NULL)
                    put("engineFormat", job.engineFormat ?: JSONObject.NULL)
                    put("maxHeight", job.maxHeight)
                }
            )
        }
        ctx.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_JOBS, array.toString())
            .apply()
    }

    /** Todos os parâmetros do download — o suficiente para relançá-lo. */
    data class ActiveJob(
        val url: String?,
        val fileName: String,
        val title: String,
        val mime: String,
        val mode: String,
        val bitrate: Int,
        val videoUrl: String?,
        val engineFormat: String?,
        val maxHeight: Int
    )
}
