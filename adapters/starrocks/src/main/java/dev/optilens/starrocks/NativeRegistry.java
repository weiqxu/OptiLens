package dev.optilens.starrocks;

import com.starrocks.sql.optimizer.rewrite.scalar.ScalarOperatorRewriteRule;
import com.starrocks.sql.optimizer.rule.Rule;
import com.starrocks.sql.optimizer.rule.RuleSet;
import com.starrocks.sql.optimizer.rule.tree.TreeRewriteRule;
import dev.optilens.Json;
import java.lang.reflect.*;
import java.util.*;

/** Only classes from the checked-in, pinned inventory can be selected by a request. */
final class NativeRegistry {
  record Entry(String id, String className, String family, List<Object> instances, String error) {}

  final Map<String, Entry> entries = new LinkedHashMap<>();

  NativeRegistry() throws Exception {
    var pool = new ArrayList<Object>();
    collect(RuleSet.class, pool);
    try {
      var rs = new RuleSet();
      pool.addAll(rs.getTransformRules());
      pool.addAll(rs.getImplementRules());
    } catch (Throwable ignored) {
    }
    var inventory =
        Json.MAPPER.readTree(NativeRegistry.class.getResourceAsStream("/starrocks-inventory.json"));
    for (var item : inventory.path("classes")) {
      String name = item.path("className").asText(),
          id = "StarRocks." + name.substring(name.lastIndexOf('.') + 1);
      var instances = new ArrayList<Object>();
      String error = "";
      try {
        Class<?> c = Class.forName(name);
        collect(c, pool);
        for (Object o : pool) if (o.getClass() == c && !instances.contains(o)) instances.add(o);
        if (instances.isEmpty()) {
          try {
            instances.add(c.getConstructor().newInstance());
          } catch (ReflectiveOperationException e) {
            for (Method m : c.getMethods())
              if (Modifier.isStatic(m.getModifiers())
                  && m.getParameterCount() == 0
                  && c.isAssignableFrom(m.getReturnType()))
                try {
                  Object o = m.invoke(null);
                  if (o != null) instances.add(o);
                } catch (ReflectiveOperationException ignored) {
                }
            if (instances.isEmpty())
              for (Constructor<?> constructor : c.getConstructors())
                if (constructor.getParameterCount() == 1
                    && Set.of(
                            "com.starrocks.qe.ConnectContext",
                            "com.starrocks.qe.SessionVariable",
                            "com.starrocks.sql.optimizer.task.TaskContext",
                            "com.starrocks.sql.optimizer.OptimizerContext")
                        .contains(constructor.getParameterTypes()[0].getName())) {
                  instances.add(c);
                  break;
                }
            if (instances.isEmpty()) throw e;
          }
        }
      } catch (Throwable e) {
        error = message(e);
      }
      entries.put(
          id, new Entry(id, name, item.path("family").asText(), List.copyOf(instances), error));
    }
  }

  static void collect(Class<?> c, List<Object> result) {
    for (Field f : c.getDeclaredFields())
      if (Modifier.isStatic(f.getModifiers()))
        try {
          f.setAccessible(true);
          Object o = f.get(null);
          if (o instanceof Collection<?> xs) {
            for (Object x : xs) if (isRule(x)) result.add(x);
          } else if (isRule(o)) result.add(o);
        } catch (Throwable ignored) {
        }
  }

  static boolean isRule(Object o) {
    return o instanceof Rule
        || o instanceof ScalarOperatorRewriteRule
        || o instanceof TreeRewriteRule;
  }

  Entry get(String id) {
    var e = entries.get(id);
    if (e == null) throw new IllegalArgumentException("Unknown native rule: " + id);
    if (e.instances().isEmpty())
      throw new IllegalArgumentException("Rule requires additional configuration: " + e.error());
    return e;
  }

  static String message(Throwable e) {
    while (e.getCause() != null && e.getCause() != e) e = e.getCause();
    return e.getClass().getSimpleName() + ": " + Objects.toString(e.getMessage(), "");
  }
}
