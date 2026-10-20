package dev.optilens;

import java.nio.file.*;
import static dev.optilens.Model.*;

/** A separate, bounded JVM keeps optimizer failures and timeouts out of the HTTP server. */
public final class Worker {
  public record Outcome(Run result,String error){}
  public static void main(String[] args) throws Exception {
    Outcome outcome;
    try {
      var input=Json.MAPPER.readValue(Path.of(args[0]).toFile(),Request.class);
      outcome=new Outcome(new Optimizer().optimize(input),null);
    }catch(Exception | StackOverflowError e){outcome=new Outcome(null,error(e));}
    byte[] bytes=Json.MAPPER.writeValueAsBytes(outcome);
    if(bytes.length>8_000_000)bytes=Json.MAPPER.writeValueAsBytes(new Outcome(null,"运行记录超过 8 MB 上限，请缩小查询或规则集合"));
    Files.write(Path.of(args[1]),bytes);
  }
  static String error(Throwable e){
    String message=e.getMessage();if(message==null||message.isBlank())message=e.getClass().getSimpleName();
    return message.length()>2000?message.substring(0,2000):message;
  }
}
