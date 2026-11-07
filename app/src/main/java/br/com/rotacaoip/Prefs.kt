package br.com.rotacaoip

import android.content.Context
import android.content.SharedPreferences

/** Configuracoes salvas no aparelho (privadas do app). */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(c: Context) {
        sp = c.getSharedPreferences("rotacaoip", Context.MODE_PRIVATE)
    }

    var conta: String
        get() = sp.getString("conta", "") ?: ""
        set(v) = sp.edit().putString("conta", v).apply()

    var chavePrivada: String
        get() = sp.getString("chave", "") ?: ""
        set(v) = sp.edit().putString("chave", v).apply()

    /** Endereco do tunel devolvido pelo Mullvad, ex.: "10.66.1.2/32,fc00:bbbb::1/128" */
    var endereco: String
        get() = sp.getString("endereco", "") ?: ""
        set(v) = sp.edit().putString("endereco", v).apply()

    var minutos: Int
        get() = sp.getInt("minutos", 10)
        set(v) = sp.edit().putInt("minutos", v).apply()

    var ligarAoAbrir: Boolean
        get() = sp.getBoolean("auto", true)
        set(v) = sp.edit().putBoolean("auto", v).apply()

    /** Se a rotacao estava ligada (para retomar se o sistema reiniciar o app). */
    var rodando: Boolean
        get() = sp.getBoolean("rodando", false)
        set(v) {
            sp.edit().putBoolean("rodando", v).commit()
        }

    var relaysJson: String
        get() = sp.getString("relays", "") ?: ""
        set(v) = sp.edit().putString("relays", v).apply()

    var relaysEm: Long
        get() = sp.getLong("relaysEm", 0L)
        set(v) = sp.edit().putLong("relaysEm", v).apply()

    val logado: Boolean
        get() = conta.isNotEmpty() && chavePrivada.isNotEmpty() && endereco.isNotEmpty()

    fun sair() {
        val min = minutos
        val auto = ligarAoAbrir
        sp.edit().clear().commit()
        minutos = min
        ligarAoAbrir = auto
    }
}
