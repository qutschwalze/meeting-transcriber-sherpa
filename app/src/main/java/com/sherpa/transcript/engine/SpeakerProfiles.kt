package com.sherpa.transcript.engine

import com.sherpa.transcript.SherpaTranscriptApp
import com.sherpa.transcript.data.local.SpeakerProfileStore
import java.io.File

/**
 * Phase 7a (0.7.2): Zentrale Speaker-Profil-Verwaltung.
 *
 * Einzige Bank-Instanz pro Prozess – Live-ViewModel (Auto-Enroll, Zuweisung,
 * Namens-Overlay) und der Kontakte-Screen (umbenennen, zusammenführen,
 * löschen) arbeiten auf DERSELBEN Instanz und Datei, damit keine veralteten
 * RAM-Stände divergieren. Datei: filesDir/speakerProfiles.json.
 *
 * Privacy: Die Datei (biometrische Profile + Namen) wird nie in den
 * Debug-Upload aufgenommen (der scannt nur das testaufnahmen-Verzeichnis).
 */
object SpeakerProfiles {

    @Volatile
    private var bank: GlobalVoiceBank? = null

    private val store: SpeakerProfileStore by lazy {
        SpeakerProfileStore(File(SherpaTranscriptApp.instance.filesDir, "speakerProfiles.json"))
    }

    /** Lädt die Bank beim ersten Zugriff (App-Start / erste Session). */
    fun ensureBank(): GlobalVoiceBank {
        bank?.let { return it }
        val loaded = GlobalVoiceBank(
            computer = SherpaEmbeddingComputer(SherpaTranscriptApp.instance.assets),
        ).apply { load(store.loadAll()) }
        bank = loaded
        return loaded
    }

    /** Aktuellen Bank-Zustand persistieren. */
    fun save() {
        val b = bank ?: return
        store.saveAll(b.snapshot())
    }

    /** Test-Hook: Bank-Instanz ersetzen (JVM-Tests ohne Android-App). */
    fun setBankForTest(b: GlobalVoiceBank?) {
        bank = b
    }
}