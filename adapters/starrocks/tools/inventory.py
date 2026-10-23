#!/usr/bin/env python3
"""Inventory the pinned frontend rule families; source references are not runtime verification."""
import argparse,hashlib,json,re,subprocess
from pathlib import Path
VERSION='4.0.14'
COMMIT='7c18dd9dded1587dad24d912dcf8e777daf919f7'
ROOTS={'Rule':'relational','ScalarOperatorRewriteRule':'scalar','TreeRewriteRule':'tree','IMaterializedViewRewriteRule':'materialized-view'}

def inventory(source):
    actual=subprocess.check_output(['git','-C',str(source),'rev-parse','HEAD'],text=True).strip()
    if actual!=COMMIT:raise ValueError('Source commit does not match the pinned release')
    if subprocess.run(['git','-C',str(source),'diff','--quiet','HEAD','--','fe/fe-core/src']).returncode:raise ValueError('Pinned FE source has local modifications')
    base=source/'fe/fe-core/src/main/java';optimizer=base/'com/starrocks/sql/optimizer'
    classes={}
    for path in sorted(optimizer.rglob('*.java')):
        text=path.read_text();clean=re.sub(r'/\*.*?\*/|//[^\n]*','',text,flags=re.S)
        name=path.stem
        header=re.search(r'\bpublic\s+(?:(abstract|final)\s+)?class\s+'+re.escape(name)+r'\b([^\{]*)\{',clean)
        if not header:continue
        package=re.search(r'\bpackage\s+([\w.]+);',clean).group(1)
        parents=[]
        extends=re.search(r'\bextends\s+([\w.]+)',header.group(2))
        if extends:parents.append(extends.group(1))
        implements=re.search(r'\bimplements\s+(.+)',header.group(2))
        if implements:parents+=re.findall(r'\b\w+\b',re.sub(r'<.*?>','',implements.group(1)))
        classes[name]={'className':package+'.'+name,'abstract':header.group(1)=='abstract','parents':parents,'path':str(path.relative_to(source)),
                       'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'ruleTypes':sorted(set(re.findall(r'RuleType\.([A-Z][A-Z_0-9]+)',clean)))}
    def family(name,seen=None):
        if name in ROOTS:return ROOTS[name]
        seen=set() if seen is None else seen
        if name in seen:return None
        seen.add(name)
        for parent in classes.get(name,{}).get('parents',[]):
            value=family(parent,seen)
            if value:return value
    testroot=source/'fe/fe-core/src/test/java'
    tests=[(str(path.relative_to(source)),path.read_text()) for path in testroot.rglob('*.java')]
    result=[]
    for name,item in classes.items():
        kind=family(name)
        if not kind or item['abstract']:continue
        item={**item,'family':kind,'tests':[path for path,text in tests if re.search(r'\b'+re.escape(name)+r'\b',text)]}
        result.append(item)
    enum=optimizer/'rule/RuleType.java'
    enumtext=re.sub(r'/\*.*?\*/|//[^\n]*','',enum.read_text(),flags=re.S)
    types=re.findall(r'^\s*((?:TF_|IMP_|GP_)[A-Z_0-9]+)\s*[,;]',enumtext,re.M)
    return {'version':VERSION,'commit':COMMIT,'scope':'All TF_, IMP_, and GP_ RuleType entries plus concrete Rule, ScalarOperatorRewriteRule, TreeRewriteRule, and IMaterializedViewRewriteRule implementations under the pinned FE optimizer source. Enum sentinels and abstract bases are excluded. Source test references do not claim that tests were executed.',
            'ruleTypes':types,'classes':result,'ruleTypeSource':str(enum.relative_to(source)),'ruleTypeSha256':hashlib.sha256(enum.read_bytes()).hexdigest()}

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('source',type=Path);p.add_argument('output',type=Path);args=p.parse_args()
    data=inventory(args.source);args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(data,indent=2)+'\n')
    print(f"Inventoried {len(data['ruleTypes'])} RuleType entries and {len(data['classes'])} concrete implementations at {COMMIT}")
