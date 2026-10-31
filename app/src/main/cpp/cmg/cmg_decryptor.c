#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/time.h>

#include "cmg_wasm.h"
#include "cmg_memory_init.h"
#include "wasm-rt.h"
#include "wasm-rt-impl.h"

#define LOG_TAG "cmg_decrypt"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define CMG_INITIAL_PAGES 256u
#define CMG_MAX_PAGES 1536u
#define CMG_TABLE_SIZE 560u
#define CMG_TABLE_BASE 0u
#define CMG_TEMP_DOUBLE_PTR 17920u
#define CMG_DYNAMIC_TOP_PTR 17904u
#define CMG_EMT_STACK_TOP 6309392u
#define CMG_EMBIND_STORAGE 6309392u
#define CMG_DYNAMIC_TOP_AFTER_SHELL_ALLOCATIONS 6687984u
#define CMG_MEMORY_EXTEND 2048u
#define CMG_PLAYER_MEMORY_EXTEND 1968u
#define CMG_NAL_MEMORY_EXTEND CMG_MEMORY_EXTEND
#define CMG_LOCATION_ORIGIN "https://www.yangshipin.cn"
#define CMG_LOCATION_HREF "https://www.yangshipin.cn/tv/home"
#define CMG_PAGE_HOST CMG_LOCATION_ORIGIN

typedef enum cmg_emval_type {
  CMG_EMVAL_EMPTY,
  CMG_EMVAL_LOCATION,
  CMG_EMVAL_STRING,
  CMG_EMVAL_DESTRUCTORS
} cmg_emval_type;

typedef struct cmg_emval_handle {
  cmg_emval_type type;
  int refcount;
  char* text;
  u32 destructor_address;
} cmg_emval_handle;

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static int g_initialized;
static wasm_rt_memory_t g_memory;
static wasm_rt_table_t g_table;
static u32 g_table_base = CMG_TABLE_BASE;
static u32 g_temp_double_ptr = CMG_TEMP_DOUBLE_PTR;
static u32 g_dynamic_top_ptr = CMG_DYNAMIC_TOP_PTR;
static u32 g_emt_stack_top = CMG_EMT_STACK_TOP;
static u32 g_embind_storage = CMG_EMBIND_STORAGE;
static u32 g_temp_ret0;
static char g_player_tag[32];
static char g_forced_player_tag[32];
static char g_forced_location_href[256];
static int g_player_active;
static int g_player_init_trap;
static u32 g_player_init_result;
static u32 g_update_tag;
static u32 g_logged_update_tag;
static uint64_t g_forced_clock_ms;
static cmg_emval_handle g_emval_handles[64];
static u32 g_emval_free_list[64];
static size_t g_emval_free_count;
static u32 g_emval_handle_count;
static int g_location_property_reads;
static const char* g_trace_phase = "idle";
static int g_env_read_log_count;
static int g_import_log_count;
static int g_update_call_log_count;
static int g_heap_log_count;
static u32 g_decode_data_address;
static u32 g_decode_data_capacity;
static u32 g_decode_tag_address;
static u32 g_decode_tag_capacity;

u32 (*Z_envZ___table_baseZ_i) = &g_table_base;
u32 (*Z_envZ_aZ_i) = &g_temp_double_ptr;
u32 (*Z_envZ_bZ_i) = &g_dynamic_top_ptr;
u32 (*Z_envZ_cZ_i) = &g_emt_stack_top;
u32 (*Z_envZ_dZ_i) = &g_embind_storage;
wasm_rt_memory_t (*Z_envZ_memory) = &g_memory;
wasm_rt_table_t (*Z_envZ_table) = &g_table;

static uint8_t* cmg_pointer(u32 address, size_t size) {
  if ((uint64_t) address + size > g_memory.size) {
    return NULL;
  }
  return g_memory.data + address;
}

static u32 cmg_dynamic_top(void) {
  uint32_t* top = (uint32_t*) cmg_pointer(CMG_DYNAMIC_TOP_PTR, sizeof(uint32_t));
  return top == NULL ? 0 : *top;
}

static void cmg_set_dynamic_top(u32 value) {
  uint32_t* top = (uint32_t*) cmg_pointer(CMG_DYNAMIC_TOP_PTR, sizeof(uint32_t));
  if (top != NULL) {
    *top = value;
  }
}

static int cmg_apply_memory_init(void) {
  size_t relocation_index;
  if (CMG_EMBIND_STORAGE + sizeof(CMG_MEMORY_INIT) > g_memory.size) {
    LOGE("CMG memory init too large: storage=%u payload=%u memory=%u",
        CMG_EMBIND_STORAGE, (u32) sizeof(CMG_MEMORY_INIT), (u32) g_memory.size);
    return 0;
  }
  memcpy(g_memory.data + CMG_EMBIND_STORAGE, CMG_MEMORY_INIT, sizeof(CMG_MEMORY_INIT));
  for (relocation_index = 0;
      relocation_index < sizeof(CMG_MEMORY_RELOCATIONS) / sizeof(CMG_MEMORY_RELOCATIONS[0]);
      relocation_index++) {
    uint32_t* target = (uint32_t*) cmg_pointer(
        CMG_EMBIND_STORAGE + CMG_MEMORY_RELOCATIONS[relocation_index], sizeof(uint32_t));
    if (target == NULL) {
      return 0;
    }
    *target += CMG_EMBIND_STORAGE;
  }
  ((u32*) (g_memory.data + CMG_DYNAMIC_TOP_PTR))[0] = CMG_DYNAMIC_TOP_AFTER_SHELL_ALLOCATIONS;
  return 1;
}

static u32 cmg_strlen(u32 address) {
  u32 length = 0;
  while (address + length < g_memory.size && g_memory.data[address + length] != 0) {
    length++;
  }
  return length;
}

static void cmg_reset_emval_state(void) {
  u32 handle;
  for (handle = 5; handle < g_emval_handle_count; handle++) {
    free(g_emval_handles[handle].text);
  }
  memset(g_emval_handles, 0, sizeof(g_emval_handles));
  memset(g_emval_free_list, 0, sizeof(g_emval_free_list));
  g_emval_handles[1].type = CMG_EMVAL_LOCATION;
  g_emval_free_count = 0;
  g_emval_handle_count = 5;
  g_location_property_reads = 0;
}

static u32 cmg_allocate_emval_handle(void) {
  u32 handle;
  if (g_emval_free_count > 0) {
    handle = g_emval_free_list[--g_emval_free_count];
  } else {
    if (g_emval_handle_count >= sizeof(g_emval_handles) / sizeof(g_emval_handles[0])) {
      return 1;
    }
    handle = g_emval_handle_count++;
  }
  memset(&g_emval_handles[handle], 0, sizeof(g_emval_handles[handle]));
  g_emval_handles[handle].refcount = 1;
  return handle;
}

static u32 cmg_register_emval_string(const char* text, size_t length) {
  u32 handle = cmg_allocate_emval_handle();
  if (handle <= 4) {
    return handle;
  }
  g_emval_handles[handle].text = (char*) malloc(length + 1);
  if (g_emval_handles[handle].text == NULL) {
    return 1;
  }
  memcpy(g_emval_handles[handle].text, text, length);
  g_emval_handles[handle].text[length] = '\0';
  g_emval_handles[handle].type = CMG_EMVAL_STRING;
  return handle;
}

static u32 cmg_register_emval_destructors(void) {
  u32 handle = cmg_allocate_emval_handle();
  if (handle > 4) {
    g_emval_handles[handle].type = CMG_EMVAL_DESTRUCTORS;
  }
  return handle;
}

static u32 cmg_register_emval_location(void) {
  u32 handle = cmg_allocate_emval_handle();
  if (handle > 4) {
    g_emval_handles[handle].type = CMG_EMVAL_LOCATION;
  }
  return handle;
}

static void cmg_release_emval_handle(u32 handle) {
  if (handle <= 4 || handle >= g_emval_handle_count || g_emval_handles[handle].refcount == 0) {
    return;
  }
  if (--g_emval_handles[handle].refcount == 0) {
    free(g_emval_handles[handle].text);
    memset(&g_emval_handles[handle], 0, sizeof(g_emval_handles[handle]));
    if (g_emval_free_count < sizeof(g_emval_free_list) / sizeof(g_emval_free_list[0])) {
      g_emval_free_list[g_emval_free_count++] = handle;
    }
  }
}

static u32 cmg_write_string(const char* text) {
  size_t length = strlen(text) + 1;
  u32 address = (*Z_CaZ_ii)((u32) length);
  uint8_t* target = cmg_pointer(address, length);
  if (target != NULL) {
    memcpy(target, text, length);
  }
  return address;
}

static u32 cmg_write_padded_string(const char* text, u32 extra) {
  size_t length = strlen(text);
  size_t total = length + 1u + extra;
  u32 address = (*Z_CaZ_ii)((u32) total);
  uint8_t* target = cmg_pointer(address, total);
  if (target != NULL) {
    memset(target, 0, total);
    memcpy(target, text, length);
  }
  return address;
}

static u32 cmg_write_tag_buffer(const char* text, u32 extra, int clear_buffer) {
  size_t length = strlen(text);
  size_t total = length + (size_t) extra;
  u32 address = (*Z_CaZ_ii)((u32) total);
  uint8_t* target = cmg_pointer(address, total);
  if (target != NULL) {
    if (clear_buffer) {
      memset(target, 0, total);
    }
    memcpy(target, text, length);
  }
  return address;
}

static u32 cmg_update_player_with_fresh_tag(void) {
  const char* previous_phase = g_trace_phase;
  u32 tag_address;
  u32 update_tag = 0;
  int should_log = g_update_call_log_count < 24;
  /* hls.cmg.js clears the complete tag + MemoryExtend allocation before
   * copying mediaTagId. UpdatePlayer reads beyond the string terminator. */
  tag_address = cmg_write_tag_buffer(g_player_tag, CMG_MEMORY_EXTEND, 1);
  g_trace_phase = "update";
  if (should_log) {
    LOGI("CMG UpdatePlayer begin tagAddr=%u size=%u top=%u g6=%u g7=%u g9=%u",
        tag_address, (u32) (strlen(g_player_tag) + CMG_MEMORY_EXTEND),
        cmg_dynamic_top(), cmg_debug_get_g6(), cmg_debug_get_g7(), cmg_debug_get_g9());
  }
  if (cmg_pointer(tag_address, strlen(g_player_tag) + CMG_MEMORY_EXTEND) != NULL) {
    update_tag = (*Z_daZ_ii)(tag_address);
  }
  if (should_log || update_tag != g_logged_update_tag) {
    LOGI("CMG UpdatePlayer end result=%08x top=%u g6=%u g7=%u g9=%u",
        update_tag, cmg_dynamic_top(), cmg_debug_get_g6(), cmg_debug_get_g7(),
        cmg_debug_get_g9());
  }
  if (tag_address != 0) {
    (*Z_BaZ_vi)(tag_address);
  }
  if (should_log) {
    LOGI("CMG UpdatePlayer free tagAddr=%u top=%u", tag_address, cmg_dynamic_top());
  }
  g_update_call_log_count++;
  g_trace_phase = previous_phase;
  return update_tag;
}

static u32 cmg_return_js_string(const char* text) {
  size_t length = strlen(text) + 1;
  size_t total = length;
  u32 address = (*Z_EaZ_ii)((u32) total);
  uint8_t* target = cmg_pointer(address, total);
  if (target != NULL) {
    memset(target, 0, total);
    memcpy(target, text, length);
  }
  return address;
}

typedef u32 (*cmg_decode_function)(u32, u32, u32, u32);

static int cmg_should_run_decode_step(u32 update_tag, int index) {
  int shift = (7 - index) * 4;
  u32 digit = (update_tag >> shift) & 0x0fu;
  return digit <= 6u;
}

static const char* cmg_emval_text(u32 handle) {
  if (handle == 1) {
    return CMG_LOCATION_HREF;
  }
  if (handle < g_emval_handle_count && g_emval_handles[handle].text != NULL) {
    return g_emval_handles[handle].text;
  }
  return "";
}

static const char* cmg_location_href(void) {
  return g_forced_location_href[0] != '\0' ? g_forced_location_href : CMG_LOCATION_HREF;
}

static void cmg_log_env_read(const char* api, const char* name, const char* value) {
  if (g_env_read_log_count < 48) {
    LOGI("CMG env %s phase=%s %s -> %s",
        api, g_trace_phase, name == NULL ? "(null)" : name, value == NULL ? "(null)" : value);
    g_env_read_log_count++;
  }
}

static int cmg_should_log_import(void) {
  return g_import_log_count < 96
      && strcmp(g_trace_phase, "idle") != 0;
}

static void cmg_log_import(const char* api, const char* format, ...) {
  va_list args;
  char message[512];
  if (!cmg_should_log_import()) {
    return;
  }
  va_start(args, format);
  vsnprintf(message, sizeof(message), format, args);
  va_end(args);
  LOGI("CMG import %s phase=%s top=%u g6=%u g7=%u g9=%u %s",
      api, g_trace_phase, cmg_dynamic_top(), cmg_debug_get_g6(),
      cmg_debug_get_g7(), cmg_debug_get_g9(), message);
  g_import_log_count++;
}

static void cmg_hex_bytes(u32 address, size_t size, char* output, size_t output_size) {
  size_t i;
  uint8_t* bytes;
  size_t limit;
  if (output_size == 0) {
    return;
  }
  output[0] = '\0';
  bytes = cmg_pointer(address, size);
  if (bytes == NULL) {
    return;
  }
  limit = size;
  if (limit > (output_size - 1) / 2) {
    limit = (output_size - 1) / 2;
  }
  for (i = 0; i < limit; i++) {
    snprintf(output + i * 2, output_size - i * 2, "%02x", bytes[i]);
  }
}

static u32 cmg_u32_at(u32 address) {
  uint32_t* value = (uint32_t*) cmg_pointer(address, sizeof(uint32_t));
  return value == NULL ? 0u : *value;
}

static void cmg_log_heap_snapshot(const char* label) {
  char stack_a[129];
  char stack_b[129];
  char heap_a[193];
  if (g_heap_log_count >= 48 || strcmp(g_trace_phase, "init") != 0) {
    return;
  }
  cmg_hex_bytes(18336u, 64, stack_a, sizeof(stack_a));
  cmg_hex_bytes(18656u, 64, stack_b, sizeof(stack_b));
  cmg_hex_bytes(6691728u, 96, heap_a, sizeof(heap_a));
  LOGI("CMG heap %s top=%u g6=%u g7=%u g9=%u s18336=%s s18656=%s h6691728=%s",
      label, cmg_dynamic_top(), cmg_debug_get_g6(), cmg_debug_get_g7(),
      cmg_debug_get_g9(), stack_a, stack_b, heap_a);
  g_heap_log_count++;
}

static uint64_t cmg_now_ms64(void) {
  struct timeval now;
  if (g_forced_clock_ms != 0u) {
    return g_forced_clock_ms;
  }
  gettimeofday(&now, NULL);
  return (uint64_t) now.tv_sec * 1000u + (uint64_t) now.tv_usec / 1000u;
}

static u32 env_now_ms(void) {
  return (u32) cmg_now_ms64();
}

static void cmg_make_player_tag(void) {
  uint64_t millis;
  if (g_forced_player_tag[0] != '\0') {
    snprintf(g_player_tag, sizeof(g_player_tag), "%s", g_forced_player_tag);
    return;
  }
  millis = cmg_now_ms64();
  snprintf(g_player_tag, sizeof(g_player_tag), "%llu", (unsigned long long) millis);
}

static u32 import_e(void) { return g_temp_ret0; }
static void import_f(u32 value) { g_temp_ret0 = value; }
static u32 import_g(u32 timeval_address, u32 timezone_address) {
  uint64_t millis;
  uint8_t* target;
  (void) timezone_address;
  millis = cmg_now_ms64();
  target = cmg_pointer(timeval_address, 8);
  if (target == NULL) {
    return (u32) -1;
  }
  ((u32*) target)[0] = (u32) (millis / 1000u);
  ((u32*) target)[1] = (u32) ((millis % 1000u) * 1000u);
  return 0;
}
static void import_h(u32 a, u32 b, u32 c, u32 d, u32 e) {
  (void) a; (void) b; (void) c; (void) d; (void) e;
}
static f64 import_i(void) {
  return (f64) cmg_now_ms64();
}
static void import_j(void) { LOGE("CMG pure virtual call"); wasm_rt_trap(WASM_RT_TRAP_UNREACHABLE); }
static void import_k(u32 a, u32 b) { (void) a; (void) b; }
static void import_l(u32 a) { (void) a; }
static u32 import_m(u32 a, u32 b, u32 c, u32 d, u32 e) {
  (void) a; (void) b; (void) c; (void) d; (void) e; return 0;
}
static u32 import_n(u32 a, u32 b, u32 c, u32 d) {
  (void) a; (void) b; (void) c; (void) d; return 0;
}
static u32 import_o(u32 requested_size) {
  LOGE("CMG cannot grow memory to %u", requested_size);
  wasm_rt_trap(WASM_RT_TRAP_EXHAUSTION);
  return 0;
}
static u32 cmg_memmove(u32 destination, u32 source, u32 size) {
  uint8_t* destination_pointer = cmg_pointer(destination, size);
  uint8_t* source_pointer = cmg_pointer(source, size);
  if (destination_pointer != NULL && source_pointer != NULL) {
    memmove(destination_pointer, source_pointer, size);
  }
  return destination;
}
static u32 import_p(u32 function_index, u32 a, u32 b) {
  (void) function_index; (void) a; (void) b; return 0;
}
static void import_q(void) { LOGE("CMG llvm trap"); wasm_rt_trap(WASM_RT_TRAP_UNREACHABLE); }
static void import_r(u32 a) { (void) a; }
static u32 import_s(u32 requested_size) {
  uint64_t current_pages = g_memory.pages;
  uint64_t requested_pages = ((uint64_t) requested_size + 65535u) / 65536u;
  if (requested_pages <= current_pages) {
    return 1;
  }
  return wasm_rt_grow_memory(&g_memory, requested_pages - current_pages) == (u32) -1 ? 0 : 1;
}
static u32 import_t(u32 destination, u32 source, u32 size) {
  return cmg_memmove(destination, source, size);
}
static void import_u(u32 flags, u32 message) {
  const char* text = (const char*) cmg_pointer(message, 1);
  (void) flags;
  if (text != NULL) {
    LOGI("%s", text);
  }
}
static u32 import_v(void) { return 1; }
static u32 import_w(void) { return g_memory.size; }
static u32 import_x(u32 flags, u32 buffer, u32 size) {
  (void) flags; (void) buffer; (void) size; return 0;
}
static u32 import_y(u32 a, u32 b, f64 c, u32 d, u32 e, u32 f, u32 g) {
  (void) a; (void) b; (void) c; (void) d; (void) e; (void) f; (void) g; return 0;
}
static u32 import_z(u32 operation, u32 argument) {
  const char* name = (const char*) cmg_pointer(argument, 1);
  (void) operation;
  if (name == NULL) {
    return 0;
  }
  if (strcmp(name, "self.location.href") == 0) {
    cmg_log_env_read("z", name, cmg_location_href());
    return cmg_return_js_string(cmg_location_href());
  }
  if (strcmp(name, "self.location.origin") == 0) {
    cmg_log_env_read("z", name, CMG_LOCATION_ORIGIN);
    return cmg_return_js_string(CMG_LOCATION_ORIGIN);
  }
  if (strcmp(name, "self.location.host") == 0) {
    cmg_log_env_read("z", name, "www.yangshipin.cn");
    return cmg_return_js_string("www.yangshipin.cn");
  }
  if (strcmp(name, "self.location.protocol") == 0) {
    cmg_log_env_read("z", name, "https:");
    return cmg_return_js_string("https:");
  }
  if (strcmp(name, "location.href") == 0) {
    cmg_log_env_read("z", name, cmg_location_href());
    return cmg_return_js_string(cmg_location_href());
  }
  if (strcmp(name, "location.origin") == 0) {
    cmg_log_env_read("z", name, CMG_LOCATION_ORIGIN);
    return cmg_return_js_string(CMG_LOCATION_ORIGIN);
  }
  if (strcmp(name, "location.host") == 0) {
    cmg_log_env_read("z", name, "www.yangshipin.cn");
    return cmg_return_js_string("www.yangshipin.cn");
  }
  if (strcmp(name, "location.protocol") == 0) {
    cmg_log_env_read("z", name, "https:");
    return cmg_return_js_string("https:");
  }
  cmg_log_env_read("z", name, "");
  return cmg_return_js_string("");
}
static void import_A(void) { LOGE("CMG abort"); wasm_rt_trap(WASM_RT_TRAP_UNREACHABLE); }
static u32 import_B(u32 type, u32 value_address) {
  uint32_t* length_pointer;
  uint8_t* text;
  u32 result;
  uint8_t* raw;
  u32 string_address = value_address;
  (void) type;
  raw = cmg_pointer(value_address, 32);
  if (raw != NULL) {
    uint32_t candidate = ((uint32_t*) raw)[0];
    uint8_t* candidate_bytes = cmg_pointer(candidate, 32);
    if (candidate_bytes != NULL) {
      string_address = candidate;
    }
  }
  length_pointer = (uint32_t*) cmg_pointer(string_address, sizeof(uint32_t));
  if (length_pointer == NULL) {
    cmg_log_import("B", "type=%u value=%u string=%u missing-length",
        type, value_address, string_address);
    return 1;
  }
  if (*length_pointer > 4096u) {
    cmg_log_import("B", "type=%u value=%u string=%u bad-length=%u",
        type, value_address, string_address, *length_pointer);
    return 1;
  }
  text = cmg_pointer(string_address + 4, *length_pointer);
  if (text == NULL) {
    cmg_log_import("B", "type=%u value=%u string=%u length=%u missing-text",
        type, value_address, string_address, *length_pointer);
    return 1;
  }
  result = cmg_register_emval_string((const char*) text, *length_pointer);
  cmg_log_import("B", "type=%u value=%u string=%u length=%u -> handle=%u text=%s",
      type, value_address, string_address, *length_pointer, result,
      result <= 4 ? "(failed)" : g_emval_handles[result].text);
  cmg_log_heap_snapshot("after-B");
  return result;
}
static void import_C(u32 destructors) {
  u32 destructor_address = destructors < g_emval_handle_count
      ? g_emval_handles[destructors].destructor_address : 0;
  cmg_log_import("C", "destructors=%u type=%u destructorAddr=%u",
      destructors,
      destructors < g_emval_handle_count ? (u32) g_emval_handles[destructors].type : 0,
      destructor_address);
  cmg_log_heap_snapshot("before-C");
  if (destructors > 4 && destructors < g_emval_handle_count
      && g_emval_handles[destructors].type == CMG_EMVAL_DESTRUCTORS) {
    if (destructor_address != 0) {
      (*Z_AaZ_vi)(destructor_address);
    }
    cmg_release_emval_handle(destructors);
  }
  cmg_log_heap_snapshot("after-C");
}
static u32 import_D(u32 object, u32 property_handle) {
  const char* property = property_handle < g_emval_handle_count
      ? g_emval_handles[property_handle].text : NULL;
  int is_location = object == 1 || (object < g_emval_handle_count
      && g_emval_handles[object].type == CMG_EMVAL_LOCATION);
  if (is_location && property != NULL) {
    if (strcmp(property, "href") == 0) {
      cmg_log_env_read("D", property, cmg_location_href());
      return cmg_register_emval_string(cmg_location_href(), strlen(cmg_location_href()));
    }
    if (strcmp(property, "origin") == 0) {
      cmg_log_env_read("D", property, CMG_LOCATION_ORIGIN);
      return cmg_register_emval_string(CMG_LOCATION_ORIGIN, strlen(CMG_LOCATION_ORIGIN));
    }
    if (strcmp(property, "host") == 0) {
      cmg_log_env_read("D", property, "www.yangshipin.cn");
      return cmg_register_emval_string("www.yangshipin.cn", strlen("www.yangshipin.cn"));
    }
    if (strcmp(property, "protocol") == 0) {
      cmg_log_env_read("D", property, "https:");
      return cmg_register_emval_string("https:", strlen("https:"));
    }
  }
  if (is_location) {
    g_location_property_reads++;
    if (g_location_property_reads == 1) {
      cmg_log_env_read("D", "(fallback-1)", CMG_LOCATION_ORIGIN);
      return cmg_register_emval_string(CMG_LOCATION_ORIGIN, strlen(CMG_LOCATION_ORIGIN));
    }
    if (g_location_property_reads == 2) {
      cmg_log_env_read("D", "(fallback-2)", cmg_location_href());
      return cmg_register_emval_string(cmg_location_href(), strlen(cmg_location_href()));
    }
    cmg_log_env_read("D", "(fallback-n)", "https:");
    return cmg_register_emval_string("https:", strlen("https:"));
  }
  cmg_log_env_read("D", property, "(handle-1)");
  return 1;
}
static u32 import_E(u32 name_address) {
  const char* name = (const char*) cmg_pointer(name_address, 1);
  if (name != NULL && (strcmp(name, "location") == 0 || strcmp(name, "self") == 0)) {
    u32 handle = cmg_register_emval_location();
    cmg_log_import("E", "nameAddr=%u name=%s -> handle=%u",
        name_address, name, handle);
    return handle;
  }
  cmg_log_import("E", "nameAddr=%u name=%s -> handle=1",
      name_address, name == NULL ? "(null)" : name);
  return 1;
}
static void import_F(u32 handle) {
  cmg_emval_type type = handle < g_emval_handle_count ? g_emval_handles[handle].type : CMG_EMVAL_EMPTY;
  cmg_log_import("F", "handle=%u type=%u ref=%d text=%s",
      handle,
      (u32) type,
      handle < g_emval_handle_count ? g_emval_handles[handle].refcount : 0,
      (handle < g_emval_handle_count && g_emval_handles[handle].text != NULL)
          ? g_emval_handles[handle].text : "");
  cmg_release_emval_handle(handle);
  cmg_log_heap_snapshot("after-F");
}
static f64 import_G(u32 handle, u32 return_type, u32 destructors_address) {
  const char* text = cmg_emval_text(handle);
  size_t length = strlen(text);
  u32 destructors = cmg_register_emval_destructors();
  u32 result;
  uint32_t* pointer;
  uint8_t* output;
  u32 destructors_before;
  char destructors_hex_before[33];
  char destructors_hex_after[33];
  char result_hex_before[129];
  char result_hex[129];
  (void) return_type;
  destructors_before = cmg_u32_at(destructors_address);
  cmg_hex_bytes(destructors_address, 16, destructors_hex_before, sizeof(destructors_hex_before));
  cmg_hex_bytes(destructors_before, 64, result_hex_before, sizeof(result_hex_before));
  pointer = (uint32_t*) cmg_pointer(destructors_address, sizeof(uint32_t));
  if (pointer == NULL) {
    cmg_log_import("G", "handle=%u returnType=%u destructorsAddr=%u missing-destructors text=%s",
        handle, return_type, destructors_address, text);
    return 0.0;
  }
  result = (*Z_EaZ_ii)((u32) length + 5);
  *pointer = destructors;
  pointer = (uint32_t*) cmg_pointer(result, sizeof(uint32_t));
  if (pointer == NULL) {
    cmg_log_import("G", "handle=%u returnType=%u destructors=%u result=%u missing-length text=%s",
        handle, return_type, destructors, result, text);
    return 0.0;
  }
  *pointer = (uint32_t) length;
  output = cmg_pointer(result + 4, length + 1);
  if (output == NULL) {
    cmg_log_import("G", "handle=%u returnType=%u destructors=%u result=%u length=%u missing-output text=%s",
        handle, return_type, destructors, result, (u32) length, text);
    return 0.0;
  }
  memcpy(output, text, length + 1);
  if (destructors > 4) {
    g_emval_handles[destructors].destructor_address = result;
  }
  cmg_hex_bytes(destructors_address, 16, destructors_hex_after, sizeof(destructors_hex_after));
  cmg_hex_bytes(result, 64, result_hex, sizeof(result_hex));
  cmg_log_import("G",
      "handle=%u returnType=%u destructorsAddr=%u d0=%u dh0=%s rbh=%s destructors=%u d1=%u dh1=%s result=%u r0=%u rh=%s length=%u text=%s",
      handle, return_type, destructors_address, destructors_before, destructors_hex_before, result_hex_before,
      destructors, cmg_u32_at(destructors_address), destructors_hex_after, result,
      cmg_u32_at(result), result_hex, (u32) length, text);
  cmg_log_heap_snapshot("after-G");
  return (f64) result;
}
static void import_H(u32 a) { (void) a; }
static u32 import_I(u32 a, u32 b) { (void) a; (void) b; return 0; }
static void import_J(u32 a, u32 b) { (void) a; (void) b; }
static void import_K(u32 a, u32 b, u32 c) { (void) a; (void) b; (void) c; }
static void import_L(u32 a, u32 b) { (void) a; (void) b; }
static void import_M(u32 a, u32 b, u32 c) { (void) a; (void) b; (void) c; }
static void import_N(u32 a, u32 b, u32 c, u32 d, u32 e) {
  (void) a; (void) b; (void) c; (void) d; (void) e;
}
static void import_O(u32 a, u32 b, u32 c) { (void) a; (void) b; (void) c; }
static void import_P(u32 a, u32 b) { (void) a; (void) b; }
static void import_Q(u32 a, u32 b, u32 c, u32 d, u32 e) {
  (void) a; (void) b; (void) c; (void) d; (void) e;
}
static u32 import_R(u32 a, u32 b) { (void) a; (void) b; return (u32) -1; }
static u32 import_S(u32 a, u32 b) { (void) a; (void) b; return (u32) -1; }
static u32 import_T(u32 a, u32 b) { (void) a; (void) b; return (u32) -1; }
static void import_U(u32 a) { (void) a; }
static u32 import_V(void) { return 0; }
static u32 import_W(u32 a) { (void) a; return 0; }
static void import_X(u32 a, u32 b, u32 c, u32 d, u32 e, u32 f, u32 g) {
  (void) a; (void) b; (void) c; (void) d; (void) e; (void) f; (void) g;
}
static void import_Y(u32 a, u32 b, u32 c, u32 d, u32 e, u32 f) {
  (void) a; (void) b; (void) c; (void) d; (void) e; (void) f;
}
static void import_Z(u32 a, u32 b, u32 c, u32 d, u32 e) {
  (void) a; (void) b; (void) c; (void) d; (void) e;
}
static void import_(u32 a, u32 b, u32 c, u32 d) { (void) a; (void) b; (void) c; (void) d; }
static void import_dollar(u32 a, u32 b, u32 c) { (void) a; (void) b; (void) c; }
static void import_aa(u32 message) {
  uint8_t* text = cmg_pointer(message, 1);
  LOGE("CMG abort: %s", text != NULL ? (const char*) text : "");
  wasm_rt_trap(WASM_RT_TRAP_UNREACHABLE);
}

u32 (*Z_envZ_eZ_iv)(void) = import_e;
void (*Z_envZ_fZ_vi)(u32) = import_f;
u32 (*Z_envZ_gZ_iii)(u32, u32) = import_g;
void (*Z_envZ_hZ_viiiii)(u32, u32, u32, u32, u32) = import_h;
f64 (*Z_envZ_iZ_dv)(void) = import_i;
void (*Z_envZ_jZ_vv)(void) = import_j;
void (*Z_envZ_kZ_vii)(u32, u32) = import_k;
void (*Z_envZ_lZ_vi)(u32) = import_l;
u32 (*Z_envZ_mZ_iiiiii)(u32, u32, u32, u32, u32) = import_m;
u32 (*Z_envZ_nZ_iiiii)(u32, u32, u32, u32) = import_n;
u32 (*Z_envZ_oZ_ii)(u32) = import_o;
u32 (*Z_envZ_pZ_iiii)(u32, u32, u32) = import_p;
void (*Z_envZ_qZ_vv)(void) = import_q;
void (*Z_envZ_rZ_vi)(u32) = import_r;
u32 (*Z_envZ_sZ_ii)(u32) = import_s;
u32 (*Z_envZ_tZ_iiii)(u32, u32, u32) = import_t;
void (*Z_envZ_uZ_vii)(u32, u32) = import_u;
u32 (*Z_envZ_vZ_iv)(void) = import_v;
u32 (*Z_envZ_wZ_iv)(void) = import_w;
u32 (*Z_envZ_xZ_iiii)(u32, u32, u32) = import_x;
u32 (*Z_envZ_yZ_iiidiiii)(u32, u32, f64, u32, u32, u32, u32) = import_y;
u32 (*Z_envZ_zZ_iii)(u32, u32) = import_z;
void (*Z_envZ_AZ_vv)(void) = import_A;
u32 (*Z_envZ_BZ_iii)(u32, u32) = import_B;
void (*Z_envZ_CZ_vi)(u32) = import_C;
u32 (*Z_envZ_DZ_iii)(u32, u32) = import_D;
u32 (*Z_envZ_EZ_ii)(u32) = import_E;
void (*Z_envZ_FZ_vi)(u32) = import_F;
f64 (*Z_envZ_GZ_diii)(u32, u32, u32) = import_G;
void (*Z_envZ_HZ_vi)(u32) = import_H;
u32 (*Z_envZ_IZ_iii)(u32, u32) = import_I;
void (*Z_envZ_JZ_vii)(u32, u32) = import_J;
void (*Z_envZ_KZ_viii)(u32, u32, u32) = import_K;
void (*Z_envZ_LZ_vii)(u32, u32) = import_L;
void (*Z_envZ_MZ_viii)(u32, u32, u32) = import_M;
void (*Z_envZ_NZ_viiiii)(u32, u32, u32, u32, u32) = import_N;
void (*Z_envZ_OZ_viii)(u32, u32, u32) = import_O;
void (*Z_envZ_PZ_vii)(u32, u32) = import_P;
void (*Z_envZ_QZ_viiiii)(u32, u32, u32, u32, u32) = import_Q;
u32 (*Z_envZ_RZ_iii)(u32, u32) = import_R;
u32 (*Z_envZ_SZ_iii)(u32, u32) = import_S;
u32 (*Z_envZ_TZ_iii)(u32, u32) = import_T;
void (*Z_envZ_UZ_vi)(u32) = import_U;
u32 (*Z_envZ_VZ_iv)(void) = import_V;
u32 (*Z_envZ_WZ_ii)(u32) = import_W;
void (*Z_envZ_XZ_viiiiiii)(u32, u32, u32, u32, u32, u32, u32) = import_X;
void (*Z_envZ_YZ_viiiiii)(u32, u32, u32, u32, u32, u32) = import_Y;
void (*Z_envZ_Z5AZ_viiiii)(u32, u32, u32, u32, u32) = import_Z;
void (*Z_envZ__Z_viiii)(u32, u32, u32, u32) = import_;
void (*Z_envZ_Z24Z_viii)(u32, u32, u32) = import_dollar;
void (*Z_envZ_aaZ_vi)(u32) = import_aa;

static int cmg_init_locked(void) {
  int trap;
  if (g_initialized) {
    return 1;
  }
  LOGI("CMG init: allocate memory/table");
  wasm_rt_allocate_memory(&g_memory, CMG_INITIAL_PAGES, CMG_MAX_PAGES);
  wasm_rt_allocate_table(&g_table, CMG_TABLE_SIZE, CMG_TABLE_SIZE);
  cmg_reset_emval_state();
  memset(g_memory.data, 0, g_memory.size);
  if (!cmg_apply_memory_init()) {
    LOGE("CMG memory init failed");
    return 0;
  }
  trap = wasm_rt_impl_try();
  if (trap != 0) {
    LOGE("CMG wasm init trapped: %d", trap);
    return 0;
  }
  LOGI("CMG init: generated init begin");
  init();
  ((u32*) (g_memory.data + CMG_DYNAMIC_TOP_PTR))[0] = CMG_DYNAMIC_TOP_AFTER_SHELL_ALLOCATIONS;
  LOGI("CMG init: global constructors");
  (*Z_LaZ_vv)();
  ((u32*) (g_memory.data + CMG_DYNAMIC_TOP_PTR))[0] = CMG_DYNAMIC_TOP_AFTER_SHELL_ALLOCATIONS;
  cmg_make_player_tag();
  LOGI("CMG init: activate player tag %s", g_player_tag);
  u32 init_tag_address = cmg_write_tag_buffer(g_player_tag, CMG_MEMORY_EXTEND, 1);
  LOGI("CMG init: activate player tagAddr=%u size=%u",
      init_tag_address, (u32) (strlen(g_player_tag) + CMG_MEMORY_EXTEND));
  g_player_init_trap = wasm_rt_impl_try();
  if (g_player_init_trap == 0) {
    g_trace_phase = "init";
    LOGI("CMG InitPlayer begin tagAddr=%u top=%u", init_tag_address, cmg_dynamic_top());
    g_player_init_result = (*Z_baZ_ii)(init_tag_address);
    LOGI("CMG InitPlayer end result=%08x top=%u", g_player_init_result, cmg_dynamic_top());
    g_trace_phase = "idle";
    LOGI("CMG InitPlayer result=%08x clock=%llu",
        g_player_init_result, (unsigned long long) cmg_now_ms64());
    g_player_active = 1;
  } else {
    LOGE("CMG InitPlayer trapped: %d", g_player_init_trap);
    g_player_active = 1;
  }
  if (init_tag_address != 0) {
    (*Z_BaZ_vi)(init_tag_address);
    LOGI("CMG InitPlayer free tagAddr=%u top=%u", init_tag_address, cmg_dynamic_top());
  }
  g_initialized = 1;
  LOGI("CMG wasm runtime initialized");
  return 1;
}

static void cmg_reset_runtime_locked(void) {
  u32 handle;
  if (g_memory.data != NULL) {
    free(g_memory.data);
  }
  if (g_table.data != NULL) {
    free(g_table.data);
  }
  for (handle = 5; handle < g_emval_handle_count; handle++) {
    free(g_emval_handles[handle].text);
  }
  memset(&g_memory, 0, sizeof(g_memory));
  memset(&g_table, 0, sizeof(g_table));
  memset(g_player_tag, 0, sizeof(g_player_tag));
  memset(g_emval_handles, 0, sizeof(g_emval_handles));
  memset(g_emval_free_list, 0, sizeof(g_emval_free_list));
  g_initialized = 0;
  g_player_active = 0;
  g_player_init_trap = 0;
  g_player_init_result = 0;
  g_update_tag = 0;
  g_logged_update_tag = 0;
  g_emval_free_count = 0;
  g_emval_handle_count = 0;
  g_location_property_reads = 0;
  g_decode_data_address = 0;
  g_decode_data_capacity = 0;
  g_decode_tag_address = 0;
  g_decode_tag_capacity = 0;
  g_trace_phase = "idle";
}

static int cmg_parse_trace_update_delta(const char* update_trace, int wanted_index, uint64_t* delta) {
  char* copy;
  char* save;
  char* token;
  int index = 0;
  if (delta == NULL) {
    return 0;
  }
  *delta = 0u;
  if (update_trace == NULL || update_trace[0] == '\0') {
    return 0;
  }
  copy = strdup(update_trace);
  if (copy == NULL) {
    return 0;
  }
  token = strtok_r(copy, ";", &save);
  while (token != NULL) {
    if (index == wanted_index) {
      char* comma = strchr(token, ',');
      if (comma != NULL) {
        *comma = '\0';
      }
      *delta = (uint64_t) strtoull(token, NULL, 10);
      free(copy);
      return 1;
    }
    index++;
    token = strtok_r(NULL, ";", &save);
  }
  free(copy);
  return 0;
}

typedef struct cmg_replay_alloc {
  u32 official;
  u32 actual;
} cmg_replay_alloc;

static u32 cmg_replay_lookup_alloc(cmg_replay_alloc* allocs, int count, u32 official) {
  int i;
  for (i = count - 1; i >= 0; i--) {
    if (allocs[i].official == official) {
      return allocs[i].actual;
    }
  }
  return 0u;
}

static void cmg_replay_remove_alloc(cmg_replay_alloc* allocs, int* count, u32 official) {
  int i;
  if (allocs == NULL || count == NULL) {
    return;
  }
  for (i = *count - 1; i >= 0; i--) {
    if (allocs[i].official == official) {
      int j;
      for (j = i; j + 1 < *count; j++) {
        allocs[j] = allocs[j + 1];
      }
      (*count)--;
      return;
    }
  }
}

static int cmg_trace_starts_with(const char* text, const char* prefix) {
  return text != NULL && strncmp(text, prefix, strlen(prefix)) == 0;
}

static u32 cmg_parse_event_number(const char* event, const char* marker) {
  const char* start;
  if (event == NULL || marker == NULL) {
    return 0u;
  }
  start = strstr(event, marker);
  if (start == NULL) {
    return 0u;
  }
  start += strlen(marker);
  return (u32) strtoul(start, NULL, 10);
}

static u32 cmg_replay_official_trace_locked(const char* native_trace,
    const char* update_trace, uint64_t update_base_time_ms, int clock_offset_ms) {
  char* copy;
  char* save;
  char* token;
  char* events[128];
  int event_count = 0;
  int i;
  int update_index = 0;
  u32 last_tag = 0u;
  cmg_replay_alloc allocs[64];
  int alloc_count = 0;
  const char* previous_phase = g_trace_phase;
  if (native_trace == NULL || native_trace[0] == '\0' || !g_initialized || !g_player_active) {
    return 0u;
  }
  copy = strdup(native_trace);
  if (copy == NULL) {
    return 0u;
  }
  token = strtok_r(copy, ";", &save);
  while (token != NULL && event_count < (int) (sizeof(events) / sizeof(events[0]))) {
    events[event_count++] = token;
    token = strtok_r(NULL, ";", &save);
  }
  g_trace_phase = "replay";
  for (i = 0; i < event_count; i++) {
    const char* event = events[i];
    if (cmg_trace_starts_with(event, "_CMG_InitPlayer")) {
      continue;
    }
    if (cmg_trace_starts_with(event, "_CMG_UpdatePlayer")) {
      uint64_t delta = 0u;
      if (update_base_time_ms > 0u
          && cmg_parse_trace_update_delta(update_trace, update_index, &delta)) {
        int64_t clock = (int64_t) update_base_time_ms + (int64_t) delta + clock_offset_ms;
        g_forced_clock_ms = clock > 0 ? (uint64_t) clock : 0u;
      }
      last_tag = cmg_update_player_with_fresh_tag();
      g_update_tag = last_tag;
      update_index++;
      continue;
    }
    if (cmg_trace_starts_with(event, "_jsmalloc:")) {
      u32 size = cmg_parse_event_number(event, "_jsmalloc:");
      u32 official = cmg_parse_event_number(event, "->");
      if (i + 1 < event_count
          && (cmg_trace_starts_with(events[i + 1], "_CMG_UpdatePlayer")
              || cmg_trace_starts_with(events[i + 1], "_CMG_InitPlayer"))) {
        continue;
      }
      if (size > 0u && official > 0u && alloc_count < (int) (sizeof(allocs) / sizeof(allocs[0]))) {
        u32 actual = (*Z_CaZ_ii)(size);
        allocs[alloc_count].official = official;
        allocs[alloc_count].actual = actual;
        alloc_count++;
        if (g_update_call_log_count < 24) {
          LOGI("CMG replay malloc size=%u official=%u actual=%u", size, official, actual);
        }
      }
      continue;
    }
    if (cmg_trace_starts_with(event, "_jsfree:")) {
      u32 official = cmg_parse_event_number(event, "_jsfree:");
      u32 actual = cmg_replay_lookup_alloc(allocs, alloc_count, official);
      if (actual != 0u) {
        (*Z_BaZ_vi)(actual);
        cmg_replay_remove_alloc(allocs, &alloc_count, official);
        if (g_update_call_log_count < 24) {
          LOGI("CMG replay free official=%u actual=%u", official, actual);
        }
      }
      continue;
    }
  }
  g_trace_phase = previous_phase;
  free(copy);
  return last_tag;
}

JNIEXPORT jstring JNICALL
Java_com_mytvb_feature_cctv_cmg_CmgNativeBridge_probeRuntime(JNIEnv* env, jclass clazz) {
  char result[224];
  u32 update_tag = 0;
  int init_trap = 0;
  int update_trap = 0;
  (void) clazz;
  pthread_mutex_lock(&g_lock);
  if (cmg_init_locked()) {
    init_trap = g_player_init_trap;
    update_trap = wasm_rt_impl_try();
    if (update_trap == 0) {
      update_tag = cmg_update_player_with_fresh_tag();
    } else {
      LOGE("CMG UpdatePlayer trapped: %d", update_trap);
    }
  }
  pthread_mutex_unlock(&g_lock);
  snprintf(result, sizeof(result),
      "ready=%d initTrap=%d updateTrap=%d updateTag=%08x tag=%s memory=%u",
      g_initialized, init_trap, update_trap, update_tag, g_player_tag, g_memory.size);
  return (*env)->NewStringUTF(env, result);
}

JNIEXPORT jboolean JNICALL
Java_com_mytvb_feature_cctv_cmg_CmgNativeBridge_configureRuntimeForProbe(
    JNIEnv* env, jclass clazz, jstring player_tag, jint update_tag) {
  const char* tag_chars;
  (void) clazz;
  pthread_mutex_lock(&g_lock);
  if (g_initialized) {
    LOGW("CMG probe configure ignored because runtime is already initialized");
    pthread_mutex_unlock(&g_lock);
    return JNI_FALSE;
  }
  memset(g_forced_player_tag, 0, sizeof(g_forced_player_tag));
  if (player_tag != NULL) {
    tag_chars = (*env)->GetStringUTFChars(env, player_tag, NULL);
    if (tag_chars != NULL) {
      snprintf(g_forced_player_tag, sizeof(g_forced_player_tag), "%s", tag_chars);
      (*env)->ReleaseStringUTFChars(env, player_tag, tag_chars);
    }
  }
  g_update_tag = (u32) update_tag;
  g_logged_update_tag = g_update_tag;
  LOGI("CMG probe configured tag=%s updateTag=%08x",
      g_forced_player_tag[0] == '\0' ? "(auto)" : g_forced_player_tag, g_update_tag);
  pthread_mutex_unlock(&g_lock);
  return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_mytvb_feature_cctv_cmg_CmgNativeBridge_configureLocationForProbe(
    JNIEnv* env, jclass clazz, jstring href) {
  const char* href_chars;
  (void) clazz;
  pthread_mutex_lock(&g_lock);
  if (!g_initialized) {
    memset(g_forced_location_href, 0, sizeof(g_forced_location_href));
    if (href != NULL) {
      href_chars = (*env)->GetStringUTFChars(env, href, NULL);
      if (href_chars != NULL) {
        snprintf(g_forced_location_href, sizeof(g_forced_location_href), "%s", href_chars);
        (*env)->ReleaseStringUTFChars(env, href, href_chars);
      }
    }
    LOGI("CMG probe location href=%s",
        g_forced_location_href[0] == '\0' ? CMG_LOCATION_HREF : g_forced_location_href);
  }
  pthread_mutex_unlock(&g_lock);
}

JNIEXPORT jboolean JNICALL
Java_com_mytvb_feature_cctv_cmg_CmgNativeBridge_initializeRuntimeForProbe(JNIEnv* env, jclass clazz) {
  jboolean result;
  (void) env;
  (void) clazz;
  pthread_mutex_lock(&g_lock);
  result = cmg_init_locked() ? JNI_TRUE : JNI_FALSE;
  pthread_mutex_unlock(&g_lock);
  return result;
}

JNIEXPORT void JNICALL
Java_com_mytvb_feature_cctv_cmg_CmgNativeBridge_resetRuntimeForProbe(JNIEnv* env, jclass clazz) {
  (void) env;
  (void) clazz;
  pthread_mutex_lock(&g_lock);
  cmg_reset_runtime_locked();
  pthread_mutex_unlock(&g_lock);
}

JNIEXPORT jint JNICALL
Java_com_mytvb_feature_cctv_cmg_CmgNativeBridge_getPlayerInitResultForProbe(JNIEnv* env, jclass clazz) {
  jint result;
  (void) env;
  (void) clazz;
  pthread_mutex_lock(&g_lock);
  result = (jint) g_player_init_result;
  pthread_mutex_unlock(&g_lock);
  return result;
}

JNIEXPORT void JNICALL
Java_com_mytvb_feature_cctv_cmg_CmgNativeBridge_setClockForProbe(
    JNIEnv* env, jclass clazz, jlong epoch_millis) {
  (void) env;
  (void) clazz;
  pthread_mutex_lock(&g_lock);
  g_forced_clock_ms = epoch_millis > 0 ? (uint64_t) epoch_millis : 0u;
  pthread_mutex_unlock(&g_lock);
}

JNIEXPORT void JNICALL
Java_com_mytvb_feature_cctv_cmg_CmgNativeBridge_clearClockForProbe(JNIEnv* env, jclass clazz) {
  (void) env;
  (void) clazz;
  pthread_mutex_lock(&g_lock);
  g_forced_clock_ms = 0u;
  pthread_mutex_unlock(&g_lock);
}

JNIEXPORT void JNICALL
Java_com_mytvb_feature_cctv_cmg_CmgNativeBridge_setPlayerTagForProbe(
    JNIEnv* env, jclass clazz, jstring player_tag) {
  const char* tag_chars;
  (void) clazz;
  pthread_mutex_lock(&g_lock);
  if (player_tag != NULL) {
    tag_chars = (*env)->GetStringUTFChars(env, player_tag, NULL);
    if (tag_chars != NULL) {
      snprintf(g_player_tag, sizeof(g_player_tag), "%s", tag_chars);
      (*env)->ReleaseStringUTFChars(env, player_tag, tag_chars);
    }
  } else {
    cmg_make_player_tag();
  }
  pthread_mutex_unlock(&g_lock);
}

JNIEXPORT jint JNICALL
Java_com_mytvb_feature_cctv_cmg_CmgNativeBridge_updateSessionForProbe(JNIEnv* env, jclass clazz) {
  u32 update_tag = 0;
  int trap;
  (void) env;
  (void) clazz;
  pthread_mutex_lock(&g_lock);
  if (cmg_init_locked() && g_player_active) {
    trap = wasm_rt_impl_try();
    if (trap == 0) {
      update_tag = cmg_update_player_with_fresh_tag();
      g_update_tag = update_tag;
      if (update_tag != g_logged_update_tag) {
        LOGI("CMG updateTag=%08x", update_tag);
        g_logged_update_tag = update_tag;
      }
    } else {
      LOGE("CMG UpdatePlayer trapped: %d", trap);
    }
  }
  pthread_mutex_unlock(&g_lock);
  return (jint) update_tag;
}

JNIEXPORT jint JNICALL
Java_com_mytvb_feature_cctv_cmg_CmgNativeBridge_replayOfficialTraceForProbe(
    JNIEnv* env, jclass clazz, jstring native_trace, jstring update_trace,
    jlong update_base_time_ms, jint clock_offset_ms) {
  const char* native_chars = NULL;
  const char* update_chars = NULL;
  u32 result;
  (void) clazz;
  if (native_trace != NULL) {
    native_chars = (*env)->GetStringUTFChars(env, native_trace, NULL);
  }
  if (update_trace != NULL) {
    update_chars = (*env)->GetStringUTFChars(env, update_trace, NULL);
  }
  pthread_mutex_lock(&g_lock);
  result = cmg_replay_official_trace_locked(native_chars, update_chars,
      update_base_time_ms > 0 ? (uint64_t) update_base_time_ms : 0u,
      (int) clock_offset_ms);
  if (result != 0u) {
    g_update_tag = result;
    g_logged_update_tag = result;
    LOGI("CMG official trace replay result=%08x", result);
  }
  pthread_mutex_unlock(&g_lock);
  if (native_chars != NULL) {
    (*env)->ReleaseStringUTFChars(env, native_trace, native_chars);
  }
  if (update_chars != NULL) {
    (*env)->ReleaseStringUTFChars(env, update_trace, update_chars);
  }
  return (jint) result;
}

JNIEXPORT void JNICALL
Java_com_mytvb_feature_cctv_cmg_CmgNativeBridge_touchActiveForProbe(JNIEnv* env, jclass clazz) {
  int trap;
  (void) env;
  (void) clazz;
  pthread_mutex_lock(&g_lock);
  if (cmg_init_locked() && g_player_active) {
    trap = wasm_rt_impl_try();
    if (trap == 0) {
      (void) cmg_update_player_with_fresh_tag();
    } else {
      LOGE("CMG touch active trapped: %d", trap);
    }
  }
  pthread_mutex_unlock(&g_lock);
}

JNIEXPORT void JNICALL
Java_com_mytvb_feature_cctv_cmg_CmgNativeBridge_setUpdateTagForProbe(
    JNIEnv* env, jclass clazz, jint update_tag) {
  (void) env;
  (void) clazz;
  pthread_mutex_lock(&g_lock);
  if (g_update_tag == (u32) update_tag) {
    pthread_mutex_unlock(&g_lock);
    return;
  }
  g_update_tag = (u32) update_tag;
  g_logged_update_tag = g_update_tag;
  if (g_update_tag != 0u) {
    LOGI("CMG forced updateTag=%08x", g_update_tag);
  }
  pthread_mutex_unlock(&g_lock);
}

JNIEXPORT jbyteArray JNICALL
Java_com_mytvb_feature_cctv_cmg_CmgNativeBridge_decodeNalForProbe(
    JNIEnv* env, jclass clazz, jbyteArray input, jboolean live, jboolean run_steps) {
  jsize length;
  jbyte* input_bytes;
  jbyteArray output;
  u32 data_address;
  u32 tag_address;
  u32 host_length;
  size_t data_allocation_size;
  u32 update_tag;
  u32 output_length;
  int trap;
  int index;
  int ran_steps = 0;
  uint8_t* data_pointer;
  cmg_decode_function live_functions[9];
  cmg_decode_function vod_functions[9];
  cmg_decode_function* functions;
  (void) clazz;
  (void) live;
  if (input == NULL) {
    return NULL;
  }
  length = (*env)->GetArrayLength(env, input);
  input_bytes = (*env)->GetByteArrayElements(env, input, NULL);
  if (input_bytes == NULL) {
    return NULL;
  }
  pthread_mutex_lock(&g_lock);
  if (!cmg_init_locked()) {
    pthread_mutex_unlock(&g_lock);
    (*env)->ReleaseByteArrayElements(env, input, input_bytes, JNI_ABORT);
    return NULL;
  }
  if (!g_player_active) {
    pthread_mutex_unlock(&g_lock);
    (*env)->ReleaseByteArrayElements(env, input, input_bytes, JNI_ABORT);
    return NULL;
  }
  live_functions[0] = Z_eaZ_iiiii;
  live_functions[1] = Z_faZ_iiiii;
  live_functions[2] = Z_gaZ_iiiii;
  live_functions[3] = Z_haZ_iiiii;
  live_functions[4] = Z_iaZ_iiiii;
  live_functions[5] = Z_jaZ_iiiii;
  live_functions[6] = Z_kaZ_iiiii;
  live_functions[7] = Z_laZ_iiiii;
  live_functions[8] = Z_maZ_iiiii;
  vod_functions[0] = Z_naZ_iiiii;
  vod_functions[1] = Z_oaZ_iiiii;
  vod_functions[2] = Z_paZ_iiiii;
  vod_functions[3] = Z_qaZ_iiiii;
  vod_functions[4] = Z_raZ_iiiii;
  vod_functions[5] = Z_saZ_iiiii;
  vod_functions[6] = Z_taZ_iiiii;
  vod_functions[7] = Z_uaZ_iiiii;
  vod_functions[8] = Z_vaZ_iiiii;
  functions = live ? live_functions : vod_functions;
  host_length = live ? (u32) strlen(CMG_PAGE_HOST) : 0;
  data_allocation_size = (size_t) length + CMG_NAL_MEMORY_EXTEND;
  data_address = (*Z_CaZ_ii)((u32) data_allocation_size);
  data_pointer = cmg_pointer(data_address, data_allocation_size);
  if (data_pointer == NULL) {
    pthread_mutex_unlock(&g_lock);
    (*env)->ReleaseByteArrayElements(env, input, input_bytes, JNI_ABORT);
    return NULL;
  }
  memcpy(data_pointer, input_bytes, (size_t) length);
  if (live && host_length > 0) {
    memcpy(data_pointer + length, CMG_PAGE_HOST, host_length);
  }
  tag_address = cmg_write_tag_buffer(g_player_tag, 0, 0);
  if (cmg_pointer(tag_address, strlen(g_player_tag)) == NULL) {
    (*Z_BaZ_vi)(data_address);
    if (tag_address != 0) {
      (*Z_BaZ_vi)(tag_address);
    }
    pthread_mutex_unlock(&g_lock);
    (*env)->ReleaseByteArrayElements(env, input, input_bytes, JNI_ABORT);
    return NULL;
  }
  output_length = (u32) length;
  trap = wasm_rt_impl_try();
  if (trap != 0) {
    LOGE("CMG decode trapped: trap=%d len=%d live=%d updateTag=%08x",
        trap, (int) length, live ? 1 : 0, g_update_tag);
    (*Z_BaZ_vi)(data_address);
    (*Z_BaZ_vi)(tag_address);
    pthread_mutex_unlock(&g_lock);
    (*env)->ReleaseByteArrayElements(env, input, input_bytes, JNI_ABORT);
    return NULL;
  }
  update_tag = g_update_tag;
  if (update_tag == 0) {
    update_tag = cmg_update_player_with_fresh_tag();
    g_update_tag = update_tag;
  }
  if (length > 100000) {
    LOGI("CMG decode begin len=%d live=%d runSteps=%d updateTag=%08x dataAddr=%u dataSize=%u tagAddr=%u hostLen=%u top=%u g6=%u g7=%u g9=%u",
        (int) length, live ? 1 : 0, run_steps ? 1 : 0, update_tag,
        data_address, (u32) data_allocation_size, tag_address, host_length,
        cmg_dynamic_top(), cmg_debug_get_g6(), cmg_debug_get_g7(), cmg_debug_get_g9());
  }
  if (run_steps) {
    for (index = 0; index < 8; index++) {
      if (cmg_should_run_decode_step(update_tag, index)) {
        functions[7 - index](tag_address, data_address, (u32) length, host_length);
        ran_steps++;
      }
    }
  }
  if (live) {
    output_length = functions[8](tag_address, data_address, (u32) length, host_length);
  } else {
    output_length = functions[8](data_address, tag_address, (u32) length, host_length);
  }
  if (length > 100000) {
    LOGI("CMG decode end len=%d out=%u ranSteps=%d top=%u g6=%u g7=%u g9=%u head=%02x%02x%02x%02x %02x%02x%02x%02x",
        (int) length, output_length, ran_steps,
        cmg_dynamic_top(), cmg_debug_get_g6(), cmg_debug_get_g7(), cmg_debug_get_g9(),
        data_pointer[0], data_pointer[1], data_pointer[2], data_pointer[3],
        data_pointer[32], data_pointer[33], data_pointer[34], data_pointer[35]);
  }
  output = (*env)->NewByteArray(env, (jsize) output_length);
  if (output != NULL) {
    (*env)->SetByteArrayRegion(env, output, 0, (jsize) output_length, (jbyte*) data_pointer);
  }
  (*Z_BaZ_vi)(data_address);
  (*Z_BaZ_vi)(tag_address);
  pthread_mutex_unlock(&g_lock);
  (*env)->ReleaseByteArrayElements(env, input, input_bytes, JNI_ABORT);
  return output;
}

/*
 * Production fast path for Dalvik devices. The old probe API returns a new
 * byte[] for every NAL, which creates several megabytes of short-lived Java
 * objects per transport-stream segment. Decode from a range of the PES buffer
 * and copy the result back only when it fits in the original range.
 *
 * Returns the decoded length, -1 on failure, or -2 when the output grew and
 * therefore cannot be written in place.
 */
JNIEXPORT jint JNICALL
Java_com_mytvb_feature_cctv_cmg_CmgNativeBridge_decodeNalRangeInPlace(
    JNIEnv* env, jclass clazz, jbyteArray input, jint offset, jint length,
    jboolean live, jboolean run_steps) {
  jsize array_length;
  jbyte* input_bytes;
  u32 data_address;
  u32 tag_address;
  u32 host_length;
  size_t data_allocation_size;
  u32 update_tag;
  u32 output_length;
  int trap;
  int index;
  int result = -1;
  uint8_t* data_pointer;
  uint8_t* tag_pointer;
  size_t tag_length;
  cmg_decode_function live_functions[9];
  cmg_decode_function vod_functions[9];
  cmg_decode_function* functions;
  (void) clazz;

  if (input == NULL || offset < 0 || length <= 0) {
    return -1;
  }
  array_length = (*env)->GetArrayLength(env, input);
  if (offset > array_length || length > array_length - offset) {
    return -1;
  }
  input_bytes = (*env)->GetByteArrayElements(env, input, NULL);
  if (input_bytes == NULL) {
    return -1;
  }

  pthread_mutex_lock(&g_lock);
  if (!cmg_init_locked() || !g_player_active) {
    pthread_mutex_unlock(&g_lock);
    (*env)->ReleaseByteArrayElements(env, input, input_bytes, JNI_ABORT);
    return -1;
  }

  live_functions[0] = Z_eaZ_iiiii;
  live_functions[1] = Z_faZ_iiiii;
  live_functions[2] = Z_gaZ_iiiii;
  live_functions[3] = Z_haZ_iiiii;
  live_functions[4] = Z_iaZ_iiiii;
  live_functions[5] = Z_jaZ_iiiii;
  live_functions[6] = Z_kaZ_iiiii;
  live_functions[7] = Z_laZ_iiiii;
  live_functions[8] = Z_maZ_iiiii;
  vod_functions[0] = Z_naZ_iiiii;
  vod_functions[1] = Z_oaZ_iiiii;
  vod_functions[2] = Z_paZ_iiiii;
  vod_functions[3] = Z_qaZ_iiiii;
  vod_functions[4] = Z_raZ_iiiii;
  vod_functions[5] = Z_saZ_iiiii;
  vod_functions[6] = Z_taZ_iiiii;
  vod_functions[7] = Z_uaZ_iiiii;
  vod_functions[8] = Z_vaZ_iiiii;
  functions = live ? live_functions : vod_functions;
  host_length = live ? (u32) strlen(CMG_PAGE_HOST) : 0;
  data_allocation_size = (size_t) length + CMG_NAL_MEMORY_EXTEND;
  if (g_decode_data_address == 0
      || g_decode_data_capacity < (u32) data_allocation_size) {
    if (g_decode_data_address != 0) {
      (*Z_BaZ_vi)(g_decode_data_address);
    }
    g_decode_data_address = (*Z_CaZ_ii)((u32) data_allocation_size);
    g_decode_data_capacity = (u32) data_allocation_size;
  }
  tag_length = strlen(g_player_tag);
  if (g_decode_tag_address == 0 || g_decode_tag_capacity < (u32) tag_length) {
    if (g_decode_tag_address != 0) {
      (*Z_BaZ_vi)(g_decode_tag_address);
    }
    g_decode_tag_address = (*Z_CaZ_ii)((u32) tag_length);
    g_decode_tag_capacity = (u32) tag_length;
  }
  data_address = g_decode_data_address;
  tag_address = g_decode_tag_address;
  data_pointer = cmg_pointer(data_address, data_allocation_size);
  tag_pointer = cmg_pointer(tag_address, tag_length);
  if (data_pointer == NULL || tag_pointer == NULL) {
    pthread_mutex_unlock(&g_lock);
    (*env)->ReleaseByteArrayElements(env, input, input_bytes, JNI_ABORT);
    return -1;
  }
  memcpy(data_pointer, input_bytes + offset, (size_t) length);
  if (live && host_length > 0) {
    memcpy(data_pointer + length, CMG_PAGE_HOST, host_length);
  }
  memcpy(tag_pointer, g_player_tag, tag_length);

  trap = wasm_rt_impl_try();
  if (trap == 0) {
    update_tag = g_update_tag;
    if (update_tag == 0) {
      update_tag = cmg_update_player_with_fresh_tag();
      g_update_tag = update_tag;
    }
    if (run_steps) {
      for (index = 0; index < 8; index++) {
        if (cmg_should_run_decode_step(update_tag, index)) {
          functions[7 - index](tag_address, data_address, (u32) length, host_length);
        }
      }
    }
    if (live) {
      output_length = functions[8](tag_address, data_address, (u32) length, host_length);
    } else {
      output_length = functions[8](data_address, tag_address, (u32) length, host_length);
    }
    if (output_length <= (u32) length) {
      memcpy(input_bytes + offset, data_pointer, (size_t) output_length);
      result = (int) output_length;
    } else {
      result = -2;
    }
  } else {
    LOGE("CMG in-place decode trapped: trap=%d len=%d live=%d updateTag=%08x",
        trap, (int) length, live ? 1 : 0, g_update_tag);
  }

  pthread_mutex_unlock(&g_lock);
  (*env)->ReleaseByteArrayElements(env, input, input_bytes,
      result >= 0 ? 0 : JNI_ABORT);
  return (jint) result;
}

JNIEXPORT jbyteArray JNICALL
Java_com_mytvb_feature_cctv_cmg_CmgNativeBridge_decodeNalSingleStepForProbe(
    JNIEnv* env, jclass clazz, jbyteArray input, jboolean live, jint step) {
  jsize length;
  jbyte* input_bytes;
  jbyteArray output;
  u32 data_address;
  u32 tag_address;
  u32 host_length;
  size_t data_allocation_size;
  u32 output_length;
  int trap;
  uint8_t* data_pointer;
  cmg_decode_function live_functions[9];
  cmg_decode_function vod_functions[9];
  cmg_decode_function* functions;
  (void) clazz;
  if (input == NULL || step < 0 || step > 8) {
    return NULL;
  }
  length = (*env)->GetArrayLength(env, input);
  input_bytes = (*env)->GetByteArrayElements(env, input, NULL);
  if (input_bytes == NULL) {
    return NULL;
  }
  pthread_mutex_lock(&g_lock);
  if (!cmg_init_locked() || !g_player_active) {
    pthread_mutex_unlock(&g_lock);
    (*env)->ReleaseByteArrayElements(env, input, input_bytes, JNI_ABORT);
    return NULL;
  }
  live_functions[0] = Z_eaZ_iiiii;
  live_functions[1] = Z_faZ_iiiii;
  live_functions[2] = Z_gaZ_iiiii;
  live_functions[3] = Z_haZ_iiiii;
  live_functions[4] = Z_iaZ_iiiii;
  live_functions[5] = Z_jaZ_iiiii;
  live_functions[6] = Z_kaZ_iiiii;
  live_functions[7] = Z_laZ_iiiii;
  live_functions[8] = Z_maZ_iiiii;
  vod_functions[0] = Z_naZ_iiiii;
  vod_functions[1] = Z_oaZ_iiiii;
  vod_functions[2] = Z_paZ_iiiii;
  vod_functions[3] = Z_qaZ_iiiii;
  vod_functions[4] = Z_raZ_iiiii;
  vod_functions[5] = Z_saZ_iiiii;
  vod_functions[6] = Z_taZ_iiiii;
  vod_functions[7] = Z_uaZ_iiiii;
  vod_functions[8] = Z_vaZ_iiiii;
  functions = live ? live_functions : vod_functions;
  host_length = live ? (u32) strlen(CMG_PAGE_HOST) : 0;
  data_allocation_size = (size_t) length + CMG_NAL_MEMORY_EXTEND;
  data_address = (*Z_CaZ_ii)((u32) data_allocation_size);
  data_pointer = cmg_pointer(data_address, data_allocation_size);
  if (data_pointer == NULL) {
    pthread_mutex_unlock(&g_lock);
    (*env)->ReleaseByteArrayElements(env, input, input_bytes, JNI_ABORT);
    return NULL;
  }
  memcpy(data_pointer, input_bytes, (size_t) length);
  if (live && host_length > 0) {
    memcpy(data_pointer + length, CMG_PAGE_HOST, host_length);
  }
  tag_address = cmg_write_tag_buffer(g_player_tag, 0, 0);
  if (cmg_pointer(tag_address, strlen(g_player_tag)) == NULL) {
    (*Z_BaZ_vi)(data_address);
    if (tag_address != 0) {
      (*Z_BaZ_vi)(tag_address);
    }
    pthread_mutex_unlock(&g_lock);
    (*env)->ReleaseByteArrayElements(env, input, input_bytes, JNI_ABORT);
    return NULL;
  }
  output_length = (u32) length;
  trap = wasm_rt_impl_try();
  if (trap != 0) {
    LOGE("CMG single step trapped: trap=%d len=%d live=%d step=%d",
        trap, (int) length, live ? 1 : 0, (int) step);
    (*Z_BaZ_vi)(data_address);
    (*Z_BaZ_vi)(tag_address);
    pthread_mutex_unlock(&g_lock);
    (*env)->ReleaseByteArrayElements(env, input, input_bytes, JNI_ABORT);
    return NULL;
  }
  if (live || step != 8) {
    output_length = functions[step](tag_address, data_address, (u32) length, host_length);
  } else {
    output_length = functions[step](data_address, tag_address, (u32) length, host_length);
  }
  if (length > 100000) {
    LOGI("CMG single step len=%d live=%d step=%d out=%u dataAddr=%u dataSize=%u tagAddr=%u head=%02x%02x%02x%02x",
        (int) length, live ? 1 : 0, (int) step, output_length,
        data_address, (u32) data_allocation_size, tag_address,
        data_pointer[32], data_pointer[33], data_pointer[34], data_pointer[35]);
  }
  output = (*env)->NewByteArray(env, (jsize) output_length);
  if (output != NULL) {
    (*env)->SetByteArrayRegion(env, output, 0, (jsize) output_length, (jbyte*) data_pointer);
  }
  (*Z_BaZ_vi)(data_address);
  (*Z_BaZ_vi)(tag_address);
  pthread_mutex_unlock(&g_lock);
  (*env)->ReleaseByteArrayElements(env, input, input_bytes, JNI_ABORT);
  return output;
}

/* ============================ 段级状态机（MyTVB 接入层） ============================
 * 参考 nTv HlsProxyServer.decryptYangshipinTransportStream / decryptPesNals 的 Java
 * 实现下沉到 C：每个 TS 段整体重启 wasm runtime；段内每个受控 NAL 前推进会话
 * （服务器校准时钟 + UpdatePlayer）；SPS(type7) 探测加密位并仅推进状态（输出丢弃）；
 * VCL(1/5) 原地替换只允许变短；PES 变短时重打包并重排视频 PID 的 continuity
 * counter；VCL 累计 150 后在下一个 SPS 边界重启 runtime。
 * 与 nTv 的每 NAL 多次 JNI 相比这里全程在 C 内闭环，宿主每段只调一次。 */

#define CMG_H264_NAL_NON_IDR 1
#define CMG_H264_NAL_IDR 5
#define CMG_H264_NAL_SPS 7
#define CMG_LIVE_MAX_VCL_PER_RUNTIME 150

static int64_t g_cmg_clock_offset_ms; /* 服务器时间 - 本地时间 (ms) */
static int g_cmg_session_configured;
static int g_cmg_vcl_since_restart;
static int g_cmg_live_video_decode_enabled;
static u32 g_cmg_nal_data_address;
static u32 g_cmg_nal_data_capacity;

typedef struct cmg_pes_slot {
  int packet_offset;    /* TS 包起点 */
  int transport_offset; /* 包内 payload 起点 */
} cmg_pes_slot;

typedef struct cmg_pes_buffer {
  uint8_t* data;
  size_t capacity;
  size_t size;
  cmg_pes_slot* slots;
  size_t slot_count;
  size_t slot_capacity;
  int header_offset; /* PES header 首字节在 TS 里的偏移（更新 PES_length 用） */
  int header_length;
  int packet_length; /* 原始 PES_packet_length（0=未指定） */
} cmg_pes_buffer;

static int64_t cmg_wall_ms(void) {
  struct timeval now;
  gettimeofday(&now, NULL);
  return (int64_t) now.tv_sec * 1000 + (int64_t) now.tv_usec / 1000;
}

/* 与 nTv syncCmgRuntimeClockForNative 同义：墙钟 + 服务器偏移。 */
static void cmg_sync_runtime_clock_locked(void) {
  if (!g_cmg_session_configured) {
    return;
  }
  g_forced_clock_ms = (uint64_t) (cmg_wall_ms() + g_cmg_clock_offset_ms);
}

static void cmg_restart_runtime_locked(const char* reason) {
  cmg_sync_runtime_clock_locked();
  cmg_reset_runtime_locked();
  /* reset 已释放旧 wasm 堆并清掉原版的 g_decode_tag_address，但不知道本层新增的
   * NAL 数据缓冲地址——残留的旧堆偏移复用会在新堆上越界写（实测播放数十秒后
   * SIGSEGV 的根因），必须一并置空让下次解密重新分配。 */
  g_cmg_nal_data_address = 0;
  g_cmg_nal_data_capacity = 0;
  if (cmg_init_locked()) {
    LOGI("CMG runtime restarted (%s) tag=%s", reason, g_player_tag);
  } else {
    LOGE("CMG runtime restart failed (%s)", reason);
  }
  g_cmg_vcl_since_restart = 0;
  g_cmg_live_video_decode_enabled = 0;
}

static int cmg_pes_reserve(cmg_pes_buffer* pes, size_t payload_size, size_t slot_count) {
  if (pes->capacity < payload_size) {
    size_t next = pes->capacity == 0 ? 64 * 1024 : pes->capacity * 2;
    uint8_t* grown;
    while (next < payload_size) {
      next *= 2;
    }
    grown = (uint8_t*) realloc(pes->data, next);
    if (grown == NULL) {
      return 0;
    }
    pes->data = grown;
    pes->capacity = next;
  }
  if (pes->slot_capacity < slot_count) {
    size_t next = pes->slot_capacity == 0 ? 64 : pes->slot_capacity * 2;
    cmg_pes_slot* grown;
    while (next < slot_count) {
      next *= 2;
    }
    grown = (cmg_pes_slot*) realloc(pes->slots, next * sizeof(cmg_pes_slot));
    if (grown == NULL) {
      return 0;
    }
    pes->slots = grown;
    pes->slot_capacity = next;
  }
  return 1;
}

static void cmg_pes_reset(cmg_pes_buffer* pes) {
  pes->size = 0;
  pes->slot_count = 0;
  pes->header_offset = -1;
  pes->header_length = 0;
  pes->packet_length = 0;
}

static void cmg_pes_dispose(cmg_pes_buffer* pes) {
  free(pes->data);
  free(pes->slots);
  memset(pes, 0, sizeof(*pes));
}

/* 返回预期 payload 长度（PES_packet_length 派生），未知返回 -1。 */
static int cmg_pes_expected_payload(const cmg_pes_buffer* pes) {
  if (pes->packet_length <= 0 || pes->header_length <= 0) {
    return -1;
  }
  return pes->packet_length - (pes->header_length - 6);
}

static void cmg_pes_update_length(cmg_pes_buffer* pes, uint8_t* ts, int payload_length) {
  int updated;
  if (pes->header_offset < 0 || pes->packet_length == 0) {
    return;
  }
  updated = payload_length + pes->header_length - 6;
  if (updated > 0xffff) {
    LOGW("CMG rebuilt PES payload too large: %d", updated);
    return;
  }
  ts[pes->header_offset + 4] = (uint8_t) ((updated >> 8) & 0xff);
  ts[pes->header_offset + 5] = (uint8_t) (updated & 0xff);
}

/* 等长直拷或变短重打包（尾部包 stuffing / 整包清 payload 改 adaptation-only）。 */
static void cmg_pes_copy_payload_to_ts(cmg_pes_buffer* pes, uint8_t* ts,
    const uint8_t* data, int data_length) {
  size_t slot;
  int data_offset = 0;
  cmg_pes_update_length(pes, ts, data_length);
  for (slot = 0; slot < pes->slot_count; slot++) {
    int packet_offset = pes->slots[slot].packet_offset;
    int transport_offset = pes->slots[slot].transport_offset;
    int packet_end = packet_offset + 188;
    int capacity = packet_end - transport_offset;
    int remaining = data_length - data_offset;
    int count = capacity < remaining ? capacity : remaining;
    if (count < 0) {
      count = 0;
    }
    if (count == capacity) {
      memcpy(ts + transport_offset, data + data_offset, (size_t) count);
      data_offset += count;
      continue;
    }
    memset(ts + packet_offset + 4, 0xff, (size_t) (packet_end - packet_offset - 4));
    if (count <= 0) {
      ts[packet_offset + 1] = (uint8_t) (ts[packet_offset + 1] & ~0x40);
      ts[packet_offset + 3] = (uint8_t) ((ts[packet_offset + 3] & 0xcf) | 0x20);
      ts[packet_offset + 4] = (uint8_t) 183;
      ts[packet_offset + 5] = 0;
    } else {
      int payload_start = packet_end - count;
      int adaptation_length = payload_start - packet_offset - 5;
      ts[packet_offset + 3] = (uint8_t) ((ts[packet_offset + 3] & 0xcf) | 0x30);
      ts[packet_offset + 4] = (uint8_t) adaptation_length;
      if (adaptation_length > 0) {
        ts[packet_offset + 5] = 0;
      }
      memcpy(ts + payload_start, data + data_offset, (size_t) count);
      data_offset += count;
    }
  }
  if (data_offset < data_length) {
    LOGW("CMG rebuilt PES did not fit original packets before=%u after=%d",
        (u32) pes->size, data_length);
  }
}

static int cmg_payload_offset(const uint8_t* ts, int packet_offset) {
  int adaptation_control = (ts[packet_offset + 3] >> 4) & 3;
  int offset;
  if ((adaptation_control & 1) == 0) {
    return -1;
  }
  offset = packet_offset + 4;
  if ((adaptation_control & 2) != 0) {
    offset += 1 + (ts[offset] & 0xff);
  }
  return offset < packet_offset + 188 ? offset : -1;
}

static int cmg_start_code_length(const uint8_t* data, int data_length, int offset) {
  if (offset + 3 < data_length && data[offset] == 0 && data[offset + 1] == 0) {
    if (data[offset + 2] == 1) {
      return 3;
    }
    if (offset + 4 < data_length && data[offset + 2] == 0 && data[offset + 3] == 1) {
      return 4;
    }
  }
  return 0;
}

static int cmg_find_video_pid(const uint8_t* ts, int ts_length) {
  int pmt_pid = -1;
  int offset;
  for (offset = 0; offset + 188 <= ts_length; offset += 188) {
    int pid;
    int payload;
    const uint8_t* section;
    int section_start;
    int section_length;
    int section_end;
    if (ts[offset] != 0x47) {
      continue;
    }
    pid = ((ts[offset + 1] & 0x1f) << 8) | (ts[offset + 2] & 0xff);
    payload = cmg_payload_offset(ts, offset);
    if (payload < 0 || (ts[offset + 1] & 0x40) == 0) {
      continue;
    }
    section_start = payload + 1 + (ts[payload] & 0xff);
    if (section_start + 3 > offset + 188) {
      continue;
    }
    section_length = ((ts[section_start + 1] & 0x0f) << 8) | (ts[section_start + 2] & 0xff);
    section_end = section_start + 3 + section_length;
    if (section_end > offset + 188) {
      section_end = offset + 188;
    }
    section = ts + section_start;
    if (pid == 0) {
      int index;
      for (index = 8; index + 4 <= section_end - section_start - 4; index += 4) {
        int program = ((section[index] & 0xff) << 8) | (section[index + 1] & 0xff);
        if (program != 0) {
          pmt_pid = ((section[index + 2] & 0x1f) << 8) | (section[index + 3] & 0xff);
          break;
        }
      }
    } else if (pid == pmt_pid) {
      int program_info_length = ((section[10] & 0x0f) << 8) | (section[11] & 0xff);
      int index = 12 + program_info_length;
      while (index + 5 <= section_end - section_start - 4) {
        int stream_type = section[index] & 0xff;
        int elementary_pid = ((section[index + 1] & 0x1f) << 8) | (section[index + 2] & 0xff);
        int info_length = ((section[index + 3] & 0x0f) << 8) | (section[index + 4] & 0xff);
        if (stream_type == 0x1b) {
          return elementary_pid;
        }
        index += 5 + info_length;
      }
    }
  }
  return -1;
}

static void cmg_normalize_video_continuity(uint8_t* ts, int ts_length, int video_pid) {
  int last_counter = -1;
  int offset;
  for (offset = 0; offset + 188 <= ts_length; offset += 188) {
    int has_payload;
    int original_counter;
    int next_counter;
    if (ts[offset] != 0x47) {
      continue;
    }
    if ((((ts[offset + 1] & 0x1f) << 8) | (ts[offset + 2] & 0xff)) != video_pid) {
      continue;
    }
    has_payload = ((ts[offset + 3] >> 4) & 1) != 0;
    original_counter = ts[offset + 3] & 0x0f;
    if (last_counter < 0) {
      next_counter = original_counter;
    } else if (has_payload) {
      next_counter = (last_counter + 1) & 0x0f;
    } else {
      next_counter = last_counter;
    }
    if (original_counter != next_counter) {
      ts[offset + 3] = (uint8_t) ((ts[offset + 3] & 0xf0) | next_counter);
    }
    last_counter = next_counter;
  }
}

static void cmg_fill_live_functions(cmg_decode_function* functions) {
  functions[0] = Z_eaZ_iiiii;
  functions[1] = Z_faZ_iiiii;
  functions[2] = Z_gaZ_iiiii;
  functions[3] = Z_haZ_iiiii;
  functions[4] = Z_iaZ_iiiii;
  functions[5] = Z_jaZ_iiiii;
  functions[6] = Z_kaZ_iiiii;
  functions[7] = Z_laZ_iiiii;
  functions[8] = Z_maZ_iiiii;
}

/* NAL 解码核心（live 版：data 尾附页面 host，tag/data 参数序）。
 * 返回 >=0 输出长度；-1 拒绝；-2 变长无法原地写。state_only=1 时输出不回拷，
 * 仅驱动 wasm 状态机（SPS 用）。 */
static int cmg_decode_nal_locked(uint8_t* host, int offset, int length, int state_only) {
  cmg_decode_function live_functions[9];
  u32 host_length = (u32) strlen(CMG_PAGE_HOST);
  size_t allocation_size = (size_t) length + CMG_NAL_MEMORY_EXTEND;
  uint8_t* data_pointer;
  size_t tag_length;
  uint8_t* tag_pointer;
  u32 update_tag;
  u32 output_length = 0;
  int trap;
  int index;
  cmg_fill_live_functions(live_functions);
  if (g_cmg_nal_data_address == 0 || g_cmg_nal_data_capacity < (u32) allocation_size) {
    if (g_cmg_nal_data_address != 0) {
      (*Z_BaZ_vi)(g_cmg_nal_data_address);
    }
    g_cmg_nal_data_address = (*Z_CaZ_ii)((u32) allocation_size);
    g_cmg_nal_data_capacity = (u32) allocation_size;
  }
  tag_length = strlen(g_player_tag);
  if (g_decode_tag_address == 0 || g_decode_tag_capacity < (u32) tag_length) {
    if (g_decode_tag_address != 0) {
      (*Z_BaZ_vi)(g_decode_tag_address);
    }
    g_decode_tag_address = (*Z_CaZ_ii)((u32) tag_length);
    g_decode_tag_capacity = (u32) tag_length;
  }
  data_pointer = cmg_pointer(g_cmg_nal_data_address, allocation_size);
  tag_pointer = cmg_pointer(g_decode_tag_address, tag_length);
  if (data_pointer == NULL || tag_pointer == NULL) {
    return -1;
  }
  memcpy(data_pointer, host + offset, (size_t) length);
  memcpy(data_pointer + length, CMG_PAGE_HOST, host_length);
  memcpy(tag_pointer, g_player_tag, tag_length);
  trap = wasm_rt_impl_try();
  if (trap != 0) {
    LOGE("CMG host decode trapped=%d len=%d state=%d memSize=%u dataAddr=%u dataCap=%u allocSize=%u tagAddr=%u top=%u",
        trap, length, state_only, g_memory.size, g_cmg_nal_data_address,
        g_cmg_nal_data_capacity, (u32) allocation_size, g_decode_tag_address,
        cmg_dynamic_top());
    return -1;
  }
  update_tag = g_update_tag;
  if (update_tag == 0) {
    update_tag = cmg_update_player_with_fresh_tag();
    g_update_tag = update_tag;
  }
  if (update_tag == 0) {
    return -1;
  }
  for (index = 0; index < 8; index++) {
    if (cmg_should_run_decode_step(update_tag, index)) {
      live_functions[7 - index](g_decode_tag_address, g_cmg_nal_data_address,
          (u32) length, host_length);
    }
  }
  output_length = live_functions[8](g_decode_tag_address, g_cmg_nal_data_address,
      (u32) length, host_length);
  if (state_only) {
    return (int) output_length;
  }
  if (output_length > (u32) length) {
    return -2;
  }
  memcpy(host + offset, data_pointer, (size_t) output_length);
  return (int) output_length;
}

/* 每个受控 NAL 前的会话推进：服务器校准时钟 + UpdatePlayer（native-light 实时算）。
 * 必须自带 wasm_rt_impl_try：一旦本段先前某次 trap 消费了 g_jmp_buf，未保护的
 * wasm 调用再 trap 会 longjmp 到已返回的栈帧（实测切台瞬间 SIGSEGV 的根因）。 */
static u32 cmg_advance_session_locked(void) {
  u32 update_tag;
  int trap;
  cmg_sync_runtime_clock_locked();
  trap = wasm_rt_impl_try();
  if (trap != 0) {
    LOGW("CMG UpdatePlayer trapped=%d (session advance)", trap);
    return 0;
  }
  update_tag = cmg_update_player_with_fresh_tag();
  g_update_tag = update_tag;
  return update_tag;
}

static void cmg_sanitize_sps_marker(uint8_t* data, int nal_start, int nal_end) {
  int before;
  int after;
  if (nal_end - nal_start < 3) {
    return;
  }
  before = data[nal_start + 2] & 0xff;
  after = before & 0xfc;
  if (before == after) {
    return;
  }
  data[nal_start + 2] = (uint8_t) after;
}


static void cmg_decrypt_pes_nals_locked(uint8_t* ts, cmg_pes_buffer* pes,
    uint8_t* rebuilt, int* rebuilt_used) {
  uint8_t* data = pes->data;
  int data_length = cmg_pes_expected_payload(pes);
  int write_offset = 0;
  int offset;
  if (data_length < 0 || data_length > (int) pes->size) {
    data_length = (int) pes->size;
  }
  for (offset = 0; offset < data_length - 4; offset++) {
    int prefix = cmg_start_code_length(data, data_length, offset);
    int nal_start;
    int nal_end;
    int nal_type;
    int replacement_length = -1;
    int is_vcl;
    int is_sps;
    if (prefix == 0) {
      continue;
    }
    nal_start = offset + prefix;
    if (nal_start >= data_length) {
      continue;
    }
    nal_end = data_length;
    {
      int next;
      for (next = nal_start + 1; next < data_length - 4; next++) {
        if (cmg_start_code_length(data, data_length, next) > 0) {
          nal_end = next;
          break;
        }
      }
    }
    nal_type = data[nal_start] & 0x1f;
    is_vcl = (nal_type == CMG_H264_NAL_NON_IDR || nal_type == CMG_H264_NAL_IDR);
    is_sps = (nal_type == CMG_H264_NAL_SPS);
    if (is_sps && g_cmg_vcl_since_restart >= CMG_LIVE_MAX_VCL_PER_RUNTIME) {
      cmg_restart_runtime_locked("VCL budget");
    }
    if (cmg_advance_session_locked() == 0) {
      /* UpdatePlayer trap：wasm 状态已脏，本段剩余 NAL 不再推进（下段整体重启恢复）。 */
      LOGW("CMG abort segment after UpdatePlayer trap at offset=%d", offset);
      break;
    }
    if (is_sps) {
      /* 状态 NAL：探测加密位（bits1-2），解码仅驱动状态机，输出丢弃。 */
      if (!g_cmg_live_video_decode_enabled && nal_end - nal_start > 2) {
        int bits = data[nal_start + 2] & 3;
        g_cmg_live_video_decode_enabled = (bits == 1 || bits == 2);
      }
      if (cmg_decode_nal_locked(data, nal_start, nal_end - nal_start, 1) < 0) {
        LOGW("CMG state NAL rejected len=%d", nal_end - nal_start);
      }
      cmg_sanitize_sps_marker(data, nal_start, nal_end);
    } else if (is_vcl && g_cmg_live_video_decode_enabled) {
      int decoded = cmg_decode_nal_locked(data, nal_start, nal_end - nal_start, 0);
      if (decoded == -2) {
        LOGW("CMG NAL kept original because output grew len=%d", nal_end - nal_start);
      } else if (decoded < 0) {
        LOGW("CMG NAL kept original after native reject len=%d", nal_end - nal_start);
        /* decode trap 后 wasm 堆内状态已脏，继续推进会放大破坏——中止本段。 */
        break;
      } else {
        replacement_length = decoded;
      }
    }
    if (is_vcl) {
      g_cmg_vcl_since_restart++;
    }
    if (replacement_length >= 0 && replacement_length < nal_end - nal_start) {
      *rebuilt_used = 1;
    }
    if (*rebuilt_used) {
      memcpy(rebuilt + write_offset, data + write_offset,
          (size_t) (nal_start - write_offset));
      if (replacement_length < 0) {
        memcpy(rebuilt + nal_start, data + nal_start, (size_t) (nal_end - nal_start));
      } else {
        memcpy(rebuilt + nal_start, data + nal_start, (size_t) replacement_length);
      }
      write_offset = nal_end;
    }
    offset = nal_end - 1;
  }
  if (*rebuilt_used) {
    cmg_pes_copy_payload_to_ts(pes, ts, rebuilt, write_offset);
  } else {
    cmg_pes_copy_payload_to_ts(pes, ts, data, data_length);
  }
}

static void cmg_decrypt_live_segment_locked(uint8_t* ts, int ts_length) {
  int video_pid = cmg_find_video_pid(ts, ts_length);
  cmg_pes_buffer pes;
  uint8_t* rebuilt;
  int rebuilt_used = 0;
  int offset;
  int pes_active = 0;
  int remaining_pes_payload = -1;
  if (video_pid < 0) {
    return;
  }
  memset(&pes, 0, sizeof(pes));
  rebuilt = (uint8_t*) malloc((size_t) ts_length);
  if (rebuilt == NULL) {
    LOGE("CMG segment rebuild buffer alloc failed bytes=%d", ts_length);
    return;
  }
  for (offset = 0; offset + 188 <= ts_length; offset += 188) {
    int pid;
    int payload;
    if (ts[offset] != 0x47) {
      continue;
    }
    pid = ((ts[offset + 1] & 0x1f) << 8) | (ts[offset + 2] & 0xff);
    if (pid != video_pid) {
      continue;
    }
    payload = cmg_payload_offset(ts, offset);
    if (payload < 0) {
      continue;
    }
    if ((ts[offset + 1] & 0x40) != 0) {
      if (pes_active && pes.size > 0) {
        cmg_decrypt_pes_nals_locked(ts, &pes, rebuilt, &rebuilt_used);
      }
      cmg_pes_reset(&pes);
      pes_active = 0;
      remaining_pes_payload = -1;
      if (payload + 9 < offset + 188
          && ts[payload] == 0 && ts[payload + 1] == 0 && ts[payload + 2] == 1) {
        int pes_header_length = 9 + (ts[payload + 8] & 0xff);
        int pes_packet_length = ((ts[payload + 4] & 0xff) << 8) | (ts[payload + 5] & 0xff);
        if (pes_packet_length > 0) {
          remaining_pes_payload = pes_packet_length - (pes_header_length - 6);
          if (remaining_pes_payload < 0) {
            remaining_pes_payload = 0;
          }
        }
        pes.header_offset = payload;
        pes.header_length = pes_header_length;
        pes.packet_length = pes_packet_length;
        pes_active = 1;
        payload += pes_header_length;
      }
    }
    if (pes_active && payload < offset + 188) {
      int payload_length = offset + 188 - payload;
      if (remaining_pes_payload >= 0) {
        if (payload_length > remaining_pes_payload) {
          payload_length = remaining_pes_payload;
        }
        remaining_pes_payload -= payload_length;
      }
      if (payload_length > 0) {
        if (!cmg_pes_reserve(&pes, pes.size + (size_t) payload_length, pes.slot_count + 1)) {
          pes_active = 0;
          continue;
        }
        pes.slots[pes.slot_count].packet_offset = offset;
        pes.slots[pes.slot_count].transport_offset = payload;
        pes.slot_count++;
        memcpy(pes.data + pes.size, ts + payload, (size_t) payload_length);
        pes.size += (size_t) payload_length;
      }
    }
  }
  if (pes_active && pes.size > 0) {
    cmg_decrypt_pes_nals_locked(ts, &pes, rebuilt, &rebuilt_used);
  }
  cmg_pes_dispose(&pes);
  free(rebuilt);
  cmg_normalize_video_continuity(ts, ts_length, video_pid);
}

/*
 * 配置频道会话：playerTag（native-light 自造毫秒时间戳）、服务器时钟偏移、
 * 播放页 href。已初始化的 runtime 立即作废（tag 变更后旧状态不可复用）。
 */
JNIEXPORT void JNICALL
Java_com_mytvb_feature_cctv_cmg_CmgNativeBridge_configureSession(
    JNIEnv* env, jclass clazz, jstring player_tag, jlong clock_offset_ms,
    jstring location_href) {
  const char* tag_chars = NULL;
  const char* href_chars = NULL;
  (void) clazz;
  pthread_mutex_lock(&g_lock);
  if (player_tag != NULL) {
    tag_chars = (*env)->GetStringUTFChars(env, player_tag, NULL);
    if (tag_chars != NULL) {
      snprintf(g_forced_player_tag, sizeof(g_forced_player_tag), "%s", tag_chars);
    }
  }
  if (location_href != NULL) {
    href_chars = (*env)->GetStringUTFChars(env, location_href, NULL);
    if (href_chars != NULL) {
      snprintf(g_forced_location_href, sizeof(g_forced_location_href), "%s", href_chars);
    }
  }
  g_cmg_clock_offset_ms = (int64_t) clock_offset_ms;
  g_cmg_session_configured = 1;
  if (g_initialized) {
    cmg_reset_runtime_locked();
    /* 堆已释放，地址直接作废（见 resetSession 同款注释）。 */
    g_cmg_nal_data_address = 0;
    g_cmg_nal_data_capacity = 0;
  }
  g_cmg_vcl_since_restart = 0;
  g_cmg_live_video_decode_enabled = 0;
  LOGI("CMG session configured tag=%s clockOffsetMs=%lld href=%s",
      g_forced_player_tag, (long long) g_cmg_clock_offset_ms,
      g_forced_location_href[0] == '\0' ? CMG_LOCATION_HREF : g_forced_location_href);
  pthread_mutex_unlock(&g_lock);
  if (tag_chars != NULL) {
    (*env)->ReleaseStringUTFChars(env, player_tag, tag_chars);
  }
  if (href_chars != NULL) {
    (*env)->ReleaseStringUTFChars(env, location_href, href_chars);
  }
}

/* 切台/退出：整体复位（含 forced 配置）。 */
JNIEXPORT void JNICALL
Java_com_mytvb_feature_cctv_cmg_CmgNativeBridge_resetSession(JNIEnv* env, jclass clazz) {
  (void) env;
  (void) clazz;
  pthread_mutex_lock(&g_lock);
  if (g_initialized) {
    cmg_reset_runtime_locked();
  }
  memset(g_forced_player_tag, 0, sizeof(g_forced_player_tag));
  memset(g_forced_location_href, 0, sizeof(g_forced_location_href));
  g_forced_clock_ms = 0u;
  g_cmg_session_configured = 0;
  g_cmg_clock_offset_ms = 0;
  g_cmg_vcl_since_restart = 0;
  g_cmg_live_video_decode_enabled = 0;
  /* wasm 堆已随 reset 释放，这里的地址绝不能再喂给 Z_BaZ_vi（在 memSize=0 的堆上
   * 跑 wasm free 会 OOB trap，longjmp 到上个段已返回的 g_jmp_buf 死栈帧=切台瞬间
   * SIGSEGV 的根因）——直接作废置 0。 */
  g_cmg_nal_data_address = 0;
  g_cmg_nal_data_capacity = 0;
  LOGI("CMG session reset");
  pthread_mutex_unlock(&g_lock);
}

/* 段级入口：每段先整体重启 runtime，再走有状态 NAL 机。原地改写；
 * 返回 JNI_FALSE 仅当会话未配置。无视频 PID 的段透传。 */
JNIEXPORT jboolean JNICALL
Java_com_mytvb_feature_cctv_cmg_CmgNativeBridge_decryptLiveSegment(
    JNIEnv* env, jclass clazz, jbyteArray transport_stream) {
  jsize length;
  jbyte* bytes;
  jboolean result = JNI_TRUE;
  (void) clazz;
  if (transport_stream == NULL) {
    return JNI_FALSE;
  }
  length = (*env)->GetArrayLength(env, transport_stream);
  if (length <= 0) {
    return JNI_TRUE;
  }
  bytes = (*env)->GetByteArrayElements(env, transport_stream, NULL);
  if (bytes == NULL) {
    return JNI_FALSE;
  }
  pthread_mutex_lock(&g_lock);
  if (!g_cmg_session_configured) {
    result = JNI_FALSE;
  } else {
    cmg_restart_runtime_locked("TS segment");
    cmg_decrypt_live_segment_locked((uint8_t*) bytes, (int) length);
  }
  pthread_mutex_unlock(&g_lock);
  (*env)->ReleaseByteArrayElements(env, transport_stream, bytes,
      result == JNI_FALSE ? JNI_ABORT : 0);
  return result;
}
