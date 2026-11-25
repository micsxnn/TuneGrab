package com.tunegrab.app

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import com.tunegrab.app.databinding.ActivityMainBinding
import com.tunegrab.app.download.DownloadService
import com.tunegrab.app.ui.BottomNav
import com.tunegrab.app.ui.HomeFragment
import com.tunegrab.app.ui.SystemBars

/**
 * Hospedeiro das abas (Início · YouTube · Downloads · Favoritos ·
 * Biblioteca · Configurações). Só cuida da barra de navegação e dos intents de
 * COMPARTILHAR/ABRIR link do YouTube (que caem sempre na aba Início).
 * Todo o resto vive nos fragments.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /** Link compartilhado que chegou antes da aba Início estar pronta. */
    private var pendingSharedUrl: String? = null

    /**
     * Modo do link pendente (v0.22.11): false = fluxo de baixar (padrão de
     * sempre), true = fluxo de OUVIR. Só faz sentido junto do
     * [pendingSharedUrl] — é consumido quando o Início processa o link.
     */
    private var pendingAutoPlay: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ANDROID 15 (v0.22.31): edge-to-edge em todas as versões — barras
        // do sistema transparentes, o app desenha por baixo (do 7 ao 15)
        SystemBars.edgeToEdge(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Áreas seguras na mão (as barras transparentes não reservam mais
        // espaço): o conteúdo das abas nasce abaixo da barra de status e a
        // barra de abas desce até embaixo. withIme: o TECLADO levanta a
        // barra (e o container encolhe) — o adjustResize clássico não
        // existe mais no mundo edge-to-edge.
        SystemBars.pad(binding.fragmentContainer, top = true)
        SystemBars.pad(binding.navBar.root, bottom = true, withIme = true)

        BottomNav.setup(this, binding.navBar.bottomNav, R.id.fragmentContainer, R.id.navHome)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        // v0.19.5: toque em qualquer notificação de download abre a CENTRAL.
        // App fechado: onCreate → a barra já nasce na Central; app aberto:
        // onNewIntent troca a aba (singleTask sempre cai aqui)
        if (intent?.action == DownloadService.ACTION_OPEN_CENTRAL) {
            openTab(R.id.navDownloads)
            return
        }
        val shared = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        } ?: return
        val home = supportFragmentManager.findFragmentById(R.id.fragmentContainer) as? HomeFragment
        if (home != null && home.isAdded) {
            home.handleSharedUrl(shared)
        } else {
            pendingSharedUrl = shared
        }
    }

    /** Chamado pela HomeFragment quando ela fica pronta (consome o link pendente). */
    fun consumePendingSharedUrl(): String? = pendingSharedUrl.also { pendingSharedUrl = null }

    /** Abre uma aba pelo id do menu (usado pelo botão de engrenagem da Home). */
    fun openTab(itemId: Int) {
        if (binding.navBar.bottomNav.selectedItemId != itemId) {
            binding.navBar.bottomNav.selectedItemId = itemId
        }
    }

    /**
     * PEGAR DO NAVEGADOR (v0.19.2): o botão "Baixar" da aba YouTube manda o
     * link aqui — cai no MESMO fluxo da aba Início (campo preenchido + busca
     * + seletor de formato + download que leva pra Central sozinho).
     */
    fun openDownloadFor(url: String) {
        val current = supportFragmentManager.findFragmentById(R.id.fragmentContainer)
        if (current is HomeFragment && current.isAdded) {
            current.handleSharedUrl(url)
            return
        }
        pendingSharedUrl = url
        pendingAutoPlay = false // baixar é o padrão; limpa resíduo de um play pendente
        openTab(R.id.navHome)
    }

    /**
     * OUVIR UM FAVORITO (v0.22.11): o toque no card da aba Favoritos manda o
     * link aqui — cai no MESMO fluxo do play da aba Início (busca + rotação
     * anti-bot + player), só que com o play disparado sozinho. Nenhuma
     * lógica de stream nasce aqui: favorito é um atalho pro caminho principal.
     */
    fun openPlayFor(url: String) {
        val current = supportFragmentManager.findFragmentById(R.id.fragmentContainer)
        if (current is HomeFragment && current.isAdded) {
            current.handleSharedUrl(url, autoplay = true)
            return
        }
        pendingSharedUrl = url
        pendingAutoPlay = true
        openTab(R.id.navHome)
    }

    /** Consumido pelo Início junto do link pendente (openPlayFor seta true). */
    fun consumePendingAutoPlay(): Boolean = pendingAutoPlay.also { pendingAutoPlay = false }
}
