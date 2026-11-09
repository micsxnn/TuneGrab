package com.tunegrab.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.tunegrab.app.databinding.ActivityLyricsBinding
import com.tunegrab.app.lyrics.LyricsFetcher
import com.tunegrab.app.lyrics.LyricsRepository
import com.tunegrab.app.lyrics.LyricsResult
import com.tunegrab.app.lyrics.LyricsSource
import com.tunegrab.app.playback.PlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * GERADOR DE LETRA (v0.22.7; v0.22.17 — as fontes do LastWave entraram de
 * verdade): a tela da letra da faixa que está tocando. Aberta pelo botão
 * "Letra" do player, ela conversa com o MESMO PlaybackService (a música
 * NUNCA para nem recomeça — aqui é só interface, igual na PlayerActivity):
 *
 *  - Letra SINCRONIZADA: a linha do momento acende no tempo do som e a tela
 *    anda sozinha (estilo Spotify/YouTube Music). Quando a fonte entrega o
 *    tempo de cada PALAVRA, acende palavra a palavra (karaokê).
 *  - Letra SIMPLES: texto inteiro, sem sincronia.
 *  - Instrumental: a tela explica e pede pra curtir.
 *  - Sem letra / sem internet: mensagem com botão pra tentar de novo.
 *
 * O botão "Fonte" (canto da tela) deixa o usuário TRAVAR de onde a letra
 * vem: em vez de a corrida entre todas as fontes, só a escolhida responde.
 * É o jeito de resolver "essa letra tá errada, me dá a de outro lugar".
 *
 * A faixa pulou (fila, notificação, auto-advance)? O tick percebe que o
 * serviço mudou de faixa e busca a letra da nova SOZINHO.
 */
class LyricsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLyricsBinding

    // MESMO padrão da PlayerActivity: bind no serviço de reprodução — a
    // música continua do jeito que está, a tela só LÊ posição/título/URI
    private var playback: PlaybackService? = null
    private var bound = false
    private val serviceConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            playback = (service as? PlaybackService.LocalBinder)?.service
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playback = null
        }
    }

    // estado da tela
    private var loadedKey: String? = null      // uri da faixa cuja letra está na tela
    private var result: LyricsResult.Success? = null
    private var lineViews: List<TextView>? = null
    private var currentLine = -1
    private var loadJob: Job? = null
    private var initialDuration = 0

    /** Fonte travada pelo usuário (null = corrida automática). */
    private var forcedSource: LyricsSource? = null

    // O link do YouTube da faixa (quando veio de lá): é o que dá pra
    // buscar a letra CASADA COM O VÍDEO (legendas do YouTube, banco do
    // vídeo). Null pra arquivo local.
    private var sourceUrl: String? = null

    // usuário rolou a letra pra reler um trecho? o auto-scroll respeita e
    // volta a andar sozinho depois de uns segundos
    private var lastUserScroll = 0L

    private val tickHandler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            syncWithService()
            tickHandler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLyricsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        })
        binding.btnRetry.setOnClickListener { reloadFromService() }
        binding.btnSource.setOnClickListener { showSourcePicker() }
        binding.svLyrics.setOnTouchListener { _, _ ->
            lastUserScroll = System.currentTimeMillis()
            false
        }

        bindService(
            Intent(this, PlaybackService::class.java),
            serviceConn,
            Context.BIND_AUTO_CREATE
        )
        bound = true

        // primeira letra: a faixa que veio do player
        initialDuration = intent.getIntExtra(EXTRA_DURATION_MS, 0)
        val uriStr = intent.getStringExtra(EXTRA_URI)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        sourceUrl = intent.getStringExtra(EXTRA_SOURCE_URL)
        binding.tvHeader.text = title
        if (uriStr != null) {
            loadedKey = uriStr
            load(uriStr, title)
        } else {
            showState(getString(R.string.lyrics_not_found), false)
        }
        tickHandler.post(tick)
    }

    /**
     * Coração da sincronia, roda a cada 250ms:
     *  - o serviço mudou de faixa? Busca a letra da nova (a tela acompanha
     *    a fila — igual o título e a capa do player);
     *  - mesma faixa? Acende a linha do tempo atual.
     */
    private fun syncWithService() {
        val svc = playback ?: return
        val uri = svc.currentUri()?.toString()
        if (uri != null && uri != loadedKey) {
            loadedKey = uri
            val title = svc.currentTitle()
            binding.tvHeader.text = title
            load(uri, title)
            return
        }
        if (uri == null || uri != loadedKey) return
        if (binding.tvHeader.text.toString() != svc.currentTitle()) {
            binding.tvHeader.text = svc.currentTitle()
        }
        if (result?.isSynced == true) highlight(svc.position())
    }

    /** Busca a letra da faixa [uriStr] (lavagem do título + as fontes). */
    private fun load(uriStr: String, title: String) {
        loadJob?.cancel()
        showLoading()
        loadJob = lifecycleScope.launch {
            val durMs = try {
                playback?.duration()?.takeIf { it > 0 } ?: initialDuration
            } catch (ignored: Throwable) {
                initialDuration
            }
            val videoId = LyricsFetcher.videoIdOf(sourceUrl)
            val q = withContext(Dispatchers.IO) {
                LyricsFetcher.buildQuery(applicationContext, title, uriStr, durMs, videoId)
            }
            try {
                val res = LyricsRepository.fetch(q, forcedSource)
                if (isFinishing || isDestroyed || loadedKey != uriStr) return@launch
                when (res) {
                    is LyricsResult.Success -> {
                        result = res
                        showLyrics(res)
                    }

                    LyricsResult.Empty -> {
                        result = null
                        showState(getString(R.string.lyrics_not_found), true)
                    }

                    is LyricsResult.Error -> {
                        result = null
                        showState(getString(R.string.lyrics_error), true)
                    }
                }
            } catch (ignored: IOException) {
                if (isFinishing || isDestroyed || loadedKey != uriStr) return@launch
                result = null
                showState(getString(R.string.lyrics_error), true)
            } catch (ignored: Exception) {
                if (isFinishing || isDestroyed || loadedKey != uriStr) return@launch
                result = null
                showState(getString(R.string.lyrics_error), true)
            }
        }
    }

    /** Botão "Tentar de novo": refaz a busca da faixa que está tocando. */
    private fun reloadFromService() {
        val uri = playback?.currentUri()?.toString() ?: loadedKey ?: return
        loadedKey = uri
        load(uri, playback?.currentTitle() ?: binding.tvHeader.text.toString())
    }

    /**
     * Escolhe de onde a letra vem. "Automático" volta pro modo corrida (a
     * melhor fonte que responder primeiro); qualquer outra trava a busca
     * naquela fonte só.
     */
    private fun showSourcePicker() {
        val entries = LyricsSource.entries
        val labels = entries.map { it.label }.toTypedArray()
        val checked = forcedSource?.let { src -> entries.indexOf(src) } ?: 0
        AlertDialog.Builder(this)
            .setTitle(R.string.lyrics_source_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                forcedSource = if (which == 0) null else entries[which]
                dialog.dismiss()
                reloadFromService()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------- estados da tela ----------

    private fun showLoading() {
        binding.progressLyrics.visibility = View.VISIBLE
        binding.stateBox.visibility = View.GONE
        binding.btnRetry.visibility = View.GONE
        binding.svLyrics.visibility = View.GONE
        binding.svPlain.visibility = View.GONE
        currentLine = -1
        lineViews = null
        binding.tvCredit.text = getString(
            R.string.lyrics_credit_source,
            forcedSource?.label ?: getString(R.string.lyrics_source_auto),
        )
    }

    private fun showState(message: String, retry: Boolean) {
        binding.progressLyrics.visibility = View.GONE
        binding.svLyrics.visibility = View.GONE
        binding.svPlain.visibility = View.GONE
        binding.stateBox.visibility = View.VISIBLE
        binding.tvState.text = message
        binding.btnRetry.visibility = if (retry) View.VISIBLE else View.GONE
    }

    private fun showLyrics(res: LyricsResult.Success) {
        binding.progressLyrics.visibility = View.GONE
        when {
            // instrumental sem letra nenhuma: nada pra mostrar além do recado
            res.isInstrumental && res.plainLyrics.isNullOrBlank() ->
                showState(getString(R.string.lyrics_instrumental), false)

            res.isSynced && res.lines.isNotEmpty() -> {
                binding.stateBox.visibility = View.GONE
                binding.svPlain.visibility = View.GONE
                buildSynced(res.lines)
                binding.svLyrics.visibility = View.VISIBLE
            }

            else -> {
                binding.stateBox.visibility = View.GONE
                binding.svLyrics.visibility = View.GONE
                binding.tvPlain.text = res.plainLyrics.orEmpty()
                binding.svPlain.visibility = View.VISIBLE
            }
        }
        binding.tvCredit.text = if (res.isWordSynced) {
            getString(R.string.lyrics_credit_word, res.sourceLabel)
        } else {
            getString(R.string.lyrics_credit_source, res.sourceLabel)
        }
    }

    // ---------- letra sincronizada ----------

    /**
     * Cada linha da letra vira um TextView dentro do scroll (letra inteira
     * em memória: centenas de TextView é nada, e o toque/destaque fica
     * trivial — sem adapter, sem recycle, sem holder sujo).
     */
    private fun buildSynced(lines: List<com.tunegrab.app.lyrics.LyricLine>) {
        binding.llLyrics.removeAllViews()
        currentLine = -1
        val views = ArrayList<TextView>(lines.size)
        val pad = (resources.displayMetrics.density * 10).toInt()
        lines.forEachIndexed { i, line ->
            val tv = TextView(this)
            tv.text = line.text.ifBlank { "·" }
            tv.textSize = 16f
            tv.setTypeface(Typeface.DEFAULT, Typeface.NORMAL)
            tv.setLineSpacing(0f, 1.25f)
            tv.setPadding(0, pad, 0, pad)
            tv.setTextColor(ContextCompat.getColor(this, R.color.on_surface_variant))
            // toque no verso = pula a música pra ele (só se ESTA faixa toca)
            tv.setOnClickListener {
                lastUserScroll = System.currentTimeMillis()
                val svc = playback
                val uri = svc?.currentUri()?.toString()
                if (svc != null && uri == loadedKey) svc.seekTo(line.timeMs.toInt())
            }
            binding.llLyrics.addView(tv)
            views.add(tv)
        }
        lineViews = views
        binding.svLyrics.scrollTo(0, 0)
    }

    /**
     * Acende a linha do momento: binária manual simples (a última linha cujo
     * tempo já chegou), recolorindo SÓ quando a linha muda. Auto-scroll
     * centraliza a linha a um terço do topo — e RESPEITA quem rolou pra
     * reler (4s de silêncio antes de voltar a andar sozinho).
     */
    private fun highlight(posMs: Int) {
        val views = lineViews ?: return
        val lines = result?.lines ?: return
        if (views.size != lines.size) return
        val now = posMs.toLong()
        var idx = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= now) idx = i else break
        }
        if (idx == currentLine) return
        currentLine = idx
        val primary = ContextCompat.getColor(this, R.color.primary)
        val normal = ContextCompat.getColor(this, R.color.on_surface_variant)
        views.forEachIndexed { i, tv ->
            if (i == idx) {
                tv.setTextColor(primary)
                tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                tv.textSize = 18f
            } else {
                tv.setTextColor(normal)
                tv.setTypeface(Typeface.DEFAULT, Typeface.NORMAL)
                tv.textSize = 16f
            }
        }
        if (idx >= 0 && System.currentTimeMillis() - lastUserScroll > 4000) {
            val v = views[idx]
            v.post {
                binding.svLyrics.smoothScrollTo(
                    0,
                    (v.top - binding.svLyrics.height / 3).coerceAtLeast(0)
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tickHandler.removeCallbacks(tick)
        loadJob?.cancel()
        if (bound) {
            try {
                unbindService(serviceConn)
            } catch (ignored: IllegalArgumentException) {
            }
            bound = false
        }
        // sem stopNow: a música segue tocando — esta tela só olhava
        playback = null
    }

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_TITLE = "title"
        const val EXTRA_DURATION_MS = "duration_ms"

        /** O link do YouTube da faixa (opcional): libera as fontes por vídeo. */
        const val EXTRA_SOURCE_URL = "source_url"

        /** Abre a tela da letra pra faixa que o player está tocando. */
        fun start(
            ctx: Context,
            uri: String,
            title: String,
            durationMs: Int,
            sourceUrl: String? = null,
        ): Intent =
            Intent(ctx, LyricsActivity::class.java)
                .putExtra(EXTRA_URI, uri)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_DURATION_MS, durationMs)
                .putExtra(EXTRA_SOURCE_URL, sourceUrl)
    }
}
