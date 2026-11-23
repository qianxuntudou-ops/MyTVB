# 生成入库用的合成向量（虚构身份，不含任何真实设备标识）：
# 用已与 HGTV 48/48 逐字节等价的参考实现，对我方两条路由的真实画像形态各出一条期望值，
# 并附带加密封装各阶段的中间量（失败时可直接定位到层）。
import sys, os, io, json, gzip, hashlib, base64, struct
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import medusa_dec as M
import medusa_ref as R

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))  # 仓库根
# 本地材料目录（抓包样本、金标准等含真实设备标识，不入库）；可用环境变量 HGTV_SIGN_DATA 覆盖
DATA = os.environ.get('HGTV_SIGN_DATA') or os.path.join(ROOT, '.zcode', 'hgtv-sign', 'tools')

HERE = os.path.dirname(os.path.abspath(__file__))
DST = os.path.join(ROOT, 'app', 'src', 'test', 'resources', 'medusa_vectors.json')  # 入库


def cached(token, did):
    t = token or did
    return base64.urlsafe_b64encode(hashlib.sha256(('hgtv-sdk-token\0%s\0%s' % (t, did)).encode()).digest()[:18]).decode().rstrip('=')


def physical(res):
    try:
        a, b = [int(x) for x in res.split('*')]
        return 'Physical size: %dx%d' % (min(a, b), max(a, b))
    except Exception:
        return 'Physical size: 1080x2400'


def encode(pairs):
    unres = "-_.!~*'()"
    out = []
    for k, v in pairs:
        def esc(s):
            return ''.join(chr(c) if (chr(c).isalnum() and chr(c).isascii()) or chr(c) in unres else '%%%02X' % c
                           for c in s.encode())
        out.append('%s=%s' % (esc(k), esc(v)))
    return '&'.join(out)


def sm3(b):
    return hashlib.new('sm3', b).digest()


def stages(url, body, sec, rnd, proto):
    """r0.E 的各阶段：SM3 密钥流 / xmxor / 内层包 / 位装入 / AES 两块 / 位写回。"""
    rnd_le = struct.pack('<I', rnd[1] & 0xFFFFFFFF)
    url = url.split('#')[0]
    q = url[url.index('?') + 1:]
    qh = sm3(q.encode())
    bmd5 = hashlib.md5(body).digest() if body else bytes(16)
    dg = R.digest12(qh, bmd5, sec)
    check = ((qh[0] & 63) << 46) | 0x1800000100000000 | ((dg[0] & 63) << 40) | (1 << 32) | (rnd[2] & 0xFFFFFFFF)
    flags = struct.pack('<Q', 0x8504)
    h = sm3(M.SIGN_KEY + rnd_le + M.SIGN_KEY)
    x = M.xmxor_fwd(list(proto), h)
    mask = M.chk(rnd_le[2:4], 0, 7, 3, 11, 5)
    u1 = list(flags) + x
    n = len(u1)
    b6 = [u1[(n - 1) - i] ^ ((mask >> ((3 - (i % 4)) * 8)) & 255) for i in range(n)]
    inner = [M.chk(M.SIGN_KEY, 0, 7, 3, 11, 5) & 255] + list(struct.pack('<Q', check)) + b6 + list(rnd_le[2:4])
    plain = bytearray(32)
    for i in range(31):
        for j in range(8):
            sb, sh = M.S[j]
            plain[i] |= ((inner[i * 8 + sb] >> sh) & 1) << j
    plain[31] = 1
    word = sec & 3
    key, iv, rk = M.RK[word]
    # 第一块分步（诊断）：输入异或 / ark0 / sub / perm / mix / ark1 / sub / ark2 / 输出
    blk = [bytes(plain[i] ^ iv[i] for i in range(16))]
    sb = bytearray(plain[0:16])
    for i in range(16):
        sb[i] ^= iv[i]
    M.ark(word, sb, rk[0:16]); blk.append(bytes(sb))
    M.h_fwd(word, sb); blk.append(bytes(sb))
    M.perm_q(word, sb); blk.append(bytes(sb))
    M.mix(sb); blk.append(bytes(sb))
    M.ark(word, sb, rk[16:32]); blk.append(bytes(sb))
    M.h_fwd(word, sb); blk.append(bytes(sb))
    M.ark(word, sb, rk[32:48]); blk.append(bytes(sb))
    blk.append(bytes(sb[i] ^ rk[16 + i] for i in range(16)))
    o0 = M.block_fwd(word, plain[0:16], list(iv))
    assert bytes(o0) == blk[-1]
    o1 = M.block_fwd(word, plain[16:32], o0)
    bits = o0 + o1
    outp = bytearray(inner)
    for i in range(31):
        for j in range(8):
            tb, off, sh = M.T[j]
            pos = i * 8 + off
            outp[pos] = ((((bits[i] >> tb) & 1) << sh) | (outp[pos] & ~(1 << sh))) & 255
    return dict(check=check, hash=h.hex(), x=bytes(x).hex(), inner=bytes(inner).hex(),
                plain=bytes(plain).hex(), bits=bytes(bits).hex(), outp=bytes(outp).hex(),
                rk=bytes(rk).hex(), iv=bytes(iv).hex(), key=bytes(key).hex(), blk=[b.hex() for b in blk])


def make(name, host_path, pairs, body_plain, sec, now, rnd, counter, tls_in, created, l3, nonce, prof):
    body = None
    if body_plain is not None:
        body = gzip.compress(body_plain.encode(), mtime=0)
    bmd5 = hashlib.md5(body).digest() if body else bytes(16)
    chosen = None
    for off in range(1024):
        q = encode([(k, str(now + off) if k == '_rticket' else v) for k, v in pairs])
        qh = sm3(q.encode())
        if (M.chk(qh + bmd5 + struct.pack('<i', sec), 539167016, 6, 4, 12, 7) & 15) == 12:
            chosen = (q, off, qh)
            break
    assert chosen, 'no selector-12 offset'
    q, off, qh = chosen
    st = dict(device_id=prof['deviceId'], version=prof['versionName'], channel=prof['channel'], nonce=nonce,
              cached_token=cached(prof['deviceToken'], prof['deviceId']), device_type=prof['deviceType'],
              device_brand=prof['deviceBrand'], os_version=prof['osVersion'], resolution=physical(prof['resolution']),
              l3_started=l3, now_c=created, j6=created - 180663, counter=counter, rec8=511, tls=tls_in, arr=[0] * 9,
              detect=0x8504)
    url = 'https://%s?%s' % (host_path, q)
    out, st2, proto = R.sign(url, body, sec, now + off, rnd, st)
    sg = stages(url, body, sec, rnd, proto)
    # 自检：各阶段产物重组回去必须就是最终输出
    rnd_le = struct.pack('<I', rnd[1] & 0xFFFFFFFF)
    rebuilt = b''.join(struct.pack('<I', (sec ^ w) & 0xFFFFFFFF) for w in M.MAGIC_WORDS)
    rebuilt += rnd_le[:2] + bytes([0, 1]) + bytes.fromhex(sg['bits'])[31:32] + bytes.fromhex(sg['outp'])
    assert base64.b64encode(rebuilt).decode() == out, name
    return dict(name=name, url=url, bodyB64=(base64.b64encode(body).decode() if body else None), sec=sec,
                nowMillis=now + off, rnd=rnd, state=dict(
                    deviceId=prof['deviceId'], deviceToken=prof['deviceToken'], versionName=prof['versionName'],
                    channel=prof['channel'], resolution=prof['resolution'], osVersion=prof['osVersion'],
                    deviceType=prof['deviceType'], deviceBrand=prof['deviceBrand'], l3StartedAt=l3,
                    createdAt=created, processNonce=nonce, counter=counter, tlsIn=tls_in),
                expectedMedusa=out, expectedProto=proto.hex(), stages=stages(url, body, sec, rnd, proto))


V5TAB_TAIL = [('video_id_type', '0'), ('device_level', '1'), ('need_all_video_definition', '1'),
              ('need_mp4_align', '0'), ('use_os_player', '0'), ('video_platform', '1024'), ('source', '0'),
              ('from_video_id', ''), ('_rticket', '')]

PRELOAD_PAIRS = [('device_platform', 'android'), ('os', 'android'), ('ssmix', 'a'), ('_rticket', ''),
                 ('cdid', 'abcdef0123456789abcdef0123456789'), ('channel', 'update_64'), ('aid', '8662'),
                 ('app_name', 'novelread'), ('version_code', '74332'), ('version_name', '7.4.3.32'),
                 ('manifest_version_code', '74332'), ('update_version_code', '74332'), ('resolution', '1280*2772'),
                 ('dpi', '520'), ('device_type', '25053RT47C'), ('device_brand', 'Redmi'), ('language', 'zh'),
                 ('os_api', '36'), ('os_version', '16'), ('ac', 'wifi'), ('host_abi', 'arm64-v8a')]

PRELOAD_BODY = ('{"dr_scene":"preload","mixed_video_id_map":{"1004":["7700000000000000003"]},'
                '"biz_param":{"video_platform":1024}}')


def main():
    dev_a = '7300000000000000002'
    dev_b = '7300000000000000003'
    tok_b = 'SYNTHETIC0TOKEN0000000000000000000000001'
    prof_a = dict(deviceId=dev_a, deviceToken='', versionName='7.4.3.32', channel='vivo_8662_64', resolution='',
                  osVersion='12', deviceType='PGT-AN10', deviceBrand='OnePlus')
    prof_b = dict(deviceId=dev_b, deviceToken=tok_b, versionName='7.4.3.32', channel='update_64',
                  resolution='1280*2772', osVersion='16', deviceType='25053RT47C', deviceBrand='Redmi')
    tab_host = 'api5-normal-sinfonlineb.fqnovel.com/reading/bookapi/video_tab/multi_video_model/v1'
    pre_host = 'api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/preload/v1'
    tab_head = [('aid', '8662'), ('app_name', 'novelread'), ('version_code', '74332'), ('version_name', '7.4.3.32'),
                ('channel', 'vivo_8662_64'), ('device_platform', 'android'), ('device_type', 'PGT-AN10'),
                ('os_version', '12'), ('update_version_code', '74332'), ('iid', '7400000000000000001'),
                ('cdid', 'abcdef0123456789abcdef0123456789'), ('device_id', dev_a)]
    pre_tail = [('device_id', dev_b), ('iid', '7400000000000000003'), ('device_token', tok_b),
                ('klink_egdi', 'synthetic')]
    nonce_a = 'SYNTHnonceAAAAAAAAAAAAAAAA'
    nonce_b = 'SYNTHnonceBBBBBBBBBBBBBBBB'
    cases = [
        make('v5tab-defaults-w0', tab_host,
             tab_head + [('video_id', '7700000000000000001')] + V5TAB_TAIL,
             None, 1795500000, 1795500000123, [1012345678, 2000999999, 3141592653], 316, 305419896,
             1795499000000, 1794900000000, nonce_a, prof_a),
        make('v5tab-defaults-w1', tab_host,
             tab_head + [('video_id', '7700000000000000002')] + V5TAB_TAIL,
             None, 1795500001, 1795500001456, [123456789, 987654321, 555555555], 317, 1234567890,
             1795499000000, 1794900000000, nonce_a, prof_a),
        make('preload-redmi-w2', pre_host, PRELOAD_PAIRS + pre_tail, PRELOAD_BODY,
             1795500002, 1795500002789, [777777777, 888888888, 999999999], 400, 2147483647,
             1795499500000, 1794900000000, nonce_b, prof_b),
        # ts&3 == 3：密钥扩展里 init 的字节掩码走 249 的分支
        make('preload-redmi-w3', pre_host, PRELOAD_PAIRS + pre_tail,
             '{"dr_scene":"preload","mixed_video_id_map":{"1004":["7700000000000000003","7700000000000000004"]},'
             '"biz_param":{"video_platform":1024,"device_level":4}}',
             1795500003, 1795500003444, [1000000001, 2000000003, 3000000007], 65535, 1,
             1795499500000, 1794900000000, nonce_b, prof_b),
    ]
    io.open(DST, 'w', encoding='utf-8', newline='\n').write(json.dumps(cases, ensure_ascii=False, indent=1) + '\n')
    for c in cases:
        print(c['name'], 'sec', c['sec'], 'word', c['sec'] & 3, 'medusa len', len(c['expectedMedusa']),
              'proto', len(bytes.fromhex(c['expectedProto'])))


if __name__ == '__main__':
    main()
