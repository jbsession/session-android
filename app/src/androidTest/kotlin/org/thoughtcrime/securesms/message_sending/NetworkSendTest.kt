
package org.thoughtcrime.securesms.message_sending

/**
 * Real-network instrumentation test for Session message sending.
 *
 * What this test does:
 * - Seeds a logged-in state inside the test process.
 * - Resolves real app singletons used by the send pipeline.
 * - Inserts outgoing SMS/MMS rows into the database.
 * - Triggers the real send pipeline through [MessageSender].
 * - Polls the database until each message reaches a terminal state.
 * - Logs a send report for basic debugging and manual verification.
 *
 * Coverage in this file:
 * - Repeatable one-to-one text sends.
 * - Repeatable closed-group text sends.
 * - Repeatable community text sends.
 * - Repeatable note-to-self text sends.
 * - Repeatable one-to-one image sends.
 *
 * Important limitations:
 * - This is not a hermetic or deterministic test. It depends on real network access,
 *   real service availability, valid recipients, local device state, and timing.
 * - Failures can be caused by transport issues, service-side issues, rate limits,
 *   attachment upload problems, account state, or device/environment instability.
 * - It is intended for local/manual validation and debugging, not as a stable CI test.
 * - The recipient addresses in this file are fixed test targets and must be treated as
 *   real destinations.
 * - Running this test sends actual messages and may create message history/spam on the
 *   receiving side.
 *
 * WARNING:
 * - Do not merge or PR this file to the main repository as-is.
 * - Keep this test local or in a private/debug-only workflow to avoid accidental message spam,
 *   noisy test traffic, and unintended use of real infrastructure from shared branches/CI.
 */


import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import android.net.Uri
import org.session.libsession.messaging.messages.signal.OutgoingMediaMessage
import org.thoughtcrime.securesms.database.MmsDatabase
import org.thoughtcrime.securesms.mms.ImageSlide
import org.thoughtcrime.securesms.mms.SlideDeck
import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.jobs.JobQueue
import org.session.libsession.messaging.messages.visible.VisibleMessage
import org.session.libsession.messaging.messages.applyExpiryMode
import org.session.libsession.messaging.messages.signal.OutgoingTextMessage
import org.session.libsession.messaging.sending_receiving.attachments.Attachment
import org.session.libsession.messaging.sending_receiving.MessageSender
import org.session.libsession.messaging.sending_receiving.SendTestHooks
import org.session.libsession.network.SnodeClock
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.crypto.MnemonicCodec
import org.thoughtcrime.securesms.auth.LoggedInState
import org.thoughtcrime.securesms.auth.LoginStateRepository
import org.thoughtcrime.securesms.crypto.MnemonicUtilities
import org.thoughtcrime.securesms.database.MmsSmsDatabase
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.database.SmsDatabase
import org.thoughtcrime.securesms.database.Storage
import org.thoughtcrime.securesms.database.model.MessageId
import javax.inject.Inject
import javax.inject.Provider

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class NetworkSendTest {

    companion object {
        //  Message Counts
        private const val NTS_MESSAGE_COUNT = 10
        private const val ONE_ON_ONE_MESSAGE_COUNT = 20
        private const val GROUP_MESSAGE_COUNT = 20
        private const val COMMUNITY_MESSAGE_COUNT = 3
        private const val IMAGE_MESSAGE_COUNT = 20

        // Delays
        private const val DEFAULT_DELAY_MS = 250L

        // Timeouts
        private const val DEFAULT_EXECUTION_TIMEOUT_MS = 120_000L
        private const val LONG_EXECUTION_TIMEOUT_MS = 300_000L
        private const val DEFAULT_AWAIT_TIMEOUT_MS = 180_000L
        private const val LONG_AWAIT_TIMEOUT_MS = 120_000L

        // Polling
        private const val POLL_INTERVAL_MS = 200L
    }

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var loginStateRepository: LoginStateRepository

    @Inject
    lateinit var messageSenderProvider: Provider<MessageSender>
    @Inject
    lateinit var snodeClockProvider: Provider<SnodeClock>
    @Inject
    lateinit var storageProvider: Provider<Storage>
    @Inject
    lateinit var jobQueueProvider: Provider<JobQueue>
    @Inject
    lateinit var mmsSmsDatabaseProvider: Provider<MmsSmsDatabase>
    @Inject
    lateinit var smsDatabaseProvider: Provider<SmsDatabase>

    @Inject
    lateinit var messagingModuleConfiguration: Provider<MessagingModuleConfiguration>

    @Inject
    lateinit var recipientRepository: RecipientRepository
    @Inject
    lateinit var mmsDatabaseProvider: Provider<MmsDatabase>

    // Resolved after we seed login state
    private lateinit var messageSender: MessageSender
    private lateinit var snodeClock: SnodeClock
    private lateinit var storage: Storage
    private lateinit var jobQueue: JobQueue
    private lateinit var mmsSmsDb: MmsSmsDatabase
    private lateinit var smsDb: SmsDatabase
    private lateinit var mmsDb: MmsDatabase
    private lateinit var sendTestCollector: InMemorySendTestCollector

    @Before
    fun setup() {
        // SQLCipher relies on native libraries, but the test application does not run the normal app
        // initialization. We prepare anything the database needs here before injection happens.
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        MessagingModuleConfiguration.configure(context)

        // Skip the automatic VACUUM step during tests to keep startup faster and more stable.
        runCatching {
            TextSecurePreferences.setLastVacuumNow(context)
        }

        // Explicitly load the SQLCipher native library before anything opens the database.
        runCatching { System.loadLibrary("sqlcipher") }
            .onFailure {
                throw IllegalStateException(
                    "Failed to load SQLCipher native lib (sqlcipher)",
                    it
                )
            }

        // Perform injection on the main thread so any Android handlers created during setup
        // are attached to the main looper.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            hiltRule.inject()
        }

        // Generate a login state the same way the app normally would, but inside the test.
        val phrase =
            "blender balding sabotage javelin cogs fetches duke fatal hitched village sensible oars sensible"

        val codec = MnemonicCodec { fileName ->
            MnemonicUtilities.loadFileContents(context, fileName)
        }

        val seed = codec.sanitizeAndDecodeAsByteArray(phrase)

        // LoggedInState expects a 16‑byte seed.
        check(seed.size == 16) { "Unexpected seed length=${seed.size}, expected 16" }

        loginStateRepository.update { LoggedInState.generate(seed) }

        // After login state is set, we can safely create database and networking singletons.
        // Some of them create Android Handlers, so this must run on the main thread.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            snodeClock = snodeClockProvider.get()
            jobQueue = jobQueueProvider.get()
            messageSender = messageSenderProvider.get()

            // Attempt to reuse the existing encrypted database. If it cannot be opened
            // (for example due to a mismatched key), delete it once and retry.
            storage = try {
                storageProvider.get()
            } catch (t: Throwable) {
                if (looksLikeSqlCipherWrongKeyOrCorruptDb(t)) {
                    Log.w(
                        "NetworkSendTest",
                        "Opening session.db failed; deleting and retrying once",
                        t
                    )
                    deleteSessionDb(context)
                    storageProvider.get()
                } else {
                    throw t
                }
            }
            mmsSmsDb = mmsSmsDatabaseProvider.get()
            smsDb = smsDatabaseProvider.get()
            mmsDb = mmsDatabaseProvider.get()
            sendTestCollector = InMemorySendTestCollector()
            SendTestHooks.collector = sendTestCollector
        }
    }

    private fun deleteSessionDb(context: Context) {
        runCatching { context.deleteDatabase("session.db") }
        runCatching { context.getDatabasePath("session.db-wal").delete() }
        runCatching { context.getDatabasePath("session.db-shm").delete() }
    }

    private fun looksLikeSqlCipherWrongKeyOrCorruptDb(t: Throwable): Boolean {
        // SQLCipher often reports key mismatches as "file is not a database".
        // Only retry for known database corruption or wrong‑key cases.
        var cur: Throwable? = t
        while (cur != null) {
            val msg = cur.message?.lowercase().orEmpty()
            if (
                msg.contains("file is not a database") ||
                msg.contains("not a database") ||
                msg.contains("file is encrypted") ||
                msg.contains("malformed")
            ) return true

            // Some devices wrap the real exception; also catch common SQLite exception types by name
            val name = cur.javaClass.name
            if (
                name.contains("SQLiteException") &&
                (msg.contains("not a database") || msg.contains("file is not a database") || msg.contains(
                    "malformed"
                ))
            ) return true

            cur = cur.cause
        }
        return false
    }

    private data class BatchSummary(
        val attempted: Int,
        val sent: List<MessageId>,
        val failed: List<MessageId>,
        val timedOut: List<MessageId>,
    )

    private suspend fun awaitTerminalStates(
        ids: List<MessageId>,
        timeoutMs: Long = 180_000L,
        pollMs: Long = 200L,
    ): BatchSummary = withTimeout(timeoutMs) {
        val remaining = ids.toMutableSet()
        val sent = mutableListOf<MessageId>()
        val failed = mutableListOf<MessageId>()

        while (remaining.isNotEmpty()) {
            val it = remaining.iterator()
            while (it.hasNext()) {
                val id = it.next()
                when (mmsSmsDb.getOutgoingTerminalState(id)) {
                    MmsSmsDatabase.OutgoingTerminalState.SENT -> {
                        sent += id; it.remove()
                    }

                    MmsSmsDatabase.OutgoingTerminalState.FAILED -> {
                        failed += id; it.remove()
                    }

                    MmsSmsDatabase.OutgoingTerminalState.PENDING -> Unit
                }
            }
            if (remaining.isNotEmpty()) delay(pollMs)
        }

        BatchSummary(
            attempted = ids.size,
            sent = sent,
            failed = failed,
            timedOut = emptyList()
        )
    }

    private suspend fun insertAndSendTextBatch(
        threadId: Long,
        recipient: Address,
        count: Int,
        delayBetweenMessagesMs: Long,
        prefix: String,
    ): List<MessageId> {
        val ids = ArrayList<MessageId>(count)

        repeat(count) { i ->
            val ts = snodeClock.currentTimeMillis()

            val message = VisibleMessage().applyExpiryMode(recipient).apply {
                sentTimestamp = ts
                text = "$prefix #${i + 1}"
            }

            val outgoing = OutgoingTextMessage(
                message = message,
                recipient = recipient,
                expiresInMillis = 0,
                expireStartedAtMillis = 0
            )

            val messageId = MessageId(
                id = smsDb.insertMessageOutbox(
                    threadId,
                    outgoing,
                    false,
                    message.sentTimestamp!!,
                ),
                mms = false
            )

            message.id = messageId
            SendTestHooks.collector?.onEnqueued(messageId)
            ids += messageId

            // Enqueue the real send pipeline (MessageSendJob via JobQueue)
            messageSender.send(message, recipient)

            if (delayBetweenMessagesMs > 0) delay(delayBetweenMessagesMs)
        }

        return ids
    }

    private fun createTestImageFile(
        context: Context,
        fileName: String = "test_upload.jpg",
        width: Int = 20,
        height: Int = 20,
    ): File {
        val file = File(context.cacheDir, fileName)

        if (file.exists()) {
            return file
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        FileOutputStream(file).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) {
                "Failed to write JPEG test image to ${file.absolutePath}"
            }
        }

        return file
    }


    private fun createImageAttachments(
        context: Context,
        fileName: String = "test_upload.jpg",
        width: Int = 20,
        height: Int = 20,
        caption: String? = null,
    ): List<Attachment> {
        val imageFile = createTestImageFile(
            context = context,
            fileName = fileName,
            width = width,
            height = height,
        )

        val slideDeck = SlideDeck()
        slideDeck.addSlide(
            ImageSlide(
                context,
                Uri.fromFile(imageFile),
                imageFile.name,
                imageFile.length(),
                width,
                height,
                caption,
            )
        )

        return slideDeck.asAttachments()
    }

    private suspend fun insertAndSendImageMessage(
        threadId: Long,
        recipient: Address,
        body: String?,
        fileName: String = "test_upload.jpg",
        width: Int = 20,
        height: Int = 20,
        caption: String? = null,
        deleteAttachmentFilesAfterSave: Boolean = false,
    ): MessageId {
        val attachments = createImageAttachments(
            context = InstrumentationRegistry.getInstrumentation().targetContext,
            fileName = fileName,
            width = width,
            height = height,
            caption = caption,
        )

        val sentTimestamp = snodeClock.currentTimeMillis()

        val message = VisibleMessage().applyExpiryMode(recipient).apply {
            this.sentTimestamp = sentTimestamp
            this.text = body
        }

        val outgoingMediaMessage = OutgoingMediaMessage(
            message = message,
            recipient = recipient,
            attachments = attachments,
            outgoingQuote = null,
            linkPreview = null,
            expiresInMillis = 0,
            expireStartedAt = 0,
        )

        val messageId = MessageId(
            id = mmsDb.insertMessageOutbox(
                outgoingMediaMessage,
                threadId,
                false,
                0,
            ),
            mms = true,
        )

        message.id = messageId
        SendTestHooks.collector?.onEnqueued(messageId)

        if (deleteAttachmentFilesAfterSave) {
            attachments
                .asSequence()
                .mapNotNull { attachment ->
                    attachment.dataUri
                        ?.takeIf { it.scheme == "file" }
                        ?.path
                        ?.let(::File)
                }
                .filter { it.exists() }
                .forEach { it.delete() }
        }

        // Use the same attachment-aware overload as production so attachment ids are reloaded
        // from the saved MMS row before the send pipeline continues.
        messageSender.send(message, recipient, null, null)

        return messageId
    }

    private suspend fun insertAndSendImageBatch(
        threadId: Long,
        recipient: Address,
        count: Int,
        bodyPrefix: String,
        fileNamePrefix: String = "test_upload",
        width: Int = 20,
        height: Int = 20,
        captionPrefix: String? = null,
        delayBetweenMessagesMs: Long,
        deleteAttachmentFilesAfterSave: Boolean = false,
    ): List<MessageId> {
        val ids = ArrayList<MessageId>(count)

        repeat(count) { i ->
            val messageId = insertAndSendImageMessage(
                threadId = threadId,
                recipient = recipient,
                body = "$bodyPrefix #${i + 1}",
                fileName = "${fileNamePrefix}_${i + 1}.jpg",
                width = width,
                height = height,
                caption = captionPrefix?.let { "$it #${i + 1}" },
                deleteAttachmentFilesAfterSave = deleteAttachmentFilesAfterSave,
            )

            ids += messageId

            if (delayBetweenMessagesMs > 0) delay(delayBetweenMessagesMs)
        }

        return ids
    }

    private fun logSendReport(
        name: String,
        startTimeMs: Long,
        showErrorsOnly: Boolean = true, // show only failed messages in Messages breakdown
    ) {
        val report = sendTestCollector.buildReport(
            name = name,
            startTimeMs = startTimeMs,
            showErrorsOnly = showErrorsOnly
        )
        Log.i("NetworkSendTest", "\n$report")
    }

    @Test
    fun send_real_network_repeatable_user_nts() = runBlocking {
        val recipient =
            Address.fromSerialized("05301f684ff55f168fcc270053788609c9a711751c5e636c4e587d804ae435a569")

        // ensure thread exists
        val threadId = storage.getOrCreateThreadIdFor(recipient)
        sendTestCollector.reset()
        val testStartTimeMs = System.currentTimeMillis()

        val messageIds = withTimeout(DEFAULT_EXECUTION_TIMEOUT_MS) {
            insertAndSendTextBatch(
                threadId = threadId,
                recipient = recipient,
                count = NTS_MESSAGE_COUNT,
                delayBetweenMessagesMs = DEFAULT_DELAY_MS,
                prefix = "hello from NetworkSendTest NTS",
            )
        }

        val summary = awaitTerminalStates(
            ids = messageIds,
            timeoutMs = DEFAULT_AWAIT_TIMEOUT_MS,
            pollMs = POLL_INTERVAL_MS
        )

        logSendReport(
            name = "send_real_network_repeatable_user_nts",
            startTimeMs = testStartTimeMs,
        )
        if (summary.failed.isNotEmpty()) {
            throw AssertionError("NTS failed message ids: ${summary.failed.map { it.id }}")
        }
    }

    @Test
    fun send_real_network_repeatable_one_on_one() = runBlocking {
        val recipient =
            Address.fromSerialized("0507012662d6972db5ba1f1f6e5501e3b6c6651c10c593d44153546c69fbe77322")

        // ensure thread exists
        val threadId = storage.getOrCreateThreadIdFor(recipient)
        sendTestCollector.reset()
        val testStartTimeMs = System.currentTimeMillis()

        val messageIds = withTimeout(LONG_EXECUTION_TIMEOUT_MS) {
            insertAndSendTextBatch(
                threadId = threadId,
                recipient = recipient,
                count = ONE_ON_ONE_MESSAGE_COUNT,
                delayBetweenMessagesMs = DEFAULT_DELAY_MS,
                prefix = "hello from NetworkSendTest 1:1",
            )
        }

        val summary = awaitTerminalStates(
            ids = messageIds,
            timeoutMs = LONG_AWAIT_TIMEOUT_MS,
            pollMs = POLL_INTERVAL_MS
        )

        logSendReport(
            name = "send_real_network_repeatable_one_on_one",
            startTimeMs = testStartTimeMs,
            showErrorsOnly = false
        )
        if (summary.failed.isNotEmpty()) {
            throw AssertionError("1:1 failed message ids: ${summary.failed.map { it.id }}")
        }
    }

    @Test
    fun send_real_network_repeatable_group() = runBlocking {
        val recipient =
            Address.fromSerialized("034ccd4890302d625eac887b660403140d9a8e131cda797d77d44bec8d5111bc24")

        // ensure thread exists
        val threadId = storage.getOrCreateThreadIdFor(recipient)
        sendTestCollector.reset()
        val testStartTimeMs = System.currentTimeMillis()

        val messageIds = withTimeout(LONG_EXECUTION_TIMEOUT_MS) {
            insertAndSendTextBatch(
                threadId = threadId,
                recipient = recipient,
                count = GROUP_MESSAGE_COUNT,
                delayBetweenMessagesMs = DEFAULT_DELAY_MS,
                prefix = "hello from NetworkSendTest Group",
            )
        }

        val summary = awaitTerminalStates(
            ids = messageIds,
            timeoutMs = DEFAULT_AWAIT_TIMEOUT_MS,
            pollMs = POLL_INTERVAL_MS
        )

        logSendReport(
            name = "send_real_network_repeatable_group",
            startTimeMs = testStartTimeMs,
        )
        if (summary.failed.isNotEmpty()) {
            throw AssertionError("Group failed message ids: ${summary.failed.map { it.id }}")
        }
    }

    @Test
    fun send_real_network_repeatable_community() = runBlocking {
        val recipient =
            Address.fromSerialized("community://https%3A%2F%2Ftest-chat.session.codes?room=testing-all-the-things")

        // ensure thread exists
        val threadId = storage.getOrCreateThreadIdFor(recipient)
        sendTestCollector.reset()
        val testStartTimeMs = System.currentTimeMillis()

        val messageIds = withTimeout(DEFAULT_EXECUTION_TIMEOUT_MS) {
            insertAndSendTextBatch(
                threadId = threadId,
                recipient = recipient,
                count = COMMUNITY_MESSAGE_COUNT,
                delayBetweenMessagesMs = DEFAULT_DELAY_MS,
                prefix = "test community",
            )
        }

        val summary = awaitTerminalStates(
            ids = messageIds,
            timeoutMs = DEFAULT_AWAIT_TIMEOUT_MS,
            pollMs = POLL_INTERVAL_MS
        )

        logSendReport(
            name = "send_real_network_repeatable_community",
            startTimeMs = testStartTimeMs,
        )
        if (summary.failed.isNotEmpty()) {
            throw AssertionError("Community failed message ids: ${summary.failed.map { it.id }}")
        }
    }

    // ATTACHMENT SENDING

    @Test
    fun send_real_network_repeatable_image_one_on_one() = runBlocking {
        val recipient =
            Address.fromSerialized("0507012662d6972db5ba1f1f6e5501e3b6c6651c10c593d44153546c69fbe77322")

        val threadId = storage.getOrCreateThreadIdFor(recipient)
        sendTestCollector.reset()
        val testStartTimeMs = System.currentTimeMillis()
        val messageIds = withTimeout(LONG_EXECUTION_TIMEOUT_MS) {
            insertAndSendImageBatch(
                threadId = threadId,
                recipient = recipient,
                count = IMAGE_MESSAGE_COUNT,
                bodyPrefix = "image upload test",
                fileNamePrefix = "repeatable_upload_test",
                width = 20,
                height = 20,
                captionPrefix = "test caption",
                delayBetweenMessagesMs = DEFAULT_DELAY_MS,
            )
        }

        val summary = awaitTerminalStates(
            ids = messageIds,
            timeoutMs = LONG_AWAIT_TIMEOUT_MS,
            pollMs = POLL_INTERVAL_MS
        )

        logSendReport(
            name = "send_real_network_repeatable_image_one_on_one",
            startTimeMs = testStartTimeMs,
        )
        if (summary.failed.isNotEmpty()) {
            throw AssertionError("Repeatable image failed message ids: ${summary.failed.map { it.id }}")
        }
    }

    @After
    fun tearDown() {
        SendTestHooks.collector = null
    }
}