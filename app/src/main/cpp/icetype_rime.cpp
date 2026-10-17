// IceType 自研 JNI 桥接层：把 librime 的 C API 暴露给 Kotlin。
//
// 设计取舍：
//  * 直接 include 官方 rime_api.h，通过 rime_get_api() 获取版本化的 API 结构体，
//    比直接链接导出符号更安全（可用 RIME_API_AVAILABLE 做能力探测）。
//  * 候选人列表以 JSON 字符串整体回传，避免 JNI 高频跨边界调用。
//  * 通知（部署进度/失败原因）通过静态方法回调 Kotlin，不依赖 glog。

#include <jni.h>
#include <android/log.h>

#include <cstring>
#include <deque>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

#include "rime_api.h"

#define LOG_TAG "IceTypeRime"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

RimeApi* g_api = nullptr;

std::string g_shared_dir;
std::string g_user_dir;
std::string g_log_dir;
std::string g_version;

/**
 * 引擎通知队列。
 *
 * 这里刻意**不做 JNI 回调**：早期实现用 env->GetStaticMethodID 缓存 Kotlin 侧静态方法来回调，
 * 在真机（Android 17 / ART）上出现了 `GetStaticMethodID` → `ClassLinker::InitializeClass`
 * → `PrettyDescriptor` 的空指针崩溃（tombstone 已确认）。改为在 native 侧保存通知，
 * 由 Kotlin 侧轮询取走：既不触发任何 JNI 类查找，也避免跨线程回调用。
 */
std::mutex g_notify_mutex;
std::deque<std::pair<std::string, std::string>> g_notifications;
constexpr size_t kMaxNotifications = 64;

std::string jstr(JNIEnv* env, jstring s) {
  if (s == nullptr) return {};
  const char* c = env->GetStringUTFChars(s, nullptr);
  std::string out = c ? c : "";
  if (c) env->ReleaseStringUTFChars(s, c);
  return out;
}

jstring to_jstring(JNIEnv* env, const std::string& s) {
  return env->NewStringUTF(s.c_str());
}

/** 最小 JSON 字符串转义（librime 的候选文本基本都是 UTF-8 明文）。 */
void append_json_string(std::string* out, const char* s) {
  out->push_back('"');
  if (s != nullptr) {
    for (const unsigned char* p = reinterpret_cast<const unsigned char*>(s); *p; ++p) {
      switch (*p) {
        case '"': *out += "\\\""; break;
        case '\\': *out += "\\\\"; break;
        case '\n': *out += "\\n"; break;
        case '\r': *out += "\\r"; break;
        case '\t': *out += "\\t"; break;
        default:
          if (*p < 0x20) {
            char buf[8];
            snprintf(buf, sizeof(buf), "\\u%04x", *p);
            *out += buf;
          } else {
            out->push_back(static_cast<char>(*p));
          }
      }
    }
  }
  out->push_back('"');
}

void on_notification(void* /*context*/, RimeSessionId /*session*/,
                     const char* message_type, const char* message_value) {
  std::lock_guard<std::mutex> lock(g_notify_mutex);
  g_notifications.emplace_back(message_type ? message_type : "",
                               message_value ? message_value : "");
  while (g_notifications.size() > kMaxNotifications) g_notifications.pop_front();
}

bool ensure_api() {
  if (g_api == nullptr) g_api = rime_get_api();
  return g_api != nullptr;
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_xfgken_iceime_rime_RimeNative_nativePollNotification(JNIEnv* env, jclass) {
  std::lock_guard<std::mutex> lock(g_notify_mutex);
  if (g_notifications.empty()) return nullptr;
  auto notification = g_notifications.front();
  g_notifications.pop_front();
  std::string packed = notification.first + "\u0001" + notification.second;
  return to_jstring(env, packed);
}

JNIEXPORT jstring JNICALL
Java_com_xfgken_iceime_rime_RimeNative_nativeVersion(JNIEnv* env, jclass) {
  if (!ensure_api()) return to_jstring(env, "librime 不可用");
  const char* v = RIME_API_AVAILABLE(g_api, get_version) ? g_api->get_version() : nullptr;
  return to_jstring(env, v ? v : "(未知)");
}

JNIEXPORT jboolean JNICALL
Java_com_xfgken_iceime_rime_RimeNative_nativeSetup(JNIEnv* env, jclass, jstring shared_dir,
                                                   jstring user_dir, jstring log_dir,
                                                   jstring version) {
  if (!ensure_api()) {
    LOGE("rime_get_api() 返回空，无法初始化");
    return JNI_FALSE;
  }
  g_shared_dir = jstr(env, shared_dir);
  g_user_dir = jstr(env, user_dir);
  g_log_dir = jstr(env, log_dir);
  g_version = jstr(env, version);

  RimeTraits traits = {0};
  RIME_STRUCT_INIT(RimeTraits, traits);
  traits.shared_data_dir = g_shared_dir.c_str();
  traits.user_data_dir = g_user_dir.c_str();
  traits.distribution_name = "IceType";
  traits.distribution_code_name = "icetype";
  traits.distribution_version = g_version.c_str();
  traits.app_name = "rime.icetype";
  traits.min_log_level = 1;
  traits.log_dir = g_log_dir.c_str();
  g_api->setup(&traits);
  if (RIME_API_AVAILABLE(g_api, set_notification_handler)) {
    // 通知改为 native 侧入队，由 Kotlin 轮询取走（不做 JNI 回调）
    g_api->set_notification_handler(on_notification, nullptr);
  }
  LOGI("RimeSetup 完成 shared=%s user=%s", g_shared_dir.c_str(), g_user_dir.c_str());
  return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_xfgken_iceime_rime_RimeNative_nativeInitialize(JNIEnv* env, jclass, jstring version) {
  if (!ensure_api()) return JNI_FALSE;
  std::string v = jstr(env, version);
  RimeTraits traits = {0};
  RIME_STRUCT_INIT(RimeTraits, traits);
  traits.shared_data_dir = g_shared_dir.c_str();
  traits.user_data_dir = g_user_dir.c_str();
  traits.distribution_name = "IceType";
  traits.distribution_code_name = "icetype";
  traits.distribution_version = v.c_str();
  traits.app_name = "rime.icetype";
  traits.min_log_level = 1;
  traits.log_dir = g_log_dir.c_str();
  g_api->initialize(&traits);
  return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_xfgken_iceime_rime_RimeNative_nativeFinalize(JNIEnv*, jclass) {
  if (ensure_api()) g_api->finalize();
}

JNIEXPORT jboolean JNICALL
Java_com_xfgken_iceime_rime_RimeNative_nativeStartMaintenance(JNIEnv*, jclass, jboolean full) {
  if (!ensure_api()) return JNI_FALSE;
  return g_api->start_maintenance(full) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_xfgken_iceime_rime_RimeNative_nativeJoinMaintenance(JNIEnv*, jclass) {
  if (ensure_api()) g_api->join_maintenance_thread();
}

JNIEXPORT jboolean JNICALL
Java_com_xfgken_iceime_rime_RimeNative_nativeIsMaintenanceMode(JNIEnv*, jclass) {
  if (!ensure_api()) return JNI_FALSE;
  return g_api->is_maintenance_mode() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_xfgken_iceime_rime_RimeNative_nativeDeploy(JNIEnv*, jclass) {
  if (!ensure_api()) return JNI_FALSE;
  return g_api->deploy() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_xfgken_iceime_rime_RimeNative_nativeDeploySchema(JNIEnv* env, jclass, jstring file) {
  if (!ensure_api()) return JNI_FALSE;
  return g_api->deploy_schema(jstr(env, file).c_str()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_xfgken_iceime_rime_RimeNative_nativeCreateSession(JNIEnv*, jclass) {
  if (!ensure_api()) return 0;
  return static_cast<jlong>(g_api->create_session());
}

JNIEXPORT jboolean JNICALL
Java_com_xfgken_iceime_rime_RimeNative_nativeDestroySession(JNIEnv*, jclass, jlong session) {
  if (!ensure_api()) return JNI_FALSE;
  return g_api->destroy_session(static_cast<RimeSessionId>(session)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_xfgken_iceime_rime_RimeNative_nativeCleanupStaleSessions(JNIEnv*, jclass) {
  if (!ensure_api()) return;
  if (RIME_API_AVAILABLE(g_api, cleanup_stale_sessions)) g_api->cleanup_stale_sessions();
}

JNIEXPORT jboolean JNICALL
Java_com_xfgken_iceime_rime_RimeNative_nativeProcessKey(JNIEnv*, jclass, jlong session,
                                                        jint keycode, jint mask) {
  if (!ensure_api()) return JNI_FALSE;
  return g_api->process_key(static_cast<RimeSessionId>(session), keycode, mask) ? JNI_TRUE
                                                                                : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_xfgken_iceime_rime_RimeNative_nativeGetInput(JNIEnv* env, jclass, jlong session) {
  if (!ensure_api()) return to_jstring(env, "");
  const char* input = g_api->get_input(static_cast<RimeSessionId>(session));
  return to_jstring(env, input ? input : "");
}

JNIEXPORT jint JNICALL
Java_com_xfgken_iceime_rime_RimeNative_nativeGetCaretPos(JNIEnv*, jclass, jlong session) {
  if (!ensure_api()) return 0;
  return static_cast<jint>(g_api->get_caret_pos(static_cast<RimeSessionId>(session)));
}

JNIEXPORT void JNICALL
Java_com_xfgken_iceime_rime_RimeNative_nativeSetCaretPos(JNIEnv*, jclass, jlong session,
                                                         jint pos) {
  if (!ensure_api()) return;
  if (RIME_API_AVAILABLE(g_api, set_caret_pos))
    g_api->set_caret_pos(static_cast<RimeSessionId>(session), static_cast<size_t>(pos));
}

JNIEXPORT jboolean JNICALL
Java_com_xfgken_iceime_rime_RimeNative_nativeSetInput(JNIEnv* env, jclass, jlong session,
                                                      jstring input) {
  if (!ensure_api()) return JNI_FALSE;
  if (!RIME_API_AVAILABLE(g_api, set_input)) return JNI_FALSE;
  return g_api->set_input(static_cast<RimeSessionId>(session), jstr(env, input).c_str())
             ? JNI_TRUE
             : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_xfgken_iceime_rime_RimeNative_nativeSelectCandidate(JNIEnv*, jclass, jlong session,
                                                            jint index) {
  if (!ensure_api()) return JNI_FALSE;
  return g_api->select_candidate(static_cast<RimeSessionId>(session),
                                 static_cast<size_t>(index))
             ? JNI_TRUE
             : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_xfgken_iceime_rime_RimeNative_nativeSelectCandidateOnPage(JNIEnv*, jclass, jlong session,
                                                                  jint index) {
  if (!ensure_api()) return JNI_FALSE;
  if (!RIME_API_AVAILABLE(g_api, select_candidate_on_current_page)) return JNI_FALSE;
  return g_api->select_candidate_on_current_page(static_cast<RimeSessionId>(session),
                                                 static_cast<size_t>(index))
             ? JNI_TRUE
             : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_xfgken_iceime_rime_RimeNative_nativeDeleteCandidateOnPage(JNIEnv*, jclass, jlong session,
                                                                  jint index) {
  if (!ensure_api()) return JNI_FALSE;
  if (!RIME_API_AVAILABLE(g_api, delete_candidate_on_current_page)) return JNI_FALSE;
  return g_api->delete_candidate_on_current_page(static_cast<RimeSessionId>(session),
                                                 static_cast<size_t>(index))
             ? JNI_TRUE
             : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_xfgken_iceime_rime_RimeNative_nativeHighlightOnPage(JNIEnv*, jclass, jlong session,
                                                            jint index) {
  if (!ensure_api()) return JNI_FALSE;
  if (!RIME_API_AVAILABLE(g_api, highlight_candidate_on_current_page)) return JNI_FALSE;
  return g_api->highlight_candidate_on_current_page(static_cast<RimeSessionId>(session),
                                                    static_cast<size_t>(index))
             ? JNI_TRUE
             : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_xfgken_iceime_rime_RimeNative_nativeChangePage(JNIEnv*, jclass, jlong session,
                                                       jboolean backward) {
  if (!ensure_api()) return JNI_FALSE;
  if (!RIME_API_AVAILABLE(g_api, change_page)) return JNI_FALSE;
  return g_api->change_page(static_cast<RimeSessionId>(session), backward) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_xfgken_iceime_rime_RimeNative_nativeCommitComposition(JNIEnv*, jclass, jlong session) {
  if (!ensure_api()) return JNI_FALSE;
  return g_api->commit_composition(static_cast<RimeSessionId>(session)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_xfgken_iceime_rime_RimeNative_nativeClearComposition(JNIEnv*, jclass, jlong session) {
  if (!ensure_api()) return;
  g_api->clear_composition(static_cast<RimeSessionId>(session));
}

/** 取提交串；没有提交内容时返回 null。 */
JNIEXPORT jstring JNICALL
Java_com_xfgken_iceime_rime_RimeNative_nativeGetCommit(JNIEnv* env, jclass, jlong session) {
  if (!ensure_api()) return nullptr;
  RimeCommit commit = {0};
  RIME_STRUCT_INIT(RimeCommit, commit);
  if (!g_api->get_commit(static_cast<RimeSessionId>(session), &commit)) return nullptr;
  jstring out = nullptr;
  if (commit.text != nullptr && commit.text[0] != '\0') out = to_jstring(env, commit.text);
  g_api->free_commit(&commit);
  return out;
}

/**
 * 上下文（预编辑串 + 当前页候选）。
 * 返回 JSON；失败时返回空串。
 */
JNIEXPORT jstring JNICALL
Java_com_xfgken_iceime_rime_RimeNative_nativeGetContext(JNIEnv* env, jclass, jlong session) {
  if (!ensure_api()) return to_jstring(env, "");
  RimeContext ctx = {0};
  RIME_STRUCT_INIT(RimeContext, ctx);
  if (!g_api->get_context(static_cast<RimeSessionId>(session), &ctx)) return to_jstring(env, "");

  std::string out;
  out.reserve(1024);
  out += "{";
  out += "\"preedit\":";
  append_json_string(&out, RIME_PROVIDED(&ctx, composition.preedit)
                               ? ctx.composition.preedit
                               : "");
  out += ",\"cursor\":";
  out += std::to_string(ctx.composition.cursor_pos);
  out += ",\"selStart\":";
  out += std::to_string(ctx.composition.sel_start);
  out += ",\"selEnd\":";
  out += std::to_string(ctx.composition.sel_end);
  out += ",\"pageSize\":";
  out += std::to_string(ctx.menu.page_size);
  out += ",\"pageNo\":";
  out += std::to_string(ctx.menu.page_no);
  out += ",\"isLastPage\":";
  out += ctx.menu.is_last_page ? "true" : "false";
  out += ",\"highlighted\":";
  out += std::to_string(ctx.menu.highlighted_candidate_index);
  out += ",\"selectKeys\":";
  append_json_string(&out, ctx.menu.select_keys ? ctx.menu.select_keys : "");
  out += ",\"commitPreview\":";
  append_json_string(&out, RIME_PROVIDED(&ctx, commit_text_preview)
                               ? ctx.commit_text_preview
                               : "");
  out += ",\"candidates\":[";
  for (int i = 0; i < ctx.menu.num_candidates; ++i) {
    if (i > 0) out += ",";
    out += "{";
    out += "\"text\":";
    append_json_string(&out, ctx.menu.candidates[i].text ? ctx.menu.candidates[i].text : "");
    out += ",\"comment\":";
    append_json_string(&out, ctx.menu.candidates[i].comment ? ctx.menu.candidates[i].comment : "");
    out += "}";
  }
  out += "]}";
  g_api->free_context(&ctx);
  return to_jstring(env, out);
}

JNIEXPORT jstring JNICALL
Java_com_xfgken_iceime_rime_RimeNative_nativeGetStatus(JNIEnv* env, jclass, jlong session) {
  if (!ensure_api()) return to_jstring(env, "");
  RimeStatus status = {0};
  RIME_STRUCT_INIT(RimeStatus, status);
  if (!g_api->get_status(static_cast<RimeSessionId>(session), &status))
    return to_jstring(env, "");
  std::string out;
  out += "{";
  out += "\"schemaId\":";
  append_json_string(&out, status.schema_id ? status.schema_id : "");
  out += ",\"schemaName\":";
  append_json_string(&out, status.schema_name ? status.schema_name : "");
  out += ",\"composing\":";
  out += status.is_composing ? "true" : "false";
  out += ",\"asciiMode\":";
  out += status.is_ascii_mode ? "true" : "false";
  out += ",\"fullShape\":";
  out += status.is_full_shape ? "true" : "false";
  out += ",\"simplified\":";
  out += status.is_simplified ? "true" : "false";
  out += ",\"traditional\":";
  out += status.is_traditional ? "true" : "false";
  out += ",\"asciiPunct\":";
  out += status.is_ascii_punct ? "true" : "false";
  out += "}";
  g_api->free_status(&status);
  return to_jstring(env, out);
}

JNIEXPORT void JNICALL
Java_com_xfgken_iceime_rime_RimeNative_nativeSetOption(JNIEnv* env, jclass, jlong session,
                                                       jstring name, jboolean value) {
  if (!ensure_api()) return;
  std::string n = jstr(env, name);
  g_api->set_option(static_cast<RimeSessionId>(session), n.c_str(), value ? True : False);
}

JNIEXPORT jboolean JNICALL
Java_com_xfgken_iceime_rime_RimeNative_nativeGetOption(JNIEnv* env, jclass, jlong session,
                                                       jstring name) {
  if (!ensure_api()) return JNI_FALSE;
  std::string n = jstr(env, name);
  return g_api->get_option(static_cast<RimeSessionId>(session), n.c_str()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_xfgken_iceime_rime_RimeNative_nativeSetProperty(JNIEnv* env, jclass, jlong session,
                                                         jstring name, jstring value) {
  if (!ensure_api()) return;
  std::string n = jstr(env, name);
  std::string v = jstr(env, value);
  if (RIME_API_AVAILABLE(g_api, set_property))
    g_api->set_property(static_cast<RimeSessionId>(session), n.c_str(), v.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_xfgken_iceime_rime_RimeNative_nativeGetProperty(JNIEnv* env, jclass, jlong session,
                                                         jstring name) {
  if (!ensure_api()) return to_jstring(env, "");
  std::string n = jstr(env, name);
  if (!RIME_API_AVAILABLE(g_api, get_property)) return to_jstring(env, "");
  char buf[512] = {0};
  if (!g_api->get_property(static_cast<RimeSessionId>(session), n.c_str(), buf, sizeof(buf)))
    return to_jstring(env, "");
  return to_jstring(env, buf);
}

JNIEXPORT jstring JNICALL
Java_com_xfgken_iceime_rime_RimeNative_nativeGetSchemaList(JNIEnv* env, jclass) {
  if (!ensure_api()) return to_jstring(env, "[]");
  RimeSchemaList list = {0, nullptr};
  if (!g_api->get_schema_list(&list)) return to_jstring(env, "[]");
  std::string out = "[";
  for (size_t i = 0; i < list.size; ++i) {
    if (i > 0) out += ",";
    out += "{";
    out += "\"id\":";
    append_json_string(&out, list.list[i].schema_id ? list.list[i].schema_id : "");
    out += ",\"name\":";
    append_json_string(&out, list.list[i].name ? list.list[i].name : "");
    out += "}";
  }
  out += "]";
  g_api->free_schema_list(&list);
  return to_jstring(env, out);
}

JNIEXPORT jboolean JNICALL
Java_com_xfgken_iceime_rime_RimeNative_nativeSelectSchema(JNIEnv* env, jclass, jlong session,
                                                          jstring schema_id) {
  if (!ensure_api()) return JNI_FALSE;
  std::string id = jstr(env, schema_id);
  return g_api->select_schema(static_cast<RimeSessionId>(session), id.c_str()) ? JNI_TRUE
                                                                              : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_xfgken_iceime_rime_RimeNative_nativeGetCurrentSchema(JNIEnv* env, jclass, jlong session) {
  if (!ensure_api()) return to_jstring(env, "");
  char buf[256] = {0};
  if (g_api->get_current_schema(static_cast<RimeSessionId>(session), buf, sizeof(buf)))
    return to_jstring(env, buf);
  return to_jstring(env, "");
}

/** 把用户数据立即同步回磁盘（用于强制保存用户词频）。 */
JNIEXPORT jboolean JNICALL
Java_com_xfgken_iceime_rime_RimeNative_nativeSyncUserData(JNIEnv*, jclass) {
  if (!ensure_api()) return JNI_FALSE;
  if (!RIME_API_AVAILABLE(g_api, sync_user_data)) return JNI_FALSE;
  return g_api->sync_user_data() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_xfgken_iceime_rime_RimeNative_nativeGetSharedDataDir(JNIEnv* env, jclass) {
  return to_jstring(env, g_shared_dir);
}

JNIEXPORT jstring JNICALL
Java_com_xfgken_iceime_rime_RimeNative_nativeGetUserDataDir(JNIEnv* env, jclass) {
  return to_jstring(env, g_user_dir);
}

}  // extern "C"
