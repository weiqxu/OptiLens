package dev.optilens;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import static dev.optilens.Model.*;

public final class Input {
  public static final Set<String> TYPES = Set.of("INTEGER", "BIGINT", "DOUBLE", "DECIMAL", "VARCHAR", "BOOLEAN", "DATE", "TIMESTAMP");
  public static void validate(Request r) {
    require(r != null, "缺少请求");
    require("calcite".equals(r.database()), "当前仅支持 Calcite");
    require(r.sql() != null && !r.sql().isBlank() && r.sql().length() <= 10_000, "SQL 必须为 1–10000 个字符");
    require(r.schema() != null && !r.schema().isEmpty() && r.schema().size() <= 16, "请提供 1–16 张表");
    var tables = new HashSet<String>();
    for (var t : r.schema()) {
      require(t != null, "表定义不能为空"); identifier(t.name());
      require(tables.add(t.name().toUpperCase(Locale.ROOT)), "表名重复：" + t.name());
      require(t.columns() != null && !t.columns().isEmpty() && t.columns().size() <= 32, "每张表需要 1–32 列");
      var columns = new HashSet<String>();
      for (var c : t.columns()) {
        require(c != null, "列定义不能为空"); identifier(c.name());
        require(columns.add(c.name().toUpperCase(Locale.ROOT)), "列名重复：" + c.name());
        require(c.type() != null && TYPES.contains(c.type()), "不支持的列类型：" + c.type());
      }
    }
    require(r.rules() != null && r.rules().size() <= Rules.MAX_SELECTED, "每次最多选择 " + Rules.MAX_SELECTED + " 条规则或规则组");
    var seen = new HashSet<String>();
    for (String id : r.rules()) { require(id != null && seen.add(id), "规则组不能为空或重复"); Rules.get(id); }
  }
  private static void identifier(String s) {
    require(s != null && s.matches("[a-zA-Z_][a-zA-Z0-9_]{0,63}"), "名称必须为不超过 64 位的字母、数字、下划线标识符");
  }
  public static void require(boolean ok, String message) { if (!ok) throw new IllegalArgumentException(message); }

  public static List<Example> examples() {
    var schema = List.of(new Table("customers", List.of(new Column("id","INTEGER",false), new Column("name","VARCHAR",true), new Column("city","VARCHAR",true))),
        new Table("orders", List.of(new Column("id","INTEGER",false),new Column("customer_id","INTEGER",true),new Column("amount","DECIMAL",true),new Column("created_at","TIMESTAMP",true))));
    var rules = Rules.ALL.stream().map(d -> d.card().id()).toList();
    return List.of(
        example("join", "客户订单 · 谓词与列裁剪", "观察过滤位置和连接输入投影；不测量实际读取或性能。", "SELECT c.name FROM customers c JOIN orders o ON c.id = o.customer_id WHERE o.amount > 50 + 50", schema, rules),
        example("constant", "常量投影", "常量可能在 SQL 转换或规则阶段化简；以真实事件为准。", "SELECT name, 10 * 20 AS threshold FROM customers",schema,rules),
        example("outer", "外连接 · NULL 拒绝条件", "右表过滤可能使外连接变为内连接；检查实际连接类型。", "SELECT c.name, o.amount FROM customers c LEFT JOIN orders o ON c.id = o.customer_id WHERE o.amount > 100",schema,rules),
        example("null", "外连接 · 保留 NULL 行", "IS NULL 与金额比较的语义不同，不能假定采用相同的下推。", "SELECT c.name FROM customers c LEFT JOIN orders o ON c.id = o.customer_id WHERE o.amount IS NULL",schema,rules),
        example("projection", "子查询 · 过滤穿过投影", "检查别名表达式在下推后的改写。", "SELECT name FROM (SELECT name, id + 1 AS next_id FROM customers) t WHERE next_id > 10",schema,rules),
        example("identity", "无过滤查询", "没有观察到变换时仍保留完整阶段快照和尝试记录。", "SELECT * FROM customers",schema,rules));
  }
  private static Example example(String id,String title,String note,String sql,List<Table> schema,List<String> rules) {
    return new Example(id,title,note,new Request(sql,schema,rules));
  }
  private Input() {}
}
