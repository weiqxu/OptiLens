package dev.optilens.starrocks;

import dev.optilens.*;
import dev.optilens.Model.*;
import java.nio.file.*;

/** Process boundary for the native frontend and its separate dependency graph. */
public final class StarRocksWorker {
  public static void main(String[] args) throws Exception {
    Worker.Outcome outcome;
    try {
      NativeVersion.verify();
      var request=Json.MAPPER.readValue(Path.of(args[0]).toFile(),Request.class);
      Input.validate(request);
      var optimizer=new NativeOptimizer(new NativeRegistry());
      outcome=args.length>2&&args[2].equals("validate")?new Worker.Outcome(null,null,optimizer.validate(request)):new Worker.Outcome(optimizer.optimize(request),null);
    }catch(Throwable e){String error=NativeRegistry.message(e);outcome=new Worker.Outcome(null,error.substring(0,Math.min(error.length(),2000)));}
    byte[] bytes=Json.MAPPER.writeValueAsBytes(outcome);
    if(bytes.length>8_000_000)bytes=Json.MAPPER.writeValueAsBytes(new Worker.Outcome(null,"Run record exceeds the 8 MB limit"));
    Files.write(Path.of(args[1]),bytes);System.exit(0);
  }
}
