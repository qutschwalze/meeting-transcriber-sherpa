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

    /**
     * 0.11.0: Backup-Export – serialisiert die Bank ins Store-JSON (biometrische
     * Daten; der Nutzer entscheidet über das ShareSheet, wohin die Datei geht).
     * @return JSON-String oder null, wenn die Bank nie geladen wurde.
     */
    fun exportJson(): String? = bank?.let { store.toJson(it.snapshot()) }

    /**
     * 0.11.0: Backup-Import – ERSETZT die aktuelle Bank komplett durch die
     * Profile aus dem JSON (bewusst: Ersetzen, nicht Mischen – UUIDs bleiben
     * stabil, Namen kommen mit). Anschließend sofort persistiert.
     * @return Anzahl importierter Profile oder -1 bei unlesbarem Backup.
     */
    fun importJson(content: String): Int {
        val profiles = store.fromJson(content)
        if (profiles.isEmpty()) return -1
        val b = bank ?: ensureBank()
        b.load(profiles)
        store.saveAll(profiles)
        return profiles.size
    }

    /** Test-Hook: Bank-Instanz ersetzen (JVM-Tests ohne Android-App). */
    fun setBankForTest(b: GlobalVoiceBank?) {
        bank = b
    }
}