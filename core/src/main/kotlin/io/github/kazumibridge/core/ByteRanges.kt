package io.github.kazumibridge.core

data class ByteRange(val start: Long, val end: Long) {
    val length: Long get() = end - start + 1
}

sealed interface RangeResult {
    data object Full : RangeResult
    data object Invalid : RangeResult
    data class Partial(val range: ByteRange) : RangeResult
}

object ByteRanges {
    fun parse(header: String?, size: Long): RangeResult {
        if (header == null) return RangeResult.Full
        if (size <= 0 || !header.startsWith("bytes=")) return RangeResult.Invalid
        val fields = header.removePrefix("bytes=").split('-')
        if (fields.size != 2 || fields.any { part -> part.any { it !in '0'..'9' } }) return RangeResult.Invalid
        if (fields[0].isEmpty()) {
            val suffix = fields[1].toLongOrNull() ?: return RangeResult.Invalid
            if (suffix <= 0) return RangeResult.Invalid
            return RangeResult.Partial(ByteRange((size - suffix).coerceAtLeast(0), size - 1))
        }
        val start = fields[0].toLongOrNull() ?: return RangeResult.Invalid
        val end = if (fields[1].isEmpty()) size - 1 else fields[1].toLongOrNull() ?: return RangeResult.Invalid
        if (start >= size || end < start) return RangeResult.Invalid
        return RangeResult.Partial(ByteRange(start, end.coerceAtMost(size - 1)))
    }
}
