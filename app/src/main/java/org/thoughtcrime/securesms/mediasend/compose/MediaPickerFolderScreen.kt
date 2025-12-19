package org.thoughtcrime.securesms.mediasend.compose

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import network.loki.messenger.R
import org.thoughtcrime.securesms.conversation.v2.utilities.AttachmentManager
import org.thoughtcrime.securesms.mediasend.MediaFolder
import org.thoughtcrime.securesms.mediasend.MediaSendViewModel
import org.thoughtcrime.securesms.ui.components.BackAppBar
import org.thoughtcrime.securesms.ui.theme.LocalColors

@Composable
fun MediaPickerFolderScreen(
    viewModel: MediaSendViewModel,
    onFolderClick: (MediaFolder) -> Unit,
    title: String,
    handleBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.refreshFolders()
        viewModel.onFolderPickerStarted()
    }

    MediaPickerFolder(
        folders = uiState.folders,
        onFolderClick = onFolderClick,
        title = title,
        handleBack = handleBack,
        refreshFolders = { viewModel.refreshFolders() }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("ConfigurationScreenWidthHeight")
@Composable
private fun MediaPickerFolder(
    folders: List<MediaFolder>,
    onFolderClick: (folder: MediaFolder) -> Unit,
    title: String,
    handleBack: () -> Unit,
    refreshFolders: () -> Unit
) {

    // span logic: screenWidth / media_picker_folder_width
    val folderWidth = dimensionResource(R.dimen.media_picker_folder_width)
    val columns = maxOf(1, (LocalConfiguration.current.screenWidthDp.dp / folderWidth).toInt())

    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val showManage = remember(activity) {
        activity?.let { AttachmentManager.shouldShowManagePhoto(it) } == true
    }

    Scaffold(
        topBar = {
            BackAppBar(
                title = title,
                onBack = handleBack,
                actions = {
                    if (showManage && activity != null) {
                        IconButton(
                            onClick = {
                                AttachmentManager.managePhotoAccess(activity) {
                                    refreshFolders()
                                }
                            }
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_plus),
                                contentDescription = null
                            )
                        }
                    }
                }
            )
        },
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Horizontal),
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues)) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier
                    .fillMaxSize()
                    .background(LocalColors.current.background)
            ) {
                items(folders) { folder ->
                    MediaFolderCell(
                        title = folder.title,
                        count = folder.itemCount,
                        thumbnailUri = folder.thumbnailUri,
                        onClick = { onFolderClick(folder) }
                    )
                }
            }
        }
    }
}

@Preview
@Composable
private fun MediaPickerFolderPreview() {
    MediaPickerFolder(
        folders = listOf(
            MediaFolder(
                title = "Camera",
                itemCount = 0,
                thumbnailUri = null,
                bucketId = "camera"
            ),
            MediaFolder(
                title = "Daily Bugle",
                itemCount = 122,
                thumbnailUri = null,
                bucketId = "daily_bugle"
            ),
            MediaFolder(
                title = "Screenshots",
                itemCount = 42,
                thumbnailUri = null,
                bucketId = "screenshots"
            )
        ),
        onFolderClick = {},
        title = "Folders",
        handleBack = {},
        refreshFolders = {}
    )
}