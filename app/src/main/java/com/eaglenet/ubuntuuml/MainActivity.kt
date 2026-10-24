package com.eaglenet.ubuntuuml

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                var ram by remember { mutableFloatStateOf(2048f) }
                var disk by remember { mutableFloatStateOf(2f) }

                Scaffold { insets ->
                    Column(
                        modifier = Modifier.fillMaxSize()
                            .padding(insets)
                            .verticalScroll(rememberScrollState())
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        Text("Ubuntu 24.04", style = MaterialTheme.typography.headlineLarge)
                        Text("ARM64 · User-Mode Linux", color = MaterialTheme.colorScheme.primary)
                        HorizontalDivider()
                        Text("VM status: Not configured")

                        Text("Memory: ${ram.toInt()} MiB")
                        Slider(
                            value = ram,
                            onValueChange = { ram = it },
                            valueRange = 512f..4096f,
                            steps = 6
                        )

                        Text("Disk: ${disk.toInt()} GiB")
                        Slider(
                            value = disk,
                            onValueChange = { disk = it },
                            valueRange = 2f..16f,
                            steps = 13
                        )

                        Button(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Start Ubuntu (backend pending)")
                        }

                        OutlinedButton(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Open Terminal (backend pending)")
                        }

                        Text(
                            "Next: integrate UML kernel, Ubuntu rootfs, VM lifecycle and terminal.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}
