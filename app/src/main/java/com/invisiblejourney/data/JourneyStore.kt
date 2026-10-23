package com.invisiblejourney.data

import android.content.Context
import android.net.Uri
import com.invisiblejourney.domain.SampleJourneys
import com.invisiblejourney.domain.model.JourneyRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/** Lightweight listing entry so the library doesn't have to hold every full record in memory. */
data class JourneyListItem(
    val id: String,
    val title: String,
    val startedAt: Long,
    val endedAt: Long?,
    val isSample: Boolean,
    val actualMin: Float?,
    val promisedEtaMin: Int,
)

/**
 * One JSON file per journey in `files/journeys/`. The file format *is* the export format
 * (docs/06_DATA_MODEL.md), so exporting is just copying the file.
 */
class JourneyStore(private val context: Context) {

    private val dir = File(context.filesDir, "journeys").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _items = MutableStateFlow<List<JourneyListItem>>(emptyList())
    val items: StateFlow<List<JourneyListItem>> = _items.asStateFlow()

    private val samples = mutableMapOf<String, JourneyRecord>()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val stored = dir.listFiles { f -> f.name.endsWith(SUFFIX) }.orEmpty().mapNotNull { f ->
            runCatching { json.decodeFromString<JourneyRecord>(f.readText()) }.getOrNull()?.toItem()
        }.sortedByDescending { it.startedAt }
        _items.value = stored + sampleRecords().map { it.toItem() }
    }

    suspend fun load(id: String): JourneyRecord? = withContext(Dispatchers.IO) {
        sampleRecords().firstOrNull { it.id == id }
            ?: File(dir, id + SUFFIX).takeIf { it.exists() }?.let { json.decodeFromString<JourneyRecord>(it.readText()) }
    }

    /** Atomic-ish write: temp file then rename, so a crash never leaves half a journey. */
    suspend fun save(record: JourneyRecord) = withContext(Dispatchers.IO) {
        val tmp = File(dir, record.id + SUFFIX + ".tmp")
        tmp.writeText(json.encodeToString(JourneyRecord.serializer(), record))
        val target = File(dir, record.id + SUFFIX)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw IOException("Could not save journey ${record.id}")
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(dir, id + SUFFIX).delete()
        refresh()
    }

    suspend fun exportTo(id: String, uri: Uri) = withContext(Dispatchers.IO) {
        val record = load(id) ?: throw IOException("Journey not found")
        context.contentResolver.openOutputStream(uri)?.use {
            it.write(json.encodeToString(JourneyRecord.serializer(), record).toByteArray())
        } ?: throw IOException("Cannot write to file")
    }

    /** Imports a `.journey.json` someone shared. Returns the imported journey id. */
    suspend fun importFrom(uri: Uri): String = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            ?: throw IOException("Cannot read file")
        val record = json.decodeFromString<JourneyRecord>(text)
        if (record.format != JourneyRecord.FORMAT) throw IOException("Not an Invisible Journey file")
        save(record.copy(isSample = false))
        refresh()
        record.id
    }

    private fun sampleRecords(): List<JourneyRecord> = synchronized(samples) {
        SampleJourneys.Scenario.entries.map { s ->
            samples.getOrPut(s.name) { SampleJourneys.generate(s) }
        }
    }

    private fun JourneyRecord.toItem() = JourneyListItem(
        id = id,
        title = title,
        startedAt = startedAt,
        endedAt = endedAt,
        isSample = isSample,
        actualMin = summary?.actualMin,
        promisedEtaMin = promisedEtaMin,
    )

    companion object {
        const val SUFFIX = ".journey.json"
    }
}
