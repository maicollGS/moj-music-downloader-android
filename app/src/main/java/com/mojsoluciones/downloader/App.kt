package com.mojsoluciones.downloader

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Motor.iniciar(this)
    }
}
