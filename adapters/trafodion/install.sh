#!/usr/bin/env bash
set -euo pipefail
if [[ $# -ne 2 ]]; then
  echo "Usage: $0 /path/to/Trafodion-Lite /path/to/runtime-home" >&2
  exit 2
fi
source_root=$(cd "$1" && pwd)
mkdir -p "$2"
runtime_home=$(cd "$2" && pwd)
project_root=$(cd "$(dirname "$0")/../.." && pwd)
binary="$source_root/core/sql/lib/linux/64bit/debug/trafodion-lite-optimizer"
if [[ ! -x "$binary" ]]; then
  echo "Build Trafodion-Lite first: OMPI_CXX=/usr/bin/g++ make -j4 lite" >&2
  exit 1
fi
if [[ ! -f "$project_root/target/optilens.jar" ]]; then
  echo "Package OptiLens first: mvn package -DskipTests" >&2
  exit 1
fi
export TRAF_HOME="$source_root/core/sqf"
export LD_LIBRARY_PATH="$source_root/core/sql/lib/linux/64bit/debug:$TRAF_HOME/export/lib64d:${LD_LIBRARY_PATH:-}"
identity=$(mktemp)
smoke=$(mktemp -d)
trap 'rm -f "$identity"; rm -rf "$smoke"' EXIT
"$binary" --identity "$identity"
python3 - "$source_root" "$binary" "$identity" "$project_root" <<'PYTHON'
import hashlib,json,pathlib,sys
root,binary,identity,project=map(pathlib.Path,sys.argv[1:])
data=json.loads(identity.read_text())
assert data['protocolVersion']==1 and data['engine']=='trafodion'
assert data['inputModes']==['sql','plan']
assert len(data['sourceCommit'])==40
catalog=json.loads((project/'src/main/resources/trafodion-catalog.json').read_text())
for path,checksum in catalog['sources'].items():
    assert hashlib.sha256((root/path).read_bytes()).hexdigest()==checksum, 'Rule source differs: '+path
PYTHON
# A real compile must succeed before this installation becomes selectable.
python3 - "$project_root" "$smoke/input.json" <<'PYTHON'
import json,pathlib,sys
# This query deliberately names a table that exists only in the request schema.
request={'database':'trafodion','inputMode':'sql', 'sql':'SELECT id FROM optilens_probe WHERE id > 1',
         'schema':[{'name':'optilens_probe','columns':[{'name':'id','type':'INTEGER','nullable':False}]}],
         'rules':[], 'options':{'matchLimit':10}}
pathlib.Path(sys.argv[2]).write_text(json.dumps({'request':request,'observedRules':[]}))
PYTHON
(cd "$smoke" && TRAF_LITE_STORE_DIR="$smoke/store" timeout 25s "$binary" "$smoke/input.json" "$smoke/output.json")
python3 - "$smoke/output.json" <<'PYTHON'
import json,sys
result=json.load(open(sys.argv[1]))
assert not result.get('error'), result.get('error')
assert result['initial']['nodes'] and result['finalPlan']['nodes']
PYTHON
mkdir -p "$runtime_home/adapter/classes"
find "$project_root/adapters/trafodion/src/main/java" -name '*.java' -print > "$runtime_home/adapter/sources.txt"
javac --release 17 -cp "$project_root/target/optilens.jar" -d "$runtime_home/adapter/classes" @"$runtime_home/adapter/sources.txt"
jar --create --file "$runtime_home/adapter/optilens-trafodion.jar" -C "$runtime_home/adapter/classes" .
python3 - "$source_root" "$binary" "$identity" "$runtime_home" <<'PYTHON'
import hashlib,json,pathlib,sys
root,binary,identity,home=map(pathlib.Path,sys.argv[1:])
data=json.loads(identity.read_text())
libraries={}
for directory in [root/'core/sql/lib/linux/64bit/debug', root/'core/sqf/export/lib64d']:
    for library in directory.glob('*.so'):
        resolved=library.resolve()
        if resolved.is_relative_to(root) and resolved.is_file():
            libraries[str(resolved)]=hashlib.sha256(resolved.read_bytes()).hexdigest()
bridge=home/'adapter/optilens-trafodion.jar'
data.update(sourceRoot=str(root),binary=str(binary),binarySha256=hashlib.sha256(binary.read_bytes()).hexdigest(),
            libraries=libraries,bridgeSha256=hashlib.sha256(bridge.read_bytes()).hexdigest())
fingerprint=data['binarySha256']+'\n'+data['bridgeSha256']+'\n'
fingerprint+=''.join(path+'='+checksum+'\n' for path,checksum in sorted(libraries.items()))
data['buildSha256']=hashlib.sha256(fingerprint.encode()).hexdigest()
(home/'provenance.json').write_text(json.dumps(data,indent=2)+'\n')
PYTHON
printf 'Installed. Set OPTILENS_TRAFODION_HOME=%s before starting OptiLens.\n' "$runtime_home"
