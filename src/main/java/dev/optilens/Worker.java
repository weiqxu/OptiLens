package dev.optilens;

import static dev.optilens.Model.*;

import java.nio.file.*;

/** A separate, bounded JVM keeps optimizer failures and timeouts out of the HTTP server. */
public final class Worker {
  public record Outcome(Run result, String error, PlanValidation validation) {
    public Outcome(Run result, String error) {
      this(result, error, null);
    }
  }

  public static void main(String[] args) throws Exception {
    Outcome outcome;
    try {
      var input = Json.MAPPER.readValue(Path.of(args[0]).toFile(), Request.class);
      outcome =
          args.length > 2 && args[2].equals("validate")
              ? new Outcome(null, null, new Optimizer().validate(input))
              : new Outcome(new Optimizer().optimize(input), null);
    } catch (Exception | StackOverflowError | AssertionError e) {
      outcome = new Outcome(null, error(e));
    }
    byte[] bytes = Json.MAPPER.writeValueAsBytes(outcome);
    if (bytes.length > 8_000_000)
      bytes =
          Json.MAPPER.writeValueAsBytes(
              new Outcome(
                  null, "Run record exceeds the 8 MB limit. Use a smaller query or fewer rules."));
    Files.write(Path.of(args[1]), bytes);
  }

  static String error(Throwable e) {
    String message = e.getMessage();
    if (message == null || message.isBlank()) message = e.getClass().getSimpleName();
    return message.length() > 2000 ? message.substring(0, 2000) : message;
  }
}
