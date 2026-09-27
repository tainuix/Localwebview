package com.example.localserver

import android.content.Context
import android.content.res.AssetManager
import java.io.File
import java.io.FileOutputStream

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
 */
object NodeEngine {

    init {
        System.loadLibrary("native-lib")
        System.loadLibrary("node")
    }

    @Volatile
    private var started = false

    private external fun startNodeWithArguments(arguments: Array<String>): Int

    /** 啟動 Node 引擎；重複呼叫不會有任何效果（見上方已知限制）。 */
    fun startIfNeeded(context: Context) {
        if (started) return
        started = true
        Thread {
            val nodeDir = File(context.filesDir, "nodejs-project")
            if (nodeDir.exists()) nodeDir.deleteRecursively()
            copyAssetFolder(context.assets, "nodejs-project", nodeDir)
            startNodeWithArguments(arrayOf("node", File(nodeDir, "main.js").absolutePath))
        }.start()
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
