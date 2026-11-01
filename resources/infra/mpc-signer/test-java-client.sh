#!/usr/bin/env bash
set -euo pipefail

MPC_JAVA_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
mvn -q -f "$MPC_JAVA_ROOT/pom.xml" -pl common -am test

# Use the reactor's actual classpath. Resolving the snapshot SDK separately can
# select a stale installed POM, and is unnecessary after this test build.
python3 - "$MPC_JAVA_ROOT" <<'VERIFY'
import os
import pathlib
import subprocess
import sys
import xml.etree.ElementTree as ET

root = pathlib.Path(sys.argv[1])
probe = root / 'common/target/test-classes/com/surprising/wallet/common/signing/MpcClientNativeProbe.class'
if not probe.is_file():
    raise SystemExit('Java native probe was not compiled')
classpath = None
reports = sorted((root / 'common/target/surefire-reports').glob('TEST-*.xml'),
                 key=lambda path: path.stat().st_mtime, reverse=True)
for path in reports:
    suite = ET.parse(path).getroot()
    if int(suite.get('errors', '0')) or int(suite.get('failures', '0')):
        raise SystemExit('common test reports contain failures')
    for prop in suite.findall('./properties/property'):
        if prop.get('name') == 'java.class.path' and 'common/target/test-classes' in prop.get('value', ''):
            classpath = prop.get('value')
            break
    if classpath:
        break
if not classpath:
    raise SystemExit('Actual Java reactor test classpath was not found')
env = os.environ.copy()
env['MPC_JAVA_TEST_CLASSPATH'] = classpath
result = subprocess.run(['bash', str(root / 'resources/infra/mpc-signer/build-and-test.sh')], env=env)
raise SystemExit(result.returncode)
VERIFY
