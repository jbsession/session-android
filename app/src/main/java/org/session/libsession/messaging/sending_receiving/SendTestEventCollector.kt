package org.session.libsession.messaging.sending_receiving

import org.thoughtcrime.securesms.database.model.MessageId

interface SendTestEventCollector {
    fun onEnqueued(messageId: MessageId)

    fun onRetry(
        messageId: MessageId,
        error: Throwable,
        failureCount: Int,
    )

    fun onSuccess(messageId: MessageId)

    fun onFailure(
        messageId: MessageId,
        error: Throwable,
    )
}

object SendTestHooks {
    @Volatile
    var collector: SendTestEventCollector? = null
}