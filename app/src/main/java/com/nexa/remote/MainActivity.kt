package com.nexa.remote

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                val serviceIntent = Intent(this, ScreenShareService::class.java).apply {
                    action = ScreenShareService.ACTION_START
                    putExtra(ScreenShareService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenShareService.EXTRA_RESULT_DATA, data)
                }
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HomeScreen(
                        onStartCapture = { requestScreenCapture() },
                        onStopCapture = { stopScreenCapture() }
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
    }
}

@Composable
private fun HomeScreen(
    onStartCapture: () -> Unit,
    onStopCapture: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("NEXA REMOTE", style = MaterialTheme.typography.headlineLarge)
        Text(
            "اشتراک صفحه فقط با تایید صاحب دستگاه",
            modifier = Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.bodyLarge
        )
        Button(
            onClick = onStartCapture,
            modifier = Modifier.padding(top = 28.dp)
        ) {
            Text("اجازه اشتراک صفحه")
        }
        Button(
            onClick = onStopCapture,
            modifier = Modifier.padding(top = 10.dp)
        ) {
            Text("توقف اشتراک صفحه")
        }
        Text(
            "برای شروع، پنجره رسمی اجازه اندروید را تایید کنید. اعلان دائمی امکان توقف اشتراک را می‌دهد.",
            modifier = Modifier.padding(top = 20.dp),
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            "در این نسخه ضبط امن صفحه آماده است؛ اتصال شبکه و کنترل از دستگاه دوم هنوز تکمیل نشده است.",
            modifier = Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.bodySmall
        )
    }
}
