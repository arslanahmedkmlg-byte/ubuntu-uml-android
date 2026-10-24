package com.eaglenet.ubuntuuml
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = getSharedPreferences("uml_settings", MODE_PRIVATE)
        setContent {
            var ramMiB by remember {
                mutableIntStateOf(preferences.getInt("ram_mib", 2048).coerceIn(512, 4096))
            }
            MaterialTheme(colorScheme = darkColorScheme()) {
                Scaffold { insets ->
                    Column(
                        modifier = Modifier.fillMaxSize().padding(insets).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        Text("Ubuntu UML", style = MaterialTheme.typography.headlineLarge)
                        Text("VM memory: ${ramMiB} MiB")
                        Slider(
                            value = ramMiB.toFloat(),
                            onValueChange = { value ->
                                val steps = ((value.toInt() + 256) / 512).coerceIn(1, 8)
                                ramMiB = steps * 512
                                preferences.edit().putInt("ram_mib", ramMiB).apply()
                            },
                            valueRange = 512f..4096f,
                            steps = 6
                        )
                        Text("Memory changes apply on the next Ubuntu start. Stop the VM first.")
                        Button(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Back to terminal")
                        }
                    }
                }
            }
        }
    }
}
