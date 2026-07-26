package com.tunnel.demo.tunneldemo

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.Manifest
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.tunnel.demo.tunneldemo.databinding.ActivityMainBinding
import com.tunnel.demo.tunneldemo.model.HttpMethod
import com.tunnel.demo.tunneldemo.model.StatsData
import com.tunnel.demo.tunneldemo.model.Transaction
import com.tunnel.demo.tunneldemo.service.FloatingOverlayService
import com.tunnel.demo.tunneldemo.service.InspectionVpnService
import com.tunnel.demo.tunneldemo.service.LocalProxyServer
import com.tunnel.demo.tunneldemo.service.PacketStatsCollector
import com.tunnel.demo.tunneldemo.proxy.DnsResolver
import com.tunnel.demo.tunneldemo.proxy.ProxyConfig
import com.tunnel.demo.tunneldemo.ui.TransactionAdapter
import com.tunnel.demo.tunneldemo.ui.TransactionDetailFragment
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 350L
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: TransactionAdapter

    private var isVpnActive = false
    private var overlayBound = false
    private var searchQuery = ""
    private var activeMethod: HttpMethod? = null
    private var httpsOnly = false
    private var searchJob: Job? = null
    private var currentStats = StatsData()

    private val vpnLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) startVpnInternal()
        else showSnackbar("VPN izni gerekli")
    }

    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (FloatingOverlayService.hasOverlayPermission(this)) showOverlay()
        else showSnackbar("Overlay izni gerekli")
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val overlayConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) { overlayBound = true }
        override fun onServiceDisconnected(name: ComponentName) { overlayBound = false }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setupToolbar()
        observeStats()
        observeTransactions()
        setupFab()
        setupSearch()
        setupFilterChips()
        setupRecyclerView()
        setupBottomNav()
        checkInitialState()
        savedInstanceState?.let { restoreState(it) }
    }

    override fun onStart() {
        super.onStart()
        try {
            Intent(this, FloatingOverlayService::class.java).also {
                bindService(it, overlayConnection, Context.BIND_AUTO_CREATE)
            }
        } catch (_: Exception) {}
    }

    override fun onStop() {
        super.onStop()
        if (overlayBound) {
            try { unbindService(overlayConnection) } catch (_: Exception) {}
            overlayBound = false
        }
    }

    override fun onResume() {
        super.onResume()
        updateVpnState()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("vpn_active", isVpnActive)
        outState.putString("search", searchQuery)
        outState.putString("method", activeMethod?.name)
        outState.putBoolean("https", httpsOnly)
    }

    override fun onDestroy() {
        searchJob?.cancel()
        super.onDestroy()
    }

    private fun restoreState(state: Bundle) {
        isVpnActive = state.getBoolean("vpn_active", false)
        searchQuery = state.getString("search", "") ?: ""
        state.getString("method")?.let {
            activeMethod = try { HttpMethod.valueOf(it) } catch (_: Exception) { null }
        }
        httpsOnly = state.getBoolean("https", false)
        binding.etSearch.setText(searchQuery)
        updateFabIcon()
        updateChipSelection()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "NetPeeker"
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_select_apps -> {
                    showAppSelectionDialog()
                    true
                }
                else -> false
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.toolbar_menu, menu)
        return true
    }

    private fun showAppSelectionDialog() {
        val apps = VpnAppManager.getInstalledApps(this)
        val allowed = VpnAppManager.getAllowedApps()
        val checked = apps.map { it.packageName in allowed }.toBooleanArray()
        val labels = apps.map { it.label }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle("Select Apps to Inspect")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("Apply") { _, _ ->
                val selected = apps.filterIndexed { i, _ -> checked[i] }
                    .map { it.packageName }.toSet()
                VpnAppManager.setAllowedApps(selected)
                val count = selected.size
                val msg = if (count == 0) "All apps will be inspected"
                    else "$count app(s) selected for inspection"
                showSnackbar(msg)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun observeStats() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                PacketStatsCollector.statsFlow(500).collect { stats ->
                    currentStats = stats
                    updateStatsUi(stats)
                    updateVpnState()
                }
            }
        }
    }

    private fun updateStatsUi(stats: StatsData) {
        binding.tvTotalPackets.text = fmtNum(stats.totalPackets)
        binding.tvHttpRequests.text = fmtNum(stats.httpPackets)
        binding.tvHttpsConnections.text = fmtNum(stats.httpsConnections)
        binding.tvActiveConnections.text = stats.activeConnections.toString()
        binding.tvBytesTransferred.text = fmtBytes(stats.bytesTransferred)
    }

    private fun observeTransactions() {
        LocalProxyServer.addListener { transaction ->
            runOnUiThread { applyFilters() }
        }
    }

    private fun applyFilters() {
        val all = LocalProxyServer.getTransactions()
        val query = searchQuery.trim().lowercase()
        val filtered = all.filter { t ->
            val matchesSearch = query.isEmpty() ||
                    t.host.lowercase().contains(query) ||
                    t.path.lowercase().contains(query) ||
                    t.fullUrl.lowercase().contains(query)
            val matchesMethod = activeMethod == null || t.method == activeMethod
            val matchesHttps = !httpsOnly || t.isHttps
            matchesSearch && matchesMethod && matchesHttps
        }
        adapter.submitList(filtered)
        binding.emptyView.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun setupFab() {
        updateFabIcon()
        binding.fabToggleVpn.setOnClickListener {
            if (isVpnActive) stopVpn()
            else requestVpn()
        }
    }

    private fun updateFabIcon() {
        binding.fabToggleVpn.setImageResource(
            if (isVpnActive) R.drawable.ic_stop else R.drawable.ic_play
        )
    }

    private fun requestVpn() {
        try {
            val intent = VpnService.prepare(this)
            if (intent != null) vpnLauncher.launch(intent)
            else startVpnInternal()
        } catch (e: Exception) {
            showSnackbar("VPN hatasi: ${e.message}")
        }
    }

    private fun startVpnInternal() {
        try {
            InspectionVpnService.start(this)
            isVpnActive = true
            updateFabIcon()
            showSnackbar("Yakalama basladi")
        } catch (e: Exception) {
            showSnackbar("VPN baslatilamadi: ${e.message}")
        }
    }

    private fun stopVpn() {
        try {
            InspectionVpnService.stop(this)
            isVpnActive = false
            updateFabIcon()
            showSnackbar("Yakalama durduruldu")
        } catch (e: Exception) {
            showSnackbar("VPN durdurulamadi: ${e.message}")
        }
    }

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    delay(SEARCH_DEBOUNCE_MS)
                    searchQuery = s?.toString() ?: ""
                    applyFilters()
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupFilterChips() {
        binding.chipAll.setOnClickListener { selectMethod(null) }
        binding.chipGet.setOnClickListener { selectMethod(HttpMethod.GET) }
        binding.chipPost.setOnClickListener { selectMethod(HttpMethod.POST) }
        binding.chipPut.setOnClickListener { selectMethod(HttpMethod.PUT) }
        binding.chipDelete.setOnClickListener { selectMethod(HttpMethod.DELETE) }
        binding.chipPatch.setOnClickListener { selectMethod(HttpMethod.PATCH) }
        binding.chipHttps.setOnCheckedChangeListener { _, isChecked ->
            httpsOnly = isChecked; applyFilters()
        }
        updateChipSelection()
    }

    private fun selectMethod(method: HttpMethod?) {
        activeMethod = method
        updateChipSelection()
        applyFilters()
    }

    private fun updateChipSelection() {
        binding.chipAll.isChecked = activeMethod == null
        binding.chipGet.isChecked = activeMethod == HttpMethod.GET
        binding.chipPost.isChecked = activeMethod == HttpMethod.POST
        binding.chipPut.isChecked = activeMethod == HttpMethod.PUT
        binding.chipDelete.isChecked = activeMethod == HttpMethod.DELETE
        binding.chipPatch.isChecked = activeMethod == HttpMethod.PATCH
        binding.chipHttps.isChecked = httpsOnly
    }

    private fun setupRecyclerView() {
        adapter = TransactionAdapter(
            onItemClick = { transaction -> showDetail(transaction) },
            onReplayClick = { transaction -> replayTransaction(transaction) }
        )
        binding.recyclerTransactions.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
            setHasFixedSize(true)
        }
    }

    private fun showDetail(transaction: Transaction) {
        try {
            val frag = TransactionDetailFragment()
            frag.arguments = Bundle().apply {
                putSerializable("transaction", transaction)
            }
            supportFragmentManager.beginTransaction()
                .setCustomAnimations(
                    android.R.anim.fade_in, android.R.anim.fade_out,
                    android.R.anim.fade_in, android.R.anim.fade_out
                )
                .replace(android.R.id.content, frag)
                .addToBackStack("detail")
                .commit()
        } catch (e: Exception) {
            showSnackbar("Detay acilamadi: ${e.message}")
        }
    }

    private fun replayTransaction(transaction: Transaction) {
        lifecycleScope.launch {
            try {
                val result = LocalProxyServer.replayRequest(transaction)
                if (result != null) showSnackbar("Tekrar gonderildi (${result.statusCode})")
                else showSnackbar("Tekrar basarisiz")
            } catch (e: Exception) {
                showSnackbar("Tekrar hatasi: ${e.message}")
            }
        }
    }

    private fun setupBottomNav() {
        binding.navBottom.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_capture -> { binding.recyclerTransactions.smoothScrollToPosition(0); true }
                R.id.nav_certificate -> { showCertificateInfo(); true }
                R.id.nav_overlay -> { toggleOverlay(); true }
                R.id.nav_export -> { showExportDialog(); true }
                R.id.nav_settings -> { showSettings(); true }
                else -> false
            }
        }
    }

    private fun showCertificateInfo() {
        MaterialAlertDialogBuilder(this)
            .setTitle("CA Sertifikasi Kurulumu")
            .setMessage("HTTPS trafigini incelemek icin NetPeeker kok CA'sini cihaz guven deposuna yukleyin.\n\n" +
                    "Rootsuz cihaz icin:\n" +
                    "1. Ayarlar > Guvenlik > Sifreleme ve kimlik bilgileri\n" +
                    "2. Bir sertifika yukle > CA sertifikasi\n" +
                    "3. NetPeeker-RootCA.crt dosyasini secin\n\n" +
                    "Android 14+ icin: Ayarlar > Guvenlik ve gizlilik > Sertifikalar")
            .setPositiveButton("Sertifikayi Olustur") { _, _ ->
                lifecycleScope.launch {
                    try {
                        val bundle = com.tunnel.demo.tunneldemo.cert.CertificateManager.generateRootCA(this@MainActivity)
                        if (bundle != null) showSnackbar("Sertifika olusturuldu: Downloads/NetPeeker-RootCA.crt")
                        else showSnackbar("Sertifika olusturulamadi")
                    } catch (e: Exception) {
                        showSnackbar("Hata: ${e.message}")
                    }
                }
            }
            .setNegativeButton("Iptal", null)
            .show()
    }

    private fun showExportDialog() {
        val items = arrayOf("HAR formatinda disa aktar", "JSON formatinda disa aktar", "Panoya kopyala")
        MaterialAlertDialogBuilder(this)
            .setTitle("Veriyi Disa Aktar")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> exportHAR()
                    1 -> exportJSON()
                    2 -> copyToClipboard()
                }
            }
            .show()
    }

    private fun exportHAR() {
        try {
            val transactions = LocalProxyServer.getTransactions()
            val json = buildHAR(transactions)
            val file = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "NetPeeker_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.har"
            )
            FileOutputStream(file).use { it.write(json.toByteArray()) }
            showSnackbar("HAR export edildi: ${file.name}")
        } catch (e: Exception) {
            showSnackbar("Export hatasi: ${e.message}")
        }
    }

    private fun exportJSON() {
        try {
            val transactions = LocalProxyServer.getTransactions()
            val arr = org.json.JSONArray()
            transactions.forEach { t ->
                val obj = org.json.JSONObject()
                obj.put("id", t.id)
                obj.put("method", t.method.value)
                obj.put("host", t.host)
                obj.put("path", t.path)
                obj.put("url", t.fullUrl)
                obj.put("statusCode", t.statusCode)
                obj.put("isHttps", t.isHttps)
                obj.put("elapsed", t.elapsed)
                obj.put("contentLength", t.contentLength)
                obj.put("sourcePort", t.sourcePort)
                obj.put("destinationPort", t.destinationPort)
                obj.put("status", t.status.name)
                obj.put("requestTime", t.requestTime)
                obj.put("responseTime", t.responseTime)
                obj.put("requestBody", t.requestBody ?: "")
                obj.put("responseBody", t.responseBody ?: "")
                val reqH = org.json.JSONObject(t.requestHeaders)
                val resH = org.json.JSONObject(t.responseHeaders)
                obj.put("requestHeaders", reqH)
                obj.put("responseHeaders", resH)
                arr.put(obj)
            }
            val json = arr.toString(2)
            val file = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "NetPeeker_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.json"
            )
            FileOutputStream(file).use { it.write(json.toByteArray()) }
            showSnackbar("JSON export edildi: ${file.name}")
        } catch (e: Exception) {
            showSnackbar("Export hatasi: ${e.message}")
        }
    }

    private fun copyToClipboard() {
        try {
            val transactions = LocalProxyServer.getTransactions()
            val sb = StringBuilder()
            transactions.take(50).forEach { t ->
                sb.appendLine("[${t.method.value}] ${t.host}${t.path} -> ${t.statusCode} (${t.elapsed}ms)")
            }
            if (transactions.size > 50) sb.appendLine("... ve ${transactions.size - 50} daha")
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("NetPeeker", sb.toString()))
            showSnackbar("Panoya kopyalandi (${transactions.size} kayit)")
        } catch (e: Exception) {
            showSnackbar("Kopyalama hatasi: ${e.message}")
        }
    }

    private fun buildHAR(transactions: List<Transaction>): String {
        val sb = StringBuilder()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        dateFormat.timeZone = TimeZone.getTimeZone("UTC")

        sb.appendLine("""{"log":{"version":"1.2","creator":{"name":"NetPeeker","version":"1.0"},"entries":[""")
        transactions.forEachIndexed { i, t ->
            val started = dateFormat.format(Date(t.requestTime))
            val time = if (t.elapsed > 0) t.elapsed else 0
            val reqHeaders = t.requestHeaders.entries.joinToString(",") { (k, v) ->
                """{"name":"${escJson(k)}","value":"${escJson(v)}"}"""
            }
            val resHeaders = t.responseHeaders.entries.joinToString(",") { (k, v) ->
                """{"name":"${escJson(k)}","value":"${escJson(v)}"}"""
            }
            val reqBodySize = t.requestBody?.length ?: 0
            val resBodySize = t.responseBody?.length ?: 0

            sb.appendLine("""{"startedDateTime":"$started","time":$time,"request":{"method":"${t.method.value}","url":"${escJson(t.fullUrl)}","httpVersion":"HTTP/1.1","headers":[$reqHeaders],"bodySize":$reqBodySize},"response":{"status":${t.statusCode},"statusText":"","httpVersion":"HTTP/1.1","headers":[$resHeaders],"content":{"size":$resBodySize,"mimeType":"${escJson(t.responseHeaders["Content-Type"] ?: "")}"},"bodySize":$resBodySize,"redirectURL":""},"cache":{},"timings":{"send":0,"wait":$time,"receive":0}}""")
            if (i < transactions.size - 1) sb.append(",")
        }
        sb.appendLine("]}}")
        return sb.toString()
    }

    private fun escJson(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

    private fun toggleOverlay() {
        if (!FloatingOverlayService.hasOverlayPermission(this)) {
            requestOverlayPermission()
            return
        }
        if (overlayBound) hideOverlay()
        else showOverlay()
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            FloatingOverlayService.openOverlaySettings(this)
    }

    private fun showOverlay() {
        try {
            Intent(this, FloatingOverlayService::class.java).apply {
                action = FloatingOverlayService.ACTION_SHOW
            }.also {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(it)
                else startService(it)
            }
            showSnackbar("Overlay acildi")
        } catch (e: Exception) {
            showSnackbar("Hata: ${e.message}")
        }
    }

    private fun hideOverlay() {
        try {
            Intent(this, FloatingOverlayService::class.java).apply {
                action = FloatingOverlayService.ACTION_HIDE
            }.also { startService(it) }
            showSnackbar("Overlay kapatildi")
        } catch (e: Exception) {
            showSnackbar("Hata: ${e.message}")
        }
    }

    private fun showSettings() {
        val items = arrayOf(
            "Veriyi temizle",
            "DNS Ayarlari (DoH)",
            "Hosts Override",
            "Proxy Zinciri (SOCKS5/HTTP)",
            "SNI Spoofing",
            "User-Agent Spoofing",
            "HAR Export",
            "JSON Export",
            "Hakkinda"
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("NetPeeker Ayarlar")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> clearData()
                    1 -> showDnsSettings()
                    2 -> showHostsSettings()
                    3 -> showProxyChainSettings()
                    4 -> showSniSettings()
                    5 -> showUaSettings()
                    6 -> exportHAR()
                    7 -> exportJSON()
                    8 -> showAbout()
                }
            }
            .show()
    }

    private fun showDnsSettings() {
        val prefs = getSharedPreferences("netpeeker", Context.MODE_PRIVATE)
        val current = prefs.getString("doh_url", "https://cloudflare-dns.com/dns-query") ?: ""
        val enabled = prefs.getBoolean("doh_enabled", false)

        val items = arrayOf(
            "Cloudflare: https://cloudflare-dns.com/dns-query",
            "Google: https://dns.google/dns-query",
            "Quad9: https://dns.quad9.net/dns-query",
            "Cloudflare IP: https://1.1.1.1/dns-query",
            "Google IP: https://8.8.8.8/dns-query",
            "Custom..."
        )
        val checked = when (current) {
            "https://cloudflare-dns.com/dns-query" -> 0
            "https://dns.google/dns-query" -> 1
            "https://dns.quad9.net/dns-query" -> 2
            "https://1.1.1.1/dns-query" -> 3
            "https://8.8.8.8/dns-query" -> 4
            else -> 5
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("DNS over HTTPS")
            .setSingleChoiceItems(items, checked) { dialog, i ->
                val url = when (i) {
                    0 -> "https://cloudflare-dns.com/dns-query"
                    1 -> "https://dns.google/dns-query"
                    2 -> "https://dns.quad9.net/dns-query"
                    3 -> "https://1.1.1.1/dns-query"
                    4 -> "https://8.8.8.8/dns-query"
                    else -> current
                }
                prefs.edit().putString("doh_url", url).putBoolean("doh_enabled", true).apply()
                val cfg = loadConfig()
                LocalProxyServer.updateConfig(cfg)
                DnsResolver.setConfig(cfg)
                showSnackbar("DNS: $url")
                dialog.dismiss()
            }
            .setNeutralButton(if (enabled) "Kapat" else "Ac") { _, _ ->
                prefs.edit().putBoolean("doh_enabled", !enabled).apply()
                val cfg = loadConfig()
                LocalProxyServer.updateConfig(cfg)
                DnsResolver.setConfig(cfg)
                showSnackbar(if (!enabled) "DoH acik" else "DoH kapali")
            }
            .setPositiveButton("Tamam", null)
            .show()
    }

    private fun showHostsSettings() {
        val prefs = getSharedPreferences("netpeeker", Context.MODE_PRIVATE)
        val hostsStr = prefs.getString("hosts_override", "") ?: ""

        val input = android.widget.EditText(this).apply {
            setText(hostsStr)
            setHint("orn: twitter.com=104.244.42.65\norn: youtube.com=142.250.80.46")
            setTextColor(resources.getColor(R.color.text_primary, theme))
            setBackgroundColor(resources.getColor(R.color.bg_input, theme))
            gravity = android.view.Gravity.TOP
            minLines = 8
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Hosts Override")
            .setMessage("Her satira bir domain=IP yazin")
            .setView(input, 32, 16, 32, 16)
            .setPositiveButton("Kaydet") { _, _ ->
                prefs.edit().putString("hosts_override", input.text.toString()).apply()
                val cfg = loadConfig()
                LocalProxyServer.updateConfig(cfg)
                showSnackbar("Hosts kaydedildi")
            }
            .setNegativeButton("Iptal", null)
            .show()
    }

    private fun showProxyChainSettings() {
        val prefs = getSharedPreferences("netpeeker", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("proxy_chain_enabled", false)
        val type = prefs.getString("proxy_chain_type", "socks5") ?: "socks5"
        val host = prefs.getString("proxy_chain_host", "127.0.0.1") ?: "127.0.0.1"
        val port = prefs.getInt("proxy_chain_port", 1080)

        val items = arrayOf("SOCKS5", "HTTP", "Kapat")
        val checked = when {
            !enabled -> 2
            type == "socks5" -> 0
            else -> 1
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Proxy Zinciri")
            .setSingleChoiceItems(items, checked) { dialog, i ->
                if (i == 2) {
                    prefs.edit().putBoolean("proxy_chain_enabled", false).apply()
                } else {
                    showProxyChainDetailDialog(prefs, if (i == 0) "socks5" else "http")
                }
                val cfg = loadConfig()
                LocalProxyServer.updateConfig(cfg)
                dialog.dismiss()
            }
            .show()
    }

    private fun showProxyChainDetailDialog(prefs: android.content.SharedPreferences, type: String) {
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.dialog_settings_input, null)

        val etHost = view.findViewById<android.widget.EditText>(R.id.et_input1).apply {
            setText(prefs.getString("proxy_chain_host", "127.0.0.1"))
            hint = "Proxy Host"
        }
        val etPort = view.findViewById<android.widget.EditText>(R.id.et_input2).apply {
            setText(prefs.getInt("proxy_chain_port", 1080).toString())
            hint = "Proxy Port"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val etUser = view.findViewById<android.widget.EditText>(R.id.et_input3).apply {
            setText(prefs.getString("proxy_chain_user", ""))
            hint = "Kullanici (opsiyonel)"
        }
        val etPass = view.findViewById<android.widget.EditText>(R.id.et_input4).apply {
            setText(prefs.getString("proxy_chain_pass", ""))
            hint = "Sifre (opsiyonel)"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("$type Proxy")
            .setView(view)
            .setPositiveButton("Kaydet") { _, _ ->
                prefs.edit()
                    .putBoolean("proxy_chain_enabled", true)
                    .putString("proxy_chain_type", type)
                    .putString("proxy_chain_host", etHost.text.toString())
                    .putInt("proxy_chain_port", etPort.text.toString().toIntOrNull() ?: 1080)
                    .putString("proxy_chain_user", etUser.text.toString())
                    .putString("proxy_chain_pass", etPass.text.toString())
                    .apply()
                val cfg = loadConfig()
                LocalProxyServer.updateConfig(cfg)
                showSnackbar("$type proxy ayarlandi")
            }
            .setNegativeButton("Iptal", null)
            .show()
    }

    private fun showSniSettings() {
        val prefs = getSharedPreferences("netpeeker", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("sni_spoof_enabled", false)
        val overrides = prefs.getString("sni_overrides", "") ?: ""

        val items = arrayOf(
            if (enabled) "SNI Spoofing: Acik" else "SNI Spoofing: Kapali",
            "SNI Override Ekle",
            "Temizle"
        )

        MaterialAlertDialogBuilder(this)
            .setTitle("SNI Spoofing")
            .setItems(items) { _, i ->
                when (i) {
                    0 -> {
                        val newState = !enabled
                        prefs.edit().putBoolean("sni_spoof_enabled", newState).apply()
                        val cfg = loadConfig()
                        LocalProxyServer.updateConfig(cfg)
                        showSnackbar(if (newState) "SNI spoofing acik" else "SNI spoofing kapali")
                    }
                    1 -> showSniOverrideDialog(prefs, overrides)
                    2 -> {
                        prefs.edit().putString("sni_overrides", "").apply()
                        val cfg = loadConfig()
                        LocalProxyServer.updateConfig(cfg)
                        showSnackbar("SNI override temizlendi")
                    }
                }
            }
            .show()
    }

    private fun showSniOverrideDialog(prefs: android.content.SharedPreferences, current: String) {
        val input = android.widget.EditText(this).apply {
            setText(current)
            setHint("orn: twitter.com=instagram.com\norn: *.x.com=example.com")
            setTextColor(resources.getColor(R.color.text_primary, theme))
            setBackgroundColor(resources.getColor(R.color.bg_input, theme))
            gravity = android.view.Gravity.TOP
            minLines = 6
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("SNI Override")
            .setMessage("Her satira: orjinal_sni=hedef_sni")
            .setView(input, 32, 16, 32, 16)
            .setPositiveButton("Kaydet") { _, _ ->
                prefs.edit().putString("sni_overrides", input.text.toString()).apply()
                val cfg = loadConfig()
                LocalProxyServer.updateConfig(cfg)
                showSnackbar("SNI overrides kaydedildi")
            }
            .setNegativeButton("Iptal", null)
            .show()
    }

    private fun showUaSettings() {
        val prefs = getSharedPreferences("netpeeker", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("ua_spoof_enabled", false)
        val ua = prefs.getString("ua_custom", "") ?: ""

        val input = android.widget.EditText(this).apply {
            setText(ua.ifEmpty { "Mozilla/5.0 (Android 14; Mobile; rv:125.0) Gecko/125.0 Firefox/125.0" })
            setHint("User-Agent")
            setTextColor(resources.getColor(R.color.text_primary, theme))
            setBackgroundColor(resources.getColor(R.color.bg_input, theme))
            selectAll()
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("User-Agent Spoofing")
            .setMessage("Tum HTTP isteklerinde User-Agent'i degistir")
            .setView(input, 32, 16, 32, 16)
            .setNeutralButton(if (enabled) "Kapat" else "Ac") { _, _ ->
                prefs.edit().putBoolean("ua_spoof_enabled", !enabled).apply()
                val cfg = loadConfig()
                LocalProxyServer.updateConfig(cfg)
                showSnackbar(if (!enabled) "UA spoofing acik" else "UA spoofing kapali")
            }
            .setPositiveButton("Kaydet") { _, _ ->
                prefs.edit().putString("ua_custom", input.text.toString()).putBoolean("ua_spoof_enabled", true).apply()
                val cfg = loadConfig()
                LocalProxyServer.updateConfig(cfg)
                showSnackbar("User-Agent guncellendi")
            }
            .setNegativeButton("Iptal", null)
            .show()
    }

    private fun loadConfig(): ProxyConfig {
        val prefs = getSharedPreferences("netpeeker", Context.MODE_PRIVATE)
        val hostsStr = prefs.getString("hosts_override", "") ?: ""
        val hostsMap = mutableMapOf<String, String>()
        hostsStr.lines().forEach { line ->
            val parts = line.split("=", limit = 2)
            if (parts.size == 2) hostsMap[parts[0].trim()] = parts[1].trim()
        }

        val sniStr = prefs.getString("sni_overrides", "") ?: ""
        val sniMap = mutableMapOf<String, String>()
        sniStr.lines().forEach { line ->
            val parts = line.split("=", limit = 2)
            if (parts.size == 2) sniMap[parts[0].trim()] = parts[1].trim()
        }

        return ProxyConfig(
            useCustomDns = prefs.getBoolean("doh_enabled", false),
            dohUrl = prefs.getString("doh_url", "https://cloudflare-dns.com/dns-query") ?: "https://cloudflare-dns.com/dns-query",
            hostsOverride = hostsMap,
            useUpstreamProxy = prefs.getBoolean("proxy_chain_enabled", false),
            upstreamType = prefs.getString("proxy_chain_type", "socks5") ?: "socks5",
            upstreamHost = prefs.getString("proxy_chain_host", "127.0.0.1") ?: "127.0.0.1",
            upstreamPort = prefs.getInt("proxy_chain_port", 1080),
            upstreamUser = prefs.getString("proxy_chain_user", "") ?: "",
            upstreamPass = prefs.getString("proxy_chain_pass", "") ?: "",
            spoofUserAgent = prefs.getBoolean("ua_spoof_enabled", false),
            customUserAgent = prefs.getString("ua_custom", "") ?: "",
            stripHeaders = listOf("X-Forwarded-For", "Via", "X-Real-IP"),
            enabledSniSpoof = prefs.getBoolean("sni_spoof_enabled", false),
            sniOverride = sniMap
        )
    }

    private fun clearData() {
        try {
            LocalProxyServer.clearTransactions()
            applyFilters()
            showSnackbar("Veri temizlendi")
        } catch (e: Exception) {
            showSnackbar("Hata: ${e.message}")
        }
    }

    private fun showAbout() {
        MaterialAlertDialogBuilder(this)
            .setTitle("NetPeeker")
            .setMessage("Surum 1.0\nAg trafigi izleme ve debug araci\nRootsuz calisir")
            .setPositiveButton("Tamam", null)
            .show()
    }

    private fun checkInitialState() {
        updateVpnState()
        if (!FloatingOverlayService.hasOverlayPermission(this))
            showSnackbar("Overlay izni gerekli: kayan istatistikler icin")
    }

    private fun updateVpnState() {
        isVpnActive = InspectionVpnService.isRunning()
        updateFabIcon()
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT)
            .setAnchorView(binding.fabToggleVpn)
            .show()
    }

    private fun fmtNum(n: Long): String =
        NumberFormat.getIntegerInstance(Locale.US).format(n)

    private fun fmtBytes(bytes: Long): String = when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}
