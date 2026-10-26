package dev.optilens.starrocks;

import com.fasterxml.jackson.databind.JsonNode;
import com.starrocks.analysis.*;
import com.starrocks.catalog.*;
import com.starrocks.sql.analyzer.Analyzer;
import com.starrocks.sql.ast.QueryStatement;
import com.starrocks.sql.optimizer.*;
import com.starrocks.sql.optimizer.base.*;
import com.starrocks.sql.optimizer.operator.*;
import com.starrocks.sql.optimizer.operator.logical.*;
import com.starrocks.sql.optimizer.operator.scalar.*;
import com.starrocks.sql.optimizer.transformer.RelationTransformer;
import com.starrocks.sql.parser.SqlParser;
import dev.optilens.Model.*;
import dev.optilens.Plans;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

final class NativePlans {
  static final Set<String> OPS =
      Set.of(
          "TableScan",
          "Values",
          "Filter",
          "Project",
          "Join",
          "Aggregate",
          "Sort",
          "Union",
          "Intersect",
          "Minus");

  static class Invalid extends IllegalArgumentException {
    final PlanIssue issue;

    Invalid(String node, String path, String message) {
      super(node + " · " + path + ": " + message);
      issue = new PlanIssue(node, path, message);
    }
  }

  final NativeSession session;
  final Map<String, OptExpression> built = new LinkedHashMap<>();
  final IdentityHashMap<OptExpression, List<ColumnRefOperator>> outputs = new IdentityHashMap<>();
  Map<String, OperatorNode> specs;
  String current;
  int expressions;

  NativePlans(NativeSession session) {
    this.session = session;
  }

  OptExpression build(Request request) {
    if (request.inputMode().equals("sql")) {
      var statements = SqlParser.parse(request.sql(), session.connection.getSessionVariable());
      if (statements.size() != 1 || !(statements.get(0) instanceof QueryStatement statement))
        throw new IllegalArgumentException("Provide exactly one SELECT query");
      Analyzer.analyze(statement, session.connection);
      var logical =
          new RelationTransformer(session.columns, session.connection)
              .transform(statement.getQueryRelation());
      var root = logical.getRoot();
      derive(root);
      return root;
    }
    Plans.validateStructure(request.plan());
    specs = new LinkedHashMap<>();
    request.plan().nodes().forEach(n -> specs.put(n.id(), n));
    return node(request.plan().rootId());
  }

  OptExpression node(String id) {
    var spec = specs.get(id);
    var inputs = spec.inputs().stream().map(this::node).toList();
    current = id;
    var a = spec.attributes();
    need(
        OPS.contains(spec.op()),
        "op",
        "This operator is not supported by the StarRocks manual-plan adapter");
    var allowed =
        Map.of(
            "TableScan",
            List.of("table"),
            "Values",
            List.of("columns", "rows"),
            "Filter",
            List.of("condition"),
            "Project",
            List.of("projects"),
            "Join",
            List.of("joinType", "condition"),
            "Aggregate",
            List.of("groupKeys", "calls"),
            "Sort",
            List.of("keys", "offset", "fetch"),
            "Union",
            List.of("all"),
            "Intersect",
            List.of("all"),
            "Minus",
            List.of("all"));
    a.fieldNames()
        .forEachRemaining(
            key ->
                need(
                    allowed.get(spec.op()).contains(key),
                    "attributes." + key,
                    "Unknown native operator attribute"));
    Operator op;
    List<ColumnRefOperator> out = null;
    try {
      switch (spec.op()) {
        case "TableScan" -> {
          var table = session.tables.get(text(a, "table").toLowerCase(Locale.ROOT));
          need(table != null, "table", "Unknown table");
          var forward = new LinkedHashMap<ColumnRefOperator, com.starrocks.catalog.Column>();
          var reverse = new LinkedHashMap<com.starrocks.catalog.Column, ColumnRefOperator>();
          int relation = session.columns.getNextRelationId();
          for (var c : table.getBaseSchema()) {
            var ref = session.columns.create(c.getName(), c.getType(), c.isAllowNull());
            forward.put(ref, c);
            reverse.put(c, ref);
            session.columns.updateColumnToRelationIds(ref.getId(), relation);
            session.columns.updateColumnRefToColumns(ref, c, table);
          }
          op = new LogicalOlapScanOperator(table, forward, reverse, null, -1, null);
          out = new ArrayList<>(forward.keySet());
        }
        case "Values" -> {
          out = new ArrayList<>();
          var rows = new ArrayList<List<ScalarOperator>>();
          need(
              a.path("columns").isArray() && a.path("columns").size() > 0,
              "columns",
              "Provide output columns");
          for (var c : a.path("columns"))
            out.add(
                session.columns.create(
                    text(c, "name"), type(text(c, "type"), c), c.path("nullable").asBoolean(true)));
          need(
              a.path("rows").isArray() && a.path("rows").size() <= 1000,
              "rows",
              "Provide at most 1000 rows");
          for (var row : a.path("rows")) {
            need(
                row.isArray() && row.size() == out.size(),
                "rows",
                "Row width does not match columns");
            var values = new ArrayList<ScalarOperator>();
            for (int i = 0; i < out.size(); i++) {
              need(
                  !row.get(i).isNull() || out.get(i).isNullable(),
                  "rows",
                  "NULL in a non-nullable column");
              values.add(literal(row.get(i), out.get(i).getType()));
            }
            rows.add(values);
          }
          op = new LogicalValuesOperator(out, rows);
        }
        case "Filter" ->
            op = new LogicalFilterOperator(condition(a.path("condition"), inputs, "condition"));
        case "Project" -> {
          var map = new LinkedHashMap<ColumnRefOperator, ScalarOperator>();
          out = new ArrayList<>();
          need(a.path("projects").isArray(), "projects", "Provide a projection list");
          for (var p : a.path("projects")) {
            var value = expression(p.path("expr"), inputs, "projects.expr", 0);
            String name = text(p, "name");
            var ref =
                value instanceof ColumnRefOperator c && c.getName().equals(name)
                    ? c
                    : session.columns.create(name, value.getType(), value.isNullable());
            need(!map.containsKey(ref), "projects", "Duplicate projected column");
            map.put(ref, value);
            out.add(ref);
          }
          op = new LogicalProjectOperator(map);
        }
        case "Join" -> {
          var join =
              switch (text(a, "joinType")) {
                case "INNER" -> JoinOperator.INNER_JOIN;
                case "LEFT" -> JoinOperator.LEFT_OUTER_JOIN;
                case "RIGHT" -> JoinOperator.RIGHT_OUTER_JOIN;
                case "FULL" -> JoinOperator.FULL_OUTER_JOIN;
                case "SEMI" -> JoinOperator.LEFT_SEMI_JOIN;
                case "ANTI" -> JoinOperator.LEFT_ANTI_JOIN;
                default -> throw invalid("joinType", "Unsupported join type");
              };
          op = new LogicalJoinOperator(join, condition(a.path("condition"), inputs, "condition"));
          out = new ArrayList<>(columns(inputs.get(0)));
          if (!join.isLeftSemiAntiJoin()) out.addAll(columns(inputs.get(1)));
          // StarRocks column identities are shared across the tree, including outer-join
          // nullability.
          if (join.isRightOuterJoin() || join.isFullOuterJoin())
            columns(inputs.get(0)).forEach(c -> c.setNullable(true));
          if (join.isLeftOuterJoin() || join.isFullOuterJoin())
            columns(inputs.get(1)).forEach(c -> c.setNullable(true));
        }
        case "Aggregate" -> {
          var group = new ArrayList<ColumnRefOperator>();
          for (var key : a.path("groupKeys"))
            group.add(field(inputs, 0, key.asInt(-1), "groupKeys"));
          var calls = new LinkedHashMap<ColumnRefOperator, CallOperator>();
          out = new ArrayList<>(group);
          for (var c : a.path("calls")) {
            need(
                !c.hasNonNull("filter"),
                "calls.filter",
                "StarRocks aggregate FILTER is not supported; express it explicitly with CASE");
            String name = text(c, "function").toLowerCase(Locale.ROOT);
            need(
                Set.of("count", "sum", "avg", "min", "max").contains(name),
                "calls.function",
                "Unsupported native aggregate");
            var args = new ArrayList<ScalarOperator>();
            for (var arg : c.path("args")) args.add(field(inputs, 0, arg.asInt(-1), "calls.args"));
            var call = call(name, args, c.path("distinct").asBoolean(false));
            var ref = session.columns.create(text(c, "name"), call.getType(), call.isNullable());
            calls.put(ref, call);
            out.add(ref);
          }
          op = new LogicalAggregationOperator(AggType.GLOBAL, group, calls);
        }
        case "Sort" -> {
          var keys = new ArrayList<Ordering>();
          for (var key : a.path("keys")) {
            int index = key.path("index").asInt(-1);
            need(
                Set.of("ASC", "DESC").contains(key.path("direction").asText("ASC"))
                    && Set.of("FIRST", "LAST").contains(key.path("nulls").asText("LAST")),
                "keys",
                "Invalid ordering");
            keys.add(
                new Ordering(
                    field(inputs, 0, index, "keys.index"),
                    !key.path("direction").asText("ASC").equals("DESC"),
                    key.path("nulls").asText("LAST").equals("FIRST")));
          }
          for (String key : List.of("offset", "fetch"))
            need(
                !a.hasNonNull(key)
                    || (a.get(key).isIntegralNumber() && a.get(key).canConvertToLong()),
                key,
                "An integer limit is required");
          long
              offset =
                  a.path("offset").isNull() || a.path("offset").isMissingNode()
                      ? 0
                      : a.path("offset").asLong(-1),
              fetch =
                  a.path("fetch").isNull() || a.path("fetch").isMissingNode()
                      ? -1
                      : a.path("fetch").asLong(-2);
          need(offset >= 0 && fetch >= -1, "offset/fetch", "Limits must be nonnegative integers");
          op =
              keys.isEmpty() && fetch >= 0
                  ? LogicalLimitOperator.init(fetch, offset)
                  : new LogicalTopNOperator(keys, fetch, offset);
        }
        case "Union", "Intersect", "Minus" -> {
          var childColumns = inputs.stream().map(this::columns).toList();
          var first = childColumns.get(0);
          out = new ArrayList<>();
          for (int i = 0; i < first.size(); i++) {
            var c = first.get(i);
            boolean nullable = false;
            for (var cols : childColumns) {
              need(
                  cols.size() == first.size() && cols.get(i).getType().equals(c.getType()),
                  "inputs",
                  "Set inputs require matching widths and explicit types");
              nullable |= cols.get(i).isNullable();
            }
            out.add(session.columns.create(c.getName(), c.getType(), nullable));
          }
          if (spec.op().equals("Union"))
            op = new LogicalUnionOperator(out, childColumns, a.path("all").asBoolean(false));
          else {
            need(
                !a.path("all").asBoolean(false),
                "all",
                "StarRocks supports DISTINCT for INTERSECT and EXCEPT");
            op =
                spec.op().equals("Intersect")
                    ? new LogicalIntersectOperator.Builder()
                        .setOutputColumnRefOp(out)
                        .setChildOutputColumns(childColumns)
                        .build()
                    : new LogicalExceptOperator.Builder()
                        .setOutputColumnRefOp(out)
                        .setChildOutputColumns(childColumns)
                        .build();
          }
        }
        default -> throw invalid("op", "Unsupported operator");
      }
      var root = OptExpression.create(op, inputs);
      derive(root);
      if (out == null) out = columns(inputs.get(0));
      outputs.put(root, List.copyOf(out));
      built.put(id, root);
      return root;
    } catch (Invalid e) {
      throw e;
    } catch (Exception e) {
      throw invalid("attributes", NativeRegistry.message(e));
    }
  }

  List<ColumnRefOperator> columns(OptExpression root) {
    return outputs.getOrDefault(
        root, root.getOutputColumns().getColumnRefOperators(session.columns));
  }

  ColumnRefOperator field(List<OptExpression> inputs, int input, int index, String path) {
    need(input >= 0 && input < inputs.size(), path, "Input index out of range");
    var cols = columns(inputs.get(input));
    need(index >= 0 && index < cols.size(), path, "Field index out of range");
    return cols.get(index);
  }

  ScalarOperator condition(JsonNode value, List<OptExpression> inputs, String path) {
    var result = expression(value, inputs, path, 0);
    need(result.getType().isBoolean(), path, "A BOOLEAN expression is required");
    return result;
  }

  ScalarOperator expression(JsonNode e, List<OptExpression> inputs, String path, int depth) {
    need(depth <= 32 && ++expressions <= 2000, path, "Expression limit exceeded");
    need(e.isObject(), path, "An expression object is required");
    switch (e.path("kind").asText()) {
      case "ref":
        return field(inputs, e.path("input").asInt(-1), e.path("index").asInt(-1), path);
      case "literal":
        return literal(e.path("value"), type(text(e, "type"), e));
      case "cast":
        return new CastOperator(
            type(text(e, "type"), e),
            expression(e.path("expr"), inputs, path + ".expr", depth + 1),
            false);
      case "call":
        {
          var args = new ArrayList<ScalarOperator>();
          need(e.path("args").isArray(), path, "An argument list is required");
          for (var arg : e.path("args"))
            args.add(expression(arg, inputs, path + ".args", depth + 1));
          String op = text(e, "op");
          if (Set.of("=", "<>", "<", "<=", ">", ">=", "IS NOT DISTINCT FROM").contains(op)) {
            need(args.size() == 2, path, "Two operands required");
            need(
                args.get(0).getType().equals(args.get(1).getType()),
                path,
                "Operand types differ; use an explicit CAST");
            var type =
                switch (op) {
                  case "=" -> BinaryType.EQ;
                  case "<>" -> BinaryType.NE;
                  case "<" -> BinaryType.LT;
                  case "<=" -> BinaryType.LE;
                  case ">" -> BinaryType.GT;
                  case ">=" -> BinaryType.GE;
                  default -> BinaryType.EQ_FOR_NULL;
                };
            return new BinaryPredicateOperator(type, args.get(0), args.get(1));
          }
          if (Set.of("AND", "OR", "NOT").contains(op)) {
            need(
                args.size() == (op.equals("NOT") ? 1 : 2)
                    && args.stream().allMatch(x -> x.getType().isBoolean()),
                path,
                "Boolean operands required");
            return new CompoundPredicateOperator(
                CompoundPredicateOperator.CompoundType.valueOf(op), args);
          }
          if (op.equals("IS NULL") || op.equals("IS NOT NULL")) {
            need(args.size() == 1, path, "One operand required");
            return new IsNullPredicateOperator(op.equals("IS NOT NULL"), args.get(0));
          }
          if (op.equals("CASE")) {
            need(
                args.size() >= 3 && args.size() % 2 == 1,
                path,
                "CASE requires condition/value pairs and ELSE");
            Type type = args.get(args.size() - 1).getType();
            for (int i = 0; i < args.size() - 1; i += 2)
              need(
                  args.get(i).getType().isBoolean() && args.get(i + 1).getType().equals(type),
                  path,
                  "CASE conditions must be boolean and result types must agree");
            return new CaseWhenOperator(
                type, null, args.get(args.size() - 1), args.subList(0, args.size() - 1));
          }
          String function =
              switch (op) {
                case "+" -> "add";
                case "-" -> "subtract";
                case "*" -> "multiply";
                case "/" -> "divide";
                case "COALESCE" -> "coalesce";
                default -> throw invalid(path, "Unsupported expression operator: " + op);
              };
          return call(function, args, false);
        }
      default:
        throw invalid(path, "Unsupported expression kind");
    }
  }

  CallOperator call(String name, List<ScalarOperator> args, boolean distinct) {
    var types = args.stream().map(ScalarOperator::getType).toArray(Type[]::new);
    var fn = Expr.getBuiltinFunction(name, types, Function.CompareMode.IS_IDENTICAL);
    if (fn == null)
      fn = Expr.getBuiltinFunction(name, types, Function.CompareMode.IS_INDISTINGUISHABLE);
    need(
        fn != null,
        "expression",
        "No exact native function signature for "
            + name
            + Arrays.toString(types)
            + "; use an explicit CAST");
    return new CallOperator(name, fn.getReturnType(), args, fn, distinct);
  }

  static Type type(String name, JsonNode options) {
    int precision = options == null ? 18 : options.path("precision").asInt(18),
        scale = options == null ? 2 : options.path("scale").asInt(2);
    if (name.equalsIgnoreCase("DECIMAL")
        && (precision < 1 || precision > 38 || scale < 0 || scale > precision))
      throw new IllegalArgumentException("Invalid DECIMAL precision or scale");
    if (name.equalsIgnoreCase("VARCHAR")
        && options != null
        && (options.path("precision").asInt(255) < 1
            || options.path("precision").asInt(255) > 65536))
      throw new IllegalArgumentException("VARCHAR length must be 1–65536");
    return switch (name.toUpperCase(Locale.ROOT)) {
      case "INTEGER", "INT" -> Type.INT;
      case "BIGINT" -> Type.BIGINT;
      case "DOUBLE" -> Type.DOUBLE;
      case "BOOLEAN" -> Type.BOOLEAN;
      case "DATE" -> Type.DATE;
      case "TIMESTAMP", "DATETIME" -> Type.DATETIME;
      case "VARCHAR" ->
          ScalarType.createVarcharType(
              options == null ? 255 : options.path("precision").asInt(255));
      case "DECIMAL" -> ScalarType.createDecimalV3Type(PrimitiveType.DECIMAL128, precision, scale);
      default -> throw new IllegalArgumentException("Unsupported StarRocks type: " + name);
    };
  }

  static ConstantOperator literal(JsonNode value, Type type) {
    if (value.isMissingNode()) throw new IllegalArgumentException("Literal value is required");
    if (value.isNull()) return ConstantOperator.createNull(type);
    if (type.isBoolean()) {
      if (!value.isBoolean()) throw new IllegalArgumentException("BOOLEAN literal required");
      return ConstantOperator.createBoolean(value.booleanValue());
    }
    if (type.equals(Type.INT)) {
      if (!value.isIntegralNumber() || !value.canConvertToInt())
        throw new IllegalArgumentException("INTEGER literal required");
      return ConstantOperator.createInt(value.intValue());
    }
    if (type.equals(Type.BIGINT)) {
      if (!value.isIntegralNumber() || !value.canConvertToLong())
        throw new IllegalArgumentException("BIGINT literal required");
      return ConstantOperator.createBigint(value.longValue());
    }
    if (type.isFloatingPointType()) {
      if (!value.isNumber()) throw new IllegalArgumentException("Numeric literal required");
      return ConstantOperator.createDouble(value.doubleValue());
    }
    if (type.isDecimalOfAnyVersion())
      return ConstantOperator.createDecimal(new BigDecimal(value.asText()), type);
    if (type.isDate())
      return ConstantOperator.createDate(LocalDate.parse(value.asText()).atStartOfDay());
    if (type.isDatetime())
      return ConstantOperator.createDatetime(LocalDateTime.parse(value.asText().replace(' ', 'T')));
    if (type.isStringType()) {
      if (!value.isTextual()) throw new IllegalArgumentException("String literal required");
      return new ConstantOperator(value.asText(), type);
    }
    throw new IllegalArgumentException("Unsupported literal type: " + type);
  }

  static void derive(OptExpression root) {
    for (var c : root.getInputs()) derive(c);
    if (root.getOp().isLogical()) root.deriveLogicalPropertyItself();
    else {
      var property =
          root.getLogicalProperty() == null
              ? new LogicalProperty()
              : new LogicalProperty(root.getLogicalProperty());
      property.setOutputColumns(root.getRowOutputInfo().getOutputColumnRefSet());
      root.setLogicalProperty(property);
    }
  }

  String text(JsonNode value, String key) {
    need(
        value.path(key).isTextual() && !value.path(key).asText().isBlank(),
        key,
        "A nonempty string is required");
    return value.path(key).asText();
  }

  void need(boolean ok, String path, String message) {
    if (!ok) throw invalid(path, message);
  }

  Invalid invalid(String path, String message) {
    return new Invalid(current, path, message);
  }
}
