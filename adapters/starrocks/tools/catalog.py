#!/usr/bin/env python3
"""Build a complete source inventory with explicit, reproducible runtime evidence."""
import argparse,json
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('inventory',type=Path);p.add_argument('resources',type=Path);p.add_argument('--audit',type=Path);a=p.parse_args()
inv=json.loads(a.inventory.read_text());audit=json.loads(a.audit.read_text()) if a.audit else {'entries':[]}
evidence={e['id']:e for e in audit['entries']};rules=[];coverage=[];examples=[]
base='https://github.com/StarRocks/starrocks/blob/'+inv['commit']+'/'
for c in inv['classes']:
 name=c['className'].rsplit('.',1)[1];id='StarRocks.'+name;e=evidence.get(id,{})
 available=e.get('available',True);reason=e.get('reason','Runtime verification has not been completed for this implementation.')
 if name=='PruneEmptyScanRule':reason='Disabled for schema-only experiments: missing tablet selection does not establish that a real table is empty.'
 rules.append(dict(id=id,title=name,description='Native StarRocks '+c['family']+' rule. '+('Rule types: '+', '.join(c['ruleTypes'])+'.' if c['ruleTypes'] else 'This implementation is outside the RuleType enumeration.'),conditions=reason,implementations=[c['className']],source=base+c['path'],category={'relational':'Relational rules','scalar':'Scalar expression rules','tree':'Whole-tree rules','materialized-view':'Materialized view rules'}[c['family']],runnable=available,unavailableReason='' if available else reason))
 coverage.append(dict(id=id,status=e.get('status','not-verified'),reason=reason))
 if e.get('example'):examples.append(e['example'])
referenced={t for c in inv['classes'] for t in c['ruleTypes']} | {t for e in audit['entries'] for t in e.get('nativeTypes',[])}
for t in inv['ruleTypes']:
 if t in referenced:continue
 id='RuleType.'+t;reason='This enum entry is not mapped to a concrete implementation by source references or runtime inspection. It is not exposed as an executable rule.'
 rules.append(dict(id=id,title=t,description='StarRocks RuleType enum entry.',conditions=reason,implementations=[],source=base+inv['ruleTypeSource'],category='Unmapped enum entries',runnable=False,unavailableReason=reason));coverage.append(dict(id=id,status='unmapped-enum',reason=reason))
operators={'TableScan':0,'Values':0,'Filter':1,'Project':1,'Join':2,'Aggregate':1,'Sort':1,'Union':-1,'Intersect':-1,'Minus':-1}
cap=dict(planVersion=1,operators=operators,expressions=['+','-','*','/','=','<>','<','<=','>','>=','AND','OR','NOT','IS NULL','IS NOT NULL','IS NOT DISTINCT FROM','COALESCE','CASE'],aggregates=['COUNT','SUM','AVG','MIN','MAX'],types=['INTEGER','BIGINT','DOUBLE','BOOLEAN','DATE','TIMESTAMP','VARCHAR','DECIMAL'],limits=dict(nodes=250,depth=40,expressionDepth=32,expressionItems=2000,valueRows=1000),parameters=dict(joinTypes=['INNER','LEFT','RIGHT','FULL','SEMI','ANTI'],distributions=[],sortDirections=['ASC','DESC'],nullOrdering=['FIRST','LAST'],decimal=dict(precisionMin=1,precisionMax=38,defaultPrecision=18,defaultScale=2),varchar=dict(lengthMin=1,lengthMax=65536,defaultLength=255),timestampPrecision=0),notes=['Native results are currently read-only.','INTERSECT and EXCEPT require DISTINCT. Aggregate FILTER and SUM0 are not supported.','Exact expression signatures are required; insert CAST explicitly.'])
report={**audit,'version':inv['version'],'commit':inv['commit'],'scope':inv['scope'],'ruleTypeCount':len(inv['ruleTypes']),'implementationCount':len(inv['classes']),'catalogCount':len(rules),'unmappedEnumEntries':[r['id'] for r in rules if r['category']=='Unmapped enum entries'],'coverage':coverage}
data=dict(rules=rules,coverage=coverage,scope=inv['scope'],capabilities=cap,verification=report)
a.resources.mkdir(parents=True,exist_ok=True);(a.resources/'starrocks-catalog.json').write_text(json.dumps(data,indent=2)+'\n');(a.resources/'starrocks-examples.json').write_text(json.dumps(examples,indent=2)+'\n')
print(f'Catalog: {len(rules)} entries, {len(examples)} verified examples')
