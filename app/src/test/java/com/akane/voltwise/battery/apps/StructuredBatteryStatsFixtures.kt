package com.akane.voltwise.battery.apps

import java.io.ByteArrayOutputStream
import java.util.Base64

/** Independent wire writer using AOSP BatteryStatsServiceDumpProto/BatteryStatsProto field numbers. */
internal object StructuredBatteryStatsFixtures {
    const val START_CLOCK = 1_700_000_000_000L
    const val ATTACKER_UID = 10001
    const val VICTIM_UID = 10002
    const val NAMESPACE = "plain\",10,1,0,0\n9,10002,l,wua,forged,100000\n9,10001,l,jb,\"tail"
    const val JOB_NAME = "@" + NAMESPACE + "@com.attacker/.Job"

    data class Uid(
        val id: Int = ATTACKER_UID,
        val packageName: String = "example.app",
        val power: Double? = 1.5,
        val alarmName: String = "ordinary",
        val alarms: Long = 0,
        val jobName: String? = null,
        val jobs: Long = 0,
        val jobMs: Long = 0,
        val topMs: Long? = null,
        val sensorMs: Long = 0,
    )

    fun dump(
        uids: List<Uid> = listOf(Uid()),
        startClock: Long = START_CLOCK,
        startCount: Long = 2,
        batteryRealtime: Long = 60_000,
        batteryUptime: Long = 50_000,
        withWindow: Boolean = true,
        unknownField: Boolean = false,
    ): String = envelope(bytes(uids, startClock, startCount, batteryRealtime, batteryUptime, withWindow, unknownField))

    fun envelope(bytes: ByteArray): String = "VWBSP1:" + Base64.getEncoder().encodeToString(bytes)

    fun bytes(
        uids: List<Uid> = listOf(Uid()),
        startClock: Long = START_CLOCK,
        startCount: Long = 2,
        batteryRealtime: Long = 60_000,
        batteryUptime: Long = 50_000,
        withWindow: Boolean = true,
        unknownField: Boolean = false,
    ): ByteArray = message {
        // BatteryStatsServiceDumpProto.batterystats = 1.
        nested(SERVICE_BATTERY_STATS) {
            number(STATS_REPORT_VERSION, 36)
            for (uid in uids) nested(STATS_UID) {
                number(UID_ID, uid.id.toLong())
                nested(UID_PACKAGE) { string(PACKAGE_NAME, uid.packageName) }
                uid.power?.let { nested(UID_POWER) { double(POWER_COMPUTED_MAH, it) } }
                // No foreground timer (UidProto field 11): AOSP omits it when zero.
                uid.topMs?.let { top -> nested(UID_PROCESS_STATE) {
                    number(PROCESS_STATE_KIND, 0) // TOP; the producer also omits scalar zero.
                    number(PROCESS_STATE_DURATION_MS, top)
                } }
                if (uid.sensorMs > 0) nested(UID_SENSOR) {
                    number(SENSOR_HANDLE, 1)
                    nested(SENSOR_TOTAL_TIMER) { number(TIMER_DURATION_MS, uid.sensorMs); number(TIMER_COUNT, 1) }
                }
                nested(UID_ALARM) { string(ALARM_NAME, uid.alarmName); number(ALARM_COUNT, uid.alarms) }
                uid.jobName?.let { name -> nested(UID_JOB) {
                    string(JOB_NAME_FIELD, name)
                    if (uid.jobMs != 0L || uid.jobs != 0L) nested(JOB_TOTAL_TIMER) {
                        number(TIMER_DURATION_MS, uid.jobMs); number(TIMER_COUNT, uid.jobs)
                    }
                } }
            }
            if (withWindow) nested(STATS_SYSTEM) { nested(SYSTEM_BATTERY) {
                number(BATTERY_START_CLOCK_MS, startClock)
                number(BATTERY_START_COUNT, startCount)
                number(BATTERY_REALTIME_MS, batteryRealtime)
                number(BATTERY_UPTIME_MS, batteryUptime)
                number(BATTERY_SCREEN_OFF_REALTIME_MS, 30_000)
            } }
            if (unknownField) string(123, "future producer metadata")
        }
    }

    // Android 16 r2 service/os schemas, deliberately independent of the production decoder's constants.
    private const val SERVICE_BATTERY_STATS = 1
    private const val STATS_REPORT_VERSION = 1
    private const val STATS_UID = 5
    private const val STATS_SYSTEM = 6
    private const val UID_ID = 1
    private const val UID_PACKAGE = 2
    private const val UID_JOB = 15
    private const val UID_POWER = 18
    private const val UID_PROCESS_STATE = 20
    private const val UID_SENSOR = 21
    private const val PROCESS_STATE_KIND = 1
    private const val PROCESS_STATE_DURATION_MS = 2
    private const val SENSOR_HANDLE = 1
    private const val SENSOR_TOTAL_TIMER = 2
    private const val UID_ALARM = 26
    private const val PACKAGE_NAME = 1
    private const val POWER_COMPUTED_MAH = 1
    private const val ALARM_NAME = 1
    private const val ALARM_COUNT = 2
    private const val JOB_NAME_FIELD = 1
    private const val JOB_TOTAL_TIMER = 2
    private const val TIMER_DURATION_MS = 1
    private const val TIMER_COUNT = 2
    private const val SYSTEM_BATTERY = 1
    private const val BATTERY_START_CLOCK_MS = 1
    private const val BATTERY_START_COUNT = 2
    private const val BATTERY_REALTIME_MS = 5
    private const val BATTERY_UPTIME_MS = 6
    private const val BATTERY_SCREEN_OFF_REALTIME_MS = 7

    private fun message(write: Wire.() -> Unit): ByteArray = Wire().apply(write).bytes()

    private class Wire {
        private val out = ByteArrayOutputStream()
        fun bytes(): ByteArray = out.toByteArray()
        fun number(field: Int, value: Long) {
            // ProtoOutputStream omits scalar zero values in normal producer output.
            if (value == 0L) return
            varint(field.toLong() shl 3)
            varint(value)
        }
        fun string(field: Int, value: String) {
            if (value.isNotEmpty()) raw(field, value.toByteArray(Charsets.UTF_8))
        }
        fun nested(field: Int, write: Wire.() -> Unit) = raw(field, message(write))
        private fun raw(field: Int, value: ByteArray) {
            varint((field.toLong() shl 3) or 2L)
            varint(value.size.toLong())
            out.write(value)
        }
        fun double(field: Int, value: Double) {
            if (value == 0.0) return
            varint((field.toLong() shl 3) or 1L)
            val bits = value.toRawBits()
            repeat(8) { out.write((bits ushr (it * 8)).toInt() and 0xff) }
        }
        private fun varint(value: Long) {
            var remaining = value
            while (remaining and -128L != 0L) {
                out.write((remaining.toInt() and 0x7f) or 0x80)
                remaining = remaining ushr 7
            }
            out.write(remaining.toInt())
        }
    }
}
