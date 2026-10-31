#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "ysp_keygen_wasm.h"
#include "wasm-rt.h"

#define LOG_TAG "ysp_keygen"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

typedef struct ysp_value_handle {
  const char* value;
} ysp_value_handle;

static pthread_once_t g_once = PTHREAD_ONCE_INIT;
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static ysp_value_handle g_handles[32];
static u32 g_next_handle = 132;
static const char* g_guid = "";
static const char* g_timestamp = "";
static const char* g_token = "";
static const char* g_input = "";

static uint8_t* ysp_memory(u32 address, size_t size) {
  wasm_rt_memory_t* memory = WASM_RT_ADD_PREFIX(Z_memory);
  if (memory == NULL || (uint64_t) address + size > memory->size) {
    return NULL;
  }
  return memory->data + address;
}

static void ysp_write_u32(u32 address, u32 value) {
  uint8_t* target = ysp_memory(address, sizeof(value));
  if (target != NULL) {
    memcpy(target, &value, sizeof(value));
  }
}

static const char* ysp_lookup(const char* key, size_t length) {
#define YSP_KEY(text) (length == sizeof(text) - 1 && memcmp(key, text, sizeof(text) - 1) == 0)
  if (YSP_KEY("cctvh5openapi.state.version")) return "v1";
  if (YSP_KEY("cctvh5openapi.state.guid")) return g_guid;
  if (YSP_KEY("cctvh5openapi.state.yspappid")) return "519748109";
  if (YSP_KEY("window.location.host")) return "www.yangshipin.cn";
  if (YSP_KEY("window.location.protocol")) return "https:";
  if (YSP_KEY("cctvh5openapi.state.token")) return g_token;
  if (YSP_KEY("cctvh5openapi.state.input")) return g_input;
  if (YSP_KEY("cctvh5openapi.state.ts")) return g_timestamp;
#undef YSP_KEY
  return "";
}

static u32 ysp_get(u32 address, u32 length) {
  const char* key = (const char*) ysp_memory(address, length);
  const char* value = key == NULL ? "" : ysp_lookup(key, length);
  u32 handle = g_next_handle++;
  if (g_next_handle >= 132 + (u32) (sizeof(g_handles) / sizeof(g_handles[0]))) {
    g_next_handle = 132;
  }
  g_handles[handle - 132].value = value;
  return handle;
}

static void ysp_string_get(u32 output, u32 handle) {
  const char* value = "";
  if (handle >= 132 && handle < 132 + (u32) (sizeof(g_handles) / sizeof(g_handles[0]))) {
    const char* stored = g_handles[handle - 132].value;
    value = stored == NULL ? "" : stored;
  }
  u32 length = (u32) strlen(value);
  u32 address = WASM_RT_ADD_PREFIX(Z___wbindgen_mallocZ_iii)(length, 1);
  uint8_t* target = ysp_memory(address, length);
  if (target != NULL && length > 0) {
    memcpy(target, value, length);
  }
  ysp_write_u32(output, address);
  ysp_write_u32(output + 4, length);
}

static void ysp_drop_ref(u32 handle) {
  if (handle >= 132 && handle < 132 + (u32) (sizeof(g_handles) / sizeof(g_handles[0]))) {
    g_handles[handle - 132].value = NULL;
  }
}

u32 (*Z_wbgZ___wbg_get_9c1840f7ecd81363Z_iii)(u32, u32) = ysp_get;
void (*Z_wbgZ___wbindgen_string_getZ_vii)(u32, u32) = ysp_string_get;
void (*Z_wbgZ___wbindgen_object_drop_refZ_vi)(u32) = ysp_drop_ref;

static void ysp_initialize(void) {
  WASM_RT_ADD_PREFIX(init)();
}

static jstring ysp_call(JNIEnv* env, void (*function)(u32)) {
  u32 stack = WASM_RT_ADD_PREFIX(Z___wbindgen_add_to_stack_pointerZ_ii)((u32) -16);
  function(stack);
  uint8_t* result = ysp_memory(stack, 8);
  if (result == NULL) {
    WASM_RT_ADD_PREFIX(Z___wbindgen_add_to_stack_pointerZ_ii)(16);
    return NULL;
  }
  u32 address;
  u32 length;
  memcpy(&address, result, 4);
  memcpy(&length, result + 4, 4);
  uint8_t* text = ysp_memory(address, length);
  char output[65];
  if (text == NULL || length == 0 || length >= sizeof(output)) {
    LOGE("Invalid keygen output length=%u", length);
    WASM_RT_ADD_PREFIX(Z___wbindgen_add_to_stack_pointerZ_ii)(16);
    return NULL;
  }
  memcpy(output, text, length);
  output[length] = '\0';
  WASM_RT_ADD_PREFIX(Z___wbindgen_add_to_stack_pointerZ_ii)(16);
  WASM_RT_ADD_PREFIX(Z___wbindgen_freeZ_viii)(address, length, 1);
  return (*env)->NewStringUTF(env, output);
}

JNIEXPORT jstring JNICALL
Java_com_mytvb_feature_cctv_cmg_CmgNativeBridge_tokenRnd(JNIEnv* env, jclass clazz,
    jstring guid, jstring timestamp) {
  (void) clazz;
  pthread_once(&g_once, ysp_initialize);
  const char* guid_text = guid == NULL ? "" : (*env)->GetStringUTFChars(env, guid, NULL);
  const char* timestamp_text = timestamp == NULL ? "" : (*env)->GetStringUTFChars(env, timestamp, NULL);
  pthread_mutex_lock(&g_lock);
  g_guid = guid_text;
  g_timestamp = timestamp_text;
  jstring result = ysp_call(env, WASM_RT_ADD_PREFIX(Z_get_token_rndZ_vi));
  g_guid = "";
  g_timestamp = "";
  pthread_mutex_unlock(&g_lock);
  if (guid != NULL) (*env)->ReleaseStringUTFChars(env, guid, guid_text);
  if (timestamp != NULL) (*env)->ReleaseStringUTFChars(env, timestamp, timestamp_text);
  return result;
}

JNIEXPORT jstring JNICALL
Java_com_mytvb_feature_cctv_cmg_CmgNativeBridge_signature(JNIEnv* env, jclass clazz,
    jstring guid, jstring token, jstring input) {
  (void) clazz;
  pthread_once(&g_once, ysp_initialize);
  const char* guid_text = guid == NULL ? "" : (*env)->GetStringUTFChars(env, guid, NULL);
  const char* token_text = token == NULL ? "" : (*env)->GetStringUTFChars(env, token, NULL);
  const char* input_text = input == NULL ? "" : (*env)->GetStringUTFChars(env, input, NULL);
  pthread_mutex_lock(&g_lock);
  g_guid = guid_text;
  g_token = token_text;
  g_input = input_text;
  jstring result = ysp_call(env, WASM_RT_ADD_PREFIX(Z_get_signatureZ_vi));
  g_guid = "";
  g_token = "";
  g_input = "";
  pthread_mutex_unlock(&g_lock);
  if (guid != NULL) (*env)->ReleaseStringUTFChars(env, guid, guid_text);
  if (token != NULL) (*env)->ReleaseStringUTFChars(env, token, token_text);
  if (input != NULL) (*env)->ReleaseStringUTFChars(env, input, input_text);
  return result;
}
