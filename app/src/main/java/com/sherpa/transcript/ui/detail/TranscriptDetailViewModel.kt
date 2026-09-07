package com.sherpa.transcript.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sherpa.transcript.data.local.SegmentEntity
import com.sherpa.transcript.data.local.TranscriptEntity
import com.sherpa.transcript.data.repository.TranscriptRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DetailUiState(
    val transcript: TranscriptEntity? = null,
    val segments: List<SegmentEntity> = emptyList(),
    val isLoading: Boolean = true,
    val searchQuery: String = "",
    // 0.12.0: Trim-Modus
    val trimMode: Boolean = false,
    val trimMarkerMs: Long? = null,
    val trimResult: TrimResult? = null,
)

/** Ergebnis einer Trim/Split-Operation (für Snackbar-Feedback). */
sealed class TrimResult {
    data class Trimmed(val deletedSegments: Int) : TrimResult()
    data class Split(val newTranscriptId: String, val tailSegments: Int) : TrimResult()
}

class TranscriptDetailViewModel : ViewModel() {

    private val repository = TranscriptRepository()

    private val _uiState = MutableStateFlow(DetailUiState())
    val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

    fun loadTranscript(transcriptId: String) {
        _uiState.update { it.copy(isLoading = true) }

        viewModelScope.launch {
            val transcript = repository.getTranscript(transcriptId)
            val segments = repository.getSegments(transcriptId)
            _uiState.update {
                it.copy(
                    transcript = transcript,
                    segments = segments,
                    isLoading = false,
                )
            }
        }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(searchQuery = query) }

        val transcriptId = _uiState.value.transcript?.transcriptId ?: return

        viewModelScope.launch {
            val segments = if (query.isBlank()) {
                repository.getSegments(transcriptId)
            } else {
                repository.searchSegments(transcriptId, query)
            }
            _uiState.update { it.copy(segments = segments) }
        }
    }

    /**
     * Phase 9a (0.9.1): Sprecher-Namen nachträglich zuweisen/ändern.
     * Setzt speakerName für ALLE Segmente mit diesem Label in diesem Transkript
     * → Anzeige UND Export nutzen den Namen sofort.
     * Leerer Name = Zuweisung entfernen (zurück auf "Sprecher N").
     * Hinweis: Ein akustisches ENROLL ist hier nicht mehr möglich (Audio-Puffer
     * weg); das globale Profil benennt man über Live → Segment-Tap oder Kontakte.
     */
    fun assignSpeakerName(label: String, name: String) {
        val transcriptId = _uiState.value.transcript?.transcriptId ?: return
        viewModelScope.launch {
            repository.assignSpeakerName(transcriptId, label, name.trim().ifBlank { null })
            _uiState.update { it.copy(segments = repository.getSegments(transcriptId)) }
        }
    }

    // ─── 0.12.0: Trim/Split ──────────────────────────────────────────

    fun enterTrimMode() {
        _uiState.update { it.copy(trimMode = true, trimMarkerMs = null, trimResult = null) }
    }

    fun exitTrimMode() {
        _uiState.update { it.copy(trimMode = false, trimMarkerMs = null) }
    }

    /** Segment als Marker-Position setzen (nur wenn im Trim-Modus). */
    fun selectTrimMarker(startTimeMs: Long) {
        if (!_uiState.value.trimMode) return
        _uiState.update { it.copy(trimMarkerMs = startTimeMs) }
    }

    /** Alles nach dem Marker löschen (Trim). */
    fun executeTrim() {
        val tid = _uiState.value.transcript?.transcriptId ?: return
        val markerMs = _uiState.value.trimMarkerMs ?: return
        viewModelScope.launch {
            val deleted = repository.trimAfter(tid, markerMs)
            val segments = repository.getSegments(tid)
            val transcript = repository.getTranscript(tid)
            _uiState.update {
                it.copy(
                    trimMode = false,
                    trimMarkerMs = null,
                    trimResult = TrimResult.Trimmed(deleted),
                    segments = segments,
                    transcript = transcript,
                )
            }
        }
    }

    /** Alles nach dem Marker in ein neues Transkript verschieben (Split). */
    fun executeSplit() {
        val tid = _uiState.value.transcript?.transcriptId ?: return
        val markerMs = _uiState.value.trimMarkerMs ?: return
        viewModelScope.launch {
            val newId = repository.splitAt(tid, markerMs)
            if (newId != null) {
                val segments = repository.getSegments(tid)
                val transcript = repository.getTranscript(tid)
                val tailSegments = repository.getSegments(newId).size
                _uiState.update {
                    it.copy(
                        trimMode = false,
                        trimMarkerMs = null,
                        trimResult = TrimResult.Split(newId, tailSegments),
                        segments = segments,
                        transcript = transcript,
                    )
                }
            }
        }
    }

    fun clearTrimResult() {
        _uiState.update { it.copy(trimResult = null) }
    }
}
