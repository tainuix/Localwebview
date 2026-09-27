# LocalWebViewServer

免 root、單機自用的本地 HTTP server + WebView 測試 App。

## 原理（v2：資料夾版）
- App 第一次啟動時，會跳出系統的「選擇資料夾」畫面，請你選一個資料夾
  （建議在 Documents 或 Downloads 底下新建一個資料夾，例如 `localserver-www`）。
- 選完後 App 會記住這個資料夾（下次啟動不用再選），並在裡面自動建立一個範例 `index.html`。
- App 內建的 NanoHTTPD server 只監聽 `127.0.0.1:8080`，直接讀你選的那個資料夾當網站根目錄。
- WebView 開 `http://127.0.0.1:8080/index.html`，而不是 `file://`，避免 WebView 對 `file://`
  的種種限制（CORS、fetch、部分 API 會失敗）。

## 怎麼測試網頁（不用重新編譯 App）
1. 打開 App，第一次會跳資料夾選擇器，選一個資料夾（或建立新資料夾）授權。
2. 用手機上**任何**檔案總管 / 文字編輯器 App，開啟同一個資料夾，編輯裡面的 `index.html`
   （或加入其他 `.css` / `.js` / 圖片，相對路徑會照樣被 server 回應）。
3. 存檔後回到這個 App，按右上角「重新整理」按鈕，就能立刻看到最新內容。
   **完全不用重新編譯、重新安裝 App。**

## 三個工具列按鈕
- **重新整理**：重新掃一次資料夾＋重新整理 WebView（新增/改名檔案後要按這個）
- **用瀏覽器開啟**：把同一個網址丟給系統選擇器，可以選 Chrome / Firefox 等你手機上裝的瀏覽器打開，
  用比 WebView 更新的引擎測試。WebView 本身的引擎沒辦法在 App 裡手動更換或替換。
- **中斷 / 換資料夾**：釋放目前資料夾的授權，回到選擇畫面，可以直接換一個資料夾，不用再清 App 資料。


## 建置方式
跟之前一樣，用你的建置環境（Android Studio 或 GitHub Actions）針對這個資料夾跑
Gradle 建置 `assembleDebug`，安裝 apk 到手機上即可。

## 安全性
- server 只 bind `127.0.0.1`（loopback），同一台手機以外的裝置連不到。
- 沒有用到任何 root API，也不需要「檔案存取全部權限」這種特殊權限，
  只需要一次性的資料夾授權（Storage Access Framework），符合 Android 現代的隱私限制。

## 匯入 ZIP（v3）
按「匯入 ZIP」選一個 zip 檔（例如自己 `npm run build` 完手動壓縮的產物），
會**清空目前連接的資料夾**再解壓縮 zip 內容進去，適合「測完一版、整包換下一版」的流程。
完成後自動重新整理 WebView。

## 自動掃描（v3）
按「自動掃描：關」切成「開」，之後每 2 秒背景比對一次資料夾內容有沒有變化，
有變化就自動重新整理 WebView。適合直接用手機檔案總管把檔案丟進你連接的那個資料夾、
不想每次手動按重新整理的情境。注意：這是輪詢，不是即時通知，最多會晚個幾秒才反應。
連接新資料夾或按「中斷/換資料夾」時，自動掃描會自動關閉，需要的話要重新開啟。

## 擴充規則（給之後加功能用）
新功能一律寫成獨立檔案（像 `ZipImporter.kt`、`AutoScanWatcher.kt`），只單純呼叫
`LocalHttpServer` 既有的公開方法（`rebuildIndex()` / `hasFile()` / `currentSignature()`），
不去修改 `serve()` 或既有方法內部邏輯。`MainActivity` 只負責加按鈕、接上呼叫點。
這樣每次加新功能都不會動到已經測試過能動的核心程式碼。

## Ultra 版：內嵌 Node.js 引擎（動態後端）
這是完全不同量級的功能，跟前面幾版純 Kotlin 的小模組不一樣，先講清楚代價和限制：

**代價**
- 需要 NDK + CMake 原生 C++ 編譯，`.github/workflows/build.yml` 已經設定好自動安裝，
  用這個工作流程編譯就不用自己裝。
- APK 體積會明顯變大（`libnode.so` 每個架構數十 MB，三個架構加起來可能 +100MB 左右）。
- **我這邊沒有 NDK 環境，沒辦法實際編譯驗證這段 C++ 橋接程式**（`native-lib.cpp`）。
  如果 GitHub Actions 編譯失敗，把錯誤訊息（Actions 分頁的紅字 log）貼給我，我再照著修。

**已知限制**
- Node 引擎（`node::Start()`）每個 App process 只能啟動一次，沒辦法乾淨地重啟，
  所以「啟動 Node 引擎」按鈕只能按一次生效，要換 Node 程式碼的話得整個 App 重新啟動一次
  （不用重新編譯，只要重開 App 就會重新複製 `assets/nodejs-project` 並重啟）。
- 要改 Node 後端的程式碼（`main.js`），目前是包在 apk 的 assets 裡，改了要重新編譯安裝——
  如果之後也想要「資料夾即時讀取、不用重編」，跟前面靜態網站那套一樣可以再做，跟我說。

**怎麼運作**
- 靜態網站測試維持原樣：`http://127.0.0.1:8080`（LocalHttpServer，你原本在用的那套，完全沒動）
- Node 動態後端是新的、獨立的：`http://127.0.0.1:3000`（`NodeEngine` + `assets/nodejs-project/main.js`）
- 兩個服務並存，可以同時開兩個分頁測試，也可以讓靜態網頁用 `fetch('http://127.0.0.1:3000/...')`
  呼叫 Node 後端做真正的動態邏輯（資料庫、API 計算等）。

**怎麼編譯**
用 `.github/workflows/build.yml`（GitHub Actions）編譯，它會自動：
1. 裝 JDK 17、Android SDK、NDK、CMake
2. 去抓最新的 `nodejs-mobile` Android 預編譯庫（`libnode.so` + headers），放進 `app/libnode/`
3. 跑 `gradle assembleDebug`，產出的 apk 在 Actions 分頁的 Artifacts 下載

如果你想手動编译（Android Studio）而不是用 GitHub Actions，要自己去
https://github.com/digidem/nodejs-mobile/releases 下載 `nodejs-mobile-android-*.zip`
（不要 `-lite-` 版），解壓縮後把 `include/` 內容放進 `app/libnode/include/`，
把每個架構的 `libnode.so` 放進 `app/libnode/bin/<架構>/libnode.so`。
