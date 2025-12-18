package org.thoughtcrime.securesms.mediasend.compose

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import network.loki.messenger.R
import org.thoughtcrime.securesms.mediasend.Media
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.util.MediaUtil
import kotlin.collections.filterNot
import kotlin.collections.indexOfFirst
import androidx.core.net.toUri

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun MediaFolderCell(
    title: String,
    count: Int,
    thumbnailUri: Uri?,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .padding(end = 2.dp, bottom = 2.dp)
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Box(modifier = Modifier.aspectRatio(1f)) {
            GlideImage(
                model = thumbnailUri,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            // Bottom shade overlay
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(50.dp)
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.0f to Color.Transparent,
                                0.5f to Color.Black.copy(alpha = 0.5333f),
                                1.0f to Color.Black.copy(alpha = 0.6667f)
                            )
                        )
                    )
            )
            // Bottom row
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_baseline_folder_24),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    colorFilter = ColorFilter.tint(Color.White)
                )

                Spacer(Modifier.width(6.dp))

                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(Modifier.width(6.dp))

                Text(
                    text = count.toString(),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}


@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun MediaPickerItemCell(
    media: Media,
    selected: List<Media>,
    forcedMultiSelect: Boolean,
    maxSelection: Int,
    onMediaChosen: (Media) -> Unit,
    onSelectionStarted: () -> Unit,
    onSelectionChanged: (List<Media>) -> Unit,
    onSelectionOverflow: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isSelected = selected.any { it.uri == media.uri }
    val selectedIndex = remember(selected, media) {
        selected.indexOfFirst { it.uri == media.uri }
    }

    // Matches adapter rules:
    val inSelectionUi = !(selected.isEmpty() && !forcedMultiSelect)
    val showSelectOff = inSelectionUi
    val showSelectOn = inSelectionUi && isSelected
    val showSelectOverlay = isSelected

    val canStartSelectionByLongPress = maxSelection > 1 && selected.isEmpty() && !forcedMultiSelect

    fun removeFromSelection(): List<Media> =
        selected.filterNot { it.uri == media.uri }

    fun addToSelection(): List<Media> =
        selected + media

    Box(
        modifier = modifier
            .padding(end = 2.dp, bottom = 2.dp)
            .aspectRatio(1f)
            .combinedClickable(
                onClick = {
                    if (selected.isEmpty() && !forcedMultiSelect) {
                        // adapter: direct choose
                        onMediaChosen(media)
                    } else if (isSelected) {
                        // adapter: remove
                        onSelectionChanged(removeFromSelection())
                    } else {
                        // adapter: add if room else overflow
                        if (selected.size < maxSelection) {
                            onSelectionChanged(addToSelection())
                        } else {
                            onSelectionOverflow(maxSelection)
                        }
                    }
                },
                onLongClick = if (canStartSelectionByLongPress) {
                    {
                        // adapter: long press starts selection, adds this item
                        onSelectionChanged(listOf(media))
                        onSelectionStarted()
                    }
                } else null
            )
    ) {
        // Thumbnail
        GlideImage(
            model = media.uri,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // Border overlay (replaces @drawable/mediapicker_item_border_dark View)
        Box(
            Modifier
                .matchParentSize()
                .border(width = 1.dp, color = Color(0x33000000))
        )

        // Play overlay (center) for video
        if (MediaUtil.isVideoType(media.mimeType)) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.triangle_right),
                    contentDescription = null,
                    modifier = Modifier
                        .size(width = 15.dp, height = 18.dp)
                        .padding(start = 2.dp),
                    colorFilter = ColorFilter.tint(Color(0xFF2A7BFF)) // match your @color/core_blue-ish
                )
            }
        }

        // Selection overlay (transparent_black_90)
        if (showSelectOverlay) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(Color(0xE6000000))
            )
        }

        // Select OFF badge (top-end)
        if (showSelectOff) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
            ) {
                IndicatorOff(size = dimensionResource(R.dimen.small_radial_size))
            }
        }

        // Select ON badge + order number (top-end)
        if (showSelectOn) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp),
                contentAlignment = Alignment.Center
            ) {
                IndicatorOn(size = dimensionResource(R.dimen.small_radial_size))

                Text(
                    text = (selectedIndex + 1).toString(),
                    color = LocalColors.current.onInvertedBackgroundAccent,
                    style = LocalType.current.base,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun IndicatorOff(size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .border(
                width = Dp.Hairline,
                color = LocalColors.current.text,
                shape = CircleShape
            )
    )
}

@Composable
private fun IndicatorOn(size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(
                color = LocalColors.current.accent,
                shape = CircleShape
            )
    )
}

@Preview
@Composable
private fun PreviewMediaFolderCell() {
    MediaFolderCell(
        title = "Test Title",
        count = 100,
        thumbnailUri = null
    ) { }
}

@Preview(name = "MediaPickerItemCell - Not selected")
@Composable
private fun Preview_MediaPickerItemCell_NotSelected() {
    val media = previewMedia("content://preview/media/1", "image/jpeg")

    MediaPickerItemCell(
        media = media,
        selected = emptyList(),
        forcedMultiSelect = false,
        maxSelection = 32,
        onMediaChosen = {},
        onSelectionStarted = {},
        onSelectionChanged = {},
        onSelectionOverflow = {},
    )
}

@Preview(name = "MediaPickerItemCell - Selected (order 1)")
@Composable
private fun Preview_MediaPickerItemCell_Selected() {
    val media = previewMedia("content://preview/media/2", "image/jpeg")

    MediaPickerItemCell(
        media = media,
        selected = listOf(media), // selectedIndex = 0 -> shows "1"
        forcedMultiSelect = true,
        maxSelection = 32,
        onMediaChosen = {},
        onSelectionStarted = {},
        onSelectionChanged = {},
        onSelectionOverflow = {},
    )
}

private fun previewMedia(uri: String, mime: String): Media {
    return Media(
        uri.toUri(),
        /* filename = */ "preview",
        /* mimeType = */ mime,
        /* date = */ 0L,
        /* width = */ 100,
        /* height = */ 100,
        /* size = */ 1234L,
        /* bucketId = */ "preview",
        /* caption = */ null
    )
}