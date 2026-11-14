package com.dongbantest.app.data.local

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Like TalkMigrationTest: an actual old SQLite fixture, opened/validated by current Room. */
@RunWith(AndroidJUnit4::class)
class TrackPlayMigrationTest {
    @Test fun migrate6To7PreservesAllTablesAndAddsIndexedCascadingTrackPlays() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(DB_NAME)
        val old = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(DB_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(6) {
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
                        DongbanDatabase.MIGRATION_4_5.migrate(db)
                        DongbanDatabase.MIGRATION_5_6.migrate(db)
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("fixture only")
                }).build(),
        )
        val before = try {
            val db = old.writableDatabase
            db.execSQL("INSERT INTO trips VALUES (1,'trip','destination','friend',100,NULL,NULL,NULL,NULL,0,NULL,NULL)")
            db.execSQL("INSERT INTO memories VALUES (2,1,'VOICE',200,NULL,'/old.m4a',NULL,1234)")
            db.execSQL("INSERT INTO location_points VALUES (3,1,220,37.0,127.0,3.0)")
            db.execSQL("INSERT INTO vehicle_signals VALUES (4,1,230,'PARKED')")
            db.execSQL("""INSERT INTO talk_meta (memoryId,source,blockStartMs,blockEndMs,title,summaryStatus,runId,storyId,storyKeep)
                VALUES (2,'AUTO',100,200,'existing title','DONE','run-1','story-1',1)""")
            snapshot(db)
        } finally { old.close() }

        val room = Room.databaseBuilder(context, DongbanDatabase::class.java, DB_NAME)
            .addMigrations(DongbanDatabase.MIGRATION_6_7).build()
        try {
            val db = room.openHelper.writableDatabase
            assertEquals(7, db.version)
            assertEquals(before, snapshot(db))
            db.query("SELECT COUNT(*) FROM track_plays").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
            db.query("PRAGMA index_list('track_plays')").use { cursor ->
                assertTrue(generateSequence { if (cursor.moveToNext()) cursor.getString(1) else null }
                    .any { it == "index_track_plays_tripId" })
            }
            db.query("PRAGMA foreign_key_list('track_plays')").use {
                assertTrue(it.moveToFirst())
                assertEquals("trips", it.getString(2)); assertEquals("tripId", it.getString(3))
                assertEquals("id", it.getString(4)); assertEquals("CASCADE", it.getString(6))
            }
            val dao = room.trackPlayDao()
            val first = dao.insert(TrackPlayEntity(tripId = 1, startedAt = 300, endedAt = 15_300,
                playedMs = 15_000, title = "A", artist = null, album = null, durationMs = null,
                sourcePackage = "test.player", sourceLabel = null))
            assertTrue(first > 0)
            val row = dao.observe(1).first().single()
            assertEquals(first, row.id); assertNull(row.artist); assertNull(row.album)
            assertNull(row.durationMs); assertNull(row.sourceLabel)
            dao.update(row.copy(endedAt = 20_300, playedMs = 20_000))
            assertEquals(20_000L, dao.observe(1).first().single().playedMs)
            val second = dao.insert(row.copy(id = 0, title = "B"))
            assertTrue(second > first)
            dao.delete(second)
            assertEquals(1, dao.observe(1).first().size)
            // Direct trip deletion must also remove the new child rows.
            db.execSQL("DELETE FROM trips WHERE id=1")
            assertTrue(dao.observe(1).first().isEmpty())
            db.query("SELECT COUNT(*) FROM talk_meta").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
            db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally {
            room.close()
            context.deleteDatabase(DB_NAME)
        }
    }

    private fun snapshot(db: SupportSQLiteDatabase): List<List<String?>> {
        val tables = listOf("trips", "memories", "location_points", "vehicle_signals", "talk_meta")
        val queries = listOf("SELECT name,sql FROM sqlite_master WHERE tbl_name IN ('trips','memories','location_points','vehicle_signals','talk_meta') ORDER BY name") +
            tables.map { "SELECT * FROM `$it` ORDER BY 1" }
        return queries.flatMap { sql -> db.query(sql).use { cursor ->
            buildList { while (cursor.moveToNext()) add(List(cursor.columnCount) { if (cursor.isNull(it)) null else cursor.getString(it) }) }
        } }
    }

    companion object { private const val DB_NAME = "track-play-migration-test.db" }
}
