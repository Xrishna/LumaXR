package com.lumaxr.player.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumaxr.player.BuildConfig
import com.lumaxr.player.hardware.RefreshRatePolicy
import com.lumaxr.player.pro.NetworkProtocol
import com.lumaxr.player.pro.NetworkServerConfig
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NetworkAndSettingsScreen(
    viewModel: MainViewModel,
    onPlayRemoteUrl: (String, String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backStyle by viewModel.backStyle.collectAsState()
    val refreshPolicy by viewModel.refreshPolicy.collectAsState()
    val experimentalHbm by viewModel.experimentalHbm.collectAsState()
    val servers by viewModel.networkServers.collectAsState()
    val remoteEntriesByServer by viewModel.remoteEntriesByServer.collectAsState()
    val backupStatus by viewModel.backupStatus.collectAsState()

    LaunchedEffect(backupStatus) {
        val msg = backupStatus
        if (!msg.isNullOrBlank()) {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.clearBackupStatus()
        }
    }

    var hostInput by rememberSaveable { mutableStateOf("192.168.1.10") }
    var shareInput by rememberSaveable { mutableStateOf("Media") }
    var userInput by rememberSaveable { mutableStateOf("") }
    var passInput by rememberSaveable { mutableStateOf("") }
    var proto by rememberSaveable { mutableStateOf(NetworkProtocol.SMB) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) {
            viewModel.exportBackup(uri)
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.importBackup(uri)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(top = 86.dp, bottom = 160.dp, start = 20.dp, end = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("LumaXR Pro Calibration & Network", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("Build Package: ${BuildConfig.BUILD_APP_ID} • Target: ${BuildConfig.TARGET_DEVICE_MODEL} (${BuildConfig.TARGET_DEVICE_CODENAME})", color = Color(0xFF94A3B8), fontSize = 12.sp)

        // Display & Experimental HBM
        Surface(color = Color(0xFF111827), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Display, Cadence & Motion", color = Color(0xFF38BDF8), fontWeight = FontWeight.SemiBold)
                Text("Refresh-Rate Cadence Policy", color = Color.White, fontSize = 13.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RefreshRatePolicy.entries.forEach { pol ->
                        FilterChip(
                            selected = refreshPolicy == pol,
                            onClick = { viewModel.updateRefreshPolicy(pol) },
                            label = { Text(pol.name, fontSize = 11.sp) },
                        )
                    }
                }

                Text("Android 16 Predictive Back Style", color = Color.White, fontSize = 13.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PredictiveBackStyle.entries.forEach { st ->
                        FilterChip(
                            selected = backStyle == st,
                            onClick = { viewModel.setBackStyle(st) },
                            label = { Text(st.label, fontSize = 11.sp) },
                        )
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Experimental 600-Nit HBM HDR Headroom", color = Color.White, fontSize = 14.sp)
                        Text(
                            "Warning on ruan: HBM is sunlight-gated (minLux 6001.0) and may trigger HyperOS desiredRatio(10.00) elevated blacks.",
                            color = Color(0xFFFBBF24),
                            fontSize = 11.sp,
                        )
                    }
                    Switch(checked = experimentalHbm, onCheckedChange = { viewModel.setExperimentalHbm(it) })
                }
            }
        }

        // Config Backup & Restore
        Surface(color = Color(0xFF111827), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Config Backup & Restore (Survives Package ID Changes)", color = Color(0xFF38BDF8), fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { exportLauncher.launch("lumaxr_config_backup.zip") }) {
                        Text("Export JSON/ZIP")
                    }
                    OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/zip", "application/json")) }) {
                        Text("Import Backup")
                    }
                }
            }
        }

        // Network Streaming (SMB / WebDAV / Jellyfin / Emby / Plex)
        Surface(color = Color(0xFF111827), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("SMB / WebDAV / Jellyfin / Emby / Plex", color = Color(0xFF38BDF8), fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NetworkProtocol.entries.forEach { p ->
                        FilterChip(selected = proto == p, onClick = { proto = p }, label = { Text(p.name, fontSize = 11.sp) })
                    }
                }
                OutlinedTextField(value = hostInput, onValueChange = { hostInput = it }, label = { Text("Host / Server URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = shareInput, onValueChange = { shareInput = it }, label = { Text("Share / Library") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = userInput, onValueChange = { userInput = it }, label = { Text("User") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(
                        value = passInput,
                        onValueChange = { passInput = it },
                        label = { Text("Pass / API Token") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.weight(1f),
                    )
                }
                Button(
                    enabled = hostInput.isNotBlank(),
                    onClick = {
                        val srv = NetworkServerConfig(
                            id = UUID.randomUUID().toString(),
                            name = "${proto.name} (${hostInput.trim()})",
                            protocol = proto,
                            hostOrUrl = hostInput.trim(),
                            shareOrLibrary = shareInput.trim(),
                            username = userInput.trim(),
                            passwordOrToken = passInput.trim(),
                        )
                        viewModel.addNetworkServer(srv)
                        viewModel.browseNetworkServer(srv)
                    },
                ) {
                    Text("Connect & Browse")
                }

                servers.forEach { srv ->
                    Text("Server: ${srv.name}", color = Color(0xFFCBD5E1), fontSize = 12.sp)
                    val entries = remoteEntriesByServer[srv.id].orEmpty()
                    entries.forEach { entry ->
                        Surface(
                            color = Color(0xFF1E293B),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (entry.isDirectory) {
                                        viewModel.browseNetworkServer(srv, entry.path)
                                    } else {
                                        scope.launch {
                                            val url = viewModel.container.networkMediaRepo.openPlayableStreamUrl(srv, entry)
                                            onPlayRemoteUrl(url, entry.name)
                                        }
                                    }
                                },
                        ) {
                            Text(
                                text = (if (entry.isDirectory) "📁 " else "🎬 ") + entry.name,
                                color = Color.White,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
