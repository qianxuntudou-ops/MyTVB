# HGTV 1.3.8.x medusa (V5, selector=12) 离线解密器原型：
# 逆向 AbstractC0210r0.E 的封装层（xmxor / 位映射 / 自定义 AES 载波），
# 把 frida 抓到的 x-medusa 头拆回 protobuf 明文与全部内部状态。
# 用法：python medusa_dec.py [样本文件]   （默认解 sign_pairs.log 里的 MEDUSA 行）
import json, base64, hashlib, struct, io, sys, os

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))  # 仓库根
# 本地材料目录（抓包样本、金标准等含真实设备标识，不入库）；可用环境变量 HGTV_SIGN_DATA 覆盖
DATA = os.environ.get('HGTV_SIGN_DATA') or os.path.join(ROOT, '.zcode', 'hgtv-sign', 'tools')

HERE = os.path.dirname(os.path.abspath(__file__))
C = json.load(io.open(os.path.join(HERE, 'consts.json'), encoding='utf-8'))
SBOX = C['f2287o']; INIT = C['f2288p']; Q = C['f2289q']; R = C['f2290r']
S = C['f2291s']; T = C['f2292t']
INV_SBOX = [[0] * 256 for _ in range(4)]
for w in range(4):
    for i, v in enumerate(SBOX[w]):
        INV_SBOX[w][v] = i
SIGN_KEY = bytes.fromhex('b23c2a470a20d105dc3f8c4438f8272ed46d6c89ffdcdedd5108698d26e9489d')
MAGIC_WORDS = [5, 3394194221, 1124955465, 749647167, 1456237858]


def sm3(b):
    return hashlib.new('sm3', b).digest()


def rotl8(x, n):
    n &= 7
    return ((x << n) | (x >> (8 - n))) & 255 if n else x & 255


def chk(bArr, j2, i2=6, i3=4, i4=12, i5=7):   # r0.C
    j3 = j2 & 0xFFFFFFFF
    for i6, j4 in enumerate(bArr):
        if i6 % 2 == 0:
            j3 = ((j3 >> i3) ^ (j4 ^ (((j3 << i2) & 0xFFFFFFFF) ^ j3))) & 0xFFFFFFFF
        else:
            j3 = ((j3 ^ ((j4 | ((j3 << i4) & 0xFFFFFFFF)) ^ (j3 >> i5))) ^ 0xFFFFFFFF) & 0xFFFFFFFF
    return j3


# ---------- GF(2^8) ----------
def xt(x):
    x <<= 1
    return (x ^ 0x1b) & 255 if x & 256 else x & 255


def gmul(a, b):
    r = 0
    for _ in range(8):
        if b & 1:
            r ^= a
        a = xt(a)
        b >>= 1
    return r


def ginv(a):
    for x in range(256):
        if gmul(a, x) == 1:
            return x
    raise ValueError(a)


MIX = [[2, 3, 1, 1], [1, 2, 3, 1], [1, 1, 2, 3], [3, 1, 1, 2]]   # 标准 AES MixColumns


def mat_inv(m):
    n = len(m)
    a = [row[:] + [1 if i == j else 0 for j in range(n)] for i, row in enumerate(m)]
    for c in range(n):
        piv = next(r for r in range(c, n) if a[r][c])
        a[c], a[piv] = a[piv], a[c]
        iv = ginv(a[c][c])
        a[c] = [gmul(iv, v) for v in a[c]]
        for r in range(n):
            if r != c and a[r][c]:
                f = a[r][c]
                a[r] = [v ^ gmul(f, p) for v, p in zip(a[r], a[c])]
    return [row[n:] for row in a]


MIX_INV = mat_inv(MIX)


def h_perm(word):
    iArr = [(i % 4) + Q[word][i // 4] * 4 for i in range(16)]
    iArr2 = [1, 9, 5, 13, 9, 1, 13, 5, 2, 14, 6, 2, 10, 6, 14, 10]
    perm = iArr[:]
    for i4 in range(0, 16, 2):
        perm[iArr2[i4]] = iArr[iArr2[i4 + 1]]
    for i5 in range(4):
        perm[3 + i5 * 4] = iArr[R[word][i5]]
    assert sorted(perm) == list(range(16)), perm
    return perm


H_PERM = [h_perm(w) for w in range(4)]


def h_fwd(word, b):
    c = b[:]
    p = H_PERM[word]
    for i in range(16):
        b[i] = SBOX[word][c[p[i]]]


def h_inv(word, b):
    out = [0] * 16
    p = H_PERM[word]
    for i in range(16):
        out[p[i]] = INV_SBOX[word][b[i]]
    b[:] = out


def ark(word, b, rk16):   # D
    for c in range(0, 16, 4):
        for j in range(4):
            b[c + j] ^= rk16[Q[word][j] + c]


def perm_q(word, b):
    out = [0] * 16
    for c in range(0, 16, 4):
        for j in range(4):
            out[c + j] = b[c + Q[word][j]]
    b[:] = out


def unperm_q(word, b):
    out = [0] * 16
    for c in range(0, 16, 4):
        for j in range(4):
            out[c + Q[word][j]] = b[c + j]
    b[:] = out


def mix(b):
    for c in range(4):
        a_, c_, d_, b_ = b[c], b[c + 4], b[c + 8], b[c + 12]
        v = [a_, c_, d_, b_]
        for r in range(4):
            b[c + r * 4] = (gmul(MIX[r][0], a_) ^ gmul(MIX[r][1], c_) ^ gmul(MIX[r][2], d_) ^ gmul(MIX[r][3], b_))


def unmix(b):
    for c in range(4):
        a_, c_, d_, b_ = b[c], b[c + 4], b[c + 8], b[c + 12]
        v = [a_, c_, d_, b_]
        for r in range(4):
            b[c + r * 4] = (gmul(MIX_INV[r][0], a_) ^ gmul(MIX_INV[r][1], c_) ^
                            gmul(MIX_INV[r][2], d_) ^ gmul(MIX_INV[r][3], b_))


def expand(word):
    key = hashlib.md5(SIGN_KEY[:16]).digest()
    iv = hashlib.md5(SIGN_KEY[16:]).digest()
    rk = bytearray(48)
    for i in range(16):
        rk[i] = key[i] ^ ((INIT[word] >> ((i % 4) * 8)) & 255)
    for i in range(4, 12):
        at = i * 4
        a, bb, c, d = rk[at - 4], rk[at - 3], rk[at - 2], rk[at - 1]
        if i % 4 == 0:
            t = (((INIT[word] >> ((i * 2) & 24)) & (249 if word == 3 else 255)) & 255) ^ SBOX[word][bb]
            a, bb, c, d = t, SBOX[word][c], SBOX[word][d], SBOX[word][a]
        for j, v in enumerate((a, bb, c, d)):
            rk[at + j] = v ^ rk[(i - 4) * 4 + j]
    return key, iv, rk


RK = [expand(w) for w in range(4)]


def block_fwd(word, inp, prev):
    s = [inp[i] ^ prev[i] for i in range(16)]
    _, _, rk = RK[word]
    ark(word, s, rk[0:16]); h_fwd(word, s); perm_q(word, s); mix(s)
    ark(word, s, rk[16:32]); h_fwd(word, s); ark(word, s, rk[32:48])
    return [s[i] ^ rk[16 + i] for i in range(16)]


def block_inv(word, out, prev):
    s = [out[i] ^ RK[word][2][16 + i] for i in range(16)]
    _, _, rk = RK[word]
    ark(word, s, rk[32:48]); h_inv(word, s); ark(word, s, rk[16:32])
    unmix(s); unperm_q(word, s); h_inv(word, s); ark(word, s, rk[0:16])
    return [s[i] ^ prev[i] for i in range(16)]


# ---------- xmxor ----------
def xmxor_fwd(P, h):
    n = len(P)
    enc = [0] * n
    for i in range(n):
        i4 = i * 4
        i5 = h[i4 % 32]
        i6 = h[(i4 + 1) % 32]
        enc[(n - 1) - i] = rotl8((rotl8((((rotl8(P[i], 4) + i5) & 255) ^ 255) ^ i6, 3) + i6) & 255, 0) ^ 255 ^ i5
    i7 = n - 1; i8 = n - 2
    enc[0] = (enc[0] + ((enc[i7] ^ enc[i8]) ^ 255)) & 255
    enc[1] = (enc[1] + ((enc[0] ^ enc[i7]) ^ 254)) & 255
    for i10 in range(0, n - 3):   # 源码 while (i10 < length - 3)，最后一步 i12 = n-2
        b3 = enc[i10]; i11 = i10 + 1; i12 = i10 + 2
        enc[i12] = (enc[i12] + ((rotl8(enc[i11], 3) ^ b3 ^ i12) ^ 255)) & 255
    enc[i7] ^= enc[i8]
    i13 = sum(enc[1:]) & 255
    enc[0] = ((enc[0] ^ enc[1]) + i13) & 255
    return enc


def xmxor_inv(enc, h):
    enc = enc[:]
    n = len(enc)
    i7 = n - 1; i8 = n - 2
    enc[0] = ((enc[0] - sum(enc[1:])) & 255) ^ enc[1]
    enc[i7] ^= enc[i8]
    for i10 in range(n - 4, -1, -1):
        b3 = enc[i10]; i11 = i10 + 1; i12 = i10 + 2
        enc[i12] = (enc[i12] - ((rotl8(enc[i11], 3) ^ b3 ^ i12) ^ 255)) & 255
    enc[1] = (enc[1] - ((enc[0] ^ enc[i7]) ^ 254)) & 255
    enc[0] = (enc[0] - ((enc[i7] ^ enc[i8]) ^ 255)) & 255
    P = [0] * n
    for i in range(n):
        i4 = i * 4
        i5 = h[i4 % 32]
        i6 = h[(i4 + 1) % 32]
        t = enc[(n - 1) - i] ^ 255
        t ^= i5
        t = (t - i6) & 255
        t = rotl8(t, 5)
        t ^= i6
        t ^= 255
        t = (t - i5) & 255
        P[i] = rotl8(t, 4)
    return P


# ---------- E 的正向与逆向 ----------
def e_fwd(P, sec, rnd, check, flags):
    word = sec & 3
    bM = struct.pack('<I', rnd & 0xFFFFFFFF)
    bN = struct.pack('<Q', check & 0xFFFFFFFFFFFFFFFF)
    h = sm3(SIGN_KEY + bM + SIGN_KEY)
    X = xmxor_fwd(P, h)
    mask = chk(bM[2:4], 0, 7, 3, 11, 5)
    u1 = list(flags) + X
    n = len(u1)
    b6 = [u1[(n - 1) - i] ^ ((mask >> ((3 - (i % 4)) * 8)) & 255) for i in range(n)]
    inner = [chk(SIGN_KEY, 0, 7, 3, 11, 5) & 255] + list(bN) + b6 + list(bM[2:4])
    assert inner[0] == 0xCA and len(inner) >= 248
    plain = bytearray(32)
    for i in range(31):
        for j in range(8):
            sb, sh = S[j]
            plain[i] |= ((inner[i * 8 + sb] >> sh) & 1) << j
    plain[31] = 1
    _, iv, _ = RK[word]
    o0 = block_fwd(word, plain[0:16], list(iv))
    o1 = block_fwd(word, plain[16:32], o0)
    bits = o0 + o1
    outp = bytearray(inner)
    for i in range(31):
        for j in range(8):
            tb, off, sh = T[j]
            pos = i * 8 + off
            outp[pos] = (outp[pos] & ~(1 << sh)) | (((bits[i] >> tb) & 1) << sh)
    res = b''.join(struct.pack('<I', (sec ^ w) & 0xFFFFFFFF) for w in MAGIC_WORDS)
    res += bytes([bM[0], bM[1], 0, 32 - 31]) + bytes(bits[31:]) + bytes(outp)
    return base64.b64encode(res).decode()


def e_dec(b64, sec=None):
    raw = base64.b64decode(b64)
    if sec is None:
        sec = struct.unpack('<I', raw[0:4])[0] ^ 5
    for i, w in enumerate(MAGIC_WORDS):
        assert struct.unpack('<I', raw[i * 4:i * 4 + 4])[0] == (sec ^ w) & 0xFFFFFFFF, ('prefix', i)
    word = sec & 3
    assert raw[22] == 0 and raw[23] == 1, ('len byte', raw[23])
    bits = bytearray(32)
    bits[31] = raw[24]
    outp = bytearray(raw[25:])
    for i in range(31):
        for j in range(8):
            tb, off, sh = T[j]
            bits[i] |= ((outp[i * 8 + off] >> sh) & 1) << tb
    _, iv, _ = RK[word]
    p0 = block_inv(word, bits[0:16], list(iv))
    p1 = block_inv(word, bits[16:32], bits[0:16])
    plain = p0 + p1
    assert plain[31] == 1, ('plain[31]', plain[31])
    inner = bytearray(outp)
    for i in range(31):
        for j in range(8):
            sb, sh = S[j]
            pos = i * 8 + sb
            inner[pos] = (inner[pos] & ~(1 << sh)) | (((plain[i] >> j) & 1) << sh)
    assert inner[0] == 0xCA, ('inner head', hex(inner[0]))
    check = struct.unpack('<Q', inner[1:9])[0]
    r23 = bytes(inner[-2:])
    rnd = raw[20] | (raw[21] << 8) | (r23[0] << 16) | (r23[1] << 24)
    b6 = inner[9:-2]
    mask = chk(r23, 0, 7, 3, 11, 5)
    n = len(b6)
    u1 = bytearray(n)
    for i in range(n):
        u1[(n - 1) - i] = b6[i] ^ ((mask >> ((3 - (i % 4)) * 8)) & 255)
    flags = bytes(u1[:8])
    h = sm3(SIGN_KEY + struct.pack('<I', rnd) + SIGN_KEY)
    P = bytes(xmxor_inv(list(u1[8:]), h))
    return dict(sec=sec, rnd=rnd, check=check, flags=flags, proto=P, raw_len=len(raw))


# ---------- protobuf 打印 ----------
def read_varint(b, i):
    r = 0; s = 0
    while True:
        c = b[i]; i += 1
        r |= (c & 0x7f) << s
        if not c & 0x80:
            return r, i
        s += 7


def parse_pb(b):
    """返回 [(field, wire, value)]；解析失败抛异常（用于判定是否为嵌套消息）。"""
    out = []; i = 0
    while i < len(b):
        key, i = read_varint(b, i)
        f, wt = key >> 3, key & 7
        if f == 0:
            raise ValueError('field 0')
        if wt == 0:
            v, i = read_varint(b, i)
        elif wt == 1:
            v = b[i:i + 8]; i += 8
        elif wt == 2:
            ln, i = read_varint(b, i)
            v = b[i:i + ln]; i += ln
            if len(v) != ln:
                raise ValueError('short')
        elif wt == 5:
            v = b[i:i + 4]; i += 4
        else:
            raise ValueError('wire %d' % wt)
        out.append((f, wt, v))
    return out


def is_text(b):
    try:
        s = b.decode('utf-8')
        return all(32 <= ord(c) < 127 for c in s)
    except Exception:
        return False


def dump_pb(b, indent=0):
    lines = []
    for f, wt, v in parse_pb(b):
        pad = '  ' * indent
        if wt == 0:
            sv = (v >> 1) ^ -(v & 1)
            lines.append('%s%d: varint %d (signed %d, 0x%x)' % (pad, f, v, sv, v))
        elif wt == 5:
            fv = struct.unpack('<f', v)[0]
            lines.append('%s%d: fixed32 0x%08x (float %r)' % (pad, f, struct.unpack('<I', v)[0], fv))
        elif wt == 1:
            lines.append('%s%d: fixed64 0x%016x' % (pad, f, struct.unpack('<Q', v)[0]))
        else:
            if is_text(v) and len(v) > 0:
                lines.append('%s%d: str(%d) %r' % (pad, f, len(v), v.decode()))
            else:
                try:
                    sub = parse_pb(v)
                    lines.append('%s%d: msg(%d)' % (pad, f, len(v)))
                    lines += dump_pb(v, indent + 1)
                except Exception:
                    lines.append('%s%d: bytes(%d) %s' % (pad, f, len(v), v.hex()))
    return lines


def selftest():
    # xmxor / block / E 的正逆自洽
    import random
    rnd = random.Random(1)
    P = bytes(rnd.randrange(256) for _ in range(300))
    for sec in (1795320458, 1795320459, 1795320460, 1795320461):
        r = rnd.randrange(1 << 32); ck = rnd.randrange(1 << 64); fl = bytes(rnd.randrange(256) for _ in range(8))
        b64 = e_fwd(list(P), sec, r, ck, fl)
        d = e_dec(b64, sec)
        assert bytes(d['proto']) == P, ('roundtrip', sec)
        assert d['rnd'] == r and d['check'] == ck and d['flags'] == fl
    print('selftest ok: E roundtrip on 4 words')


if __name__ == '__main__':
    selftest()
    if len(sys.argv) > 1:
        d = e_dec(io.open(sys.argv[1], encoding='utf-8').read().strip())
        print('\n'.join(dump_pb(d['proto'])))
        sys.exit(0)
    path = os.path.join(ROOT, '.zcode', 'hgtv-frida', 'sign_pairs.log')
    n = 0
    for ln in io.open(path, encoding='utf-8'):
        if not ln.startswith('MEDUSA'):
            continue
        p = ln.rstrip('\n').split('|')
        out = p[4]
        sec = int(p[3])
        try:
            d = e_dec(out, sec)
        except AssertionError as e:
            print('FAIL sec', sec, 'len', len(out), e)
            n += 1
            continue
        print('OK  sec', sec, 'word', sec & 3, 'len', len(out), 'rnd', hex(d['rnd']), 'check', hex(d['check']),
              'flags', d['flags'].hex(), 'proto', len(d['proto']))
        n += 1
    print('total', n)
