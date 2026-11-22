package com.nexa.remote

import android.content.Intent
import android.content.ClipboardManager
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.projection.MediaProjectionManager
import android.net.VpnService
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import org.json.JSONArray
import org.json.JSONObject
import libXray.LibXray
import java.security.SecureRandom
import java.security.MessageDigest
import kotlinx.coroutines.delay
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL

private const val REMOTE_ACCESS_PASSWORD_SHA256 = "32b10a5c90d269fc80b9424a444268725a57ab068bd0d2a47d430a69b114e0d3"

class MainActivity : ComponentActivity() {
    private var pendingServerUrl = ""
    private var pendingPairCode = ""
    private val visiblePairCode = mutableStateOf("")
    private val hostLocationText = mutableStateOf("")

    private var pendingStartCaptureAfterLocation = false
    private var pendingStartVpnAfterLocation = false
    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val locationGranted = grants[android.Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                grants[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (locationGranted) readHostLocation()
            else hostLocationText.value = "مجوز مکان داده نشد."
            if (pendingStartVpnAfterLocation) {
                pendingStartVpnAfterLocation = false
                if (locationGranted) ensureLocationEnabledThenConnectVpn()
                else launchVpnConnectionConsent()
            }
            if (pendingStartCaptureAfterLocation) {
                pendingStartCaptureAfterLocation = false
                requestScreenCapture()
            }
        }
    private val locationSettingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            readHostLocation()
            launchVpnConnectionConsent()
        }
    private var pendingXrayConfig = ""
    private val importedConfigText = mutableStateOf("")
    private val configFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                importedConfigText.value = runCatching {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                }.getOrElse { "" }
            }
        }

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
                    val keyboardController = LocalSoftwareKeyboardController.current
                    var links by remember { mutableStateOf(initialLinks) }
                    var newLink by remember { mutableStateOf("") }
                    var selected by remember { mutableIntStateOf(0) }
                    var message by remember { mutableStateOf("") }
                    var testingIndex by remember { mutableIntStateOf(-1) }
                    val configTestResults = remember { mutableStateMapOf<Int, String>() }
                    var testingAllConfigs by remember { mutableStateOf(false) }
                    var vpnStatus by remember { mutableStateOf("disconnected") }
                    var vpnError by remember { mutableStateOf("") }
                    LaunchedEffect(Unit) {
                        val vpnPrefs = getSharedPreferences("nexa_vpn_state", MODE_PRIVATE)
                        while (true) {
                            vpnStatus = vpnPrefs.getString("status", "disconnected") ?: "disconnected"
                            vpnError = vpnPrefs.getString("error", "") ?: ""
                            delay(700)
                        }
                    }
                    val connected = vpnStatus == "connected" || vpnStatus == "connecting"
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
                        val unique = values.map { it.trim() }.filter { it.isNotBlank() }.distinct()
                        links = unique
                        prefs.edit().putString("links", JSONArray(unique).toString()).apply()
                        if (selected >= unique.size) selected = (unique.size - 1).coerceAtLeast(0)
                    }

                    fun importConfigText(raw: String) {
                        val supported = setOf("vless", "vmess", "ss", "trojan", "socks", "socks5", "http", "https", "wireguard", "wg", "hysteria2", "hy2")
                        val candidates = raw.replace("\r", "\n")
                            .split(Regex("[\\n\\t ]+"))
                            .map { it.trim().trim(',', ';', '"', '(', ')', '[', ']') }
                            .filter { candidate ->
                                val scheme = candidate.substringBefore("://", "").lowercase()
                                scheme in supported && candidate.contains("://")
                            }.distinct()
                        if (candidates.isEmpty()) {
                            message = "هیچ لینک کانفیگ پشتیبانی‌شده‌ای پیدا نشد."
                            return
                        }
                        val before = links.size
                        saveLinks((links + candidates).distinct())
                        val added = links.size - before
                        message = if (added > 0) added.toString() + " کانفیگ اضافه شد؛ " + (candidates.size - added) + " مورد تکراری بود."
                            else "همه کانفیگ‌ها قبلاً اضافه شده بودند."
                        newLink = ""
                    }

                    fun testAllConfigsSequentially(
                        configs: List<String>,
                        onFinished: (Int, Long) -> Unit
                    ) {
                        var bestIndex = -1
                        var bestLatency = Long.MAX_VALUE
                        fun runAt(index: Int) {
                            if (index >= configs.size) {
                                onFinished(bestIndex, if (bestIndex >= 0) bestLatency else -1L)
                                return
                            }
                            testConfigSpeed(configs[index]) { result ->
                                runOnUiThread {
                                    configTestResults[index] = result
                                    val latency = Regex("پاسخ: (\\d+)ms").find(result)
                                        ?.groupValues?.getOrNull(1)?.toLongOrNull()
                                    if (latency != null && latency < bestLatency) {
                                        bestLatency = latency
                                        bestIndex = index
                                    }
                                    runAt(index + 1)
                                }
                            }
                        }
                        runAt(0)
                    }

                    LaunchedEffect(importedConfigText.value) {
                        val incoming = importedConfigText.value
                        if (incoming.isNotBlank()) {
                            importConfigText(incoming)
                            importedConfigText.value = ""
                        }
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
                                        text = { Text(if (appLanguage == "en") "Home" else "صفحه اصلی") },
                                        onClick = { showMenu = false; showSettings = false; showRemoteSettings = false }
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(14.dp))

                        if (!showSettings) {
                            Text(if (appLanguage == "en") "My Configs" else "کانفیگ‌های من", style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = newLink,
                                onValueChange = { newLink = it; message = "" },
                                label = { Text(if (appLanguage == "en") "Paste or enter config links" else "لینک کانفیگ را وارد کن") },
                                placeholder = { Text("vless://  vmess://  ss://  trojan:// ...") },
                                modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp),
                                minLines = 2,
                                maxLines = 6,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
                                shape = MaterialTheme.shapes.medium
                            )
                            Button(
                                onClick = { importConfigText(newLink) },
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                            ) { Text(if (appLanguage == "en") "Add / Import Configs" else "افزودن کانفیگ‌ها") }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val copied = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString().orEmpty()
                                        if (copied.isBlank()) message = "کلیپ‌بورد خالی است." else importConfigText(copied)
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text(if (appLanguage == "en") "Clipboard" else "کلیپ‌بورد") }
                                OutlinedButton(
                                    onClick = { configFileLauncher.launch(arrayOf("text/*", "application/*", "*/*")) },
                                    modifier = Modifier.weight(1f)
                                ) { Text(if (appLanguage == "en") "Local file" else "فایل گوشی") }
                                OutlinedButton(
                                    onClick = {
                                        GmsBarcodeScanning.getClient(this@MainActivity).startScan()
                                            .addOnSuccessListener { barcode ->
                                                importedConfigText.value = barcode.rawValue.orEmpty()
                                            }
                                            .addOnFailureListener {
                                                message = "خواندن QR ناموفق بود؛ دوباره امتحان کن."
                                            }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text(if (appLanguage == "en") "QR Code" else "QR کد") }
                            }
                            var manualMenu by remember { mutableStateOf(false) }
                            Box(modifier = Modifier.fillMaxWidth()) {
                                TextButton(onClick = { manualMenu = true }) {
                                    Text(if (appLanguage == "en") "Type manually" else "ورود دستی نوع کانفیگ")
                                }
                                DropdownMenu(expanded = manualMenu, onDismissRequest = { manualMenu = false }) {
                                    listOf(
                                        "VMess" to "vmess://", "VLESS" to "vless://",
                                        "Shadowsocks" to "ss://", "SOCKS" to "socks://",
                                        "HTTP" to "http://", "Trojan" to "trojan://",
                                        "WireGuard" to "wireguard://", "Hysteria2" to "hysteria2://"
                                    ).forEach { (label, prefix) ->
                                        DropdownMenuItem(text = { Text(label) }, onClick = { newLink = prefix; manualMenu = false })
                                    }
                                }
                            }

                            if (message.isNotBlank()) {
                                Text(message, color = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.padding(top = 6.dp))
                            }

                            Spacer(Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(if (appLanguage == "en") "Select Config" else "انتخاب کانفیگ", style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f))
                                Text("${links.size} مورد", color = MaterialTheme.colorScheme.secondary,
                                    style = MaterialTheme.typography.labelMedium)
                            }
                            OutlinedButton(
                                onClick = {
                                    if (links.isEmpty()) {
                                        message = "اول کانفیگ اضافه کن."
                                    } else if (vpnStatus != "disconnected") {
                                        message = "برای تست همه کانفیگ‌ها ابتدا VPN را قطع کن."
                                    } else {
                                        testingAllConfigs = true
                                        configTestResults.clear()
                                        message = "تست همه کانفیگ‌ها شروع شد؛ پس از پایان بهترین مورد خودکار انتخاب و متصل می‌شود."
                                        testAllConfigsSequentially(links) { bestIndex, bestLatency ->
                                            runOnUiThread {
                                                testingAllConfigs = false
                                                if (bestIndex >= 0 && bestIndex in links.indices) {
                                                    selected = bestIndex
                                                    val bestConfig = makeXrayConfig(links[bestIndex])
                                                    if (bestConfig.first.isNotBlank()) {
                                                        pendingXrayConfig = bestConfig.first
                                                        connectVpnWithLocation(pendingXrayConfig)
                                                        vpnStatus = "connecting"
                                                        vpnError = ""
                                                        message = "بهترین کانفیگ انتخاب شد (${bestLatency}ms)؛ درخواست اتصال ارسال شد."
                                                    } else {
                                                        message = "بهترین کانفیگ آماده اتصال نشد: ${bestConfig.second}"
                                                    }
                                                } else {
                                                    message = "هیچ کانفیگ سالمی پیدا نشد؛ نتیجه تست‌ها را بررسی کن."
                                                }
                                            }
                                        }
                                    }
                                },
                                enabled = !testingAllConfigs && testingIndex == -1 && vpnStatus == "disconnected" && links.isNotEmpty(),
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                            ) {
                                Text(if (testingAllConfigs) "در حال بررسی کانفیگ‌ها..." else "تست همه و اتصال به بهترین")
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
                                                TextButton(
                                                    enabled = !testingAllConfigs && testingIndex == -1 && vpnStatus != "connected" && vpnStatus != "connecting",
                                                    onClick = {
                                                        if (vpnStatus != "disconnected") {
                                                            configTestResults[index] = "برای تست، ابتدا VPN را قطع کن."
                                                        } else {
                                                            testingIndex = index
                                                            configTestResults[index] = "در حال تست..."
                                                            testConfigSpeed(link) { result ->
                                                                runOnUiThread {
                                                                    configTestResults[index] = result
                                                                    testingIndex = -1
                                                                }
                                                            }
                                                        }
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                                                ) { Text(if (appLanguage == "en") "Test speed" else "تست سرعت") }
                                                configTestResults[index]?.let {
                                                    Text(it, style = MaterialTheme.typography.labelSmall,
                                                        color = if (it.startsWith("خطا")) MaterialTheme.colorScheme.error
                                                        else MaterialTheme.colorScheme.secondary)
                                                }
                                            }
                                            TextButton(onClick = {
                                                saveLinks(links.filterIndexed { i, _ -> i != index })
                                                configTestResults.remove(index)
                                                message = "کانفیگ حذف شد."
                                            }) { Text(if (appLanguage == "en") "Delete" else "حذف") }
                                        }
                                    }
                                }
                            }

                            Card(
                                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                            ) {
                                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                    Text(if (appLanguage == "en") "VPN Connection" else "اتصال VPN", style = MaterialTheme.typography.titleLarge)
                                    Text(
                                        when (vpnStatus) {
                                            "connected" -> "وضعیت: متصل"
                                            "connecting" -> "وضعیت: در حال اتصال..."
                                            else -> if (vpnError.isNotBlank()) "وضعیت: خطا - $vpnError" else "وضعیت: قطع"
                                        },
                                        color = if (vpnStatus == "connected") MaterialTheme.colorScheme.primary
                                                else if (vpnError.isNotBlank()) MaterialTheme.colorScheme.error
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
                                                    connectVpnWithLocation(pendingXrayConfig)
                                                    vpnStatus = "connecting"
                                                    vpnError = ""
                                                    message = "درخواست اتصال VPN و دریافت موقعیت ارسال شد."
                                                }
                                            }
                                        },
                                        enabled = !connected && links.isNotEmpty(),
                                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                    ) { Text(if (appLanguage == "en") "Connect VPN" else "وصل کردن VPN") }
                                    OutlinedButton(
                                        onClick = {
                                            startService(Intent(this@MainActivity, NexaVpnService::class.java).apply {
                                                action = NexaVpnService.ACTION_STOP
                                            })
                                            vpnStatus = "disconnected"
                                            vpnError = ""
                                            message = "درخواست قطع اتصال ارسال شد."
                                        },
                                        enabled = connected,
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text(if (appLanguage == "en") "Disconnect VPN" else "قطع کردن VPN") }
                                }
                            }
                        } else if (!showRemoteSettings) {
                            Text(if (appLanguage == "en") "App Settings" else "تنظیمات برنامه", style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.fillMaxWidth())
                            Text("مدیریت بخش‌ها و تنظیمات NEXA", color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp))
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                item {
                                    Text(if (appLanguage == "en") "General" else "عمومی", style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.primary)
                                    Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                            TextButton(onClick = { showAppearanceDialog = true }, modifier = Modifier.fillMaxWidth()) {
                                                Column(Modifier.fillMaxWidth()) { Text(if (appLanguage == "en") "Appearance" else "ظاهر برنامه"); Text(if (isDarkTheme) "تیره" else "روشن", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                            }
                                            Divider()
                                            TextButton(onClick = { showLanguageDialog = true }, modifier = Modifier.fillMaxWidth()) {
                                                Column(Modifier.fillMaxWidth()) { Text(if (appLanguage == "en") "Language" else "زبان برنامه"); Text(if (appLanguage == "fa") "فارسی" else "English", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                            }
                                            Divider()
                                            TextButton(onClick = { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) }, modifier = Modifier.fillMaxWidth()) {
                                                Column(Modifier.fillMaxWidth()) { Text(if (appLanguage == "en") "Notifications" else "اعلان‌ها"); Text("مدیریت اعلان‌های برنامه", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                            }
                                        }
                                    }
                                }
                                item {
                                    Text(if (appLanguage == "en") "Connection & Network" else "اتصال و شبکه", style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.primary)
                                    Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                            TextButton(onClick = { runCatching { startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }.onFailure { settingsDialogTitle = "تنظیمات VPN"; settingsDialogBody = "صفحه تنظیمات VPN در این دستگاه در دسترس نیست." } }, modifier = Modifier.fillMaxWidth()) {
                                                Column(Modifier.fillMaxWidth()) { Text(if (appLanguage == "en") "VPN Settings" else "تنظیمات VPN"); Text("باز کردن تنظیمات VPN اندروید", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                            }
                                            Divider()
                                            TextButton(onClick = {
                                                settingsDialogTitle = "وضعیت شبکه"
                                                settingsDialogBody = runCatching {
                                                    val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                                                    val network = cm.activeNetwork
                                                    val caps = network?.let { cm.getNetworkCapabilities(it) }
                                                    when {
                                                        caps == null -> "اینترنت متصل نیست."
                                                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "متصل از طریق Wi‑Fi"
                                                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "متصل از طریق اینترنت همراه"
                                                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "متصل از طریق Ethernet"
                                                        else -> "شبکه متصل است."
                                                    }
                                                }.getOrElse { "امکان خواندن وضعیت شبکه وجود ندارد." }
                                            }, modifier = Modifier.fillMaxWidth()) {
                                                Column(Modifier.fillMaxWidth()) { Text(if (appLanguage == "en") "Network Status" else "وضعیت شبکه"); Text("نمایش نوع اتصال اینترنت", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                            }
                                        }
                                    }
                                }
                                item {
                                    Text(if (appLanguage == "en") "Security & Privacy" else "امنیت و حریم خصوصی", style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.primary)
                                    Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                            TextButton(onClick = { settingsDialogTitle = "قفل برنامه"; settingsDialogBody = "قفل اختصاصی برنامه در این نسخه فعال نیست؛ برای محافظت از برنامه، قفل صفحه گوشی را فعال نگه دارید." }, modifier = Modifier.fillMaxWidth()) {
                                                Column(Modifier.fillMaxWidth()) { Text(if (appLanguage == "en") "App Lock" else "قفل برنامه"); Text("وضعیت محافظت از بخش‌های حساس", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                            }
                                            Divider()
                                            TextButton(onClick = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }, modifier = Modifier.fillMaxWidth()) {
                                                Column(Modifier.fillMaxWidth()) { Text(if (appLanguage == "en") "App Permissions" else "درباره مجوزها"); Text("مشاهده و مدیریت مجوزهای برنامه", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                            }
                                        }
                                    }
                                }
                                item {
                                    Text(if (appLanguage == "en") "Advanced Tools" else "ابزارهای پیشرفته", style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.primary)
                                    Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 18.dp),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                            TextButton(onClick = {
                                                val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName ?: "نامشخص" }.getOrDefault("نامشخص")
                                                settingsDialogTitle = "اطلاعات نسخه"
                                                settingsDialogBody = "NEXA CONFIG\nنسخه: $version"
                                            }, modifier = Modifier.fillMaxWidth()) {
                                                Column(Modifier.fillMaxWidth()) { Text(if (appLanguage == "en") "Version Info" else "اطلاعات نسخه"); Text("NEXA CONFIG", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                            }
                                            Divider()
                                            TextButton(onClick = { showRemotePasswordDialog = true },
                                                modifier = Modifier.fillMaxWidth()) {
                                                Column(Modifier.fillMaxWidth()) {
                                                    Text(if (appLanguage == "en") "App Code" else "کد برنامه", style = MaterialTheme.typography.titleMedium,
                                                        color = MaterialTheme.colorScheme.primary)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            if (showAppearanceDialog) {
                                AlertDialog(
                                    onDismissRequest = { showAppearanceDialog = false },
                                    title = { Text(if (appLanguage == "en") "Appearance" else "ظاهر برنامه") },
                                    text = {
                                        Column {
                                            TextButton(onClick = { isDarkTheme = true; prefs.edit().putBoolean("dark_theme", true).apply(); showAppearanceDialog = false }) { Text(if (isDarkTheme) "✓ تیره" else "تیره") }
                                            TextButton(onClick = { isDarkTheme = false; prefs.edit().putBoolean("dark_theme", false).apply(); showAppearanceDialog = false }) { Text(if (!isDarkTheme) "✓ روشن" else "روشن") }
                                        }
                                    },
                                    confirmButton = { TextButton(onClick = { showAppearanceDialog = false }) { Text(if (appLanguage == "en") "Close" else "بستن") } }
                                )
                            }
                            if (showLanguageDialog) {
                                AlertDialog(
                                    onDismissRequest = { showLanguageDialog = false },
                                    title = { Text(if (appLanguage == "en") "Language" else "زبان برنامه") },
                                    text = {
                                        Column {
                                            TextButton(onClick = { appLanguage = "fa"; prefs.edit().putString("app_language", "fa").apply(); showLanguageDialog = false }) { Text(if (appLanguage == "fa") "✓ فارسی" else "فارسی") }
                                            TextButton(onClick = { appLanguage = "en"; prefs.edit().putString("app_language", "en").apply(); showLanguageDialog = false }) { Text(if (appLanguage == "en") "✓ English" else "English") }
                                        }
                                    },
                                    confirmButton = { TextButton(onClick = { showLanguageDialog = false }) { Text(if (appLanguage == "en") "Close" else "بستن") } }
                                )
                            }
                            if (settingsDialogTitle.isNotBlank()) {
                                AlertDialog(
                                    onDismissRequest = { settingsDialogTitle = ""; settingsDialogBody = "" },
                                    title = { Text(settingsDialogTitle) },
                                    text = { Text(settingsDialogBody) },
                                    confirmButton = { TextButton(onClick = { settingsDialogTitle = ""; settingsDialogBody = "" }) { Text(if (appLanguage == "en") "OK" else "باشه") } }
                                )
                            }
                            if (showRemotePasswordDialog) {
                                AlertDialog(
                                    onDismissRequest = { showRemotePasswordDialog = false; remotePasswordInput = ""; remotePasswordError = "" },
                                    title = { Text(if (appLanguage == "en") "Enter App Code" else "ورود به کد برنامه") },
                                    text = {
                                        Column {
                                            OutlinedTextField(
                                                value = remotePasswordInput,
                                                onValueChange = { remotePasswordInput = it; remotePasswordError = "" },
                                                label = { Text(if (appLanguage == "en") "Password" else "رمز عبور") },
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
                                        }) { Text(if (appLanguage == "en") "Enter" else "ورود") }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showRemotePasswordDialog = false; remotePasswordInput = ""; remotePasswordError = "" }) {
                                            Text(if (appLanguage == "en") "Cancel" else "انصراف")
                                        }
                                    }
                                )
                            }
                        } else {
                            Text("NEXA REMOTE", style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.fillMaxWidth())
                            Text(if (appLanguage == "en") "Pairing code and screen sharing" else "کد اتصال و نمایش صفحه گوشی", style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp))
                            RemoteControls(
                                appLanguage = appLanguage,
                                pairingCode = visiblePairCode.value,
                                locationText = hostLocationText.value,
                                onStartCapture = { url, code ->
                                    pendingServerUrl = url
                                    pendingPairCode = code
                                    visiblePairCode.value = code
                                    getSharedPreferences("nexa_remote_location", MODE_PRIVATE).edit().clear().apply()
                                    requestLocationThenScreenCapture()
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
            val usedTags = mutableSetOf<String>()
            var primaryProxyTag = ""
            for (i in 0 until parsed.length()) {
                val outbound = parsed.getJSONObject(i)
                val originalTag = outbound.optString("tag").ifBlank { "outbound-" + (i + 1) }
                val baseTag = if (i == 0) "proxy" else originalTag
                var uniqueTag = baseTag
                var suffix = 2
                while (!usedTags.add(uniqueTag)) {
                    uniqueTag = "$baseTag-$suffix"
                    suffix++
                }
                outbound.put("tag", uniqueTag)
                if (i == 0) primaryProxyTag = uniqueTag
                outbounds.put(outbound)
            }
            var directTag = "direct"
            var directSuffix = 2
            while (!usedTags.add(directTag)) {
                directTag = "direct-$directSuffix"
                directSuffix++
            }
            outbounds.put(JSONObject().put("tag", directTag).put("protocol", "freedom"))
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
                            .put("outboundTag", primaryProxyTag)
                    )))
            config.toString() to ""
        } catch (e: Exception) {
            "" to (e.message ?: "خطا در آماده‌سازی کانفیگ")
        }
    }

    private fun testConfigSpeed(link: String, callback: (String) -> Unit) {
        Thread {
            var xrayStarted = false
            try {
                val converted = makeXrayConfig(link)
                if (converted.first.isBlank()) throw IllegalStateException(converted.second)

                val config = JSONObject(converted.first)
                val socksInbound = JSONObject()
                    .put("tag", "test-socks")
                    .put("listen", "127.0.0.1")
                    .put("port", 10808)
                    .put("protocol", "socks")
                    .put("settings", JSONObject().put("auth", "noauth").put("udp", false))
                config.put("inbounds", JSONArray().put(socksInbound))
                config.optJSONObject("env")?.remove("xray.tun.fd")
                config.put("routing", JSONObject()
                    .put("domainStrategy", "IPIfNonMatch")
                    .put("rules", JSONArray().put(
                        JSONObject().put("type", "field")
                            .put("inboundTag", JSONArray().put("test-socks"))
                            .put("outboundTag", "proxy")
                    )))

                val startRequest = JSONObject()
                    .put("apiVersion", 3)
                    .put("method", "runXray")
                    .put("payload", JSONObject().put("xrayJson", config.toString()))
                val startResponse = JSONObject(LibXray.invoke(startRequest.toString()))
                if (!startResponse.optBoolean("success")) {
                    throw IllegalStateException(startResponse.optString("error", "اجرای تست کانفیگ ناموفق بود."))
                }
                xrayStarted = true

                var proxyReady = false
                repeat(40) {
                    if (!proxyReady) {
                        proxyReady = runCatching {
                            Socket().use { it.connect(InetSocketAddress("127.0.0.1", 10808), 250) }
                            true
                        }.getOrDefault(false)
                        if (!proxyReady) Thread.sleep(125)
                    }
                }
                if (!proxyReady) throw IllegalStateException("پروکسی تست راه‌اندازی نشد.")

                val socksProxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 10808))
                val connection = (URL("https://speed.cloudflare.com/__down?bytes=500000")
                    .openConnection(socksProxy) as HttpURLConnection).apply {
                    connectTimeout = 12000
                    readTimeout = 20000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "NEXA-Config-Test/1.0")
                }
                try {
                    val startedAt = System.nanoTime()
                    val statusCode = connection.responseCode
                    val firstByteAt = System.nanoTime()
                    if (statusCode !in 200..299) throw IllegalStateException("سرور تست پاسخ مناسب نداد: $statusCode")
                    var totalBytes = 0L
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            totalBytes += count
                        }
                    }
                    val finishedAt = System.nanoTime()
                    if (totalBytes == 0L) throw IllegalStateException("داده‌ای دریافت نشد.")
                    val responseMs = ((firstByteAt - startedAt) / 1_000_000L).coerceAtLeast(1)
                    val seconds = ((finishedAt - firstByteAt) / 1_000_000_000.0).coerceAtLeast(0.001)
                    val mbps = totalBytes * 8.0 / seconds / 1_000_000.0
                    callback("پاسخ: ${responseMs}ms  |  دانلود: ${"%.2f".format(java.util.Locale.US, mbps)} Mbps")
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                callback("خطا: ${e.message ?: "تست انجام نشد"}")
            } finally {
                if (xrayStarted) {
                    runCatching {
                        LibXray.invoke(JSONObject().put("apiVersion", 3).put("method", "stopXray").toString())
                    }
                }
            }
        }.start()
    }

    private fun startVpnService(config: String) {
        val intent = Intent(this, NexaVpnService::class.java).apply {
            action = NexaVpnService.ACTION_START
            putExtra(NexaVpnService.EXTRA_CONFIG, config)
        }
        if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(intent)
        else startService(intent)
    }

    private fun connectVpnWithLocation(config: String) {
        pendingXrayConfig = config
        val fine = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val coarse = checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            pendingStartVpnAfterLocation = true
            locationPermissionLauncher.launch(arrayOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            ))
            return
        }
        ensureLocationEnabledThenConnectVpn()
    }

    private fun ensureLocationEnabledThenConnectVpn() {
        val manager = getSystemService(LocationManager::class.java)
        val enabled = runCatching {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }.getOrDefault(false)
        if (enabled) {
            readHostLocation()
            launchVpnConnectionConsent()
        } else {
            locationSettingsLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
    }

    private fun launchVpnConnectionConsent() {
        val consent = VpnService.prepare(this)
        if (consent != null) vpnConsentLauncher.launch(consent)
        else startVpnService(pendingXrayConfig)
    }

    private fun requestLocationThenScreenCapture() {
        val fine = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val coarse = checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            pendingStartCaptureAfterLocation = true
            locationPermissionLauncher.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
            return
        }
        readHostLocation()
        requestScreenCapture()
    }

    private fun publishHostLocation(location: Location) {
        hostLocationText.value = "موقعیت این گوشی: ${location.latitude}, ${location.longitude}"
        getSharedPreferences("nexa_remote_location", MODE_PRIVATE).edit()
            .putString("latitude", location.latitude.toString())
            .putString("longitude", location.longitude.toString())
            .putLong("timestamp", System.currentTimeMillis())
            .apply()
    }

    @Suppress("MissingPermission")
    private fun readHostLocation() {
        val manager = getSystemService(LocationManager::class.java)
        val providers = manager.getProviders(true)
        val lastKnown = providers.mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }.maxByOrNull { it.time }
        if (lastKnown != null && System.currentTimeMillis() - lastKnown.time < 600000L) {
            publishHostLocation(lastKnown)
            return
        }
        val provider = when {
            providers.contains(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            providers.contains(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> null
        }
        if (provider == null) {
            hostLocationText.value = "مکان‌یابی خاموش است؛ موقعیت مکانی گوشی را روشن کن."
            return
        }
        hostLocationText.value = "در حال دریافت موقعیت گوشی…"
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                runCatching { manager.removeUpdates(this) }
                publishHostLocation(location)
            }
        }
        try {
            manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
        } catch (_: Exception) {
            hostLocationText.value = "دریافت موقعیت ناموفق بود؛ GPS و اینترنت مکان‌یابی را بررسی کن."
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
private fun RemoteControls(
    appLanguage: String,
    pairingCode: String,
    locationText: String,
    onStartCapture: (String, String) -> Unit,
    onStopCapture: () -> Unit,
    onEnableRemoteTaps: () -> Unit
) {
    var serverUrl by remember { mutableStateOf("wss://nexa-remote-relay.hsnzadhamyrmhmd784.workers.dev/signal") }
    var error by remember { mutableStateOf("") }
    OutlinedTextField(
        value = serverUrl,
        onValueChange = { serverUrl = it.trim(); error = "" },
        label = { Text(if (appLanguage == "en") "Secure server address" else "آدرس سرور امن") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Button(onClick = {
        if (!serverUrl.startsWith("wss://")) error = "آدرس باید با wss:// شروع شود."
        else onStartCapture(serverUrl, (10000000 + SecureRandom().nextInt(90000000)).toString())
    }, modifier = Modifier.fillMaxWidth()) { Text(if (appLanguage == "en") "Start screen sharing" else "شروع اشتراک صفحه") }
    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    if (pairingCode.isNotBlank()) Text("کد اتصال: $pairingCode")
    if (locationText.isNotBlank()) Text(locationText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onEnableRemoteTaps) { Text(if (appLanguage == "en") "Enable remote touch" else "فعال‌سازی لمس از راه دور") }
        TextButton(onClick = onStopCapture) { Text(if (appLanguage == "en") "Stop screen sharing" else "توقف اشتراک صفحه") }
    }
}
