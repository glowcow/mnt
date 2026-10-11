package dev.glowcow.mnt.files

import android.content.ContentResolver
import android.content.Context
import android.os.Bundle
import android.provider.MediaStore

/** The system's index of the shared storage, asked for what the file system does not keep. */
class MediaIndex(private val context: Context) {
    /** When the file at [path] came to the storage, in milliseconds; null when the index has no such time for it. */
    fun added(path: String): Long? = runCatching {
        val query = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.DATA} = ?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(path))
            // A file still marked as being written or as thrown away is a file all the same.
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
        }
        context.contentResolver.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), arrayOf(MediaStore.MediaColumns.DATE_ADDED), query, null)
            ?.use { found -> if (found.moveToFirst() && !found.isNull(0)) found.getLong(0) * 1000 else null }
    }.getOrNull()
}
