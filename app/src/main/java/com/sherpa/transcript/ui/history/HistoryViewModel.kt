package com.sherpa.transcript.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sherpa.transcript.data.local.TranscriptEntity
import com.sherpa.transcript.data.repository.TranscriptRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HistoryUiState(
    val transcripts: List<TranscriptEntity> = emptyList(),
    val searchQuery: String = "",
    val isLoading: Boolean = true,
)

class HistoryViewModel : ViewModel() {

    private val repository = TranscriptRepository()

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    init {
        loadTranscripts()
    }

    private fun loadTranscripts() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val list = repository.getAllTranscripts()
            _uiState.update {
                it.copy(
                    transcripts = list,
                    isLoading = false,
                )
            }
        }
    }

    fun refresh() {
        loadTranscripts()
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(searchQuery = query) }

        viewModelScope.launch {
            val list = if (query.isBlank()) {
                repository.getAllTranscripts()
            } else {
                repository.searchTranscripts(query)
            }
            _uiState.update { it.copy(transcripts = list) }
        }
    }

    fun deleteTranscript(id: String) {
        viewModelScope.launch {
            repository.deleteTranscript(id)
            loadTranscripts()
        }
    }

    fun renameTranscript(id: String, newTitle: String) {
        viewModelScope.launch {
            repository.updateTitle(id, newTitle)
            loadTranscripts()
        }
    }
}
