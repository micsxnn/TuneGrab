package com.tunegrab.app.update

/**
 * v0.22.25: o estado do DOWNLOAD DO APK DE UPDATE, fora do fragment.
 *
 * POR QUE EXISTE: o download do APK rodava no `viewLifecycleOwner.lifecycleScope`
 * do HomeFragment — e esse escopo é CANCELADO no `onDestroyView`, ou seja,
 * NA TROCA DE ABA (o BottomNav faz `replace()` a cada troca). O resultado era
 * duplo:
 *
 *  1. o download era ABORTADO — e o `UpdateInstaller` apaga o arquivo baixado
 *     no `catch`, então o trabalho era jogado fora;
 *  2. as variáveis do fragment (`updateDownloading`, `updatePercent`) morriam
 *     junto — ao voltar pra aba, o card nascia de novo em 0%, como se o
 *     download tivesse começado do zero. É o "card continua resetando".
 *
 * AQUI o estado vive no escopo do APP (que sobrevive à troca de aba, igual à
 * fila da playlist do HomeFragment, que já usava `TuneGrabApp.appScope`
 * exatamente por isso) e o download é lançado em `appScope`.
 *
 * Isto é SÓ a interface (fase, percentual, se terminou). Nada da mecânica:
 * o [UpdateInstaller] continua sendo o único que baixa o arquivo.
 */
object UpdateDownloadState {

    /** A release que está sendo baixada (null = nada em andamento). */
    @Volatile
    var info: UpdateChecker.UpdateInfo? = null

    /** O download está rodando AGORA? */
    @Volatile
    var downloading: Boolean = false

    /** Percentual do download (0–100). */
    @Volatile
    var percent: Int = 0

    /** Terminou com sucesso — o APK está pronto pra instalar. */
    @Volatile
    var done: Boolean = false

    /** Falhou (rede, etc.) — o card mostra "tente de novo". */
    @Volatile
    var failed: Boolean = false

    /** Zera o estado (download novo). */
    fun begin(target: UpdateChecker.UpdateInfo) {
        info = target
        downloading = true
        percent = 0
        done = false
        failed = false
    }

    /** O download desta release ainda é o que está valendo? */
    fun isCurrent(target: UpdateChecker.UpdateInfo?): Boolean =
        target != null && info != null && info!!.version == target.version
}
