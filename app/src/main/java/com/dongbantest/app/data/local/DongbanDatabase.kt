package com.dongbantest.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TripEntity::class, MemoryEntity::class, LocationPointEntity::class, VehicleSignalEntity::class],
    version = 4,
    exportSchema = false,
)
abstract class DongbanDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao

    companion object {
        fun create(context: Context): DongbanDatabase =
            Room.databaseBuilder(context, DongbanDatabase::class.java, "dongbancha.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
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
