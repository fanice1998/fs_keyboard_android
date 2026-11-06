#include "ChiaKeyCore/ChiaKeyCoreC.h"

#include <jni.h>

#include <string>

namespace {

struct Session {
    CKC_Runtime* runtime;
    CKC_Engine* engine;
};

std::string FrameOf(const CKC_EngineSnapshot& snapshot) {
    std::string out;
    if (snapshot.reading_text) out += snapshot.reading_text;
    out.push_back('\x1f');
    if (snapshot.composing_text) out += snapshot.composing_text;
    out.push_back('\x1f');
    if (snapshot.committed_text) out += snapshot.committed_text;
    out.push_back('\x1f');
    out += snapshot.candidate_state.visible ? '1' : '0';
    out.push_back('\x1f');
    const size_t count = snapshot.candidate_state.candidate_count;
    char** candidates = snapshot.candidate_state.candidates;
    for (size_t index = 0; index < count; ++index) {
        if (index) out.push_back('\x1e');
        if (candidates && candidates[index]) out += candidates[index];
    }
    return out;
}

std::string TakeFrame(CKC_Engine* engine) {
    if (!engine) return std::string();
    CKC_EngineSnapshot snapshot = CKC_EngineCopySnapshot(engine);
    std::string frame = FrameOf(snapshot);
    CKC_EngineSnapshotDestroy(&snapshot);
    return frame;
}

jstring NewFrame(JNIEnv* env, const std::string& frame) {
    return env->NewStringUTF(frame.c_str());
}

Session* AsSession(jlong handle) {
    return reinterpret_cast<Session*>(handle);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_fskeyboard_ime_engine_ChiaKeyNative_nativeOpen(
    JNIEnv* env, jclass, jstring writable, jstring database) {
    const char* writable_path = env->GetStringUTFChars(writable, nullptr);
    const char* database_path = env->GetStringUTFChars(database, nullptr);
    CKC_EnginePaths paths = {};
    paths.loaded_path = writable_path;
    paths.resource_path = writable_path;
    paths.writable_path = writable_path;
    paths.lexicon_database_path = database_path;
    CKC_EngineConfig config = CKC_EngineConfigDefault();
    char* error = nullptr;
    CKC_Runtime* runtime = CKC_RuntimeCreate(&paths, &config, &error);
    CKC_StringDestroy(error);
    error = nullptr;
    CKC_Engine* engine = nullptr;
    if (runtime) engine = CKC_RuntimeCreateEngine(runtime, &error);
    CKC_StringDestroy(error);
    env->ReleaseStringUTFChars(writable, writable_path);
    env->ReleaseStringUTFChars(database, database_path);
    if (!runtime || !engine) {
        if (engine) CKC_EngineDestroy(engine);
        if (runtime) CKC_RuntimeDestroy(runtime);
        return 0;
    }
    return reinterpret_cast<jlong>(new Session{runtime, engine});
}

extern "C" JNIEXPORT void JNICALL
Java_com_fskeyboard_ime_engine_ChiaKeyNative_nativeClose(JNIEnv*, jclass, jlong handle) {
    Session* session = AsSession(handle);
    if (!session) return;
    if (session->engine) CKC_EngineDestroy(session->engine);
    if (session->runtime) CKC_RuntimeDestroy(session->runtime);
    delete session;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_fskeyboard_ime_engine_ChiaKeyNative_nativeReset(JNIEnv*, jclass, jlong handle) {
    Session* session = AsSession(handle);
    if (!session || !session->runtime) return JNI_FALSE;
    if (session->engine) {
        CKC_EngineDestroy(session->engine);
        session->engine = nullptr;
    }
    char* error = nullptr;
    session->engine = CKC_RuntimeCreateEngine(session->runtime, &error);
    CKC_StringDestroy(error);
    if (session->engine) return JNI_TRUE;
    CKC_RuntimeDestroy(session->runtime);
    session->runtime = nullptr;
    delete session;
    return JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_fskeyboard_ime_engine_ChiaKeyNative_nativeAscii(
    JNIEnv* env, jclass, jlong handle, jchar key) {
    Session* session = AsSession(handle);
    if (!session || !session->engine || key > 127) {
        return NewFrame(env, session && session->engine ? TakeFrame(session->engine) : std::string());
    }
    CKC_EngineHandleAsciiKey(session->engine, static_cast<char>(key), CKC_KeyModifiersNone());
    return NewFrame(env, TakeFrame(session->engine));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_fskeyboard_ime_engine_ChiaKeyNative_nativeKey(
    JNIEnv* env, jclass, jlong handle, jint code) {
    Session* session = AsSession(handle);
    if (!session || !session->engine) return NewFrame(env, std::string());
    CKC_KeyEvent event = {};
    event.key_code = code;
    event.modifiers = CKC_KeyModifiersNone();
    CKC_EngineHandleKey(session->engine, &event);
    return NewFrame(env, TakeFrame(session->engine));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_fskeyboard_ime_engine_ChiaKeyNative_nativeSelect(
    JNIEnv* env, jclass, jlong handle, jint index) {
    Session* session = AsSession(handle);
    if (!session || !session->engine || index < 0) return NewFrame(env, std::string());
    CKC_EngineSelectCandidate(session->engine, static_cast<size_t>(index));
    return NewFrame(env, TakeFrame(session->engine));
}

extern "C" JNIEXPORT void JNICALL
Java_com_fskeyboard_ime_engine_ChiaKeyNative_nativeAck(JNIEnv*, jclass, jlong handle) {
    Session* session = AsSession(handle);
    if (!session || !session->engine) return;
    CKC_EngineAcknowledgeCommit(session->engine);
}
