package dev.foldman

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import dev.foldman.deck.DeckViewModel
import dev.foldman.deck.Source
import dev.foldman.ui.FoldmanScreen

class MainActivity : ComponentActivity() {
    private val vm by lazy { ViewModelProvider(this)[DeckViewModel::class.java] }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        fitOrientation(resources.configuration)
        setContent {
            val windowLayout by remember { WindowInfoTracker.getOrCreate(this).windowLayoutInfo(this) }
                .collectAsStateWithLifecycle<WindowLayoutInfo?>(initialValue = null)
            val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                if (uri != null) {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    // Android caps how many folder permissions an app may hold; keep only this one.
                    contentResolver.persistedUriPermissions.filter { it.uri != uri }.forEach {
                        runCatching { contentResolver.releasePersistableUriPermission(it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    }
                    vm.setFolder(uri)
                }
            }
            val appleSignIn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                vm.onAppleSignInResult(result.data)
            }
            // Only for the media notification; playback works without it.
            val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            MaterialTheme(colorScheme = darkColorScheme()) {
                FoldmanScreen(
                    vm, windowLayout,
                    onPickFolder = { folderPicker.launch(null) },
                    onInstallApp = ::openStoreListing,
                    onAppleSignIn = { vm.appleSignInIntent()?.let(appleSignIn::launch) },
                )
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Folding and unfolding swaps screens without recreating the activity.
        fitOrientation(newConfig)
    }

    /**
     * Small screens (ordinary phones, a foldable's cover screen) stay upright: held sideways they
     * are too short for the deck layout, and the upright player is what they're designed for.
     * Big screens (an unfolded foldable, a tablet) turn freely.
     */
    private fun fitOrientation(config: Configuration) {
        val wanted = if (config.smallestScreenWidthDp < 600) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        if (requestedOrientation != wanted) requestedOrientation = wanted
    }

    override fun onStart() {
        super.onStart()
        vm.setForeground(true)
    }

    override fun onStop() {
        vm.setForeground(false)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // Notification access may have just been granted in Settings, or the streaming app started.
        vm.refresh()
    }

    private fun openStoreListing(source: Source) {
        val pkg = source.packageName ?: return
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
        runCatching { startActivity(market) }.onFailure {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")))
        }
    }
}
