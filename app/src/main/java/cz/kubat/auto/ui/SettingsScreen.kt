package cz.kubat.auto.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.kubat.auto.App
import cz.kubat.auto.BuildConfigInfo
import cz.kubat.auto.sync.Api
import cz.kubat.auto.sync.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SuppressLint("MissingPermission", "BatteryLife")
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val s = App.instance.settings
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf(s.serverUrl) }
    var token by remember { mutableStateOf(s.token) }
    var price by remember { mutableStateOf(s.fuelPrice.toString()) }
    var adapter by remember { mutableStateOf(s.adapterAddress) }
    var result by remember { mutableStateOf("") }

    val bonded: List<BluetoothDevice> = remember {
        try {
            context.getSystemService(BluetoothManager::class.java).adapter.bondedDevices.toList()
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    Column(
        Modifier.fillMaxSize().background(Palette.bg).verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        Section("OBD adapter")
        if (bonded.isEmpty()) {
            Text(
                "No paired devices visible. Pair the adapter (\"OBDII\", PIN 1234) in Android Bluetooth settings " +
                    "and allow the Nearby devices permission (tap Start on the Drive tab).",
                color = Palette.muted, fontSize = 14.sp,
            )
        }
        for (d in bonded) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = adapter == d.address, onClick = { adapter = d.address; s.adapterAddress = d.address })
                Text("${d.name ?: "?"}  ${d.address}", color = Palette.text)
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
                            SyncWorker.runNow(context)
                            "OK — device \"${ping.optString("device")}\", map key ${if (s.mapyKey.isNotEmpty()) "loaded" else "missing"}"
                        } catch (e: Exception) {
                            "Failed: ${e.message}"
                        }
                    }
                }
            }) { Text("Save & test") }
            Spacer(Modifier.padding(4.dp))
            OutlinedButton(onClick = { SyncWorker.runNow(context); result = "Sync queued" }) { Text("Sync now", color = Palette.text) }
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

        Spacer(Modifier.height(24.dp))
        Text("Version ${BuildConfigInfo.versionName(context)} · read-only OBD (never clears codes)", color = Palette.muted, fontSize = 12.sp)
    }
}

@Composable
private fun Section(title: String) {
    Text(title, color = Palette.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
}
