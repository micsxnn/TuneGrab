package com.tunegrab.app.download

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Espelho em memória do estado dos downloads, para a Central de Downloads.
 *
 * IMPORTANTE: isto NÃO faz parte da mecânica de download — o DownloadService
 * continua dono da mecânica e apenas AVISA aqui o que já informa nas
 * notificações (mesma fase, mesmo percentual, mesma velocidade). Se ninguém
 * estiver olhando a Central, nada muda: os dados vivem só neste objeto, sem
 * rede, sem disco, sem lógica.
 *
 * v0.22.21: o espelho também é GRAVADO em DISCO ([DownloadBusStore]) — se o
 * Android matar o processo inteiro, o download continua (START_STICKY) e o
 * card precisa continuar mostrando onde ele está, em vez de renascer zerado.
 */
object DownloadBus {

    enum class State { RUNNING, DONE, FAILED, PAUSED, CANCELLED }

    data class Item(
        val fileName: String,
        val title: String,
        val phase: String = "",
        val percent: Int = 0,
        val indeterminate: Boolean = true,
        val state: State = State.RUNNING,
        val detail: String? = null,
        /** Velocidade atual (texto pronto, ex.: "2,3 MB/s") — só quando faz sentido. */
        val speed: String? = null,
        val updatedAt: Long = System.currentTimeMillis()
    )

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items

    /** Nome do arquivo do download que está RODANDO de fato (fila: os outros esperam). */
    @Volatile
    var activeFileName: String? = null
        private set

    /**
     * v0.22.21: contexto do app, usado só pra GRAVAR o espelho em disco.
     * Null enquanto o app não Started (nada a gravar — o download não existe).
     */
    @Volatile private var appContext: Context? = null

    /**
     * v0.22.21: última gravação em disco (epoch ms). O progresso chega
     * várias vezes por segundo — gravar o espelho inteiro em cada batada
     * seria I/O à toa. A gravação é ADIADA: no máximo 1 vez por segundo, e
     * na hora se for uma mudança de ESTADO (começou/terminou/pausou), que
     * é o que importa se o processo morrer.
     */
    @Volatile private var lastPersistAt = 0L

    /** De quanto em quanto tempo o espelho é gravado (ms). */
    private const val PERSIST_INTERVAL_MS = 1_000L

    /** Tag do log de diagnóstico do espelho (v0.22.22). */
    private const val TAG = "TuneGrabBus"

    /**
     * v0.22.21: liga o espelho ao disco e REPOVOA o que foi gravado antes.
     *
     * Chamado uma vez, quando o app abre (TuneGrabApp). O que é lido aqui é o
     * último estado conhecido do download: se ele ainda está rodando, o card
     * renasce no percentual em que parou — não em 0%.
     *
     * Só os itens VIVOS (rodando/pausado) voltam: o histórico de downloads
     * concluídos/falhados é de uma sessão só (a Central semprestarts vazia de
     * histórico), senão o app abriria com uma lista de downloads antigos de
     * semanas atrás que ninguém terminou.
     */
    @Synchronized
    fun attach(ctx: Context) {
        if (appContext != null) return
        val app = ctx.applicationContext
        appContext = app
        val (saved, active) = DownloadBusStore.load(app)
        val alive = saved.filter { it.state == State.RUNNING || it.state == State.PAUSED }
        if (alive.isNotEmpty()) {
            _items.value = alive
            // o ativo só vale se o item vivo for ele mesmo (o dono do download
            // é o DownloadService — se ele morreu, o card fica sem ativo)
            activeFileName = active?.takeIf { name -> alive.any { it.fileName == name } }
        } else {
            // nada vivo: o espelho gravado é só histórico — some com ele
            DownloadBusStore.clear(app)
        }
    }

    /**
     * Grava o espelho em disco (toda mudança de estado passa por aqui).
     *
     * [force] =true grava na hora (mudança de ESTADO: começou, terminou,
     * pausou, falhou). O progresso comum é adiado em [PERSIST_INTERVAL_MS] —
     * gravar o JSON inteiro a cada batida seria I/O à toa (e o progresso
     * chega várias vezes por segundo).
     */
    private fun persist(force: Boolean = false) {
        val ctx = appContext ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastPersistAt < PERSIST_INTERVAL_MS) return
        lastPersistAt = now
        DownloadBusStore.save(ctx, _items.value, activeFileName)
    }

    @Synchronized
    fun setActive(fileName: String?) {
        activeFileName = fileName
        persist(force = true)
    }

    /**
     * v0.22.21: o download COMEÇOU (ou foi relançado).
     *
     * NÃO-DESTRUTIVO de propósito: se o item JÁ existe na lista, o estado
     * atual (percentual, fase, velocidade) é PRESERVADO e só o título é
     * atualizado se mudou.
     *
     * Por quê (o bug do "card reseta ao trocar de aba"): o DownloadService é
     * relançado pelo Android (START_STICKY, onTaskRemoved, morte por memória)
     * e cada relançamento passa por aqui. Antes, este método SUBSTITUÍA o
     * item inteiro por um Item novo (percent = 0, phase = "") — então o card
     * da Central voltava pra "preparando, 0%" mesmo com o download já em 40%.
     *
     * Criar o item só faz sentido quando ele ainda não existe na lista.
     */
    @Synchronized
    fun start(title: String, fileName: String) {
        val list = _items.value.toMutableList()
        val idx = list.indexOfFirst { it.fileName == fileName }
        if (idx >= 0) {
            val current = list[idx]
            // v0.22.21: o item VIVO (rodando/pausado) tem o progresso
            // PRESERVADO — relançar o mesmo job NÃO pode zerar o card.
            // Só o título é corrigido se mudou.
            if (current.state == State.RUNNING || current.state == State.PAUSED) {
                // v0.22.22 (DIAGNÓSTICO): o log mostra o que o card exibia
                // ANTES e DEPOIS do start() — se o card ainda resetar, este
                // log diz qual chamada zerou o percentual.
                Log.i(
                    TAG,
                    "start preservado: $fileName — estado=${current.state} " +
                        "percent=${current.percent} fase='${current.phase}'"
                )
                if (current.title != title) {
                    list[idx] = current.copy(title = title, updatedAt = System.currentTimeMillis())
                    _items.value = list
                    persist(force = true)
                }
                return
            }
            // item ENCERRADO (cancelado/falhado) e o mesmo arquivo foi pedido
            // de novo → recomeça do zero (percent 0, barra indeterminada).
            Log.i(
                TAG,
                "start RECOMEÇA do zero: $fileName — estado anterior=${current.state}"
            )
            list[idx] = Item(fileName = fileName, title = title)
            _items.value = list
            persist(force = true)
        } else {
            Log.i(TAG, "start novo item: $fileName")
            list.add(0, Item(fileName = fileName, title = title))
            _items.value = list
            persist(force = true)
        }
    }

    /**
     * v0.22.21: progresso do download.
     *
     * Não rebaixa o percentual: um `progress` com percent menor que o atual
     * (atraso de notificação, service relançado que reconta do zero) NÃO
     * pode apagar o avanço que o card já mostra.
     *
     * v0.22.24: o mesmo atraso não pode trocar a FASE nem a barra: quando o
     * percent chega MENOR, o card inteiro é preservado (só o log registra).
     */
    @Synchronized
    fun progress(
        fileName: String,
        phase: String,
        percent: Int,
        indeterminate: Boolean,
        speed: String? = null
    ) {
        // v0.22.22 (DIAGNÓSTICO): o log mostra quando o percentual CHEGA
        // menor do que o card já exibia (a situação que zeraria o card
        // antes do maxOf) — e também quando a barra volta a ficar
        // indeterminada (que esconde o percentual na tela).
        val before = _items.value.firstOrNull { it.fileName == fileName }
        if (before != null && percent < before.percent) {
            Log.w(
                TAG,
                "progress REBAIXADO (ignorado): $fileName — veio $percent%, " +
                    "card já estava em ${before.percent}%"
            )
        }
        mutate(fileName) {
            if (percent < it.percent) {
                // progresso atrasado: preserva o card INTEIRO (fase, barra,
                // velocidade e percentual) — senão o card mostrava
                // "Baixando · 99%" (a fase atrasada com o percentual novo)
                it
            } else {
                it.copy(
                    phase = phase,
                    percent = percent,
                    indeterminate = indeterminate,
                    speed = speed
                )
            }
        }
    }

    /**
     * v0.22.24: o download (re)começou a rodar — fase "preparando" ou
     * "retomando".
     *
     * Se o item JÁ TINHA progresso (percent > 0), a barra NÃO volta a ficar
     * indeterminada e a fase anterior é preservada: o card não pode
     * "voltar pra preparando" quando o download na verdade já está a 40%.
     * A fase nova (preparando/retomando) entra no texto, mas a barra segue
     * mostrando o percentual real.
     */
    @Synchronized
    fun starting(fileName: String, phase: String) {
        mutate(fileName) {
            if (it.percent > 0) {
                it.copy(phase = phase, indeterminate = false)
            } else {
                it.copy(phase = phase, indeterminate = true)
            }
        }
    }

    /**
     * v0.22.24: o item entrou na FILA (outro download está rodando).
     *
     * Se ele JÁ tinha progresso (é o mesmo job relançado), a fase e a barra
     * não voltam pra "na fila" — o card mantém onde estava. Só o item
     * realmente novo entra como "na fila" com barra indeterminada.
     */
    @Synchronized
    fun queued(fileName: String, phase: String) {
        mutate(fileName) {
            if (it.percent > 0) {
                it
            } else {
                it.copy(phase = phase, indeterminate = true)
            }
        }
    }

    @Synchronized
    fun finished(fileName: String) {
        mutate(fileName) {
            it.copy(
                state = State.DONE,
                phase = "",
                percent = 100,
                indeterminate = false,
                speed = null,
                detail = null
            )
        }
    }

    @Synchronized
    fun paused(fileName: String) {
        mutate(fileName) {
            it.copy(
                state = State.PAUSED,
                phase = "",
                indeterminate = false,
                speed = null,
                detail = null
            )
        }
    }

    @Synchronized
    fun cancelled(fileName: String) {
        mutate(fileName) {
            it.copy(
                state = State.CANCELLED,
                phase = "",
                indeterminate = false,
                speed = null,
                detail = null
            )
        }
    }

    @Synchronized
    fun failed(fileName: String, message: String) {
        mutate(fileName) {
            it.copy(state = State.FAILED, phase = "", indeterminate = false, speed = null, detail = message)
        }
    }

    @Synchronized
    fun clearFinished() {
        // itens vivos (rodando/pausado) ficam; só limpa o histórico
        _items.value = _items.value.filter {
            it.state == State.RUNNING || it.state == State.PAUSED
        }
        // v0.22.24: força a gravação — se o processo morrer logo depois, o
        // espelho em disco não pode trazer os concluídos de volta.
        persist(force = true)
    }

    /**
     * Remove UM item do espelho (v0.19.5): a Central apagou a mídia do
     * aparelho e o card precisa sair na hora — sem esperar o fim do app.
     * Espelho de UI: nada da mecânica de download passa por aqui.
     */
    @Synchronized
    fun remove(fileName: String) {
        _items.value = _items.value.filter { it.fileName != fileName }
        // v0.22.24: força a gravação (o item deletado não pode ressuscitar).
        persist(force = true)
    }

    private fun mutate(fileName: String, transform: (Item) -> Item) {
        val list = _items.value.toMutableList()
        val idx = list.indexOfFirst { it.fileName == fileName }
        if (idx < 0) return
        val before = list[idx]
        val after = transform(before)
        list[idx] = after.copy(updatedAt = System.currentTimeMillis())
        _items.value = list
        // v0.22.21: mudança de ESTADO grava na hora (terminou/pausou/falhou);
        // o progresso comum é adiado (chega várias vezes por segundo).
        persist(force = before.state != after.state)
    }
}
