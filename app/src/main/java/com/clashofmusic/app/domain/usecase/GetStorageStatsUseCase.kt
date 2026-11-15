package com.clashofmusic.app.domain.usecase

import com.clashofmusic.app.data.media.MediaStoreHelper
import com.clashofmusic.app.domain.model.StorageSpaceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GetStorageStatsUseCase(
    private val mediaStoreHelper: MediaStoreHelper
) {
    suspend operator fun invoke(): StorageSpaceInfo = withContext(Dispatchers.IO) {
        mediaStoreHelper.getStorageSpaceInfo()
    }
}
