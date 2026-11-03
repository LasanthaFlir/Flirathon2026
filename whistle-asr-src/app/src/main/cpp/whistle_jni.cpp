#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <sys/mman.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>

#include "needle.h"

#define LOG_TAG "QnnWhistle"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// The .cact container is mapped and read in place by the engine (per Cactus's
// own docs), so the mmap'd region must stay resident for the process
// lifetime — it is intentionally never munmap'd.
namespace {
const unsigned char *g_cactData = nullptr;
size_t g_cactSize = 0;
}

// ---------------------------------------------------------------------------
// JNI: initWhistle
// jCactPath — absolute path to whistle.cact on internal storage.
// Returns "" on success, error message on failure.
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_qnnwhistle_WhistleInference_initWhistle(
        JNIEnv *env, jobject /*thiz*/, jstring jCactPath) {
    const char *pathChars = env->GetStringUTFChars(jCactPath, nullptr);
    std::string cactPath(pathChars);
    env->ReleaseStringUTFChars(jCactPath, pathChars);

    int fd = open(cactPath.c_str(), O_RDONLY);
    if (fd < 0) {
        std::string msg = "Failed to open .cact file: " + cactPath;
        LOGE("%s", msg.c_str());
        return env->NewStringUTF(msg.c_str());
    }
    struct stat st{};
    if (fstat(fd, &st) != 0) {
        close(fd);
        return env->NewStringUTF("fstat failed on .cact file");
    }
    void *mapped = mmap(nullptr, st.st_size, PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (mapped == MAP_FAILED) {
        return env->NewStringUTF("mmap failed on .cact file");
    }

    g_cactData = static_cast<const unsigned char *>(mapped);
    g_cactSize = static_cast<size_t>(st.st_size);

    int rc = needle_load(g_cactData, g_cactSize);
    if (rc < 0) {
        const char *err = needle_last_error();
        std::string msg = std::string("needle_load failed: ") + (err ? err : "unknown error");
        LOGE("%s", msg.c_str());
        return env->NewStringUTF(msg.c_str());
    }
    LOGI("Whistle loaded (%zu bytes), models=%d", g_cactSize, needle_models());
    return env->NewStringUTF("");
}

// ---------------------------------------------------------------------------
// JNI: transcribe
// Accepts raw PCM-16 mono 16kHz samples (any length) and an optional language
// code ("en", "de", ... or null to auto-detect). Returns the JSON string from
// needle_transcribe(), e.g. {"text":"...","language":"en","ttft_ms":0.0,"decode_tps":0.0}
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_qnnwhistle_WhistleInference_transcribe(
        JNIEnv *env, jobject /*thiz*/, jshortArray jPcm, jstring jLanguage) {
    jsize numSamples = env->GetArrayLength(jPcm);
    jshort *pcmData = env->GetShortArrayElements(jPcm, nullptr);

    std::vector<float> audio(numSamples);
    for (jsize i = 0; i < numSamples; i++) audio[i] = pcmData[i] / 32768.0f;
    env->ReleaseShortArrayElements(jPcm, pcmData, JNI_ABORT);

    std::string langStr;
    const char *language = nullptr;
    if (jLanguage != nullptr) {
        const char *c = env->GetStringUTFChars(jLanguage, nullptr);
        langStr = c;
        env->ReleaseStringUTFChars(jLanguage, c);
        if (!langStr.empty()) language = langStr.c_str();
    }

    static std::vector<char> outBuf(8192);
    int rc = needle_transcribe(audio.data(), (int) audio.size(), language, nullptr, 0,
                                outBuf.data(), (int) outBuf.size());
    if (rc < 0) {
        const char *err = needle_last_error();
        LOGE("needle_transcribe failed: %s", err ? err : "unknown error");
        std::string json = std::string("{\"error\":\"") + (err ? err : "unknown error") + "\"}";
        return env->NewStringUTF(json.c_str());
    }
    return env->NewStringUTF(outBuf.data());
}
