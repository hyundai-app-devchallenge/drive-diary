package com.dongbantest.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TripEntity::class, MemoryEntity::class, LocationPointEntity::class, VehicleSignalEntity::class, TalkMetaEntity::class, TrackPlayEntity::class],
    version = 7,
    exportSchema = true,
)
abstract class DongbanDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao
    abstract fun trackPlayDao(): TrackPlayDao

    companion object {
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `track_plays` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `tripId` INTEGER NOT NULL, `startedAt` INTEGER NOT NULL,
                        `endedAt` INTEGER NOT NULL, `playedMs` INTEGER NOT NULL,
                        `title` TEXT NOT NULL, `artist` TEXT, `album` TEXT, `durationMs` INTEGER,
                        `sourcePackage` TEXT NOT NULL, `sourceLabel` TEXT,
                        FOREIGN KEY(`tripId`) REFERENCES `trips`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_track_plays_tripId` ON `track_plays` (`tripId`)")
            }
        }
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf("runId" to "TEXT", "closeReason" to "TEXT", "runClosed" to "INTEGER",
                    "storyId" to "TEXT", "storyIndex" to "INTEGER", "storyKind" to "TEXT",
                    "storyKeep" to "INTEGER", "storyReason" to "TEXT").forEach { (name, type) ->
                    db.execSQL("ALTER TABLE `talk_meta` ADD COLUMN `$name` $type")
                }
            }
        }
        /** v5 only adds metadata; the four team tables are untouched. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `talk_meta` (
                        `memoryId` INTEGER NOT NULL, `source` TEXT NOT NULL,
                        `blockStartMs` INTEGER NOT NULL, `blockEndMs` INTEGER NOT NULL,
                        `keepScore` REAL, `kind` TEXT, `title` TEXT, `tags` TEXT,
                        `summaryStatus` TEXT NOT NULL, `summaryModel` TEXT,
                        PRIMARY KEY(`memoryId`),
                        FOREIGN KEY(`memoryId`) REFERENCES `memories`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_talk_meta_summaryStatus` ON `talk_meta` (`summaryStatus`)")
            }
        }

        fun create(context: Context): DongbanDatabase =
            Room.databaseBuilder(context, DongbanDatabase::class.java, "dongbancha.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                .build()

        /** v2: 위치 기록 테이블 추가. 기존 여행·추억 데이터는 그대로 유지한다. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `location_points` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`tripId` INTEGER NOT NULL, " +
                        "`recordedAt` INTEGER NOT NULL, " +
                        "`latitude` REAL NOT NULL, " +
                        "`longitude` REAL NOT NULL, " +
                        "`accuracyMeters` REAL, " +
                        "FOREIGN KEY(`tripId`) REFERENCES `trips`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_location_points_tripId` ON `location_points` (`tripId`)")
            }
        }

        /** v3: 차량 신호 기록 테이블 추가. 기존 데이터는 그대로 유지한다. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `vehicle_signals` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`tripId` INTEGER NOT NULL, " +
                        "`recordedAt` INTEGER NOT NULL, " +
                        "`type` TEXT NOT NULL, " +
                        "FOREIGN KEY(`tripId`) REFERENCES `trips`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_vehicle_signals_tripId` ON `vehicle_signals` (`tripId`)")
            }
        }

        /** v4: 출발지·도착지 이름 칸 추가. 기존 여행은 빈 값(null)으로 남는다. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `trips` ADD COLUMN `startPlace` TEXT")
                db.execSQL("ALTER TABLE `trips` ADD COLUMN `endPlace` TEXT")
            }
        }
    }
}
