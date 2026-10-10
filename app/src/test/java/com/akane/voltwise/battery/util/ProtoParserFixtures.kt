package com.akane.voltwise.battery.util

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Independent literal schema numbers from AOSP android/os/batterystats.proto, not parser constants. */
internal object ProtoParserFixtures {
    fun join(vararg fields: ByteArray): ByteArray = fields.fold(byteArrayOf()) { a, b -> a + b }
    fun rawVarint(value: Long): ByteArray = ByteArrayOutputStream().apply {
        var remaining = value
        while (remaining and -128L != 0L) {
            write((remaining.toInt() and 127) or 128)
            remaining = remaining ushr 7
        }
        write(remaining.toInt())
    }.toByteArray()
    fun number(field: Int, value: Long): ByteArray = if (value == 0L) byteArrayOf() else
        rawVarint((field * 8).toLong()) + rawVarint(value)
    fun double(field: Int, value: Double): ByteArray = if (value == 0.0) byteArrayOf() else
        rawVarint((field * 8 + 1).toLong()) + ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(value).array()
    fun message(field: Int, vararg fields: ByteArray): ByteArray {
        val body = join(*fields)
        return rawVarint((field * 8 + 2).toLong()) + rawVarint(body.size.toLong()) + body
    }
    fun text(field: Int, value: String): ByteArray = if (value.isEmpty()) byteArrayOf() else
        message(field, value.toByteArray(Charsets.UTF_8))
    fun timer(duration: Long = 0, count: Long = 0, actual: Long? = null): ByteArray =
        join(number(1, duration), number(2, count), actual?.let { number(5, it) } ?: byteArrayOf())
    fun uid(id: Int = 10001, power: Double? = 3.25, vararg more: ByteArray): ByteArray = message(5,
        number(1, id.toLong()), message(2, text(1, "org.example.app$id")),
        power?.let { message(18, double(1, it)) } ?: byteArrayOf(), *more,
    )
    fun battery(start: Long = 1700000000000L, count: Long = 0, realtime: Long = 12000, uptime: Long = 7000): ByteArray =
        message(1, number(1, start), number(2, count), number(5, realtime), number(6, uptime), number(7, 9000), number(9, 1000))
    fun dump(vararg uids: ByteArray, start: Long = 1700000000000L, count: Long = 0, moreSystem: ByteArray = byteArrayOf()): ByteArray =
        message(1, number(1, 36), *uids, message(6, battery(start, count), moreSystem))
}
