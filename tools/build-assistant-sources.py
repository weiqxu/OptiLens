#!/usr/bin/env python3
"""Build a reproducible source pack from clean, pinned upstream checkouts.

No network access. Input checkouts must contain the full core Java source roots.
The ZIP includes upstream license/notice files and SHA-256 file provenance.
"""
import argparse, hashlib, json, pathlib, subprocess, zipfile, re
PINS = {
 'calcite': ('1.39.0', '0b328db78cca67b7b3407e7bcbeb59061862dd40', 'core/src/main/java', 'apache/calcite'),
 'starrocks': ('4.0.14', '7c18dd9dded1587dad24d912dcf8e777daf919f7', 'fe/fe-core/src/main/java/com/starrocks/sql/optimizer', 'StarRocks/starrocks')}
def build(roots, output):
 entries, manifest = {}, []
 for engine, root in roots.items():
  version, commit, prefix, repo = PINS[engine]
  actual = subprocess.check_output(['git','-C',str(root),'rev-parse','HEAD'],text=True).strip()
  if actual != commit: raise ValueError(f'{engine}: expected {commit}, got {actual}')
  subprocess.run(['git','-C',str(root),'diff','--exit-code','HEAD','--',prefix],check=True,stdout=subprocess.DEVNULL)
  paths = subprocess.check_output(['git','-C',str(root),'ls-tree','-r','--name-only','HEAD','--',prefix],text=True).splitlines()
  for path in paths:
   if not path.endswith('.java'): continue
   data = subprocess.check_output(['git','-C',str(root),'show',f'HEAD:{path}'])
   sha = hashlib.sha256(data).hexdigest(); ident = engine+'-'+hashlib.sha256(path.encode()).hexdigest()[:20]
   entries['files/'+ident+'.java'] = data
   manifest.append(dict(id=ident,database=engine,version=version,commit=commit,path=path,sha256=sha,
                        url=f'https://github.com/{repo}/blob/{commit}/{path}'))
  for path in ('LICENSE','NOTICE','LICENSE.txt','NOTICE.txt'):
   found = subprocess.run(['git','-C',str(root),'show',f'HEAD:{path}'],capture_output=True)
   if found.returncode == 0: entries[engine+'/'+path] = found.stdout
 # Lexical class references include same-package bases and imported helpers. They
 # aid retrieval; they are not a complete semantic dependency graph.
 for item in manifest:
  text = entries['files/'+item['id']+'.java'].decode()
  package = re.search(r'^package ([\w.]+);', text, re.M)
  item['className'] = (package.group(1)+'.' if package else '')+pathlib.Path(item['path']).stem
  names = set(re.findall(r'\b[A-Z][A-Za-z0-9_]*\b', text))
  item['relatedPaths'] = sorted(other['path'] for other in manifest if other['database']==item['database'] and other['id']!=item['id'] and pathlib.Path(other['path']).stem in names)
 entries['index.json'] = json.dumps(manifest,sort_keys=True,ensure_ascii=True,separators=(',',':')).encode()
 output.parent.mkdir(parents=True,exist_ok=True)
 with zipfile.ZipFile(output,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as z:
  for name, data in sorted(entries.items()):
   info=zipfile.ZipInfo(name,(2026,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=0o644<<16
   z.writestr(info,data)
 print(f'{len(manifest)} indexed files; SHA-256 {hashlib.sha256(output.read_bytes()).hexdigest()}')
if __name__=='__main__':
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--calcite',required=True,type=pathlib.Path);p.add_argument('--starrocks',required=True,type=pathlib.Path);p.add_argument('--output',type=pathlib.Path,default=pathlib.Path('src/main/resources/assistant/sources.zip'));a=p.parse_args();build({'calcite':a.calcite,'starrocks':a.starrocks},a.output)
