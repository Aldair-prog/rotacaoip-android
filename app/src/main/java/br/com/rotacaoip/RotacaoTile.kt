package br.com.rotacaoip

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Bloco nos atalhos rapidos (onde ficam Wi-Fi e lanterna).
 * Ligado: toque desativa tudo (rotacao, VPN e "Ligar ao abrir"), igual ao parar-rotacao do Windows.
 * Desligado: toque liga a rotacao.
 */
class RotacaoTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        atualizar()
    }

    override fun onClick() {
        super.onClick()
        val ativo = Engine.rodando || Engine.estado.value.vpnLigada
        if (ativo) {
            Engine.desativarTudo()
        } else if (Prefs.logado && VpnService.prepare(this) == null) {
            Engine.iniciar()
        } else {
            abrirApp() // precisa entrar na conta ou dar a permissao de VPN
        }
        atualizar(!ativo)
    }

    private fun atualizar(ligado: Boolean = Engine.rodando) {
        val t = qsTile ?: return
        t.state = if (ligado) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "Rotação de IP"
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = if (ligado) "Ligada" else "Desativada"
        t.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun abrirApp() {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 1, i, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }
}
