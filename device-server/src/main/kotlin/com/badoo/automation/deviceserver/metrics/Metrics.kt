package com.badoo.automation.deviceserver.metrics

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder

/** Prometheus counter with a fixed set of label names; no metrics library, so the native image stays small. */
class Counter(private val name: String, private val help: String, private val labelNames: List<String>) {
    private val series = ConcurrentHashMap<List<String>, LongAdder>()

    fun inc(vararg labelValues: String) {
        require(labelValues.size == labelNames.size) { "$name takes labels $labelNames" }
        series.computeIfAbsent(labelValues.toList()) { LongAdder() }.increment()
    }

    fun render(out: StringBuilder) {
        out.appendLine("# HELP $name $help")
        out.appendLine("# TYPE $name counter")
        series.toSortedMap(LABEL_ORDER).forEach { (values, count) -> out.appendLine("$name${labels(labelNames, values)} ${count.sum()}") }
    }
}

/** Prometheus histogram with cumulative `le` buckets, `_sum` and `_count` per label set. */
class Histogram(
    private val name: String,
    private val help: String,
    private val buckets: List<Double>,
    private val labelNames: List<String>
) {
    private class Series(bucketCount: Int) {
        val counts = LongArray(bucketCount)
        var sum = 0.0
        var count = 0L
    }

    private val series = HashMap<List<String>, Series>()

    @Synchronized
    fun observe(value: Double, vararg labelValues: String) {
        require(labelValues.size == labelNames.size) { "$name takes labels $labelNames" }
        val target = series.getOrPut(labelValues.toList()) { Series(buckets.size) }
        val bucket = buckets.indexOfFirst { value <= it }
        if (bucket >= 0) target.counts[bucket]++
        target.sum += value
        target.count++
    }

    @Synchronized
    fun render(out: StringBuilder) {
        out.appendLine("# HELP $name $help")
        out.appendLine("# TYPE $name histogram")
        series.toSortedMap(LABEL_ORDER).forEach { (values, data) ->
            var cumulative = 0L
            buckets.forEachIndexed { index, bound ->
                cumulative += data.counts[index]
                out.appendLine("${name}_bucket${labels(labelNames + "le", values + format(bound))} $cumulative")
            }
            out.appendLine("${name}_bucket${labels(labelNames + "le", values + "+Inf")} ${data.count}")
            out.appendLine("${name}_sum${labels(labelNames, values)} ${data.sum}")
            out.appendLine("${name}_count${labels(labelNames, values)} ${data.count}")
        }
    }

    private fun format(bound: Double) = if (bound % 1.0 == 0.0) bound.toLong().toString() else bound.toString()
}

// Numeric label values (running, boots_in_flight) sort as numbers, so the output is stable and readable
private val LABEL_ORDER = Comparator<List<String>> { a, b ->
    a.zip(b).map { (x, y) -> compareLabel(x, y) }.firstOrNull { it != 0 } ?: a.size.compareTo(b.size)
}

private fun compareLabel(a: String, b: String): Int {
    val x = a.toLongOrNull()
    val y = b.toLongOrNull()
    return if (x != null && y != null) x.compareTo(y) else a.compareTo(b)
}

internal fun labels(names: List<String>, values: List<String>): String =
    if (names.isEmpty()) "" else names.zip(values).joinToString(",", "{", "}") { (name, value) -> "$name=\"${escape(value)}\"" }

private fun escape(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
