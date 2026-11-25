package com.tunegrab.app.ui

import android.graphics.Color
import android.os.Build
import android.view.View
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Barras do sistema na era do ANDROID 15 (v0.22.31, targetSdk 35): o
 * edge-to-edge virou OBRIGATÓRIO — a barra de status e a de navegação
 * ficam transparentes e o conteúdo do app desenha por baixo delas. Antes
 * (targetSdk 34) o sistema reservava o espaço das barras e o app só
 * pintava a cor; com o 15 o espaço não existe mais e quem marca a área
 * segura agora é o app, via insets.
 *
 * O MESMO caminho serve em TODAS as versões do app (minSdk 24 = Android 7):
 * o [enableEdgeToEdge] do androidx.activity resolve 7–14 com mecanismos
 * compatíveis — no Android 11, por exemplo, as barras ficam transparentes
 * e os paddings de inset funcionam igual. Um só código, do 7 ao 15.
 *
 * Uso: [edgeToEdge] no onCreate (antes do setContentView) e as áreas
 * seguras com [pad] — a MainActivity e o Player usam os helpers direto
 * nas views que precisam (container de abas, barra de abas, controles).
 */
object SystemBars {

    /** Liga o edge-to-edge na activity e desliga o "véu de contraste" que
     *  o Android 10+ desenha sobre a barra de navegação transparente —
     *  sem isto o sistema clareia a barra sobre o fundo escuro do app. */
    fun edgeToEdge(activity: AppCompatActivity) {
        // App sempre escuro: ícones CLAROS nas barras em qualquer tema do
        // aparelho. O auto() do androidx escolhe pelo tema DO SISTEMA — num
        // aparelho em modo claro, os ícones escuros sumiriam no nosso fundo.
        activity.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activity.window.isNavigationBarContrastEnforced = false
        }
    }

    /**
     * Marca a área segura numa view: soma o inset da barra de status ao
     * padding de cima ([top]) e/ou o da barra de navegação ao de baixo
     * ([bottom]); com [withIme], o padding de baixo passa a valer também
     * para o TECLADO (o maior dos dois vence) — é o "adjustResize" do
     * mundo edge-to-edge. O padding que a view já tinha no XML é
     * preservado: o inset entra POR CIMA dele.
     *
     * O listener devolve os insets SEM consumir: irmãos e filhos também
     * recebem (a Main marca o container de abas e a barra de abas com
     * dois pads independentes).
     */
    fun pad(view: View, top: Boolean = false, bottom: Boolean = false, withIme: Boolean = false) {
        val baseTop = view.paddingTop
        val baseBottom = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = if (withIme) insets.getInsets(WindowInsetsCompat.Type.ime()).bottom else 0
            if (top) v.updatePadding(top = baseTop + bars.top)
            if (bottom) v.updatePadding(bottom = baseBottom + maxOf(bars.bottom, ime))
            insets
        }
    }
}
