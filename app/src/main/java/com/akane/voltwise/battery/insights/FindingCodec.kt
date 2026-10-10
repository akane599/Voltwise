package com.akane.voltwise.battery.insights

import com.akane.voltwise.battery.data.db.InsightFindingEntity
import com.akane.voltwise.battery.data.db.InsightFindingStatus
import com.akane.voltwise.battery.insights.model.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Versioned persistence boundary. Engine enum names are data, including names from a newer build. */
object FindingCodec {
    const val EVIDENCE_VERSION = 2
    const val MAX_BYTES = 16 * 1_024
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(
        finding: Finding,
        firstSeenAt: Long,
        lastSeenAt: Long = firstSeenAt,
        status: InsightFindingStatus = InsightFindingStatus.ACTIVE,
        feedbackMultiplier: Double = 1.0,
    ): InsightFindingEntity {
        var payload = Payload(
            finding.direction?.name,
            finding.evidence.map { EvidenceRow(it.metric.name, it.observed, it.baseline, it.unit.name, it.sessions) },
            finding.series.sortedBy { it.atMs }.map { PointRow(it.atMs, it.value, it.baselineLow, it.baselineHigh) },
            finding.recommendations.map { RecommendationRow(it.action.name, it.reversible, it.requiresPrivilege) },
            finding.attributions.map { AttributionRow(it.kind.name, it.name, it.packageName, it.value, it.unit.name, it.sessions) },
        )
        // Keep the newest series points; binary search avoids repeatedly encoding a long history.
        if (!fits(payload)) payload = trim(payload.series) { payload.copy(series = it) }
        if (!fits(payload)) payload = trim(payload.attributions) { payload.copy(attributions = it) }
        if (!fits(payload)) payload = trim(payload.evidence) { payload.copy(evidence = it) }
        if (!fits(payload)) payload = trim(payload.recommendations) { payload.copy(recommendations = it) }
        val app = finding.subject as? Subject.App
        return InsightFindingEntity(
            finding.key, finding.type.name, app?.uid, app?.packageName, finding.severity.name,
            finding.confidence.name, finding.score, firstSeenAt, lastSeenAt, status, feedbackMultiplier,
            EVIDENCE_VERSION, json.encodeToString(payload),
        )
    }

    fun decode(entity: InsightFindingEntity): Finding? {
        if (entity.evidenceVersion !in 1..EVIDENCE_VERSION || entity.evidenceJson.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return null
        val type = enumName<FindingType>(entity.type) ?: return null
        val severity = enumName<Severity>(entity.severity) ?: return null
        val confidence = enumName<Confidence>(entity.confidence) ?: return null
        val subject = when {
            entity.uid == null && entity.packageName == null -> Subject.Device
            entity.uid != null && entity.packageName != null -> Subject.App(entity.uid, entity.packageName)
            else -> return null
        }
        val payload = try {
            json.decodeFromString<Payload>(entity.evidenceJson)
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        return Finding(
            entity.key, type, severity, confidence, entity.score, subject, enumName<Direction>(payload.direction),
            payload.evidence.mapNotNull {
                val metric = enumName<Metric>(it.metric) ?: return@mapNotNull null
                Evidence(metric, it.observed, it.baseline, metric.unit, it.sessions)
            },
            payload.series.map { SeriesPoint(it.atMs, it.value, it.baselineLow, it.baselineHigh) },
            payload.recommendations.mapNotNull {
                val action = enumName<ActionType>(it.action) ?: return@mapNotNull null
                Recommendation(action, action.reversible, action.requiresPrivilege)
            },
            if (entity.evidenceVersion == 1) emptyList() else payload.attributions.mapNotNull {
                val kind = enumName<AttributionKind>(it.kind) ?: return@mapNotNull null
                val unit = enumName<MetricUnit>(it.unit) ?: return@mapNotNull null
                Attribution(kind, it.name, it.packageName, it.value, unit, it.sessions)
            },
        )
    }

    private fun fits(payload: Payload) = json.encodeToString(payload).toByteArray(Charsets.UTF_8).size <= MAX_BYTES

    private fun <T> trim(rows: List<T>, copy: (List<T>) -> Payload): Payload {
        var low = 0
        var high = rows.size
        while (low < high) {
            val removed = (low + high) / 2
            if (fits(copy(rows.drop(removed)))) high = removed else low = removed + 1
        }
        return copy(rows.drop(low))
    }

    @Serializable private data class Payload(
        val direction: String? = null,
        val evidence: List<EvidenceRow> = emptyList(),
        val series: List<PointRow> = emptyList(),
        val recommendations: List<RecommendationRow> = emptyList(),
        val attributions: List<AttributionRow> = emptyList(),
    )
    @Serializable private data class EvidenceRow(val metric: String, val observed: Double, val baseline: Double?, val unit: String, val sessions: Int)
    @Serializable private data class PointRow(val atMs: Long, val value: Double, val baselineLow: Double?, val baselineHigh: Double?)
    @Serializable private data class RecommendationRow(val action: String, val reversible: Boolean, val requiresPrivilege: Boolean)
    @Serializable private data class AttributionRow(val kind: String, val name: String, val packageName: String?, val value: Double, val unit: String, val sessions: Int)
}
