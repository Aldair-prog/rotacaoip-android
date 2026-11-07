package br.com.rotacaoip

import android.app.Application
import android.net.VpnService

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        RotationService.criarCanal(this)
        Engine.init(this)
        // Se o sistema matou o app no meio da rotacao, retoma assim que o processo volta.
        if (Prefs.rodando && Prefs.logado && VpnService.prepare(this) == null) {
            Engine.iniciar()
        }
    }
}
