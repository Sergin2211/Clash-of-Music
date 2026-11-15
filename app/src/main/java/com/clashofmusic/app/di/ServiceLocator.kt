package com.clashofmusic.app.di

import android.content.Context
import com.clashofmusic.app.data.database.ClashDatabase
import com.clashofmusic.app.data.downloader.YoutubeDLManager
import com.clashofmusic.app.data.extractor.SpotifyExtractorDataSource
import com.clashofmusic.app.data.extractor.YouTubeExtractorDataSource
import com.clashofmusic.app.data.media.MediaStoreHelper
import com.clashofmusic.app.data.player.MusicPlayerManager
import com.clashofmusic.app.data.repository.DownloadRepositoryImpl
import com.clashofmusic.app.data.repository.FavoritesRepositoryImpl
import com.clashofmusic.app.data.repository.HistoryRepositoryImpl
import com.clashofmusic.app.data.repository.LibraryRepositoryImpl
import com.clashofmusic.app.data.repository.MusicRepositoryImpl
import com.clashofmusic.app.data.repository.PlaylistRepositoryImpl
import com.clashofmusic.app.data.repository.PreferencesRepositoryImpl
import com.clashofmusic.app.domain.repository.DownloadRepository
import com.clashofmusic.app.domain.repository.FavoritesRepository
import com.clashofmusic.app.domain.repository.HistoryRepository
import com.clashofmusic.app.domain.repository.LibraryRepository
import com.clashofmusic.app.domain.repository.MusicRepository
import com.clashofmusic.app.domain.repository.PlaylistRepository
import com.clashofmusic.app.domain.repository.PreferencesRepository

object ServiceLocator {

    @Volatile
    private var isInitialized = false

    lateinit var database: ClashDatabase
        private set
    lateinit var extractorDataSource: YouTubeExtractorDataSource
        private set
    lateinit var spotifyDataSource: SpotifyExtractorDataSource
        private set
    lateinit var youtubeDLManager: YoutubeDLManager
        private set
    lateinit var mediaStoreHelper: MediaStoreHelper
        private set

    lateinit var musicRepository: MusicRepository
        private set
    lateinit var downloadRepository: DownloadRepository
        private set
    lateinit var downloadActivity: com.clashofmusic.app.services.DownloadActivityMonitor
        private set
    lateinit var libraryRepository: LibraryRepository
        private set
    lateinit var genreManager: com.clashofmusic.app.data.media.GenreLibraryManager
        private set
    lateinit var playlistRepository: PlaylistRepository
        private set
    lateinit var historyRepository: HistoryRepository
        private set
    lateinit var favoritesRepository: FavoritesRepository
        private set
    lateinit var preferencesRepository: PreferencesRepository
        private set
    lateinit var playerManager: MusicPlayerManager
        private set
    lateinit var radioLibrarySaver: com.clashofmusic.app.data.player.RadioLibrarySaver
        private set

    lateinit var appleMusicDataSource: com.clashofmusic.app.data.extractor.AppleMusicDataSource
        private set
    lateinit var clashVaultHelper: com.clashofmusic.app.data.local.ClashVaultHelper
        private set
    lateinit var getDownloadedSongsUseCase: com.clashofmusic.app.domain.usecase.GetDownloadedSongsUseCase
        private set
    lateinit var getFavoriteSongsUseCase: com.clashofmusic.app.domain.usecase.GetFavoriteSongsUseCase
        private set
    lateinit var searchOnlineSongsUseCase: com.clashofmusic.app.domain.usecase.SearchOnlineSongsUseCase
        private set
    lateinit var downloadSongUseCase: com.clashofmusic.app.domain.usecase.DownloadSongUseCase
        private set
    lateinit var toggleFavoriteUseCase: com.clashofmusic.app.domain.usecase.ToggleFavoriteUseCase
        private set
    lateinit var managePlaylistUseCase: com.clashofmusic.app.domain.usecase.ManagePlaylistUseCase
        private set
    lateinit var syncStorageUseCase: com.clashofmusic.app.domain.usecase.SyncStorageUseCase
        private set
    lateinit var getStorageStatsUseCase: com.clashofmusic.app.domain.usecase.GetStorageStatsUseCase
        private set

    fun init(context: Context) {
        if (!isInitialized) {
            synchronized(this) {
                if (!isInitialized) {
                    val appContext = context.applicationContext

                    database = ClashDatabase.getInstance(appContext)
                    extractorDataSource = YouTubeExtractorDataSource(playlistRecovery = {
                        com.clashofmusic.app.data.extractor.YtDlpPlaylistReader(appContext).read(it)
                    })
                    spotifyDataSource = SpotifyExtractorDataSource()
                    appleMusicDataSource = com.clashofmusic.app.data.extractor.AppleMusicDataSource()
                    youtubeDLManager = YoutubeDLManager(appContext, extractorDataSource)
                    mediaStoreHelper = MediaStoreHelper(appContext)
                    val genreStore = com.clashofmusic.app.data.local.GenreProfileStore(java.io.File(appContext.filesDir, "genre_profiles.json"))
                    clashVaultHelper = com.clashofmusic.app.data.local.ClashVaultHelper(appContext, mediaStoreHelper, database, genreStore)

                    musicRepository = MusicRepositoryImpl(extractorDataSource, spotifyDataSource, appleMusicDataSource)
                    playlistRepository = PlaylistRepositoryImpl(
                        playlistDao = database.playlistDao(),
                        downloadedSongDao = database.downloadedSongDao(),
                        clashVaultHelper = clashVaultHelper
                    )
                    libraryRepository = LibraryRepositoryImpl(
                        downloadedSongDao = database.downloadedSongDao(),
                        playlistDao = database.playlistDao(),
                        mediaStoreHelper = mediaStoreHelper,
                        clashVaultHelper = clashVaultHelper,
                        database = database,
                        metadataEditor = com.clashofmusic.app.data.media.NativeMetadataEditor(appContext),
                        genreStore = genreStore, onSongSaved = { genreManager.analyzeNew(it) },
                        requestedFormats = com.clashofmusic.app.data.local.RequestedAudioFormatStore(java.io.File(appContext.filesDir, "requested_audio_formats.json"))
                    )
                    val genreLookup = com.clashofmusic.app.data.extractor.MusicBrainzGenres(java.io.File(appContext.cacheDir, "genre_lookup"))
                    genreManager = com.clashofmusic.app.data.media.GenreLibraryManager(libraryRepository, genreLookup)
                    historyRepository = HistoryRepositoryImpl(database.searchHistoryDao())
                    favoritesRepository = FavoritesRepositoryImpl(database.favoriteSongDao())
                    preferencesRepository = PreferencesRepositoryImpl(appContext)

                    playerManager = MusicPlayerManager(
                        context = appContext,
                        favoritesRepository = favoritesRepository,
                        libraryRepository = libraryRepository,
                        radioCache = com.clashofmusic.app.data.player.RadioAudioCache(appContext, extractorDataSource),
                        radioRecommender = com.clashofmusic.app.data.player.RadioRecommender(object : com.clashofmusic.app.data.player.RadioRecommendationSource {
                            override suspend fun related(song: com.clashofmusic.app.domain.model.DownloadedSong) =
                                if (song.songId.matches(Regex("[A-Za-z0-9_-]{11}"))) extractorDataSource.relatedSongs(song.songId) else emptyList()

                        }, onTrace = { trace ->
                            // One bounded private diagnostic, no titles, audio or library export.
                            runCatching {
                                val target = java.io.File(appContext.cacheDir, "radio_mix_trace.json")
                                val staged = java.io.File(appContext.cacheDir, "radio_mix_trace.tmp")
                                staged.writeText(trace.json(), Charsets.UTF_8)
                                staged.renameTo(target)
                            }
                        })
                    )

                    downloadRepository = DownloadRepositoryImpl(
                        context = appContext,
                        youtubeDLManager = youtubeDLManager,
                        mediaStoreHelper = mediaStoreHelper,
                        libraryRepository = libraryRepository,
                        preferencesRepository = preferencesRepository,
                        appleMusicDataSource = appleMusicDataSource
                    )
                    radioLibrarySaver = com.clashofmusic.app.data.player.RadioLibrarySaver(libraryRepository, downloadRepository, preferencesRepository)
                    downloadActivity = com.clashofmusic.app.services.DownloadActivityMonitor(appContext, downloadRepository,
                        (appContext as com.clashofmusic.app.ClashOfMusicApp).visibility.visible)

                                        getDownloadedSongsUseCase = com.clashofmusic.app.domain.usecase.GetDownloadedSongsUseCase(libraryRepository)
                    getFavoriteSongsUseCase = com.clashofmusic.app.domain.usecase.GetFavoriteSongsUseCase(favoritesRepository)
                    searchOnlineSongsUseCase = com.clashofmusic.app.domain.usecase.SearchOnlineSongsUseCase(musicRepository)
                    downloadSongUseCase = com.clashofmusic.app.domain.usecase.DownloadSongUseCase(downloadRepository, libraryRepository)
                    toggleFavoriteUseCase = com.clashofmusic.app.domain.usecase.ToggleFavoriteUseCase(favoritesRepository)
                    managePlaylistUseCase = com.clashofmusic.app.domain.usecase.ManagePlaylistUseCase(playlistRepository)
                    syncStorageUseCase = com.clashofmusic.app.domain.usecase.SyncStorageUseCase(libraryRepository)
                    getStorageStatsUseCase = com.clashofmusic.app.domain.usecase.GetStorageStatsUseCase(mediaStoreHelper)
                    isInitialized = true
                }
            }
        }
    }
}
