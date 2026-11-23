# 解密 sign_pairs.log 的全部 MEDUSA 样本 → dec_samples.jsonl（供对拍测试当金标准输入）
import io, json, os, sys, base64
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import medusa_dec as M

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))  # 仓库根
# 本地材料目录（抓包样本、金标准等含真实设备标识，不入库）；可用环境变量 HGTV_SIGN_DATA 覆盖
DATA = os.environ.get('HGTV_SIGN_DATA') or os.path.join(ROOT, '.zcode', 'hgtv-sign', 'tools')

src = os.path.join(ROOT, '.zcode', 'hgtv-frida', 'sign_pairs.log')
dst = os.path.join(DATA, 'dec_samples.jsonl')
out = io.open(dst, 'w', encoding='utf-8')
n = 0
for ln in io.open(src, encoding='utf-8'):
    if not ln.startswith('MEDUSA'):
        continue
    p = ln.rstrip('\n').split('|')
    d = M.e_dec(p[4], int(p[3]))
    rec = dict(url=p[1], body=None if p[2] == '-' else p[2], sec=int(p[3]), out=p[4],
               rnd=d['rnd'], rnd2=d['check'] & 0xFFFFFFFF, check=d['check'],
               flags=d['flags'].hex(), proto=d['proto'].hex())
    out.write(json.dumps(rec, ensure_ascii=False) + '\n')
    n += 1
out.close()
print('written', n, dst)
