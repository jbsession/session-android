package org.thoughtcrime.securesms.message_sending

import org.thoughtcrime.securesms.database.model.MessageId
import java.util.concurrent.ConcurrentHashMap

private data class MutableSendMetric(
    val messageId: MessageId,
    val startedAtMs: Long,
    @Volatile var endedAtMs: Long? = null,
    @Volatile var retryCount: Int = 0,
    @Volatile var success: Boolean? = null,
    @Volatile var error: Throwable? = null,
) {
    fun toResult(nowMs: Long): SendTestResult {
        val end = endedAtMs ?: nowMs
        return SendTestResult(
            messageId = messageId,
            success = success == true,
            retryCount = retryCount,
            latencyMs = (end - startedAtMs).coerceAtLeast(0L),
            error = error,
        )
    }
}

class InMemorySendTestCollector : SendTestEventCollector {

    private val metrics = ConcurrentHashMap<String, MutableSendMetric>()

    private fun key(messageId: MessageId): String =
        "${messageId.id}:${messageId.mms}"

    override fun onEnqueued(messageId: MessageId) {
        metrics.putIfAbsent(
            key(messageId),
            MutableSendMetric(
                messageId = messageId,
                startedAtMs = System.currentTimeMillis(),
            )
        )
    }

    override fun onRetry(
        messageId: MessageId,
        error: Throwable,
        failureCount: Int,
    ) {
        val metric = metrics.computeIfAbsent(key(messageId)) {
            MutableSendMetric(
                messageId = messageId,
                startedAtMs = System.currentTimeMillis(),
            )
        }

        metric.retryCount = maxOf(metric.retryCount, failureCount)
        metric.error = error
    }

    override fun onSuccess(messageId: MessageId) {
        val metric = metrics.computeIfAbsent(key(messageId)) {
            MutableSendMetric(
                messageId = messageId,
                startedAtMs = System.currentTimeMillis(),
            )
        }

        metric.success = true
        metric.endedAtMs = System.currentTimeMillis()
        metric.error = null
    }

    override fun onFailure(
        messageId: MessageId,
        error: Throwable,
    ) {
        val metric = metrics.computeIfAbsent(key(messageId)) {
            MutableSendMetric(
                messageId = messageId,
                startedAtMs = System.currentTimeMillis(),
            )
        }

        metric.success = false
        metric.endedAtMs = System.currentTimeMillis()
        metric.error = error
    }

    fun buildReport(
        name: String,
        startTimeMs: Long,
        endTimeMs: Long = System.currentTimeMillis(),
    ): SendTestReport {
        val now = System.currentTimeMillis()
        val results = metrics.values
            .sortedBy { it.startedAtMs }
            .map { it.toResult(now) }

        return SendTestReport(
            name = name,
            results = results,
            startTimeMs = startTimeMs,
            endTimeMs = endTimeMs,
        )
    }

    fun reset() {
        metrics.clear()
    }
}