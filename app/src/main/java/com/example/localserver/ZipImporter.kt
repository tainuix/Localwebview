package com.example.localserver

import android.content.ContentResolver
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * 獨立模組：把一個 zip 檔的內容解壓縮進指定的 DocumentFile 資料夾。
 * 不會動到 LocalHttpServer / MainActivity 既有邏輯，只單純操作檔案系統。
 * 呼叫完之後，外部要自己呼叫 server.rebuildIndex() 讓 server 看到新內容。
 */
object ZipImporter {

    /**
     * @param clearFirst 匯入前是否先清空目標資料夾內容（預設 true，
     *   符合「測完一版、整包換新版本」的情境；設 false 則是疊加/覆蓋既有檔案）
     * @return 成功匯入的檔案數量
     */
    fun importZip(
        resolver: ContentResolver,
        zipUri: Uri,
        targetRoot: DocumentFile,
        clearFirst: Boolean = true
    ): Int {
        if (clearFirst) {
            targetRoot.listFiles().forEach { it.delete() }
        }

        var count = 0
        val input = resolver.openInputStream(zipUri) ?: throw IOException("無法開啟這個檔案")
        ZipInputStream(input).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val entryPath = entry.name.trim('/')
                if (entryPath.isEmpty()) {
                    entry = zis.nextEntry
                    continue
                }
                if (entry.isDirectory) {
                    getOrCreateDir(targetRoot, entryPath)
                } else {
                    val lastSlash = entryPath.lastIndexOf('/')
                    val dirPath = if (lastSlash >= 0) entryPath.substring(0, lastSlash) else ""
                    val fileName = if (lastSlash >= 0) entryPath.substring(lastSlash + 1) else entryPath
                    val dir = getOrCreateDir(targetRoot, dirPath)

                    // 避免 clearFirst=false 時同名檔案被 SAF 自動改名成 "xxx (1)"
                    dir.listFiles().firstOrNull { it.name == fileName }?.delete()

                    val newFile = dir.createFile(guessMime(fileName), fileName)
                        ?: throw IOException("無法建立檔案: $fileName")
                    resolver.openOutputStream(newFile.uri)?.use { out -> zis.copyTo(out) }
                    count++
                }
                entry = zis.nextEntry
            }
        }
        return count
    }

    private fun getOrCreateDir(root: DocumentFile, path: String): DocumentFile {
        var current = root
        if (path.isEmpty()) return current
        for (seg in path.split("/")) {
            if (seg.isEmpty()) continue
            val existing = current.listFiles().firstOrNull { it.name == seg && it.isDirectory }
            current = existing ?: current.createDirectory(seg)
                ?: throw IOException("無法建立資料夾: $seg")
        }
        return current
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
