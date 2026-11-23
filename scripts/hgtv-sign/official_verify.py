# 用官方 App 的真实 medusa（服务端 100% 接受）反向验证参考实现：
# 摘要 field13、field14、check64 结构、以及 protobuf 字段与请求的一致性关系。
# X-SS-STUB 即 gzip body 的 MD5，种子三要素（SM3(query) / MD5(body) / ts）齐全，全程离线。
# 注意：官方 App 的 selector 在 0~15 均匀随机，只有 selector==12 的样本才适用我方唯一实现的摘要分支；
# 另有少量样本抓包记录的 URL 被规范化过（field14 对不上），一并排除。
import sys, os, hashlib, struct, collections
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import medusa_dec as M
import medusa_ref as R
import official_dec as O


def zz(v):
    return (v >> 1) ^ -(v & 1)


def fields(b):
    return {k: v for k, wt, v in M.parse_pb(b)}


def main():
    evs = O.load()
    n = sel12 = url_exact = ok_dg = ok_chk = 0
    cons = collections.Counter()
    bad = []
    for e in evs:
        try:
            d = M.e_dec(e['medusa'], e['khronos'])
            f = fields(d['proto'])
        except Exception:
            continue
        n += 1
        url = e['url'].split('#')[0]
        q = url[url.index('?') + 1:] if '?' in url else ''
        qh = hashlib.new('sm3', q.encode()).digest()
        stub = (e['stub'] or '').strip()
        bmd5 = bytes.fromhex(stub) if len(stub) == 32 else bytes(16)
        # check64 结构公式对全部样本成立（与 selector 无关）
        exp = ((qh[0] & 63) << 46) | 0x1800000100000000 | ((f[13][0] & 63) << 40) | (1 << 32) | (d['check'] & 0xFFFFFFFF)
        ok_chk += d['check'] == exp
        if (M.chk(qh + bmd5 + struct.pack('<i', e['khronos']), 539167016, 6, 4, 12, 7) & 15) != 12:
            continue
        sel12 += 1
        if f[14] != qh[:6]:
            continue
        url_exact += 1
        if R.digest12(qh, bmd5, e['khronos']) == f[13]:
            ok_dg += 1
        elif len(bad) < 4:
            bad.append((e['url'].split('?')[0][-46:], e['method'], len(stub), e['file']))
        env = fields(f[23])
        dev = fields(env[12])
        qdid = {}
        for kv in q.split('&'):
            k, _, v = kv.partition('=')
            qdid[k] = v
        cons[(zz(f[12]) == e['khronos'], zz(f[17]) == e['khronos'],
              f[5].decode() == qdid.get('device_id'), dev[4].decode() == qdid.get('device_id'))] += 1
    print('官方样本解密成功            : %d' % n)
    print('check64 结构公式            : %d / %d' % (ok_chk, n))
    print('其中 selector==12            : %d（其余走我方未移植的分支）' % sel12)
    print('其中 URL 记录精确            : %d' % url_exact)
    print('摘要 == digest12(官方种子)   : %d / %d' % (ok_dg, url_exact))
    for k, v in cons.most_common():
        print('  (f12==ts, f17==ts, f5==query.device_id, dev4==device_id) =', k, '×', v)
    if bad:
        print('摘要不符样本:', bad)
    return 0 if ok_dg == url_exact and url_exact >= 100 else 1


if __name__ == '__main__':
    sys.exit(main())
