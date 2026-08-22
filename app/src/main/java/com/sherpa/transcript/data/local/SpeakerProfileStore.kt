package com.sherpa.transcript.data.local

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * JSON-basierte persistente Ablage der globalen Speaker-Profile (Phase 7).
 *
 * Eine Datei `filesDir/speakerProfiles.json` – bewusst KEIN Room:
 * wenige Profile (10–30), immer komplett lesen/schreiben, kein Query-Bedarf.
 * Atomarer Save (.tmp + renameTo) schützt vor Korruption bei Absturz.
 *
 * Privacy: Diese Datei enthält biometrische Voiceprints + (ab 0.7.1) Namen.
 * Sie darf NIE in den Debug-Upload geraten (triggerDebugUpload filtert nur
 * testaufnahme_*-Dateien – bei Erweiterungen gegenprüfen!).
 */
class SpeakerProfileStore(private val profilesFile: File) {

    companion object {
        private const val TAG = "SpeakerProfileStore"

        /** Version 2 (0.7.2): optionales `name`-Feld pro Profil. */
        private const val VERSION = 2
    }

    init {
        profilesFile.parentFile?.mkdirs()
    }

    /**
     * Liest alle Profile. Jede Unlesbarkeit (Datei fehlt, kaputtes JSON,
     * falsches Schema) → leere Liste + Log.w – die App darf daran nie crashen;
     * eine leere Bank ist der sichere Fallback (alles wird neu enrolled).
     */
    fun loadAll(): List<SpeakerProfile> {
        if (!profilesFile.exists()) return emptyList()
        return try {
            val root = JSONObject(profilesFile.readText())
            val arr = root.optJSONArray("profiles") ?: return emptyList()
            buildList {
                for (i in 0 until arr.length()) {
                    val p = arr.getJSONObject(i)
                    val embJson = p.getJSONArray("embedding")
                    val emb = FloatArray(embJson.length()) { j -> embJson.getDouble(j).toFloat() }
                    add(
                        SpeakerProfile(
                            id = p.getString("id"),
                            embedding = emb,
                            sampleCount = p.optInt("sampleCount", 1),
                            updatedAt = p.optLong("updatedAt", 0L),
                            // 0.7.2: isNull-Check – JSONObject.NULL.toString() waere sonst "null"
                            name = if (p.isNull("name")) null
                            else p.optString("name").takeIf { it.isNotBlank() },
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "loadAll: Profile-Datei unlesbar (${t.message}) – starte mit leerer Bank")
            emptyList()
        }
    }

    /** Schreibt alle Profile atomar (.tmp schreiben, dann renameTo). */
    fun saveAll(profiles: List<SpeakerProfile>) {
        try {
            val arr = JSONArray()
            profiles.forEach { p ->
                val embJson = JSONArray()
                p.embedding.forEach { embJson.put(it.toDouble()) }
                arr.put(
                    JSONObject().apply {
                        put("id", p.id)
                        put("embedding", embJson)
                        put("sampleCount", p.sampleCount)
                        put("updatedAt", p.updatedAt)
                        put("name", p.name ?: JSONObject.NULL)
                    }
                )
            }
            val json = JSONObject().apply {
                put("version", VERSION)
                put("profiles", arr)
            }
            val content = json.toString(2)
            val tmp = File(profilesFile.parentFile, profilesFile.name + ".tmp")
            tmp.writeText(content)
            if (!tmp.renameTo(profilesFile)) {
                // Fallback (z.B. rename-Sonderfälle): direkt schreiben
                Log.w(TAG, "saveAll: renameTo fehlgeschlagen – Fallback direktes Schreiben")
                profilesFile.writeText(content)
                tmp.delete()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "saveAll failed: ${t.message}")
        }
    }
}