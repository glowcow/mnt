package dev.glowcow.mnt

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.glowcow.mnt.data.AppSettings
import dev.glowcow.mnt.ui.MntRoot
import dev.glowcow.mnt.ui.components.TopTab
import dev.glowcow.mnt.ui.theme.MntTheme
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

class MainActivity : ComponentActivity() {

    private val openTab = Channel<TopTab>(Channel.BUFFERED)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)

        val tabRequests = openTab.receiveAsFlow()
        setContent {
            // Nothing is drawn until the stored settings are read: the theme is one of them.
            val stored by container.settings.settings.collectAsStateWithLifecycle<AppSettings?>(null)
            val settings = stored ?: return@setContent
            MntTheme(settings.theme, settings.palette) { MntRoot(tabRequests) }
        }
    }

    override fun onResume() {
        super.onResume()
        container.haptics.show(window.decorView)
    }

    override fun onPause() {
        container.haptics.show(null)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    // A tap on a notification leads to where it can be acted on: the settings for a new version, the files for a transfer, the player.
    private fun handleIntent(intent: Intent?) {
        if (intent?.hasExtra(EXTRA_APP_UPDATE) == true) openTab.trySend(TopTab.SETTINGS)
        if (intent?.hasExtra(EXTRA_TRANSFER) == true) openTab.trySend(TopTab.FILES)
        if (intent?.hasExtra(EXTRA_PLAYER) == true) container.playback.show()
    }

    companion object {
        const val EXTRA_APP_UPDATE = "app_update"
        const val EXTRA_TRANSFER = "transfer"
        const val EXTRA_PLAYER = "player"
    }
}
