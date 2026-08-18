package com.ytmp3

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class ProjectDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "projects.db"
        private const val DATABASE_VERSION = 2

        private const val PROJECTS = "projects"
        private const val REGIONS = "regions"
        private const val SAMPLES = "samples"
        private const val TAG_SEPARATOR = "\u001F"
        private val EXPORT_FORMATS = setOf("WAV", "FLAC")

        @Volatile private var instance: ProjectDb? = null

        fun get(context: Context): ProjectDb = instance ?: synchronized(this) {
            instance ?: ProjectDb(context).also { instance = it }
        }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE $PROJECTS (" +
                "id TEXT PRIMARY KEY, " +
                "source_uri TEXT NOT NULL, " +
                "title TEXT NOT NULL, " +
                "imported_at_ms INTEGER NOT NULL, " +
                "source_fingerprint TEXT, " +
                "waveform_cache TEXT, " +
                "duration_ms INTEGER NOT NULL DEFAULT 0, " +
                "bpm_estimate REAL, " +
                "bpm_override REAL, " +
                "key_estimate TEXT)"
        )
        db.execSQL(
            "CREATE TABLE $REGIONS (" +
                "id TEXT PRIMARY KEY, " +
                "project_id TEXT NOT NULL REFERENCES $PROJECTS(id) ON DELETE CASCADE, " +
                "position INTEGER NOT NULL, " +
                "start_ms INTEGER NOT NULL, " +
                "end_ms INTEGER NOT NULL, " +
                "label TEXT NOT NULL DEFAULT '', " +
                "fade_in_ms INTEGER NOT NULL DEFAULT 0, " +
                "fade_out_ms INTEGER NOT NULL DEFAULT 0, " +
                "normalise INTEGER NOT NULL DEFAULT 0, " +
                "mono INTEGER NOT NULL DEFAULT 0, " +
                "reverse INTEGER NOT NULL DEFAULT 0, " +
                "UNIQUE(project_id, position))"
        )
        db.execSQL(
            "CREATE TABLE $SAMPLES (" +
                "id TEXT PRIMARY KEY, " +
                "project_id TEXT NOT NULL REFERENCES $PROJECTS(id) ON DELETE CASCADE, " +
                "region_id TEXT REFERENCES $REGIONS(id) ON DELETE SET NULL, " +
                "start_ms INTEGER NOT NULL, " +
                "end_ms INTEGER NOT NULL, " +
                "output_uri TEXT NOT NULL, " +
                "duration_ms INTEGER NOT NULL, " +
                "format TEXT NOT NULL CHECK(format IN ('WAV', 'FLAC')), " +
                "label TEXT NOT NULL DEFAULT '', " +
                "tags TEXT NOT NULL DEFAULT '', " +
                "favourite INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE packs (" +
                "id TEXT PRIMARY KEY, " +
                "name TEXT NOT NULL, " +
                "created_at_ms INTEGER NOT NULL, " +
                "tags TEXT NOT NULL DEFAULT '', " +
                "destination_uri TEXT, " +
                "format TEXT NOT NULL CHECK(format IN ('WAV', 'FLAC')), " +
                "sample_rate_hz INTEGER NOT NULL, " +
                "bit_depth INTEGER NOT NULL, " +
                "zip INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE pack_samples (" +
                "pack_id TEXT NOT NULL REFERENCES packs(id) ON DELETE CASCADE, " +
                "sample_id TEXT NOT NULL REFERENCES $SAMPLES(id) ON DELETE CASCADE, " +
                "position INTEGER NOT NULL, " +
                "PRIMARY KEY(pack_id, sample_id), " +
                "UNIQUE(pack_id, position))"
        )
        db.execSQL("CREATE INDEX regions_project_position_idx ON $REGIONS(project_id, position)")
        db.execSQL("CREATE INDEX samples_project_idx ON $SAMPLES(project_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS packs (" +
                    "id TEXT PRIMARY KEY, name TEXT NOT NULL, created_at_ms INTEGER NOT NULL, " +
                    "tags TEXT NOT NULL DEFAULT '', destination_uri TEXT, " +
                    "format TEXT NOT NULL CHECK(format IN ('WAV', 'FLAC')), " +
                    "sample_rate_hz INTEGER NOT NULL, bit_depth INTEGER NOT NULL, zip INTEGER NOT NULL DEFAULT 0)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS pack_samples (" +
                    "pack_id TEXT NOT NULL REFERENCES packs(id) ON DELETE CASCADE, " +
                    "sample_id TEXT NOT NULL REFERENCES $SAMPLES(id) ON DELETE CASCADE, position INTEGER NOT NULL, " +
                    "PRIMARY KEY(pack_id, sample_id), UNIQUE(pack_id, position))"
            )
        }
    }

    fun upsertProject(project: SampleProject) {
        require(SampleRegion.validateOrdered(project.regions)) { "Project regions must be ordered and non-overlapping" }
        writableDatabase.transaction {
            insertProject(this, project)
            replaceRegions(this, project.id, project.regions)
        }
    }

    fun saveRegions(projectId: String, regions: List<SampleRegion>) {
        require(SampleRegion.validateOrdered(regions)) { "Regions must be ordered and non-overlapping" }
        writableDatabase.transaction {
            replaceRegions(this, projectId, regions)
        }
    }

    fun getProject(id: String): SampleProject? = readableDatabase.rawQuery(
        "SELECT id, source_uri, title, imported_at_ms, source_fingerprint, waveform_cache, duration_ms, " +
            "bpm_estimate, bpm_override, key_estimate FROM $PROJECTS WHERE id = ?",
        arrayOf(id)
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else cursor.toProject(regionsFor(id))
    }

    fun listProjects(): List<SampleProject> = readableDatabase.rawQuery(
        "SELECT id, source_uri, title, imported_at_ms, source_fingerprint, waveform_cache, duration_ms, " +
            "bpm_estimate, bpm_override, key_estimate FROM $PROJECTS ORDER BY imported_at_ms DESC, id ASC",
        null
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(cursor.toProject(regionsFor(cursor.getString(0))))
        }
    }

    fun upsertSample(sample: SampleRecord) {
        require(sample.format in EXPORT_FORMATS) { "Samples must be WAV or FLAC" }
        val values = sample.toContentValues()
        if (writableDatabase.update(SAMPLES, values, "id = ?", arrayOf(sample.id)) == 0) {
            writableDatabase.insertOrThrow(SAMPLES, null, values)
        }
    }

    fun listSamples(): List<SampleRecord> = readableDatabase.rawQuery(
        "SELECT id, project_id, region_id, start_ms, end_ms, output_uri, duration_ms, format, label, tags, favourite " +
            "FROM $SAMPLES ORDER BY id ASC",
        null
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(cursor.toSample())
        }
    }

    fun upsertPack(pack: SamplePack) {
        require(pack.format in EXPORT_FORMATS) { "Packs must be WAV or FLAC" }
        require(pack.sampleIds.distinct().size == pack.sampleIds.size) { "A pack cannot contain a sample twice" }
        writableDatabase.transaction {
            val values = ContentValues().apply {
                put("id", pack.id)
                put("name", pack.name)
                put("created_at_ms", pack.createdAtMs)
                put("tags", pack.tags.joinToString(TAG_SEPARATOR))
                put("destination_uri", pack.destinationUri)
                put("format", pack.format)
                put("sample_rate_hz", pack.sampleRateHz)
                put("bit_depth", pack.bitDepth)
                put("zip", pack.zip.asDatabaseInt())
            }
            if (update("packs", values, "id = ?", arrayOf(pack.id)) == 0) {
                insertOrThrow("packs", null, values)
            }
            delete("pack_samples", "pack_id = ?", arrayOf(pack.id))
            pack.sampleIds.forEachIndexed { position, sampleId ->
                insertOrThrow("pack_samples", null, ContentValues().apply {
                    put("pack_id", pack.id)
                    put("sample_id", sampleId)
                    put("position", position)
                })
            }
        }
    }

    fun getPack(id: String): SamplePack? = readableDatabase.rawQuery(
        "SELECT id, name, created_at_ms, tags, destination_uri, format, sample_rate_hz, bit_depth, zip FROM packs WHERE id = ?",
        arrayOf(id)
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toPack(packSampleIds(id)) else null }

    fun listPacks(): List<SamplePack> = readableDatabase.rawQuery(
        "SELECT id, name, created_at_ms, tags, destination_uri, format, sample_rate_hz, bit_depth, zip FROM packs ORDER BY created_at_ms DESC, id ASC",
        null
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toPack(packSampleIds(cursor.getString(0)))) } }

    private fun packSampleIds(packId: String): List<String> = readableDatabase.rawQuery(
        "SELECT sample_id FROM pack_samples WHERE pack_id = ? ORDER BY position ASC", arrayOf(packId)
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    private fun Cursor.toPack(sampleIds: List<String>) = SamplePack(
        id = getString(0),
        name = getString(1),
        createdAtMs = getLong(2),
        tags = getString(3).splitTags(),
        destinationUri = getStringOrNull(4),
        format = getString(5),
        sampleRateHz = getInt(6),
        bitDepth = getInt(7),
        zip = getInt(8).asBoolean(),
        sampleIds = sampleIds
    )

    private fun insertProject(db: SQLiteDatabase, project: SampleProject) {
        val values = project.toContentValues()
        if (db.update(PROJECTS, values, "id = ?", arrayOf(project.id)) == 0) {
            db.insertOrThrow(PROJECTS, null, values)
        }
    }

    private fun replaceRegions(db: SQLiteDatabase, projectId: String, regions: List<SampleRegion>) {
        db.delete(REGIONS, "project_id = ?", arrayOf(projectId))
        regions.forEachIndexed { position, region ->
            val recipe = region.recipe.validated(region.endMs - region.startMs)
            db.insertOrThrow(
                REGIONS,
                null,
                ContentValues().apply {
                    put("id", region.id)
                    put("project_id", projectId)
                    put("position", position)
                    put("start_ms", region.startMs)
                    put("end_ms", region.endMs)
                    put("label", region.label)
                    put("fade_in_ms", recipe.fadeInMs)
                    put("fade_out_ms", recipe.fadeOutMs)
                    put("normalise", recipe.normalise.asDatabaseInt())
                    put("mono", recipe.mono.asDatabaseInt())
                    put("reverse", recipe.reverse.asDatabaseInt())
                }
            )
        }
    }

    private fun regionsFor(projectId: String): List<SampleRegion> = readableDatabase.rawQuery(
        "SELECT id, start_ms, end_ms, label, fade_in_ms, fade_out_ms, normalise, mono, reverse " +
            "FROM $REGIONS WHERE project_id = ? ORDER BY position ASC",
        arrayOf(projectId)
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    SampleRegion(
                        id = cursor.getString(0),
                        startMs = cursor.getLong(1),
                        endMs = cursor.getLong(2),
                        label = cursor.getString(3),
                        recipe = ProcessingRecipe(
                            fadeInMs = cursor.getLong(4),
                            fadeOutMs = cursor.getLong(5),
                            normalise = cursor.getInt(6).asBoolean(),
                            mono = cursor.getInt(7).asBoolean(),
                            reverse = cursor.getInt(8).asBoolean()
                        )
                    )
                )
            }
        }
    }

    private fun Cursor.toProject(regions: List<SampleRegion>) = SampleProject(
        id = getString(0),
        sourceUri = getString(1),
        title = getString(2),
        importedAtMs = getLong(3),
        sourceFingerprint = getStringOrNull(4),
        waveformCache = getStringOrNull(5),
        durationMs = getLong(6),
        bpmEstimate = getFloatOrNull(7),
        bpmOverride = getFloatOrNull(8),
        keyEstimate = getStringOrNull(9),
        regions = regions
    )

    private fun Cursor.toSample() = SampleRecord(
        id = getString(0),
        projectId = getString(1),
        regionId = getStringOrNull(2),
        startMs = getLong(3),
        endMs = getLong(4),
        outputUri = getString(5),
        durationMs = getLong(6),
        format = getString(7),
        label = getString(8),
        tags = getString(9).splitTags(),
        favourite = getInt(10).asBoolean()
    )

    private fun SampleProject.toContentValues() = ContentValues().apply {
        put("id", id)
        put("source_uri", sourceUri)
        put("title", title)
        put("imported_at_ms", importedAtMs)
        put("source_fingerprint", sourceFingerprint)
        put("waveform_cache", waveformCache)
        put("duration_ms", durationMs)
        put("bpm_estimate", bpmEstimate)
        put("bpm_override", bpmOverride)
        put("key_estimate", keyEstimate)
    }

    private fun SampleRecord.toContentValues() = ContentValues().apply {
        put("id", id)
        put("project_id", projectId)
        put("region_id", regionId)
        put("start_ms", startMs)
        put("end_ms", endMs)
        put("output_uri", outputUri)
        put("duration_ms", durationMs)
        put("format", format)
        put("label", label)
        put("tags", tags.joinToString(TAG_SEPARATOR))
        put("favourite", favourite.asDatabaseInt())
    }

    private fun Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

    private fun Cursor.getFloatOrNull(index: Int): Float? = if (isNull(index)) null else getFloat(index)

    private fun SQLiteDatabase.transaction(block: SQLiteDatabase.() -> Unit) {
        beginTransaction()
        try {
            block()
            setTransactionSuccessful()
        } finally {
            endTransaction()
        }
    }

    private fun Boolean.asDatabaseInt() = if (this) 1 else 0

    private fun Int.asBoolean() = this != 0

    private fun String.splitTags(): List<String> = if (isEmpty()) emptyList() else split(TAG_SEPARATOR)

}
