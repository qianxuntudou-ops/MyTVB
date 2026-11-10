# 将 HGTV 的网络层 dex 以 classes20/21.dex 塞进 app-debug.apk 并重签名
# 用法: python -I embed_hgtv_dex.py [in.apk] [out.apk]
# HGTV dex 来源固定：.trae/HGTV/apk_raw/（用户已授权使用该参考项目）
import os
import shutil
import subprocess
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HGTV_DEX = [os.path.join(ROOT, '.trae', 'HGTV', 'apk_raw', 'classes.dex'),
            os.path.join(ROOT, '.trae', 'HGTV', 'apk_raw', 'classes2.dex')]
SDK_BT = os.path.join(os.environ['LOCALAPPDATA'], 'Android', 'Sdk', 'build-tools', '36.1.0')
KEYSTORE = os.path.join(os.path.expanduser('~'), '.android', 'debug.keystore')

src = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk')
dst = sys.argv[2] if len(sys.argv) > 2 else os.path.join(ROOT, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug-l1.apk')

for p in HGTV_DEX:
    assert os.path.exists(p), 'missing ' + p
assert os.path.exists(src), 'missing ' + src

# 1) 复制并塞 dex（zip 追加；classes20/21 编号在宿主 19 个 dex 之后）
for stale in (dst, dst + '.aligned', dst + '.idsig'):
    if os.path.exists(stale):
        os.remove(stale)
shutil.copyfile(src, dst)
with zipfile.ZipFile(dst, 'a') as zf:
    zf.write(HGTV_DEX[0], 'classes20.dex')
    zf.write(HGTV_DEX[1], 'classes21.dex')
print('embedded classes20/21.dex')

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
