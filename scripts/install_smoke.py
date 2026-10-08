#!/usr/bin/env python3
"""Exercise the distributable JAR or extracted native app, using synthetic PDFs."""
import argparse, hashlib, json, os, pathlib, platform, subprocess, tempfile, zipfile
p=argparse.ArgumentParser()
p.add_argument('--archive',type=pathlib.Path)
a=p.parse_args()
root=pathlib.Path(__file__).resolve().parents[1]
java=pathlib.Path(os.environ['JAVA_HOME'])/'bin'/('java.exe' if os.name=='nt' else 'java')
jar=root/'target'/'annotrail-0.1.1.jar'
(root/'target').mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix='annotrail-smoke-',dir=root/'target') as td:
    tmp=pathlib.Path(td)
    fixture=tmp/'fixture'
    subprocess.run([str(java),'-Djava.awt.headless=true','-cp',os.pathsep.join([str(root/'target'/'test-classes'),str(jar)]),'net.whago.annotrail.FixtureGenerator',str(fixture)],check=True)
    command=[str(java),'-Djava.awt.headless=true','-Xmx512m','-jar',str(jar)]
    if a.archive:
        with zipfile.ZipFile(a.archive) as z:
            for info in z.infolist():
                member=pathlib.PurePosixPath(info.filename)
                assert not member.is_absolute() and '..' not in member.parts
            z.extractall(tmp/'installed')
            if os.name!='nt':
                for info in z.infolist():
                    mode=(info.external_attr>>16)&0o777
                    if mode: (tmp/'installed'/info.filename).chmod(mode)
        rel={'Darwin':'Annotrail.app/Contents/MacOS/Annotrail','Windows':'Annotrail/Annotrail.exe','Linux':'Annotrail/bin/Annotrail'}[platform.system()]
        command=[str(tmp/'installed'/rel)]
    def run(args,code=0):
        r=subprocess.run(command+list(map(str,args)),cwd=tmp,text=True,capture_output=True,timeout=90)
        assert r.returncode==code,(r.returncode,r.stdout,r.stderr)
        return r
    assert '0.1.1' in run(['--version']).stdout
    run(['--help'])
    old,new=fixture/'old.pdf',fixture/'new.pdf'
    hashes=[hashlib.sha256(x.read_bytes()).hexdigest() for x in (old,new)]
    plan=tmp/'plan.json'
    run(['analyze','--old',old,'--new',new,'--plan',plan],1)
    data=json.loads(plan.read_text())
    assert [e['status'] for e in data['entries']]==['confident','ambiguous','removed','unsupported'],data
    output,report=tmp/'reviewed.pdf',tmp/'report.json'
    args=['export','--old',old,'--new',new,'--plan',plan,'--accept-confident','--output',output,'--report',report]
    run(args)
    result=json.loads(report.read_text())['result']
    assert result['transferred']==1 and result['skipped']==3,result
    assert output.read_bytes().startswith(b'%PDF')
    run(args,2) # cannot overwrite either completed artifact
    data['entries'][0]['candidates'][0]['quads'][0]+=10
    tampered=tmp/'tampered.json';tampered.write_text(json.dumps(data))
    run(['export','--old',old,'--new',new,'--plan',tampered,'--accept-confident','--output',tmp/'bad.pdf','--report',tmp/'bad.json'],2)
    assert not (tmp/'bad.pdf').exists() and not (tmp/'bad.json').exists()
    assert hashes==[hashlib.sha256(x.read_bytes()).hexdigest() for x in (old,new)]
print('PASS: installed version/help, analyze, reviewed export, stale-plan refusal, overwrite refusal, originals unchanged, temporary install removed')
