package com.example.localserver

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import java.io.File

class MainActivity : Activity() {

    private val port = 8080
    private var server: LocalHttpServer? = null
    private var currentRoot: DocumentFile? = null
    private var autoScanWatcher: AutoScanWatcher? = null
    private lateinit var prefs: SharedPreferences

    private lateinit var webView: WebView
    private lateinit var pickButton: Button
    private lateinit var serverView: LinearLayout
    private lateinit var refreshButton: Button
    private lateinit var openBrowserButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var importZipButton: Button
    private lateinit var autoScanButton: Button
    private lateinit var startNodeButton: Button
    private lateinit var viewLogButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("localserver", MODE_PRIVATE)
        webView = findViewById(R.id.webview)
        pickButton = findViewById(R.id.pick_folder_button)
        serverView = findViewById(R.id.server_view)
        refreshButton = findViewById(R.id.refresh_button)
        openBrowserButton = findViewById(R.id.open_browser_button)
        disconnectButton = findViewById(R.id.disconnect_button)
        importZipButton = findViewById(R.id.import_zip_button)
        autoScanButton = findViewById(R.id.auto_scan_button)
        startNodeButton = findViewById(R.id.start_node_button)
        viewLogButton = findViewById(R.id.view_log_button)

        webView.webViewClient = WebViewClient()
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_NO_CACHE // 開發測試時避免快取舊檔
        }

        pickButton.setOnClickListener { launchFolderPicker() }
        refreshButton.setOnClickListener {
            server?.rebuildIndex()
            webView.reload()
        }
        openBrowserButton.setOnClickListener { openInExternalBrowser() }
        disconnectButton.setOnClickListener { disconnectFolder() }
        importZipButton.setOnClickListener { launchZipPicker() }
        autoScanButton.setOnClickListener { toggleAutoScan() }
        startNodeButton.setOnClickListener { startNodeEngine() }
        viewLogButton.setOnClickListener { showLogDialog() }

        val savedUri = prefs.getString(KEY_URI, null)
        val restoredDoc = savedUri?.let { tryRestoreFolder(Uri.parse(it)) }
        if (restoredDoc != null) {
            startServing(restoredDoc)
        } else {
            showPicker()
        }
    }

    private fun tryRestoreFolder(treeUri: Uri): DocumentFile? {
        val doc = DocumentFile.fromTreeUri(this, treeUri) ?: return null
        return if (doc.exists() && doc.canRead()) doc else null
    }

    private fun showPicker() {
        serverView.visibility = View.GONE
        pickButton.visibility = View.VISIBLE
    }

    private fun launchFolderPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        startActivityForResult(intent, REQUEST_CODE_PICK_FOLDER)
    }

    private fun launchZipPicker() {
        if (server == null || currentRoot == null) {
            Toast.makeText(this, "請先連接一個資料夾", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQUEST_CODE_PICK_ZIP)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return

        when (requestCode) {
            REQUEST_CODE_PICK_FOLDER -> {
                val treeUri = data?.data ?: return
                contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                prefs.edit().putString(KEY_URI, treeUri.toString()).apply()
                val doc = DocumentFile.fromTreeUri(this, treeUri) ?: return
                startServing(doc)
            }
            REQUEST_CODE_PICK_ZIP -> {
                val zipUri = data?.data ?: return
                importZipFile(zipUri)
            }
        }
    }

    private fun importZipFile(zipUri: Uri) {
        val root = currentRoot ?: return
        val srv = server ?: return
        Toast.makeText(this, "匯入中…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val count = ZipImporter.importZip(contentResolver, zipUri, root, clearFirst = true)
                srv.rebuildIndex()
                runOnUiThread {
                    Toast.makeText(this, "匯入完成，共 $count 個檔案，正在重新整理", Toast.LENGTH_SHORT).show()
                    webView.reload()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "匯入失敗：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun toggleAutoScan() {
        val srv = server ?: return
        if (autoScanWatcher == null) {
            autoScanWatcher = AutoScanWatcher(srv) {
                webView.reload()
            }.also { it.start() }
            autoScanButton.text = "自動掃描：開"
        } else {
            autoScanWatcher?.stop()
            autoScanWatcher = null
            autoScanButton.text = "自動掃描：關"
        }
    }

    /**
     * 顯示 NodeEngine 記錄檔內容——不需要 adb/root/Termux，
     * App 自己讀自己寫的檔案，並提供「分享」讓你可以直接把記錄傳出去（例如傳給我）。
     */
    private fun showLogDialog() {
        val file = NodeEngine.logFile(applicationContext)
        val content = if (file.exists()) file.readText() else "（還沒有任何記錄，先按「啟動 Node 引擎」試試）"

        val textView = android.widget.TextView(this).apply {
            text = content
            textSize = 11f
            setPadding(24, 24, 24, 24)
            setTextIsSelectable(true)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(textView) }

        android.app.AlertDialog.Builder(this)
            .setTitle("記錄檔（node-debug.log）")
            .setView(scroll)
            .setPositiveButton("分享") { _, _ -> shareLogFile(file) }
            .setNegativeButton("關閉", null)
            .show()
    }

    private fun shareLogFile(file: File) {
        if (!file.exists()) {
            Toast.makeText(this, "還沒有記錄檔可以分享", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "分享記錄檔"))
        } catch (e: Exception) {
            Toast.makeText(this, "分享失敗：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 完全獨立的功能：啟動 App 內嵌的 Node.js 引擎，跑 assets/nodejs-project/main.js。
     * 跟上面靜態網站測試（LocalHttpServer, port 8080）完全無關，互不影響。
     * 只能啟動一次（nodejs-mobile 的限制），重複按沒有效果，只會提示已啟動。
     */
    private fun startNodeEngine() {
        if (NodeEngine.hasStarted()) {
            Toast.makeText(this, "Node 引擎已經啟動了，重開 App 才能重啟", Toast.LENGTH_SHORT).show()
            return
        }
        NodeEngine.startIfNeeded(applicationContext)
        startNodeButton.text = "Node 引擎執行中：http://127.0.0.1:3000"
        startNodeButton.isEnabled = false
        Toast.makeText(this, "Node 引擎啟動中，稍等幾秒後可連 http://127.0.0.1:3000", Toast.LENGTH_LONG).show()
    }

    /**
     * 用「同一個 server 物件」的索引來判斷 index.html 存不存在，
     * 檢查跟之後實際 serve() 讀取的是同一份資料，不會有兩次查詢結果兜不起來的問題。
     */
    private fun ensureStarterFile(root: DocumentFile, currentServer: LocalHttpServer) {
        if (currentServer.hasFile("index.html")) return
        val file = root.createFile("text/html", "index.html") ?: run {
            Toast.makeText(this, "建立範例檔失敗，請確認資料夾可寫入", Toast.LENGTH_LONG).show()
            return
        }
        contentResolver.openOutputStream(file.uri)?.use { out ->
            out.write(STARTER_HTML.toByteArray(Charsets.UTF_8))
        }
        currentServer.rebuildIndex()
    }

    private fun startServing(root: DocumentFile) {
        autoScanWatcher?.stop()
        autoScanWatcher = null
        autoScanButton.text = "自動掃描：關"

        server?.stop()
        val newServer = LocalHttpServer(contentResolver, root, port)
        ensureStarterFile(root, newServer)
        newServer.start(NANOHTTPD_TIMEOUT_MS, false)
        server = newServer
        currentRoot = root

        pickButton.visibility = View.GONE
        serverView.visibility = View.VISIBLE
        webView.loadUrl(SERVER_URL)
    }

    private fun openInExternalBrowser() {
        // 引擎沒辦法自己換掉（那等於要自己包一個瀏覽器引擎），
        // 但可以交給系統選擇器，讓你自己選手機上裝的任何瀏覽器打開同一個網址。
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(SERVER_URL))
        val chooser = Intent.createChooser(intent, "選擇瀏覽器開啟")
        try {
            startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(this, "找不到可用的瀏覽器", Toast.LENGTH_SHORT).show()
        }
    }

    /** 中斷目前的資料夾授權，回到選擇畫面，不用清 App 資料。 */
    private fun disconnectFolder() {
        autoScanWatcher?.stop()
        autoScanWatcher = null
        autoScanButton.text = "自動掃描：關"

        server?.stop()
        server = null
        currentRoot = null

        val savedUri = prefs.getString(KEY_URI, null)
        if (savedUri != null) {
            try {
                contentResolver.releasePersistableUriPermission(
                    Uri.parse(savedUri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // 權限可能已經失效，忽略即可
            }
        }
        prefs.edit().remove(KEY_URI).apply()
        showPicker()
    }

    override fun onDestroy() {
        autoScanWatcher?.stop()
        server?.stop()
        super.onDestroy()
    }

    companion object {
        private const val KEY_URI = "webroot_tree_uri"
        private const val REQUEST_CODE_PICK_FOLDER = 42
        private const val REQUEST_CODE_PICK_ZIP = 43
        private const val NANOHTTPD_TIMEOUT_MS = 5000
        private const val SERVER_URL = "http://127.0.0.1:8080/index.html"
        private const val STARTER_HTML = """<!DOCTYPE html>
<html lang="zh-Hant">
<head><meta charset="UTF-8"><title>本地測試頁</title></head>
<body style="font-family:sans-serif;padding:24px;">
<h1>資料夾連接成功！</h1>
<p>直接編輯這個資料夾裡的 index.html（用手機上任何檔案總管/文字編輯器都可以），
存檔後回到這個 App 按上方「重新整理」，或開啟「自動掃描」讓它自己偵測變化，
不用重新編譯 App。</p>
</body></html>"""
    }
}
