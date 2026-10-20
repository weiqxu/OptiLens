package dev.optilens;

import java.util.*;
import java.lang.reflect.Modifier;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.rel.rules.CoreRules;
import static dev.optilens.Model.*;

public final class Rules {
  public static final int MAX_SELECTED = 32;
  public record Definition(RuleCard card, List<RelOptRule> rules) {}
  private static final String SOURCE = "https://github.com/apache/calcite/blob/calcite-1.39.0/core/src/main/java/org/apache/calcite/rel/rules/";
  public static final List<Definition> ALL = List.of(
      definition("reduce", "表达式化简", "计算常量表达式，并按 Calcite 的类型和 NULL 语义化简过滤与投影。",
          "匹配 Filter 或 Project；仅化简引擎认为可安全求值的表达式。SQL 转换阶段可能已提前完成部分化简。",
          "ReduceExpressionsRule.java", CoreRules.FILTER_REDUCE_EXPRESSIONS, CoreRules.PROJECT_REDUCE_EXPRESSIONS),
      definition("filter-join", "连接谓词下推", "将过滤或连接条件下推至允许的输入，也可能简化连接类型。",
          "实际行为由连接类型、列引用和 NULL 拒绝条件决定；不能把内连接条件直接套用到外连接。",
          "FilterJoinRule.java", CoreRules.FILTER_INTO_JOIN, CoreRules.JOIN_CONDITION_PUSH),
      definition("filter-project", "过滤穿过投影", "将过滤条件改写为投影输入上的表达式，并向下移动。",
          "匹配 Filter → Project；包含窗口等不允许穿越的表达式时可能不产生结果。",
          "FilterProjectTransposeRule.java", CoreRules.FILTER_PROJECT_TRANSPOSE),
      definition("project-join", "连接输入列裁剪", "在连接输入添加投影，仅保留上层输出和连接条件需要的表达式。",
          "匹配 Project → Join。新增投影表示逻辑输出裁剪，不证明底层存储减少了读取。",
          "ProjectJoinTransposeRule.java", CoreRules.PROJECT_JOIN_TRANSPOSE),
      definition("project-cleanup", "投影整理", "合并相邻投影并删除恒等投影。",
          "仅在引擎确认表达式合并或恒等映射成立时变换；不产生结果的具体原因未采集。",
          "ProjectMergeRule.java", CoreRules.PROJECT_MERGE, CoreRules.PROJECT_REMOVE));

  private static Definition definition(String id, String title, String description, String conditions,
      String source, RelOptRule... rules) {
    var list = List.of(rules);
    return new Definition(new RuleCard(id, title, description, conditions,
        list.stream().map(Object::toString).toList(), SOURCE + source, "常用规则组", true, ""), list);
  }
  private static final String ROOT = "https://github.com/apache/calcite/blob/calcite-1.39.0/core/src/main/java/";
  public static final String SCOPE = "目录来自 Calcite 1.39.0 的 CoreRules、PruneEmptyRules、DateRangeRules、SingleValuesOptimizationRules、MeasureRules、SpatialRules、MaterializedViewRules、StreamRules、EnumerableRules、Bindables 和 JdbcRules。包含公开规则常量、列表及 JDBC 规则类型；不含额外适配器、自定义规则或运行时配置生成的全部变体。";
  private static final List<Definition> CATALOG = catalog();

  private static List<Definition> catalog() {
    var result = new ArrayList<>(ALL);
    collect(result, "rel.rules.CoreRules", "核心逻辑规则", "");
    collect(result, "rel.rules.PruneEmptyRules", "空结果裁剪", "");
    collect(result, "rel.rules.SingleValuesOptimizationRules", "单行值优化", "");
    collect(result, "rel.rules.DateRangeRules", "日期范围", "");
    collect(result, "rel.rules.MeasureRules", "度量", "当前 SQL 和表结构尚未接入度量类型");
    collect(result, "rel.rules.SpatialRules", "空间", "当前 SQL 环境尚未注册空间函数");
    collect(result, "rel.rules.materialize.MaterializedViewRules", "物化视图", "需要注册物化视图和匹配元数据");
    collect(result, "rel.stream.StreamRules", "流式查询", "需要流式表和流式查询环境");
    collect(result, "adapter.enumerable.EnumerableRules", "Enumerable 物理规则", "需要物理执行约定与完整转换程序");
    collect(result, "interpreter.Bindables", "Bindable 物理规则", "需要解释器执行约定与完整转换程序");
    try {
      var jdbc = Class.forName("org.apache.calcite.adapter.jdbc.JdbcRules");
      var classes = new ArrayList<Class<?>>(Arrays.asList(jdbc.getDeclaredClasses()));
      classes.add(Class.forName("org.apache.calcite.adapter.jdbc.JdbcToEnumerableConverterRule"));
      classes.stream().filter(c -> RelOptRule.class.isAssignableFrom(c) && !Modifier.isAbstract(c.getModifiers()))
          .sorted(Comparator.comparing(Class::getSimpleName)).forEach(c -> {
            String name = "JdbcRules." + c.getSimpleName();
            result.add(new Definition(new RuleCard(name, name, "JDBC 适配器规则类型", "规则实例随 JDBC 方言和执行约定创建。",
                List.of(c.getName()), source(c), "JDBC 适配器", false, "需要 JDBC 方言、数据源和执行约定"), List.of()));
          });
    } catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
    return List.copyOf(result);
  }

  private static void collect(List<Definition> result, String path, String category, String unavailable) {
    try {
      Class<?> owner = Class.forName("org.apache.calcite." + path);
      var fields = Arrays.stream(owner.getFields()).filter(f -> Modifier.isStatic(f.getModifiers()))
          .sorted(Comparator.comparing(java.lang.reflect.Field::getName)).toList();
      Set<RelOptRule> named = Collections.newSetFromMap(new IdentityHashMap<>());
      for (var field : fields) if (RelOptRule.class.isAssignableFrom(field.getType())) {
        var rule = (RelOptRule) field.get(null);
        named.add(rule);
        add(result, owner.getSimpleName() + "." + field.getName(), rule, category, unavailable);
      }
      for (var field : fields) if (Iterable.class.isAssignableFrom(field.getType())) {
        int index = 0;
        for (Object value : (Iterable<?>) field.get(null)) {
          if (value instanceof RelOptRule rule && named.add(rule))
            add(result, owner.getSimpleName() + "." + field.getName() + "." + index, rule, category, unavailable);
          index++;
        }
      }
    } catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
  }

  private static void add(List<Definition> result, String id, RelOptRule rule, String category, String unavailable) {
    // These CoreRules entries require table adapters or registered star schemas.
    if (id.contains("STAR_TABLE")) unavailable = "需要注册星型表和物化关系";
    if (id.equals("CoreRules.FILTER_SCAN") || id.equals("CoreRules.FILTER_INTERPRETER_SCAN")
        || id.equals("CoreRules.PROJECT_TABLE_SCAN") || id.equals("CoreRules.PROJECT_INTERPRETER_TABLE_SCAN"))
      unavailable = "需要支持过滤或投影下推的表适配器";
    result.add(new Definition(new RuleCard(id, id, rule.toString(),
        "由 Calcite 的算子匹配和语义条件决定是否触发；可运行不代表当前 SQL 一定匹配。",
        List.of(rule.toString()), source(rule.getClass()), category, unavailable.isEmpty(), unavailable), List.of(rule)));
  }

  private static String source(Class<?> type) {
    while (type.getEnclosingClass() != null) type = type.getEnclosingClass();
    return ROOT + type.getName().replace('.', '/') + ".java";
  }

  public static Definition get(String id) {
    var definition = CATALOG.stream().filter(r -> r.card.id().equals(id)).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("不支持的规则：" + id));
    Input.require(definition.card.runnable(), "规则当前不可运行：" + id + "；" + definition.card.unavailableReason());
    return definition;
  }
  public static List<RuleCard> cards() { return CATALOG.stream().map(Definition::card).toList(); }
  private Rules() {}
}
