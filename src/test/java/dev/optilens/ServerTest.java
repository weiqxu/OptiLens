package dev.optilens;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerTest {
  @TempDir Path directory;
  final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  HttpResponse<String> request(Server server, String method, String path, String data)
      throws Exception {
    var b =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
            // Worker validation may use its complete 25-second execution budget.
            .timeout(Duration.ofSeconds(35));
    if (method.equals("POST"))
      b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(data));
    else b.GET();
    return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void databaseCatalogAndCapabilitiesUseTheRequestedEngine() throws Exception {
    try (var server = new Server(0, directory)) {
      var catalog =
          Json.MAPPER.readTree(
              request(server, "GET", "/api/catalog?database=starrocks", null).body());
      assertEquals("starrocks", catalog.path("database").asText());
      assertEquals(StarRocks.VERSION, catalog.path("engineVersion").asText());
      assertEquals(StarRocks.rules().size(), catalog.path("rules").size());
      assertEquals(3, catalog.path("databases").size());
      assertEquals(200, request(server, "GET", "/database-options.mjs", null).statusCode());
      var databases = catalog.path("databases");
      assertTrue(databases.get(0).path("available").asBoolean());
      assertEquals(
          StarRocks.runtime().get("available"), databases.get(1).path("available").asBoolean());
      assertEquals(
          Trafodion.runtime().get("available"), databases.get(2).path("available").asBoolean());
      assertFalse(databases.get(1).path("message").asText().isBlank());
      var trafodion =
          Json.MAPPER.readTree(
              request(server, "GET", "/api/catalog?database=trafodion", null).body());
      assertEquals(66, trafodion.path("rules").size());
      assertEquals(
          Trafodion.runtime().get("available"),
          trafodion.path("runtime").path("available").asBoolean());
      var capabilities =
          Json.MAPPER.readTree(
              request(server, "GET", "/api/plan-capabilities?database=starrocks", null).body());
      assertFalse(capabilities.path("operators").has("Calc"));
      assertEquals(200, request(server, "GET", "/api/starrocks/verification", null).statusCode());
      assertEquals(400, request(server, "GET", "/api/catalog?database=invalid", null).statusCode());
    }
  }

  @Test
  void planValidationUsesWorkerWithoutCreatingHistory() throws Exception {
    try (var server = new Server(0, directory)) {
      assertEquals(200, request(server, "GET", "/plan-lab", null).statusCode());
      assertEquals(200, request(server, "GET", "/plan-editor.mjs", null).statusCode());
      var capabilities =
          Json.MAPPER.readTree(request(server, "GET", "/api/plan-capabilities", null).body());
      assertEquals(14, capabilities.get("operators").size());
      var input = PlanExamples.all().get(0).input();
      var response =
          request(server, "POST", "/api/plans/validate", Json.MAPPER.writeValueAsString(input));
      assertEquals(200, response.statusCode(), response.body());
      assertTrue(Json.MAPPER.readTree(response.body()).get("valid").asBoolean());
      assertEquals(
          0, Json.MAPPER.readTree(request(server, "GET", "/api/runs", null).body()).size());
      var broken = Json.MAPPER.valueToTree(input);
      ((com.fasterxml.jackson.databind.node.ObjectNode) broken.get("plan"))
          .put("rootId", "missing");
      var invalid = request(server, "POST", "/api/plans/validate", broken.toString());
      assertEquals(422, invalid.statusCode());
      assertTrue(Json.MAPPER.readTree(invalid.body()).get("errors").size() > 0);
      try (var files = java.nio.file.Files.list(directory)) {
        assertEquals(0, files.count());
      }
    }
  }

  @Test
  void httpLifecycleImportExportAndRestart() throws Exception {
    String id;
    try (var server = new Server(0, directory)) {
      assertEquals(200, request(server, "GET", "/", null).statusCode());
      assertEquals(200, request(server, "GET", "/app.mjs", null).statusCode());
      assertEquals(200, request(server, "GET", "/rules", null).statusCode());
      assertEquals(200, request(server, "GET", "/rules.mjs", null).statusCode());
      assertEquals(200, request(server, "GET", "/rules.css", null).statusCode());
      var library = Json.MAPPER.readTree(request(server, "GET", "/api/rule-examples", null).body());
      assertEquals("calcite", library.get("database").asText());
      assertEquals(RuleExamples.all().size(), library.get("examples").size());
      assertEquals(RuleExamples.coverage().size(), library.get("coverage").size());
      var catalog = Json.MAPPER.readTree(request(server, "GET", "/api/catalog", null).body());
      assertEquals("calcite", catalog.get("databases").get(0).get("id").asText());
      assertTrue(catalog.get("rules").size() > 200);
      var unsupported = Json.MAPPER.valueToTree(Input.examples().get(0).input());
      ((com.fasterxml.jackson.databind.node.ObjectNode) unsupported).put("database", "postgres");
      assertEquals(400, request(server, "POST", "/api/runs", unsupported.toString()).statusCode());
      var response =
          request(
              server,
              "POST",
              "/api/runs",
              Json.MAPPER.writeValueAsString(Input.examples().get(0).input()));
      assertEquals(202, response.statusCode(), response.body());
      id = Json.MAPPER.readTree(response.body()).get("id").asText();
      Jobs.Job job = null;
      for (int i = 0; i < 160; i++) {
        job =
            Json.MAPPER.readValue(
                request(server, "GET", "/api/runs/" + id, null).body(), Jobs.Job.class);
        if (job.status().equals("succeeded") || job.status().equals("failed")) break;
        Thread.sleep(250);
      }
      assertNotNull(job);
      assertEquals("succeeded", job.status(), job.error());
      var exported = request(server, "GET", "/api/runs/" + id + "/download", null);
      assertEquals(200, exported.statusCode());
      var imported = request(server, "POST", "/api/import", exported.body());
      assertEquals(201, imported.statusCode(), imported.body());
      assertTrue(Json.MAPPER.readTree(imported.body()).get("imported").asBoolean());
      assertEquals(400, request(server, "POST", "/api/import", "{}").statusCode());
      assertEquals(400, request(server, "POST", "/api/runs", "not json").statusCode());
      assertEquals(
          404,
          request(server, "GET", "/api/runs/00000000-0000-0000-0000-000000000000", null)
              .statusCode());
      var cross =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/runs"))
              .header("Origin", "https://example.org")
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString("{}"))
              .build();
      assertEquals(403, client.send(cross, HttpResponse.BodyHandlers.ofString()).statusCode());
    }
    try (var restarted = new Server(0, directory)) {
      assertEquals(
          "succeeded",
          Json.MAPPER
              .readTree(request(restarted, "GET", "/api/runs/" + id, null).body())
              .get("status")
              .asText());
    }
  }

  @Test
  void workerTimeoutBecomesAFailedRecord() throws Exception {
    try (var jobs = new Jobs(directory, 0)) {
      var job = jobs.submit(Input.examples().get(0).input());
      for (int i = 0; i < 100 && !jobs.get(job.id()).status().equals("failed"); i++)
        Thread.sleep(50);
      assertEquals("failed", jobs.get(job.id()).status());
      assertTrue(jobs.get(job.id()).error().contains("seconds"));
    }
  }

  @Test
  void forwardedLoopbackAuthorityWorksForPagesAndSubmittingQueries() throws Exception {
    try (var server = new Server(0, directory)) {
      assertEquals(200, forwarded(server, "GET", "/", "localhost:49894", null, null));
      assertEquals(200, forwarded(server, "GET", "/api/catalog", "127.0.0.1:49894", null, null));
      var input = Input.examples().get(5).input();
      String body =
          Json.MAPPER.writeValueAsString(
              new Model.Request(input.sql(), input.schema(), java.util.List.of()));
      assertEquals(
          202,
          forwarded(
              server, "POST", "/api/runs", "localhost:49894", "http://localhost:49894", body));
      // Some local preview proxies rewrite Host to the backend address but preserve browser Origin.
      assertEquals(
          202,
          forwarded(
              server,
              "POST",
              "/api/runs",
              "127.0.0.1:" + server.port(),
              "http://localhost:49894",
              body));
      assertEquals(
          403,
          forwarded(server, "POST", "/api/runs", "localhost:49894", "https://example.org", body));
      assertEquals(403, forwarded(server, "GET", "/", "example.org:49894", null, null));
      assertEquals(403, forwarded(server, "GET", "/", "localhost.evil:49894", null, null));
    }
  }

  private int forwarded(
      Server server, String method, String path, String host, String origin, String body)
      throws Exception {
    // A raw connection allows an explicit Host header without weakening the HTTP client's checks.
    try (var socket = new java.net.Socket("127.0.0.1", server.port())) {
      socket.setSoTimeout(10000);
      byte[] payload =
          body == null ? new byte[0] : body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      String head =
          method
              + " "
              + path
              + " HTTP/1.1\r\nHost: "
              + host
              + "\r\nConnection: close\r\n"
              + (origin == null ? "" : "Origin: " + origin + "\r\n")
              + "Content-Type: application/json\r\nContent-Length: "
              + payload.length
              + "\r\n\r\n";
      socket.getOutputStream().write(head.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
      socket.getOutputStream().write(payload);
      socket.getOutputStream().flush();
      var reader =
          new java.io.BufferedReader(
              new java.io.InputStreamReader(
                  socket.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
      return Integer.parseInt(reader.readLine().split(" ")[1]);
    }
  }
}
