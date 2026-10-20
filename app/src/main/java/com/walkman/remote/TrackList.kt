package com.walkman.remote

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.session.MediaSession
import android.net.Uri
import android.os.Bundle
import java.util.Locale

/** Один трек в списке (экран со списком песен). */
class TrackItem(
    val queueId: Long,
    val title: String,
    val uri: Uri?,
    var durationMs: Long,
    val trackNo: Int = 0,
    val year: Int = 0
) {
    // Название, обрезанное под ширину строки (запоминаем, чтобы не считать каждый кадр)
    var shownTitle: String? = null
}

/** Чтение очереди плеера, длительности и формата файла. */
object TrackLoader {

    private val durationKeys = arrayOf(
        "android.media.metadata.DURATION", "duration", "durationMs", "DURATION"
    )

    fun fromQueue(queue: List<MediaSession.QueueItem>?): List<TrackItem> {
        if (queue == null) return emptyList()
        val out = ArrayList<TrackItem>(queue.size)
        for ((i, q) in queue.withIndex()) {
            val d = q.description
            val title = d.title?.toString()?.takeIf { it.isNotBlank() } ?: "Track ${i + 1}"
            out.add(TrackItem(q.queueId, title, d.mediaUri, durationFromExtras(d.extras)))
        }
        return out
    }

    /**
     * Песни альбома из музыкальной библиотеки плеера (нужен доступ к музыке).
     * Используется, когда плеер Sony не отдаёт очередь воспроизведения.
     */
    fun fromAlbum(context: Context, album: String, artist: String?): List<TrackItem> {
        if (album.isBlank()) return emptyList()
        val pairs = ArrayList<Pair<TrackItem, String>>()
        val base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.ARTIST
        )
        try {
            val cursor = context.contentResolver.query(
                base,
                proj,
                MediaStore.Audio.Media.ALBUM + " = ?",
                arrayOf(album),
                MediaStore.Audio.Media.TRACK + " ASC, " + MediaStore.Audio.Media.TITLE + " ASC"
            )
            cursor?.use { c ->
                while (c.moveToNext()) {
                    val title = c.getString(1)
                    if (title.isNullOrBlank()) continue
                    val item = TrackItem(
                        -1L,
                        title,
                        ContentUris.withAppendedId(base, c.getLong(0)),
                        c.getLong(3),
                        c.getInt(2),
                        c.getInt(4)
                    )
                    pairs.add(Pair(item, c.getString(5) ?: ""))
                }
            }
        } catch (e: Exception) {
        }
        if (pairs.isEmpty()) return emptyList()

        // В разных альбомах бывает одинаковое название: оставляем песни нужного исполнителя
        val same = if (!artist.isNullOrBlank()) {
            pairs.filter { it.second.equals(artist, ignoreCase = true) }
        } else {
            emptyList()
        }
        return (if (same.isNotEmpty()) same else pairs).map { it.first }
    }

    private fun durationFromExtras(extras: Bundle?): Long {
        if (extras == null) return -1L
        for (k in durationKeys) {
            if (!extras.containsKey(k)) continue
            try {
                @Suppress("DEPRECATION")
                val v = extras.get(k)
                val n = when (v) {
                    is Long -> v
                    is Int -> v.toLong()
                    is String -> v.toLongOrNull() ?: -1L
                    else -> -1L
                }
                if (n > 0) return n
            } catch (e: Exception) {
            }
        }
        return -1L
    }

    /** Длительность файла в миллисекундах или -1, если прочитать нельзя. */
    fun readDuration(context: Context, uri: Uri): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1L
        } catch (e: Exception) {
            -1L
        } finally {
            try {
                r.release()
            } catch (e: Exception) {
            }
        }
    }

    /** Строка вида "FLAC 44,1 кГц / 16 бит" или null, если файл прочитать нельзя. */
    fun readFormat(context: Context, uri: Uri): String? {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(context, uri, null)
            if (ex.trackCount == 0) {
                null
            } else {
                val f = ex.getTrackFormat(0)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                val rate = if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) f.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 0

                // 2 = 16 бит, 3 = 8 бит, 4 = float, 21 = 24 бита, 22 = 32 бита
                val bits = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    when (f.getInteger(MediaFormat.KEY_PCM_ENCODING)) {
                        3 -> 8
                        2 -> 16
                        21 -> 24
                        22, 4 -> 32
                        else -> 0
                    }
                } else {
                    0
                }

                val codec = when {
                    mime.contains("flac") -> "FLAC"
                    mime.contains("mpeg") -> "MP3"
                    mime.contains("mp4a") || mime.contains("aac") -> "AAC"
                    mime.contains("alac") -> "ALAC"
                    mime.contains("opus") -> "OPUS"
                    mime.contains("vorbis") -> "OGG"
                    mime.contains("raw") || mime.contains("wav") -> "WAV"
                    mime.contains("dsd") -> "DSD"
                    else -> mime.substringAfter('/').uppercase(Locale.ROOT)
                }

                val sb = StringBuilder(codec)
                if (rate > 0) {
                    val khz = if (rate % 1000 == 0) {
                        (rate / 1000).toString()
                    } else {
                        String.format(Locale.US, "%.1f", rate / 1000.0).replace('.', ',')
                    }
                    sb.append(' ').append(khz).append(' ').append(Design.UNIT_KHZ)
                }
                if (bits > 0) sb.append(" / ").append(bits).append(' ').append(Design.UNIT_BIT)
                sb.toString()
            }
        } catch (e: Exception) {
            null
        } finally {
            try {
                ex.release()
            } catch (e: Exception) {
            }
        }
    }
}
