package com.tunegrab.app.download

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.22.21: o ESPELHO da Central de Downloads gravado em DISCO.
 *
 * Por que existe: o [DownloadBus] vive só em memória (é um `object`). Quando o
 * Android mata o PROCESSO inteiro do app (memória baixa, ou o app foi fechado
 * dos recentes e o service relançou o download), o espelho em memória vai
 * junto — mas o DOWNLOAD CONTINUA (é o que o `START_STICKY` + o
 * [ActiveJobStore] garantem). Resultado: o download working na notificação,
 * e a Central de Downloads mostrando "nenhum download" ou um card zerado.
 *
 * Antes da v0.22.18 isso acontecia o tempo todo; a v0.22.18 consertou o
 * DOWNLOAD, mas o CARD ainda nascia do zero. Este arquivo fecha a conta: o
 * espelho é gravado a cada mudança de estado e repovoado quando o app
 * reabre, pra o card continuar mostrando onde o download realmente está.
 *
 * É só um REGISTRO do que a tela mostra (fase, percentual, velocidade,
 * estado) — nada do arquivo baixado em si, e NADA da mecânica de download:
 * o [DownloadService] continua sendo o único dono do download.
 */
object DownloadBusStore {

    private const val TAG = "TuneGrab"
    private const val PREFS = "tunegrab_download_bus"
    private const val KEY_ITEMS = "items"
    private const val KEY_ACTIVE = "active"

    /** Grava o espelho inteiro (chamado a cada mudança de estado). */
    @Synchronized
    fun save(ctx: Context, items: List<DownloadBus.Item>, activeFileName: String?) {
        try {
            val array = JSONArray()
            items.forEach { item ->
                array.put(
                    JSONObject().apply {
                        put("file", item.fileName)
                        put("title", item.title)
                        put("phase", item.phase)
                        put("percent", item.percent)
                        put("indeterminate", item.indeterminate)
                        put("state", item.state.name)
                        put("detail", item.detail ?: JSONObject.NULL)
                        put("speed", item.speed ?: JSONObject.NULL)
                        put("updatedAt", item.updatedAt)
                    }
                )
            }
            ctx.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ITEMS, array.toString())
                .apply()
            // o download ativo é gravado/limpo junto (uma escrita só)
            if (activeFileName != null) {
                ctx.applicationContext
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_ACTIVE, activeFileName)
                    .apply()
            } else {
                ctx.applicationContext
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .remove(KEY_ACTIVE)
                    .apply()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "não consegui gravar o espelho da Central", t)
        }
    }

    /** Lê o espelho gravado (chamado quando o app reabre). */
    @Synchronized
    fun load(ctx: Context): Pair<List<DownloadBus.Item>, String?> {
        try {
            val prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_ITEMS, null) ?: return emptyList<DownloadBus.Item>() to null
            val array = JSONArray(raw)
            val out = ArrayList<DownloadBus.Item>(array.length())
            for (idx in 0 until array.length()) {
                val json = array.optJSONObject(idx) ?: continue
                val fileName = json.optString("file").takeIf { it.isNotBlank() } ?: continue
                out.add(
                    DownloadBus.Item(
                        fileName = fileName,
                        title = json.optString("title").takeIf { it.isNotBlank() } ?: fileName,
                        phase = json.optString("phase"),
                        percent = json.optInt("percent", 0).coerceIn(0, 100),
                        indeterminate = json.optBoolean("indeterminate", false),
                        state = parseState(json.optString("state")),
                        detail = json.optString("detail").takeIf { it.isNotBlank() },
                        speed = json.optString("speed").takeIf { it.isNotBlank() },
                        updatedAt = json.optLong("updatedAt", System.currentTimeMillis())
                    )
                )
            }
            val active = prefs.getString(KEY_ACTIVE, null)?.takeIf { it.isNotBlank() }
            return out to active
        } catch (t: Throwable) {
            Log.w(TAG, "não consegui ler o espelho da Central", t)
            return emptyList<DownloadBus.Item>() to null
        }
    }

    /** Apaga o espelho gravado (o app reiniciou sem download nenhum). */
    @Synchronized
    fun clear(ctx: Context) {
        try {
            ctx.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_ITEMS)
                .remove(KEY_ACTIVE)
                .apply()
        } catch (t: Throwable) {
            Log.w(TAG, "não consegui limpar o espelho da Central", t)
        }
    }

    private fun parseState(raw: String?): DownloadBus.State = when (raw) {
        DownloadBus.State.DONE.name -> DownloadBus.State.DONE
        DownloadBus.State.FAILED.name -> DownloadBus.State.FAILED
        DownloadBus.State.PAUSED.name -> DownloadBus.State.PAUSED
        DownloadBus.State.CANCELLED.name -> DownloadBus.State.CANCELLED
        else -> DownloadBus.State.RUNNING
    }
}
