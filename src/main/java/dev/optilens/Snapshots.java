package dev.optilens;

import java.util.*;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.hep.HepRelVertex;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelWriter;
import org.apache.calcite.sql.SqlExplainLevel;
import org.apache.calcite.util.Pair;
import static dev.optilens.Model.*;

public final class Snapshots {
  public static Snapshot capture(RelNode root, String scope) {
    var nodes = new LinkedHashMap<String,PlanNode>();
    visit(root,nodes,0);
    // Copy only for the raw EXPLAIN: unwrap Hep vertices without mutating live optimizer nodes.
    RelNode plain = plain(root,new IdentityHashMap<>());
    return new Snapshot(scope, id(root), List.copyOf(nodes.values()), RelOptUtil.toString(plain));
  }
  public static RelNode unwrap(RelNode n) {
    while (n instanceof HepRelVertex v) n = v.getCurrentRel();
    return n;
  }
  private static String id(RelNode n) { return String.valueOf(unwrap(n).getId()); }
  private static void visit(RelNode original, LinkedHashMap<String,PlanNode> nodes, int depth) {
    RelNode n=unwrap(original);
    if(nodes.containsKey(id(n))) return;
    Input.require(depth<=40 && nodes.size()<250,"计划超过 250 个节点或 40 层深度上限");
    var writer = new Attributes(); n.explain(writer);
    var fields=n.getRowType().getFieldList().stream().map(f->f.getName()+": "+f.getType().getFullTypeString()).toList();
    nodes.put(id(n),new PlanNode(id(n),n.getRelTypeName(),Collections.unmodifiableMap(writer.values),fields,n.getInputs().stream().map(Snapshots::id).toList()));
    for(var child:n.getInputs()) visit(child,nodes,depth+1);
  }
  private static RelNode plain(RelNode original,IdentityHashMap<RelNode,RelNode> copies) {
    RelNode n=unwrap(original); if(copies.containsKey(n))return copies.get(n);
    var result=n.copy(n.getTraitSet(),n.getInputs().stream().map(c->plain(c,copies)).toList());
    copies.put(n,result);return result;
  }
  private static final class Attributes implements RelWriter {
    final Map<String,String> values=new LinkedHashMap<>();
    public void explain(RelNode rel,List<Pair<String,Object>> pairs){pairs.forEach(p->item(p.left,p.right));}
    public SqlExplainLevel getDetailLevel(){return SqlExplainLevel.EXPPLAN_ATTRIBUTES;}
    public RelWriter input(String term,RelNode input){return this;}
    public RelWriter item(String term,Object value){if(!(value instanceof RelNode))values.put(term,String.valueOf(value));return this;}
    public RelWriter itemIf(String term,Object value,boolean condition){if(condition)item(term,value);return this;}
    public RelWriter done(RelNode node){return this;}
    public boolean nest(){return false;}
  }
  private Snapshots() {}
}
