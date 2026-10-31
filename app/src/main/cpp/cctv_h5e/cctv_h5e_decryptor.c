#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/time.h>

#include "generated/cctv_h5e_memory_init.h"
#include "generated/cctv_h5e_wasm.h"
#include "wasm-rt/wasm-rt-impl.h"

#define LOG_TAG "cctv_h5e"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define TS_PACKET_SIZE 188
#define MEMORY_INITIAL_PAGES 256
#define MEMORY_MAX_PAGES 1536
#define TABLE_ELEMENTS 544
#define DYNAMIC_TOP_PTR 40512u
#define TEMP_DOUBLE_PTR 40528u
#define EMT_STACK_TOP 5283424u
#define EMBIND_STORAGE 6332000u
#define DYNAMIC_TOP_AFTER_SHELL_ALLOCATIONS 7053264u
#define MEMORY_EXTEND 2048u
/* The wasm VOD output function uses the allocation tail as transform scratch.
 * Valid CCTV slices can require close to 2 MiB even when the NAL itself is only
 * a few KiB. A 2 KiB tail causes deterministic wasm bounds traps after startup. */
#define NAL_MEMORY_EXTEND (2u * 1024u * 1024u)
/* This must match the video element id used by the current CCTV live player.
 * It is part of the H5E key state, not merely a diagnostic label. */
#define PLAYER_TAG "h5player_player"
#define PAGE_HOST "https://tv.cctv.com"
#define LOCATION_HREF "blob:https://tv.cctv.com/5bca710b-9f02-41f0-a9f1-102bbc65192a"
#define H5PLAYER_CONFIG "{\"h5player\":{\"ver\":20190904,\"md5\":\"c7ed5a71dbe4dee1a2ba171f660ee98d\",\"BTime\":\"2019-09-04 20:25:10\"}}"

typedef struct w2c_env {
  u32 table_base;
  u32 temp_double_ptr;
  u32 dynamic_top_ptr;
  u32 emt_stack_top;
  u32 embind_storage;
  u32 temp_ret0;
  wasm_rt_memory_t memory;
  wasm_rt_funcref_table_t table;
  w2c_cctv__h5e* module;
} w2c_env;

typedef struct packet_slot {
  size_t packet_offset;
  size_t payload_offset;
  size_t payload_length;
} packet_slot;

typedef struct pes_stream {
  int pid;
  uint8_t* bytes;
  size_t length;
  size_t capacity;
  packet_slot* slots;
  size_t slot_count;
  size_t slot_capacity;
  size_t header_packet_offset;
  size_t header_payload_offset;
  size_t header_length;
  unsigned int packet_length;
} pes_stream;

typedef u32 (*decrypt_function)(w2c_cctv__h5e*, u32, u32, u32, u32);

typedef enum emval_type {
  EMVAL_EMPTY,
  EMVAL_LOCATION,
  EMVAL_STRING,
  EMVAL_DESTRUCTORS
} emval_type;

typedef struct emval_handle {
  emval_type type;
  int refcount;
  char* text;
  u32 destructor_address;
} emval_handle;

static __thread w2c_env g_env;
static __thread w2c_cctv__h5e g_module;
static __thread int g_runtime_initialized;
static __thread int g_ready;
static __thread int g_session_started;
static __thread emval_handle g_emval_handles[64];
static __thread u32 g_emval_free_list[64];
static __thread size_t g_emval_free_count;
static __thread u32 g_emval_handle_count;
static __thread const char* g_wasm_stage = "idle";
static __thread struct timeval g_worker_now;
static __thread int g_worker_clock_frozen;
static volatile u32 g_cancel_generation;
static volatile int g_sps_compatibility_mode = 1;
static volatile int g_sps_compatibility_log_count;
static __thread u32 g_decrypt_generation;
static __thread int g_decrypt_cancelled;
static __thread size_t g_current_nal_length;
static __thread int g_current_nal_type;
static __thread char g_current_update_tag[16];
static __thread int g_should_decrypt;
static __thread u32 g_pending_fetch;
static __thread u32 g_player_argument;

static int decrypt_was_cancelled(void) {
  u32 generation = __sync_add_and_fetch(&g_cancel_generation, 0);
  if (generation != g_decrypt_generation) {
    g_decrypt_cancelled = 1;
  }
  return g_decrypt_cancelled;
}

static void get_worker_time(struct timeval* now) {
  if (g_worker_clock_frozen) {
    *now = g_worker_now;
  } else {
    gettimeofday(now, NULL);
  }
}

static uint8_t* env_pointer(w2c_env* env, u32 address, size_t size) {
  if ((uint64_t) address + size > env->memory.size) {
    return NULL;
  }
  return env->memory.data + address;
}

static void reset_emval_state(void) {
  u32 handle;
  for (handle = 5; handle < g_emval_handle_count; handle++) {
    free(g_emval_handles[handle].text);
  }
  memset(g_emval_handles, 0, sizeof(g_emval_handles));
  memset(g_emval_free_list, 0, sizeof(g_emval_free_list));
  g_emval_handles[1].type = EMVAL_LOCATION;
  g_emval_free_count = 0;
  g_emval_handle_count = 5;
}

static u32 allocate_emval_handle(void) {
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

static u32 register_emval_string(const char* text, size_t length) {
  u32 handle = allocate_emval_handle();
  if (handle <= 4) {
    return handle;
  }
  g_emval_handles[handle].text = (char*) malloc(length + 1);
  if (g_emval_handles[handle].text == NULL) {
    return 1;
  }
  memcpy(g_emval_handles[handle].text, text, length);
  g_emval_handles[handle].text[length] = '\0';
  g_emval_handles[handle].type = EMVAL_STRING;
  return handle;
}

static u32 register_emval_destructors(void) {
  u32 handle = allocate_emval_handle();
  if (handle > 4) {
    g_emval_handles[handle].type = EMVAL_DESTRUCTORS;
  }
  return handle;
}

static void release_emval_handle(u32 handle) {
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

u32* w2c_env_0x5F_table_base(struct w2c_env* env) { return &env->table_base; }
u32* w2c_env_a(struct w2c_env* env) { return &env->temp_double_ptr; }
u32* w2c_env_b(struct w2c_env* env) { return &env->dynamic_top_ptr; }
u32* w2c_env_c(struct w2c_env* env) { return &env->emt_stack_top; }
u32* w2c_env_d(struct w2c_env* env) { return &env->embind_storage; }
wasm_rt_memory_t* w2c_env_memory(struct w2c_env* env) { return &env->memory; }
wasm_rt_funcref_table_t* w2c_env_table(struct w2c_env* env) { return &env->table; }

void w2c_env_e(struct w2c_env* env, u32 value) { env->temp_ret0 = value; }

f64 w2c_env_f(struct w2c_env* env) {
  struct timeval now;
  (void) env;
  get_worker_time(&now);
  return (f64) now.tv_sec * 1000.0 + (f64) now.tv_usec / 1000.0;
}

u32 w2c_env_g(struct w2c_env* env) { return env->temp_ret0; }
void w2c_env_h(struct w2c_env* env) { (void) env; LOGE("pure virtual call"); }
void w2c_env_i(struct w2c_env* env, u32 a, u32 b) { (void) env; (void) a; (void) b; }
void w2c_env_j(struct w2c_env* env, u32 a) { (void) env; (void) a; }
u32 w2c_env_k(struct w2c_env* env, u32 a, u32 b, u32 c, u32 d, u32 e) {
  (void) env; (void) a; (void) b; (void) c; (void) d; (void) e; return 0;
}
u32 w2c_env_l(struct w2c_env* env, u32 a, u32 b, u32 c, u32 d) {
  (void) env; (void) a; (void) b; (void) c; (void) d; return 0;
}
u32 w2c_env_m(struct w2c_env* env, u32 a, u32 b, u32 c) {
  (void) env; (void) a; (void) b; (void) c; return 0;
}
u32 w2c_env_n(struct w2c_env* env, u32 size) { (void) env; (void) size; return 0; }
void w2c_env_o(struct w2c_env* env) { (void) env; LOGE("llvm trap"); }

u32 w2c_env_p(struct w2c_env* env, u32 timeval_address, u32 timezone_address) {
  struct timeval now;
  uint8_t* target;
  (void) timezone_address;
  get_worker_time(&now);
  target = env_pointer(env, timeval_address, 8);
  if (target == NULL) {
    return (u32) -1;
  }
  ((u32*) target)[0] = (u32) now.tv_sec;
  ((u32*) target)[1] = (u32) now.tv_usec;
  return 0;
}

/* InitPlayer asynchronously fetches /Library/H5player.json in the browser.
 * Leaving this import as a no-op lets the first key window work by accident,
 * then UpdatePlayer produces the wrong VMP tag after roughly five 4-second
 * segments. Queue the request here and complete it after InitPlayer returns,
 * matching the worker's asynchronous callback without a network dependency. */
void w2c_env_q(struct w2c_env* env, u32 fetch) {
  (void) env;
  g_pending_fetch = fetch;
}

static void invoke_fetch_callback(w2c_env* env, u32 table_index, u32 fetch) {
  wasm_rt_funcref_t* callback;
  if (table_index == 0 || table_index >= env->table.size) {
    return;
  }
  callback = &env->table.data[table_index];
  if (callback->func != NULL) {
    ((void (*)(void*, u32)) callback->func)(callback->module_instance, fetch);
  }
}

static int complete_pending_fetch(void) {
  const char* response = H5PLAYER_CONFIG;
  size_t response_length = strlen(response);
  u32 fetch = g_pending_fetch;
  u32 response_address;
  uint8_t* target;
  uint32_t* word;
  uint16_t* half;
  u32 success_callback;
  u32 ready_callback;
  if (fetch == 0) {
    return 1;
  }
  g_pending_fetch = 0;
  response_address = w2c_cctv__h5e_Fa(&g_module, (u32) response_length);
  target = env_pointer(&g_env, response_address, response_length);
  if (response_address == 0 || target == NULL) {
    return 0;
  }
  memcpy(target, response, response_length);
  word = (uint32_t*) env_pointer(&g_env, fetch + 12, 28);
  half = (uint16_t*) env_pointer(&g_env, fetch + 40, 4);
  if (word == NULL || half == NULL
      || env_pointer(&g_env, fetch + 44, 64) == NULL
      || env_pointer(&g_env, fetch + 148, 16) == NULL) {
    return 0;
  }
  word[0] = response_address;
  word[1] = (uint32_t) response_length;
  word[2] = 0;
  word[3] = 0;
  word[4] = 0;
  word[5] = (uint32_t) response_length;
  word[6] = 0;
  half[0] = 4;
  half[1] = 200;
  memset(env_pointer(&g_env, fetch + 44, 64), 0, 64);
  memcpy(env_pointer(&g_env, fetch + 44, 64), "OK", 2);
  success_callback = *(uint32_t*) env_pointer(&g_env, fetch + 148, 4);
  ready_callback = *(uint32_t*) env_pointer(&g_env, fetch + 160, 4);
  invoke_fetch_callback(&g_env, success_callback, fetch);
  invoke_fetch_callback(&g_env, ready_callback, fetch);
  return 1;
}

u32 w2c_env_r(struct w2c_env* env, u32 requested_size) {
  uint64_t current_pages = env->memory.pages;
  uint64_t requested_pages = ((uint64_t) requested_size + WASM_DEFAULT_PAGE_SIZE - 1)
      / WASM_DEFAULT_PAGE_SIZE;
  if (requested_pages <= current_pages) {
    return 1;
  }
  return wasm_rt_grow_memory(&env->memory, requested_pages - current_pages) == UINT64_MAX ? 0 : 1;
}

u32 w2c_env_s(struct w2c_env* env, u32 destination, u32 source, u32 size) {
  uint8_t* destination_pointer = env_pointer(env, destination, size);
  uint8_t* source_pointer = env_pointer(env, source, size);
  if (destination_pointer == NULL || source_pointer == NULL) {
    return destination;
  }
  memmove(destination_pointer, source_pointer, size);
  return destination;
}

void w2c_env_t(struct w2c_env* env, u32 flags, u32 message) {
  const char* text = (const char*) env_pointer(env, message, 1);
  (void) flags;
  if (text != NULL) {
    LOGI("%s", text);
  }
}

u32 w2c_env_u(struct w2c_env* env) { (void) env; return 1; }
u32 w2c_env_v(struct w2c_env* env) { return (u32) env->memory.size; }
u32 w2c_env_w(struct w2c_env* env, u32 a, u32 b, u32 c) {
  (void) env; (void) a; (void) b; (void) c; return 0;
}
u32 w2c_env_x(struct w2c_env* env, u32 a, u32 b, f64 c, u32 d, u32 e, u32 f, u32 g) {
  (void) env; (void) a; (void) b; (void) c; (void) d; (void) e; (void) f; (void) g; return 0;
}

static u32 write_asm_constant_result(w2c_env* env, const char* text) {
  size_t length = strlen(text) + 1;
  g_wasm_stage = "asm-constant-malloc";
  u32 address = w2c_cctv__h5e_Fa(env->module, (u32) length);
  g_wasm_stage = "idle";
  uint8_t* destination = env_pointer(env, address, length);
  if (destination == NULL) {
    return 0;
  }
  memcpy(destination, text, length);
  return address;
}

u32 w2c_env_y(struct w2c_env* env, u32 operation, u32 argument) {
  const char* name = (const char*) env_pointer(env, argument, 1);
  (void) operation;
  if (name == NULL) {
    return 0;
  }
  if (strcmp(name, "self.location.href") == 0) {
    return write_asm_constant_result(env, LOCATION_HREF);
  }
  if (strcmp(name, "self.location.host") == 0) {
    return write_asm_constant_result(env, "");
  }
  if (strcmp(name, "self.location.protocol") == 0) {
    return write_asm_constant_result(env, "blob:");
  }
  return write_asm_constant_result(env, "");
}

void w2c_env_z(struct w2c_env* env) { (void) env; LOGE("abort"); }

void w2c_env_0x24(struct w2c_env* env, u32 code) { (void) env; LOGE("abort: %u", code); }
u32 w2c_env_A(struct w2c_env* env, u32 a, u32 b) {
  uint32_t* length_pointer;
  uint8_t* text;
  u32 result;
  (void) a;
  length_pointer = (uint32_t*) env_pointer(env, b, sizeof(uint32_t));
  if (length_pointer == NULL) {
    return 1;
  }
  text = env_pointer(env, b + 4, *length_pointer);
  if (text == NULL) {
    return 1;
  }
  result = register_emval_string((const char*) text, *length_pointer);
  w2c_cctv__h5e_Ba(env->module, b);
  return result;
}
void w2c_env_B(struct w2c_env* env, u32 a) {
  if (a > 4 && a < g_emval_handle_count && g_emval_handles[a].type == EMVAL_DESTRUCTORS) {
    if (g_emval_handles[a].destructor_address != 0) {
      w2c_cctv__h5e_Ba(env->module, g_emval_handles[a].destructor_address);
    }
    release_emval_handle(a);
  }
}
u32 w2c_env_C(struct w2c_env* env, u32 a) {
  const char* text = (const char*) env_pointer(env, a, 1);
  return text == NULL ? 1 : register_emval_string(text, strlen(text));
}
u32 w2c_env_D(struct w2c_env* env, u32 a, u32 b) {
  const char* property = b < g_emval_handle_count ? g_emval_handles[b].text : NULL;
  (void) env;
  if (a == 1 && property != NULL && strcmp(property, "href") == 0) {
    return register_emval_string(LOCATION_HREF, strlen(LOCATION_HREF));
  }
  return 1;
}
u32 w2c_env_E(struct w2c_env* env, u32 a) {
  (void) env;
  (void) a;
  return 1;
}
void w2c_env_F(struct w2c_env* env, u32 a) { (void) env; release_emval_handle(a); }
f64 w2c_env_G(struct w2c_env* env, u32 a, u32 b, u32 c) {
  const char* text;
  uint8_t* output;
  size_t length;
  u32 destructors;
  u32 result;
  uint32_t* destructor_pointer;
  (void) b;
  text = a == 1 ? LOCATION_HREF
      : a < g_emval_handle_count && g_emval_handles[a].text != NULL
          ? g_emval_handles[a].text : "";
  length = strlen(text);
  destructors = register_emval_destructors();
  destructor_pointer = (uint32_t*) env_pointer(env, c, sizeof(uint32_t));
  if (destructor_pointer == NULL) {
    return 0;
  }
  *destructor_pointer = destructors;
  result = w2c_cctv__h5e_Fa(env->module, (u32) length + 5);
  destructor_pointer = (uint32_t*) env_pointer(env, result, sizeof(uint32_t));
  if (destructor_pointer == NULL) {
    return 0;
  }
  *destructor_pointer = (uint32_t) length;
  output = env_pointer(env, result + 4, length + 1);
  if (output == NULL) {
    return 0;
  }
  memcpy(output, text, length + 1);
  if (destructors > 4) {
    g_emval_handles[destructors].destructor_address = result;
  }
  return (f64) result;
}
u32 w2c_env_H(struct w2c_env* env, u32 a, u32 b) { (void) env; (void) a; (void) b; return 0; }
void w2c_env_I(struct w2c_env* env, u32 a) { (void) env; (void) a; }
void w2c_env_J(struct w2c_env* env, u32 a, u32 b) { (void) env; (void) a; (void) b; }
void w2c_env_K(struct w2c_env* env, u32 a, u32 b, u32 c) { (void) env; (void) a; (void) b; (void) c; }
void w2c_env_L(struct w2c_env* env, u32 a, u32 b) { (void) env; (void) a; (void) b; }
void w2c_env_M(struct w2c_env* env, u32 a, u32 b, u32 c) { (void) env; (void) a; (void) b; (void) c; }
void w2c_env_N(struct w2c_env* env, u32 a, u32 b, u32 c, u32 d, u32 e) {
  (void) env; (void) a; (void) b; (void) c; (void) d; (void) e;
}
void w2c_env_O(struct w2c_env* env, u32 a, u32 b, u32 c) { (void) env; (void) a; (void) b; (void) c; }
void w2c_env_P(struct w2c_env* env, u32 a, u32 b) { (void) env; (void) a; (void) b; }
void w2c_env_Q(struct w2c_env* env, u32 a, u32 b, u32 c, u32 d, u32 e) {
  (void) env; (void) a; (void) b; (void) c; (void) d; (void) e;
}
u32 w2c_env_R(struct w2c_env* env, u32 a, u32 b) { (void) env; (void) a; (void) b; return (u32) -1; }
u32 w2c_env_S(struct w2c_env* env, u32 a, u32 b) { (void) env; (void) a; (void) b; return (u32) -1; }
u32 w2c_env_T(struct w2c_env* env, u32 a, u32 b) { (void) env; (void) a; (void) b; return (u32) -1; }
void w2c_env_U(struct w2c_env* env, u32 value) { (void) env; (void) value; }
u32 w2c_env_V(struct w2c_env* env) { (void) env; return 0; }
u32 w2c_env_W(struct w2c_env* env, u32 a) { (void) env; (void) a; return 0; }
void w2c_env_X(struct w2c_env* env, u32 a, u32 b, u32 c, u32 d, u32 e, u32 f, u32 g) {
  (void) env; (void) a; (void) b; (void) c; (void) d; (void) e; (void) f; (void) g;
}
void w2c_env_Y(struct w2c_env* env, u32 a, u32 b, u32 c, u32 d, u32 e, u32 f) {
  (void) env; (void) a; (void) b; (void) c; (void) d; (void) e; (void) f;
}
void w2c_env_Z(struct w2c_env* env, u32 a, u32 b, u32 c, u32 d, u32 e) {
  (void) env; (void) a; (void) b; (void) c; (void) d; (void) e;
}
void w2c_env_0x5F(struct w2c_env* env, u32 a, u32 b, u32 c) {
  (void) env; (void) a; (void) b; (void) c;
}

static int reset_module_state(void) {
  uint32_t* dynamic_top;
  size_t relocation_index;
  memset(&g_module, 0, sizeof(g_module));
  g_pending_fetch = 0;
  g_player_argument = 0;
  reset_emval_state();
  memset(g_env.memory.data, 0, g_env.memory.size);
  if (EMBIND_STORAGE + sizeof(CCTV_H5E_MEMORY_INIT) > g_env.memory.size) {
    return 0;
  }
  memcpy(g_env.memory.data + EMBIND_STORAGE,
      CCTV_H5E_MEMORY_INIT, sizeof(CCTV_H5E_MEMORY_INIT));
  for (relocation_index = 0;
      relocation_index < sizeof(CCTV_H5E_MEMORY_RELOCATIONS) / sizeof(CCTV_H5E_MEMORY_RELOCATIONS[0]);
      relocation_index++) {
    uint32_t* target = (uint32_t*) env_pointer(&g_env,
        EMBIND_STORAGE + CCTV_H5E_MEMORY_RELOCATIONS[relocation_index], sizeof(uint32_t));
    if (target == NULL) {
      return 0;
    }
    *target += EMBIND_STORAGE;
  }
  dynamic_top = (uint32_t*) env_pointer(&g_env, DYNAMIC_TOP_PTR, sizeof(uint32_t));
  if (dynamic_top == NULL) {
    return 0;
  }
  *dynamic_top = DYNAMIC_TOP_AFTER_SHELL_ALLOCATIONS;
  g_env.module = &g_module;
  g_wasm_stage = "instantiate";
  wasm2c_cctv__h5e_instantiate(&g_module, &g_env);
  g_wasm_stage = "global-ctors";
  w2c_cctv__h5e_La(&g_module);
  g_wasm_stage = "idle";
  return 1;
}

static int ensure_module_ready(void) {
  if (!g_runtime_initialized) {
    memset(&g_env, 0, sizeof(g_env));
    wasm_rt_init();
    wasm_rt_allocate_memory(&g_env.memory, MEMORY_INITIAL_PAGES, MEMORY_MAX_PAGES, 0, WASM_DEFAULT_PAGE_SIZE);
    wasm_rt_allocate_funcref_table(&g_env.table, TABLE_ELEMENTS, TABLE_ELEMENTS);
    g_env.temp_double_ptr = TEMP_DOUBLE_PTR;
    g_env.dynamic_top_ptr = DYNAMIC_TOP_PTR;
    g_env.emt_stack_top = EMT_STACK_TOP;
    g_env.embind_storage = EMBIND_STORAGE;
    g_runtime_initialized = 1;
  }
  /* Keep the runtime across the short VMP key window. The live decrypt path
   * performs its controlled five-segment refresh before calling this helper. */
  if (!g_ready) {
    g_ready = reset_module_state();
  }
  return g_ready;
}

static u32 allocate_player_argument(void) {
  size_t length = strlen(PLAYER_TAG);
  size_t capacity = length + MEMORY_EXTEND;
  uint8_t* target;
  u32 address;
  g_wasm_stage = "player-malloc";
  address = w2c_cctv__h5e_Da(&g_module, (u32) capacity);
  g_wasm_stage = "idle";
  target = env_pointer(&g_env, address, capacity);
  if (target == NULL) {
    return 0;
  }
  memset(target, 0, capacity);
  memcpy(target, PLAYER_TAG, length);
  return address;
}

static int begin_session(void) {
  if (g_session_started) {
    return 1;
  }
  g_player_argument = allocate_player_argument();
  if (g_player_argument == 0) {
    return 0;
  }
  g_wasm_stage = "init-player";
  w2c_cctv__h5e_aa(&g_module, g_player_argument);
  g_wasm_stage = "init-player-fetch";
  if (!complete_pending_fetch()) {
    g_wasm_stage = "idle";
    return 0;
  }
  g_session_started = 1;
  /* Current CCTV cdrmld streams may omit the legacy type-25 enable NAL while
   * still encrypting every VCL NAL. Start enabled; an explicit type-25 control
   * NAL, when present on older feeds, still overrides this state below. */
  g_should_decrypt = 1;
  return 1;
}

static void end_session(void) {
  if (g_session_started) {
    if (g_player_argument == 0) {
      g_session_started = 0;
      return;
    }
    g_wasm_stage = "uninit-player";
    w2c_cctv__h5e_ba(&g_module, g_player_argument);
    g_wasm_stage = "idle";
    g_wasm_stage = "player-free";
    w2c_cctv__h5e_Ca(&g_module, g_player_argument);
    g_wasm_stage = "idle";
    g_player_argument = 0;
    g_session_started = 0;
  }
}

static int decrypt_nal(const uint8_t* nal, size_t nal_length,
    uint8_t** result, size_t* result_length) {
  static decrypt_function functions[9] = {
    w2c_cctv__h5e_ka, w2c_cctv__h5e_ja, w2c_cctv__h5e_ia,
    w2c_cctv__h5e_ha, w2c_cctv__h5e_ga, w2c_cctv__h5e_fa,
    w2c_cctv__h5e_ea, w2c_cctv__h5e_da, w2c_cctv__h5e_la
  };
  static const char host[] = PAGE_HOST;
  size_t host_length = strlen(host);
  char tag[16];
  u32 output_length;
  int index;
  g_current_nal_length = nal_length;
  g_current_nal_type = nal_length > 0 ? nal[0] & 0x1f : -1;
  if (g_player_argument == 0) {
    return 0;
  }
  /* The official live wrapper keeps one player argument alive for Init,
   * Update and every jsdecLive call. UpdatePlayer mutates state associated
   * with that exact address, so substituting a temporary or a VOD media tag
   * eventually produces an invalid transform key. */
  g_wasm_stage = "update-player";
  snprintf(tag, sizeof(tag), "%08x", w2c_cctv__h5e_ca(&g_module, g_player_argument));
  snprintf(g_current_update_tag, sizeof(g_current_update_tag), "%s", tag);
  g_wasm_stage = "idle";
  g_wasm_stage = "nal-malloc";
  u32 data_address = w2c_cctv__h5e_Da(&g_module,
      (u32) nal_length + (u32) host_length + NAL_MEMORY_EXTEND);
  g_wasm_stage = "idle";
  uint8_t* data = env_pointer(&g_env, data_address,
      nal_length + host_length + NAL_MEMORY_EXTEND);
  if (data == NULL) {
    return 0;
  }
  memcpy(data, nal, nal_length);
  memcpy(data + nal_length, host, host_length);
  for (index = 0; index < 8; index++) {
    if (strchr("0123456", tag[index]) != NULL) {
      g_wasm_stage = "vod-step";
      functions[index](&g_module, g_player_argument, data_address,
          (u32) nal_length, (u32) host_length);
      g_wasm_stage = "idle";
    }
  }
  g_wasm_stage = "vod-output";
  output_length = functions[8](&g_module, g_player_argument, data_address,
      (u32) nal_length, (u32) host_length);
  g_wasm_stage = "idle";
  if (output_length > nal_length + host_length + NAL_MEMORY_EXTEND) {
    g_wasm_stage = "nal-free";
    w2c_cctv__h5e_Ca(&g_module, data_address);
    g_wasm_stage = "idle";
    return 0;
  }
  *result = (uint8_t*) malloc(output_length);
  if (*result == NULL) {
    g_wasm_stage = "nal-free";
    w2c_cctv__h5e_Ca(&g_module, data_address);
    g_wasm_stage = "idle";
    return 0;
  }
  memcpy(*result, data, output_length);
  *result_length = output_length;
  g_wasm_stage = "nal-free";
  w2c_cctv__h5e_Ca(&g_module, data_address);
  g_wasm_stage = "idle";
  return 1;
}

static int append_bytes(pes_stream* pes, const uint8_t* bytes, size_t length) {
  size_t needed = pes->length + length;
  if (needed > pes->capacity) {
    size_t capacity = pes->capacity == 0 ? 64 * 1024 : pes->capacity;
    uint8_t* replacement;
    while (capacity < needed) {
      capacity *= 2;
    }
    replacement = (uint8_t*) realloc(pes->bytes, capacity);
    if (replacement == NULL) {
      return 0;
    }
    pes->bytes = replacement;
    pes->capacity = capacity;
  }
  memcpy(pes->bytes + pes->length, bytes, length);
  pes->length += length;
  return 1;
}

static int append_slot(pes_stream* pes, size_t packet_offset, size_t payload_offset, size_t payload_length) {
  if (pes->slot_count == pes->slot_capacity) {
    size_t capacity = pes->slot_capacity == 0 ? 16 : pes->slot_capacity * 2;
    packet_slot* replacement = (packet_slot*) realloc(pes->slots, capacity * sizeof(packet_slot));
    if (replacement == NULL) {
      return 0;
    }
    pes->slots = replacement;
    pes->slot_capacity = capacity;
  }
  pes->slots[pes->slot_count].packet_offset = packet_offset;
  pes->slots[pes->slot_count].payload_offset = payload_offset;
  pes->slots[pes->slot_count].payload_length = payload_length;
  pes->slot_count++;
  return 1;
}

static size_t find_start_code(const uint8_t* bytes, size_t length, size_t from, size_t* prefix_length) {
  size_t index;
  for (index = from; index + 3 < length; index++) {
    if (bytes[index] == 0 && bytes[index + 1] == 0 && bytes[index + 2] == 1) {
      *prefix_length = 3;
      return index;
    }
    if (index + 4 < length && bytes[index] == 0 && bytes[index + 1] == 0
        && bytes[index + 2] == 0 && bytes[index + 3] == 1) {
      *prefix_length = 4;
      return index;
    }
  }
  return length;
}

static int write_pes_payload(uint8_t* output, pes_stream* pes, const uint8_t* bytes, size_t length) {
  size_t consumed = 0;
  size_t slot_index;
  size_t total_capacity = 0;
  for (slot_index = 0; slot_index < pes->slot_count; slot_index++) {
    total_capacity += pes->slots[slot_index].payload_length;
  }
  if (length > total_capacity) {
    LOGW("Decrypted PES grew beyond TS capacity: %u > %u", (unsigned int) length, (unsigned int) total_capacity);
    return 0;
  }
  for (slot_index = 0; slot_index < pes->slot_count; slot_index++) {
    packet_slot* slot = &pes->slots[slot_index];
    uint8_t* packet = output + slot->packet_offset;
    size_t available = length - consumed;
    if (available >= slot->payload_length) {
      memcpy(packet + slot->payload_offset, bytes + consumed, slot->payload_length);
      consumed += slot->payload_length;
      continue;
    }
    if (available > 0) {
      size_t adaptation_length = 183 - available;
      packet[3] = (packet[3] & 0xcf) | 0x30;
      packet[4] = (uint8_t) adaptation_length;
      if (adaptation_length > 0) {
        packet[5] = 0;
        if (adaptation_length > 1) {
          memset(packet + 6, 0xff, adaptation_length - 1);
        }
      }
      memcpy(packet + TS_PACKET_SIZE - available, bytes + consumed, available);
      consumed += available;
    } else {
      /* Keep the payload-present bit even when the rewritten PES has no bytes
       * left for this packet. Its continuity counter was advanced as a payload
       * packet upstream; changing it to adaptation-only makes the next real
       * packet look discontinuous and causes FFmpeg/MediaCodec to discard a
       * complete GOP. A 183-byte adaptation field still leaves zero payload. */
      packet[3] = (packet[3] & 0xcf) | 0x30;
      packet[4] = 183;
      packet[5] = 0;
      memset(packet + 6, 0xff, 182);
    }
  }
  if (consumed != length) {
    return 0;
  }
  if (pes->packet_length > 0 && pes->header_length >= 6
      && pes->packet_length >= pes->header_length - 6) {
    size_t updated_length = length + pes->header_length - 6;
    if (updated_length <= 0xffff) {
      uint8_t* header = output + pes->header_packet_offset + pes->header_payload_offset;
      header[4] = (uint8_t) ((updated_length >> 8) & 0xff);
      header[5] = (uint8_t) (updated_length & 0xff);
    }
  }
  return 1;
}

static int decrypt_pes(uint8_t* output, pes_stream* pes) {
  uint8_t* decrypted = NULL;
  size_t decrypted_length = 0;
  size_t decrypted_capacity = 0;
  size_t position = 0;
  size_t prefix_length;
  int changed = 0;
  if (pes->packet_length > 0 && pes->header_length >= 6
      && pes->packet_length >= pes->header_length - 6) {
    size_t expected = pes->packet_length - (pes->header_length - 6);
    if (pes->length < expected) {
      /* Never decrypt the final, truncated slice of a segment. A partially
       * rewritten frame produces the characteristic top-only mosaic. */
      return 0;
    }
    if (pes->length > expected) {
      pes->length = expected;
    }
  }
  while ((position = find_start_code(pes->bytes, pes->length, position, &prefix_length)) < pes->length) {
    size_t nal_start = position + prefix_length;
    size_t next_prefix;
    size_t next = find_start_code(pes->bytes, pes->length, nal_start, &next_prefix);
    size_t nal_length = next - nal_start;
    uint8_t* replacement = NULL;
    size_t replacement_length = 0;
    int type;
    size_t output_nal_start;
    size_t required;
    if (decrypt_was_cancelled()) {
      free(decrypted);
      return 0;
    }
    if (nal_length == 0) {
      position = nal_start;
      continue;
    }
    type = pes->bytes[nal_start] & 0x1f;
    output_nal_start = decrypted_length + prefix_length;
    required = decrypted_length + prefix_length + nal_length + MEMORY_EXTEND;
    if (required > decrypted_capacity) {
      size_t capacity = decrypted_capacity == 0 ? pes->length + MEMORY_EXTEND : decrypted_capacity * 2;
      uint8_t* resized;
      while (capacity < required) {
        capacity *= 2;
      }
      resized = (uint8_t*) realloc(decrypted, capacity);
      if (resized == NULL) {
        free(decrypted);
        return 0;
      }
      decrypted = resized;
      decrypted_capacity = capacity;
    }
    memcpy(decrypted + decrypted_length, pes->bytes + position, prefix_length);
    decrypted_length += prefix_length;
    if (decrypt_nal(pes->bytes + nal_start, nal_length,
            &replacement, &replacement_length)) {
      memcpy(decrypted + decrypted_length, replacement, replacement_length);
      decrypted_length += replacement_length;
      changed = 1;
      free(replacement);
    } else {
      memcpy(decrypted + decrypted_length, pes->bytes + nal_start, nal_length);
      decrypted_length += nal_length;
    }
    /* CCTV and CMG use reserved_zero_2bits in the SPS constraint byte as a
     * private encryption marker. FFmpeg ignores it, while old MStar OMX
     * implementations can reject the codec configuration. Consume the marker
     * before exposing the decrypted elementary stream to MediaCodec. */
    if (g_sps_compatibility_mode && type == 7
        && decrypted_length >= output_nal_start + 3) {
      uint8_t before = decrypted[output_nal_start + 2];
      decrypted[output_nal_start + 2] = before & 0xfc;
      if (before != decrypted[output_nal_start + 2]) {
        changed = 1;
        if (__sync_fetch_and_add(&g_sps_compatibility_log_count, 1) < 8) {
          LOGI("Sanitized H264 SPS private marker %02x -> %02x",
              before, decrypted[output_nal_start + 2]);
        }
      }
    }
    position = next;
  }
  if (position == 0) {
    free(decrypted);
    return 0;
  }
  if (position < pes->length) {
    append_bytes(pes, pes->bytes + position, pes->length - position);
  }
  if (changed && write_pes_payload(output, pes, decrypted, decrypted_length)) {
    free(decrypted);
    return 1;
  }
  free(decrypted);
  return 0;
}

static void clear_pes(pes_stream* pes) {
  free(pes->bytes);
  free(pes->slots);
  memset(pes, 0, sizeof(*pes));
  pes->pid = -1;
}

static int flush_pes(uint8_t* output, pes_stream* pes) {
  int changed = 0;
  if (pes->length > 0 && pes->slot_count > 0) {
    changed = decrypt_pes(output, pes);
  }
  clear_pes(pes);
  return changed;
}

static int decrypt_transport_stream(uint8_t* output, const uint8_t* input, size_t length) {
  pes_stream pes;
  int changed = 0;
  size_t packet_offset;
  memset(&pes, 0, sizeof(pes));
  pes.pid = -1;
  if (output != input) {
    memcpy(output, input, length);
  }
  gettimeofday(&g_worker_now, NULL);
  g_worker_clock_frozen = 1;
  /* Current cdrmld live fragments are independently decryptable and omit the
   * old type-25 cross-fragment control NAL. The browser wrapper refreshes its
   * outer H5E state between fragments; this standalone wasm port does not, and
   * its UpdatePlayer state starts producing corrupt slices after a few files.
   * Reset only the native module here. This keeps ijkplayer, MediaCodec, the
   * proxy connection and the video Surface alive across the boundary. */
  if (g_session_started) {
    g_session_started = 0;
    g_should_decrypt = 0;
    g_ready = reset_module_state();
  }
  if (!ensure_module_ready() || !begin_session()) {
    LOGE("Unable to initialize wasm decryptor");
    g_worker_clock_frozen = 0;
    return 0;
  }
  for (packet_offset = 0; packet_offset + TS_PACKET_SIZE <= length; packet_offset += TS_PACKET_SIZE) {
    const uint8_t* packet = input + packet_offset;
    size_t payload_offset = 4;
    size_t payload_length;
    int pid;
    int adaptation_control;
    int payload_unit_start;
    if (decrypt_was_cancelled()) {
      break;
    }
    if (packet[0] != 0x47) {
      continue;
    }
    pid = ((packet[1] & 0x1f) << 8) | packet[2];
    payload_unit_start = (packet[1] & 0x40) != 0;
    adaptation_control = (packet[3] >> 4) & 3;
    if ((adaptation_control & 1) == 0) {
      continue;
    }
    if ((adaptation_control & 2) != 0) {
      payload_offset += 1 + packet[4];
    }
    if (payload_offset >= TS_PACKET_SIZE) {
      continue;
    }
    payload_length = TS_PACKET_SIZE - payload_offset;
    if (payload_unit_start && payload_length >= 9
        && packet[payload_offset] == 0 && packet[payload_offset + 1] == 0
        && packet[payload_offset + 2] == 1) {
      int stream_id = packet[payload_offset + 3];
      if (stream_id < 0xe0 || stream_id > 0xef) {
        continue;
      }
      if (pes.pid >= 0) {
        changed |= flush_pes(output, &pes);
      }
      pes.pid = pid;
      pes.header_packet_offset = packet_offset;
      pes.header_payload_offset = payload_offset;
      pes.header_length = 9 + packet[payload_offset + 8];
      pes.packet_length = ((unsigned int) packet[payload_offset + 4] << 8)
          | packet[payload_offset + 5];
      payload_offset += pes.header_length;
      if (payload_offset >= TS_PACKET_SIZE) {
        continue;
      }
      payload_length = TS_PACKET_SIZE - payload_offset;
    }
    if (pes.pid == pid) {
      if (!append_slot(&pes, packet_offset, payload_offset, payload_length)
          || !append_bytes(&pes, packet + payload_offset, payload_length)) {
        LOGE("Unable to buffer PES payload");
        break;
      }
    }
  }
  if (pes.pid >= 0 && !decrypt_was_cancelled()) {
    changed |= flush_pes(output, &pes);
  } else {
    clear_pes(&pes);
  }
  /* Preserve state inside the current key window. releaseThreadContext() still
   * tears the runtime down immediately when the channel changes. */
  g_worker_clock_frozen = 0;
  return changed;
}

JNIEXPORT jbyteArray JNICALL
Java_com_mytvb_feature_cctv_h5e_CctvH5eDecryptor_decryptTransportStream(
    JNIEnv* env, jclass clazz, jbyteArray transport_stream) {
  jsize length;
  jbyte* input;
  int trapped = 0;
  int decrypted = 0;
  (void) clazz;
  if (transport_stream == NULL) {
    return NULL;
  }
  g_decrypt_generation = __sync_add_and_fetch(&g_cancel_generation, 0);
  g_decrypt_cancelled = 0;
  length = (*env)->GetArrayLength(env, transport_stream);
  input = (*env)->GetByteArrayElements(env, transport_stream, NULL);
  if (input == NULL) {
    return NULL;
  }
  wasm_rt_trap_t trap = (wasm_rt_trap_t) wasm_rt_try(g_wasm_rt_jmp_buf);
  if (trap == WASM_RT_TRAP_NONE) {
    /* The TS rewriter only updates packet slots after their source bytes have been
     * consumed, so input and output may safely share the JNI buffer. This removes a
     * full segment allocation plus two whole-buffer copies on every fragment. */
    decrypted = decrypt_transport_stream(
        (uint8_t*) input, (const uint8_t*) input, (size_t) length);
  } else {
    LOGE("wasm trap during %s: %s nalType=%d nalLen=%u updateTag=%s",
        g_wasm_stage, wasm_rt_strerror(trap), g_current_nal_type,
        (unsigned int) g_current_nal_length, g_current_update_tag);
    trapped = 1;
    g_ready = 0;
    g_session_started = 0;
    g_worker_clock_frozen = 0;
  }
  if (trapped) {
    (*env)->ReleaseByteArrayElements(env, transport_stream, input, JNI_ABORT);
    LOGW("Rejecting encrypted transport stream after decrypt trap");
    return NULL;
  }
  if (g_decrypt_cancelled) {
    (*env)->ReleaseByteArrayElements(env, transport_stream, input, JNI_ABORT);
    LOGI("CCTV decrypt cancelled for channel switch");
    return NULL;
  }
  if (!decrypted) {
    (*env)->ReleaseByteArrayElements(env, transport_stream, input, JNI_ABORT);
    LOGW("Rejecting transport stream because no encrypted video NAL was decoded");
    return NULL;
  }
  (*env)->ReleaseByteArrayElements(env, transport_stream, input, 0);
  return transport_stream;
}

JNIEXPORT void JNICALL
Java_com_mytvb_feature_cctv_h5e_CctvH5eDecryptor_setSpsCompatibilityMode(
    JNIEnv* env, jclass clazz, jboolean enabled) {
  (void) env;
  (void) clazz;
  g_sps_compatibility_mode = enabled ? 1 : 0;
  LOGI("H264 SPS compatibility mode=%d", g_sps_compatibility_mode);
}

JNIEXPORT void JNICALL
Java_com_mytvb_feature_cctv_h5e_CctvH5eDecryptor_cancelPendingDecrypts(JNIEnv* env, jclass clazz) {
  u32 generation;
  (void) env;
  (void) clazz;
  generation = __sync_add_and_fetch(&g_cancel_generation, 1);
  LOGI("CCTV decrypt cancellation generation=%u", generation);
}

JNIEXPORT void JNICALL
Java_com_mytvb_feature_cctv_h5e_CctvH5eDecryptor_releaseThreadContext(JNIEnv* env, jclass clazz) {
  (void) env;
  (void) clazz;
  if (!g_runtime_initialized) {
    return;
  }
  reset_emval_state();
  wasm2c_cctv__h5e_free(&g_module);
  wasm_rt_free_funcref_table(&g_env.table);
  wasm_rt_free_memory(&g_env.memory);
  wasm_rt_free_thread();
  memset(&g_env, 0, sizeof(g_env));
  memset(&g_module, 0, sizeof(g_module));
  g_runtime_initialized = 0;
  g_ready = 0;
  g_session_started = 0;
  g_should_decrypt = 0;
  g_worker_clock_frozen = 0;
  g_wasm_stage = "idle";
}
