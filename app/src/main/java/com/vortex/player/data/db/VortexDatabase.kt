package com.vortex.player.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        MediaStateEntity::class,
        DownloadEntity::class,
        PlaylistEntity::class,
        PlaylistItemEntity::class
    ],
    version = 8,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class VortexDatabase : RoomDatabase() {

    abstract fun mediaStateDao(): MediaStateDao
    abstract fun downloadDao(): DownloadDao
    abstract fun playlistDao(): PlaylistDao

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

        /** v2 → v3 añade listas de reproducción propias. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `playlists` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `playlist_items` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `playlistId` INTEGER NOT NULL,
                        `uri` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `position` INTEGER NOT NULL,
                        FOREIGN KEY(`playlistId`) REFERENCES `playlists`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_playlist_items_playlistId` " +
                        "ON `playlist_items` (`playlistId`)"
                )
            }
        }

        /** v3 → v4 añade lo que necesitan las descargas originadas en Spotify. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `downloads` ADD COLUMN `searchQuery` TEXT")
                db.execSQL(
                    "ALTER TABLE `downloads` ADD COLUMN `targetDurationMs` INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL("ALTER TABLE `downloads` ADD COLUMN `tagsJson` TEXT")
            }
        }

        /** v4 → v5 guarda la política de SponsorBlock con la que se lanzó cada descarga. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `downloads` ADD COLUMN `sponsorMode` TEXT NOT NULL DEFAULT 'OFF'"
                )
                db.execSQL(
                    "ALTER TABLE `downloads` ADD COLUMN `sponsorCategories` TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        /** v5 → v6 guarda el identificador de la pista para reconocer lo ya descargado. */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `downloads` ADD COLUMN `sourceId` TEXT")
            }
        }

        /** v6 → v7 guarda el avance dentro de una lista, para poder enseñarlo en la cola. */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `downloads` ADD COLUMN `playlistCount` INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE `downloads` ADD COLUMN `playlistIndex` INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE `downloads` ADD COLUMN `playlistItems` TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        /** v7 -> v8 guarda el contenedor de vídeo elegido para cada descarga. */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `downloads` ADD COLUMN `videoContainer` TEXT NOT NULL " +
                        "DEFAULT 'MP4'"
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
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
                        MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8
                    )
                    .build()
                    .also { instance = it }
            }
    }
}
