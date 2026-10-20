package dev.optilens;

import java.util.*;
import static dev.optilens.Model.*;

/** Validate imported data structurally; importing does not verify its claimed provenance. */
public final class Records {
  public static void validate(Run run) {
    Input.require(run != null && run.formatVersion()==1,"不支持的记录格式，请导入 OptiLens v1 运行记录");
    text(run.engine(),120);text(run.engineVersion(),80);text(run.createdAt(),80);
    Input.validate(run.input());snapshot(run.initial());snapshot(run.finalPlan());
    Input.require(run.stages()!=null && run.stages().size()<=Rules.MAX_SELECTED,"阶段数量不合法");
    Input.require(run.stages().size()==run.input().rules().size(),"阶段与规则程序不一致");
    int count=0;
    for(int i=0;i<run.stages().size();i++){
      var s=run.stages().get(i);Input.require(s!=null,"阶段不能为空");
      Input.require(run.input().rules().get(i).equals(s.id()),"阶段顺序与规则程序不一致");
      text(s.title(),120);text(s.description(),4000);text(s.conditions(),4000);text(s.source(),1000);
      snapshot(s.before());snapshot(s.after());
      Input.require(s.attempts()!=null && s.attempts().size()<=500 && s.events()!=null && s.events().size()<=80,"事件数量不合法");
      count+=s.attempts().size();
      Set<String> calls=new HashSet<>();
      for(var a:s.attempts()){
        Input.require(a!=null && a.matchedIds()!=null && a.matchedIds().size()<=250,"尝试记录不合法");
        text(a.rule(),500);a.matchedIds().forEach(id->text(id,100));calls.add(a.callId()+":"+a.rule());
      }
      for(var e:s.events()){
        Input.require(e!=null,"事件不能为空");text(e.rule(),500);
        Input.require(calls.contains(e.callId()+":"+e.rule()),"变换事件缺少对应尝试记录");
        snapshot(e.before());snapshot(e.after());
      }
    }
    Input.require(count<=500,"尝试记录过多");
    Input.require(run.limitations()!=null && run.limitations().size()<=20,"缺少能力说明");
    run.limitations().forEach(s->text(s,4000));
  }
  private static void text(String s,int max){Input.require(s!=null && s.length()<=max,"记录字段缺失或过长");}
  private static void snapshot(Snapshot p){
    Input.require(p!=null && ("full-plan".equals(p.scope())||"local-expression".equals(p.scope())),"快照范围不合法");
    text(p.rootId(),100);text(p.raw(),500_000);
    Input.require(p.nodes()!=null && !p.nodes().isEmpty() && p.nodes().size()<=250,"快照节点数量不合法");
    Map<String,PlanNode> nodes=new HashMap<>();
    for(var n:p.nodes()){
      Input.require(n!=null,"节点不能为空");text(n.id(),100);text(n.type(),200);
      Input.require(nodes.put(n.id(),n)==null,"节点标识重复");
      Input.require(n.attributes()!=null && n.attributes().size()<=100 && n.fields()!=null && n.fields().size()<=1024 && n.inputs()!=null && n.inputs().size()<=250,"节点属性不合法");
      n.attributes().forEach((k,v)->{text(k,200);text(v,50_000);});n.fields().forEach(f->text(f,1000));n.inputs().forEach(id->text(id,100));
    }
    var visited=new HashMap<String,Integer>();walk(p.rootId(),nodes,new HashSet<>(),visited,0);
    Input.require(visited.size()==nodes.size(),"快照含有无法从根访问的节点");
  }
  private static int walk(String id,Map<String,PlanNode> nodes,Set<String> path,Map<String,Integer> visited,int depth){
    Input.require(depth<=40 && nodes.containsKey(id),"计划深度超限或引用不存在的节点");
    if(visited.containsKey(id)){Input.require(depth+visited.get(id)<=40,"计划深度超限");return visited.get(id);}
    Input.require(path.add(id),"计划包含循环引用");
    int height=0;
    for(String child:nodes.get(id).inputs())height=Math.max(height,1+walk(child,nodes,path,visited,depth+1));
    path.remove(id);visited.put(id,height);return height;
  }
  private Records(){}
}
