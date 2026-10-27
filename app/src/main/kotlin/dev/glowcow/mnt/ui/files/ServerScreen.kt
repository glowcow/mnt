package dev.glowcow.mnt.ui.files

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.glowcow.mnt.AppContainer
import dev.glowcow.mnt.MntApp
import dev.glowcow.mnt.R
import dev.glowcow.mnt.net.Connection
import dev.glowcow.mnt.net.Protocol
import dev.glowcow.mnt.ui.components.ChoiceSheet
import dev.glowcow.mnt.ui.components.DetailScreen
import dev.glowcow.mnt.ui.components.Group
import dev.glowcow.mnt.ui.components.GroupDivider
import dev.glowcow.mnt.ui.components.GroupField
import dev.glowcow.mnt.ui.components.GroupRow
import dev.glowcow.mnt.ui.components.PillButton
import dev.glowcow.mnt.ui.components.SwitchRow
import dev.glowcow.mnt.ui.components.rememberLocalNetwork
import dev.glowcow.mnt.ui.theme.MntTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** What trying a server came to: still asking, an answer, or the server's own words for why not. */
sealed interface Checked {
    data object Running : Checked

    data object Fine : Checked

    class Failed(val reason: String) : Checked
}

class ServerViewModel(id: String?, private val c: AppContainer) : ViewModel() {
    /** The server as the page has it now; a new one starts as an SMB share. */
    val draft = MutableStateFlow(id?.let { c.connections.find(it) } ?: Connection(UUID.randomUUID().toString().take(8), Protocol.SMB))

    /** How the last try went; null before one and after anything was changed. */
    val checked = MutableStateFlow<Checked?>(null)
    private var checking: Job? = null

    fun change(to: Connection) {
        checking?.cancel()
        checked.value = null
        draft.value = to
    }

    /** Asks the server for its top folder, with what the page holds and without keeping it. */
    fun check() {
        checking?.cancel()
        checked.value = Checked.Running
        checking = viewModelScope.launch {
            val store = draft.value.store()
            val listed = store.list("/")
            checked.value = if (listed != null) Checked.Fine else Checked.Failed(store.error.orEmpty())
            c.haptics.done(listed != null)
            withContext(Dispatchers.IO) { store.close() }
        }
    }

    fun save() = c.connections.save(draft.value)
}

/** A server to add or to change: how it is spoken to, where it is, who asks. */
@Composable
fun ServerScreen(id: String?, onBack: () -> Unit, vm: ServerViewModel = viewModel { ServerViewModel(id, (this[APPLICATION_KEY] as MntApp).container) }) {
    val draft by vm.draft.collectAsStateWithLifecycle()
    val checked by vm.checked.collectAsStateWithLifecycle()
    var choosing by rememberSaveable { mutableStateOf(false) }
    val protocol = draft.protocol
    val network = rememberLocalNetwork()

    DetailScreen(
        stringResource(if (id == null) R.string.server_new else R.string.server),
        onBack,
        actions = {
            // The pill ends where the blocks below do.
            Box(Modifier.padding(end = 12.dp)) {
                PillButton(stringResource(R.string.server_save), enabled = draft.complete) {
                    vm.save()
                    onBack()
                }
            }
        },
    ) {
        Group { GroupRow(stringResource(R.string.server_protocol), value = protocol.name, onClick = { choosing = true }) }
        Group {
            GroupField(stringResource(R.string.name_label), draft.name, { vm.change(draft.copy(name = it)) }, placeholder = draft.copy(name = "").title, capitalize = true)
            GroupDivider()
            GroupField(
                stringResource(if (protocol == Protocol.S3) R.string.server_endpoint else R.string.server_host),
                draft.host,
                { vm.change(draft.copy(host = it)) },
                placeholder = if (protocol == Protocol.S3) "Amazon S3" else null,
                keyboard = KeyboardType.Uri,
            )
            GroupDivider()
            // NFS finds its port by asking the server.
            if (protocol != Protocol.NFS) {
                GroupField(
                    stringResource(R.string.server_port),
                    draft.port.takeIf { it > 0 }?.toString().orEmpty(),
                    { typed -> vm.change(draft.copy(port = typed.filter(Char::isDigit).take(5).toIntOrNull()?.coerceAtMost(65535) ?: 0)) },
                    placeholder = (if (protocol == Protocol.S3) if (draft.secure) 443 else 80 else protocol.port).toString(),
                    keyboard = KeyboardType.Number,
                )
                GroupDivider()
            }
            GroupField(
                stringResource(
                    when (protocol) {
                        Protocol.SMB -> R.string.server_share
                        Protocol.FTP -> R.string.server_folder
                        Protocol.S3 -> R.string.server_bucket
                        Protocol.NFS -> R.string.server_export
                    },
                ),
                draft.share,
                { vm.change(draft.copy(share = it)) },
                placeholder = if (protocol == Protocol.NFS) "/export/files" else null,
            )
        }
        Group {
            if (protocol == Protocol.NFS) {
                GroupField(stringResource(R.string.server_ids), draft.user, { vm.change(draft.copy(user = it)) }, placeholder = "1000:1000")
            } else {
                GroupField(
                    stringResource(if (protocol == Protocol.S3) R.string.server_access_key else R.string.server_user),
                    draft.user,
                    { vm.change(draft.copy(user = it)) },
                    placeholder = when (protocol) {
                        Protocol.SMB -> stringResource(R.string.server_guest)
                        Protocol.FTP -> "anonymous"
                        else -> null
                    },
                )
                GroupDivider()
                GroupField(
                    stringResource(if (protocol == Protocol.S3) R.string.server_secret_key else R.string.backup_password),
                    draft.password,
                    { vm.change(draft.copy(password = it)) },
                    secret = true,
                )
            }
            if (protocol == Protocol.S3) {
                GroupDivider()
                GroupField(stringResource(R.string.server_region), draft.region, { vm.change(draft.copy(region = it)) }, placeholder = "us-east-1")
            }
        }
        if (protocol == Protocol.FTP || protocol == Protocol.S3) {
            Group {
                SwitchRow(
                    stringResource(R.string.server_secure),
                    if (protocol == Protocol.FTP) stringResource(R.string.server_secure_ftp) else "HTTPS",
                    draft.secure,
                ) { vm.change(draft.copy(secure = it)) }
            }
        }
        if (protocol == Protocol.NFS) Hint(stringResource(R.string.server_nfs_note))
        Group {
            GroupRow(
                stringResource(R.string.server_check),
                onClick = if (draft.complete && checked != Checked.Running) ({ network(vm::check) }) else null,
            )
        }
        when (val result = checked) {
            null -> Unit
            Checked.Running -> Hint(stringResource(R.string.server_checking))
            Checked.Fine -> Hint(stringResource(R.string.server_ok))
            is Checked.Failed -> Hint(listOf(stringResource(R.string.server_failed), result.reason).filter { it.isNotEmpty() }.joinToString(": "))
        }
    }

    if (choosing) {
        ChoiceSheet(
            stringResource(R.string.server_protocol),
            Protocol.entries.map { it to it.label },
            protocol,
            // Amazon's own takes nothing but https, so that is where S3 starts.
            { picked -> vm.change(draft.copy(protocol = picked, port = 0, secure = picked == Protocol.S3)) },
        ) { choosing = false }
    }
}

/** A line under a block that tells what the block came to, or what it needs. */
@Composable
private fun Hint(text: String) {
    Text(text, color = MntTheme.colors.muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp))
}

// What a protocol is called and where it is met; the names are the same in every language.
private val Protocol.label: String
    get() = when (this) {
        Protocol.SMB -> "SMB — Windows, Samba, NAS"
        Protocol.FTP -> "FTP, FTPS"
        Protocol.S3 -> "S3 — Amazon, MinIO"
        Protocol.NFS -> "NFS"
    }
