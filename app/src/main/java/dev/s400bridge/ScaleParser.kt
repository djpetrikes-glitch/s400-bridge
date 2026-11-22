package dev.s400bridge

import kotlinx.serialization.json.*

class ProtocolFailure(message: String): Exception(message)
class AuthFailure(message: String = "Xiaomi session expired. Please reconnect."): Exception(message)
class VerificationRequired(val url: String): Exception("Xiaomi requires verification. Complete it in your browser, then retry sign-in.")

object ScaleParser {
    const val MODEL = "yunmai.scales.ms104"
    /** Diagnostic only: adds the KEY NAMES (never values) of a JSON object, e.g. "data.weight", to [out]. */
    internal fun collectNames(prefix: String, obj: JsonObject, out: MutableSet<String>, depth: Int = 0) {
        obj.forEach { (key, value) ->
            val name = "$prefix.$key"
            if (out.size < MAX_FIELD_NAMES) out += name
            if (value is JsonObject && depth < 2) collectNames(name, value, out, depth + 1)
        }
    }
    private const val MAX_FIELD_NAMES = 200
    /** Diagnostic only: numeric values of the newest record, without identifiers or personal fields. */
    private val SAMPLE_SKIP = setOf("duid", "miid", "uid", "accountId", "did", "idx", "time", "userType", "model", "fromSource", "user")
    internal fun numericSample(prefix: String, obj: JsonObject): Map<String, String> {
        val out = linkedMapOf<String, String>()
        obj.forEach { (key, value) ->
            if (key in SAMPLE_SKIP) return@forEach
            val v = (value as? JsonPrimitive)?.contentOrNull ?: return@forEach
            if (v.toDoubleOrNull() != null) out["$prefix.$key"] = v
        }
        return out
    }
    fun parse(items: JsonArray, region: String, owner: String, fieldNames: MutableSet<String>? = null, sample: LatestSample? = null): Pair<List<Profile>, List<Measurement>> {
        val profiles = linkedMapOf<String, Profile>()
        val measurements = mutableListOf<Measurement>()
        items.forEach { item ->
            val row = item.jsonObject
            if (row.text("model") != MODEL) return@forEach
            if (row.text("uid") != owner) return@forEach
            val source = row.text("fromSource").toIntOrNull() ?: throw ProtocolFailure("Missing Xiaomi source format.")
            if (source !in 1..3) throw ProtocolFailure("Unsupported Xiaomi source format $source. No data was guessed.")
            val d = Json.parseToJsonElement(row.text("data")).jsonObject
            fieldNames?.let { collectNames("data", d, it) }
            val user = d["user"] as? JsonObject
            // Never infer a person's identity from their name or weight.
            val account = row.text("accountId").ifBlank { user?.text("accountId").orEmpty() }
            if (account.isBlank() || account == "null") throw ProtocolFailure("History has no stable profile ID. Synchronization stopped to protect other users.")
            val profile = "$region:$owner:$account"
            val name = user?.text("name")?.ifBlank { null } ?: "Profile $account"
            profiles[profile] = Profile(profile, "$name · $account")
            val time = if (source == 3) d.text("time").toLongOrNull() else row.text("createTime").toLongOrNull()
            if (time == null || time < 1_000_000_000_000L) throw ProtocolFailure("Unsupported measurement timestamp; expected milliseconds.")
            val kg = d.text("weight").toDoubleOrNull()
            if (kg == null || !kg.isFinite() || kg !in 1.0..500.0) throw ProtocolFailure("Invalid or missing cloud weight.")
            val body = if (source == 3) {
                d.text("bodyResData").takeIf { it.isNotBlank() && it != "null" }?.let { Json.parseToJsonElement(it).jsonObject }
            } else d
            if (source == 3) body?.let { fieldNames?.let { names -> collectNames("bodyResData", it, names) } }
            if (sample != null && time >= sample.time) {
                sample.time = time; sample.values.clear()
                sample.values += numericSample("data", d)
                if (source == 3 && body != null) sample.values += numericSample("bodyResData", body)
            }
            val fat = body?.text("bfp")?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 && it <= 100 }
            val id = stableId("$profile:${row.text("did")}:$time")
            measurements += Measurement(id, profile, time, kg, fat)
        }
        return profiles.values.toList() to measurements
    }
}
/** Newest record seen across all cloud pages; diagnostic only. */
class LatestSample { var time = 0L; val values = linkedMapOf<String, String>() }
fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
