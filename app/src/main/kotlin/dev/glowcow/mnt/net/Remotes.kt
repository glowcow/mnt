package dev.glowcow.mnt.net

import dev.glowcow.mnt.files.FileStore
import dev.glowcow.mnt.files.Remote
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlin.concurrent.thread

/** A place outside the phone's file system as the Files tab lists it: a server, or a device that speaks MTP. */
data class Mount(
    val id: String,
    val title: String,
    /** How it is spoken to; null for MTP. */
    val protocol: Protocol?,
    /** Where it is, for the second line of its row. */
    val detail: String,
    /** False for a device the user has not yet let the app talk to. */
    val ready: Boolean,
) {
    val path: String get() = Remote.root(id)
}

/** Every place that is not the phone's own file system, and the store that reaches each. */
class Remotes(private val connections: Connections, private val mtp: MtpDevices) {
    private val stores = HashMap<String, Pair<Connection, RemoteStore>>()

    val mounts: Flow<List<Mount>> = combine(mtp.ports, connections.list) { ports, kept ->
        ports.map { Mount(it.id, it.name, null, "MTP", it.ready) } + kept.map { Mount(it.id, it.title, it.protocol, it.where(), true) }
    }

    /** The store of the place [id]; null when there is no such place, or it may not be talked to. */
    @Synchronized
    fun store(id: String): FileStore? {
        val connection = connections.find(id) ?: return mtp.store(id)
        stores[id]?.let { (made, store) ->
            if (made == connection) return store
            drop(store)
        }
        return connection.store().also { stores[id] = connection to it }
    }

    /** What the place [id] goes by; null when there is no such place. */
    fun title(id: String): String? = connections.find(id)?.title ?: mtp.ports.value.firstOrNull { it.id == id }?.name

    /** Why the place [id] last could not do what was asked of it. */
    fun error(id: String): String? = (store(id) as? RemoteStore)?.error

    @Synchronized
    fun forget(id: String) {
        stores.remove(id)?.second?.let(::drop)
        connections.remove(id)
    }

    // Letting go of a connection says goodbye over the network, which is not for the main thread.
    private fun drop(store: RemoteStore) {
        thread(name = "remote-close") { store.close() }
    }

    private fun Connection.where(): String = listOf(protocol.name, host.ifBlank { null }, share.trim('/').ifEmpty { null }).filterNotNull().let { parts ->
        parts.first() + if (parts.size > 1) " | " + parts.drop(1).joinToString("/") else ""
    }
}
