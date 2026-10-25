import test from 'node:test';
import assert from 'node:assert/strict';
import {databaseOptions} from '../src/main/resources/web/database-options.mjs';
const createOption=(label,value)=>({label,value});
test('installed runtimes are selectable and missing runtimes are labelled and disabled',()=>{
 const options=databaseOptions([
  {id:'calcite',title:'Apache Calcite',version:'1.39.0',available:true},
  {id:'starrocks',title:'StarRocks',version:'4.0.14',available:false,message:'Install the pinned runtime.'},
  {id:'trafodion',title:'Trafodion Lite',version:'lite',available:false,message:'Worker implementation pending.'}
 ],createOption);
 assert.equal(options[0].disabled,false);assert.doesNotMatch(options[0].label,/Not installed/);
 for(const option of options.slice(1)){assert.equal(option.disabled,true);assert.match(option.label,/Not installed/);}
 assert.equal(options[1].title,'Install the pinned runtime.');
 assert.deepEqual(options.map(o=>o.value),['calcite','starrocks','trafodion']);
});
test('availability is recomputed on reload and missing metadata fails closed',()=>{
 const database={id:'starrocks',title:'StarRocks',version:'4.0.14'};
 assert.equal(databaseOptions([database],createOption)[0].disabled,true);
 assert.equal(databaseOptions([{...database,available:true}],createOption)[0].disabled,false);
});
