package org.thoughtcrime.securesms.message_sending

import org.thoughtcrime.securesms.database.model.MessageId

data class SendTestResult(
    val messageId: MessageId,
    val success: Boolean,
    val retryCount: Int,
    val latencyMs: Long,
    val error: Throwable?,
)

data class SendTestReport(
    val name: String,
    val results: List<SendTestResult>,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val showErrorsOnly: Boolean,
) {

    private val successCount = results.count { it.success }
    private val failureCount = results.count { !it.success }
    private val retryCount = results.sumOf { it.retryCount }

    private val latencies = results.map { it.latencyMs }.sorted()

    private val avgLatency =
        if (latencies.isEmpty()) 0.0 else latencies.average()

    private val medianLatency =
        if (latencies.isEmpty()) 0.0
        else if (latencies.size % 2 == 1) latencies[latencies.size / 2].toDouble()
        else {
            val mid = latencies.size / 2
            (latencies[mid - 1] + latencies[mid]) / 2.0
        }

    private val minLatency = latencies.minOrNull() ?: 0L
    private val maxLatency = latencies.maxOrNull() ?: 0L

    private fun classifyError(error: Throwable?): String {
        if (error == null) return "Unknown"

        val msg = error.message.orEmpty().lowercase()

        return when {
            msg.contains("timeout") -> "Timeout"
            msg.contains("decryption") -> "Decryption failed"
            msg.contains("rate") -> "Rate limited"
            msg.contains("clock") -> "Clock out of sync"
            msg.contains("gateway") -> "Gateway issue"
            else -> error::class.java.simpleName
        }
    }

    private fun errorBreakdown(): String {
        val failed = results.filter { !it.success }
        if (failed.isEmpty()) return ""

        return "\n" + failed
            .groupingBy { classifyError(it.error) }
            .eachCount()
            .entries
            .joinToString("\n") { (error, count) ->
                "  - $error: $count"
            }
    }

    private fun perMessageBreakdown(): String {
        val items = if (showErrorsOnly) results.filterNot { it.success } else results
        if (items.isEmpty()) return "\n  (No matching message results)"

        return "\n" + items.joinToString("\n") { result ->
            val status = if (result.success) "SUCCESS" else "FAILED"
            val errorText = result.error?.let { "\n      error=${classifyError(it)}" } ?: ""

            """
              - messageId=${result.messageId.id}
                  status=$status
                  retries=${result.retryCount}
                  latency=${String.format("%.3fs", result.latencyMs / 1000.0)}$errorText
            """.trimEnd()
        }
    }

    override fun toString(): String {
        val total = results.size
        val successRate = if (total == 0) 0.0 else successCount.toDouble() / total
        val failureRate = if (total == 0) 0.0 else failureCount.toDouble() / total

        return """
            
            $name:
            ------------------------
            Successes: $successCount (${String.format("%.2f%%", successRate * 100)})
            Failures: $failureCount (${String.format("%.2f%%", failureRate * 100)})${errorBreakdown()}
            Retries: $retryCount
            Latency:
              - Avg: ${String.format("%.3fs", avgLatency / 1000.0)}
              - Median: ${String.format("%.3fs", medianLatency / 1000.0)}
              - Min: ${String.format("%.3fs", minLatency / 1000.0)}
              - Max: ${String.format("%.3fs", maxLatency / 1000.0)}
              - Total: ${String.format("%.3fs", (endTimeMs - startTimeMs) / 1000.0)}
            Messages:${perMessageBreakdown()}
        """.trimIndent()
    }
}