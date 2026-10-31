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
