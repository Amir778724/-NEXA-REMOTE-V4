package com.nexa.remote

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import libXray.LibXray
import java.security.SecureRandom

class MainActivity : ComponentActivity() {
    private var pendingServerUrl = ""
    private var pendingPairCode = ""
    private val visiblePairCode = mutableStateOf("")
    private var pendingXrayConfig = ""

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
            } else visiblePairCode.value = ""
        }

    private val vpnConsentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) startVpnService(pendingXrayConfig)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("nexa_user_configs", MODE_PRIVATE)
        val saved = prefs.getString("links", "[]") ?: "[]"
        val initialLinks = runCatching {
            val array = JSONArray(saved)
            List(array.length()) { index -> array.getString(index) }
        }.getOrDefault(emptyList())

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = androidx.compose.ui.graphics.Color(0xFF5B8CFF),
                    secondary = androidx.compose.ui.graphics.Color(0xFF9B6BFF),
                    background = androidx.compose.ui.graphics.Color(0xFF10121B),
                    surface = androidx.compose.ui.graphics.Color(0xFF191D2B)
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var links by remember { mutableStateOf(initialLinks) }
                    var newLink by remember { mutableStateOf("") }
                    var selected by remember { mutableIntStateOf(0) }
                    var message by remember { mutableStateOf("") }
                    var connected by remember { mutableStateOf(false) }
                    var page by remember { mutableIntStateOf(0) }

                    fun saveLinks(values: List<String>) {
                        links = values
                        prefs.edit().putString("links", JSONArray(values).toString()).apply()
                        if (selected >= values.size) selected = (values.size - 1).coerceAtLeast(0)
                    }

                    Column(
                        modifier = Modifier.fillMaxSize().padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("NEXA", style = MaterialTheme.typography.headlineLarge)
                        Text("REMOTE  •  VPN", color = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.height(18.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { page = 0 }, modifier = Modifier.weight(1f), enabled = page != 0) { Text("۱. کانفیگ‌ها") }
                            Button(onClick = { page = 1 }, modifier = Modifier.weight(1f), enabled = page != 1) { Text("۲. اتصال") }
                        }
                        if (page == 0) {
                        Text("کانفیگ‌های من", style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(
                            value = newLink,
                            onValueChange = { newLink = it; message = "" },
                            label = { Text("لینک VLESS خودت را وارد کن") },
                            placeholder = { Text("vless://...") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2
                        )
                        Button(
                            onClick = {
                                val link = newLink.trim()
                                if (!link.startsWith("vless://", ignoreCase = true)) {
                                    message = "لینک باید با vless:// شروع شود."
                                } else if (links.contains(link)) {
                                    message = "این کانفیگ قبلاً اضافه شده است."
                                } else {
                                    saveLinks(links + link)
                                    newLink = ""
                                    message = "کانفیگ به فهرست شخصی تو اضافه شد."
                                }
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        ) { Text("افزودن کانفیگ") }

                        if (message.isNotBlank()) {
                            Text(message, color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.padding(top = 6.dp))
                        }
                        Spacer(Modifier.height(12.dp))
                        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            itemsIndexed(links) { index, link ->
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (selected == index)
                                            MaterialTheme.colorScheme.surfaceVariant
                                        else MaterialTheme.colorScheme.surface
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(selected == index, onClick = { selected = index })
                                        Column(Modifier.weight(1f)) {
                                            Text("کانفیگ ${index + 1}")
                                            Text(link.substringAfter("://").take(34),
                                                style = MaterialTheme.typography.bodySmall)
                                        }
                                        TextButton(onClick = {
                                            saveLinks(links.filterIndexed { i, _ -> i != index })
                                            message = "کانفیگ حذف شد."
                                        }) { Text("حذف") }
                                    }
                                }
                            }
                        }
                        Button(onClick = { page = 1 }, enabled = links.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                            Text("رفتن به صفحه اتصال")
                        }
                        } else {
                        Text("اتصال", style = MaterialTheme.typography.titleLarge)
                        Text(
                            if (selected in links.indices) "کانفیگ انتخاب‌شده: ${selected + 1}" else "هنوز کانفیگی انتخاب نشده",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            if (connected) "وضعیت: در حال اتصال" else "وضعیت: قطع",
                            color = if (connected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = {
                                if (selected !in links.indices) {
                                    message = "اول یک کانفیگ اضافه کن."
                                } else {
                                    val result = makeXrayConfig(links[selected])
                                    if (result.first.isBlank()) message = result.second
                                    else {
                                        pendingXrayConfig = result.first
                                        val consent = VpnService.prepare(this@MainActivity)
                                        if (consent != null) vpnConsentLauncher.launch(consent)
                                        else startVpnService(pendingXrayConfig)
                                        connected = true
                                        message = "درخواست اتصال ارسال شد."
                                    }
                                }
                            },
                            enabled = !connected && links.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        ) { Text("اتصال VPN") }
                        OutlinedButton(
                            onClick = {
                                startService(Intent(this@MainActivity, NexaVpnService::class.java).apply {
                                    action = NexaVpnService.ACTION_STOP
                                })
                                connected = false
                                message = "درخواست قطع اتصال ارسال شد."
                            },
                            enabled = connected,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("قطع اتصال") }

                        HorizontalDivider(Modifier.padding(vertical = 12.dp))
                        Text("NEXA REMOTE", style = MaterialTheme.typography.titleMedium)
                        RemoteControls(
                            pairingCode = visiblePairCode.value,
                            onStartCapture = { url, code ->
                                pendingServerUrl = url
                                pendingPairCode = code
                                visiblePairCode.value = code
                                requestScreenCapture()
                            },
                            onStopCapture = { stopScreenCapture() },
                            onEnableRemoteTaps = {
                                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }
                        )
                        Text(
                            "برای اتصال VPN اجازه اندروید را تأیید کن. اشتراک صفحه ریموت نیز با اجازه جداگانه شروع می‌شود.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        }
                    }
                }
            }
        }
    }

    private fun makeXrayConfig(link: String): Pair<String, String> {
        return try {
            val request = JSONObject()
                .put("apiVersion", 3)
                .put("method", "convertShareLinksToXrayJson")
                .put("payload", JSONObject().put("text", link))
            val response = JSONObject(LibXray.invoke(request.toString()))
            if (!response.optBoolean("success")) {
                return "" to (response.optString("error").ifBlank { "خواندن کانفیگ ناموفق بود." })
            }
            val parsed = response.getJSONObject("data").getJSONArray("outbounds")
            if (parsed.length() == 0) return "" to "کانفیگ معتبر پیدا نشد."
            val outbounds = JSONArray()
            for (i in 0 until parsed.length()) {
                val outbound = parsed.getJSONObject(i)
                if (i == 0) outbound.put("tag", "proxy")
                outbounds.put(outbound)
            }
            outbounds.put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
            val inbound = JSONObject()
                .put("tag", "tun-in")
                .put("port", 0)
                .put("protocol", "tun")
                .put("settings", JSONObject().put("name", "NEXA").put("MTU", 1500))
            val config = JSONObject()
                .put("log", JSONObject().put("loglevel", "warning"))
                .put("env", JSONObject().put("xray.tun.fd", "0"))
                .put("inbounds", JSONArray().put(inbound))
                .put("outbounds", outbounds)
                .put("routing", JSONObject().put("domainStrategy", "IPIfNonMatch")
                    .put("rules", JSONArray().put(
                        JSONObject().put("type", "field")
                            .put("inboundTag", JSONArray().put("tun-in"))
                            .put("outboundTag", "proxy")
                    )))
            config.toString() to ""
        } catch (e: Exception) {
            "" to (e.message ?: "خطا در آماده‌سازی کانفیگ")
        }
    }

    private fun startVpnService(config: String) {
        val intent = Intent(this, NexaVpnService::class.java).apply {
            action = NexaVpnService.ACTION_START
            putExtra(NexaVpnService.EXTRA_CONFIG, config)
        }
        if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(intent)
        else startService(intent)
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
private fun RemoteControls(
    pairingCode: String,
    onStartCapture: (String, String) -> Unit,
    onStopCapture: () -> Unit,
    onEnableRemoteTaps: () -> Unit
) {
    var serverUrl by remember { mutableStateOf("wss://nexa-remote-relay.hsnzadhamyrmhmd784.workers.dev/signal") }
    var error by remember { mutableStateOf("") }
    OutlinedTextField(
        value = serverUrl,
        onValueChange = { serverUrl = it.trim(); error = "" },
        label = { Text("آدرس سرور امن") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Button(onClick = {
        if (!serverUrl.startsWith("wss://")) error = "آدرس باید با wss:// شروع شود."
        else onStartCapture(serverUrl, (10000000 + SecureRandom().nextInt(90000000)).toString())
    }, modifier = Modifier.fillMaxWidth()) { Text("شروع اشتراک صفحه") }
    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    if (pairingCode.isNotBlank()) Text("کد اتصال: $pairingCode")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onEnableRemoteTaps) { Text("فعال‌سازی لمس از راه دور") }
        TextButton(onClick = onStopCapture) { Text("توقف اشتراک صفحه") }
    }
}
