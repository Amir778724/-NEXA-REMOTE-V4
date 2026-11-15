package com.nexa.remote

import android.os.Bundle
import androidx.activity.ComponentActivity
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HomeScreen()
                }
            }
        }
    }
}

@Composable
private fun HomeScreen() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("NEXA REMOTE", style = MaterialTheme.typography.headlineLarge)
        Text(
            "اتصال امن با اجازه صاحب دستگاه",
            modifier = Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.bodyLarge
        )
        Button(
            onClick = { /* Screen sharing will be added with Android consent flow. */ },
            modifier = Modifier.padding(top = 28.dp)
        ) {
            Text("شروع اتصال")
        }
        Text(
            "نسخه اولیه پروژه؛ قابلیت اشتراک صفحه در مرحله بعد اضافه می‌شود.",
            modifier = Modifier.padding(top = 20.dp),
            style = MaterialTheme.typography.bodySmall
        )
    }
}
