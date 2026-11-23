# HGTV medusa (V5, selector=12) 的 Python 参考实现：I1.e K1 分支 + r0.G/E 的正向。
# 用途：1) 用解密出的内部状态回放样本，证明对源码的理解逐字节正确；
#       2) 作为 Kotlin 移植的对拍基准。
import json, io, os, struct, hashlib, base64
import medusa_dec as M

C = M.C
TT = [x & 0xFFFFFFFF for x in C['f2275c']]            # Java int → 无符号（旋转前必须归一，否则负数走算术移位）
INIT8 = [x & 0xFFFFFFFF for x in C['f2274b']]
PAD = [x & 255 for x in C['f2276d'][:4]]                # Java 有符号 byte → 无符号
# I1.f1805c（64 字节；其中 bz.f8900l=14 / f8902n=16 / f8899k=13 / f8901m=15）
TBL64 = [58, 50, 42, 34, 26, 18, 10, 2, 60, 52, 44, 36, 28, 20, 12, 4, 62, 54, 46, 38, 30, 22, 14, 6, 64, 56, 48,
         40, 32, 24, 16, 8, 57, 49, 41, 33, 25, 17, 9, 1, 59, 51, 43, 35, 27, 19, 11, 3, 61, 53, 45, 37, 29,
         21, 13, 5, 63, 55, 47, 39, 31, 23, 15, 7]
SIGN_KEY = M.SIGN_KEY
MAGIC16 = bytes.fromhex('2d4b4fca49750d433fb5ae2c226dcc56')


def u32(x): return x & 0xFFFFFFFF
def rotr32(x, n): n &= 31; return u32((x >> n) | (x << (32 - n))) if n else u32(x)
def rev32(x): return struct.unpack('>I', struct.pack('<I', u32(x)))[0]


def crc64(b):
    j2 = 0
    for by in b:
        j3 = (by ^ j2) & 255
        for _ in range(8):
            j3 = ((j3 >> 1) ^ 0x95AC9329AC4BC9B5) if (j3 & 1) else (j3 >> 1)
        j2 = (j2 >> 8) ^ j3
    return j2


CRC64_LIC = crc64(b'646637794')


def rotl64_32(x, n):
    """Long.rotateLeft(x, n) 后取低 32 位。"""
    x &= 0xFFFFFFFFFFFFFFFF
    r = ((x << n) | (x >> (64 - n))) & 0xFFFFFFFFFFFFFFFF
    return r & 0xFFFFFFFF


def digest12(qhash, bmd5, sec):
    data = qhash + bmd5 + struct.pack('<i', sec)
    count = u32(sec + 66)
    pad = bytes(M.rotl8(PAD[i], -(count + 2)) for i in range(4))
    di = list(struct.unpack('>16I', data + pad + struct.pack('>Q', 416)))
    for i in range(16, 128):
        a = di[i - 15]; b = di[i - 2]
        di.append(u32(di[i - 16] + (rotr32(a, 18) ^ rotr32(a, 7) ^ (a >> 3)) + di[i - 7] +
                      (rotr32(b, 17) ^ rotr32(b, 19) ^ (b >> 10))))
    initial = [rotr32(x, count) for x in INIT8]
    tt = [rotr32(x, count + 1) for x in TT]
    idx = [3, 5, 2, 7, 1, 0, 4, 6]
    st = initial[:]
    for r in range(96):
        base = 66 + r     # 源码 i30 从 K1.c(=66) 起、循环内自增；是固定下标，不含时间戳
        s = st
        a = s[3]; b = s[5]; c = s[2]; d = s[7]; e = s[1]; f = s[0]; g = s[4]; h = s[6]
        maj = ((a | b) & c) | (a & b)
        S0 = rotr32(a, 2) ^ rotr32(a, 13) ^ rotr32(a, 22)
        T1 = u32(h + (rotr32(e, 25) ^ rotr32(e, 11) ^ rotr32(e, 6)) + (g ^ (e & (f ^ g))) + tt[base & 63] + di[base & 127])
        ns = [s[7], s[0], s[1], s[2], s[3], s[4], s[5], s[6]]
        ns[(idx[3] + 1) & 7] = u32(d + T1)
        ns[7] = u32(T1 + S0 + maj)
        st = ns
    b6 = b''.join(struct.pack('>I', u32(st[i] + initial[i])) for i in range(8))
    b = bytes(b6[i] ^ b6[i + 16] for i in range(16))
    out = bytearray(b + struct.pack('<I', M.chk(b[:12], 539100192, 7, 3, 11, 5)))
    out[16] |= 4; out[16] |= 32
    out[19] ^= 1     # M0(47).f1859a > 0（jadx 把构造器三元式显示反了；48 组样本 dg[19] 实证）
    return bytes(out)


# ---------- schema 驱动的 protobuf 序列化（r0.G）----------
# 类型：1=int32 zigzag, 4=int64 zigzag, 6=fixed32(int), 10=float, 13=raw varint, 14=bytes/str 截到首个 0, 15=bytes/str, 16=嵌套
DEV = {1: 1, 2: 1, 3: 14, 4: 14, 5: 14, 6: 14, 7: 1, 8: 1, 9: 1, 10: 1, 11: 14, 12: 14, 13: 14, 14: 1, 15: 14,
       16: 10, 17: 10, 18: 10, 19: 10, 20: 10, 21: 10, 22: 14, 23: 1, 24: 1, 25: 4, 26: 4, 27: 4, 28: 4, 29: 1,
       30: 14, 31: 14, 32: 14, 33: 14, 34: 14, 35: 14, 36: 14, 37: 14, 38: 4, 39: 15, 40: 4}
ENV = {1: 1, 2: 4, 3: 4, 4: 1, 5: 1, 6: 14, 7: 1, 8: 14, 9: 14, 10: 14, 11: 14, 12: 16, 13: 16, 14: 14, 15: 1,
       16: 4, 17: 4, 18: 4, 19: 1, 20: 4, 21: 1, 22: 1, 23: 1, 24: 1}
ENV13 = {1: 4, 2: 13, 3: 14, 4: 1, 5: 1, 6: 1}
ENV15 = {1: 14, 2: 14, 3: 14, 4: 1}
MAIN = {1: 15, 2: 1, 3: 1, 4: 14, 5: 14, 6: 14, 7: 14, 8: 14, 9: 1, 10: 15, 11: 13, 12: 4, 13: 15, 14: 15,
        15: 16, 16: 14, 17: 1, 18: 15, 19: 15, 20: 14, 21: 1, 22: 1, 23: 16, 24: 14, 25: 16, 26: 1, 27: 15, 28: 1, 29: 6}
SUB = {'15': {1: 1, 2: 1, 3: 1, 4: 1, 5: 1}, '23.12': DEV, '23.13': ENV13, '23': ENV, '25': ENV15}


def wvarint(out, v):
    v &= 0xFFFFFFFFFFFFFFFF
    while v >= 0x80:
        out.append((v & 0x7F) | 0x80); v >>= 7
    out.append(v)


def ser(m, schema, path=''):
    out = bytearray()
    for fid in sorted(schema):
        typ = schema[fid]
        v = m.get(fid)
        if v is None:
            continue
        if typ == 1:
            zz = ((v >> 31) ^ (v << 1)) & 0xFFFFFFFF      # Java: (i5 >> 31) ^ (i5 << 1)，v 为有符号 int32
            if v == 0:
                continue
            wvarint(out, fid << 3); wvarint(out, zz)
        elif typ == 4:
            zz = ((v << 1) ^ (v >> 63)) & 0xFFFFFFFFFFFFFFFF
            if v == 0:
                continue
            wvarint(out, fid << 3); wvarint(out, zz)
        elif typ == 6:
            if v == 0:
                continue
            wvarint(out, (fid << 3) | 5); out += struct.pack('<I', v & 0xFFFFFFFF)
        elif typ == 10:
            fv = struct.unpack('<f', struct.pack('<f', v))[0]
            if fv == 0.0:
                continue
            wvarint(out, (fid << 3) | 5); out += struct.pack('<f', v)
        elif typ == 13:
            if v == 0:
                continue
            wvarint(out, fid << 3); wvarint(out, v)
        elif typ == 16:
            b = ser(v, SUB[path + str(fid)], path + str(fid) + '.')
            wvarint(out, (fid << 3) | 2); wvarint(out, len(b)); out += b
        else:
            b = v if isinstance(v, (bytes, bytearray)) else v.encode()
            if typ == 14 and 0 in b:
                b = b[:b.index(0)]
            if len(b) == 0:
                continue
            wvarint(out, (fid << 3) | 2); wvarint(out, len(b)); out += b
    return bytes(out)


def build_proto(qhash, bmd5, sec, rnd, st):
    """st：签名器状态字典（见 sign()）。返回 (protobuf, digest20, check64, flags)。"""
    dg = digest12(qhash, bmd5, sec)
    fl = bytearray(8); struct.pack_into('<Q', fl, 0, st['detect'])
    now = st['now']
    main = {1: MAGIC16, 2: 5, 3: rnd[0] & 0xFFFFFFFF, 4: '8662', 5: st['device_id'], 6: '646637794',
            7: st['version'], 8: 'v04.10.03-ml-android', 9: 67764992, 10: bytes(fl), 12: sec, 13: dg,
            14: qhash[:6], 15: {1: st['counter'], 2: 5, 3: 694367, 5: 281826712}, 16: st['nonce'],
            17: sec & 0xFFFFFFFF, 20: 'none', 21: 312}
    started_sec = st['now_c'] // 1000 - 180
    # jadx 显示为「startedAtSeconds == 0 才写」，样本实证相反：非零时写 (int)(sec - startedAtSeconds)
    env = {1: (sec - started_sec) & 0xFFFFFFFF, 2: 247235599, 3: 247235596, 5: 7, 6: 'v04.10.03.04-bugfix', 7: 22088, 8: st['channel'],
           14: st['version'], 15: 2113, 16: 27, 17: 2491,
           18: (518304718848 ^ 519321784724) ^ sec, 19: (1804 ^ sec) & 0xFFFFFFFF, 20: 519321784724 ^ sec,
           22: -780914011, 23: -780914011}
    dev = {1: 1, 2: 2, 3: '8662', 4: st['device_id'], 5: st['cached_token'], 6: '!noperm!', 7: -888888,
           8: -888888, 9: 2, 10: -888888, 11: '!notset!', 12: 'Asia/Shanghai,8', 13: 'zh_CN', 14: 3,
           15: st['resolution'], 22: st['os_version'], 23: 102, 24: 5, 25: st['l3_started'], 26: st['now_c'] - 180290,
           27: st['now_c'] - 6117375, 28: now, 29: -1, 30: st['device_type'], 31: st['device_brand'],
           32: 'kona', 33: 'arm64_mac_qemu', 34: 'OnePlus8Pro', 35: 'OnePlus', 36: 'unknown', 38: 21,
           40: st['j6']}
    for k, fv in zip(range(16, 22), [124.99121, 121.888664, 3.8510208, 0.09474182, 249.98242, 243.77733]):
        dev[k] = fv
    env[12] = dev
    env[13] = {1: st['j6'] // 1000 + 3, 2: 3, 4: 200}
    main[23] = env
    arr = st['arr']
    lp = '2|%d|%d|%d|%d' % (st['rec8'] ^ 520830913368, st['tls'] ^ 520830913368, st['j6'] ^ 520830913368, 523425248256)
    flstr = '|'.join([str(arr[i] & 0xFFFFFFFF) for i in range(9)] + [str(st['tls'] & 0xFFFFFFFF)])
    main[24] = ('{"cmr":16777216,"cmr2":16777216,"un_h":1893996536,"vpn":0,"sts":32251,"kd":0,'
                '"fkd":2187746471,"pd":1417818606,"lp":"%s","fl":"%s","dyn":"","do":1,"tk":true}' % (lp, flstr))
    proto = ser(main, MAIN)
    check = ((qhash[0] & 63) << 46) | 0x1800000100000000 | ((dg[0] & 63) << 40) | (1 << 32) | (rnd[2] & 0xFFFFFFFF)
    return proto, dg, check, bytes(fl)


def step_fl(arr, rec8, j6):
    """I1.a(f12, 2,3,4,5,6)。"""
    arr = arr[:]
    j = j6 & 0xFFFFFFFFFFFFFFFF
    arr[2] = ((j >> 32) ^ j) & 0xFFFFFFFF
    arr[3] = rev32(arr[2])
    arr[4] = M.chk(struct.pack('<I', arr[3]) + bytes(4), 2102535, 6, 4, 12, 7)
    rot = arr[4]
    for i4 in range(2):
        if rec8 & (1 << [0, 1][i4]):
            rot = ((rot << 1) | (rot >> 31)) & 0xFFFFFFFF
    v = 0
    for k in range(4):
        v |= (TBL64[(rot >> (k * 8)) & 63] & 0xFF) << (k * 8)
    arr[5] = v
    j = arr[5]
    j ^= (j << 13) & 0xFFFFFFFFFFFFFFFF
    i3 = (j ^ (j >> 17)) & 0xFFFFFFFF
    arr[6] = ((i3 << 5) ^ i3) & 0xFFFFFFFF
    rec8 |= 0x7C
    return arr, rec8


def sign(url, body, sec, now, rnd, st):
    """url 含 query；body 为实际发送字节（None=无）；rnd=[r0,r1,r2]（每个 < 2^31）；st=签名器状态。
    返回 (medusa 头, 更新后的状态, protobuf 明文)。"""
    url = url.split('#')[0]
    q = url[url.index('?') + 1:] if '?' in url else ''
    qhash = hashlib.new('sm3', q.encode()).digest()
    bmd5 = hashlib.md5(body).digest() if body else bytes(16)
    arr, rec8 = step_fl(st['arr'], st['rec8'], st['j6'])
    st2 = dict(st, arr=arr, rec8=rec8, now=now)
    lic = CRC64_LIC ^ (sec & 0xFFFFFFFF)
    tls = st['tls']
    if tls == 0:
        tls = (rotl64_32(lic, 4) ^ 3026372000) & 0xFFFFFFFF
    tls = (tls ^ rotl64_32(lic, 4)) & 0xFFFFFFFF
    st2['tls'] = tls
    proto, dg, check, fl = build_proto(qhash, bmd5, sec, rnd, st2)
    return M.e_fwd(list(proto), sec, rnd[1], check, fl), st2, proto
