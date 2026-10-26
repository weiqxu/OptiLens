package dev.optilens;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipInputStream;

/** Immutable, offline source citations. IDs never resolve to arbitrary paths or URLs. */
public final class AssistantSources {
  public record File(
      String id,
      String database,
      String version,
      String commit,
      String path,
      String sha256,
      String url,
      String className,
      List<String> relatedPaths) {}

  public record Excerpt(
      String id,
      String database,
      String version,
      String commit,
      String path,
      String sha256,
      int startLine,
      int endLine,
      String text,
      String url) {}

  private record Content(File file, List<String> lines) {}

  private final Map<String, Content> files = new LinkedHashMap<>();

  private static final class Holder {
    static final AssistantSources INSTANCE = new AssistantSources();
  }

  public static AssistantSources instance() {
    return Holder.INSTANCE;
  }

  private AssistantSources() {
    try (var raw = getClass().getResourceAsStream("/assistant/sources.zip")) {
      if (raw == null) return;
      var entries = new HashMap<String, byte[]>();
      try (var zip = new ZipInputStream(raw)) {
        java.util.zip.ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null)
          entries.put(entry.getName(), zip.readAllBytes());
      }
      for (var node : Json.MAPPER.readTree(entries.get("index.json"))) {
        File f = Json.MAPPER.treeToValue(node, File.class);
        byte[] bytes = entries.get("files/" + f.id() + ".java");
        if (!digest(bytes).equals(f.sha256()))
          throw new IllegalStateException("Source checksum mismatch");
        files.put(
            f.id(),
            new Content(
                f, Arrays.asList(new String(bytes, StandardCharsets.UTF_8).split("\n", -1))));
      }
    } catch (Exception e) {
      throw new IllegalStateException("Unable to read the bundled source index", e);
    }
  }

  public static String digest(byte[] data) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public int size() {
    return files.size();
  }

  public Excerpt get(String id) {
    int sep = id.lastIndexOf(':');
    Input.require(sep > 0, "Unknown source excerpt");
    Content c = files.get(id.substring(0, sep));
    Input.require(c != null, "Unknown source excerpt");
    int start;
    try {
      start = Integer.parseInt(id.substring(sep + 1));
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid source line");
    }
    Input.require(start >= 1 && start <= c.lines.size(), "Source line is out of bounds");
    int end = Math.min(start + 99, c.lines.size());
    File f = c.file;
    return new Excerpt(
        id,
        f.database,
        f.version,
        f.commit,
        f.path,
        f.sha256,
        start,
        end,
        String.join("\n", c.lines.subList(start - 1, end)),
        f.url + "#L" + start + "-L" + end);
  }

  public Map<String, Object> mapping(String database, Model.RuleCard rule) {
    var implementation =
        files.values().stream()
            .filter(
                c ->
                    c.file.database.equals(database)
                        && (rule.source().endsWith(c.file.path)
                            || rule.implementations().stream()
                                .anyMatch(
                                    name ->
                                        name.equals(c.file.className)
                                            || name.equals(
                                                c.file.className.substring(
                                                    c.file.className.lastIndexOf('.') + 1)))))
            .map(Content::file)
            .toList();
    String owner = rule.id().split("\\.")[0];
    var configuration =
        files.values().stream()
            .filter(
                c ->
                    c.file.database.equals(database)
                        && (c.file.path.endsWith("/" + owner + ".java")
                            || database.equals("starrocks")
                                && (c.file.path.endsWith("/RuleSet.java")
                                    || c.file.path.endsWith("/RuleType.java"))))
            .map(Content::file)
            .toList();
    return Map.of(
        "ruleId",
        rule.id(),
        "implementations",
        implementation,
        "configuration",
        configuration,
        "note",
        "Related paths are lexical class references for retrieving base classes and helpers, not a complete semantic dependency graph. Missing generated or external classes are not indexed.");
  }

  public List<Excerpt> search(String database, String query, List<String> sourcePaths) {
    Engines.validateDatabase(database);
    var terms =
        Arrays.stream(query.toLowerCase(Locale.ROOT).split("[^a-z0-9_]+"))
            .filter(t -> t.length() > 2)
            .distinct()
            .limit(24)
            .toList();
    record Match(String id, int score) {}
    var matches = new ArrayList<Match>();
    for (var c : files.values()) {
      if (!c.file.database.equals(database)) continue;
      String path = c.file.path.toLowerCase(Locale.ROOT);
      boolean direct = sourcePaths.stream().anyMatch(p -> p.endsWith(c.file.path));
      int nameScore = terms.stream().mapToInt(t -> path.contains(t) ? 5 : 0).sum();
      for (int line = 0; line < c.lines.size(); line += 90) {
        String text =
            String.join("\n", c.lines.subList(line, Math.min(line + 100, c.lines.size())))
                .toLowerCase(Locale.ROOT);
        int score = nameScore + terms.stream().mapToInt(t -> text.contains(t) ? 1 : 0).sum();
        if (direct)
          score +=
              30
                  + (text.contains("onmatch")
                          || text.contains("transform(")
                          || text.contains("check(")
                      ? 8
                      : 0);
        if (score > 0) matches.add(new Match(c.file.id + ":" + (line + 1), score));
      }
    }
    return matches.stream()
        .sorted(Comparator.comparingInt(Match::score).reversed().thenComparing(Match::id))
        .limit(4)
        .map(m -> get(m.id))
        .toList();
  }
}
