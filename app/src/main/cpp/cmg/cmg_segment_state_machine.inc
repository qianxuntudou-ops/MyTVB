
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
    LOGE("CMG host decode trapped=%d len=%d state=%d", trap, length, state_only);
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

/* 每个受控 NAL 前的会话推进：服务器校准时钟 + UpdatePlayer（native-light 实时算）。 */
static u32 cmg_advance_session_locked(void) {
  u32 update_tag;
  cmg_sync_runtime_clock_locked();
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
    cmg_advance_session_locked();
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
  if (g_cmg_nal_data_address != 0) {
    (*Z_BaZ_vi)(g_cmg_nal_data_address);
    g_cmg_nal_data_address = 0;
    g_cmg_nal_data_capacity = 0;
  }
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
