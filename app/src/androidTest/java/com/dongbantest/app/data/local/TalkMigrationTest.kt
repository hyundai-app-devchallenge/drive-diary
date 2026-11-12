package com.dongbantest.app.data.local

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v4 → v5 마이그레이션 시험.
 * room-testing(MigrationTestHelper)은 앱의 kotlinx-serialization 1.7.3(Mapbox 경유)과 충돌해 쓰지 않는다.
 * 대신 v4 테이블을 직접 만든 뒤 실제 앱처럼 Room 으로 연다. Room 이 열 때 마이그레이션을 돌리고
 * 엔티티 정의와 실제 테이블이 같은지 검사하므로, 다르면 여기서 IllegalStateException 이 난다.
 */
@RunWith(AndroidJUnit4::class)
class TalkMigrationTest {
    @Test fun migrate4To5PreservesTeamTablesAndAddsNullableCascadingMetadata() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(DB_NAME)
        // v4 disabled schema export, so build the actual v4 fixture by hand.
        val old = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(DB_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("""CREATE TABLE IF NOT EXISTS `trips` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL,
                            `destination` TEXT NOT NULL, `companions` TEXT NOT NULL, `startedAt` INTEGER NOT NULL,
                            `endedAt` INTEGER, `startOdometer` REAL, `endOdometer` REAL, `odometerSource` TEXT,
                            `isSample` INTEGER NOT NULL, `startPlace` TEXT, `endPlace` TEXT)""")
                        db.execSQL("""CREATE TABLE IF NOT EXISTS `memories` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `tripId` INTEGER NOT NULL,
                            `type` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `text` TEXT, `filePath` TEXT,
                            `transcript` TEXT, `durationMs` INTEGER,
                            FOREIGN KEY(`tripId`) REFERENCES `trips`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_tripId` ON `memories` (`tripId`)")
                        db.execSQL("""CREATE TABLE IF NOT EXISTS `location_points` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `tripId` INTEGER NOT NULL,
                            `recordedAt` INTEGER NOT NULL, `latitude` REAL NOT NULL, `longitude` REAL NOT NULL,
                            `accuracyMeters` REAL,
                            FOREIGN KEY(`tripId`) REFERENCES `trips`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_location_points_tripId` ON `location_points` (`tripId`)")
                        db.execSQL("""CREATE TABLE IF NOT EXISTS `vehicle_signals` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `tripId` INTEGER NOT NULL,
                            `recordedAt` INTEGER NOT NULL, `type` TEXT NOT NULL,
                            FOREIGN KEY(`tripId`) REFERENCES `trips`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_vehicle_signals_tripId` ON `vehicle_signals` (`tripId`)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("fixture only")
                }).build(),
        )
        val before = old.writableDatabase.let { db ->
            db.execSQL("INSERT INTO trips VALUES (1,'test trip','test destination','test person',100,NULL,1.5,NULL,'MOCK',0,'start',NULL)")
            db.execSQL("INSERT INTO memories VALUES (2,1,'VOICE',200,NULL,'/test/old.m4a',NULL,1234)")
            db.execSQL("INSERT INTO memories VALUES (3,1,'TEXT',210,'synthetic memo',NULL,NULL,NULL)")
            db.execSQL("INSERT INTO location_points VALUES (4,1,220,37.0,127.0,3.0)")
            db.execSQL("INSERT INTO vehicle_signals VALUES (5,1,230,'PARKED')")
            snapshot(db)
        }
        old.close()
        val room = Room.databaseBuilder(context, DongbanDatabase::class.java, DB_NAME)
            .addMigrations(DongbanDatabase.MIGRATION_4_5).build()
        try {
            val db = room.openHelper.writableDatabase
            assertEquals(5, db.version)
            assertEquals(before, snapshot(db))
            db.execSQL("PRAGMA foreign_keys=ON")
            db.execSQL("""INSERT INTO talk_meta (memoryId,source,blockStartMs,blockEndMs,keepScore,kind,title,tags,summaryStatus,summaryModel)
                VALUES (2,'AUTO',100,200,NULL,NULL,NULL,NULL,'SKIPPED',NULL)""")
            db.query("SELECT keepScore,kind FROM talk_meta WHERE memoryId=2").use {
                assertTrue(it.moveToFirst()); assertTrue(it.isNull(0)); assertTrue(it.isNull(1))
            }
            db.execSQL("DELETE FROM memories WHERE id=2")
            db.query("SELECT COUNT(*) FROM talk_meta").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
            db.execSQL("""INSERT INTO talk_meta (memoryId,source,blockStartMs,blockEndMs,summaryStatus)
                VALUES (3,'MANUAL',100,200,'PENDING')""")
            db.execSQL("DELETE FROM trips WHERE id=1")
            db.query("SELECT COUNT(*) FROM talk_meta").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        } finally {
            room.close()
            context.deleteDatabase(DB_NAME)
        }
    }

    private fun snapshot(db: SupportSQLiteDatabase): List<List<String?>> {
        val queries = listOf(
            "SELECT name,sql FROM sqlite_master WHERE tbl_name IN ('trips','memories','location_points','vehicle_signals') ORDER BY name",
            "SELECT * FROM trips ORDER BY id", "SELECT * FROM memories ORDER BY id",
            "SELECT * FROM location_points ORDER BY id", "SELECT * FROM vehicle_signals ORDER BY id",
        )
        return queries.flatMap { sql -> db.query(sql).use { cursor ->
            buildList { while (cursor.moveToNext()) add(List(cursor.columnCount) { if (cursor.isNull(it)) null else cursor.getString(it) }) }
        } }
    }

    companion object { private const val DB_NAME = "talk-migration-test.db" }
}
