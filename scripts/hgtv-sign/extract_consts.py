# 从 jadx 产物抽取 medusa 相关常量表 → consts.json，并与我方 Kotlin 实现对比
import os
import re, io, json, sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))  # 仓库根
# 本地材料目录（抓包样本、金标准等含真实设备标识，不入库）；可用环境变量 HGTV_SIGN_DATA 覆盖
DATA = os.environ.get('HGTV_SIGN_DATA') or os.path.join(ROOT, '.zcode', 'hgtv-sign', 'tools')

SRC = os.path.join(ROOT, '.zcode', 'hgtv-13812', 'jadx-out', 'sources', 'H0', 'AbstractC0172j1.java')
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'consts.json')  # 入库产物
SYM = {
    'CrashStatKey.LOG_LEGACY_TMP_FILE': 200, 'CrashStatKey.LOG_UPLOAD_ENCRYPT_COUNT': 201,
    'ExitType.UNEXP_REASON_ANR': 103, 'ExitType.UNEXP_REASON_EXIT': 104,
    'ExitType.UNEXP_REASON_KILL_PROCESS': 105, 'ExitType.UNEXP_REASON_LOW_MEMORY': 107,
    'ExitType.UNEXP_REASON_RESTART': 106,
    'UMErrorCode.E_UM_BE_CREATE_FAILED': 111, 'UMErrorCode.E_UM_BE_DEFLATE_FAILED': 112,
    'UMErrorCode.E_UM_BE_EMPTY_URL_PATH': 121, 'UMErrorCode.E_UM_BE_FILE_OVERSIZE': 114,
    'UMErrorCode.E_UM_BE_JSON_FAILED': 110, 'UMErrorCode.E_UM_BE_NOT_MAINPROCESS': 120,
    'UMErrorCode.E_UM_BE_RAW_OVERSIZE': 113,
}

s = io.open(SRC, encoding='utf-8').read()

def grab(name):
    m = re.search(r'public static final [\[\]a-zA-Z]+ ' + name + r' = (\{.*?\});', s, re.S)
    assert m, name
    return m.group(1)

def flat(body):
    # 递归解析 {a, b, {c, d}, ...}
    body = body.strip()
    assert body[0] == '{' and body[-1] == '}', body[:40]
    inner = body[1:-1]
    # 先把嵌套 {} 展开
    inner = inner.replace('new int[]', '')
    out, depth, cur = [], 0, ''
    parts = []
    for ch in inner:
        if ch == '{':
            depth += 1
            cur += ch
        elif ch == '}':
            depth -= 1
            cur += ch
        elif ch == ',' and depth == 0:
            parts.append(cur); cur = ''
        else:
            cur += ch
    if cur.strip():
        parts.append(cur)
    res = []
    for p in parts:
        p = p.strip()
        if not p:
            continue
        if p.startswith('{'):
            res.append(flat(p))
        else:
            p = p.rstrip('L')
            if p in SYM:
                res.append(SYM[p])
            else:
                res.append(int(p))
    return res

consts = {n: flat(grab(n)) for n in
          ['f2274b', 'f2275c', 'f2276d', 'f2287o', 'f2288p', 'f2289q', 'f2290r', 'f2291s', 'f2292t', 'f2286n']}
json.dump(consts, io.open(OUT, 'w', encoding='utf-8'))
for k, v in consts.items():
    def cnt(x):
        return sum(cnt(i) for i in x) if isinstance(x, list) else 1
    print(k, 'items', cnt(v), 'shape', [len(v)] + ([len(v[0])] if isinstance(v[0], list) else []))
# sbox 必须是 4×256 的置换
for w, box in enumerate(consts['f2287o']):
    assert len(box) == 256 and sorted(box) == list(range(256)), ('sbox word', w)
print('sbox ok: 4 perms of 0..255')
