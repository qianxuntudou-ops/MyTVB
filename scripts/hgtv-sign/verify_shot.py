# 发枪前的自检：解密 HgMedusaVerifyProbeTest 落盘的请求（last_shot.json），核对 x-medusa 签的确实是
# 「将要发送的那份 query / body / 时间戳」，并与 HGTV 实测请求形态比对。全程离线。
# 用法：python verify_shot.py [last_shot.json 路径]
import sys, os, io, json, hashlib, struct, base64, gzip, datetime
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import medusa_dec as M
import medusa_ref as R

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DATA = os.environ.get('HGTV_SIGN_DATA') or os.path.join(ROOT, '.zcode', 'hgtv-sign', 'tools')

# HGTV 1.3.8.13 真机 preload 请求的请求头（scripts/hgtv-frida/sample/），名称与顺序
HGTV_HEADERS = ['User-Agent', 'Accept', 'x-ss-req-ticket', 'sdk-version', 'passport-sdk-version', 'lc',
                'x-vc-bdturing-sdk-version', 'x-xs-from-web', 'x-ss-dp', 'x-tt-store-region', 'x-tt-store-region-src',
                'x-khronos', 'x-ladon', 'x-argus', 'x-gorgon', 'x-helios', 'x-medusa', 'Content-Type',
                'Content-Encoding', 'x-reading-request', 'x-ss-stub']


def zz(v):
    return (v >> 1) ^ -(v & 1)


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(DATA, 'last_shot.json')
    shot = json.load(io.open(path, encoding='utf-8'))
    hdrs = [tuple(h) for h in shot['headers']]
    h = dict(hdrs)
    url = shot['url']
    body = base64.b64decode(shot['bodyB64'])
    sec = int(h['x-khronos'])
    query = url.split('?', 1)[1]
    qh = hashlib.new('sm3', query.encode()).digest()
    bmd5 = hashlib.md5(body).digest()
    qd = dict(kv.split('=', 1) for kv in query.split('&'))
    d = M.e_dec(h['x-medusa'], sec)
    f = {k: v for k, wt, v in M.parse_pb(d['proto'])}
    env = {k: v for k, wt, v in M.parse_pb(f[23])}
    dev = {k: v for k, wt, v in M.parse_pb(env[12])}
    checks = [
        ('selector == 12（我方只移植了 12 号分支）',
         (M.chk(qh + bmd5 + struct.pack('<i', sec), 539167016, 6, 4, 12, 7) & 15) == 12),
        ('body 是 gzip 且声明了 Content-Encoding', body[:2] == b'\x1f\x8b' and h.get('Content-Encoding') == 'gzip'),
        ('x-ss-stub == MD5(body)', h['x-ss-stub'].lower() == bmd5.hex()),
        ('x-ss-req-ticket == query._rticket', h['x-ss-req-ticket'] == qd.get('_rticket')),
        ('x-medusa 可解密', len(d['proto']) > 0),
        ('medusa 摘要 == digest12(实际 query, body, ts)', R.digest12(qh, bmd5, sec) == f[13]),
        ('medusa field14 == SM3(实际 query)[:6]', f[14] == qh[:6]),
        ('medusa 时间戳字段 == x-khronos', zz(f[12]) == sec and zz(f[17]) == sec),
        ('medusa 设备号 / 渠道 / 版本 == query', f[5].decode() == qd.get('device_id')
         and env[8].decode() == qd.get('channel') and f[7].decode() == qd.get('version_name')),
        ('请求头名与 HGTV 实测一致', sorted(k for k, _ in hdrs) == sorted(HGTV_HEADERS)),
    ]
    print('请求时刻 %s  x-khronos=%d  版本 %s  medusa %d 字符 / protobuf %d 字节' % (
        datetime.datetime.fromtimestamp(sec).strftime('%Y-%m-%d %H:%M:%S'), sec, shot.get('version'),
        len(h['x-medusa']), len(d['proto'])))
    print('body 明文: %s' % gzip.decompress(body).decode()[:200])
    bad = 0
    for name, ok in checks:
        print('  [%s] %s' % ('OK' if ok else '!!', name))
        bad += not ok
    r = shot.get('resp')
    if r:
        print('响应: http=%s len=%s code=%s ms=%s dataKeys=%s hasModel=%s' % (
            r['http'], r['len'], r['code'], r['ms'], r['dataKeys'], r['hasModel']))
    print('RESULT: %s' % ('PASS' if bad == 0 else 'FAIL (%d 项不符，不要发)' % bad))
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
