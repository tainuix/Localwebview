#include <jni.h>
#include <cstring>
#include <cstdlib>
#include <cstdio>
#include <csignal>
#include <fcntl.h>
#include <thread>
#include <unistd.h>
#include <android/log.h>
#include "node.h"

#define LOG_TAG "NodeEngine"

// 崩潰記錄檔的檔案描述符。用低階 write()（async-signal-safe）而不是 fprintf/FILE*，
// 因為這個 fd 也會在訊號處理常式（signal handler）裡使用，
// 訊號處理常式裡只能呼叫「async-signal-safe」的函式，fprintf 不是，write() 是。
static volatile int g_log_fd = -1;

static void write_log_line(const char *msg) {
    if (g_log_fd >= 0) {
        write(g_log_fd, msg, strlen(msg));
        write(g_log_fd, "\n", 1);
    }
}

// 把 Node.js 的 stdout/stderr 導到 logcat，同時也寫進我們自己的記錄檔，
// 這樣不需要 adb / root 也能在 App 裡直接看到 Node 印出來的東西（含 JS 錯誤訊息）。
static int pipe_fds[2];

static void redirect_loop() {
    ssize_t read_size;
    char buf[2048];
    while ((read_size = read(pipe_fds[0], buf, sizeof(buf) - 1)) > 0) {
        if (buf[read_size - 1] == '\n') {
            read_size -= 1;
        }
        buf[read_size] = 0;
        __android_log_write(ANDROID_LOG_DEBUG, LOG_TAG, buf);
        write_log_line(buf);
    }
}

static void start_redirecting_stdout_stderr() {
    setvbuf(stdout, nullptr, _IOLBF, 0);
    setvbuf(stderr, nullptr, _IOLBF, 0);
    if (pipe(pipe_fds) != 0) {
        return;
    }
    dup2(pipe_fds[1], STDOUT_FILENO);
    dup2(pipe_fds[1], STDERR_FILENO);
    std::thread(redirect_loop).detach();
}

// 常見的致命訊號（native 崩潰，例如 SIGSEGV）不會經過 Java 的例外處理機制，
// 直接在這裡攔一手，至少留一筆「發生過什麼訊號、什麼時候」的紀錄，
// 沒有 adb/root 也能在 App 裡看到「有沒有崩潰、是哪種崩潰」，而不是完全沒有線索。
static void crash_signal_handler(int signal_number) {
    const char *name;
    switch (signal_number) {
        case SIGSEGV: name = "[native crash] SIGSEGV（記憶體存取錯誤）"; break;
        case SIGABRT: name = "[native crash] SIGABRT（程式主動 abort，常見於 V8/Node 的 fatal error）"; break;
        case SIGBUS:  name = "[native crash] SIGBUS"; break;
        case SIGILL:  name = "[native crash] SIGILL"; break;
        case SIGFPE:  name = "[native crash] SIGFPE"; break;
        default:      name = "[native crash] 未知訊號"; break;
    }
    write_log_line(name);
    // 記錄完之後恢復系統預設處理，讓系統照常產生 tombstone、正常結束 process
    signal(signal_number, SIG_DFL);
    raise(signal_number);
}

static void install_crash_handlers() {
    signal(SIGSEGV, crash_signal_handler);
    signal(SIGABRT, crash_signal_handler);
    signal(SIGBUS, crash_signal_handler);
    signal(SIGILL, crash_signal_handler);
    signal(SIGFPE, crash_signal_handler);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_localserver_NodeEngine_nativeSetLogPath(
        JNIEnv *env,
        jobject /* this */,
        jstring path) {
    const char *cpath = env->GetStringUTFChars(path, nullptr);
    int fd = open(cpath, O_WRONLY | O_CREAT | O_APPEND, 0666);
    env->ReleaseStringUTFChars(path, cpath);
    if (fd >= 0) {
        g_log_fd = fd;
    }
}

// node 的 libuv 需要所有參數放在連續記憶體裡
extern "C"
JNIEXPORT jint JNICALL
Java_com_example_localserver_NodeEngine_startNodeWithArguments(
        JNIEnv *env,
        jobject /* this */,
        jobjectArray arguments) {

    install_crash_handlers();
    write_log_line("[native] 準備呼叫 node::Start()");

    jsize argument_count = env->GetArrayLength(arguments);

    int total_bytes = 0;
    for (int i = 0; i < argument_count; i++) {
        auto jstr = (jstring) env->GetObjectArrayElement(arguments, i);
        const char *cstr = env->GetStringUTFChars(jstr, nullptr);
        total_bytes += (int) strlen(cstr) + 1;
        env->ReleaseStringUTFChars(jstr, cstr);
        env->DeleteLocalRef(jstr);
    }

    auto *args_buffer = (char *) calloc(total_bytes, sizeof(char));
    auto **argv = (char **) calloc(argument_count, sizeof(char *));
    char *cursor = args_buffer;

    for (int i = 0; i < argument_count; i++) {
        auto jstr = (jstring) env->GetObjectArrayElement(arguments, i);
        const char *cstr = env->GetStringUTFChars(jstr, nullptr);
        size_t len = strlen(cstr);
        memcpy(cursor, cstr, len);
        argv[i] = cursor;
        cursor += len + 1;
        env->ReleaseStringUTFChars(jstr, cstr);
        env->DeleteLocalRef(jstr);
    }

    start_redirecting_stdout_stderr();
    write_log_line("[native] 開始執行 node::Start()（如果之後沒有任何 [native] 結束訊息，代表在這之後崩潰）");

    int result = node::Start(argument_count, argv);

    char result_msg[64];
    snprintf(result_msg, sizeof(result_msg), "[native] node::Start() 正常返回，結果碼 = %d", result);
    write_log_line(result_msg);

    free(argv);
    free(args_buffer);

    return jint(result);
}
