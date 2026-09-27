package com.example.localserver

import android.content.ContentResolver
import androidx.documentfile.provider.DocumentFile
import fi.iki.elonen.NanoHTTPD
import java.io.IOException

/**
 * 從使用者透過系統資料夾選擇器授權的資料夾讀取檔案。
 *
 * 效能與正確性設計：SAF 的 listFiles() 每次呼叫都要問系統的 DocumentsProvider，
 * 比直接讀檔案慢很多，而且如果「檢查檔案是否存在」跟「實際讀取檔案」各自呼叫一次
 * listFiles()，兩次結果在某些情況下可能不一致（快取時機差異），容易誤判。
 * 這裡改成整個資料夾只掃一次、建成索引表放記憶體裡，之後所有查詢
 * （包含 hasFile 檢查）都查同一份索引，不會再有不一致的問題。
 * 索引只在 rebuildIndex() 被呼叫時（App 啟動、連接資料夾、或按重新整理）才重建。
 */
class LocalHttpServer(
    private val contentResolver: ContentResolver,
    private val root: DocumentFile,
    port: Int
) : NanoHTTPD("127.0.0.1", port) {

    @Volatile
    private var index: Map<String, DocumentFile> = emptyMap()

    init {
        rebuildIndex()
    }

    /** 重新掃一次資料夾，建立 相對路徑 -> 檔案 的索引表。 */
    fun rebuildIndex() {
        val map = HashMap<String, DocumentFile>()
        walk(root, "", map)
        index = map
    }

    /** 用跟 serve() 完全同一份索引表檢查檔案是否存在，避免兩次查詢結果不一致。 */
    fun hasFile(relativePath: String): Boolean = index.containsKey(relativePath)

    /**
     * 給自動掃描模組用：回傳目前索引的簡易簽章（路徑＋大小＋修改時間）。
     * 資料夾內容只要有變化，這個字串就會跟著變，藉此判斷「有沒有變動」不用比對整份內容。
     * 純附加方法，不影響 serve() 既有行為。
     */
    fun currentSignature(): String =
        index.entries.sortedBy { it.key }
            .joinToString("|") { (path, doc) -> "$path:${doc.length()}:${doc.lastModified()}" }

    private fun walk(dir: DocumentFile, prefix: String, out: MutableMap<String, DocumentFile>) {
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            val path = if (prefix.isEmpty()) name else "$prefix/$name"
            if (child.isDirectory) {
                walk(child, path, out)
            } else {
                out[path] = child
            }
        }
    }

    override fun serve(session: IHTTPSession): Response {
        var uri = session.uri ?: "/"
        if (uri == "/") uri = "/index.html"
        val relativePath = uri.removePrefix("/")

        val file = index[relativePath]
        if (file == null) {
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                MIME_PLAINTEXT,
                "404 Not Found: $uri\n\n(找不到檔案。如果你剛新增/改名檔案，先按 App 上方「重新整理」讓索引更新)"
            )
        }

        return try {
            val stream = contentResolver.openInputStream(file.uri)
                ?: throw IOException("openInputStream 回傳 null")
            newChunkedResponse(Response.Status.OK, guessMime(file.name ?: ""), stream)
        } catch (e: IOException) {
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                "讀取檔案失敗: ${e.message}"
            )
        }
    }

    private fun guessMime(name: String): String = when {
        name.endsWith(".html") || name.endsWith(".htm") -> "text/html"
        name.endsWith(".css") -> "text/css"
        name.endsWith(".js") -> "application/javascript"
        name.endsWith(".json") -> "application/json"
        name.endsWith(".png") -> "image/png"
        name.endsWith(".jpg") || name.endsWith(".jpeg") -> "image/jpeg"
        name.endsWith(".svg") -> "image/svg+xml"
        name.endsWith(".woff2") -> "font/woff2"
        else -> "application/octet-stream"
    }
}
