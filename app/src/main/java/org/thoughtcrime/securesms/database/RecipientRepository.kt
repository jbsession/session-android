package org.thoughtcrime.securesms.database

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import network.loki.messenger.libsession_util.ReadableGroupInfoConfig
import network.loki.messenger.libsession_util.util.ExpiryMode
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.ConfigUpdateNotification
import org.session.libsession.utilities.GroupRecord
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.getGroup
import org.session.libsession.utilities.recipients.BasicRecipient
import org.session.libsession.utilities.recipients.RecipientAvatar
import org.session.libsession.utilities.recipients.RecipientAvatar.Companion.toRecipientAvatar
import org.session.libsession.utilities.recipients.RecipientSettings
import org.session.libsession.utilities.recipients.RecipientV2
import org.session.libsession.utilities.recipients.displayNameOrFallback
import org.session.libsession.utilities.userConfigsChanged
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.IdPrefix
import org.session.libsignal.utilities.Log
import java.lang.ref.WeakReference
import java.time.Instant
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * This repository is responsible for observing and retrieving recipient data from different sources.
 *
 * Not to be confused with [RecipientDatabase], where it manages the actual database storage of
 * some recipient data. Note that not all recipient data is stored in the database, as we've moved
 * them to the config system. Details in the [RecipientDatabase].
 *
 * This class will source the correct recipient data from different sources based on their types.
 */
@Singleton
class RecipientRepository @Inject constructor(
    private val configFactory: ConfigFactoryProtocol,
    private val groupDatabase: GroupDatabase,
    private val recipientDatabase: RecipientDatabase,
    private val preferences: TextSecurePreferences,
) {
    private val recipientCache = HashMap<Address, WeakReference<SharedFlow<RecipientV2?>>>()

    fun observeRecipient(address: Address): Flow<RecipientV2?> {
        synchronized(recipientCache) {
            var cached = recipientCache[address]?.get()
            if (cached == null) {
                cached = createRecipientFlow(address)
                recipientCache[address] = WeakReference(cached)
            }

            return cached
        }
    }

    // This function creates a flow that emits the recipient information for the given address,
    // the function itself must be fast, not directly access db and lock free, as it is called from a locked context.
    private fun createRecipientFlow(address: Address): SharedFlow<RecipientV2?> {
        return flow {
            while (true) {
                val (value, changeSource) = fetchRecipient(address) {
                    withContext(Dispatchers.Default) { recipientDatabase.getRecipientSettings(it) }
                } ?: run {
                    // If we don't have a recipient for this address, emit null and terminate the flow.
                    emit(null)
                    return@flow
                }

                emit(value)
                changeSource.first()
                Log.d(TAG, "Recipient changed for ${address.address.substring(0..10)}")
            }

        }.shareIn(GlobalScope,
            // replay must be cleared one when no one is subscribed, so that if no one is subscribed,
            // we will always fetch the latest data. The cache is only valid while there is at least one subscriber.
            SharingStarted.WhileSubscribed(replayExpirationMillis = 0L), replay = 1)
    }

    private inline fun fetchRecipient(address: Address, settingsFetcher: (address: Address) -> RecipientSettings?): Pair<RecipientV2?, Flow<*>>? {
        val basicRecipient = getBasicRecipientFast(address)

        val changeSource: Flow<*>
        val value: RecipientV2?

        when (basicRecipient) {
            is BasicRecipient.Self -> {
                value = createLocalRecipient(basicRecipient)
                changeSource = configFactory.userConfigsChanged()
            }

            is BasicRecipient.Contact -> {
                value = createContactRecipient(
                    basic = basicRecipient,
                    fallbackSettings = settingsFetcher(address)
                )

                changeSource = merge(
                    configFactory.userConfigsChanged(),
                    recipientDatabase.updateNotifications.filter { it == address }
                )
            }

            is BasicRecipient.Group -> {
                value = createGroupV2Recipient(
                    basic = basicRecipient,
                    settings = settingsFetcher(address)
                )

                changeSource = merge(
                    configFactory.userConfigsChanged(),
                    configFactory.configUpdateNotifications
                        .filterIsInstance<ConfigUpdateNotification.GroupConfigsUpdated>()
                        .filter { it.groupId.hexString == address.address },
                    recipientDatabase.updateNotifications.filter { it == address }
                )
            }

            null -> {
                // Given address is not backed by the config system so we'll get them from
                // local database.

                val settings = settingsFetcher(address)

                when {
                    address.isLegacyGroup || address.isCommunity -> {
                        changeSource = merge(
                            groupDatabase.updateNotification,
                            recipientDatabase.updateNotifications.filter { it == address }
                        )

                        val group: GroupRecord? = groupDatabase.getGroup(address.toGroupString()).orNull()
                        value = group?.let { createCommunityOrLegacyGroupRecipient(address, it, settings) }
                    }

                    settings != null -> {
                        value = createGenericRecipient(address, settings)
                        changeSource = recipientDatabase.updateNotifications.filter { it == address }
                    }

                    else -> {
                        Log.w(TAG, "No recipient found for address: ${address.debugString}")
                        return null
                    }
                }
            }
        }

        return value to changeSource
    }

    suspend fun getRecipient(address: Address): RecipientV2? {
        return observeRecipient(address).first()
    }

    @Deprecated(
        "Use the suspend version of getRecipient instead",
        ReplaceWith("getRecipient(address)")
    )
    fun getRecipientSync(address: Address): RecipientV2? {
        val flow = observeRecipient(address)

        // If the flow is a SharedFlow, we might be able to access its last cached value directly.
        if (flow is SharedFlow<RecipientV2?>) {
            val lastCacheValue = flow.replayCache.lastOrNull()
            if (lastCacheValue != null) {
                return lastCacheValue
            }
        }

        // Otherwise, we might have to go to the database to get the recipient..
        return fetchRecipient(address, recipientDatabase::getRecipientSettings)?.first
    }

    /**
     * Returns the recipient name for the given address. This will try to get the information
     * as efficiently as possible, but if it fails to do so a blocking call to the database
     * might be made.
     *
     * If you know the recipient is backed by the config system, it's better to use
     * [getBasicRecipientFast] instead.
     */
    @DelicateCoroutinesApi
    inline fun getRecipientDisplayNameSync(address: Address, fallbackName: () -> String? = { null }): String {
        val basic = getBasicRecipientFast(address)
        if (basic != null) {
            return basic.displayName
        }

        return getRecipientSync(address).displayNameOrFallback(
            fallbackName = fallbackName,
            address = address.address,
        )
    }

    /**
     * Returns a [BasicRecipient] for the given address, without going into the database.
     * If it's impossible, this will return null. When it does, it doesn't mean the recipient
     * doesn't exist, it just means we don't have a fast way to get it. You will need to call
     * [RecipientRepository.getRecipient] to get the full recipient data.
     */
    fun getBasicRecipientFast(address: Address): BasicRecipient.ConfigBasedRecipient? {
        return when {
            // Is this our own address?
            address.address.equals(preferences.getLocalNumber(), ignoreCase = true) -> {
                configFactory.withUserConfigs { configs ->
                    BasicRecipient.Self(
                        address = address,
                        name = configs.userProfile.getName().orEmpty(),
                        avatar = configs.userProfile.getPic().toRecipientAvatar(),
                        expiryMode = configs.userProfile.getNtsExpiry(),
                        acceptsCommunityMessageRequests = configs.userProfile.getCommunityMessageRequests(),
                    )
                }
            }

            // Is this in our contact?
            !address.isGroupOrCommunity &&
                    AccountId.fromStringOrNull(address.address)?.prefix == IdPrefix.STANDARD -> {
                configFactory.withUserConfigs { configs ->
                    configs.contacts.get(address.address)
                }?.let { contact ->
                    BasicRecipient.Contact(
                        address = address,
                        name = contact.name,
                        nickname = contact.nickname.takeIf { it.isNotBlank() },
                        avatar = contact.profilePicture.toRecipientAvatar(),
                        approved = contact.approved,
                        approvedMe = contact.approvedMe,
                        blocked = contact.blocked,
                        expiryMode = contact.expiryMode
                    )
                }
            }

            // Is this a group?
            address.isGroupV2 -> {
                val groupId = AccountId(address.address)
                val groupInfo = configFactory.getGroup(groupId) ?: return null
                configFactory.withGroupConfigs(groupId) { configs ->
                    BasicRecipient.Group(
                        address = address,
                        avatar = configs.groupInfo.getProfilePic().toRecipientAvatar(),
                        expiryMode = configs.groupInfo.expiryMode,
                        name = configs.groupInfo.getName() ?: groupInfo.name
                    )
                }
            }

            // Otherwise, there's no fast way to get a basic recipient
            else -> {
                Log.w(TAG, "No fast way to get a basic recipient for address: ${address.debugString}")
                null
            }
        }
    }

    /**
     * Returns a recipient for the given address, or an empty recipient if not found.
     * This is useful to avoid null checks in the UI.
     */
    @Deprecated(
        "Use the suspend version of getRecipient instead",
        ReplaceWith("getRecipient(address)")
    )
    fun getRecipientSyncOrEmpty(address: Address): RecipientV2 {
        return getRecipientSync(address) ?: empty(address)
    }

    suspend fun getRecipientOrEmpty(address: Address): RecipientV2 {
        return getRecipient(address) ?: empty(address)
    }

    companion object {
        private const val TAG = "RecipientRepository"

        private fun createLocalRecipient(basic: BasicRecipient.Self): RecipientV2 {
            return RecipientV2(
                basic = basic,
                mutedUntil = null,
                autoDownloadAttachments = true,
                notifyType = RecipientDatabase.NOTIFY_TYPE_ALL,
                acceptsCommunityMessageRequests = basic.acceptsCommunityMessageRequests,
            )
        }

        private val RecipientSettings.muteUntilDate: ZonedDateTime?
            get() = if (muteUntil > 0) {
                ZonedDateTime.from(Instant.ofEpochMilli(muteUntil))
            } else {
                null
            }

        private val ReadableGroupInfoConfig.expiryMode: ExpiryMode
            get() {
                val timer = getExpiryTimer()
                return when {
                    timer > 0 -> ExpiryMode.AfterSend(timer)
                    else -> ExpiryMode.NONE
                }
            }

        private fun createGroupV2Recipient(
            basic: BasicRecipient.Group,
            settings: RecipientSettings?
        ): RecipientV2 {
            return RecipientV2(
                basic = basic,
                mutedUntil = settings?.muteUntilDate,
                notifyType = settings?.notifyType ?: RecipientDatabase.NOTIFY_TYPE_ALL,
                autoDownloadAttachments = settings?.autoDownloadAttachments,
                acceptsCommunityMessageRequests = false,
            )

        }

        /**
         * Creates a RecipientV2 instance from the provided Contact config and optional fallback settings.
         */
        private fun createContactRecipient(
            basic: BasicRecipient.Contact,
            fallbackSettings: RecipientSettings?, // Local db data
        ): RecipientV2 {
            return RecipientV2(
                basic = basic,
                mutedUntil = fallbackSettings?.muteUntilDate,
                autoDownloadAttachments = fallbackSettings?.autoDownloadAttachments,
                notifyType = fallbackSettings?.notifyType ?: RecipientDatabase.NOTIFY_TYPE_ALL,
                acceptsCommunityMessageRequests = fallbackSettings?.blocksCommunityMessagesRequests == false,
            )
        }

        private fun createCommunityOrLegacyGroupRecipient(
            address: Address,
            group: GroupRecord, // Local db data
            settings: RecipientSettings?, // Local db data
        ): RecipientV2 {
            return RecipientV2(
                basic = BasicRecipient.Generic(
                    address = address,
                    displayName = group.title,
                    avatar = group.avatar?.let { RecipientAvatar.fromBytes(it) }
                ),
                mutedUntil = settings?.muteUntilDate,
                autoDownloadAttachments = settings?.autoDownloadAttachments,
                notifyType = settings?.notifyType ?: RecipientDatabase.NOTIFY_TYPE_ALL,
                acceptsCommunityMessageRequests = false,
            )
        }

        /**
         * Creates a RecipientV2 instance from the provided Address and RecipientSettings.
         * Note that this method assumes the recipient is not ourselves.
         */
        private fun createGenericRecipient(address: Address, settings: RecipientSettings): RecipientV2 {
            return RecipientV2(
                basic = BasicRecipient.Generic(
                    address = address,
                    displayName = settings.systemDisplayName?.takeIf { it.isNotBlank() } ?: settings.profileName.orEmpty(),
                    avatar = settings.profileAvatar?.let { RecipientAvatar.from(it, settings.profileKey) },
                    isLocalNumber = false,
                    blocked = settings.blocked
                ),
                mutedUntil = settings.muteUntil.takeIf { it > 0 }
                    ?.let { ZonedDateTime.from(Instant.ofEpochMilli(it)) },
                autoDownloadAttachments = settings.autoDownloadAttachments,
                notifyType = settings.notifyType,
                acceptsCommunityMessageRequests = !settings.blocksCommunityMessagesRequests
            )
        }

        fun empty(address: Address): RecipientV2 {
            return RecipientV2(
                basic = BasicRecipient.Generic(address = address),
                mutedUntil = null,
                autoDownloadAttachments = null,
                notifyType = RecipientDatabase.NOTIFY_TYPE_ALL,
                acceptsCommunityMessageRequests = false,
            )
        }
    }
}