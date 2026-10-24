package cz.kubat.auto.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.kubat.auto.App
import cz.kubat.auto.BuildConfigInfo
import cz.kubat.auto.data.AppLog
import cz.kubat.auto.sync.Api
import cz.kubat.auto.sync.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val NEEDED_PERMISSIONS = arrayOf(
    Manifest.permission.BLUETOOTH_CONNECT,
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.POST_NOTIFICATIONS,
)

fun hasPermission(context: Context, p: String) =
    ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

@SuppressLint("MissingPermission")
private fun deviceLabel(d: BluetoothDevice) = "${d.name ?: "(no name)"}  ${d.address}"

@SuppressLint("MissingPermission", "BatteryLife")
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val s = App.instance.settings
    val scope = rememberCoroutineScope()
    val btAdapter = remember { context.getSystemService(BluetoothManager::class.java).adapter }

    var refresh by remember { mutableIntStateOf(0) } // bump to re-read permissions / bonded devices
    var server by remember { mutableStateOf(s.serverUrl) }
    var token by remember { mutableStateOf(s.token) }
    var price by remember { mutableStateOf(s.fuelPrice.toString()) }
    var adapter by remember { mutableStateOf(s.adapterAddress) }
    var result by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    val found = remember { mutableStateListOf<BluetoothDevice>() }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        AppLog.i("Permissions: " + res.entries.joinToString { "${it.key.substringAfterLast('.')}=${it.value}" })
        refresh++
    }
    val canConnect = refresh >= 0 && hasPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
    val canScan = refresh >= 0 && hasPermission(context, Manifest.permission.BLUETOOTH_SCAN)

    val bonded: List<BluetoothDevice> = if (canConnect && refresh >= 0) {
        try {
            btAdapter.bondedDevices.toList().sortedBy { if ((it.name ?: "").contains("OBD", true)) 0 else 1 }
        } catch (e: SecurityException) {
            emptyList()
        }
    } else emptyList()

    // Discovery + bonding events
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
                        if (found.none { it.address == d.address } && d.bondState != BluetoothDevice.BOND_BONDED) {
                            found += d
                            AppLog.i("Scan: found ${deviceLabel(d)}")
                        }
                    }
                    android.bluetooth.BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        scanning = false
                        AppLog.i("Scan: finished, ${found.size} new device(s)")
                    }
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        val d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
                        val state = when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                            BluetoothDevice.BOND_BONDED -> "BONDED"
                            BluetoothDevice.BOND_BONDING -> "BONDING"
                            BluetoothDevice.BOND_NONE -> "NONE"
                            else -> "?"
                        }
                        AppLog.i("Pair: ${deviceLabel(d)} -> $state")
                        if (state == "BONDED") {
                            found.removeAll { it.address == d.address }
                            if (s.adapterAddress.isEmpty() || (d.name ?: "").contains("OBD", true)) {
                                s.adapterAddress = d.address
                                adapter = d.address
                                AppLog.i("Adapter selected: ${d.address}")
                            }
                        }
                        refresh++
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(android.bluetooth.BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        onDispose {
            context.unregisterReceiver(receiver)
            if (scanning) try { btAdapter.cancelDiscovery() } catch (_: SecurityException) {}
        }
    }

    Column(
        Modifier.fillMaxSize().background(Palette.bg).verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        Section("Permissions")
        val missing = NEEDED_PERMISSIONS.filter { refresh >= 0 && !hasPermission(context, it) }
        if (missing.isEmpty()) {
            Text("All permissions granted", color = Palette.ok, fontSize = 14.sp)
        } else {
            Text("Missing: " + missing.joinToString { it.substringAfterLast('.') }, color = Palette.warm, fontSize = 14.sp)
            Button(onClick = {
                AppLog.i("Permissions: requesting ${missing.joinToString { it.substringAfterLast('.') }}")
                permissionLauncher.launch(NEEDED_PERMISSIONS)
            }) { Text("Grant permissions") }
        }

        Section("OBD adapter")
        if (!btAdapter.isEnabled) Text("Bluetooth is off", color = Palette.hot, fontSize = 14.sp)
        if (canConnect && bonded.isEmpty()) Text("No paired devices yet — scan below.", color = Palette.muted, fontSize = 14.sp)
        for (d in bonded) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = adapter == d.address, onClick = {
                    adapter = d.address
                    s.adapterAddress = d.address
                    AppLog.i("Adapter selected: ${deviceLabel(d)}")
                })
                Text(deviceLabel(d), color = Palette.text)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(enabled = canScan && canConnect && !scanning, onClick = {
                found.clear()
                scanning = btAdapter.startDiscovery()
                AppLog.i("Scan: started=$scanning")
            }) { Text(if (scanning) "Scanning…" else "Scan for adapter", color = Palette.text) }
            Spacer(Modifier.padding(4.dp))
            Text("Ignition on, adapter plugged in", color = Palette.muted, fontSize = 12.sp)
        }
        for (d in found) {
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(deviceLabel(d), color = Palette.text, modifier = Modifier.weight(1f))
                Button(onClick = {
                    try { btAdapter.cancelDiscovery() } catch (_: SecurityException) {}
                    val ok = d.createBond()
                    AppLog.i("Pair: createBond(${d.address}) = $ok — enter PIN 1234 when asked")
                }) { Text("Pair") }
            }
        }

        Section("Server")
        OutlinedTextField(server, { server = it }, label = { Text("Server URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            token, { token = it }, label = { Text("Device token") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row {
            Button(onClick = {
                s.serverUrl = server
                s.token = token
                result = "Testing…"
                scope.launch {
                    result = withContext(Dispatchers.IO) {
                        try {
                            val ping = Api(s.serverUrl, s.token).request("GET", "/api/v1/ping")
                            SyncWorker.fetchConfig(App.instance)
                            AppLog.i("Server OK: device ${ping.optString("device")}")
                            SyncWorker.runNow(context)
                            "OK — device \"${ping.optString("device")}\", map key ${if (s.mapyKey.isNotEmpty()) "loaded" else "missing"}"
                        } catch (e: Exception) {
                            AppLog.e("Server test failed", e)
                            "Failed: ${e.message}"
                        }
                    }
                }
            }) { Text("Save & test") }
            Spacer(Modifier.padding(4.dp))
            OutlinedButton(onClick = { SyncWorker.runNow(context); result = "Sync queued (also uploads the log)" }) {
                Text("Sync now", color = Palette.text)
            }
        }
        if (result.isNotEmpty()) Text(result, color = Palette.muted, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))

        Section("Fuel price")
        OutlinedTextField(
            price, { price = it; it.replace(',', '.').toDoubleOrNull()?.let { v -> s.fuelPrice = v } },
            label = { Text("Kč per litre") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
        )

        Section("Background")
        val pm = context.getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(context.packageName)) {
            Text("Battery optimisation: off for this app (good)", color = Palette.ok, fontSize = 14.sp)
        } else {
            OutlinedButton(onClick = {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                )
            }) { Text("Allow recording in background", color = Palette.text) }
        }

        Section("Log (uploaded to the server on sync)")
        val lines by AppLog.lines.collectAsStateWithLifecycle()
        val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
        Column(Modifier.fillMaxWidth().background(Palette.panel, RoundedCornerShape(8.dp)).padding(8.dp)) {
            if (lines.isEmpty()) Text("(empty)", color = Palette.muted, fontSize = 12.sp)
            for (l in lines.takeLast(60).reversed()) {
                Text(
                    "${fmt.format(Date((l.t * 1000).toLong()))} ${l.level} ${l.msg}",
                    color = when (l.level) { "E" -> Palette.hot; "W" -> Palette.warm; else -> Palette.text },
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        Text("Version ${BuildConfigInfo.versionName(context)} · read-only OBD (never clears codes)", color = Palette.muted, fontSize = 12.sp)
    }
}

@Composable
private fun Section(title: String) {
    Text(title, color = Palette.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
}
