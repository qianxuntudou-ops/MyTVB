# 生成 Kotlin 对拍金标准 golden.jsonl：把每条 HGTV 样本还原成「Kotlin 实现需要的全部显式输入 + 期望输出」。
# 输入来自 dec_samples.jsonl（解密结果），期望输出即 HGTV 原始 x-medusa 与明文 protobuf。
import sys, os, io, json, hashlib, base64
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import medusa_dec as M
import medusa_ref as R

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))  # 仓库根
# 本地材料目录（抓包样本、金标准等含真实设备标识，不入库）；可用环境变量 HGTV_SIGN_DATA 覆盖
DATA = os.environ.get('HGTV_SIGN_DATA') or os.path.join(ROOT, '.zcode', 'hgtv-sign', 'tools')

HERE = os.path.dirname(os.path.abspath(__file__))


def zz(v):
    """zigzag 解码（protobuf 的 int32/int64 字段值）。"""
    return (v >> 1) ^ -(v & 1)


def fields(b):
    return {k: v for k, wt, v in M.parse_pb(b)}


def query_dict(q):
    out = {}
    for kv in q.split('&'):
        k, _, v = kv.partition('=')
        out[k] = v
    return out


def main():
    recs = [json.loads(l) for l in io.open(os.path.join(DATA, 'dec_samples.jsonl'), encoding='utf-8')]
    out = []
    tokens = set()
    for rec in recs:
        proto = bytes.fromhex(rec['proto'])
        mainm = fields(proto)
        env = fields(mainm[23])
        dev = fields(env[12])
        lp = [int(x) for x in json.loads(mainm[24].decode())['lp'].split('|')]
        url = rec['url'].split('#')[0]
        qd = query_dict(url[url.index('?') + 1:])
        tokens.add(qd.get('device_token'))
        tlsNow = lp[2] ^ 520830913368
        state = dict(
            deviceId=mainm[5].decode(), versionName=mainm[7].decode(), channel=env[8].decode(),
            resolution=qd.get('resolution', ''), osVersion=dev[22].decode(), deviceType=dev[30].decode(),
            deviceBrand=dev[31].decode(),
            l3StartedAt=zz(dev[25]), createdAt=zz(dev[26]) + 180290, processNonce=mainm[16].decode(),
            counter=zz(fields(mainm[15])[1]),
            # 链式值的输入是「上一条」的状态：tls_prev = tls_now ^ rotl(crc ^ sec, 4)
            tlsIn=(tlsNow ^ R.rotl64_32(R.CRC64_LIC ^ (rec['sec'] & 0xFFFFFFFF), 4)) & 0xFFFFFFFF,
        )
        out.append(dict(
            url=rec['url'], bodyB64=rec['body'], sec=rec['sec'], nowMillis=zz(dev[28]),
            rnd=[zz(mainm[3]), rec['rnd'] & 0xFFFFFFFF, rec['check'] & 0xFFFFFFFF],
            state=state, expectedMedusa=rec['out'], expectedProto=rec['proto'],
        ))
    # 全部样本同一设备；device_token 只出现在部分请求的 query 里
    tokens.discard(None)
    assert len(tokens) == 1, tokens
    token = tokens.pop()
    for o in out:
        o['state']['deviceToken'] = token
    # 自检 cachedToken 公式（protobuf 里只能看到结果，看不到 token）
    seed = ('hgtv-sdk-token\0%s\0%s' % (token, out[0]['state']['deviceId'])).encode()
    ct = base64.urlsafe_b64encode(hashlib.sha256(seed).digest()[:18]).decode().rstrip('=')
    env0 = fields(fields(bytes.fromhex(out[0]['expectedProto']))[23])
    dev0 = fields(env0[12])
    assert ct == dev0[5].decode(), ct
    dst = os.path.join(DATA, 'golden.jsonl')
    with io.open(dst, 'w', encoding='utf-8') as f:
        for o in out:
            f.write(json.dumps(o, ensure_ascii=False) + '\n')
    print('golden samples', len(out), '->', dst)
    print('cachedToken formula verified for device', out[0]['state']['deviceId'])


if __name__ == '__main__':
    main()
