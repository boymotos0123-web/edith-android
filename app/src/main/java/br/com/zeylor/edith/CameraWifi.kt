package br.com.zeylor.edith

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * Fala com a camera do drone (MJPEG sobre UDP) pela rede Wi-Fi dela,
 * mesmo que o celular use o 4G como internet principal.
 */
object CameraWifi {
    private const val IP_CAMERA = "192.168.4.153"

    private fun acharRedeWifi(contexto: Context): Network? {
        val cm = contexto.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        for (rede in cm.allNetworks) {
            val capacidades = cm.getNetworkCapabilities(rede) ?: continue
            if (capacidades.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return rede
        }
        return null
    }

    private fun pacote(dados: ByteArray, alvo: InetAddress, porta: Int): DatagramPacket {
        return DatagramPacket(dados, dados.size, alvo, porta)
    }

    /** Devolve uma foto JPEG, ou null se a camera nao responder. Leva uns 3 a 4 segundos. */
    fun tirarFoto(contexto: Context): ByteArray? {
        val rede = acharRedeWifi(contexto) ?: return null
        val soquete = DatagramSocket()
        try {
            rede.bindSocket(soquete)
            soquete.soTimeout = 1000
            val alvo = InetAddress.getByName(IP_CAMERA)

            val oi = byteArrayOf(
                0xAA.toByte(), 0x80.toByte(), 0x80.toByte(), 0x00,
                0x80.toByte(), 0x00, 0x80.toByte(), 0x55
            )
            soquete.send(pacote(oi, alvo, 8090))
            Thread.sleep(2000)
            soquete.send(pacote(byteArrayOf(0x42, 0x77), alvo, 8080))
            soquete.send(pacote(byteArrayOf(0x42, 0x76), alvo, 8080))

            val buffer = ByteArray(2048)
            val frame = ByteArrayOutputStream()
            var comecou = false
            val limite = System.currentTimeMillis() + 6000
            while (System.currentTimeMillis() < limite) {
                val recebido = DatagramPacket(buffer, buffer.size)
                try {
                    soquete.receive(recebido)
                } catch (e: SocketTimeoutException) {
                    return null
                }
                val tamanho = recebido.length
                if (tamanho <= 8) continue
                if (tamanho >= 10 && buffer[8] == 0xFF.toByte() && buffer[9] == 0xD8.toByte()) {
                    frame.reset()
                    comecou = true
                }
                if (comecou) {
                    frame.write(buffer, 8, tamanho - 8)
                    if (buffer[1].toInt() == 1) break
                }
            }

            val bytes = frame.toByteArray()
            var fim = bytes.size - 2
            while (fim >= 0 && !(bytes[fim] == 0xFF.toByte() && bytes[fim + 1] == 0xD9.toByte())) fim--
            if (fim < 0) return null
            return bytes.copyOf(fim + 2)
        } catch (e: Exception) {
            return null
        } finally {
            soquete.close()
        }
    }
}
