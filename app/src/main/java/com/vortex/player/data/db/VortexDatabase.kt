package com.vortex.player.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [MediaStateEntity::class, DownloadEntity::class],
    version = 2,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class VortexDatabase : RoomDatabase() {

    abstract fun mediaStateDao(): MediaStateDao
    abstract fun downloadDao(): DownloadDao

    companion object {
        @Volatile
        private var instance: VortexDatabase? = null

        /**
         * v1 → v2 añade la tabla de descargas. Se migra en vez de recrear porque el
         * historial de reproducción ("continuar viendo") es lo más valioso que guarda
         * la app y perderlo por una función nueva sería inaceptable.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `downloads` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `url` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `uploader` TEXT NOT NULL,
                        `thumbnailUrl` TEXT,
                        `kind` TEXT NOT NULL,
                        `videoQuality` TEXT NOT NULL,
                        `audioCodec` TEXT NOT NULL,
                        `audioBitrate` TEXT NOT NULL,
                        `playlist` INTEGER NOT NULL,
                        `embedThumbnail` INTEGER NOT NULL,
                        `embedSubtitles` INTEGER NOT NULL,
                        `embedMetadata` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `progress` REAL NOT NULL,
                        `etaSeconds` INTEGER NOT NULL,
                        `statusLine` TEXT NOT NULL,
                        `errorMessage` TEXT,
                        `playlistFolder` TEXT,
                        `outputLocation` TEXT,
                        `fileCount` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `finishedAt` INTEGER
                    )
                    """.trimIndent()
                )
            }
        }

        fun get(context: Context): VortexDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    VortexDatabase::class.java,
                    "vortex.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
