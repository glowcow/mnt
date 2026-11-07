package dev.glowcow.mnt.net

import kotlinx.serialization.Serializable

/** How a place on the network is spoken to. */
enum class Protocol(val port: Int) { SMB(445), FTP(21), S3(0), NFS(2049) }

/**
 * A place on the network as the user described it. [share] is the share of an SMB server, the bucket of
 * an S3 one, the export of an NFS one, the folder to start in of an FTP one; [user] and [password] are
 * the access key and the secret of S3 and the numbers of the user and the group, `1000:1000`, of NFS.
 */
@Serializable
data class Connection(
    val id: String,
    val protocol: Protocol,
    val name: String = "",
    val host: String = "",
    /** 0 for the protocol's own. */
    val port: Int = 0,
    val share: String = "",
    val user: String = "",
    val password: String = "",
    /** TLS: FTPS for FTP, https for S3. */
    val secure: Boolean = false,
    val region: String = "",
    /** The fingerprint of the FTPS certificate trusted, kept from the first time the server was reached. */
    val pin: String = "",
) {
    /** What the place goes by: the name it was given, or where it is. */
    val title: String get() = name.ifBlank { listOf(host.ifBlank { protocol.name }, share.trim('/')).filter { it.isNotEmpty() }.joinToString("/") }

    /** Whether enough is told to try it. */
    val complete: Boolean
        get() = when (protocol) {
            Protocol.SMB, Protocol.NFS -> host.isNotBlank() && share.isNotBlank()
            Protocol.FTP -> host.isNotBlank()
            Protocol.S3 -> share.isNotBlank() && user.isNotBlank() && password.isNotBlank()
        }

    /** The store that reaches the place; [onPin] keeps the certificate trusted on the way. */
    fun store(onPin: (String) -> Unit = {}): RemoteStore = when (protocol) {
        Protocol.SMB -> SmbStore(this)
        Protocol.FTP -> FtpStore(this, onPin)
        Protocol.S3 -> S3Store(this)
        Protocol.NFS -> NfsStore(this)
    }
}
