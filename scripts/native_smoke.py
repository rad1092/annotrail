#!/usr/bin/env python3
"""Bound a real Swing-window workflow driven inside the test process, without OS input."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

root = Path(__file__).resolve().parents[1]
java = Path(os.environ['JAVA_HOME']) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
jar = root / 'target' / 'annotrail-0.1.2.jar'
parent = root / 'target' / 'native-smoke'
parent.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory(prefix='run-', dir=parent) as temporary:
    evidence = Path(temporary) / 'evidence'
    command = [str(java), '-Xmx512m', '-Djava.awt.headless=false', '-cp',
               os.pathsep.join([str(root / 'target' / 'test-classes'), str(jar)]),
               'net.whago.annotrail.NativeReviewSmoke', str(evidence)]
    if sys.platform.startswith('linux') and not os.environ.get('DISPLAY'):
        xvfb = shutil.which('xvfb-run')
        if not xvfb:
            raise SystemExit('A desktop DISPLAY or xvfb-run is required for the native window smoke.')
        command = [xvfb, '--auto-servernum', '--server-args=-screen 0 1440x1080x24', *command]
    result = subprocess.run(command, cwd=root, text=True, capture_output=True, timeout=60)
    (parent / 'stdout.log').write_text(result.stdout, encoding='utf-8')
    (parent / 'stderr.log').write_text(result.stderr, encoding='utf-8')
    for name in ('result.json', 'internal-swing-render.png'):
        if (evidence / name).is_file():
            shutil.copy2(evidence / name, parent / name)
    if result.returncode:
        raise SystemExit(f'Native workflow failed ({result.returncode}); see {parent}')
    record = json.loads((parent / 'result.json').read_text(encoding='utf-8'))
    if record.get('result') != 'PASS' or record.get('ownWindowDisposed') is not True:
        raise SystemExit('Native workflow did not confirm success and its own window cleanup.')
    print('PASS: real native Swing window, internal review/export/reopen workflow, source preservation, own-window cleanup; OS input and file chooser unverified')
