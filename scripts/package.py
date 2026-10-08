#!/usr/bin/env python3
"""Create a platform-native portable app image with its Java runtime included."""
import argparse, hashlib, json, os, pathlib, platform, shutil, subprocess, zipfile
p=argparse.ArgumentParser()
p.add_argument('--skip-build',action='store_true')
a=p.parse_args()
root=pathlib.Path(__file__).resolve().parents[1]
java_home=pathlib.Path(os.environ['JAVA_HOME'])
maven=os.environ.get('ANNOTRAIL_MAVEN','mvn.cmd' if os.name=='nt' else 'mvn')
if not a.skip_build:
    subprocess.run([maven,'-B','verify'],cwd=root,check=True)
jar=root/'target'/'annotrail-0.1.0.jar'
assert jar.is_file(),jar
dist=root/'dist'
dist.mkdir(exist_ok=True)
staging=root/'target'/'package-input'
staging.mkdir(exist_ok=True)
shutil.copy2(jar,staging/jar.name)
image=dist/('Annotrail.app' if platform.system()=='Darwin' else 'Annotrail')
if image.exists():
    raise SystemExit('App image exists; review and remove the old generated dist image before rebuilding.')
jpackage=java_home/'bin'/('jpackage.exe' if os.name=='nt' else 'jpackage')
subprocess.run([str(jpackage),'--type','app-image','--name','Annotrail','--input',str(staging),'--main-jar',jar.name,'--main-class','net.whago.annotrail.Main','--dest',str(dist),'--app-version',('1' if platform.system()=='Darwin' else '0.1.0'),'--vendor','Annotrail contributors','--description','Reviewed PDF annotation transfer across revisions','--arguments','gui','--java-options','-Xmx512m','--add-modules','java.base,java.desktop,java.logging,java.xml,jdk.unsupported,jdk.charsets'],check=True)
# A separate jar keeps the portable CLI available even in headless environments.
shutil.copy2(jar,dist/jar.name)
for file in ('README.md','LICENSE','NOTICE','THIRD_PARTY_NOTICES.md'):
    shutil.copy2(root/file,dist/file)
shutil.copytree(root/'docs'/'licenses',dist/'licenses',dirs_exist_ok=True)
name=f'annotrail-0.1.0-{platform.system().lower()}-{platform.machine().lower()}.zip'
archive=dist/name
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
    for file in sorted(image.rglob('*')):
        if file.is_file():
            # ZipInfo preserves POSIX executable mode for macOS/Linux launchers.
            z.write(file,file.relative_to(dist))
    for file in sorted((dist/'licenses').rglob('*')):
        if file.is_file(): z.write(file,file.relative_to(dist))
    for file in ('annotrail-0.1.0.jar','README.md','LICENSE','NOTICE','THIRD_PARTY_NOTICES.md'):
        z.write(dist/file,file)
checks={f.name:hashlib.sha256(f.read_bytes()).hexdigest() for f in (archive,dist/jar.name)}
(dist/'SHA256SUMS').write_text(''.join(f'{digest}  {name}\n' for name,digest in checks.items()))
print(json.dumps({'archive':str(archive),'checksums':checks},indent=2))
