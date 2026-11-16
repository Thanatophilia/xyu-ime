#include <jni.h>
#include <rime_api.h>
#include <string>
#include <vector>
#include <codecvt>
#include <locale>
#include <mutex>
#include <algorithm>
#include <stdexcept>

// Always release librime-owned buffers, including when UTF conversion/allocation throws.
template<class T> struct RimeResource {
    T value{};
    void (*release)(T*);
    bool active = false;
    explicit RimeResource(void (*fn)(T*)) : release(fn) {}
    ~RimeResource() { if (active) release(&value); }
};

namespace {
std::mutex mutex;
RimeApi* api = rime_get_api();
bool ready = false;
std::string shared, user, logs;
std::string utf8(JNIEnv* env, jstring s) {
    if (!s) return {};
    const jchar* p = env->GetStringChars(s, nullptr);
    if (!p) return {};
    std::u16string value(reinterpret_cast<const char16_t*>(p), env->GetStringLength(s));
    env->ReleaseStringChars(s, p);
    return std::wstring_convert<std::codecvt_utf8_utf16<char16_t>, char16_t>{}.to_bytes(value);
}
jstring javaString(JNIEnv* env, const std::string& s) {
    auto value = std::wstring_convert<std::codecvt_utf8_utf16<char16_t>, char16_t>{}.from_bytes(s);
    return env->NewString(reinterpret_cast<const jchar*>(value.data()), value.size());
}
void fail(JNIEnv* env) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Native engine operation failed");
}
}
#define JNI(name) Java_org_thanatophilia_xyuime_rime_NativeRime_##name
extern "C" JNIEXPORT jboolean JNICALL JNI(initialize)(JNIEnv* env, jobject, jstring s, jstring u, jstring l) {
    std::lock_guard<std::mutex> lock(mutex);
    try {
        if (ready) return true;
        shared = utf8(env, s); user = utf8(env, u); logs = utf8(env, l);
        RIME_STRUCT(RimeTraits, traits);
        traits.shared_data_dir = shared.c_str(); traits.user_data_dir = user.c_str();
        traits.log_dir = logs.c_str(); traits.app_name = "rime.xyu";
        traits.distribution_name = "Xyu"; traits.distribution_code_name = "xyu";
        traits.distribution_version = "0.3"; traits.min_log_level = 3;
        api->setup(&traits); api->initialize(&traits); ready = true;
        if (api->start_maintenance(true)) api->join_maintenance_thread();
        return true;
    } catch (...) { fail(env); return false; }
}
extern "C" JNIEXPORT jlong JNICALL JNI(createSession)(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(mutex);
    try {
        if (!ready) return 0;
        auto id = api->create_session();
        if (!id || !api->select_schema(id, "xyu_pinyin")) { if (id) api->destroy_session(id); return 0; }
        api->set_option(id, "ascii_mode", false);
        return static_cast<jlong>(id);
    } catch (...) { fail(env); return 0; }
}
// op: 0 snapshot, 1 key, 2 global selection, 3 clear, 4 commit composition.
// One call drains commits and snapshots context atomically on the worker.
extern "C" JNIEXPORT jobjectArray JNICALL JNI(step)(JNIEnv* env, jobject, jlong handle, jint op, jint key, jint limit) {
    std::lock_guard<std::mutex> lock(mutex);
    try {
        auto id = static_cast<RimeSessionId>(handle);
        if (!ready || !api->find_session(id)) { fail(env); return nullptr; }
        bool consumed = false;
        if (op == 1) consumed = api->process_key(id, key, 0);
        if (op == 2 && key >= 0) consumed = api->select_candidate(id, key);
        if (op == 3) { api->clear_composition(id); consumed = true; }
        if (op == 4) consumed = api->commit_composition(id);
        std::vector<std::string> data{consumed ? "1" : "0", "", "", "0", "0", "0", "1"};
        RIME_STRUCT(RimeCommit, commit);
        if (api->get_commit(id, &commit)) {
            data[1] = commit.text ? commit.text : ""; api->free_commit(&commit);
        }
        RIME_STRUCT(RimeContext, ctx);
        if (api->get_context(id, &ctx)) {
            data[2] = ctx.composition.preedit ? ctx.composition.preedit : "";
            // Rime cursor is a UTF-8 byte offset; Kotlin needs UTF-16 code units.
            auto prefix = data[2].substr(0, static_cast<size_t>(ctx.composition.cursor_pos));
            data[3] = std::to_string(std::wstring_convert<std::codecvt_utf8_utf16<char16_t>, char16_t>{}.from_bytes(prefix).size());
            data[4] = std::to_string(ctx.menu.page_no * ctx.menu.page_size + ctx.menu.highlighted_candidate_index);
            data[5] = std::to_string(ctx.menu.page_no);
            api->free_context(&ctx);
        }
        RimeCandidateListIterator it{};
        if (api->candidate_list_begin(id, &it)) {
            int count = 0;
            while (api->candidate_list_next(&it)) {
                if (count++ >= limit) { data[6] = "0"; break; }
                data.emplace_back(it.candidate.text ? it.candidate.text : "");
            }
            api->candidate_list_end(&it);
        }
        auto array = env->NewObjectArray(data.size(), env->FindClass("java/lang/String"), nullptr);
        for (size_t i = 0; i < data.size(); ++i) {
            auto value = javaString(env, data[i]); env->SetObjectArrayElement(array, i, value); env->DeleteLocalRef(value);
        }
        return array;
    } catch (...) { fail(env); return nullptr; }
}
extern "C" JNIEXPORT void JNICALL JNI(destroySession)(JNIEnv* env, jobject, jlong id) {
    std::lock_guard<std::mutex> lock(mutex);
    try { if (ready && id) api->destroy_session(static_cast<RimeSessionId>(id)); } catch (...) { fail(env); }
}
extern "C" JNIEXPORT void JNICALL JNI(shutdown)(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(mutex);
    try { if (ready) { api->finalize(); ready = false; } } catch (...) { fail(env); }
}
