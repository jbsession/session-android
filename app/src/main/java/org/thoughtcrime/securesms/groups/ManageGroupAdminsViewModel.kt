package org.thoughtcrime.securesms.groups

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import network.loki.messenger.R
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.groups.GroupManagerV2
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.thoughtcrime.securesms.conversation.v2.settings.ConversationSettingsDestination
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.ui.CollapsibleFooterItemData
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.ui.UINavigator
import org.thoughtcrime.securesms.util.AvatarUtils

/**
 * Admin screen:
 *  - Shows admins + their promotion status
 *  - Lets you select admins with failed/sent promotions
 *  - Bottom tray: "Resend promotions"
 *
 * No removing members, no invites here.
 */
@HiltViewModel(assistedFactory = ManageGroupAdminsViewModel.Factory::class)
class ManageGroupAdminsViewModel @AssistedInject constructor(
    @Assisted private val groupAddress: Address.Group,
    @Assisted private val navigator: UINavigator<ConversationSettingsDestination>,
    @ApplicationContext private val context: Context,
    storage: StorageProtocol,
    private val configFactory: ConfigFactoryProtocol,
    private val groupManager: GroupManagerV2,
    private val recipientRepository: RecipientRepository,
    avatarUtils: AvatarUtils,
) : BaseGroupMembersViewModel(
    groupAddress = groupAddress,
    context = context,
    storage = storage,
    configFactory = configFactory,
    avatarUtils = avatarUtils,
    recipientRepository = recipientRepository
) {
    private val groupId = groupAddress.accountId

    // Current group name (for header / text, if needed)
    val groupName: StateFlow<String> = groupInfo
        .map { it?.first?.name.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "")

    private val _mutableSelectedAdmins = MutableStateFlow(emptySet<GroupMemberState>())
    val selectedAdmins: StateFlow<Set<GroupMemberState>> = _mutableSelectedAdmins

    private val footerCollapsed = MutableStateFlow(false)

    /**
     * One option for admins for now: "Promote members"
     */
    private val optionsList: List<OptionsItem> by lazy {
        listOf(
            OptionsItem(
                // use plural version of this string resource
                name = context.resources.getQuantityString(R.plurals.promoteMember, 2, 2),
                icon = R.drawable.ic_add_admin_custom,
                onClick = ::navigateToPromoteMembers
            )
        )
    }

    private val _uiState = MutableStateFlow(UiState(options = optionsList))
    val uiState: StateFlow<UiState> = _uiState

    init {
        // Build footer from selected admins + collapsed state
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                selectedAdmins,
                footerCollapsed,
                ::buildFooterState
            ).collect { footer ->
                _uiState.update { it.copy(footer = footer) }
            }
        }
    }

    fun onAdminItemClicked(member: GroupMemberState) {
        val newSet = _mutableSelectedAdmins.value.toHashSet()
        if (!newSet.remove(member)) {
            newSet.add(member)
        }
        _mutableSelectedAdmins.value = newSet
    }

    fun onSearchFocusChanged(isFocused: Boolean) {
        _uiState.update { it.copy(isSearchFocused = isFocused) }
    }

    private fun navigateToPromoteMembers() {
        viewModelScope.launch {
            navigator.navigate(
                ConversationSettingsDestination.RouteManageMembers(groupAddress)
            )
        }
    }

    /**
     * Resend promotions to all selected admins.
     */
    fun onResendPromotionsClicked() {
        val selected = selectedAdmins.value
        if (selected.isEmpty()) return

        performGroupOperation(showLoading = false) {
            val accountIds = selected.map { it.accountId }

            removeSearchState(clearSelection = true)

            _uiState.update {
                it.copy(
                    ongoingAction = context.resources.getQuantityString(
                        R.plurals.resendingPromotion,
                        accountIds.size,
                        accountIds.size
                    )
                )
            }

            groupManager.promoteMember(
                groupId,
                accountIds,
                isRepromote = true
            )
        }
    }

    fun removeSearchState(clearSelection: Boolean) {
        onSearchFocusChanged(false)
        onSearchQueryChanged("")

        if (clearSelection) {
            clearSelection()
        }
    }

    fun clearSelection() {
        _mutableSelectedAdmins.value = emptySet()
    }

    fun toggleFooter() {
        footerCollapsed.update { !it }
    }

    fun onDismissError() {
        _uiState.update { it.copy(error = null) }
    }

    fun onDismissResend() {
        _uiState.update { it.copy(ongoingAction = null) }
    }

    /**
     * Shared helper for group operations (same pattern with ManageGroupMembersViewModel).
     */
    private fun performGroupOperation(
        showLoading: Boolean = true,
        errorMessage: ((Throwable) -> String?)? = null,
        operation: suspend () -> Unit
    ) {
        viewModelScope.launch {
            if (showLoading) {
                _uiState.update { it.copy(inProgress = true) }
            }

            @Suppress("OPT_IN_USAGE")
            val task = GlobalScope.async {
                operation()
            }

            try {
                task.await()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        error = errorMessage?.invoke(e)
                            ?: context.getString(R.string.errorUnknown)
                    )
                }
            } finally {
                if (showLoading) {
                    _uiState.update { it.copy(inProgress = false) }
                }
            }
        }
    }

    private fun buildFooterState(
        selected: Set<GroupMemberState>,
        isCollapsed: Boolean
    ): CollapsibleFooterState {
        val count = selected.size
        val visible = count > 0

        val title =
            if (count == 0) GetString("")
            else {
                GetString(
                    context.resources.getQuantityString(
                        R.plurals.adminSelected,
                        count,
                        count
                    )
                )
            }

        val trayItems = listOf(
            CollapsibleFooterItemData(
                label = GetString(
                    context.resources.getQuantityString(R.plurals.resendPromotion, count, count)
                ),
                buttonLabel = GetString(context.getString(R.string.resend)),
                isDanger = false,
                onClick = { onResendPromotionsClicked() }
            )
        )

        return CollapsibleFooterState(
            visible = visible,
            collapsed = if (!visible) false else isCollapsed,
            footerActionTitle = title,
            footerActionItems = trayItems
        )
    }

    fun onCommand(command: Commands) {
        when (command) {
            is Commands.DismissError -> onDismissError()
            is Commands.DismissResend -> onDismissResend()
            is Commands.ToggleFooter -> toggleFooter()
            is Commands.CloseFooter,
            is Commands.ClearSelection -> clearSelection()
            is Commands.MemberClick -> onAdminItemClicked(command.member)
            is Commands.RemoveSearchState -> removeSearchState(command.clearSelection)
            is Commands.SearchFocusChange -> onSearchFocusChanged(command.focus)
            is Commands.SearchQueryChange -> onSearchQueryChanged(command.query)
        }
    }

    data class UiState(
        val options: List<OptionsItem> = emptyList(),

        val inProgress: Boolean = false,
        val error: String? = null,
        val ongoingAction: String? = null,

        // search UI state:
        val searchQuery: String = "",
        val isSearchFocused: Boolean = false,

        //Collapsible footer
        val footer: CollapsibleFooterState = CollapsibleFooterState()
    )

    data class CollapsibleFooterState(
        val visible: Boolean = false,
        val collapsed: Boolean = false,
        val footerActionTitle: GetString = GetString(""),
        val footerActionItems: List<CollapsibleFooterItemData> = emptyList()
    )

    data class OptionsItem(
        val name: String,
        @DrawableRes val icon: Int,
        @StringRes val qaTag: Int? = null,
        val onClick: () -> Unit
    )

    sealed interface Commands {
        data object DismissError : Commands
        data object DismissResend : Commands

        data object ToggleFooter : Commands
        data object CloseFooter : Commands
        data object ClearSelection : Commands

        data class RemoveSearchState(val clearSelection: Boolean) : Commands
        data class SearchQueryChange(val query: String) : Commands
        data class SearchFocusChange(val focus: Boolean) : Commands

        data class MemberClick(val member: GroupMemberState) : Commands
    }

    @AssistedFactory
    interface Factory {
        fun create(
            groupAddress: Address.Group,
            navigator: UINavigator<ConversationSettingsDestination>
        ): ManageGroupAdminsViewModel
    }
}