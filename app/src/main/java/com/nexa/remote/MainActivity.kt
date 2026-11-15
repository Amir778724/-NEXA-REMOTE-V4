package com.nexa.remote

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.security.SecureRandom

class MainActivity : ComponentActivity() {
    private var pendingServerUrl = ""
    private var pendingPairCode = ""
    private val visiblePairCode = mutableStateOf("")

    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                val serviceIntent = Intent(this, ScreenShareService::class.java).apply {
                    action = ScreenShareService.ACTION_START
                    putExtra(ScreenShareService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenShareService.EXTRA_RESULT_DATA, data)
                    putExtra(ScreenShareService.EXTRA_SERVER_URL, pendingServerUrl)
                    putExtra(ScreenShareService.EXTRA_PAIR_CODE, pendingPairCode)
                }
                if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent)
                else startService(serviceIntent)
            } else {
                visiblePairCode.value = ""
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HomeScreen(
                        pairingCode = visiblePairCode.value,
                        onStartCapture = { url, code ->
                            pendingServerUrl = url
                            pendingPairCode = code
                            visiblePairCode.value = code
                            requestScreenCapture()
                        },
                        onStopCapture = { stopScreenCapture() },
                        onEnableRemoteTaps = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    )
                }
            }
        }
    }

    private fun requestScreenCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun stopScreenCapture() {
        startService(Intent(this, ScreenShareService::class.java).apply {
            action = ScreenShareService.ACTION_STOP
        })
        visiblePairCode.value = ""
    }
}

@Composable
private fun HomeScreen(
    pairingCode: String,
    onStartCapture: (String, String) -> Unit,
    onStopCapture: () -> Unit,
    onEnableRemoteTaps: () -> Unit
) {
    var serverUrl by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("NEXA REMOTE", style = MaterialTheme.typography.headlineLarge)
        Text(
            "اشتراک زنده صفحه با اجازه صاحب دستگاه",
            modifier = Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.bodyLarge
        )
        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it.trim(); error = "" },
            label = { Text("آدرس سرور امن (WSS)") },
            placeholder = { Text("wss://your-server.onrender.com/signal") },
            singleLine = true,
            modifier = Modifier.padding(top = 24.dp)
        )
        Button(
            onClick = {
                if (!serverUrl.startsWith("wss://")) {
                    error = "آدرس سرور باید با wss:// شروع شود."
                } else {
                    val code = (10000000 + SecureRandom().nextInt(90000000)).toString()
                    onStartCapture(serverUrl, code)
                }
            },
            modifier = Modifier.padding(top = 16.dp)
        ) {
            Text("شروع اشتراک صفحه")
        }
        if (error.isNotBlank()) {
            Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        }
        if (pairingCode.isNotBlank()) {
            Text(
                "کد اتصال برای بیننده:",
                modifier = Modifier.padding(top = 20.dp),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                pairingCode,
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.headlineLarge
            )
        }
        Button(
            onClick = onEnableRemoteTaps,
            modifier = Modifier.padding(top = 12.dp)
        ) {
            Text("فعال‌سازی لمس از راه دور")
        }
        Button(
            onClick = onStopCapture,
            modifier = Modifier.padding(top = 10.dp)
        ) {
            Text("توقف اشتراک صفحه")
        }
        Text(
            "پس از زدن شروع، اجازه رسمی اندروید را تایید کنید. کد را فقط به فرد مورد اعتماد بدهید. برای لمس از راه دور، سرویس دسترس‌پذیری را خودتان در تنظیمات اندروید فعال کنید. اعلان دائمی و دکمه توقف همیشه در دسترس‌اند.",
            modifier = Modifier.padding(top = 18.dp),
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            "برای مشاهده تصویر، سرویس واسط باید روی سرور HTTPS فعال باشد و بیننده از صفحه وب استفاده کند.",
            modifier = Modifier.padding(top = 10.dp),
            style = MaterialTheme.typography.bodySmall
        )
    }
}
