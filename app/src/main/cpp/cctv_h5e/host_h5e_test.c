/* 取证用命令行：直接解密一个本地 TS 分片文件（VOD 模式）。
 * 用法: host_h5e_test <in.ts> <out.ts>
 * 不打包进 APK，仅本地构建后推到设备 /data/local/tmp 运行。 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

int cctv_h5e_host_decrypt_vod(const unsigned char* input, size_t length, unsigned char* output);

int main(int argc, char** argv) {
  if (argc < 3) {
    fprintf(stderr, "usage: %s <in.ts> <out.ts>\n", argv[0]);
    return 2;
  }
  FILE* f = fopen(argv[1], "rb");
  if (!f) { perror("open in"); return 2; }
  fseek(f, 0, SEEK_END);
  long size = ftell(f);
  fseek(f, 0, SEEK_SET);
  unsigned char* buf = (unsigned char*) malloc(size);
  unsigned char* out = (unsigned char*) malloc(size);
  if (!buf || !out) { fprintf(stderr, "oom\n"); return 2; }
  if (fread(buf, 1, size, f) != (size_t) size) { fprintf(stderr, "read fail\n"); return 2; }
  fclose(f);

  /* 调试支持：argv[3] 存在时先等 25 秒供 lldb attach，再循环解密 5 轮。 */
  int rounds = 1;
  if (argc > 3) {
    fprintf(stderr, "waiting for debugger attach (25s)...\n");
    sleep(25);
    rounds = 5;
  }
  int r = 0;
  int i;
  for (i = 0; i < rounds; i++) {
    r = cctv_h5e_host_decrypt_vod(buf, (size_t) size, out);
    fprintf(stderr, "round %d decrypt result=%d size=%ld\n", i, r, size);
  }

  FILE* o = fopen(argv[2], "wb");
  if (!o) { perror("open out"); return 2; }
  fwrite(out, 1, size, o);
  fclose(o);
  return r == 1 ? 0 : 1;
}
