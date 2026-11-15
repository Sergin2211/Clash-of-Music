package com.clashofmusic.app.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.clashofmusic.app.data.database.dao.DownloadedSongDao
import com.clashofmusic.app.data.database.dao.FavoriteSongDao
import com.clashofmusic.app.data.database.dao.PlaylistDao
import com.clashofmusic.app.data.database.dao.SearchHistoryDao
import com.clashofmusic.app.data.database.entity.DownloadedSongEntity
import com.clashofmusic.app.data.database.entity.FavoriteSongEntity
import com.clashofmusic.app.data.database.entity.PlaylistEntity
import com.clashofmusic.app.data.database.entity.PlaylistSongEntity
import com.clashofmusic.app.data.database.entity.SearchHistoryEntity

@Database(
    entities = [
        DownloadedSongEntity::class,
        SearchHistoryEntity::class,
        FavoriteSongEntity::class,
        PlaylistEntity::class,
        PlaylistSongEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class ClashDatabase : RoomDatabase() {

    abstract fun downloadedSongDao(): DownloadedSongDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun favoriteSongDao(): FavoriteSongDao
    abstract fun playlistDao(): PlaylistDao

    companion object {
        private const val DATABASE_NAME = "clash_of_music.db"

        @Volatile
        private var instance: ClashDatabase? = null

        fun getInstance(context: Context): ClashDatabase {
            return instance ?: synchronized(this) {
                instance ?: buildDatabase(context).also { instance = it }
            }
        }

        private fun buildDatabase(context: Context): ClashDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                ClashDatabase::class.java,
                DATABASE_NAME
            )
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
