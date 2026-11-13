# Room schemas

`app/build.gradle.kts` enables `room.schemaLocation` here and packages this directory as
androidTest assets. The next KSP build exports
`com.dongbantest.app.data.local.DongbanDatabase/5.json`; keep that generated JSON in version control.

Version 4 used `exportSchema = false`, so no historical `4.json` exists. `TalkMigrationTest`
creates a v4 SQLite fixture with the original four tables, then uses MigrationTestHelper to
apply the real 4-to-5 migration and validate against the generated v5 schema. It also compares
the original table/index definitions and rows before/after migration.
