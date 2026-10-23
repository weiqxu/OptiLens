#!/usr/bin/env python3
"""Deterministic native input corpus. A fixture is not verified until its target emits a change."""
import json,sys,copy
schema=[dict(name='customers',columns=[dict(name='id',type='INTEGER',nullable=False),dict(name='name',type='VARCHAR'),dict(name='city',type='VARCHAR')]),dict(name='orders',columns=[dict(name='id',type='INTEGER',nullable=False),dict(name='customer_id',type='INTEGER'),dict(name='amount',type='DOUBLE')])]
fixtures=[]
def ref(i=0,input=0):return dict(kind='ref',input=input,index=i)
def lit(v,t='INTEGER'):return dict(kind='literal',type=t,value=v,nullable=v is None)
def call(op,*args):return dict(kind='call',op=op,args=list(args))
def n(op,*inputs,**attrs):return dict(op=op,children=inputs,attributes=attrs)
def scan(table='customers'):return n('TableScan',table=table)
def vals(rows=((1,),(2,)),width=1):return n('Values',columns=[dict(name='id' if i==0 else 'v'+str(i),type='INTEGER',nullable=True) for i in range(width)],rows=[list(r) for r in rows])
def fil(child,predicate=None):return n('Filter',child,condition=predicate or call('>',ref(),lit(1)))
def project(child,*exprs):return n('Project',child,projects=[dict(name='id' if i==0 else 'v'+str(i),expr=e) for i,e in enumerate(exprs or [ref()])])
def join(left=None,right=None,j='INNER',predicate=None):return n('Join',left or scan(),right or scan('orders'),joinType=j,condition=predicate or call('=',ref(),ref(1,1)))
def agg(child,group=(),fn='COUNT',args=(),distinct=False):return n('Aggregate',child,groupKeys=list(group),calls=[dict(function=fn,args=list(args),name='total',distinct=distinct)])
def sort(child,fetch=2,keys=None):return n('Sort',child,keys=keys or [],offset=0,fetch=fetch)
def manual(title,root):
 nodes=[]
 def emit(node):
  inputs=[emit(c) for c in node['children']];id='n'+str(len(nodes)+1);nodes.append(dict(id=id,op=node['op'],inputs=inputs,attributes=node['attributes']));return id
 rid=emit(root);fixtures.append(dict(id='plan-'+str(len(fixtures)),title=title,note='Native manual-plan fixture.',input=dict(database='starrocks',inputMode='plan',sql=None,schema=schema,rules=[],plan=dict(planVersion=1,rootId=rid,nodes=nodes,layout={}),options=dict(matchLimit=2))))
manual('Merge adjacent filters',fil(fil(scan()),call('<',ref(),lit(10))))
manual('Remove a true filter',fil(scan(),lit(True,'BOOLEAN')))
manual('Fold arithmetic',project(scan(),call('*',lit(10),lit(20))))
manual('Merge adjacent projections',project(project(scan(),ref()),ref()))
manual('Prune identity projection',project(scan(),ref(),ref(1),ref(2)))
manual('Filter a projection',fil(project(scan(),ref())))
manual('Project a filter',project(fil(scan()),ref()))
for j in ['INNER','LEFT','RIGHT','FULL','SEMI','ANTI']:
 manual('Native '+j+' join',join(j=j))
 manual('Filter above '+j+' join',fil(join(j=j)))
 manual('Projection above '+j+' join',project(join(j=j),ref()))
 manual('Limit above '+j+' join',sort(join(j=j)))
 manual('Empty input '+j+' join',join(vals(rows=[]),vals(),j,call('=',ref(),ref(0,1))))
manual('Three-way join',join(join(),scan(),predicate=call('=',ref(),ref(0,1))))
manual('Left nested join',join(scan(),join(),predicate=call('=',ref(),ref(0,1))))
manual('Join with one constant row',join(vals(rows=[[1]]),vals(),predicate=call('=',ref(),ref(0,1))))
manual('Join with expression',join(predicate=call('=',call('+',ref(),lit(1)),ref(1,1))))
manual('Join with disjunction',join(predicate=call('OR',call('=',ref(),ref(1,1)),call('=',ref(),ref(0,1)))))
for op in ['Union','Intersect','Minus']:
 for empty in [False,True]:
  tree=n(op,vals(),vals(rows=[] if empty else [[2],[3]]),all=op=='Union')
  manual(op+(' empty input' if empty else ''),tree)
  manual('Filter above '+op,fil(tree));manual('Limit above '+op,sort(tree));manual('Project above '+op,project(tree,ref()))
manual('Single-input union',n('Union',vals(),all=True))
for fn in ['COUNT','SUM','AVG','MIN','MAX']:
 for distinct in [False,True]:
  tree=agg(scan(),fn=fn,args=[0],distinct=distinct)
  manual(fn+(' DISTINCT' if distinct else ''),tree)
  manual('Filter above '+fn,fil(tree,call('>',ref(),lit(1,'BIGINT' if fn in ['COUNT','SUM'] else 'INTEGER'))))
manual('Group by',agg(scan(),group=[0],args=[0]));manual('Filter group key',fil(agg(scan(),group=[0],args=[0])))
manual('Aggregate projection',agg(project(scan(),ref()),args=[0]))
manual('Adjacent aggregation',agg(agg(scan(),group=[0],args=[0]),group=[0],args=[1],fn='SUM'))
manual('Aggregate join',agg(join(),group=[0],args=[0]))
for fetch in [0,2]:
 for child in [scan(),fil(scan()),project(scan(),ref()),vals(),sort(scan())]:manual('Limit '+str(fetch),sort(child,fetch))
manual('Order by with limit',sort(scan(),2,[dict(index=0,direction='ASC',nulls='LAST')]))
manual('Limit over sort',sort(sort(scan(),None,[dict(index=0,direction='ASC',nulls='LAST')]),2))
manual('Sort filtered constant key',sort(fil(scan(),call('=',ref(),lit(2))),2,[dict(index=0,direction='ASC',nulls='LAST')]))
for expr in [call('AND',lit(True,'BOOLEAN'),call('=',ref(),lit(1))),call('OR',lit(False,'BOOLEAN'),call('=',ref(),lit(1))),call('=',lit(1),ref()),call('=',lit(1),lit(2)),call('IS NULL',lit(None)),call('CASE',lit(True,'BOOLEAN'),lit(1),lit(2)),dict(kind='cast',type='INTEGER',expr=ref()),call('OR',call('AND',call('>',ref(),lit(1)),call('<',ref(),lit(5))),call('AND',call('>',ref(),lit(1)),call('=',ref(),lit(9))))]:manual('Structured scalar expression',project(vals(),expr))
manual('Empty projection',n('Project',scan(),projects=[]))
manual('Filter empty values',fil(vals(rows=[])))
manual('Project empty values',project(vals(rows=[]),ref()))
manual('Grouped aggregate over empty values',agg(vals(rows=[]),group=[0]))
manual('Sort empty values',sort(vals(rows=[])))
manual('Constant grouping key',agg(project(scan(),ref(),lit(7)),group=[0,1],args=[0]))
manual('Duplicate grouping keys',agg(project(scan(),ref(),ref()),group=[0,1],args=[0]))
manual('Distinct inner join',n('Aggregate',join(),groupKeys=[0],calls=[]))
manual('Duplicate-insensitive outer aggregate',agg(join(j='LEFT'),group=[0],fn='MIN',args=[0]))
for index in [0,3,4]:
 for j in ['INNER','LEFT','SEMI','ANTI']:
  manual('Join association '+j+str(index),join(join(),scan(),j,call('=',ref(index),ref(0,1))))
manual('Aggregate union',agg(n('Union',vals(),vals(),all=True),group=[0],args=[0],fn='SUM'))
manual('Nested union',n('Union',n('Union',vals(),vals(),all=True),vals(),all=True))
manual('Duplicate aggregate expressions',n('Aggregate',scan(),groupKeys=[],calls=[dict(function='COUNT',args=[],name='count1'),dict(function='COUNT',args=[],name='count2')]))
queries=[
'SELECT 1 + 2 AS total','SELECT * FROM customers','SELECT id FROM customers WHERE id > 1','SELECT id FROM customers WHERE id > 1 AND id > 2',
'SELECT id FROM customers WHERE id IN (1,2,3)','SELECT id FROM customers WHERE id IN (SELECT customer_id FROM orders)',
'SELECT id FROM customers WHERE EXISTS (SELECT 1 FROM orders WHERE customer_id=customers.id)',
'SELECT id,(SELECT MAX(amount) FROM orders WHERE customer_id=customers.id) AS maximum FROM customers',
'SELECT id FROM customers WHERE id NOT IN (SELECT customer_id FROM orders)',
'SELECT id FROM customers WHERE id = (SELECT MAX(customer_id) FROM orders)',
'SELECT city,COUNT(*) FROM customers GROUP BY city','SELECT customer_id,SUM(amount),AVG(amount),MIN(amount),MAX(amount) FROM orders GROUP BY customer_id',
'SELECT COUNT(DISTINCT customer_id),COUNT(DISTINCT amount) FROM orders','SELECT SUM(CASE WHEN amount>100 THEN 1 ELSE 0 END) FROM orders',
'SELECT SUM(amount+1) FROM orders','SELECT MIN(customer_id+1),MAX(customer_id+1) FROM orders',
'SELECT customer_id,COUNT(*) FROM orders GROUP BY ROLLUP(customer_id)','SELECT customer_id,COUNT(*) FROM orders GROUP BY CUBE(customer_id)',
'SELECT id,ROW_NUMBER() OVER (PARTITION BY city ORDER BY id) AS rn FROM customers',
'SELECT * FROM (SELECT id,ROW_NUMBER() OVER (ORDER BY id) AS rn FROM customers) t WHERE rn<=2',
'SELECT id,SUM(id) OVER (PARTITION BY city) AS total FROM customers',
'WITH c AS (SELECT id FROM customers) SELECT * FROM c',
'WITH c AS (SELECT id FROM customers) SELECT * FROM c UNION ALL SELECT * FROM c',
'SELECT id FROM customers UNION SELECT customer_id FROM orders',
'SELECT id FROM customers INTERSECT SELECT customer_id FROM orders',
'SELECT id FROM customers EXCEPT SELECT customer_id FROM orders',
'SELECT c.id FROM customers c JOIN orders o ON c.id=o.customer_id WHERE o.amount>100',
'SELECT c.id FROM customers c LEFT JOIN orders o ON c.id=o.customer_id WHERE o.id IS NOT NULL',
'SELECT c.id,COUNT(*) FROM customers c JOIN orders o ON c.id=o.customer_id GROUP BY c.id',
'SELECT id FROM customers ORDER BY id LIMIT 2',
'SELECT id FROM customers WHERE name LIKE \'ab%\' OR name LIKE \'ac%\'',
'SELECT id FROM customers WHERE CAST(id AS BIGINT)>1',
'SELECT id FROM customers WHERE id=1 OR id=2',
'SELECT CASE WHEN id=1 THEN true ELSE false END FROM customers',
'SELECT id FROM customers WHERE COALESCE(id,0)>1',
'SELECT count_if(amount>10) FROM orders',
'SELECT array_distinct(array_agg(customer_id)) FROM orders',
'SELECT bitmap_union_count(to_bitmap(id)) FROM customers',
'SELECT approx_count_distinct(id) FROM customers',
'SELECT id FROM customers LIMIT 0']
for sql in queries:fixtures.append(dict(id='sql-'+str(len(fixtures)),title=sql[:100],note='Native SQL fixture.',input=dict(database='starrocks',inputMode='sql',sql=sql,schema=schema,rules=[],options=dict(matchLimit=2))))
# Keep preparation stages explicit in exported examples. Only the final target's own events count.
original=copy.deepcopy(fixtures)
for fixture in original:
 root=fixture['input'].get('plan',{});ops=[n['op'] for n in root.get('nodes',[])]
 if 'Sort' in ops:
  prepared=copy.deepcopy(fixture);prepared['id']+='-split-limit';prepared['title']='Split limit before '+prepared['title'];prepared['input']['rules']=['StarRocks.SplitLimitRule'];fixtures.append(prepared)
 if 'Aggregate' in ops or fixture['input'].get('sql','') and ('GROUP BY' in fixture['input']['sql'] or 'DISTINCT' in fixture['input']['sql']):
  prepared=copy.deepcopy(fixture);prepared['id']+='-project';prepared['title']='Prepare projection before '+prepared['title'];prepared['input']['rules']=['StarRocks.MergeProjectWithChildRule'];fixtures.append(prepared)
physical=['SplitLimitRule','MergeLimitDirectRule','PartitionPruneRule','FilterImplementationRule','ProjectImplementationRule','HashAggImplementationRule','HashJoinImplementationRule','NestLoopJoinImplementationRule','OlapScanImplementationRule','ValuesImplementationRule','TopNImplementationRule','LimitImplementationRule','UnionImplementationRule','WindowImplementationRule']
for fixture in original:
 if fixture['id'] not in ['plan-0','plan-2','plan-7','plan-94'] and not fixture['input'].get('sql'):continue
 if fixture['input'].get('sql') and ('WITH ' in fixture['input']['sql'] or 'ROLLUP' in fixture['input']['sql'] or 'CUBE' in fixture['input']['sql']):continue
 prepared=copy.deepcopy(fixture);prepared['id']+='-physical';prepared['title']='Physical plan for '+prepared['title'];prepared['input']['rules']=['StarRocks.'+r for r in physical];fixtures.append(prepared)
# Remove intentionally ill-typed aggregate comparisons from the successful fixture corpus.
fixtures=[f for f in fixtures if not f['id'].startswith(('plan-76','plan-78'))]
open(sys.argv[1],'w').write(json.dumps(fixtures,indent=2)+'\n');print(len(fixtures),'fixtures')
