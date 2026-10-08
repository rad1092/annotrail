#!/usr/bin/env python3
"""Create a platform-native portable app image with its Java runtime included."""
import argparse, hashlib, json, os, pathlib, platform, shutil, subprocess, tempfile, zipfile
from package_integrity import add_regular_tree, extract_regular_zip, materialize_runtime_legal_links, seal_macos_app, verify_macos_app
p=argparse.ArgumentParser()
p.add_argument('--skip-build',action='store_true')
a=p.parse_args()
root=pathlib.Path(__file__).resolve().parents[1]
java_home=pathlib.Path(os.environ['JAVA_HOME'])
maven=os.environ.get('ANNOTRAIL_MAVEN','mvn.cmd' if os.name=='nt' else 'mvn')
if not a.skip_build:
    subprocess.run([maven,'-B','verify'],cwd=root,check=True)
jar=root/'target'/'annotrail-0.1.2.jar'
if not jar.is_file():
    raise SystemExit(f'Missing built application: {jar}')
dist=root/'dist'
dist.mkdir(exist_ok=True)
image=dist/('Annotrail.app' if platform.system()=='Darwin' else 'Annotrail')
if image.exists():
    raise SystemExit('App image exists; review and remove the old generated dist image before rebuilding.')
jpackage=java_home/'bin'/('jpackage.exe' if os.name=='nt' else 'jpackage')
with tempfile.TemporaryDirectory(prefix='package-input-',dir=root/'target') as temporary:
    staging=pathlib.Path(temporary)
    shutil.copy2(jar,staging/jar.name)
    subprocess.run([str(jpackage),'--type','app-image','--name','Annotrail','--input',str(staging),'--main-jar',jar.name,'--main-class','net.whago.annotrail.Main','--dest',str(dist),'--app-version',('3' if platform.system()=='Darwin' else '0.1.2'),'--vendor','Annotrail contributors','--description','Reviewed PDF annotation transfer across revisions','--arguments','gui','--java-options','-Xmx512m','--add-modules','java.base,java.desktop,java.logging,java.xml,jdk.unsupported,jdk.charsets'],check=True)
# A separate jar keeps the portable CLI available even in headless environments.
shutil.copy2(jar,dist/jar.name)
for file in ('README.md','LICENSE','NOTICE','THIRD_PARTY_NOTICES.md'):
    shutil.copy2(root/file,dist/file)
shutil.copytree(root/'docs'/'licenses',dist/'licenses',dirs_exist_ok=True)
# Nothing inside the app may change after this final seal. jlink legal links
# must become regular files BEFORE signing, matching our portable ZIP format.
if platform.system() == 'Darwin':
    materialized = seal_macos_app(image)
else:
    # jlink also deduplicates legal documents on Linux; the same regular-file
    # archive format must be complete before ZIP creation on every platform.
    legal_path = 'runtime/legal' if platform.system() == 'Windows' else 'lib/runtime/legal'
    materialized = materialize_runtime_legal_links(image, legal_path)
name=f'annotrail-0.1.2-{platform.system().lower()}-{platform.machine().lower()}.zip'
archive=dist/name
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
    add_regular_tree(z, image, dist)
    add_regular_tree(z, dist/'licenses', dist)
    for file in ('annotrail-0.1.2.jar','README.md','LICENSE','NOTICE','THIRD_PARTY_NOTICES.md'):
        add_regular_tree(z, dist/file, dist)
if platform.system() == 'Darwin':
    # Verify the actual distributed bytes/types, not just the pre-ZIP image.
    with tempfile.TemporaryDirectory(prefix='package-roundtrip-', dir=root/'target') as td:
        extracted = pathlib.Path(td)
        extract_regular_zip(archive, extracted)
        verify_macos_app(extracted/'Annotrail.app')
checks={f.name:hashlib.sha256(f.read_bytes()).hexdigest() for f in (archive,dist/jar.name)}
(dist/'SHA256SUMS').write_text(''.join(f'{digest}  {name}\n' for name,digest in checks.items()))
print(json.dumps({'archive':str(archive),'checksums':checks,'materializedLegalLinks':materialized},indent=2))
