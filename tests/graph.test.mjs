import test from 'node:test';
import assert from 'node:assert/strict';
import {diffPlans,layoutPlan} from '../src/main/resources/web/graph.mjs';
const n=(id,type,attributes={},inputs=[])=>({id,type,attributes,inputs,fields:[]});
const p=(rootId,nodes)=>({rootId,nodes});
test('preserved identities expose moved filter and changed inputs',()=>{
  const a=p('f',[n('f','Filter',{condition:'x > 1'},['j']),n('j','Join',{},['a','b']),n('a','Scan',{table:'a'}),n('b','Scan',{table:'b'})]);
  const b=p('j',[n('j','Join',{},['a','f']),n('f','Filter',{condition:'x > 1'},['b']),n('a','Scan',{table:'a'}),n('b','Scan',{table:'b'})]);
  const d=diffPlans(a,b);assert.equal(d.left.get('f').label,'输入关系变化');assert.equal(d.right.get('j').label,'输入关系变化');assert.equal(d.left.has('a'),false);
});
test('unique structural matching labels inferred property changes',()=>{
  const a=p('1',[n('1','Filter',{condition:'50+50'},['2']),n('2','Scan',{table:'t'})]);
  const b=p('3',[n('3','Filter',{condition:'100'},['2']),n('2','Scan',{table:'t'})]);
  const d=diffPlans(a,b);assert.equal(d.left.get('1').label,'属性变化');assert.match(d.left.get('1').basis,/推断/);
});
test('ambiguous peers remain unmatched',()=>{
  const a=p('r',[n('r','Union',{},['1','2']),n('1','Scan',{table:'t'}),n('2','Scan',{table:'t'})]);
  const b=p('r',[n('r','Union',{},['3','4']),n('3','Scan',{table:'t'}),n('4','Scan',{table:'t'})]);
  const d=diffPlans(a,b);assert.equal(d.matches.size,1);assert.equal(d.left.get('1').label,'仅见于应用前');
});
test('DAG layout retains shared nodes once and places inputs below parents',()=>{
  const plan=p('r',[n('r','Join',{},['a','b']),n('a','Project',{},['s']),n('b','Filter',{},['s']),n('s','Scan',{table:'t'})]);
  const layout=layoutPlan(plan);assert.equal(layout.positions.size,4);assert.ok(layout.positions.get('s').y>layout.positions.get('b').y);
});
