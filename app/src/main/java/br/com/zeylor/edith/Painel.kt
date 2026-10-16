package br.com.zeylor.edith

import android.os.Handler
import android.os.Looper

/** Quadro de avisos compartilhado: o servico escreve, a tela le. */
object Painel {
    val historico = StringBuilder()

    @Volatile
    var foto: ByteArray? = null

    @Volatile
    var ouvinte: (() -> Unit)? = null

    private val principal = Handler(Looper.getMainLooper())

    fun log(mensagem: String) {
        synchronized(historico) {
            historico.append(mensagem).append('\n')
            if (historico.length > 20000) historico.delete(0, 10000)
        }
        principal.post { ouvinte?.invoke() }
    }
}
