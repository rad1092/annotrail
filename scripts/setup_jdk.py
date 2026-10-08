#!/usr/bin/env python3
"""CI-only exact Temurin 17.0.20.1+1 bootstrap; avoids four-part SemVer ambiguity."""
import hashlib, json, os, pathlib, platform, subprocess, tarfile, urllib.request, zipfile
if not os.environ.get('GITHUB_ENV') or not os.environ.get('GITHUB_PATH'):
    raise SystemExit('This helper is for GitHub CI. Local development uses your JAVA_HOME.')
root=pathlib.Path(__file__).resolve().parents[1]
arch={'arm64':'aarch64','aarch64':'aarch64','x86_64':'x64','amd64':'x64'}[platform.machine().lower()]
os_key={'Darwin':'mac','Linux':'linux','Windows':'windows'}[platform.system()]
asset=json.loads((root/'scripts'/'jdk-artifacts.json').read_text())[f'{arch}_{os_key}']
work=root/'.tools'/'ci-jdk'
work.mkdir(parents=True,exist_ok=False)
archive=work/('jdk.zip' if os_key=='windows' else 'jdk.tar.gz')
digest=hashlib.sha256();size=0
with urllib.request.urlopen(asset['url'],timeout=60) as response,archive.open('xb') as out:
    while block:=response.read(1024*1024):
        digest.update(block);size+=len(block);out.write(block)
assert size==asset['bytes'] and digest.hexdigest()==asset['sha256'],'JDK checksum/length mismatch'
if os_key=='windows':
    with zipfile.ZipFile(archive) as z:
        for entry in z.infolist():
            path=pathlib.PurePosixPath(entry.filename)
            assert not path.is_absolute() and '..' not in path.parts
        z.extractall(work)
else:
    with tarfile.open(archive) as t:t.extractall(work,filter='data')
archive.unlink()
jdks=[x for x in work.iterdir() if x.is_dir()]
assert len(jdks)==1,jdks
home=jdks[0]/'Contents'/'Home' if os_key=='mac' else jdks[0]
java=home/'bin'/('java.exe' if os_key=='windows' else 'java')
subprocess.run([str(java),'-version'],check=True)
release=(home/'release').read_text()
assert '17.0.20.1' in release and 'Temurin' in release,release
with open(os.environ['GITHUB_ENV'],'a',encoding='utf-8') as f:f.write(f'JAVA_HOME={home}\n')
with open(os.environ['GITHUB_PATH'],'a',encoding='utf-8') as f:f.write(f'{home / "bin"}\n')
print(f'Verified {os_key}/{arch} Temurin 17.0.20.1+1 SHA256 {asset["sha256"]}')
