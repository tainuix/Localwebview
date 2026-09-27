package com.example.localserver

import android.content.Context
import android.content.res.AssetManager
import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 獨立模組：負責把 assets/nodejs-project 複製到內部儲存空間，並啟動內嵌的
 * Node.js 引擎執行裡面的 main.js。
 *
 * 完全獨立於 LocalHttpServer（靜態檔案伺服器，port 8080）之外——
 * 這裡的 main.js 建議開在別的 port（範例用 3000）當「動態後端 / API」，
 * 兩者並存互不影響，不會動到既有的靜態網站測試功能。
 *
 * 已知限制（nodejs-mobile 本身的限制，不是這支 App 的 bug）：
 * node::Start() 每個 process 只能呼叫一次，沒辦法乾淨地重啟 Node 引擎，
 * 所以這裡設計成「只啟動一次」，要換 Node 程式碼的話目前得整個 App 重開。
 *
 * 記錄檔設計：沒有 root / adb 的情況下，一般 App 讀不到系統的 logcat，
 * 所以這裡把 Node 的 stdout/stderr、Kotlin 層的例外、native 層的致命訊號，
 * 全部寫進 App 自己的檔案（logFile()），搭配 MainActivity 的「查看記錄」按鈕，
 * 完全不需要 Termux/adb/root 就能看到發生了什麼事。
 */
object NodeEngine {

    init {
        System.loadLibrary("native-lib")
        System.loadLibrary("node")
    }

    @Volatile
    private var started = false

    private external fun startNodeWithArguments(arguments: Array<String>): Int
    private external fun nativeSetLogPath(path: String)

    /** 記錄檔位置，MainActivity 用這個路徑讀取/分享記錄內容。 */
    fun logFile(context: Context): File = File(context.filesDir, "node-debug.log")

    private fun appendLog(context: Context, line: String) {
        try {
            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            logFile(context).appendText("[$timestamp] $line\n")
        } catch (_: Exception) {
            // 記錄本身失敗就算了，不能讓 App 因為寫 log 又出問題
        }
    }

    /** 啟動 Node 引擎；重複呼叫不會有任何效果（見上方已知限制）。 */
    fun startIfNeeded(context: Context) {
        if (started) return
        started = true

        appendLog(context, "=== 啟動 Node 引擎 ===")
        nativeSetLogPath(logFile(context).absolutePath)

        val thread = Thread {
            try {
                // Android 系統本身沒有「暫存目錄」的概念，但 Node.js 內部（os.tmpdir()、
                // 部分模組的暫存檔操作）依賴 TMPDIR 這個環境變數，沒設定的話啟動階段就可能直接崩潰。
                // 官方 nodejs-mobile 的 Cordova / React Native 外掛都是這樣處理的：
                // 把 TMPDIR 指到 App 的快取目錄，HOME 指到 App 的私有資料目錄。
                try {
                    Os.setenv("TMPDIR", context.cacheDir.absolutePath, true)
                    Os.setenv("HOME", context.filesDir.absolutePath, true)
                    appendLog(context, "[kotlin] 已設定 TMPDIR=${context.cacheDir.absolutePath}")
                } catch (e: Exception) {
                    appendLog(context, "[kotlin] 設定 TMPDIR/HOME 失敗：${e.message}")
                }

                val nodeDir = File(context.filesDir, "nodejs-project")
                if (nodeDir.exists()) nodeDir.deleteRecursively()
                copyAssetFolder(context.assets, "nodejs-project", nodeDir)
                appendLog(context, "[kotlin] 已複製 nodejs-project 到 ${nodeDir.absolutePath}")

                val mainJs = File(nodeDir, "main.js")
                if (!mainJs.exists()) {
                    appendLog(context, "[kotlin] 錯誤：找不到 ${mainJs.absolutePath}，assets/nodejs-project 裡可能沒有 main.js")
                    return@Thread
                }

                appendLog(context, "[kotlin] 呼叫 startNodeWithArguments(${mainJs.absolutePath})")
                val result = startNodeWithArguments(arrayOf("node", mainJs.absolutePath))
                appendLog(context, "[kotlin] startNodeWithArguments 返回：$result")
            } catch (t: Throwable) {
                // 這裡只能接到 Kotlin/Java 層的例外（例如複製檔案失敗）。
                // 如果是 native 層的崩潰（SIGSEGV 等），會直接毀掉整個 process，
                // 連這個 catch 都不會執行到——那種情況要看 native-lib.cpp 裡訊號處理常式寫的那行。
                appendLog(context, "[kotlin] 例外：${t.javaClass.simpleName}: ${t.message}")
                appendLog(context, t.stackTraceToString())
            }
        }
        thread.setUncaughtExceptionHandler { _, e ->
            appendLog(context, "[kotlin] 未捕捉例外：${e.javaClass.simpleName}: ${e.message}")
        }
        thread.start()
    }

    fun hasStarted(): Boolean = started

    private fun copyAssetFolder(assets: AssetManager, fromAssetPath: String, toDir: File) {
        toDir.mkdirs()
        val entries = assets.list(fromAssetPath) ?: return
        for (name in entries) {
            val assetPath = "$fromAssetPath/$name"
            val subEntries = assets.list(assetPath)
            if (!subEntries.isNullOrEmpty()) {
                copyAssetFolder(assets, assetPath, File(toDir, name))
            } else {
                assets.open(assetPath).use { input ->
                    FileOutputStream(File(toDir, name)).use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }
}
