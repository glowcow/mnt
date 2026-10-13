package dev.glowcow.mnt.net

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The places on the network the user has added, passwords with them: one file in the app's own
 * storage, sealed with a key that never leaves the phone's keystore.
 */
class Connections(context: Context) {
    private val file = File(context.filesDir, "connections")
    private val json = Json { ignoreUnknownKeys = true }

    private val _list = MutableStateFlow(load())
    val list: StateFlow<List<Connection>> = _list

    fun find(id: String): Connection? = _list.value.firstOrNull { it.id == id }

    /** Keeps [connection], in the place of the one with its id when there is one. */
    @Synchronized
    fun save(connection: Connection) {
        val kept = _list.value
        store(if (kept.any { it.id == connection.id }) kept.map { if (it.id == connection.id) connection else it } else kept + connection)
    }

    @Synchronized
    fun remove(id: String) = store(_list.value.filterNot { it.id == id })

    private fun store(list: List<Connection>) {
        val cipher = Cipher.getInstance(AES_GCM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        file.writeBytes(cipher.iv + cipher.doFinal(json.encodeToString(list).toByteArray()))
        _list.value = list
    }

    // A file that cannot be opened — one brought over from another phone — is as good as none.
    private fun load(): List<Connection> = runCatching {
        val sealed = file.readBytes()
        val cipher = Cipher.getInstance(AES_GCM).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES)) }
        json.decodeFromString<List<Connection>>(cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES).decodeToString())
    }.getOrDefault(emptyList())

    private fun key(): SecretKey {
        val keys = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keys.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "connections"
        const val AES_GCM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
