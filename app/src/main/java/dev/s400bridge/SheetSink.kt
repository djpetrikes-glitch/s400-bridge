package dev.s400bridge

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Sends every measurement, with all its scale fields, to the nutrition Google Sheet. Health Connect is untouched. */
object SheetSink {
    private const val BATCH = 40

    /** Returns a short status for the screen, or null when there was nothing to do. Never includes the key. */
    suspend fun sendPending(store: Store): String? = withContext(Dispatchers.IO) {
        val state = store.read()
        val url = state.settings.sheetUrl.trim(); val key = state.settings.sheetKey.trim()
        if (url.isEmpty() && key.isEmpty()) return@withContext null
        if (!url.startsWith("https://") || key.isEmpty()) return@withContext "Sheet: enter the web app URL and the scale key in Settings."
        // Only the profile selected in Settings: other people weighing on the same scale stay out of the sheet.
        val pending = state.measurements.filter { !it.sheetSent && it.profile == state.settings.profile }
        if (pending.isEmpty()) return@withContext null
        val zone = ZoneId.systemDefault()
        var sent = 0
        try {
            for (batch in pending.chunked(BATCH)) {
                val body = buildJsonObject {
                    put("accion", "bascula"); put("clave", key)
                    putJsonArray("registros") { batch.forEach { add(record(it, zone)) } }
                }.toString()
                if (!post(url, body)) return@withContext "Sheet: rejected or unreachable after $sent records. Pending records will be retried."
                val ids = batch.map { it.id }.toSet()
                store.update { st -> st.copy(measurements = st.measurements.map { if (it.id in ids) it.copy(sheetSent = true) else it }) }
                sent += batch.size
            }
            "Sheet: $sent records sent."
        } catch (_: IOException) {
            "Sheet: network error after $sent records. Pending records will be retried."
        }
    }

    private fun record(m: Measurement, zone: ZoneId): JsonObject {
        val at = Instant.ofEpochMilli(m.time).atZone(zone)
        return buildJsonObject {
            put("id", m.id)
            put("ts", Instant.ofEpochMilli(m.time).toString())
            put("fecha", at.toLocalDate().toString())
            put("hora", "%02d:%02d".format(at.hour, at.minute))
            putJsonObject("campos") {
                put("data.weight", m.kg)
                m.fat?.let { put("data.bfp", it) }
                // Zero means "not measured" for this field; it is not sent.
                m.fields.forEach { (name, value) ->
                    val n = value.toDoubleOrNull()
                    if (n != null && n.isFinite() && n != 0.0) put(name, n)
                }
            }
        }
    }

    private fun post(url: String, body: String): Boolean {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 20_000; readTimeout = 60_000
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (c.responseCode != 200) return false
            val text = c.inputStream.bufferedReader().use { it.readText() }
            return runCatching { Json.parseToJsonElement(text).jsonObject["error"] == null }.getOrDefault(false)
        } finally { c.disconnect() }
    }
}
