package dev.optilens;

import static dev.optilens.Model.*;

import java.nio.file.*;
import java.util.*;

/** Engine routing is explicit; native runtimes never share an optimizer class loader. */
public final class Engines {
  public static void validateDatabase(String database) {
    Input.require(
        Set.of("calcite", "starrocks", "trafodion").contains(database),
        "Unsupported database: " + database);
  }

  public static void validateRule(String database, String id) {
    if (database.equals("calcite")) Rules.get(id);
    else if (database.equals("trafodion")) Trafodion.rule(id);
    else StarRocks.rule(id);
  }

  public static Map<String, Object> catalog(String database) {
    validateDatabase(database);
    if (database.equals("trafodion")) {
      var result = new LinkedHashMap<String, Object>(Trafodion.catalog());
      result.put("databases", databases());
      return result;
    }
    return Map.of(
        "database",
        database,
        "rules",
        database.equals("calcite") ? Rules.cards() : StarRocks.rules(),
        "examples",
        database.equals("calcite") ? Input.examples() : StarRocks.examples(),
        "engineVersion",
        database.equals("calcite") ? "1.39.0" : StarRocks.VERSION,
        "databases",
        databases(),
        "scope",
        database.equals("calcite") ? Rules.SCOPE : StarRocks.scope(),
        "runtime",
        database.equals("calcite")
            ? Map.of("available", true, "message", "Bundled Calcite runtime")
            : StarRocks.runtime(),
        "maxSelected",
        Rules.MAX_SELECTED);
  }

  public static Map<String, Object> examples(String database) {
    validateDatabase(database);
    if (database.equals("trafodion")) return Trafodion.examples();
    return Map.of(
        "database",
        database,
        "engineVersion",
        database.equals("calcite") ? "1.39.0" : StarRocks.VERSION,
        "examples",
        database.equals("calcite") ? RuleExamples.all() : StarRocks.examples(),
        "coverage",
        database.equals("calcite") ? RuleExamples.coverage() : StarRocks.coverage());
  }

  public static Map<String, Object> planCapabilities(String database) {
    validateDatabase(database);
    if (database.equals("trafodion")) return Trafodion.capabilities();
    var result =
        new LinkedHashMap<String, Object>(
            database.equals("calcite") ? Plans.capabilities() : StarRocks.capabilities());
    result.put("database", database);
    result.put("inputModes", List.of("sql", "plan"));
    return result;
  }

  public static List<String> workerCommand(
      Request request, Path input, Path output, boolean validation) {
    Input.require(
        !request.database().equals("trafodion"), (String) Trafodion.runtime().get("message"));
    String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    String cp =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    var command =
        new ArrayList<>(
            List.of(javaExecutable, "-Xmx384m", "-Xss1m", "-XX:ActiveProcessorCount=2"));
    String worker = Worker.class.getName();
    if (request.database().equals("starrocks")) {
      var runtime = StarRocks.runtime();
      Input.require(Boolean.TRUE.equals(runtime.get("available")), (String) runtime.get("message"));
      Path home = Path.of(System.getenv("OPTILENS_STARROCKS_HOME"));
      cp =
          home.resolve("adapter/optilens-starrocks.jar")
              + java.io.File.pathSeparator
              + home.resolve("fe/lib/*")
              + java.io.File.pathSeparator
              + cp;
      command.addAll(
          List.of(
              "--add-opens=java.base/java.lang=ALL-UNNAMED",
              "--add-opens=java.base/java.util=ALL-UNNAMED",
              "--add-opens=java.base/java.nio=ALL-UNNAMED"));
      worker = "dev.optilens.starrocks.StarRocksWorker";
    }
    command.addAll(List.of("-cp", cp, worker, input.toString(), output.toString()));
    if (validation) command.add("validate");
    return command;
  }

  private static List<Map<String, Object>> databases() {
    return List.of(
        database(
            "calcite",
            "Apache Calcite",
            "1.39.0",
            Map.of("available", true, "message", "Bundled Calcite runtime")),
        database("starrocks", "StarRocks", StarRocks.VERSION, StarRocks.runtime()),
        database("trafodion", "Trafodion Lite", Trafodion.VERSION, Trafodion.runtime()));
  }

  private static Map<String, Object> database(
      String id, String title, String version, Map<String, Object> runtime) {
    return Map.of(
        "id",
        id,
        "title",
        title,
        "version",
        version,
        "available",
        Boolean.TRUE.equals(runtime.get("available")),
        "message",
        runtime.get("message"));
  }

  private Engines() {}
}
