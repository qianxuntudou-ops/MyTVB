cmake_minimum_required(VERSION 3.22)
project(cctv_h5e)

# CCTV H5e 直播流解密（issue#106 阶段二：摆脱 WebView 直拉流）。
# - generated/cctv_h5e_wasm.c|h、cctv_h5e_memory_init.h：CCTV 官方播放器内嵌 wasm 的
#   wasm2c 转译产物，源自 MIT 项目 xiaoxi-ij478/cctv-h5e-decrypt（commit f25831e）。
# - cctv_h5e_decryptor.c：TS 流级解密封装（TS demux + NALU 解密调度 + wasm 运行时桥），
#   借鉴 TvWasm/cjs（NativeWasmTv 站点插件仓库，仅供学习研究用途，非商用）。
# - wasm-rt/：wasm2c 官方运行时。
# 编译参数对齐 cjs Android.mk：-O3 -DNDEBUG -DWASM_RT_USE_MMAP=0。
add_library(cctv_h5e SHARED
    cctv_h5e_decryptor.c
    generated/cctv_h5e_wasm.c
    wasm-rt/wasm-rt-impl.c
    wasm-rt/wasm-rt-mem-impl.c)
target_include_directories(cctv_h5e PRIVATE generated wasm-rt)
target_compile_options(cctv_h5e PRIVATE -O3 -DNDEBUG -std=c99 -DWASM_RT_USE_MMAP=0 -fvisibility=hidden)
target_link_libraries(cctv_h5e log m)

# 央视频（yangshipin）CMG 直播解密 + 签名 wasm（卫视/央视频超清组原生直拉，issue#106 阶段二延伸）。
# - cmg_decryptor.c + generated_cmg/ + cmg-rt/：CMG wasm（hls.cmg.js）的 wasm2c 产物与
#   封装，源自 TvWasm/cjs sites/yangshipin.cn（仅供学习研究用途，非商用，经用户确认引入）；
# #include 尾部拼接的段级状态机由本仓库自研（cmg_segment_state_machine.inc）。
# - ysp_keygen.c + generated_ysp/ + ysp-rt/：get_live_info 签名 wasm（tokenRnd/signature）。
# 两套 wasm-rt 同符号共存于一个 so：cmg 侧用 11 个 -D 前缀重命名（对齐 cjs Android.mk），
# ysp 侧保持原名。ARM32 必须 Clang（NDK 25 全 Clang 满足）。
set(CMG_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../cmg)
set(CMG_RUNTIME_SOURCES
    ${CMG_DIR}/cmg_decryptor.c
    ${CMG_DIR}/generated_cmg/cmg_wasm.c
    ${CMG_DIR}/cmg-rt/wasm-rt-impl.c)
set_source_files_properties(${CMG_RUNTIME_SOURCES} PROPERTIES COMPILE_OPTIONS
    "-O3;-DNDEBUG;-std=c99;-fvisibility=hidden;-DWASM_RT_MEMCHECK_SIGNAL_HANDLER=0;-Dinit=cmg_module_init;-Dwasm_rt_allocate_memory=cmg_wasm_rt_allocate_memory;-Dwasm_rt_allocate_table=cmg_wasm_rt_allocate_table;-Dwasm_rt_call_stack_depth=cmg_wasm_rt_call_stack_depth;-Dwasm_rt_grow_memory=cmg_wasm_rt_grow_memory;-Dwasm_rt_register_func_type=cmg_wasm_rt_register_func_type;-Dwasm_rt_trap=cmg_wasm_rt_trap;-Dg_jmp_buf=cmg_wasm_g_jmp_buf;-Dg_saved_call_stack_depth=cmg_wasm_g_saved_call_stack_depth;-Dg_func_types=cmg_wasm_g_func_types;-Dg_func_type_count=cmg_wasm_g_func_type_count")
add_library(cmg_yangshipin SHARED
    ${CMG_RUNTIME_SOURCES}
    ${CMG_DIR}/ysp_keygen.c
    ${CMG_DIR}/generated_ysp/ysp_keygen_wasm.c
    ${CMG_DIR}/ysp-rt/wasm-rt-impl.c)
target_include_directories(cmg_yangshipin PRIVATE
    ${CMG_DIR}/generated_cmg ${CMG_DIR}/cmg-rt
    ${CMG_DIR}/generated_ysp ${CMG_DIR}/ysp-rt)
set_source_files_properties(
    ${CMG_DIR}/ysp_keygen.c
    ${CMG_DIR}/generated_ysp/ysp_keygen_wasm.c
    ${CMG_DIR}/ysp-rt/wasm-rt-impl.c
    PROPERTIES COMPILE_OPTIONS "-O3;-DNDEBUG;-std=c99;-DWASM_RT_USE_MMAP=0;-fvisibility=hidden")
target_link_libraries(cmg_yangshipin log m)
