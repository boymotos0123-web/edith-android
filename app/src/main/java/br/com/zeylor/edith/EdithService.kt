package br.com.zeylor.edith

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * O coracao da EDITH: fica em segundo plano (mesmo com a tela apagada),
 * escuta pelo microfone, conversa com o Gemini Live e fala pelo fone.
 */
class EdithService : Service() {

    companion object {
        @Volatile
        var rodando = false

        val MODELO = "models/gemini-3.8-live"
        val VOZ = "Kore"
        val URL_WS =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        val PERSONALIDADE =
            "Você é a EDITH, a assistente de voz dos óculos do José, que gosta de ser chamado de Zeylor. " +
                "Inspirada na EDITH do Tony Stark. Fale português do Brasil, de forma natural, direta e simpática, " +
                "em no máximo duas frases curtas. Chame o José de Zeylor. " +
                "Você NÃO vê nada por conta própria. Quando o Zeylor pedir para olhar, ler, ver ou identificar algo, " +
                "chame a função olhar e depois descreva só o que realmente aparece na imagem recebida. " +
                "Nunca invente o que está na imagem: se estiver escura, borrada ou sem nada claro, diga isso. " +
                "Se a função olhar falhar, avise que a câmera não respondeu. " +
                "Você fica sempre ouvindo: se perceber que a fala não é para você, por exemplo uma conversa " +
                "de outras pessoas ao fundo, fique em silêncio."
    }

    private val cliente = OkHttpClient()
    private var ws: WebSocket? = null

    @Volatile
    private var pronta = false

    @Volatile
    private var geracao = 0
    private var fechado = false
    private var variante = 0
    private var handleSessao: String? = null
    private val reconexoes = mutableListOf<Long>()
    private var chave = ""

    private val filaAudio = LinkedBlockingQueue<ByteArray>()
    private var trilha: AudioTrack? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var gerenteAudio: AudioManager? = null
    private val principal = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "PARAR") {
            stopSelf()
            return START_NOT_STICKY
        }
        iniciarPrimeiroPlano()
        if (rodando) return START_NOT_STICKY

        chave = getSharedPreferences("edith", Context.MODE_PRIVATE).getString("chave", "") ?: ""
        rodando = true
        Painel.log("EDITH ligando...")

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "edith:live")
        wakeLock?.acquire(4 * 60 * 60 * 1000L)

        iniciarAudio()
        conectar()
        return START_NOT_STICKY
    }

    private fun iniciarPrimeiroPlano() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("edith", "EDITH", NotificationManager.IMPORTANCE_LOW))
        val parar = PendingIntent.getService(
            this, 0,
            Intent(this, EdithService::class.java).setAction("PARAR"),
            PendingIntent.FLAG_IMMUTABLE
        )
        val abrir = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notificacao = Notification.Builder(this, "edith")
            .setContentTitle("EDITH ouvindo")
            .setContentText("Toque para abrir")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(abrir)
            .addAction(android.R.drawable.ic_media_pause, "Desligar", parar)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(1, notificacao, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notificacao)
        }
    }

    // ---------------------------------------------------------------- audio

    private fun iniciarAudio() {
        val gerente = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        gerenteAudio = gerente
        gerente.mode = AudioManager.MODE_IN_COMMUNICATION

        if (Build.VERSION.SDK_INT >= 31) {
            val fone = gerente.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            }
            if (fone != null) {
                gerente.setCommunicationDevice(fone)
                Painel.log("Usando o fone bluetooth: " + fone.productName)
            } else {
                Painel.log("Nenhum fone bluetooth encontrado; usando o celular.")
            }
        } else {
            gerente.startBluetoothSco()
            gerente.isBluetoothScoOn = true
        }

        // saida: 24 kHz, mono, 16 bits (formato da voz do Gemini)
        val minimoSaida = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val novaTrilha = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(24000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minimoSaida, 48000))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        novaTrilha.play()
        trilha = novaTrilha

        Thread {
            while (rodando) {
                val pedaco = filaAudio.poll(200, TimeUnit.MILLISECONDS) ?: continue
                novaTrilha.write(pedaco, 0, pedaco.size)
            }
            novaTrilha.release()
        }.start()

        // entrada: 16 kHz, mono, 16 bits (formato que o Gemini espera)
        val minimoEntrada = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val gravador = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minimoEntrada, 6400) * 2
        )

        Thread {
            val buffer = ByteArray(3200) // 100 ms de audio
            gravador.startRecording()
            while (rodando) {
                val lidos = gravador.read(buffer, 0, buffer.size)
                if (lidos > 0 && pronta) {
                    val dados = Base64.encodeToString(buffer, 0, lidos, Base64.NO_WRAP)
                    enviar(
                        JSONObject().put(
                            "realtimeInput",
                            JSONObject().put(
                                "audio",
                                JSONObject().put("data", dados).put("mimeType", "audio/pcm;rate=16000")
                            )
                        )
                    )
                }
            }
            gravador.stop()
            gravador.release()
        }.start()
    }

    private fun limparAudio() {
        filaAudio.clear()
        trilha?.let {
            it.pause()
            it.flush()
            it.play()
        }
    }

    // ------------------------------------------------------------ conexao

    private fun montarSetup(v: Int): JSONObject {
        val agora = SimpleDateFormat("EEEE, dd 'de' MMMM 'de' yyyy, HH:mm", Locale.forLanguageTag("pt-BR")).format(Date())
        val voz = JSONObject().put(
            "voiceConfig",
            JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", VOZ))
        )
        val ferramenta = JSONObject()
            .put("name", "olhar")
            .put(
                "description",
                "Tira uma foto agora com a câmera dos óculos e entrega a imagem para você ver. " +
                    "Use quando o usuário pedir para olhar, ver, ler ou identificar algo."
            )
        val setup = JSONObject()
            .put("model", MODELO)
            .put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", PERSONALIDADE + " Data e hora atuais: " + agora + "."))
                )
            )
            .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", JSONArray().put(ferramenta))))
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())

        if (v <= 1) {
            setup.put(
                "generationConfig",
                JSONObject()
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    .put("speechConfig", voz)
            )
        } else {
            setup.put("responseModalities", JSONArray().put("AUDIO"))
            setup.put("speechConfig", voz)
        }
        if (v == 0) {
            setup.put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
            val retomada = JSONObject()
            val h = handleSessao
            if (h != null) retomada.put("handle", h)
            setup.put("sessionResumption", retomada)
        }
        return JSONObject().put("setup", setup)
    }

    private fun conectar() {
        pronta = false
        fechado = false
        geracao += 1
        val minha = geracao
        val pedido = Request.Builder().url(URL_WS + "?key=" + chave).build()
        ws = cliente.newWebSocket(pedido, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (minha != geracao) return
                webSocket.send(montarSetup(variante).toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (minha != geracao) return
                try {
                    tratar(JSONObject(text))
                } catch (e: Exception) {
                    Painel.log("(mensagem estranha: " + e.message + ")")
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                onMessage(webSocket, bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (minha != geracao) return
                Painel.log("Conexao fechada: $code $reason")
                aoFechar()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (minha != geracao) return
                Painel.log("Erro de conexao: " + t.message)
                aoFechar()
            }
        })
    }

    private fun aoFechar() {
        if (!rodando || fechado) return
        fechado = true
        val eraPronta = pronta
        pronta = false

        if (!eraPronta && variante < 2) {
            variante += 1
            Painel.log("Tentando o formato $variante do setup...")
            principal.post { if (rodando) conectar() }
            return
        }
        if (eraPronta) {
            val agora = System.currentTimeMillis()
            reconexoes.removeAll { agora - it > 20000 }
            reconexoes.add(agora)
            if (reconexoes.size <= 3) {
                Painel.log("Reconectando...")
                principal.postDelayed({ if (rodando) conectar() }, 800)
                return
            }
        }
        Painel.log("Nao consegui manter a conexao. Desligando.")
        principal.post { stopSelf() }
    }

    private fun enviar(json: JSONObject) {
        ws?.send(json.toString())
    }

    // ---------------------------------------------------------- mensagens

    private fun tratar(m: JSONObject) {
        if (m.has("setupComplete")) {
            pronta = true
            Painel.log("Pronta. Pode falar!")
            return
        }
        val retomada = m.optJSONObject("sessionResumptionUpdate")
        if (retomada != null) {
            val novo = retomada.optString("newHandle", "")
            if (novo.isNotEmpty()) handleSessao = novo
        }
        if (m.has("goAway")) Painel.log("(o servidor vai fechar a conexao em breve; reconecto sozinha)")

        val chamada = m.optJSONObject("toolCall")
        if (chamada != null) {
            val funcoes = chamada.optJSONArray("functionCalls") ?: JSONArray()
            for (i in 0 until funcoes.length()) {
                val fc = funcoes.getJSONObject(i)
                if (fc.optString("name") == "olhar") {
                    val id = fc.optString("id")
                    Thread { olhar(id) }.start()
                }
            }
            return
        }

        val conteudo = m.optJSONObject("serverContent") ?: return
        if (conteudo.optBoolean("interrupted", false)) limparAudio()

        val ouvido = conteudo.optJSONObject("inputTranscription")?.optString("text", "") ?: ""
        if (ouvido.isNotBlank()) Painel.log("Voce: $ouvido")
        val falado = conteudo.optJSONObject("outputTranscription")?.optString("text", "") ?: ""
        if (falado.isNotBlank()) Painel.log("EDITH: $falado")

        val partes = conteudo.optJSONObject("modelTurn")?.optJSONArray("parts")
        if (partes != null) {
            for (i in 0 until partes.length()) {
                val dados = partes.getJSONObject(i).optJSONObject("inlineData")?.optString("data", "") ?: ""
                if (dados.isNotEmpty()) filaAudio.put(Base64.decode(dados, Base64.DEFAULT))
            }
        }
    }

    private fun olhar(id: String) {
        Painel.log("(olhando pela camera... leva uns 4 s)")
        val foto = CameraWifi.tirarFoto(this)
        val resposta = JSONObject()
        if (foto != null) {
            Painel.foto = foto
            Painel.log("(foto ok, " + foto.size + " bytes)")
            enviar(
                JSONObject().put(
                    "realtimeInput",
                    JSONObject().put(
                        "video",
                        JSONObject()
                            .put("data", Base64.encodeToString(foto, Base64.NO_WRAP))
                            .put("mimeType", "image/jpeg")
                    )
                )
            )
            resposta.put("output", "A foto foi tirada e a imagem acabou de ser enviada. Descreva só o que realmente aparece nela.")
        } else {
            Painel.log("A camera nao respondeu (o celular esta no Wi-Fi dela?)")
            resposta.put("error", "A câmera não respondeu. Avise o Zeylor.")
        }
        val retorno = JSONObject().put("id", id).put("name", "olhar").put("response", resposta)
        enviar(JSONObject().put("toolResponse", JSONObject().put("functionResponses", JSONArray().put(retorno))))
    }

    // ------------------------------------------------------------ desligar

    override fun onDestroy() {
        rodando = false
        pronta = false
        principal.removeCallbacksAndMessages(null)
        try {
            ws?.close(1000, "fim")
        } catch (e: Exception) {
        }
        try {
            val gerente = gerenteAudio
            if (gerente != null) {
                if (Build.VERSION.SDK_INT >= 31) gerente.clearCommunicationDevice() else gerente.stopBluetoothSco()
                gerente.mode = AudioManager.MODE_NORMAL
            }
        } catch (e: Exception) {
        }
        try {
            val trava = wakeLock
            if (trava != null && trava.isHeld) trava.release()
        } catch (e: Exception) {
        }
        Painel.log("EDITH desligada.")
        super.onDestroy()
    }
}
