package com.eaglenet.ubuntuuml

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = getSharedPreferences("uml_settings", MODE_PRIVATE)
        val rootfs = File(filesDir, "uml/rootfs.ext4")
        ActiveVm.state.update { state ->
            state.copy(
                diskReady = rootfs.isFile && rootfs.length() > 128L * 1024L * 1024L,
                diskBytes = rootfs.takeIf { it.isFile }?.length() ?: 0,
                nativeRuntimeAvailable = UmlPtySession.nativeRuntimeAvailable(this),
                ramMiB = normalizeRamMiB(preferences.getInt("ram_mib", 2048)),
            )
        }
        setContent {
            val state = ActiveVm.state.value
            MaterialTheme(colorScheme = darkColorScheme()) {
                Scaffold { insets ->
                    Column(
                        modifier = Modifier.fillMaxSize()
                            .padding(insets)
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        Text("Ubuntu 24.04", style = MaterialTheme.typography.headlineLarge)
                        Text("ARM64 · User-Mode Linux", color = MaterialTheme.colorScheme.primary)
                        HorizontalDivider()
                        Text(
                            "VM status: ${if (state.running) "Running" else "Stopped"}",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(state.status, style = MaterialTheme.typography.bodySmall)
                        Text(
                            "Terminal: ${
                                when {
                                    state.terminalAvailable -> "Available"
                                    !state.diskReady -> "Import a rootfs from the terminal"
                                    else -> "UML runtime is not installed"
                                }
                            }",
                        )

                        Text("Memory: ${state.ramMiB} MiB")
                        Slider(
                            value = state.ramMiB.toFloat(),
                            onValueChange = { value ->
                                val ramMiB = normalizeRamMiB(value.toInt())
                                ActiveVm.state.update { it.copy(ramMiB = ramMiB) }
                                preferences.edit().putInt("ram_mib", ramMiB).apply()
                            },
                            valueRange = 512f..4096f,
                            steps = 6,
                            enabled = !state.running && !state.importing,
                        )

                        val diskSize = if (state.diskBytes > 0) {
                            String.format(
                                Locale.ROOT,
                                "%.2f GiB",
                                state.diskBytes / (1024.0 * 1024.0 * 1024.0),
                            )
                        } else {
                            "Not installed"
                        }
                        Text("Disk image: $diskSize")

                        Button(
                            onClick = { finishWithAction(VmSettingsAction.START) },
                            enabled = state.terminalAvailable && !state.running && !state.importing,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Start Ubuntu (systemd)")
                        }

                        OutlinedButton(
                            onClick = { finishWithAction(VmSettingsAction.RECOVERY) },
                            enabled = state.terminalAvailable && !state.running && !state.importing,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Recovery shell (emergency)")
                        }

                        OutlinedButton(
                            onClick = { finishWithAction(VmSettingsAction.STOP) },
                            enabled = state.running,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Stop Ubuntu")
                        }

                        OutlinedButton(
                            onClick = { finish() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Open terminal / import rootfs")
                        }
                    }
                }
            }
        }
    }

    private fun finishWithAction(action: String) {
        setResult(RESULT_OK, Intent().putExtra(VmSettingsAction.EXTRA_ACTION, action))
        finish()
    }
}
