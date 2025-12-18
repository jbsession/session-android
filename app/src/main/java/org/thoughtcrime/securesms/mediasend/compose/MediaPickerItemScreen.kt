package org.thoughtcrime.securesms.mediasend.compose

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.thoughtcrime.securesms.mediasend.Media
import org.thoughtcrime.securesms.mediasend.MediaSendViewModel
import network.loki.messenger.R
import org.session.libsession.utilities.MediaTypes
import org.thoughtcrime.securesms.mediasend.Media.Companion.ALL_MEDIA_BUCKET_ID
import org.thoughtcrime.securesms.ui.components.BackAppBar
import org.thoughtcrime.securesms.ui.theme.LocalColors
import androidx.core.net.toUri

@Composable
fun MediaPickerItemScreen(
    viewModel: MediaSendViewModel,
    bucketId: String,
    title: String,
    maxSelection: Int,
    onBack: () -> Unit,
    onMediaSelected: (Media) -> Unit, // navigate to send screen
) {
    val uiState = viewModel.uiState.collectAsState().value
    val context = LocalContext.current


    LaunchedEffect(bucketId) {
        viewModel.getMediaInBucket(bucketId) // triggers repository + updates uiState.bucketMedia
        viewModel.onItemPickerStarted()
    }

    LaunchedEffect(Unit) {
        viewModel.effects.collect { eff ->
            when (eff) {
                is MediaSendViewModel.MediaSendEffect.ShowError -> {
                    Toast.makeText(context, R.string.attachmentsErrorNumber, Toast.LENGTH_SHORT)
                        .show()
                }

                is MediaSendViewModel.MediaSendEffect.Toast ->
                    Toast.makeText(context, eff.messageRes, Toast.LENGTH_SHORT).show()

                is MediaSendViewModel.MediaSendEffect.ToastText ->
                    Toast.makeText(context, eff.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    MediaPickerItem(
        title = title,
        media = uiState.bucketMedia,
        selected = uiState.selectedMedia,
        maxSelection = maxSelection,
        showMultiSelectAction = !uiState.showCountButton,
        onBack = onBack,
        onStartMultiSelect = { viewModel.onMultiSelectStarted() },
        onToggleSelection = { nextSelected ->
            viewModel.onSelectedMediaChanged(nextSelected.map { it }) // List<Media?>
        },
        onSinglePick = { media ->
            viewModel.onSingleMediaSelected(context, media)
            onMediaSelected(media)
        }
    )

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaPickerItem(
    title: String,
    media: List<Media>,
    selected: List<Media>,
    maxSelection: Int,
    showMultiSelectAction: Boolean,
    onBack: () -> Unit,
    onStartMultiSelect: () -> Unit,
    onToggleSelection: (List<Media>) -> Unit,
    onSinglePick: (Media) -> Unit,
) {

    // spanCount = screenWidth / itemWidth (same as fragment)
    val itemWidth = dimensionResource(R.dimen.media_picker_item_width)
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val columns = maxOf(1, (screenWidth / itemWidth).toInt())

    var multiSelectMode by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            BackAppBar(
                title = title,
                onBack = onBack,
                actions = {
                    if (showMultiSelectAction) {
                        IconButton(
                            onClick = {
                                multiSelectMode = true
                                onStartMultiSelect()
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
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(LocalColors.current.background)
        ) {
            items(media, key = { it.uri }) { item ->
                MediaPickerItemCell(
                    media = item,
                    selected = selected,
                    forcedMultiSelect = multiSelectMode, // your remembered state / VM flag
                    maxSelection = maxSelection,
                    onMediaChosen = { onSinglePick(it) },
                    onSelectionStarted = onStartMultiSelect,
                    onSelectionChanged = { onToggleSelection(it.map { m -> m }) },
                    onSelectionOverflow = { /* show toast */ }
                )
            }
        }
    }
}


@Preview(name = "Picker - no selection")
@Composable
private fun Preview_MediaPickerItem_NoSelection() {
    val media = previewMediaList()
    MediaPickerItem(
        title = "Screenshots",
        media = media,
        selected = emptyList(),
        maxSelection = 32,
        showMultiSelectAction = true,
        onBack = {},
        onStartMultiSelect = {},
        onToggleSelection = {},
        onSinglePick = {},
    )
}

@Preview(name = "Picker - multi-select with 2 selected")
@Composable
private fun Preview_MediaPickerItem_WithSelection() {
    val media = previewMediaList()
    val selected = listOf(media[1], media[4])

    MediaPickerItem(
        title = "Camera Roll",
        media = media,
        selected = selected,
        maxSelection = 32,
        showMultiSelectAction = false,
        onBack = {},
        onStartMultiSelect = {},
        onToggleSelection = {},
        onSinglePick = {},
    )
}

private fun previewMediaList(): List<Media> {
    return (1..12).map { i ->
        Media(
            "content://preview/media/$i".toUri(),
            "preview_$i.jpg",
            MediaTypes.IMAGE_JPEG,
            /* date */ 0L,
            /* width */ 1080,
            /* height */ 1080,
            /* size */ 1234L,
            /* bucketId */ ALL_MEDIA_BUCKET_ID,
            /* caption */ null
        )
    }
}