package cz.kubat.auto.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.kubat.auto.App
import cz.kubat.auto.data.AppLog
import cz.kubat.auto.record.Link
import cz.kubat.auto.record.RecorderService

class MainActivity : ComponentActivity() {

    private val permissions = NEEDED_PERMISSIONS

    private var startAfterGrant = false
    private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        AppLog.i("Permissions: " + res.entries.joinToString { "${it.key.substringAfterLast('.')}=${it.value}" })
        if (startAfterGrant) startRecording()
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun startRecording() {
        if (!granted(Manifest.permission.BLUETOOTH_CONNECT) || !granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            AppLog.w("Start refused: missing Nearby devices or Location permission")
            Toast.makeText(this, "Needs Nearby devices + precise Location permission", Toast.LENGTH_LONG).show()
            return
        }
        RecorderService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (!permissions.all { granted(it) }) requestPermissions.launch(permissions) // ask once at first launch
        val colors = darkColorScheme(
            primary = Palette.ok, background = Palette.bg, surface = Palette.panel,
            onBackground = Palette.text, onSurface = Palette.text,
        )
        setContent {
            MaterialTheme(colorScheme = colors) {
                val live by App.instance.live.state.collectAsStateWithLifecycle()
                var tab by rememberSaveable { mutableIntStateOf(0) }

                LaunchedEffect(live.link) {
                    if (live.link == Link.STOPPED) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }

                Scaffold(
                    containerColor = Palette.bg,
                    bottomBar = {
                        NavigationBar(containerColor = Palette.panel) {
                            NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Filled.Home, null) }, label = { Text("Drive") })
                            NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Filled.DateRange, null) }, label = { Text("Trips") })
                            NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Filled.Build, null) }, label = { Text("Sensors") })
                            NavigationBarItem(tab == 3, { tab = 3 }, { Icon(Icons.Filled.Settings, null) }, label = { Text("Settings") })
                        }
                    },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        when (tab) {
                            0 -> DriveScreen(
                                live,
                                onStart = {
                                    if (permissions.all { granted(it) }) startRecording()
                                    else { startAfterGrant = true; requestPermissions.launch(permissions) }
                                },
                                onStop = { RecorderService.stop(this@MainActivity) },
                            )
                            1 -> TripsScreen()
                            2 -> SensorsScreen(live)
                            else -> SettingsScreen()
                        }
                    }
                }
            }
        }
    }
}
