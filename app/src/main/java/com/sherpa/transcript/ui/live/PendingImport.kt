package com.sherpa.transcript.ui.live

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase 9c (0.9.3): Übergabe Share-Intent → LiveViewModel.
 *
 * Löst das Zwei-Instanzen-Problem: MainActivity bekommt den Share-Intent,
 * aber der Live-Screen nutzt eine NAV-scoped LiveViewModel-Instanz (eigener
 * ViewModelStore pro NavBackStackEntry) – ein direkt dort aufgerufenes
 * importAudio() lief daher in einer ANDEREN Instanz als der angezeigte
 * Live-Screen (Symptom: leerer Live-Screen nach dem Teilen).
 *
 * Lösung: MainActivity legt die URI hier ab; LiveScreen konsumiert sie beim
 * ersten Compose und startet den Import auf SEINER Instanz.
 */
object PendingImport {
    private var uri: Uri? = null
    private var name: String? = null

    @Synchronized
    fun put(u: Uri, n: String) { uri = u; name = n }

    @Synchronized
    fun consume(): Pair<Uri, String>? {
        val u = uri ?: return null
        uri = null
        return u to (name ?: "Sprachnachricht")
    }
}

/**
 * Phase 9c: Import-Fortschritt als Prozess-Singleton – der globale Banner in
 * der AppNavigation liest hier, unabhängig davon, welche ViewModel-Instanz
 * den Import gerade ausführt.
 */
object ImportUiBridge {
    private val _progress = MutableStateFlow(-1)
    val progress = _progress.asStateFlow()

    private val _fileName = MutableStateFlow<String?>(null)
    val fileName = _fileName.asStateFlow()

    fun set(p: Int, f: String?) {
        _progress.value = p
        _fileName.value = f
    }

    /** Phase 9c: „Überspringen" – Banner schließen (Zustand zurücksetzen). */
    fun dismiss() = set(-1, null)
}