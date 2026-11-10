# 将 HGTV 的网络层 dex 以 classes20/21.dex 塞进 app-debug.apk 并重签名
# 用法: python -I embed_hgtv_dex.py [in.apk] [out.apk] [--hg=134|136]
# HGTV dex 来源：
#   .trae/HGTV/apk_raw/（1.3.4 classes.dex+classes2.dex）
#   .trae/HGTV-136/apk_raw/（1.3.6 单 classes.dex，2026-11-10 起默认）
# 反射映射见 HongguoL1.kt（MAP_134/MAP_136，运行时自动探测）
import os
import shutil
import subprocess
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HGTV_134 = [os.path.join(ROOT, '.trae', 'HGTV', 'apk_raw', 'classes.dex'),
            os.path.join(ROOT, '.trae', 'HGTV', 'apk_raw', 'classes2.dex')]
HGTV_136 = [os.path.join(ROOT, '.trae', 'HGTV-136', 'apk_raw', 'classes.dex')]
SDK_BT = os.path.join(os.environ['LOCALAPPDATA'], 'Android', 'Sdk', 'build-tools', '36.1.0')
KEYSTORE = os.path.join(os.path.expanduser('~'), '.android', 'debug.keystore')

args = [a for a in sys.argv[1:] if not a.startswith('--')]
ver = '136'
for a in sys.argv[1:]:
    if a.startswith('--hg='):
        ver = a.split('=', 1)[1]

src = args[0] if len(args) > 0 else os.path.join(ROOT, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk')
dst = args[1] if len(args) > 1 else os.path.join(ROOT, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug-l1.apk')
hgtv_dex = HGTV_136 if ver == '136' else HGTV_134

for p in hgtv_dex:
    assert os.path.exists(p), 'missing ' + p
assert os.path.exists(src), 'missing ' + src
assert os.path.getmtime(src) > os.path.getmtime(__file__) - 3600, \
    'in.apk 过旧：assembleDebug 失败后勿直接跑本脚本（会装到旧包）'

# 1) 复制并塞 dex（zip 追加；classes20 起编号在宿主 dex 之后）
for stale in (dst, dst + '.aligned', dst + '.idsig'):
    if os.path.exists(stale):
        os.remove(stale)
shutil.copyfile(src, dst)
with zipfile.ZipFile(dst, 'a') as zf:
    zf.write(hgtv_dex[0], 'classes20.dex')
    if len(hgtv_dex) > 1:
        zf.write(hgtv_dex[1], 'classes21.dex')
print(f'embedded HGTV {ver} dex x{len(hgtv_dex)}')

# 2) zipalign
aligned = dst + '.aligned'
subprocess.run([os.path.join(SDK_BT, 'zipalign.exe'), '-f', '4', dst, aligned], check=True)
shutil.copyfile(aligned, dst)
os.remove(aligned)

# 3) apksigner debug 签名（v1+v2）
subprocess.run([
    os.path.join(SDK_BT, 'apksigner.bat'), 'sign',
    '--ks', KEYSTORE, '--ks-pass', 'pass:android',
    '--key-pass', 'pass:android',
    '--v1-signing-enabled', 'true', '--v2-signing-enabled', 'true',
    dst
], check=True)
print('signed -> ' + dst)
