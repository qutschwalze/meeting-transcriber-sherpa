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
)

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
            // Segmente neu laden (Anzeige + Export nutzen speakerName sofort)
            _uiState.update { it.copy(segments = repository.getSegments(transcriptId)) }
        }
    }
}
