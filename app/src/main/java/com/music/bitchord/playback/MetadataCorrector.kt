package com.music.bitchord.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import java.util.LinkedHashMap

/**
 * Ensures that track metadata (title, artist, artwork) remains stable and
 * correct even when the underlying audio source is substituted or upgraded.
 */
object MetadataCorrector {
    private val canonicalMetadata = object : LinkedHashMap<String, MediaMetadata>(0, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, MediaMetadata>) = size > 256
    }

    /**
     * Records the canonical metadata for a track.
     */
    @Synchronized
    fun remember(mediaId: String, metadata: MediaMetadata) {
        if (metadata.title != null || metadata.artist != null) {
            canonicalMetadata[mediaId] = metadata
        }
    }

    /**
     * Applies the remembered canonical metadata to [item].
     */
    @Synchronized
    fun correct(item: MediaItem): MediaItem {
        val canonical = canonicalMetadata[item.mediaId] ?: return item
        val current = item.mediaMetadata

        if (current.title?.toString() == canonical.title?.toString() &&
            current.artist?.toString() == canonical.artist?.toString() &&
            current.artworkUri == canonical.artworkUri
        ) {
            return item
        }

        val corrected = current.buildUpon()
            .setTitle(canonical.title ?: current.title)
            .setArtist(canonical.artist ?: current.artist)
            .setAlbumTitle(canonical.albumTitle ?: current.albumTitle)
            .setArtworkUri(canonical.artworkUri ?: current.artworkUri)
            .setExtras(canonical.extras ?: current.extras)
            .build()

        return item.buildUpon().setMediaMetadata(corrected).build()
    }

    @Synchronized
    fun clean(mediaId: String) {
        canonicalMetadata.remove(mediaId)
    }
}
