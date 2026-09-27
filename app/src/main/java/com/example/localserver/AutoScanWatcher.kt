package com.example.localserver

import android.os.Handler
import android.os.Looper

/**
 * 獨立的自動掃描模組。
 *
 * 為什麼是輪詢（每隔幾秒重掃一次）而不是即時通知：
 * 使用者透過系統資料夾選擇器（SAF）授權的資料夾是 content:// URI，不是真正的檔案路徑，
 * 拿不到系統原生的「檔案異動通知」（FileObserver 之類）。輪詢雖然不是即時，
 * 但不管底層是內部儲存、記憶卡、或哪個 provider，都能穩定運作。
 *
 * 效能重點：rebuildIndex() 會做 SAF 目錄查詢（I/O），絕對不能在主執行緒（UI 執行緒）跑，
 * 不然畫面會卡頓。這裡用一條獨立的背景 Thread 輪詢，只有偵測到真的有變化時，
 * 才把 onChanged 丟回主執行緒執行（因為它通常會操作 WebView，WebView 只能在主執行緒操作）。
 *
 * 純粹疊加在 LocalHttpServer 既有的 rebuildIndex()/currentSignature() 之上，
 * 不會修改 LocalHttpServer 的 serve() 邏輯。
 */
class AutoScanWatcher(
    private val server: LocalHttpServer,
    private val intervalMs: Long = 2000L,
    private val onChanged: () -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var thread: Thread? = null

    @Volatile
    private var running = false

    fun start() {
        if (running) return
        running = true
        thread = Thread {
            // 先建立基準值，避免一啟動就被誤判成「內容有變化」而立刻重整一次
            var lastSignature: String? = safeSignature()
            while (running) {
                try {
                    Thread.sleep(intervalMs)
                } catch (_: InterruptedException) {
                    break
                }
                if (!running) break
                val sig = safeSignature()
                if (lastSignature != null && sig != lastSignature) {
                    mainHandler.post(onChanged)
                }
                lastSignature = sig
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun safeSignature(): String? = try {
        server.rebuildIndex()
        server.currentSignature()
    } catch (_: Exception) {
        null // 資料夾暫時讀不到（例如剛好被中斷連線），跳過這一輪即可
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }
}
