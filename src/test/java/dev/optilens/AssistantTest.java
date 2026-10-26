package dev.optilens;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AssistantTest {
  @TempDir Path directory;
  final HttpClient client = HttpClient.newHttpClient();

  interface Answer {
    String respond(JsonNode request) throws Exception;
  }

  static final class Gateway implements AutoCloseable {
    final HttpServer server;
    final ExecutorService threads = Executors.newCachedThreadPool();

    Gateway(Answer answer) throws Exception {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
      server.setExecutor(threads);
      server.createContext(
          "/chat",
          x -> {
            try {
              var req = Json.MAPPER.readTree(x.getRequestBody());
              String body = answer.respond(req);
              x.getResponseHeaders().set("Content-Type", "text/event-stream");
              x.sendResponseHeaders(200, 0);
              byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
              for (int i = 0; i < bytes.length; i += 7) {
                x.getResponseBody().write(bytes, i, Math.min(7, bytes.length - i));
                x.getResponseBody().flush();
              }
            } catch (Exception ignored) {
            } finally {
              x.close();
            }
          });
      server.start();
    }

    String endpoint() {
      return "http://127.0.0.1:" + server.getAddress().getPort() + "/chat";
    }

    public void close() {
      server.stop(0);
      threads.shutdownNow();
    }
  }

  AssistantModel.Config config(Gateway gateway) {
    return new AssistantModel.Config(
        gateway.endpoint(), "test-model", "secret-not-for-browser", true, 30);
  }

  String event(Object delta, String finish) throws Exception {
    var choice = new HashMap<String, Object>();
    choice.put("delta", delta);
    choice.put("finish_reason", finish);
    return "data: "
        + Json.MAPPER.writeValueAsString(Map.of("choices", List.of(choice))).replace("\n", "")
        + "\n\n";
  }

  String text(String value) throws Exception {
    return event(Map.of("content", value), null) + event(Map.of(), "stop") + "data: [DONE]\n\n";
  }

  String tool(String name, Object args) throws Exception {
    String encoded = Json.MAPPER.writeValueAsString(args);
    int split = encoded.length() / 2;
    return event(
            Map.of(
                "tool_calls",
                List.of(
                    Map.of(
                        "index",
                        0,
                        "id",
                        "c1",
                        "function",
                        Map.of("name", name, "arguments", encoded.substring(0, split))))),
            null)
        + event(
            Map.of(
                "tool_calls",
                List.of(
                    Map.of("index", 0, "function", Map.of("arguments", encoded.substring(split))))),
            null)
        + event(Map.of(), "tool_calls")
        + "data: [DONE]\n\n";
  }

  ObjectNode request(String page, Model.Request input) {
    var context =
        Json.MAPPER
            .createObjectNode()
            .put("page", page)
            .put("database", input.database())
            .put("version", "a".repeat(64));
    context.set("input", Json.MAPPER.valueToTree(input));
    context.put("stage", -1).put("event", -1).putNull("runId");
    var body =
        Json.MAPPER
            .createObjectNode()
            .put("requestId", UUID.randomUUID().toString())
            .put("question", "Explain this rule and suggest an experiment.");
    body.putArray("history");
    body.set("context", context);
    return body;
  }

  HttpResponse<String> post(Server server, String path, JsonNode body) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(45))
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  HttpResponse<String> get(Server server, String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  List<JsonNode> events(String stream, String name) throws Exception {
    var result = new ArrayList<JsonNode>();
    for (var block : stream.split("\n\n"))
      if (block.startsWith("event: " + name + "\n"))
        result.add(Json.MAPPER.readTree(block.substring(block.indexOf("data: ") + 6)));
    return result;
  }

  @Test
  void disabledAssistantLeavesExistingPagesWorking() throws Exception {
    try (var server = new Server(0, directory, new AssistantModel.Config("", "", "", true, 30))) {
      var status = get(server, "/api/assistant/status");
      assertFalse(Json.MAPPER.readTree(status.body()).path("enabled").asBoolean());
      assertEquals(200, get(server, "/plan-lab").statusCode());
      assertEquals(200, get(server, "/assistant.mjs").statusCode());
      assertEquals(
          400,
          post(server, "/api/assistant/chat", request("query-lab", Input.examples().get(0).input()))
              .statusCode());
    }
  }

  @Test
  void sourceIndexPinsBothEnginesAndRejectsArbitraryPaths() {
    var index = AssistantSources.instance();
    assertTrue(index.size() > 2000);
    for (var db : List.of("calcite", "starrocks")) {
      var sources = index.search(db, "FilterJoinRule pushdown", List.of());
      assertFalse(sources.isEmpty());
      for (var s : sources) {
        assertEquals(db, s.database());
        assertTrue(s.commit().matches("[a-f0-9]{40}"));
        assertTrue(s.sha256().matches("[a-f0-9]{64}"));
        assertEquals(s.endLine() - s.startLine() + 1, s.text().split("\n", -1).length);
        assertEquals(s, index.get(s.id()));
      }
    }
    assertThrows(IllegalArgumentException.class, () -> index.get("../../etc/passwd:1"));
    assertThrows(IllegalArgumentException.class, () -> index.get("https://evil.test:1"));
  }

  @Test
  void utf8StreamingAndSignedSqlProposal() throws Exception {
    var input = Input.examples().get(1).input();
    var count = new AtomicInteger();
    try (var gateway =
            new Gateway(
                req -> {
                  int n = count.getAndIncrement();
                  return n == 0
                      ? tool(
                          "propose_input",
                          Map.of(
                              "title",
                              "Try constant reduction",
                              "inputJson",
                              Json.MAPPER.writeValueAsString(input)))
                      : text("待验证建议：请运行候选输入。 <script>never execute</script>");
                });
        var server = new Server(0, directory, config(gateway))) {
      var response = post(server, "/api/assistant/chat", request("query-lab", input));
      assertEquals(200, response.statusCode());
      assertEquals(1, events(response.body(), "done").size());
      assertTrue(response.body().contains("待验证"));
      assertFalse(get(server, "/api/assistant/status").body().contains("secret-not-for-browser"));
      var proposals = events(response.body(), "proposal");
      assertEquals(1, proposals.size(), response.body());
      assertEquals(
          200, post(server, "/api/assistant/proposals/validate", proposals.get(0)).statusCode());
      ((ObjectNode) proposals.get(0).get("input")).put("sql", "SELECT 2");
      assertEquals(
          400, post(server, "/api/assistant/proposals/validate", proposals.get(0)).statusCode());
      assertEquals("[ ]", get(server, "/api/runs").body());
    }
  }

  @Test
  void completePlanProposalUsesWorkerAndPreservesNoHistory() throws Exception {
    var input = PlanExamples.all().get(0).input();
    var count = new AtomicInteger();
    try (var gateway =
            new Gateway(
                req ->
                    count.getAndIncrement() == 0
                        ? tool(
                            "propose_input",
                            Map.of(
                                "title",
                                "Candidate plan",
                                "inputJson",
                                Json.MAPPER.writeValueAsString(input)))
                        : text("User validation required."));
        var server = new Server(0, directory, config(gateway))) {
      var response = post(server, "/api/assistant/chat", request("plan-lab", input));
      var proposals = events(response.body(), "proposal");
      assertEquals(1, proposals.size(), response.body());
      var result = post(server, "/api/assistant/proposals/validate", proposals.get(0));
      assertEquals(200, result.statusCode(), result.body());
      assertTrue(Json.MAPPER.readTree(result.body()).hasNonNull("snapshot"));
      assertEquals(0, Json.MAPPER.readTree(get(server, "/api/runs").body()).size());
    }
  }

  @Test
  void citationsMustResolveSuppliedEvidence() throws Exception {
    var count = new AtomicInteger();
    var source = new AtomicReference<String>();
    try (var gateway =
            new Gateway(
                req -> {
                  int n = count.getAndIncrement();
                  if (n == 0) {
                    String content = req.get("messages").get(1).path("content").asText();
                    var context =
                        Json.MAPPER.readTree(
                            content.substring(
                                content.indexOf('{'), content.lastIndexOf("\n\nQuestion:")));
                    source.set(context.path("sourceExcerpts").get(0).path("id").asText());
                    return tool("cite_source", Map.of("id", source.get()));
                  }
                  if (n == 1) return tool("cite_node", Map.of("id", "invented"));
                  return text("Source analysis only.");
                });
        var server = new Server(0, directory, config(gateway))) {
      var input = Input.examples().get(0).input();
      var response = post(server, "/api/assistant/chat", request("query-lab", input));
      assertEquals(1, events(response.body(), "source").size(), response.body());
      assertEquals(0, events(response.body(), "node").size());
      assertEquals(1, events(response.body(), "notice").size());
      assertEquals(200, get(server, "/api/assistant/sources/" + source.get()).statusCode());
    }
  }

  @Test
  void truncatedStreamCannotProduceProposal() throws Exception {
    try (var gateway =
            new Gateway(
                req ->
                    event(
                        Map.of(
                            "tool_calls",
                            List.of(
                                Map.of(
                                    "index",
                                    0,
                                    "id",
                                    "c1",
                                    "function",
                                    Map.of("name", "propose_input", "arguments", "{")))),
                        null));
        var server = new Server(0, directory, config(gateway))) {
      var response =
          post(
              server, "/api/assistant/chat", request("query-lab", Input.examples().get(0).input()));
      assertEquals(1, events(response.body(), "error").size());
      assertTrue(events(response.body(), "proposal").isEmpty());
      assertTrue(events(response.body(), "done").isEmpty());
    }
  }

  @Test
  void lookupLimitAndInvalidProposalAreReported() throws Exception {
    var count = new AtomicInteger();
    try (var gateway =
            new Gateway(
                req -> {
                  count.incrementAndGet();
                  return tool("lookup_source", Map.of("query", "ProjectMergeRule"));
                });
        var server = new Server(0, directory, config(gateway))) {
      var response =
          post(
              server, "/api/assistant/chat", request("query-lab", Input.examples().get(0).input()));
      assertEquals(5, count.get());
      assertFalse(events(response.body(), "notice").isEmpty());
    }
    try (var gateway =
            new Gateway(
                req -> tool("propose_input", Map.of("title", "Invalid", "inputJson", "{}")));
        var server = new Server(0, directory.resolve("invalid"), config(gateway))) {
      var response =
          post(
              server, "/api/assistant/chat", request("query-lab", Input.examples().get(0).input()));
      assertTrue(events(response.body(), "proposal").isEmpty());
      assertFalse(events(response.body(), "notice").isEmpty());
    }
  }

  @Test
  void cancellationAndConcurrencyDoNotBlockHealth() throws Exception {
    var entered = new CountDownLatch(2);
    try (var gateway =
            new Gateway(
                req -> {
                  entered.countDown();
                  Thread.sleep(20000);
                  return text("late");
                });
        var server = new Server(0, directory, config(gateway))) {
      var a = request("query-lab", Input.examples().get(0).input());
      var b = request("query-lab", Input.examples().get(0).input());
      var first =
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  return post(server, "/api/assistant/chat", a);
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
              });
      var second =
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  return post(server, "/api/assistant/chat", b);
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
              });
      assertTrue(entered.await(10, TimeUnit.SECONDS));
      assertEquals(200, get(server, "/api/health").statusCode());
      assertEquals(
          429,
          post(server, "/api/assistant/chat", request("query-lab", Input.examples().get(0).input()))
              .statusCode());
      post(server, "/api/assistant/cancel", a);
      post(server, "/api/assistant/cancel", b);
      assertEquals(1, events(first.get(5, TimeUnit.SECONDS).body(), "error").size());
      assertEquals(1, events(second.get(5, TimeUnit.SECONDS).body(), "error").size());
    }
  }

  @Test
  void deepSeekUsesNonThinkingToolCallsWithoutChangingOtherGateways() {
    var messages =
        Json.MAPPER
            .createArrayNode()
            .addObject()
            .put("role", "user")
            .put("content", "Explain the plan.");
    var history = Json.MAPPER.createArrayNode().add(messages);
    var deepseek =
        new AssistantModel(
            new AssistantModel.Config(
                "https://api.deepseek.com/chat/completions", "deepseek-flash", "", true, 120));
    var payload = deepseek.requestBody(history, AssistantModel.tools());
    assertEquals("disabled", payload.path("thinking").path("type").asText());
    assertTrue(payload.path("stream").asBoolean());
    assertEquals(4, payload.path("tools").size());
    var generic =
        new AssistantModel(
            new AssistantModel.Config(
                "https://gateway.example/v1/chat/completions", "configured-model", "", true, 120));
    assertFalse(generic.requestBody(history, AssistantModel.tools()).has("thinking"));
  }

  @Test
  void textOnlyModeDoesNotAdvertiseTools() throws Exception {
    try (var gateway =
            new Gateway(
                req -> {
                  assertFalse(req.has("tools"));
                  return text("Plain explanation");
                });
        var server =
            new Server(
                0,
                directory,
                new AssistantModel.Config(gateway.endpoint(), "text-model", "", false, 30))) {
      var response =
          post(
              server, "/api/assistant/chat", request("query-lab", Input.examples().get(0).input()));
      assertEquals(1, events(response.body(), "done").size());
      assertFalse(
          Json.MAPPER
              .readTree(get(server, "/api/assistant/status").body())
              .path("tools")
              .asBoolean());
    }
  }

  @Test
  void deadlineTerminatesStalledGateway() throws Exception {
    try (var gateway =
            new Gateway(
                req -> {
                  Thread.sleep(20000);
                  return text("late");
                });
        var server =
            new Server(
                0, directory, new AssistantModel.Config(gateway.endpoint(), "slow", "", true, 1))) {
      long start = System.nanoTime();
      try {
        var response =
            post(
                server,
                "/api/assistant/chat",
                request("query-lab", Input.examples().get(0).input()));
        assertTrue(events(response.body(), "done").isEmpty());
      } catch (java.io.IOException expected) {
      }
      assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 5);
      assertEquals(200, get(server, "/api/health").statusCode());
    }
  }

  @Test
  void contextRejectsMissingRunsAndUsesActualSnapshots() throws Exception {
    try (var jobs = new Jobs(directory)) {
      var context = request("plan-lab", PlanExamples.all().get(0).input()).path("context");
      ((ObjectNode) context).put("runId", "missing");
      assertThrows(
          IllegalArgumentException.class, () -> new AssistantContext(context, jobs, "why"));
      var run = new Optimizer().optimize(Input.examples().get(0).input());
      var saved = jobs.imported(run);
      ((ObjectNode) context).put("runId", saved.id());
      var built = new AssistantContext(context, jobs, "why");
      assertEquals("imported-unverified", built.data.path("run").path("provenance").asText());
      assertFalse(built.references.isEmpty());
      assertEquals(
          run.initial().rootId(), built.data.path("run").path("before").path("rootId").asText());
    }
  }
}
