#include <jni.h>
#include <cstring>
#include <cstdlib>
#include <thread>
#include <unistd.h>
#include <android/log.h>
#include "node.h"

#define LOG_TAG "NodeEngine"

// 把 Node.js 的 stdout/stderr 導到 logcat，方便在手機上直接看 console.log，
// 不用連電腦看 chrome://inspect。
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

// node 的 libuv 需要所有參數放在連續記憶體裡
extern "C"
JNIEXPORT jint JNICALL
Java_com_example_localserver_NodeEngine_startNodeWithArguments(
        JNIEnv *env,
        jobject /* this */,
        jobjectArray arguments) {

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

    int result = node::Start(argument_count, argv);

    free(argv);
    free(args_buffer);

    return jint(result);
}
