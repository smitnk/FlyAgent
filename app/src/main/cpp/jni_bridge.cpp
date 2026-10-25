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

JNIEXPORT jlong JNICALL Java_com_smitnk_flyagent_NativeLlm_load(JNIEnv* env, jclass, jstring path, jint nCtx, jint threads) {
    std::string err; auto* e = flyllm::load(str(env, path), nCtx, threads, err);
    if (!e) throwIo(env, err);
    return reinterpret_cast<jlong>(e);
}
JNIEXPORT jstring JNICALL Java_com_smitnk_flyagent_NativeLlm_chat(JNIEnv* env, jclass, jlong h, jobjectArray roles, jobjectArray texts, jint maxTokens) {
    std::vector<std::string> r, t;
    for (jsize i = 0; i < env->GetArrayLength(roles); i++) {
        r.push_back(str(env, (jstring)env->GetObjectArrayElement(roles, i)));
        t.push_back(str(env, (jstring)env->GetObjectArrayElement(texts, i)));
    }
    std::string err; auto out = flyllm::chat(reinterpret_cast<flyllm::Engine*>(h), r, t, maxTokens, nullptr, err);
    if (!err.empty() && out.empty()) { throwIo(env, err); return nullptr; }
    return env->NewStringUTF(out.c_str());
}
JNIEXPORT void JNICALL Java_com_smitnk_flyagent_NativeLlm_free(JNIEnv*, jclass, jlong h) { flyllm::release(reinterpret_cast<flyllm::Engine*>(h)); }
}
