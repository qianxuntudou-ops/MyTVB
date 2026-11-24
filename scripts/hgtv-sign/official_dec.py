# 解密官方 App（com.phoenix.read 7.4.5.32，2026-11-13 frida 抓的事件流）的 x-medusa，
# 供 Kotlin 对拍（official_verify.py / HongguoMedusaTest.officialAppDigest）。官方请求是服务端
# 100% 接受的 ground truth，全程离线。
#   - 控制台：解密成功率 + 一条播放类接口的完整明文；
#   - official_dec.json：全部 selector==12 的样本（与我方唯一实现的分支同类）。
import sys, io, json, glob, os, collections, hashlib, struct
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import medusa_dec as M

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))  # 仓库根


def _first_dir(*cands):
    """本地材料目录历史上在 .zcode/ 与 .trae/ 之间搬过，按顺序取第一个存在的。"""
    for c in cands:
        if os.path.isdir(c):
            return c
    return cands[0]


# 本地材料目录（抓包样本、金标准等含真实设备标识，不入库）；可用环境变量覆盖
DATA = os.environ.get('HGTV_SIGN_DATA') or _first_dir(
    os.path.join(ROOT, '.zcode', 'hgtv-sign', 'tools'),
    os.path.join(ROOT, '.trae', 'hgtv-sign', 'tools'),
)
CAP = os.environ.get('HGTV_CAPTURE') or _first_dir(
    os.path.join(ROOT, '.trae', 'hongguo-api-capture'),
    os.path.join(ROOT, '.zcode', 'hongguo-api-capture'),
)
HERE = os.path.dirname(os.path.abspath(__file__))


def load():
    """读出带 x-medusa 与 x-khronos 的官方请求事件。"""
    out = []
    for f in sorted(glob.glob(os.path.join(CAP, 'out', '*', 'events-*.jsonl'), recursive=True)):
        for ln in io.open(f, encoding='utf-8', errors='replace'):
            ln = ln.strip()
            if not ln.startswith('{') or 'medusa' not in ln.lower():
                continue
            try:
                e = json.loads(ln)
            except Exception:
                continue
            hdr = {k.lower(): v for k, v in (e.get('reqHeaders') or {}).items()}
            if 'x-medusa' in hdr and 'x-khronos' in hdr:
                out.append(dict(file=os.path.relpath(f, CAP), url=e['url'], method=e.get('method'),
                                body=e.get('reqBody'), medusa=hdr['x-medusa'], khronos=int(hdr['x-khronos']),
                                stub=hdr.get('x-ss-stub'), code=e.get('code'), rlen=len(e.get('respBody') or '')))
    return out


def query_of(url):
    url = url.split('#')[0]
    return url[url.index('?') + 1:] if '?' in url else ''


def selector_of(e):
    q = query_of(e['url'])
    qh = hashlib.new('sm3', q.encode()).digest()
    stub = (e['stub'] or '').strip()
    bmd5 = bytes.fromhex(stub) if len(stub) == 32 else bytes(16)
    return M.chk(qh + bmd5 + struct.pack('<i', e['khronos']), 539167016, 6, 4, 12, 7) & 15


def path_of(url):
    return url.split('#')[0].split('?', 1)[0].split('fqnovel.com')[-1]


if __name__ == '__main__':
    evs = load()
    ok = 0
    sel12 = []
    shown = collections.OrderedDict()
    fails = collections.Counter()
    for e in evs:
        try:
            d = M.e_dec(e['medusa'], e['khronos'])
            M.parse_pb(d['proto'])
        except AssertionError as ex:
            fails[str(ex)[:50]] += 1
            continue
        except Exception as ex:
            fails[type(ex).__name__ + ':' + str(ex)[:40]] += 1
            continue
        ok += 1
        p = path_of(e['url'])
        if p not in shown:
            shown[p] = (e, d)
        if selector_of(e) == 12:
            sel12.append(dict(url=e['url'], body=e['body'], sec=e['khronos'], medusa=e['medusa'], stub=e['stub'],
                              rnd=d['rnd'], check=d['check'], flags=d['flags'].hex(), proto=d['proto'].hex(),
                              code=e['code'], rlen=e['rlen'], file=e['file']))
    print('official medusa total %d, decrypted+parsed %d' % (len(evs), ok))
    print('fail reasons:', dict(fails))
    print('selector==12 samples:', len(sel12))
    with io.open(os.path.join(DATA, 'official_dec.json'), 'w', encoding='utf-8') as f:
        json.dump(sel12, f, ensure_ascii=False)
    want = [p for p in shown if 'multi_video_model' in p]
    for p in want[:2]:
        e, d = shown[p]
        print('=' * 100)
        print('OFFICIAL', e['method'], p, 'sec', e['khronos'], 'resp', e['code'], e['rlen'], 'from', e['file'])
        print('\n'.join(M.dump_pb(d['proto'])))
