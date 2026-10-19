// Thin JNI wrapper around librime's public C API (BSD-3-Clause).
// The caller (RimeEngine) serializes every call on one worker thread.
// Kotlin methods live on the RimeNative singleton, so they are instance methods.

#include <jni.h>

#include <android/log.h>
#include <rime_api.h>

#include <atomic>
#include <cstring>
#include <string>
#include <vector>

namespace {

constexpr const char* kTag = "FsKeyboard";
constexpr int kMaxCandidates = 200;

RimeApi* g_api = nullptr;
bool g_initialized = false;
std::atomic<bool> g_deploy_failed{false};
std::string g_error;
std::string g_shared;
std::string g_user;
std::string g_log;
std::string g_staging;

jclass g_response_class = nullptr;
jmethodID g_response_ctor = nullptr;

void set_error(const std::string& message) {
    g_error = message;
    __android_log_print(ANDROID_LOG_WARN, kTag, "%s", message.c_str());
}

std::string jstring_to_std(JNIEnv* env, jstring value) {
    if (value == nullptr) {
        return {};
    }
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        return {};
    }
    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
    return out;
}

const char* copy_chars(const char* value) {
    return value == nullptr ? "" : value;
}

int clamp_limit(jint limit) {
    if (limit < 1) {
        return 1;
    }
    if (limit > kMaxCandidates) {
        return kMaxCandidates;
    }
    return limit;
}

RimeSessionId session_of(jlong id) {
    return static_cast<RimeSessionId>(static_cast<uintptr_t>(id));
}

void on_notify(void*, RimeSessionId, const char* type, const char* value) {
    if (type != nullptr && value != nullptr &&
        std::strcmp(type, "deploy") == 0 && std::strcmp(value, "failure") == 0) {
        g_deploy_failed.store(true);
    }
}

jobjectArray to_string_array(JNIEnv* env, const std::vector<std::string>& items) {
    jclass string_class = env->FindClass("java/lang/String");
    jobjectArray array = env->NewObjectArray(
        static_cast<jsize>(items.size()), string_class, nullptr);
    for (jsize i = 0; i < static_cast<jsize>(items.size()); ++i) {
        jstring text = env->NewStringUTF(items[static_cast<size_t>(i)].c_str());
        env->SetObjectArrayElement(array, i, text);
        env->DeleteLocalRef(text);
    }
    env->DeleteLocalRef(string_class);
    return array;
}

jintArray to_int_array(JNIEnv* env, const std::vector<int>& items) {
    jintArray array = env->NewIntArray(static_cast<jsize>(items.size()));
    if (!items.empty()) {
        env->SetIntArrayRegion(array, 0, static_cast<jsize>(items.size()), items.data());
    }
    return array;
}

// Reads preedit plus up to `limit` candidates. Index is librime's global
// candidate index, so a later select_candidate still hits the original item
// after the Kotlin reranker reorders the list.
jobject make_response(JNIEnv* env, RimeSessionId session, bool consumed, int limit) {
    std::string commit;
    std::string preedit;
    std::string preview;
    std::vector<std::string> texts;
    std::vector<std::string> comments;
    std::vector<int> indices;
    int highlighted = 0;

    if (g_api != nullptr && session != 0) {
        RIME_STRUCT(RimeCommit, commit_struct);
        if (g_api->get_commit(session, &commit_struct)) {
            commit = copy_chars(commit_struct.text);
            g_api->free_commit(&commit_struct);
        }

        RIME_STRUCT(RimeContext, context);
        if (g_api->get_context(session, &context)) {
            preedit = copy_chars(context.composition.preedit);
            preview = copy_chars(context.commit_text_preview);
            if (context.menu.page_size > 0 && context.menu.highlighted_candidate_index >= 0) {
                highlighted = context.menu.page_no * context.menu.page_size +
                              context.menu.highlighted_candidate_index;
            }
            g_api->free_context(&context);
        }

        if (RIME_API_AVAILABLE(g_api, candidate_list_begin)) {
            RimeCandidateListIterator iterator{};
            if (g_api->candidate_list_begin(session, &iterator)) {
                int count = 0;
                while (count < limit) {
                    texts.emplace_back(copy_chars(iterator.candidate.text));
                    comments.emplace_back(copy_chars(iterator.candidate.comment));
                    indices.push_back(iterator.index);
                    ++count;
                    if (!g_api->candidate_list_next(&iterator)) {
                        break;
                    }
                }
                g_api->candidate_list_end(&iterator);
            }
        }
    }

    jstring commit_text = env->NewStringUTF(commit.c_str());
    jstring preedit_text = env->NewStringUTF(preedit.c_str());
    jstring preview_text = env->NewStringUTF(preview.c_str());
    jobjectArray text_array = to_string_array(env, texts);
    jobjectArray comment_array = to_string_array(env, comments);
    jintArray index_array = to_int_array(env, indices);
    jobject response = env->NewObject(
        g_response_class,
        g_response_ctor,
        consumed ? JNI_TRUE : JNI_FALSE,
        commit_text,
        preedit_text,
        preview_text,
        text_array,
        comment_array,
        index_array,
        highlighted);
    env->DeleteLocalRef(commit_text);
    env->DeleteLocalRef(preedit_text);
    env->DeleteLocalRef(preview_text);
    env->DeleteLocalRef(text_array);
    env->DeleteLocalRef(comment_array);
    env->DeleteLocalRef(index_array);
    return response;
}

bool ready() {
    return g_api != nullptr && g_initialized;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    jclass local = env->FindClass("com/fskeyboard/ime/bridge/RimeResponse");
    if (local == nullptr) {
        return JNI_ERR;
    }
    g_response_class = reinterpret_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    g_response_ctor = env->GetMethodID(
        g_response_class,
        "<init>",
        "(ZLjava/lang/String;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;[II)V");
    if (g_response_ctor == nullptr) {
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_fskeyboard_ime_bridge_RimeNative_startup(
    JNIEnv* env, jobject, jstring shared_dir, jstring user_dir, jstring log_dir) {
    g_api = rime_get_api();
    if (g_api == nullptr) {
        set_error("rime_get_api returned null");
        return JNI_FALSE;
    }

    g_shared = jstring_to_std(env, shared_dir);
    g_user = jstring_to_std(env, user_dir);
    g_log = jstring_to_std(env, log_dir);
    g_staging = g_user + "/build";
    g_deploy_failed.store(false);
    g_error.clear();

    RIME_STRUCT(RimeTraits, traits);
    traits.shared_data_dir = g_shared.c_str();
    traits.user_data_dir = g_user.c_str();
    traits.staging_dir = g_staging.c_str();
    traits.distribution_name = "FsKeyboard";
    traits.distribution_code_name = "fskeyboard";
    traits.distribution_version = "0.1.0";
    traits.app_name = "rime.fskeyboard";
    traits.min_log_level = 2;
    traits.log_dir = g_log.c_str();

    g_api->setup(&traits);
    if (RIME_API_AVAILABLE(g_api, set_notification_handler)) {
        g_api->set_notification_handler(&on_notify, nullptr);
    }
    g_api->initialize(&traits);
    g_initialized = true;
    if (g_api->start_maintenance(False)) {
        g_api->join_maintenance_thread();
    }
    if (g_deploy_failed.load()) {
        g_api->finalize();
        g_initialized = false;
        set_error("deployment failed");
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_fskeyboard_ime_bridge_RimeNative_shutdown(JNIEnv*, jobject) {
    if (g_api != nullptr && g_initialized) {
        if (RIME_API_AVAILABLE(g_api, set_notification_handler)) {
            g_api->set_notification_handler(nullptr, nullptr);
        }
        g_api->finalize();
        g_initialized = false;
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_fskeyboard_ime_bridge_RimeNative_createSession(JNIEnv*, jobject) {
    if (!ready()) {
        return 0;
    }
    return static_cast<jlong>(g_api->create_session());
}

extern "C" JNIEXPORT void JNICALL
Java_com_fskeyboard_ime_bridge_RimeNative_destroySession(JNIEnv*, jobject, jlong session_id) {
    if (!ready() || session_id == 0) {
        return;
    }
    g_api->destroy_session(session_of(session_id));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_fskeyboard_ime_bridge_RimeNative_selectSchema(
    JNIEnv* env, jobject, jlong session_id, jstring schema_id) {
    if (!ready() || session_id == 0) {
        set_error("select_schema without a session");
        return JNI_FALSE;
    }
    const std::string schema = jstring_to_std(env, schema_id);
    if (!g_api->select_schema(session_of(session_id), schema.c_str())) {
        set_error("select_schema failed: " + schema);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_fskeyboard_ime_bridge_RimeNative_processKey(
    JNIEnv* env, jobject, jlong session_id, jint keycode, jint mask, jint limit) {
    bool consumed = false;
    if (ready() && session_id != 0) {
        consumed = g_api->process_key(session_of(session_id), keycode, mask);
    }
    return make_response(env, session_of(session_id), consumed, clamp_limit(limit));
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_fskeyboard_ime_bridge_RimeNative_selectCandidate(
    JNIEnv* env, jobject, jlong session_id, jint index, jint limit) {
    bool consumed = false;
    if (ready() && session_id != 0 && index >= 0) {
        consumed = g_api->select_candidate(
            session_of(session_id), static_cast<size_t>(index));
    }
    return make_response(env, session_of(session_id), consumed, clamp_limit(limit));
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_fskeyboard_ime_bridge_RimeNative_snapshot(
    JNIEnv* env, jobject, jlong session_id, jint limit) {
    return make_response(env, session_of(session_id), false, clamp_limit(limit));
}

extern "C" JNIEXPORT void JNICALL
Java_com_fskeyboard_ime_bridge_RimeNative_clearComposition(JNIEnv*, jobject, jlong session_id) {
    if (!ready() || session_id == 0) {
        return;
    }
    g_api->clear_composition(session_of(session_id));
}

extern "C" JNIEXPORT void JNICALL
Java_com_fskeyboard_ime_bridge_RimeNative_setOption(
    JNIEnv* env, jobject, jlong session_id, jstring name, jboolean value) {
    if (!ready() || session_id == 0) {
        return;
    }
    const std::string option = jstring_to_std(env, name);
    g_api->set_option(session_of(session_id), option.c_str(), value == JNI_TRUE);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_fskeyboard_ime_bridge_RimeNative_getOption(
    JNIEnv* env, jobject, jlong session_id, jstring name) {
    if (!ready() || session_id == 0) {
        return JNI_FALSE;
    }
    const std::string option = jstring_to_std(env, name);
    return g_api->get_option(session_of(session_id), option.c_str()) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_fskeyboard_ime_bridge_RimeNative_lastError(JNIEnv* env, jobject) {
    return env->NewStringUTF(g_error.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_fskeyboard_ime_bridge_RimeNative_version(JNIEnv* env, jobject) {
    const char* version = (g_api != nullptr && g_api->get_version != nullptr)
                              ? g_api->get_version()
                              : "";
    return env->NewStringUTF(version == nullptr ? "" : version);
}
