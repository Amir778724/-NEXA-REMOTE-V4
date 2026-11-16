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
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import org.json.JSONArray
import org.json.JSONObject
import libXray.LibXray
import java.security.SecureRandom
import java.security.MessageDigest

private const val REMOTE_ACCESS_PASSWORD_SHA256 = "32b10a5c90d269fc80b9424a444268725a57ab068bd0d2a47d430a69b114e0d3"

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
            var isDarkTheme by remember { mutableStateOf(prefs.getBoolean("dark_theme", true)) }
            var appLanguage by remember { mutableStateOf(prefs.getString("app_language", "fa") ?: "fa") }
            MaterialTheme(
                colorScheme = if (isDarkTheme) darkColorScheme(
                    primary = androidx.compose.ui.graphics.Color(0xFF35D6C8),
                    secondary = androidx.compose.ui.graphics.Color(0xFF9B7BFF),
                    background = androidx.compose.ui.graphics.Color(0xFF0B0F18),
                    surface = androidx.compose.ui.graphics.Color(0xFF151C29),
                    surfaceVariant = androidx.compose.ui.graphics.Color(0xFF202A3A)
                ) else lightColorScheme(
                    primary = androidx.compose.ui.graphics.Color(0xFF087F78),
                    secondary = androidx.compose.ui.graphics.Color(0xFF6650A4),
                    background = androidx.compose.ui.graphics.Color(0xFFF5F7FA),
                    surface = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
                    surfaceVariant = androidx.compose.ui.graphics.Color(0xFFE8EDF3)
                ),
                shapes = Shapes(
                    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                    small = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                    medium = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                    large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var links by remember { mutableStateOf(initialLinks) }
                    var newLink by remember { mutableStateOf("") }
                    var selected by remember { mutableIntStateOf(0) }
                    var message by remember { mutableStateOf("") }
                    var connected by remember { mutableStateOf(false) }
                    var showMenu by remember { mutableStateOf(false) }
                    var showSettings by remember { mutableStateOf(false) }
                    var showRemoteSettings by remember { mutableStateOf(false) }
                    var showRemotePasswordDialog by remember { mutableStateOf(false) }
                    var remotePasswordInput by remember { mutableStateOf("") }
                    var remotePasswordError by remember { mutableStateOf("") }
                    var settingsDialogTitle by remember { mutableStateOf("") }
                    var settingsDialogBody by remember { mutableStateOf("") }
                    var showAppearanceDialog by remember { mutableStateOf(false) }
                    var showLanguageDialog by remember { mutableStateOf(false) }

                    fun saveLinks(values: List<String>) {
                        links = values
                        prefs.edit().putString("links", JSONArray(values).toString()).apply()
                        if (selected >= values.size) selected = (values.size - 1).coerceAtLeast(0)
                    }

                    Column(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("NEXA CONFIG", style = MaterialTheme.typography.headlineMedium,
                                    color = MaterialTheme.colorScheme.primary)
                                Text(
                                    if (showRemoteSettings) "تنظیمات  /  ریموت" else if (showSettings) "تنظیمات" else "CONFIG  •  VPN",
                                    color = MaterialTheme.colorScheme.secondary,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                            Box {
                                TextButton(onClick = { showMenu = true }) {
                                    Text("⋮", style = MaterialTheme.typography.headlineMedium,
                                        color = MaterialTheme.colorScheme.primary)
                                }
                                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Settings") },
                                        onClick = { showMenu = false; showSettings = true; showRemoteSettings = false }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("صفحه اصلی") },
                                        onClick = { showMenu = false; showSettings = false; showRemoteSettings = false }
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(14.dp))

                        if (!showSettings) {
                            Text("کانفیگ‌های من", style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = newLink,
                                onValueChange = { newLink = it; message = "" },
                                label = { Text("لینک VLESS را وارد کن") },
                                placeholder = { Text("vless://...") },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2,
                                shape = MaterialTheme.shapes.medium
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
                                        message = "کانفیگ به فهرست اضافه شد."
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                            ) { Text("افزودن کانفیگ") }

                            if (message.isNotBlank()) {
                                Text(message, color = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.padding(top = 6.dp))
                            }

                            Spacer(Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("انتخاب کانفیگ", style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f))
                                Text("${links.size} مورد", color = MaterialTheme.colorScheme.secondary,
                                    style = MaterialTheme.typography.labelMedium)
                            }
                            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                itemsIndexed(links) { index, link ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (selected == index)
                                                MaterialTheme.colorScheme.surfaceVariant
                                            else MaterialTheme.colorScheme.surface
                                        ),
                                        border = if (selected == index)
                                            androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                                        else null
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            RadioButton(selected == index, onClick = { selected = index })
                                            Column(Modifier.weight(1f)) {
                                                Text("کانفیگ ${index + 1}")
                                                Text(link.substringAfter("://").take(34),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            TextButton(onClick = {
                                                saveLinks(links.filterIndexed { i, _ -> i != index })
                                                message = "کانفیگ حذف شد."
                                            }) { Text("حذف") }
                                        }
                                    }
                                }
                            }

                            Card(
                                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                            ) {
                                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                    Text("اتصال VPN", style = MaterialTheme.typography.titleLarge)
                                    Text(
                                        if (connected) "وضعیت: درخواست اتصال ارسال شده"
                                        else "وضعیت: قطع",
                                        color = if (connected) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                    Button(
                                        onClick = {
                                            if (selected !in links.indices) {
                                                message = "اول یک کانفیگ انتخاب یا اضافه کن."
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
                                    ) { Text("وصل کردن VPN") }
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
                                    ) { Text("قطع کردن VPN") }
                                }
                            }
                        } else if (!showRemoteSettings) {
                            Text("تنظیمات برنامه", style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.fillMaxWidth())
                            Text("مدیریت بخش‌ها و تنظیمات NEXA", color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp))
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                item {
                                    Text("عمومی", style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.primary)
                                    Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                            TextButton(onClick = { showAppearanceDialog = true }, modifier = Modifier.fillMaxWidth()) {
                                                Column(Modifier.fillMaxWidth()) { Text("ظاهر برنامه"); Text(if (isDarkTheme) "تیره" else "روشن", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                            }
                                            Divider()
                                            TextButton(onClick = { showLanguageDialog = true }, modifier = Modifier.fillMaxWidth()) {
                                                Column(Modifier.fillMaxWidth()) { Text("زبان برنامه"); Text(if (appLanguage == "fa") "فارسی" else "English", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                            }
                                            Divider()
                                            TextButton(onClick = { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) }, modifier = Modifier.fillMaxWidth()) {
                                                Column(Modifier.fillMaxWidth()) { Text("اعلان‌ها"); Text("مدیریت اعلان‌های برنامه", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                            }
                                        }
                                    }
                                }
                                item {
                                    Text("اتصال و شبکه", style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.primary)
                                    Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                            TextButton(onClick = { runCatching { startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }.onFailure { settingsDialogTitle = "تنظیمات VPN"; settingsDialogBody = "صفحه تنظیمات VPN در این دستگاه در دسترس نیست." } }, modifier = Modifier.fillMaxWidth()) {
                                                Column(Modifier.fillMaxWidth()) { Text("تنظیمات VPN"); Text("باز کردن تنظیمات VPN اندروید", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                            }
                                            Divider()
                                            TextButton(onClick = {
                                                val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                                                val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
                                                settingsDialogTitle = "وضعیت شبکه"
                                                settingsDialogBody = when {
                                                    caps == null -> "اینترنت متصل نیست."
                                                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "متصل از طریق Wi‑Fi"
                                                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "متصل از طریق اینترنت همراه"
                                                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "متصل از طریق Ethernet"
                                                    else -> "شبکه متصل است."
                                                }
                                            }, modifier = Modifier.fillMaxWidth()) {
                                                Column(Modifier.fillMaxWidth()) { Text("وضعیت شبکه"); Text("نمایش نوع اتصال اینترنت", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                            }
                                        }
                                    }
                                }
                                item {
                                    Text("امنیت و حریم خصوصی", style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.primary)
                                    Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                            TextButton(onClick = { settingsDialogTitle = "قفل برنامه"; settingsDialogBody = "قفل اختصاصی برنامه در این نسخه فعال نیست؛ برای محافظت از برنامه، قفل صفحه گوشی را فعال نگه دارید." }, modifier = Modifier.fillMaxWidth()) {
                                                Column(Modifier.fillMaxWidth()) { Text("قفل برنامه"); Text("وضعیت محافظت از بخش‌های حساس", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                            }
                                            Divider()
                                            TextButton(onClick = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }, modifier = Modifier.fillMaxWidth()) {
                                                Column(Modifier.fillMaxWidth()) { Text("درباره مجوزها"); Text("مشاهده و مدیریت مجوزهای برنامه", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                            }
                                        }
                                    }
                                }
                                item {
                                    Text("ابزارهای پیشرفته", style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.primary)
                                    Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 18.dp),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                            TextButton(onClick = {
                                                val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName ?: "نامشخص" }.getOrDefault("نامشخص")
                                                settingsDialogTitle = "اطلاعات نسخه"
                                                settingsDialogBody = "NEXA CONFIG\nنسخه: $version"
                                            }, modifier = Modifier.fillMaxWidth()) {
                                                Column(Modifier.fillMaxWidth()) { Text("اطلاعات نسخه"); Text("NEXA CONFIG", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                            }
                                            Divider()
                                            TextButton(onClick = { showRemotePasswordDialog = true },
                                                modifier = Modifier.fillMaxWidth()) {
                                                Column(Modifier.fillMaxWidth()) {
                                                    Text("کد برنامه", style = MaterialTheme.typography.titleMedium,
                                                        color = MaterialTheme.colorScheme.primary)
                                                    Text("ورود امن به بخش اتصال و کنترل از راه دور",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            if (showAppearanceDialog) {
                                AlertDialog(
                                    onDismissRequest = { showAppearanceDialog = false },
                                    title = { Text("ظاهر برنامه") },
                                    text = {
                                        Column {
                                            TextButton(onClick = { isDarkTheme = true; prefs.edit().putBoolean("dark_theme", true).apply(); showAppearanceDialog = false }) { Text(if (isDarkTheme) "✓ تیره" else "تیره") }
                                            TextButton(onClick = { isDarkTheme = false; prefs.edit().putBoolean("dark_theme", false).apply(); showAppearanceDialog = false }) { Text(if (!isDarkTheme) "✓ روشن" else "روشن") }
                                        }
                                    },
                                    confirmButton = { TextButton(onClick = { showAppearanceDialog = false }) { Text("بستن") } }
                                )
                            }
                            if (showLanguageDialog) {
                                AlertDialog(
                                    onDismissRequest = { showLanguageDialog = false },
                                    title = { Text("زبان برنامه") },
                                    text = {
                                        Column {
                                            TextButton(onClick = { appLanguage = "fa"; prefs.edit().putString("app_language", "fa").apply(); showLanguageDialog = false }) { Text(if (appLanguage == "fa") "✓ فارسی" else "فارسی") }
                                            TextButton(onClick = { appLanguage = "en"; prefs.edit().putString("app_language", "en").apply(); showLanguageDialog = false }) { Text(if (appLanguage == "en") "✓ English" else "English") }
                                        }
                                    },
                                    confirmButton = { TextButton(onClick = { showLanguageDialog = false }) { Text("بستن") } }
                                )
                            }
                            if (settingsDialogTitle.isNotBlank()) {
                                AlertDialog(
                                    onDismissRequest = { settingsDialogTitle = ""; settingsDialogBody = "" },
                                    title = { Text(settingsDialogTitle) },
                                    text = { Text(settingsDialogBody) },
                                    confirmButton = { TextButton(onClick = { settingsDialogTitle = ""; settingsDialogBody = "" }) { Text("باشه") } }
                                )
                            }
                            if (showRemotePasswordDialog) {
                                AlertDialog(
                                    onDismissRequest = { showRemotePasswordDialog = false; remotePasswordInput = ""; remotePasswordError = "" },
                                    title = { Text("ورود به کد برنامه") },
                                    text = {
                                        Column {
                                            OutlinedTextField(
                                                value = remotePasswordInput,
                                                onValueChange = { remotePasswordInput = it; remotePasswordError = "" },
                                                label = { Text("رمز عبور") },
                                                singleLine = true,
                                                visualTransformation = PasswordVisualTransformation()
                                            )
                                            if (remotePasswordError.isNotBlank()) {
                                                Text(remotePasswordError, color = MaterialTheme.colorScheme.error,
                                                    style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    },
                                    confirmButton = {
                                        TextButton(onClick = {
                                            val enteredHash = MessageDigest.getInstance("SHA-256")
                                                .digest(remotePasswordInput.toByteArray())
                                                .joinToString("") { "%02x".format(it) }
                                            if (enteredHash == REMOTE_ACCESS_PASSWORD_SHA256) {
                                                showRemotePasswordDialog = false
                                                remotePasswordInput = ""
                                                remotePasswordError = ""
                                                showRemoteSettings = true
                                            } else remotePasswordError = "رمز عبور اشتباه است."
                                        }) { Text("ورود") }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showRemotePasswordDialog = false; remotePasswordInput = ""; remotePasswordError = "" }) {
                                            Text("انصراف")
                                        }
                                    }
                                )
                            }
                        } else {
                            Text("NEXA REMOTE", style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.fillMaxWidth())
                            Text("کد اتصال و نمایش صفحه گوشی", style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp))
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
