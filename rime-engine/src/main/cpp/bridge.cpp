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
template<class T, class F> struct RimeResource {
    T value{};
    F release;
    bool active = false;
    explicit RimeResource(F fn) : release(fn) {}
    ~RimeResource() { if (active) release(&value); }
};

namespace {
std::mutex mutex;
RimeApi* api = rime_get_api();
bool ready = false;
std::string shared, userDataPath, logs;
std::string utf8(JNIEnv* env, jstring s) {
    if (!s) throw std::invalid_argument("null path");
    const auto length = env->GetStringLength(s);
    std::u16string value(static_cast<size_t>(length), u'\0');
    env->GetStringRegion(s, 0, length, reinterpret_cast<jchar*>(value.data()));
    if (env->ExceptionCheck()) throw std::runtime_error("JNI string allocation");
    if (value.empty() || value.find(u'\0') != std::u16string::npos)
        throw std::invalid_argument("invalid path");
    return std::wstring_convert<std::codecvt_utf8_utf16<char16_t>, char16_t>{}.to_bytes(value);
}
jstring javaString(JNIEnv* env, const std::string& s) {
    auto value = std::wstring_convert<std::codecvt_utf8_utf16<char16_t>, char16_t>{}.from_bytes(s);
    return env->NewString(reinterpret_cast<const jchar*>(value.data()), value.size());
}
void fail(JNIEnv* env) {
    if (env->ExceptionCheck()) return;
    auto type = env->FindClass("java/lang/IllegalStateException");
    if (type) { env->ThrowNew(type, "Native engine operation failed"); env->DeleteLocalRef(type); }
}
}
#define JNI(name) Java_org_thanatophilia_xyuime_rime_NativeRime_##name
extern "C" JNIEXPORT jboolean JNICALL JNI(initialize)(JNIEnv* env, jobject, jstring s, jstring u, jstring l) {
    std::lock_guard<std::mutex> lock(mutex);
    try {
        const auto nextShared = utf8(env, s), nextUser = utf8(env, u), nextLogs = utf8(env, l);
        if (ready) {
            if (shared != nextShared || userDataPath != nextUser || logs != nextLogs)
                throw std::invalid_argument("engine already initialized with other paths");
            return true;
        }
        shared = nextShared; userDataPath = nextUser; logs = nextLogs;
        RIME_STRUCT(RimeTraits, traits);
        traits.shared_data_dir = shared.c_str(); traits.user_data_dir = userDataPath.c_str();
        traits.log_dir = logs.c_str(); traits.app_name = "rime.xyu";
        traits.distribution_name = "Xyu"; traits.distribution_code_name = "xyu";
        traits.distribution_version = "0.3"; traits.min_log_level = 3;
        api->setup(&traits);
        try {
            api->initialize(&traits);
            // deploy() is synchronous and reports failure; joining maintenance alone does not.
            api->deployer_initialize(&traits);
            if (!api->deploy()) throw std::runtime_error("deployment failed");
            auto probe = api->create_session();
            bool usable = probe && api->select_schema(probe, "xyu_pinyin");
            if (usable) {
                // select_schema accepts even a nonexistent schema in 1.17.0.
                // Check the actual translator without committing or learning probe input.
                for (const char key : std::string("nihao")) api->process_key(probe, key, 0);
                RimeCandidateListIterator it{};
                usable = api->candidate_list_begin(probe, &it);
                if (usable) {
                    usable = api->candidate_list_next(&it);
                    api->candidate_list_end(&it);
                }
            }
            if (probe) api->destroy_session(probe);
            if (!usable) throw std::runtime_error("schema unavailable");
            ready = true;
            return true;
        } catch (...) {
            api->finalize();
            throw;
        }
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
        if (op < 0 || op > 4) throw std::invalid_argument("unknown operation");
        bool consumed = false;
        if (op == 1) consumed = api->process_key(id, key, 0);
        if (op == 2 && key >= 0) consumed = api->select_candidate(id, key);
        if (op == 3) { api->clear_composition(id); consumed = true; }
        if (op == 4) consumed = api->commit_composition(id);
        // Wire format: seven header strings, then (text, comment) pairs in global order.
        // Keep this in lockstep with RimeEngine's parser; empty comments still occupy a slot.
        std::vector<std::string> data{consumed ? "1" : "0", "", "", "0", "0", "0", "1"};
        RimeResource<RimeCommit, decltype(api->free_commit)> commitOwner(api->free_commit);
        auto& commit = commitOwner.value;
        RIME_STRUCT_INIT(RimeCommit, commit);
        if ((commitOwner.active = api->get_commit(id, &commit))) {
            data[1] = commit.text ? commit.text : "";
        }
        RimeResource<RimeContext, decltype(api->free_context)> contextOwner(api->free_context);
        auto& ctx = contextOwner.value;
        RIME_STRUCT_INIT(RimeContext, ctx);
        if ((contextOwner.active = api->get_context(id, &ctx))) {
            data[2] = ctx.composition.preedit ? ctx.composition.preedit : "";
            // Rime cursor is a UTF-8 byte offset; Kotlin needs UTF-16 code units.
            auto prefix = data[2].substr(0, static_cast<size_t>(ctx.composition.cursor_pos));
            data[3] = std::to_string(std::wstring_convert<std::codecvt_utf8_utf16<char16_t>, char16_t>{}.from_bytes(prefix).size());
            data[4] = std::to_string(ctx.menu.page_no * ctx.menu.page_size + ctx.menu.highlighted_candidate_index);
            data[5] = std::to_string(ctx.menu.page_no);
        }
        RimeResource<RimeCandidateListIterator, decltype(api->candidate_list_end)> iteratorOwner(api->candidate_list_end);
        auto& it = iteratorOwner.value;
        limit = std::clamp(limit, 1, 4096);
        if ((iteratorOwner.active = api->candidate_list_begin(id, &it))) {
            int count = 0;
            while (api->candidate_list_next(&it)) {
                if (count++ >= limit) { data[6] = "0"; break; }
                // next()/end() release both pointers, so copy both before advancing.
                data.emplace_back(it.candidate.text ? it.candidate.text : "");
                data.emplace_back(it.candidate.comment ? it.candidate.comment : "");
            }
        }
        auto stringClass = env->FindClass("java/lang/String");
        if (!stringClass) return nullptr;
        auto array = env->NewObjectArray(static_cast<jsize>(data.size()), stringClass, nullptr);
        env->DeleteLocalRef(stringClass);
        if (!array) return nullptr;
        for (size_t i = 0; i < data.size(); ++i) {
            auto value = javaString(env, data[i]);
            if (!value) return nullptr;
            env->SetObjectArrayElement(array, static_cast<jsize>(i), value);
            env->DeleteLocalRef(value);
            if (env->ExceptionCheck()) return nullptr;
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
