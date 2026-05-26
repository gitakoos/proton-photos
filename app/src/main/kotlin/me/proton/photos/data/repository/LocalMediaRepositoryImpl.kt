package me.proton.photos.data.repository

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import me.proton.photos.domain.entity.LocalMediaItem
import me.proton.photos.domain.repository.LocalMediaRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalMediaRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : LocalMediaRepository {

    // Manual refresh trigger merged with the MediaStore ContentObserver. Permission grants do
    // not fire onChange, so without this the gallery would stay empty after first grant until
    // the user restarts the app.
    private val refreshTrigger = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override fun notifyPermissionChanged() {
        refreshTrigger.tryEmit(Unit)
    }

    override fun observeLocalMedia(): Flow<List<LocalMediaItem>> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }

        context.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer
        )
        context.contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer
        )

        trySend(Unit) // initial query

        awaitClose {
            context.contentResolver.unregisterContentObserver(observer)
        }
    }.let { contentChanges ->
        merge(contentChanges, refreshTrigger.asSharedFlow())
            .let { allTriggers ->
                flow {
                    allTriggers.collect {
                        emit(queryAllMedia())
                    }
                }
            }
    }

    override fun observeTrashedMedia(): Flow<List<LocalMediaItem>> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return flowOf(emptyList())
        return callbackFlow {
            suspend fun emit() { trySend(queryTrashedMedia()) }
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) { trySend(Unit) }
            }
            context.contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer
            )
            context.contentResolver.registerContentObserver(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer
            )
            emit()
            awaitClose { context.contentResolver.unregisterContentObserver(observer) }
        }.let { changes ->
            flow { changes.collect { emit(queryTrashedMedia()) } }
        }
    }

    override fun hasMediaPermission(): Flow<Boolean> = flowOf(checkMediaPermission())

    override suspend fun queryByUri(uri: String): LocalMediaItem? = withContext(Dispatchers.IO) {
        val parsedUri = Uri.parse(uri)
        // App-private hidden file (file://...) — synthesize the LocalMediaItem from the file.
        if (parsedUri.scheme == "file") {
            val file = parsedUri.path?.let { java.io.File(it) } ?: return@withContext null
            if (!file.exists()) return@withContext null
            val ext = file.extension.lowercase()
            val mime = when (ext) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "heic", "heif" -> "image/heic"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                "mp4", "m4v" -> "video/mp4"
                "mov" -> "video/quicktime"
                "webm" -> "video/webm"
                "mkv" -> "video/x-matroska"
                "avi" -> "video/x-msvideo"
                else -> ""
            }
            return@withContext LocalMediaItem(
                uri         = uri,
                dateTaken   = file.lastModified(),
                displayName = file.name,
                mimeType    = mime,
                sizeBytes   = file.length(),
                bucketName  = "Hidden",
                width       = 0,
                height      = 0,
                duration    = 0L,
            )
        }
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.DURATION,
        )
        try {
            // parsedUri is already an item URI (e.g. content://media/external/images/media/12345).
            // We must pass the COLLECTION URI as baseUri — NOT the item URI — so that
            // toLocalMediaItem can reconstruct the correct content URI from the cursor's _ID.
            // Using the item URI directly causes Uri.withAppendedPath(itemUri, id) to produce
            // a double-ID path (".../12345/12345") which openInputStream cannot resolve.
            val collectionUri: Uri? = when {
                parsedUri.toString().contains("/video/") ->
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                parsedUri.toString().contains("/images/") ->
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                else -> null
            }
            context.contentResolver.query(parsedUri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.toLocalMediaItem(baseUri = collectionUri) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun queryAllMedia(): List<LocalMediaItem> = withContext(Dispatchers.IO) {
        if (!checkMediaPermission()) return@withContext emptyList()

        val result = mutableListOf<LocalMediaItem>()

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.IS_PENDING,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.DURATION,
        )

        val selection = "${MediaStore.MediaColumns.IS_PENDING} = 0"
        val sortOrder = "${MediaStore.MediaColumns.DATE_TAKEN} DESC, ${MediaStore.MediaColumns.DATE_ADDED} DESC"

        for (uri in listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        )) {
            try {
                context.contentResolver.query(uri, projection, selection, null, sortOrder)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        result += cursor.toLocalMediaItem(baseUri = uri)
                    }
                }
            } catch (e: Exception) {
                // Skip inaccessible URIs
            }
        }

        result.sortedByDescending { it.dateTaken }
    }

    private suspend fun queryTrashedMedia(): List<LocalMediaItem> = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@withContext emptyList()
        if (!checkMediaPermission()) return@withContext emptyList()

        val result = mutableListOf<LocalMediaItem>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.DURATION,
        )
        val queryArgs = android.os.Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
        }

        for (baseUri in listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        )) {
            try {
                context.contentResolver.query(baseUri, projection, queryArgs, null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        result += cursor.toLocalMediaItem(baseUri = baseUri)
                    }
                }
            } catch (_: Exception) {}
        }
        result.sortedByDescending { it.dateTaken }
    }

    private fun checkMediaPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // On Android 13+, images and videos are separate permissions; grant if at least one is.
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun android.database.Cursor.toLocalMediaItem(baseUri: Uri? = null): LocalMediaItem {
        val idCol        = getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        val dateTakenCol = getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
        val dateAddedCol = getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
        val nameCol      = getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
        val mimeCol      = getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
        val sizeCol      = getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
        val bucketCol    = getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
        val widthCol     = getColumnIndex(MediaStore.MediaColumns.WIDTH)
        val heightCol    = getColumnIndex(MediaStore.MediaColumns.HEIGHT)
        val durationCol  = getColumnIndex(MediaStore.MediaColumns.DURATION)

        val id = getLong(idCol)
        val rawDateTaken = getLong(dateTakenCol)
        val dateAdded    = getLong(dateAddedCol)
        val dateTaken    = if (rawDateTaken > 0) rawDateTaken else dateAdded * 1000L
        val root         = baseUri ?: MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val contentUri   = Uri.withAppendedPath(root, id.toString())

        val displayName  = getString(nameCol) ?: ""
        val rawMimeType  = getString(mimeCol) ?: ""
        val mimeType     = if (rawMimeType.isNotEmpty()) rawMimeType else {
            when (displayName.substringAfterLast('.').lowercase()) {
                "mp4", "m4v", "3gp", "ts"  -> "video/mp4"
                "mov"                        -> "video/quicktime"
                "avi"                        -> "video/x-msvideo"
                "mkv"                        -> "video/x-matroska"
                "webm"                       -> "video/webm"
                "jpg", "jpeg"               -> "image/jpeg"
                "png"                        -> "image/png"
                "gif"                        -> "image/gif"
                "webp"                       -> "image/webp"
                "heic", "heif"               -> "image/heic"
                else                         -> ""
            }
        }

        return LocalMediaItem(
            uri         = contentUri.toString(),
            dateTaken   = dateTaken,
            displayName = displayName,
            mimeType    = mimeType,
            sizeBytes   = getLong(sizeCol),
            bucketName  = if (bucketCol >= 0) getString(bucketCol) else null,
            width       = if (widthCol >= 0) getInt(widthCol) else 0,
            height      = if (heightCol >= 0) getInt(heightCol) else 0,
            duration    = if (durationCol >= 0) getLong(durationCol) else 0L,
        )
    }
}
