package com.music.bitchord.ui.player

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.components.optimizedHazeEffect
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

private const val SHARE_FOLDER = "story_cards"
private const val MIME = "image/png"

/**
 * A bottom sheet displaying an Apple Music-style 9:16 story card for sharing songs to
 * Instagram Stories, WhatsApp Status, or saving to Gallery.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun SongShareSheet(
    song: Song,
    hazeState: HazeState? = null,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var poster by remember(song.videoId) { mutableStateOf<Bitmap?>(null) }
    var failed by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()

    LaunchedEffect(song.videoId) {
        poster = runCatching { renderSongPoster(context, song) }
            .onFailure { failed = true }
            .getOrNull()
    }

    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = if (hazeState != null && !reduceDynamicBlur) 0.88f else 1f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (hazeState != null && !reduceDynamicBlur) {
                    Modifier.optimizedHazeEffect(
                        state = hazeState,
                        style = HazeMaterials.thin(MaterialTheme.colorScheme.surface),
                    )
                } else {
                    Modifier
                }
            ),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(top = 16.dp, bottom = 20.dp),
        ) {
            Text(
                text = "Share Story Card",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.W800,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Text(
                text = "Apple Music-style 9:16 card formatted for Instagram & WhatsApp",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            // Story card preview (9:16 aspect ratio)
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.55f)
                    .aspectRatio(9f / 16f)
                    .align(Alignment.CenterHorizontally)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                val img = poster
                if (img != null) {
                    Image(
                        bitmap = img.asImageBitmap(),
                        contentDescription = song.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize(),
                    )
                } else if (failed) {
                    Text(
                        text = "Could not render story card",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 3.dp,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            val ready = poster != null

            // Action Buttons Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Instagram Stories / Direct Share
                ShareAction(
                    label = "Instagram",
                    icon = Icons.Rounded.IosShare,
                    accent = true,
                    enabled = ready,
                    modifier = Modifier.weight(1f),
                ) {
                    val image = poster ?: return@ShareAction
                    scope.launch {
                        val uri = cacheForSharing(context, image) ?: return@launch
                        shareToInstagram(context, uri, song)
                        onDismiss()
                    }
                }

                // WhatsApp Share
                ShareAction(
                    label = "WhatsApp",
                    icon = Icons.Rounded.IosShare,
                    accent = false,
                    enabled = ready,
                    modifier = Modifier.weight(1f),
                ) {
                    val image = poster ?: return@ShareAction
                    scope.launch {
                        val uri = cacheForSharing(context, image) ?: return@launch
                        shareToWhatsApp(context, uri, song)
                        onDismiss()
                    }
                }

                // Save Card
                ShareAction(
                    label = if (saved) "Saved" else "Save",
                    icon = Icons.Rounded.Download,
                    accent = false,
                    enabled = ready && !saved,
                    modifier = Modifier.weight(1f),
                ) {
                    val image = poster ?: return@ShareAction
                    scope.launch {
                        val uri = saveToGallery(context, image, song.title)
                        if (uri != null) saved = true
                    }
                }

                // More Options
                ShareAction(
                    label = "More",
                    icon = Icons.Rounded.IosShare,
                    accent = false,
                    enabled = ready,
                    modifier = Modifier.weight(1f),
                ) {
                    val image = poster ?: return@ShareAction
                    scope.launch {
                        val uri = cacheForSharing(context, image) ?: return@launch
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = MIME
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_TEXT, "${song.title} - ${song.artist}\nhttps://music.youtube.com/watch?v=${song.videoId}")
                            clipData = android.content.ClipData.newRawUri("image", uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "Share Track Card"))
                        onDismiss()
                    }
                }
            }
        }
    }
}

@Composable
private fun ShareAction(
    label: String,
    icon: ImageVector,
    accent: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val background = when {
        accent -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val foreground = when {
        accent -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(background.copy(alpha = if (enabled) 1f else 0.4f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = foreground,
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.W700,
            color = foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun shareToInstagram(context: Context, uri: Uri, song: Song) {
    val shareText = "${song.title} - ${song.artist}\nhttps://music.youtube.com/watch?v=${song.videoId}"
    val songUrl = "https://music.youtube.com/watch?v=${song.videoId}"

    // Explicitly grant read permission to Instagram package for the FileProvider URI
    runCatching {
        context.grantUriPermission(
            "com.instagram.android",
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    }

    val storyIntent = Intent("com.instagram.share.ADD_TO_STORY").apply {
        setDataAndType(uri, MIME)
        putExtra("background_asset_uri", uri)
        putExtra("source_application", context.packageName)
        putExtra("content_url", songUrl)
        putExtra("top_background_color", "#121212")
        putExtra("bottom_background_color", "#121212")
        clipData = android.content.ClipData.newRawUri("image", uri)
        setPackage("com.instagram.android")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    val directIntent = Intent(Intent.ACTION_SEND).apply {
        type = MIME
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TEXT, shareText)
        clipData = android.content.ClipData.newRawUri("image", uri)
        setPackage("com.instagram.android")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    val launched = runCatching {
        context.startActivity(storyIntent)
        true
    }.getOrElse {
        runCatching {
            context.startActivity(directIntent)
            true
        }.getOrDefault(false)
    }

    if (!launched) {
        val genericIntent = Intent(Intent.ACTION_SEND).apply {
            type = MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, shareText)
            clipData = android.content.ClipData.newRawUri("image", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(genericIntent, "Share Track Card"))
    }
}

private fun shareToWhatsApp(context: Context, uri: Uri, song: Song) {
    val shareText = "${song.title} - ${song.artist}\nhttps://music.youtube.com/watch?v=${song.videoId}"

    runCatching {
        context.grantUriPermission("com.whatsapp", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.grantUriPermission("com.whatsapp.w4b", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    val whatsappIntent = Intent(Intent.ACTION_SEND).apply {
        type = MIME
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TEXT, shareText)
        clipData = android.content.ClipData.newRawUri("image", uri)
        setPackage("com.whatsapp")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    val whatsappBusinessIntent = Intent(Intent.ACTION_SEND).apply {
        type = MIME
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TEXT, shareText)
        clipData = android.content.ClipData.newRawUri("image", uri)
        setPackage("com.whatsapp.w4b")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    val launched = runCatching {
        context.startActivity(whatsappIntent)
        true
    }.getOrElse {
        runCatching {
            context.startActivity(whatsappBusinessIntent)
            true
        }.getOrDefault(false)
    }

    if (!launched) {
        val genericIntent = Intent(Intent.ACTION_SEND).apply {
            type = MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, shareText)
            clipData = android.content.ClipData.newRawUri("image", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(genericIntent, "Share Track Card"))
    }
}

private suspend fun cacheForSharing(context: Context, bitmap: Bitmap): Uri? =
    withContext(Dispatchers.IO) {
        runCatching {
            val folder = File(context.cacheDir, SHARE_FOLDER).apply { mkdirs() }

            // Clean up temporary shared story PNGs older than 24 hours
            val dayOld = System.currentTimeMillis() - (24 * 60 * 60 * 1000L)
            folder.listFiles()?.forEach { oldFile ->
                if (oldFile.lastModified() < dayOld) {
                    runCatching { oldFile.delete() }
                }
            }

            val file = File(folder, "song_card_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull()
    }

private suspend fun saveToGallery(context: Context, bitmap: Bitmap, title: String): Uri? =
    withContext(Dispatchers.IO) {
        val filename = "BitChord_${title.replace(Regex("[^a-zA-Z0-9]"), "_")}_${System.currentTimeMillis()}.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, MIME)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/BitChord")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext null
        runCatching {
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            uri
        }.getOrNull()
    }
