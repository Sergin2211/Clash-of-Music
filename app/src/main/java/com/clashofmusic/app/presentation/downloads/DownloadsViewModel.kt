package com.clashofmusic.app.presentation.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clashofmusic.app.di.ServiceLocator
import com.clashofmusic.app.domain.model.DownloadStatus
import com.clashofmusic.app.domain.model.DownloadTask
import com.clashofmusic.app.domain.model.DownloadedSong
import com.clashofmusic.app.domain.model.StorageSpaceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DownloadsUiState(
    val activeTasks: List<DownloadTask> = emptyList(),
    val completedTasks: List<DownloadTask> = emptyList(),
    val failedTasks: List<DownloadTask> = emptyList(),
    val storageSpace: StorageSpaceInfo = StorageSpaceInfo(),
    val isCancellingAll: Boolean = false,
    val cancellationError: String? = null
)

class DownloadsViewModel(
    private val downloadRepository: com.clashofmusic.app.domain.repository.DownloadRepository = ServiceLocator.downloadRepository
) : ViewModel() {
    private val libraryRepository = ServiceLocator.libraryRepository
    private val mediaStoreHelper = ServiceLocator.mediaStoreHelper
    val playerManager = ServiceLocator.playerManager

    private val _uiState = MutableStateFlow(DownloadsUiState())
    val uiState: StateFlow<DownloadsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.Default) {
            downloadRepository.observeTasks().collect { tasks ->
                val active = tasks.filter { it.isActive }
                val completed = tasks.filter { it.status == DownloadStatus.COMPLETED }
                val failed = tasks.filter { it.status == DownloadStatus.FAILED || it.status == DownloadStatus.CANCELLED }

                _uiState.update {
                    it.copy(
                        activeTasks = active,
                        completedTasks = completed,
                        failedTasks = failed
                    )
                }
            }
        }
        refreshStorage()
    }

    fun refreshStorage() {
        viewModelScope.launch(Dispatchers.IO) {
            val stats = mediaStoreHelper.getStorageSpaceInfo()
            _uiState.update { it.copy(storageSpace = stats) }
        }
    }

    fun pauseTask(taskId: String) {
        viewModelScope.launch {
            downloadRepository.pauseDownload(taskId)
        }
    }

    fun resumeTask(taskId: String) {
        viewModelScope.launch {
            downloadRepository.resumeDownload(taskId)
        }
    }

    fun cancelTask(taskId: String) {
        viewModelScope.launch {
            downloadRepository.cancelDownload(taskId)
        }
    }

    fun cancelAllTasks() {
        if (_uiState.value.isCancellingAll) return
        _uiState.update { it.copy(isCancellingAll = true, cancellationError = null) }
        viewModelScope.launch {
            try { downloadRepository.cancelAllDownloads() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { _uiState.update { it.copy(cancellationError = "No se pudieron cancelar todas las descargas. Inténtalo de nuevo.") } }
            finally { _uiState.update { it.copy(isCancellingAll = false) } }
        }
    }

    fun dismissCancellationError() { _uiState.update { it.copy(cancellationError = null) } }

    fun retryTask(taskId: String) {
        viewModelScope.launch {
            downloadRepository.retryDownload(taskId)
        }
    }

    fun deleteTask(taskId: String) {
        viewModelScope.launch {
            downloadRepository.deleteDownloadTask(taskId)
        }
    }

    fun clearCompleted() {
        viewModelScope.launch {
            downloadRepository.clearFinishedDownloads()
            refreshStorage()
        }
    }

    fun playCompletedSong(task: DownloadTask) {
        viewModelScope.launch {
            val song = libraryRepository.getDownloadedSong(task.songId)
            if (song != null) {
                playerManager.playSong(song)
            }
        }
    }
}
