package com.akane.voltwise.battery.util

import com.akane.voltwise.battery.util.BatteryStatsParser.AlarmStats
import com.akane.voltwise.battery.util.BatteryStatsParser.AppPowerStats
import com.akane.voltwise.battery.util.BatteryStatsParser.BluetoothStats
import com.akane.voltwise.battery.util.BatteryStatsParser.CpuFrequencyStats
import com.akane.voltwise.battery.util.BatteryStatsParser.DozeStats
import com.akane.voltwise.battery.util.BatteryStatsParser.FullSnapshot
import com.akane.voltwise.battery.util.BatteryStatsParser.JobStats
import com.akane.voltwise.battery.util.BatteryStatsParser.KernelWakelockStats
import com.akane.voltwise.battery.util.BatteryStatsParser.NetworkStats
import com.akane.voltwise.battery.util.BatteryStatsParser.ProcessStats
import com.akane.voltwise.battery.util.BatteryStatsParser.SensorStats
import com.akane.voltwise.battery.util.BatteryStatsParser.SignalStrengthStats
import com.akane.voltwise.battery.util.BatteryStatsParser.SyncStats
import com.akane.voltwise.battery.util.BatteryStatsParser.WakelockStats
import com.akane.voltwise.battery.util.BatteryStatsParser.WakelockType
import com.akane.voltwise.battery.util.BatteryStatsParser.WakeupReasonStats
import com.akane.voltwise.battery.util.BatteryStatsParser.WifiSignalStats
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * AOSP API 28+ current charged aggregate, android/service/batterystats.proto ->
 * android/os/batterystats.proto. Field numbers/types are unchanged through Android 16.
 * Names remain opaque UTF-8 payload: no CSV, line framing, or shell text is interpreted here.
 * Invalid framing or typed record shapes can make the whole dump unavailable. Invalid numeric metrics
 * in app or consumed non-waker records prevent app session certification. Rejected numeric device-waker
 * metrics mark waker completeness separately, without invalidating valid app measurements.
 * Normal producer zero omissions are valid.
 */
object BatteryStatsProtoParser {
    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val MAX_FIELDS = 200_000
    private const val MAX_DEPTH = 16

    fun parse(bytes: ByteArray): FullSnapshot? {
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
        return try {
            val root = Decoder(bytes).read(0, bytes.size, 0)
            root.validate(Schema.ROOT)
            val stats = root.message(1) ?: throw InvalidWire()
            metric(stats.nonnegative(1) > 0)
            // Optional metadata is still typed/singleton-checked when present.
            stats.nonnegative(2)
            stats.text(3)
            stats.text(4)
            val system = stats.message(6) ?: throw InvalidWire()
            val battery = system.message(1) ?: throw InvalidWire()
            val startedAt = battery.nonnegative(1).also { metric(it > 0) }
            val startCount = battery.nonnegative(2)
            val realtime = battery.nonnegative(5)
            val uptime = battery.nonnegative(6).also { metric(it <= realtime) }
            val evidence = Evidence()
            evidence.tags += "bt"
            evidence.readSystem(system)
            val uids = mutableSetOf<Int>()
            stats.messages(5).forEach { uid ->
                val id = uid.integer(1)
                if (id < 0 || !uids.add(id)) throw InvalidWire()
                evidence.readUid(uid, id)
            }
            evidence.snapshot().copy(
                startedAt = startedAt,
                startCount = startCount,
                batteryRealtimeMs = realtime,
                batteryUptimeMs = uptime,
                screenOffTimeMs = battery.nonnegative(7),
                screenDozeTimeMs = battery.nonnegative(9),
                // AOSP writes -1 until learned capacity is known; omitted/zero also means unknown.
                learnedMinCapacityUah = battery.number(11).also { metric(it >= -1) }.takeIf { it > 0 },
                learnedMaxCapacityUah = battery.number(12).also { metric(it >= -1) }.takeIf { it > 0 },
            )
        } catch (_: InvalidWire) {
            null
        } catch (_: InvalidMetric) {
            null
        }
    }

    private class InvalidWire : RuntimeException()
    private class InvalidMetric : RuntimeException()

    private fun metric(valid: Boolean) {
        if (!valid) throw InvalidMetric()
    }

    private fun sum(a: Long, b: Long): Long {
        metric(a >= 0 && b >= 0 && a <= Long.MAX_VALUE - b)
        return a + b
    }

    private data class Field(val wire: Int, val value: Long = 0, val start: Int = 0, val end: Int = 0)

    private enum class Schema {
        ROOT, STATS, SYSTEM, BATTERY, UID, PACKAGE, CPU, FREQUENCY, TIMER,
        NETWORK, POWER, SYSTEM_POWER, STATE, SENSOR, JOB, WAKELOCK, ALARM, PROCESS,
        AGGREGATE, BLE, NAMED_TIMER, SIGNAL, MISC, DISCHARGE, SUMMARY, CONTROLLER, TX,
    }

    /** Slices reference the bounded input. Unknown length-delimited values are never recursively decoded. */
    private class Decoder(val bytes: ByteArray) {
        private var remainingFields = MAX_FIELDS

        fun read(start: Int, end: Int, depth: Int): Message {
            if (depth > MAX_DEPTH) throw InvalidWire()
            var offset = start
            val fields = mutableMapOf<Int, MutableList<Field>>()
            fun varint(): Long {
                var value = 0L
                for (index in 0..9) {
                    if (offset >= end) throw InvalidWire()
                    val next = bytes[offset++].toInt() and 255
                    if (index == 9 && next > 1) throw InvalidWire()
                    value = value or ((next and 127).toLong() shl (index * 7))
                    if (next and 128 == 0) return value
                }
                throw InvalidWire()
            }
            fun fixed(size: Int): Field {
                if (size > end - offset) throw InvalidWire()
                val field = Field(if (size == 8) 1 else 5, start = offset, end = offset + size)
                offset += size
                return field
            }
            while (offset < end) {
                if (--remainingFields < 0) throw InvalidWire()
                val key = varint()
                if (key <= 0 || key > 0xffffffffL) throw InvalidWire()
                val id = (key ushr 3).toInt()
                if (id == 0) throw InvalidWire()
                val field = when ((key and 7).toInt()) {
                    0 -> Field(0, value = varint())
                    1 -> fixed(8)
                    2 -> {
                        val length = varint()
                        if (length < 0 || length > (end - offset).toLong()) throw InvalidWire()
                        Field(2, start = offset, end = offset + length.toInt()).also { offset = it.end }
                    }
                    5 -> fixed(4)
                    else -> throw InvalidWire()
                }
                fields.getOrPut(id) { mutableListOf() }.add(field)
            }
            return Message(this, fields, depth)
        }
    }

    private class Message(
        private val decoder: Decoder,
        private val fields: Map<Int, List<Field>>,
        private val depth: Int,
    ) {
        private val children = mutableMapOf<Int, List<Message>>()
        private fun single(id: Int, wire: Int): Field? {
            val values = fields[id].orEmpty()
            if (values.size > 1 || values.any { it.wire != wire }) throw InvalidWire()
            return values.firstOrNull()
        }

        private fun repeated(id: Int, wire: Int): List<Field> = fields[id].orEmpty().also { values ->
            if (values.any { it.wire != wire }) throw InvalidWire()
        }

        fun number(id: Int): Long = single(id, 0)?.value ?: 0
        fun nonnegative(id: Int): Long = number(id).also { metric(it >= 0) }
        fun optionalNonnegative(id: Int): Long? = single(id, 0)?.value?.also { metric(it >= 0) }
        fun integer(id: Int): Int = number(id).let { value ->
            metric(value in 0..Int.MAX_VALUE.toLong() || value in Int.MIN_VALUE.toLong()..-1)
            value.toInt()
        }
        fun count(id: Int): Int = nonnegative(id).also { metric(it <= Int.MAX_VALUE) }.toInt()
        fun bool(id: Int): Boolean = number(id).also { metric(it == 0L || it == 1L) } == 1L

        fun power(id: Int): Double {
            val field = single(id, 1) ?: return 0.0
            var bits = 0L
            for (index in 0..7) bits = bits or ((decoder.bytes[field.start + index].toLong() and 255) shl (index * 8))
            return Double.fromBits(bits).also { metric(it.isFinite() && it >= 0) }
        }

        fun text(id: Int): String {
            val field = single(id, 2) ?: return ""
            return try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(decoder.bytes, field.start, field.end - field.start)).toString()
            } catch (_: CharacterCodingException) {
                throw InvalidWire()
            }
        }

        fun message(id: Int): Message? {
            single(id, 2) ?: return null
            return messages(id).single()
        }
        fun messages(id: Int): List<Message> {
            val values = repeated(id, 2)
            return children.getOrPut(id) { values.map { decoder.read(it.start, it.end, depth + 1) } }
        }
        fun numbers(id: Int): List<Long> = repeated(id, 0).map { it.value.also { value -> metric(value >= 0) } }

        /** Check every consumed record's shape/name before any bad numeric field can reject it. */
        fun validate(schema: Schema) {
            fun scalars(wire: Int, vararg ids: Int) { ids.forEach { single(it, wire) } }
            fun strings(vararg ids: Int) { ids.forEach { text(it) } }
            fun child(id: Int, kind: Schema, multiple: Boolean = false) {
                if (multiple) messages(id).forEach { it.validate(kind) } else message(id)?.validate(kind)
            }
            when (schema) {
                Schema.ROOT -> child(1, Schema.STATS)
                Schema.STATS -> {
                    scalars(0, 1, 2); strings(3, 4)
                    child(5, Schema.UID, true); child(6, Schema.SYSTEM)
                }
                Schema.SYSTEM -> {
                    child(1, Schema.BATTERY); child(2, Schema.DISCHARGE); repeated(7, 0)
                    child(9, Schema.CONTROLLER); child(14, Schema.NAMED_TIMER, true)
                    child(15, Schema.MISC); child(16, Schema.SIGNAL, true)
                    child(17, Schema.SYSTEM_POWER, true); child(18, Schema.SUMMARY)
                    child(22, Schema.NAMED_TIMER, true); child(24, Schema.SIGNAL, true)
                }
                Schema.BATTERY -> (1..12).forEach { single(it, 0) }
                Schema.UID -> {
                    scalars(0, 1); child(2, Schema.PACKAGE, true); child(6, Schema.BLE); child(7, Schema.CPU)
                    (8..14).forEach { child(it, Schema.TIMER) }
                    child(15, Schema.JOB, true); child(17, Schema.NETWORK); child(18, Schema.POWER)
                    child(19, Schema.PROCESS, true); child(20, Schema.STATE, true); child(21, Schema.SENSOR, true)
                    child(22, Schema.JOB, true); child(24, Schema.AGGREGATE)
                    child(25, Schema.WAKELOCK, true); child(26, Schema.ALARM, true)
                }
                Schema.PACKAGE -> strings(1)
                Schema.CPU -> { scalars(0, 1, 2); child(3, Schema.FREQUENCY, true) }
                Schema.FREQUENCY -> scalars(0, 1, 2, 3)
                Schema.TIMER -> scalars(0, 1, 2, 3, 4, 5)
                Schema.NETWORK -> (1..22).forEach { single(it, 0) }
                Schema.POWER -> { scalars(1, 1, 3, 4); scalars(0, 2) }
                Schema.SYSTEM_POWER -> { scalars(0, 1, 2, 4); scalars(1, 3, 5, 6) }
                Schema.STATE, Schema.AGGREGATE -> scalars(0, 1, 2)
                Schema.SENSOR -> { scalars(0, 1); child(2, Schema.TIMER); child(3, Schema.TIMER) }
                Schema.JOB -> { strings(1); child(2, Schema.TIMER); child(3, Schema.TIMER) }
                Schema.WAKELOCK -> { strings(1); (2..5).forEach { child(it, Schema.TIMER) } }
                Schema.ALARM -> { strings(1); scalars(0, 2) }
                Schema.PROCESS -> { strings(1); (2..7).forEach { single(it, 0) } }
                Schema.BLE -> { (1..4).forEach { child(it, Schema.TIMER) }; scalars(0, 5, 6) }
                Schema.NAMED_TIMER -> { strings(1); child(2, Schema.TIMER) }
                Schema.SIGNAL -> { scalars(0, 1); child(2, Schema.TIMER) }
                Schema.MISC -> scalars(0, 1, 12, 13, 17, 18)
                Schema.DISCHARGE -> scalars(0, 3, 4)
                Schema.SUMMARY -> scalars(1, 1, 2, 3, 4)
                Schema.CONTROLLER -> { scalars(0, 1, 2, 3); child(4, Schema.TX, true) }
                Schema.TX -> scalars(0, 1, 2)
            }
        }
    }

    private data class Timer(val duration: Long, val count: Int, val max: Long?, val actual: Long) {
        companion object {
            fun read(message: Message?): Timer {
                if (message == null) return Timer(0, 0, null, 0)
                val duration = message.nonnegative(1)
                val count = message.count(2)
                val max = message.optionalNonnegative(3)
                message.optionalNonnegative(4) // Validate consumed timer's current-duration field too.
                return Timer(duration, count, max, message.nonnegative(5))
            }
        }
    }

    private class Evidence {
        val tags = mutableSetOf<String>()
        private var rejected = 0
        private var powerRecords = 0
        private var rejectedPower = 0
        private var appsComplete = true
        private var wakersComplete = true
        private var invalidUidTags: MutableSet<String>? = null
        private val apps = mutableListOf<AppPowerStats>()
        private val locks = mutableListOf<WakelockStats>()
        private val alarms = mutableListOf<AlarmStats>()
        private val jobs = mutableListOf<JobStats>()
        private val syncs = mutableListOf<SyncStats>()
        private val networks = mutableListOf<NetworkStats>()
        private val sensors = mutableListOf<SensorStats>()
        private val processes = mutableListOf<ProcessStats>()
        private val kernels = mutableListOf<KernelWakelockStats>()
        private val reasons = mutableListOf<WakeupReasonStats>()
        private val components = mutableMapOf<String, Double>()
        private var signals: List<SignalStrengthStats> = emptyList()
        private var wifiSignals: List<WifiSignalStats> = emptyList()
        private var bluetooth: BluetoothStats? = null
        private var doze: DozeStats? = null
        private var screenOn: Long? = null
        private var screenOnDischarge: Int? = null
        private var screenOffDischarge: Int? = null
        private var capacity: Double? = null
        private var frequencies: List<Long> = emptyList()
        private val frequencyTimes = mutableMapOf<Int, Long>()
        private var frequencyObserved = false
        private var frequenciesValid = true

        private fun <T> measured(tag: String, waker: Boolean = false, block: () -> T): T? {
            tags += tag
            return try {
                block()
            } catch (_: InvalidMetric) {
                rejected++
                if (waker) wakersComplete = false else appsComplete = false
                invalidUidTags?.add(tag)
                null
            }
        }

        private fun total(tag: String, values: List<Long>): Long? =
            if (tag in invalidUidTags.orEmpty()) null else measured(tag) { values.fold(0L, ::sum) }

        fun readUid(uid: Message, id: Int) {
            invalidUidTags = mutableSetOf()
            val packages = uid.messages(2).map { it.text(1) }.filter { it.isNotEmpty() }.distinct().sorted()
            val label = BatteryStatsParser.displayNameFor(id, packages)
            val power = uid.message(18)?.let { item ->
                powerRecords++
                measured("pwi") {
                    item.bool(2)
                    AppPowerStats(id, label, item.power(1), packages,
                        screenPowerMah = item.power(3), proportionalSmearMah = item.power(4))
                }.also { if (it == null) rejectedPower++ }
            }
            val cpu = uid.message(7)
            val cpuMs = measured("cpu") { sum(cpu?.nonnegative(1) ?: 0, cpu?.nonnegative(2) ?: 0) }
            cpu?.messages(3)?.forEach { frequency ->
                measured("ctf") {
                    val index = frequency.count(1)
                    metric(index > 0 && index <= frequencies.size)
                    val duration = frequency.nonnegative(2)
                    frequency.nonnegative(3)
                    frequencyTimes[index] = sum(frequencyTimes[index] ?: 0, duration)
                    frequencyObserved = true
                }.also { if (it == null) frequenciesValid = false }
            }
            val aggregate = uid.message(24)
            val aggregateMs = measured("awl") {
                aggregate?.nonnegative(2)
                aggregate?.nonnegative(1) ?: 0
            }
            fun timer(field: Int, tag: String): Long? = measured(tag) { Timer.read(uid.message(field)).duration }
            val foreground = timer(11, "fg")
            val foregroundService = timer(12, "fgs")
            val stateTimes = mutableMapOf<Int, Long>()
            uid.messages(20).forEach { state ->
                measured("st") {
                    val kind = state.integer(1)
                    val duration = state.nonnegative(2)
                    if (kind in 0..6) {
                        if (stateTimes.put(kind, duration) != null) throw InvalidWire()
                    }
                }
            }
            fun state(kind: Int): Long? = if ("st" in invalidUidTags.orEmpty()) null else stateTimes[kind] ?: 0L
            val top = state(0)
            val background = state(3)
            val network = measured("nt") {
                val item = uid.message(17)
                fun n(field: Int) = item?.nonnegative(field) ?: 0L
                // Validate all reported network counters, including unused packet/background fields.
                for (field in 1..22) n(field)
                // Session snapshots combine each RX/TX pair; reject overflow before conversion.
                sum(n(1), n(2))
                sum(n(3), n(4))
                val activeCount = item?.count(12) ?: 0
                NetworkStats(id, label, packages, n(1), n(2), n(3), n(4), n(5), n(6), n(11), activeCount)
            }?.also(networks::add)
            val networkMessage = uid.message(17)
            fun packets(field: Int): Long? = if (network == null) null else networkMessage?.nonnegative(field) ?: 0

            val uidAlarms = uid.messages(26).mapNotNull { record -> measured("wua") {
                val count = record.count(2)
                AlarmStats(id, label, packages, record.text(1), count, count, null)
            } }.also(alarms::addAll)
            val uidJobs = uid.messages(15).mapNotNull { record -> measured("jb") {
                val total = Timer.read(record.message(2))
                val background = Timer.read(record.message(3))
                JobStats(id, label, packages, record.text(1), total.count, total.duration, background.count, background.actual)
            } }.also(jobs::addAll)
            val uidSyncs = uid.messages(22).mapNotNull { record -> measured("sy") {
                val total = Timer.read(record.message(2))
                val background = Timer.read(record.message(3))
                SyncStats(id, label, packages, record.text(1), total.count, total.duration, background.count, background.actual)
            } }.also(syncs::addAll)
            val uidLocks = uid.messages(25).flatMap { record ->
                val name = record.text(1)
                val backgroundMessage = record.message(4)
                val background = measured("wl") { Timer.read(backgroundMessage) }
                listOf(2 to WakelockType.FULL, 3 to WakelockType.PARTIAL, 5 to WakelockType.WINDOW).mapNotNull { (field, type) ->
                    record.message(field)?.let { item -> measured("wl") {
                        val total = Timer.read(item)
                        WakelockStats(id, label, packages, name, type, total.count, total.duration, total.max,
                            if (type == WakelockType.PARTIAL) background?.actual else null,
                            if (type == WakelockType.PARTIAL) background?.count else null)
                    } }
                }
            }.also(locks::addAll)
            val uidSensors = uid.messages(21).mapNotNull { record -> measured("sr") {
                val handle = record.integer(1)
                val total = Timer.read(record.message(2))
                val background = Timer.read(record.message(3))
                SensorStats(id, label, packages, handle, if (handle == -10000) "GPS" else "Sensor #$handle",
                    total.count, total.duration, background.actual, background.count)
            } }.also(sensors::addAll)
            uid.messages(19).forEach { record -> measured("pr") {
                record.count(6)
                record.count(7)
                ProcessStats(id, label, packages, record.text(1), record.nonnegative(2), record.nonnegative(3),
                    record.nonnegative(4), record.count(5))
            }?.let(processes::add) }
            val audio = timer(8, "aud")
            val camera = timer(9, "cam")
            val flashlight = timer(10, "fla")
            val video = timer(14, "vid")
            val ble = uid.message(6)
            val scan = measured("blem") { Timer.read(ble?.message(1)).duration }
            val unoptimizedScan = measured("blem") { Timer.read(ble?.message(3)).duration }
            if (ble != null) measured("blem") {
                Timer.read(ble.message(2))
                Timer.read(ble.message(4))
                ble.count(5)
                ble.count(6)
            }
            val partial = uidLocks.filter { it.type == WakelockType.PARTIAL }
            val alarmCount = total("wua", uidAlarms.map { it.count.toLong() })
            val jobCount = total("jb", uidJobs.map { it.count.toLong() })
            val jobMs = total("jb", uidJobs.map { it.totalTimeMs })
            val syncCount = total("sy", uidSyncs.map { it.count.toLong() })
            val partialCount = total("wl", partial.map { it.count.toLong() })
            val partialMs = total("wl", partial.map { it.totalTimeMs })
            val partialBgMs = total("wl", partial.mapNotNull { it.backgroundTimeMs })
            val gpsMs = total("sr", uidSensors.filter { it.sensorHandle == -10000 }.map { it.totalTimeMs })
            val sensorMs = total("sr", uidSensors.filter { it.sensorHandle != -10000 }.map { it.totalTimeMs })
            // An omitted energy consumer can still own cumulative counters (for example proxy
            // jobs). Losing those counters from a primary-app baseline would fabricate later deltas.
            // Check only fields carried to session snapshots; zero omissions and details stay valid.
            if (power == null && id in BatteryStatsParser.FIRST_APPLICATION_UID until BatteryStatsParser.PER_USER_RANGE &&
                listOf(cpuMs, aggregateMs, foreground, foregroundService, top, background,
                    network?.mobileRxBytes, network?.mobileTxBytes, network?.wifiRxBytes, network?.wifiTxBytes,
                    network?.mobileActiveTimeMs, alarmCount, jobCount, jobMs, syncCount, partialCount,
                    partialBgMs, gpsMs, sensorMs).any { it != null && it > 0 }
            ) appsComplete = false
            power?.copy(
                cpuTimeMs = cpuMs, wakeLockTimeMs = aggregateMs,
                foregroundTimeMs = foreground, foregroundServiceTimeMs = foregroundService,
                topTimeMs = top, backgroundTimeMs = background, cachedTimeMs = state(6),
                mobileRxBytes = network?.mobileRxBytes, mobileTxBytes = network?.mobileTxBytes,
                wifiRxBytes = network?.wifiRxBytes, wifiTxBytes = network?.wifiTxBytes,
                mobileRxPackets = packets(7), mobileTxPackets = packets(8), wifiRxPackets = packets(9), wifiTxPackets = packets(10),
                wakeupAlarmCount = alarmCount, jobCount = jobCount, jobTimeMs = jobMs, syncCount = syncCount,
                partialWakelockCount = partialCount, partialWakelockTimeMs = partialMs, partialWakelockBgTimeMs = partialBgMs,
                gpsTimeMs = gpsMs, sensorTimeMs = sensorMs,
                audioTimeMs = audio, cameraTimeMs = camera, flashlightTimeMs = flashlight, videoTimeMs = video,
                bluetoothScanTimeMs = scan, bluetoothUnoptimizedScanTimeMs = unoptimizedScan,
            )?.let(apps::add)
            invalidUidTags = null
        }

        fun readSystem(system: Message) {
            val misc = system.message(15)
            measured("m") {
                screenOn = misc?.nonnegative(1) ?: 0L
                val deep = misc?.nonnegative(12) ?: 0L
                val light = misc?.nonnegative(17) ?: 0L
                val deepCount = misc?.count(13) ?: 0
                val lightCount = misc?.count(18) ?: 0
                val combinedCount = sum(deepCount.toLong(), lightCount.toLong()).also { metric(it <= Int.MAX_VALUE) }
                doze = DozeStats(sum(deep, light), combinedCount.toInt(), deep, deepCount, light, lightCount)
            }
            val discharge = system.message(2)
            measured("dc") {
                screenOnDischarge = discharge?.count(3) ?: 0
                screenOffDischarge = discharge?.count(4) ?: 0
            }
            val summary = system.message(18)
            measured("pws") {
                val value = summary?.power(1) ?: 0.0
                metric(value <= 200_000)
                capacity = value.takeIf { it > 0 }
                summary?.power(2)
                summary?.power(3)
                summary?.power(4)
            }
            system.messages(14).forEach { record -> measured("kwl", waker = true) {
                val timer = Timer.read(record.message(2))
                KernelWakelockStats(record.text(1), timer.count, timer.duration, maxTimeMs = timer.max)
            }?.let(kernels::add) }
            system.messages(22).forEach { record -> measured("wr", waker = true) {
                val timer = Timer.read(record.message(2))
                WakeupReasonStats(record.text(1), timer.count, timer.duration)
            }?.let(reasons::add) }
            fun signal(field: Int, tag: String): Map<Int, Long>? = measured(tag) {
                val levels = mutableMapOf<Int, Long>()
                system.messages(field).forEach { record ->
                    val level = record.integer(1)
                    val duration = Timer.read(record.message(2)).duration
                    if (level in 0..4 && levels.put(level, duration) != null) throw InvalidWire()
                }
                levels
            }
            signal(16, "sgt")?.let { levels ->
                val total = levels.values.sumOf { it.toDouble() }
                signals = (0..4).map { level ->
                    val duration = levels[level] ?: 0L
                    SignalStrengthStats(level, duration, if (total > 0) (duration / total).toFloat() else 0f)
                }
            }
            signal(24, "wsgt")?.let { levels ->
                val total = levels.values.sumOf { it.toDouble() }
                wifiSignals = (0..4).map { level ->
                    val duration = levels[level] ?: 0L
                    WifiSignalStats(level, duration, if (total > 0) (duration / total).toFloat() else 0f)
                }
            }
            system.message(9)?.let { controller -> measured("gble") {
                val txLevels = mutableSetOf<Int>()
                val tx = controller.messages(4).map { level ->
                    if (!txLevels.add(level.count(1))) throw InvalidWire()
                    level.nonnegative(2)
                }.fold(0L, ::sum)
                BluetoothStats(controller.nonnegative(1), controller.nonnegative(2), tx, controller.nonnegative(3).toDouble())
            }?.let { bluetooth = it } }
            system.messages(17).forEach { record -> measured("pwi") {
                val kind = record.integer(1)
                record.integer(2)
                record.bool(4)
                record.power(5)
                record.power(6)
                val power = record.power(3)
                val label = when (kind) {
                    1 -> "idle"
                    2 -> "cell"
                    3 -> "phone"
                    4 -> "wifi"
                    5 -> "blue"
                    6 -> "flashlight"
                    7 -> "scrn"
                    8 -> "user"
                    9 -> "unacc"
                    10 -> "over"
                    11 -> "camera"
                    12 -> "memory"
                    13 -> "ambi"
                    else -> null // UNKNOWN_SIPPER cannot distinguish CPU/wakelock/GPS/etc.
                }
                if (label != null) components.putIfAbsent(label, power)
            } }
            measured("gcf") { system.numbers(7).also { values -> values.forEach { metric(it > 0) } } }
                ?.let { frequencies = it }
        }

        fun snapshot(): FullSnapshot {
            val cpuFrequencies = if (frequencyObserved && frequenciesValid) {
                val total = frequencyTimes.values.sumOf { it.toDouble() }
                frequencies.mapIndexed { index, frequency ->
                    val duration = frequencyTimes[index + 1] ?: 0
                    CpuFrequencyStats(-1, frequency, duration, if (total > 0) (duration / total).toFloat() else 0f)
                }
            } else emptyList()
            return FullSnapshot(
                apps = apps.sortedByDescending { it.powerMah },
                reportedTags = tags.toSet(), rejectedRecords = rejected,
                appPowerRecords = powerRecords, rejectedAppPowerRecords = rejectedPower,
                appMeasurementsComplete = appsComplete, deviceWakersComplete = wakersComplete,
                componentEstimatesMah = components.toMap(),
                wakelocks = locks.sortedByDescending { it.totalTimeMs },
                alarms = alarms.sortedByDescending { it.count }, jobs = jobs.sortedByDescending { it.totalTimeMs },
                syncs = syncs.sortedByDescending { it.totalTimeMs },
                network = networks.sortedByDescending { it.mobileRxBytes.toDouble() + it.mobileTxBytes + it.wifiRxBytes + it.wifiTxBytes },
                sensors = sensors.sortedByDescending { it.totalTimeMs },
                processStats = processes.sortedByDescending { it.userTimeMs.toDouble() + it.systemTimeMs },
                kernelWakelocks = kernels.sortedByDescending { it.totalTimeMs }, wakeupReasons = reasons.sortedByDescending { it.totalTimeMs },
                signalStrength = signals, wifiSignal = wifiSignals, bluetooth = bluetooth, doze = doze,
                screenOnTimeMs = screenOn, screenOnDischargePercent = screenOnDischarge,
                screenOffDischargePercent = screenOffDischarge, estimatedCapacityMah = capacity,
                cpuFrequency = cpuFrequencies,
            )
        }
    }
}
