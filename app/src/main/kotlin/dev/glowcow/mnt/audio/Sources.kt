package dev.glowcow.mnt.audio

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import dev.glowcow.mnt.files.FileStore
import kotlinx.coroutines.runBlocking
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/** Where the player reads: a file of any place through `mnt:`, the web through the player's own sources. */
@OptIn(UnstableApi::class)
class Sources(private val context: Context, private val files: FileStore) : DataSource.Factory {
    override fun createDataSource(): DataSource = Either(StoreDataSource(files), DefaultDataSource.Factory(context).createDataSource())

    private class Either(private val store: DataSource, private val web: DataSource) : DataSource {
        private var open: DataSource? = null

        override fun addTransferListener(listener: TransferListener) {
            store.addTransferListener(listener)
            web.addTransferListener(listener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val source = if (dataSpec.uri.scheme == Sounds.SCHEME) store else web
            open = source
            return source.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = open!!.read(buffer, offset, length)

        override fun getUri(): Uri? = open?.uri

        override fun getResponseHeaders(): Map<String, List<String>> = open?.responseHeaders.orEmpty()

        override fun close() {
            open?.close()
            open = null
        }
    }
}

/** A file of the phone, of a server or of an archive, read from where the player asks: a jump opens it again there. */
@OptIn(UnstableApi::class)
class StoreDataSource(private val files: FileStore) : BaseDataSource(true) {
    private var input: InputStream? = null
    private var uri: Uri? = null
    private var left = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val path = Sounds.path(dataSpec.uri)
        val size = try {
            runBlocking { files.stat(path) }?.size
        } catch (e: IOException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        } ?: throw DataSourceException(FileNotFoundException(path), PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        if (dataSpec.position > size) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        input = try {
            files.read(path, dataSpec.position)
        } catch (e: IOException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        }
        left = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else size - dataSpec.position
        opened = true
        transferStarted(dataSpec)
        return left
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (left == 0L) return C.RESULT_END_OF_INPUT
        val n = try {
            input!!.read(buffer, offset, minOf(length.toLong(), left).toInt())
        } catch (e: IOException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        }
        if (n < 0) return C.RESULT_END_OF_INPUT
        left -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            input?.close()
        } catch (_: IOException) {
        } finally {
            input = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}
