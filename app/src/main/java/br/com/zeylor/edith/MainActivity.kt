package br.com.zeylor.edith

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

class MainActivity : Activity() {

    private lateinit var campoChave: EditText
    private lateinit var botao: Button
    private lateinit var estado: TextView
    private lateinit var registro: TextView
    private lateinit var rolagem: ScrollView
    private lateinit var imagem: ImageView
    private var fotoMostrada: ByteArray? = null

    private fun dp(valor: Int): Int = (valor * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val raiz = LinearLayout(this)
        raiz.orientation = LinearLayout.VERTICAL
        raiz.setBackgroundColor(Color.parseColor("#0B0F14"))
        raiz.setPadding(dp(16), dp(32), dp(16), dp(16))

        val titulo = TextView(this)
        titulo.text = "E D I T H"
        titulo.textSize = 28f
        titulo.setTextColor(Color.parseColor("#7FD4FF"))
        titulo.gravity = android.view.Gravity.CENTER
        raiz.addView(titulo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        campoChave = EditText(this)
        campoChave.hint = "Chave do Gemini (so na primeira vez)"
        campoChave.setHintTextColor(Color.GRAY)
        campoChave.setTextColor(Color.WHITE)
        campoChave.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        campoChave.setSingleLine(true)
        campoChave.setText(getSharedPreferences("edith", MODE_PRIVATE).getString("chave", ""))
        raiz.addView(campoChave, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        botao = Button(this)
        botao.setOnClickListener {
            if (EdithService.rodando) {
                stopService(Intent(this, EdithService::class.java))
            } else {
                ligar()
            }
        }
        raiz.addView(botao, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        estado = TextView(this)
        estado.setTextColor(Color.parseColor("#8AA0B4"))
        estado.gravity = android.view.Gravity.CENTER
        raiz.addView(estado, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        imagem = ImageView(this)
        imagem.visibility = View.GONE
        imagem.adjustViewBounds = true
        raiz.addView(imagem, LinearLayout.LayoutParams(dp(160), ViewGroup.LayoutParams.WRAP_CONTENT))

        registro = TextView(this)
        registro.setTextColor(Color.parseColor("#DBE7F3"))
        registro.textSize = 13f
        registro.typeface = android.graphics.Typeface.MONOSPACE
        rolagem = ScrollView(this)
        rolagem.setBackgroundColor(Color.parseColor("#121923"))
        rolagem.setPadding(dp(10), dp(10), dp(10), dp(10))
        rolagem.addView(registro)
        raiz.addView(rolagem, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(raiz)
    }

    override fun onResume() {
        super.onResume()
        Painel.ouvinte = { atualizar() }
        atualizar()
    }

    override fun onPause() {
        Painel.ouvinte = null
        super.onPause()
    }

    private fun atualizar() {
        val texto = synchronized(Painel.historico) { Painel.historico.toString() }
        registro.text = texto
        rolagem.post { rolagem.fullScroll(View.FOCUS_DOWN) }
        botao.text = if (EdithService.rodando) "Desligar EDITH" else "Ligar EDITH"
        estado.text = if (EdithService.rodando) "ligada (pode apagar a tela)" else "desligada"
        val foto = Painel.foto
        if (foto != null && foto !== fotoMostrada) {
            fotoMostrada = foto
            imagem.setImageBitmap(BitmapFactory.decodeByteArray(foto, 0, foto.size))
            imagem.visibility = View.VISIBLE
        }
    }

    private fun ligar() {
        val chave = campoChave.text.toString().trim()
        if (chave.isEmpty()) {
            Toast.makeText(this, "Cole a chave do Gemini primeiro", Toast.LENGTH_LONG).show()
            return
        }
        getSharedPreferences("edith", MODE_PRIVATE).edit().putString("chave", chave).apply()

        val faltando = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            faltando.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            faltando.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            faltando.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (faltando.isNotEmpty()) {
            requestPermissions(faltando.toTypedArray(), 1)
        } else {
            iniciarServico()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            iniciarServico()
        } else {
            Toast.makeText(this, "Sem o microfone a EDITH nao funciona", Toast.LENGTH_LONG).show()
        }
    }

    private fun iniciarServico() {
        ContextCompat.startForegroundService(this, Intent(this, EdithService::class.java))
    }
}
