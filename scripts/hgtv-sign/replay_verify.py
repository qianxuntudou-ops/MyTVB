# 回放验证：用解密出的内部状态驱动 medusa_ref，重建 protobuf 并加密，与 HGTV 原始输出逐字节比对。
import sys, io, json, hashlib, struct, base64, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import medusa_dec as M
import medusa_ref as R

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))  # 仓库根
# 本地材料目录（抓包样本、金标准等含真实设备标识，不入库）；可用环境变量 HGTV_SIGN_DATA 覆盖
DATA = os.environ.get('HGTV_SIGN_DATA') or os.path.join(ROOT, '.zcode', 'hgtv-sign', 'tools')

HERE = os.path.dirname(os.path.abspath(__file__))
recs = [json.loads(l) for l in io.open(os.path.join(DATA, 'dec_samples.jsonl'), encoding='utf-8')]


def zz(v):  # zigzag decode (varint 值)
    v &= 0xFFFFFFFFFFFFFFFF
    return (v >> 1) ^ -(v & 1)


def get(b, fid):
    for f, wt, v in M.parse_pb(b):
        if f == fid:
            return v
    return None


def getall(b, fid):
    return [v for f, wt, v in M.parse_pb(b) if f == fid]


def parse_query(q):
    out = {}
    for kv in q.split('&'):
        k, _, v = kv.partition('=')
        out[k] = v
    return out


def state_of(rec):
    p = bytes.fromhex(rec['proto'])
    url = rec['url'].split('#')[0]
    q = url[url.index('?') + 1:]
    qd = parse_query(q)
    main = {f: v for f, wt, v in M.parse_pb(p)}
    env = {f: v for f, wt, v in M.parse_pb(main[23])}
    dev = {f: v for f, wt, v in M.parse_pb(env[12])}
    js = json.loads(main[24].decode())
    lp = [int(x) for x in js['lp'].split('|')]
    fl = [int(x) for x in js['fl'].split('|')]
    res = qd.get('resolution', '')
    try:
        a, b = [int(x) for x in res.split('*')]
        resolution = 'Physical size: %dx%d' % (min(a, b), max(a, b))
    except Exception:
        resolution = 'Physical size: 1080x2400'
    body = None if rec['body'] is None else base64.b64decode(rec['body'])
    st = dict(
        device_id=main[5].decode(), version=main[7].decode(), channel=env[8].decode(),
        nonce=main[16].decode(), cached_token=dev[5].decode(),
        device_type=dev[30].decode(), device_brand=dev[31].decode(), os_version=dev[22].decode(),
        resolution=resolution, l3_started=zz(dev[25]), now_c=zz(dev[26]) + 180290, j6=lp[3] ^ 520830913368,
        counter=zz(get(dev if False else main[15], 1)), rec8=lp[1] ^ 520830913368, tls=lp[2] ^ 520830913368,
        arr=fl[:9], detect=struct.unpack('<Q', main[10])[0],
    )
    rnd = [zz(main[3]), rec['rnd'], rec['check'] & 0xFFFFFFFF]
    return st, rnd, url, q, body, dev


ok_p = ok_e = ok_dg = ok_ck = ok_fl = 0
prev = None
chain_ok = 0; chain_n = 0
for i, rec in enumerate(recs):
    st, rnd, url, q, body, dev = state_of(rec)
    now = zz(dev[28])
    qhash = hashlib.new('sm3', q.encode()).digest()
    bmd5 = hashlib.md5(body).digest() if body else bytes(16)
    # 链式值的输入是「上一条」的状态：tls_prev = tls_cur ^ rotl(crc ^ sec, 4)
    st_in = dict(st, tls=(st['tls'] ^ R.rotl64_32(R.CRC64_LIC ^ (rec['sec'] & 0xFFFFFFFF), 4)) & 0xFFFFFFFF)
    out, st2, proto = R.sign(url, body, rec['sec'], now, rnd, st_in)
    ok_p += (proto == bytes.fromhex(rec['proto']))
    ok_e += (out == rec['out'])
    ok_dg += (R.digest12(qhash, bmd5, rec['sec']) == get(bytes.fromhex(rec['proto']), 13))
    ok_ck += (st2['tls'] == st['tls'])
    # 链式演化：本条 tls == 上一条 tls ^ rotl(crc ^ sec, 4)
    if prev is not None:
        chain_n += 1
        chain_ok += (st['tls'] == (prev['tls'] ^ R.rotl64_32(R.CRC64_LIC ^ (rec['sec'] & 0xFFFFFFFF), 4)))
    prev = st
    if proto != bytes.fromhex(rec['proto']) and i < 3:
        a = bytes.fromhex(rec['proto']); b = proto
        k = next((x for x in range(min(len(a), len(b))) if a[x] != b[x]), min(len(a), len(b)))
        print('PROTO DIFF #%d at %d: hgtv %s | ours %s (len %d vs %d)' % (i, k, a[k:k + 12].hex(), b[k:k + 12].hex(), len(a), len(b)))
print('digest  == field13      :', ok_dg, '/', len(recs))
print('proto   == decrypted    :', ok_p, '/', len(recs))
print('E(proto) == hgtv output :', ok_e, '/', len(recs))
print('tls chain consistency   :', chain_ok, '/', chain_n)
