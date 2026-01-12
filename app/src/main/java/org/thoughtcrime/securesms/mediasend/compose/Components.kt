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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import network.loki.messenger.R
import org.thoughtcrime.securesms.mediasend.Media
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.util.MediaUtil

@Composable
fun MediaFolderCell(
    title: String,
    count: Int,
    thumbnailUri: Uri?,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Box(modifier = Modifier.aspectRatio(1f)) {
            AsyncImage(
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.Crop,
                model = ImageRequest.Builder(LocalContext.current)
                    .data(thumbnailUri)
                    .build(),
                contentDescription = null,
            )

            // Bottom shade overlay
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background( Color.Transparent)
                    .innerShadow(
                        shape = RectangleShape,
                        shadow = Shadow(
                            radius = 8.dp,
                            color = Color.Black.copy(alpha = 0.4f),
                            offset = DpOffset(x = (-2).dp, (-40).dp) // shadow appears form the bottom
                        )
                    )
                    .padding(LocalDimensions.current.smallSpacing)
            ) {
                // Bottom row
                Row(
                    modifier = Modifier
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_baseline_folder_24),
                        contentDescription = null,
                        modifier = Modifier.size(LocalDimensions.current.iconSmall),
                        colorFilter = ColorFilter.tint(Color.White)
                    )

                    Spacer(Modifier.width(LocalDimensions.current.xxsSpacing))

                    Text(
                        text = title,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Spacer(Modifier.width(LocalDimensions.current.xxsSpacing))

                    Text(
                        text = count.toString(),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}


@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun MediaPickerItemCell(
    media: Media,
    isSelected: Boolean = false,
    selectedIndex: Int = 1,
    isMultiSelect: Boolean,
    onMediaChosen: (Media) -> Unit,
    onSelectionStarted: () -> Unit,
    onSelectionChanged: (selectedMedia: Media) -> Unit,
    modifier: Modifier = Modifier,
    showSelectionOn: Boolean = false,
    canLongPress: Boolean = true
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .border(
                width = LocalDimensions.current.borderStroke,
                color = LocalColors.current.borders
            )
            .combinedClickable(
                onClick = {
                    if (!isMultiSelect) {
                        onMediaChosen(media) // Choosing a single media
                    } else {
                        onSelectionChanged(media) // Selecting/unselecting media
                    }
                },
                onLongClick = if (canLongPress) {
                    {
                        // long press starts selection, adds this item
                        onSelectionChanged(media)
                        onSelectionStarted()
                    }
                } else null
            )
    ) {
        // Thumbnail
        AsyncImage(
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            model = ImageRequest.Builder(LocalContext.current)
                .data(media.uri)
                .build(),
            contentDescription = null,
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
                    modifier = Modifier.size(LocalDimensions.current.iconMedium),
                    colorFilter = ColorFilter.tint(LocalColors.current.accent) // match @color/core_blue-ish
                )
            }
        }

        // Selection overlay
        if (isSelected) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.80f))
            )
        }

        if (isMultiSelect) {
            // Select ON badge + order number (top-end)
            if (showSelectionOn) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(LocalDimensions.current.xxsSpacing),
                    contentAlignment = Alignment.Center
                ) {
                    IndicatorOn()

                    Text(
                        text = (selectedIndex + 1).toString(),
                        color = Color.White,
                        style = LocalType.current.base,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                // Select OFF badge
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(LocalDimensions.current.xxsSpacing)
                ) {
                    IndicatorOff()
                }
            }
        }
    }
}

@Composable
private fun IndicatorOff(modifier: Modifier = Modifier, size: Dp = 26.dp ) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .border(
                width = LocalDimensions.current.borderStroke,
                color = LocalColors.current.text,
                shape = CircleShape
            )
    )
}

@Composable
private fun IndicatorOn(modifier: Modifier = Modifier, size: Dp = 26.dp) {
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
        isMultiSelect = false,
        canLongPress = true,
        onMediaChosen = {},
        onSelectionStarted = {},
        onSelectionChanged = {},
    )
}

@Preview(name = "MediaPickerItemCell - Selected (order 1)")
@Composable
private fun Preview_MediaPickerItemCell_Selected() {
    val media = previewMedia("content://preview/media/2", "image/jpeg")

    MediaPickerItemCell(
        media = media,
        isMultiSelect = true,
        canLongPress = true,
        onMediaChosen = {},
        onSelectionStarted = {},
        onSelectionChanged = {},
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