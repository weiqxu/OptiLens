package dev.optilens.starrocks;

import dev.optilens.Model.*;
import com.starrocks.sql.optimizer.OptExpression;
import com.starrocks.sql.optimizer.base.ColumnRefFactory;
import java.util.*;

final class NativeSnapshots {
  static Snapshot capture(OptExpression root,ColumnRefFactory columns,String scope) {
    var nodes=new ArrayList<PlanNode>();walk(root,columns,nodes,new IdentityHashMap<>(),0);
    return new Snapshot(scope,"r0",List.copyOf(nodes),root.debugString());
  }
  static String walk(OptExpression root,ColumnRefFactory columns,List<PlanNode> nodes,IdentityHashMap<OptExpression,Boolean> path,int depth) {
    if(depth>60||nodes.size()>=1000||path.put(root,true)!=null)throw new IllegalArgumentException("Native result exceeds tree limits");
    String id="r"+nodes.size();int slot=nodes.size();nodes.add(null);
    var children=new ArrayList<String>();for(var child:root.getInputs())children.add(walk(child,columns,nodes,path,depth+1));
    var attrs=new LinkedHashMap<String,String>();attrs.put("expression",root.debugString(1).trim());
    attrs.put("operator",root.getOp().getOpType().toString());
    var fields=fields(root,columns);
    nodes.set(slot,new PlanNode(id,root.getOp().getClass().getSimpleName(),attrs,fields,children));path.remove(root);return id;
  }
  static List<String> fields(OptExpression root,ColumnRefFactory columns) {
    var result=new ArrayList<String>();
    for(int id:root.getOutputColumns().getColumnIds()) {var c=columns.getColumnRef(id);result.add(c.getId()+": "+c.getName()+" "+c.getType().toSql()+(c.isNullable()?" NULL":" NOT NULL"));}
    return result;
  }
  static String rootId(OptExpression root,List<Integer> path){int id=0;OptExpression cursor=root;for(int index:path){id++;for(int i=0;i<index;i++)id+=size(cursor.inputAt(i));cursor=cursor.inputAt(index);}return "r"+id;}
  static int size(OptExpression n){int size=1;for(var c:n.getInputs())size+=size(c);return size;}
}
