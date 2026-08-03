package com.sherpa.transcript

import android.app.Application

class SherpaTranscriptApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: SherpaTranscriptApp
            private set
    }
}
