// JNI bindings for the connectome engine and the local LLM.
#include <jni.h>
#include <string>
#include <vector>
#include "connectome.h"
#include "llm_core.h"

static std::string str(JNIEnv* env, jstring s) {
    const char* c = env->GetStringUTFChars(s, nullptr); std::string r(c); env->ReleaseStringUTFChars(s, c); return r;
}
static void throwIo(JNIEnv* env, const std::string& msg) { env->ThrowNew(env->FindClass("java/io/IOException"), msg.c_str()); }
static std::vector<uint32_t> ints(JNIEnv* env, jintArray a) {
    std::vector<uint32_t> v; if (!a) return v;
    jsize n = env->GetArrayLength(a); v.resize(n);
    env->GetIntArrayRegion(a, 0, n, reinterpret_cast<jint*>(v.data())); return v;
}
static volatile bool gCancel = false;

extern "C" {

JNIEXPORT jlong JNICALL Java_com_smitnk_flyagent_NativeBrain_load(JNIEnv* env, jclass, jstring path) {
    auto* c = new fly::Connectome; std::string err;
    if (!fly::load(str(env, path), *c, err)) { delete c; throwIo(env, err); return 0; }
    return reinterpret_cast<jlong>(c);
}
JNIEXPORT void JNICALL Java_com_smitnk_flyagent_NativeBrain_free(JNIEnv*, jclass, jlong h) { delete reinterpret_cast<fly::Connectome*>(h); }
JNIEXPORT jstring JNICALL Java_com_smitnk_flyagent_NativeBrain_source(JNIEnv* env, jclass, jlong h) {
    return env->NewStringUTF(reinterpret_cast<fly::Connectome*>(h)->source.c_str());
}
JNIEXPORT jlong JNICALL Java_com_smitnk_flyagent_NativeBrain_edges(JNIEnv*, jclass, jlong h) { return (jlong) reinterpret_cast<fly::Connectome*>(h)->e(); }
JNIEXPORT jlongArray JNICALL Java_com_smitnk_flyagent_NativeBrain_ids(JNIEnv* env, jclass, jlong h) {
    auto* c = reinterpret_cast<fly::Connectome*>(h);
    jlongArray a = env->NewLongArray(c->n());
    env->SetLongArrayRegion(a, 0, c->n(), reinterpret_cast<const jlong*>(c->ids.data())); return a;
}
// returns [totalSpikes, wallMs, spikes per neuron...]
JNIEXPORT jlongArray JNICALL Java_com_smitnk_flyagent_NativeBrain_simulate(JNIEnv* env, jclass, jlong h, jintArray exc,
        jdouble hz, jintArray sil, jdouble ms, jint trials, jlong seed) {
    auto* c = reinterpret_cast<fly::Connectome*>(h);
    gCancel = false;
    auto r = fly::simulate(*c, ints(env, exc), hz, ints(env, sil), ms, trials, (uint64_t)seed, fly::Params(), &gCancel);
    std::vector<jlong> out(2 + r.spikes.size());
    out[0] = (jlong)r.totalSpikes; out[1] = (jlong)r.wallMs;
    for (size_t i = 0; i < r.spikes.size(); i++) out[2 + i] = r.spikes[i];
    jlongArray a = env->NewLongArray(out.size()); env->SetLongArrayRegion(a, 0, out.size(), out.data()); return a;
}
JNIEXPORT void JNICALL Java_com_smitnk_flyagent_NativeBrain_cancel(JNIEnv*, jclass) { gCancel = true; }

JNIEXPORT jstring JNICALL Java_com_smitnk_flyagent_NativeLlm_loadBackends(JNIEnv* env, jclass, jstring dir) {
    return env->NewStringUTF(flyllm::loadBackends(str(env, dir)).c_str());
}
JNIEXPORT jstring JNICALL Java_com_smitnk_flyagent_NativeLlm_stats(JNIEnv* env, jclass, jlong h) {
    return env->NewStringUTF(flyllm::stats(reinterpret_cast<flyllm::Engine*>(h)).c_str());
}
JNIEXPORT jlong JNICALL Java_com_smitnk_flyagent_NativeLlm_load(JNIEnv* env, jclass, jstring path, jint nCtx, jint threads) {
    std::string err; auto* e = flyllm::load(str(env, path), nCtx, threads, err);
    if (!e) throwIo(env, err);
    return reinterpret_cast<jlong>(e);
}
// Length of the longest prefix of s that ends on a complete UTF-8 character (tokens can split multi-byte chars).
static size_t completeUtf8(const std::string& s) {
    size_t n = s.size(), i = n;
    while (i > 0 && n - i < 3 && ((unsigned char)s[i - 1] & 0xC0) == 0x80) i--;   // skip up to 3 continuation bytes
    if (i == 0) return n;
    unsigned char lead = (unsigned char)s[i - 1];
    size_t need = lead >= 0xF0 ? 4 : lead >= 0xE0 ? 3 : lead >= 0xC0 ? 2 : 1;
    return (n - (i - 1) >= need) ? n : i - 1;
}
static jbyteArray bytes(JNIEnv* env, const std::string& s) {
    jbyteArray a = env->NewByteArray(s.size()); env->SetByteArrayRegion(a, 0, s.size(), (const jbyte*)s.data()); return a;
}

// Text crosses JNI as UTF-8 bytes (NewStringUTF rejects emoji). listener.onToken(byte[]) returning false stops generation.
JNIEXPORT jbyteArray JNICALL Java_com_smitnk_flyagent_NativeLlm_chat(JNIEnv* env, jclass, jlong h, jobjectArray roles, jobjectArray texts, jint maxTokens, jobject listener) {
    std::vector<std::string> r, t;
    for (jsize i = 0; i < env->GetArrayLength(roles); i++) {
        r.push_back(str(env, (jstring)env->GetObjectArrayElement(roles, i)));
        t.push_back(str(env, (jstring)env->GetObjectArrayElement(texts, i)));
    }
    jmethodID onToken = listener ? env->GetMethodID(env->GetObjectClass(listener), "onToken", "([B)Z") : nullptr;
    std::string pending;
    auto cb = [&](const std::string& piece) -> bool {
        if (!onToken) return true;
        pending += piece;
        size_t k = completeUtf8(pending);
        if (k == 0) return true;
        jbyteArray a = bytes(env, pending.substr(0, k)); pending.erase(0, k);
        jboolean go = env->CallBooleanMethod(listener, onToken, a);
        env->DeleteLocalRef(a);
        return go && !env->ExceptionCheck();
    };
    std::string err; auto out = flyllm::chat(reinterpret_cast<flyllm::Engine*>(h), r, t, maxTokens, cb, err);
    if (env->ExceptionCheck()) return nullptr;
    if (!err.empty() && out.empty()) { throwIo(env, err); return nullptr; }
    return bytes(env, out);
}
JNIEXPORT void JNICALL Java_com_smitnk_flyagent_NativeLlm_free(JNIEnv*, jclass, jlong h) { flyllm::release(reinterpret_cast<flyllm::Engine*>(h)); }
}
