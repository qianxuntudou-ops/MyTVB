# 直接从 dex 二进制读取 fill-array-data payload，绕开 jadx 的数组还原。
# 用法：python dex_arrays.py <dex> <类描述符> ；依赖同目录 i1_dexdump.txt 里记录的 code item 偏移。
import os
import io, re, struct, sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))  # 仓库根
# 本地材料目录（抓包样本、金标准等含真实设备标识，不入库）；可用环境变量 HGTV_SIGN_DATA 覆盖
DATA = os.environ.get('HGTV_SIGN_DATA') or os.path.join(ROOT, '.zcode', 'hgtv-sign', 'tools')

DEX = os.path.join(ROOT, '.zcode', 'hgtv-13812', 'dex', 'classes.dex')
# dexdump -d 的输出（约 100MB，不入库；重新生成见 README），可用环境变量 HGTV_SIGN_DUMP 指定
DUMP = os.environ.get('HGTV_SIGN_DUMP') or os.path.join(DATA, 'i1_dexdump.txt')
dex = open(DEX, 'rb').read()
lines = io.open(DUMP, encoding='utf-8', errors='replace').read().split('\n')


def class_seg(desc):
    s = next(i for i, l in enumerate(lines) if ("Class descriptor  : '%s'" % desc) in l)
    e = next(i for i in range(s + 1, len(lines)) if lines[i].startswith('Class #'))
    return lines[s:e]


def read_payload(code_off, units):
    off = code_off + 16 + units * 2
    ident, width, size = struct.unpack_from('<HHI', dex, off)
    assert ident == 0x0300, hex(ident)
    raw = dex[off + 8: off + 8 + width * size]
    fmt = {1: 'b', 2: 'h', 4: 'i', 8: 'q'}[width]
    return list(struct.unpack('<%d%s' % (size, fmt), raw))


def arrays_of(desc):
    seg = class_seg(desc)
    out = []
    # 方法头:  |[115780] H0.K1.<init>:([IIII)V
    cur = None
    for i, l in enumerate(seg):
        m = re.search(r'\|\[([0-9a-f]+)\] (\S+)$', l.strip())
        if m and not l.strip().startswith('|0'):
            cur = (int(m.group(1), 16), m.group(2))
        m2 = re.search(r'\|([0-9a-f]{4}): fill-array-data v\d+, ([0-9a-f]+) // \+([0-9a-f]+)', l)
        if m2:
            units, target = int(m2.group(1), 16), int(m2.group(3), 16)
            out.append((cur, read_payload(cur[0], units + target)))
    return out


if __name__ == '__main__':
    for name, arr in arrays_of(sys.argv[1] if len(sys.argv) > 1 else 'LH0/j1;'):
        if len(arr) in (8, 64, 16) or 200 <= len(arr) <= 1100:
            print(name[1], len(arr), arr[:80] if len(arr) <= 80 else str(arr[:12]) + ' …')
