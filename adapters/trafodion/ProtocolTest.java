import dev.optilens.*;
import dev.optilens.trafodion.TrafodionWorker;

/** Protocol fixtures are not evidence that a native rule actually fired. */
public final class ProtocolTest {
  public static void main(String[] args) throws Exception {
    var request = Trafodion.sqlExamples().get(0).input();
    String snapshot =
        "{\"scope\":\"full-plan\",\"rootId\":\"n1\",\"nodes\":[{\"id\":\"n1\",\"type\":\"root\",\"attributes\":{},\"fields\":[],\"inputs\":[]}],\"raw\":\"root\"}";
    String commit = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    var data =
        Json.MAPPER.readTree(
            "{\"protocolVersion\":1,\"sourceCommit\":\""
                + commit
                + "\",\"initial\":"
                + snapshot
                + ",\"finalPlan\":"
                + snapshot
                + ",\"events\":[],\"truncated\":true}");
    var run = TrafodionWorker.decode(request, data, commit, "test-build");
    assert run.engine().equals("trafodion");
    assert run.stages().size() == request.rules().size();
    assert run.limitations().stream().anyMatch(s -> s.contains("truncated"));
    assert run.stages().stream().allMatch(s -> s.events().isEmpty());
    assert run.editableResult().plan() == null;
    Records.validate(run);
    expectFailure(() -> TrafodionWorker.decode(request, data, "wrong", "test-build"));
    var event = Json.MAPPER.createObjectNode();
    event.put("rule", "CoreRules.FILTER_MERGE");
    event.put("callId", 1);
    ((com.fasterxml.jackson.databind.node.ArrayNode) data.get("events")).add(event);
    expectFailure(() -> TrafodionWorker.decode(request, data, commit, "test-build"));
    System.out.println(
        "Native protocol identity, event filtering, truncation and read-only record tests passed.");
  }

  interface Checked {
    void run() throws Exception;
  }

  static void expectFailure(Checked task) throws Exception {
    try {
      task.run();
    } catch (IllegalArgumentException expected) {
      return;
    }
    throw new AssertionError("Expected protocol rejection");
  }
}
