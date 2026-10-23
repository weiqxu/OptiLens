package dev.optilens;

import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static dev.optilens.Model.*;

public final class Server implements AutoCloseable {
  private final HttpServer http;
  private final Jobs jobs;
  private final Assistant assistant;
  private final ExecutorService requests=Executors.newFixedThreadPool(8);
  private static final Map<String,String> ASSETS=Map.ofEntries(Map.entry("/assistant.mjs","assistant.mjs"),Map.entry("/assistant.css","assistant.css"),Map.entry("/plan-lab","plan-lab.html"),Map.entry("/plan-lab.mjs","plan-lab.mjs"),Map.entry("/plan-editor.mjs","plan-editor.mjs"),Map.entry("/plan-lab.css","plan-lab.css"),Map.entry("/","index.html"),Map.entry("/index.html","index.html"),Map.entry("/app.mjs","app.mjs"),Map.entry("/graph.mjs","graph.mjs"),Map.entry("/styles.css","styles.css"),Map.entry("/rules","rules.html"),Map.entry("/rules.html","rules.html"),Map.entry("/rules.mjs","rules.mjs"),Map.entry("/rules.css","rules.css"));
  public Server(int port,Path data) throws Exception {
    this(port,data,AssistantModel.Config.environment());
  }
  Server(int port,Path data,AssistantModel.Config config) throws Exception {
    jobs=new Jobs(data);assistant=new Assistant(jobs,config);http=HttpServer.create(new InetSocketAddress("127.0.0.1",port),32);
    http.createContext("/",this::handle);http.setExecutor(requests);http.start();
  }
  public int port(){return http.getAddress().getPort();}
  private void handle(HttpExchange x){
    boolean streaming=false;
    try{
      String host=x.getRequestHeaders().getFirst("Host");
      // Browser previews can expose a different loopback port and may rewrite Host.
      if(!LocalAccess.allowsHost(host)){reply(x,403,Map.of("error","Only local access is allowed"));return;}
      String path=x.getRequestURI().getPath(),method=x.getRequestMethod();
      if(path.startsWith("/api/")){
        if(method.equals("POST")){
          String origin=x.getRequestHeaders().getFirst("Origin");
          if(!LocalAccess.allowsOrigin(origin)){reply(x,403,Map.of("error","Request origin is not allowed"));return;}
          String type=x.getRequestHeaders().getFirst("Content-Type");
          if(type==null||!type.toLowerCase(Locale.ROOT).startsWith("application/json")){reply(x,415,Map.of("error","Use Content-Type: application/json"));return;}
        }
        if(path.equals("/api/assistant/status")&&method.equals("GET")){reply(x,200,assistant.status());return;}
        if(path.equals("/api/assistant/chat")&&method.equals("POST")){assistant.start(x,Json.MAPPER.readTree(body(x,250_000)));streaming=true;return;}
        if(path.equals("/api/assistant/cancel")&&method.equals("POST")){assistant.cancel(Json.MAPPER.readTree(body(x,1000)).path("requestId").asText());reply(x,200,Map.of("status","stopped"));return;}
        if(path.equals("/api/assistant/proposals/validate")&&method.equals("POST")){var result=assistant.validateProposal(Json.MAPPER.readTree(body(x,110_000)));reply(x,result.path("valid").asBoolean()?200:422,result);return;}
        if(path.startsWith("/api/assistant/sources/")&&method.equals("GET")){reply(x,200,AssistantSources.instance().get(path.substring("/api/assistant/sources/".length())));return;}
        if(path.equals("/api/plan-capabilities") && method.equals("GET")){reply(x,200,Engines.planCapabilities(database(x)));return;}
        if(path.equals("/api/plans/validate") && method.equals("POST")){var result=jobs.validatePlan(Json.MAPPER.readValue(body(x,100_000),Request.class));reply(x,result.valid()?200:422,result);return;}
        if(path.equals("/api/starrocks/verification") && method.equals("GET")){reply(x,200,StarRocks.verification());return;}
        if(path.equals("/api/health") && method.equals("GET")){reply(x,200,Map.of("status","ok","version","0.1.0"));return;}
        if(path.equals("/api/rule-examples") && method.equals("GET")){reply(x,200,Engines.examples(database(x)));return;}
        if(path.equals("/api/catalog") && method.equals("GET")){reply(x,200,Engines.catalog(database(x)));return;}
        if(path.equals("/api/runs")){
          if(method.equals("GET")){reply(x,200,jobs.list());return;}
          if(method.equals("POST")){reply(x,202,jobs.submit(Json.MAPPER.readValue(body(x,100_000),Request.class)));return;}
        }
        if(path.equals("/api/import") && method.equals("POST")){reply(x,201,jobs.imported(Json.MAPPER.readValue(body(x,8_000_000),Run.class)));return;}
        if(method.equals("GET") && path.matches("/api/runs/[0-9a-f-]{36}(/download)?")){
          String id=path.split("/")[3];var job=jobs.get(id);
          if(job==null){reply(x,404,Map.of("error","Record not found"));return;}
          if(path.endsWith("/download")){
            if(job.result()==null){reply(x,409,Map.of("error","This run has no result to export yet"));return;}
            x.getResponseHeaders().set("Content-Disposition","attachment; filename=optilens-"+id+".json");reply(x,200,job.result());
          }else reply(x,200,job);return;
        }
        reply(x,404,Map.of("error","Endpoint or request method not found"));return;
      }
      if(!method.equals("GET")||!ASSETS.containsKey(path)){reply(x,404,Map.of("error","Page not found"));return;}
      String asset=ASSETS.get(path);
      try(var input=Server.class.getResourceAsStream("/web/"+asset)){
        if(input==null){reply(x,404,Map.of("error","Page asset not found"));return;}
        String type=asset.endsWith(".css")?"text/css":asset.endsWith(".mjs")?"text/javascript":"text/html";
        send(x,200,type+"; charset=utf-8",input.readAllBytes());
      }
    }catch(RejectedExecutionException e){safeError(x,429,"The service is at its concurrency limit. Try again later.");}
    catch(IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException e){safeError(x,400,Worker.error(e));}
    catch(Exception e){System.err.println("Request failed: "+e.getClass().getSimpleName());safeError(x,500,"Request processing failed. Check local storage and service logs.");}
    finally{if(!streaming)x.close();}
  }
  private String database(HttpExchange exchange) {
    String database="calcite",query=exchange.getRequestURI().getRawQuery();
    if(query!=null)for(String part:query.split("&")){String[] pair=part.split("=",2);if(pair[0].equals("database")){Input.require(pair.length==2,"Missing database selector");database=java.net.URLDecoder.decode(pair[1],java.nio.charset.StandardCharsets.UTF_8);}}
    Engines.validateDatabase(database);return database;
  }
  private byte[] body(HttpExchange x,int limit) throws Exception {
    byte[] b=x.getRequestBody().readNBytes(limit+1);Input.require(b.length<=limit,"Request body exceeds the size limit");return b;
  }
  private void safeError(HttpExchange x,int status,String message){try{reply(x,status,Map.of("error",message));}catch(Exception ignored){}}
  private void reply(HttpExchange x,int status,Object obj) throws Exception {send(x,status,"application/json; charset=utf-8",Json.MAPPER.writeValueAsBytes(obj));}
  private void send(HttpExchange x,int status,String type,byte[] data) throws Exception {
    var h=x.getResponseHeaders();h.set("Content-Type",type);h.set("X-Content-Type-Options","nosniff");h.set("Cache-Control","no-store");
    h.set("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'");
    x.sendResponseHeaders(status,data.length);x.getResponseBody().write(data);
  }
  public static void main(String[] args) throws Exception {
    int port=Integer.parseInt(System.getenv().getOrDefault("OPTILENS_PORT","8080"));
    Path data=Path.of(System.getenv().getOrDefault("OPTILENS_DATA_DIR","data/runs"));
    var server=new Server(port,data);Runtime.getRuntime().addShutdownHook(new Thread(server::close));
    System.out.println("OptiLens 0.1.0 — http://127.0.0.1:"+server.port());
    System.out.println("Local run records: "+data.toAbsolutePath());
  }
  @Override public void close(){assistant.close();http.stop(1);jobs.close();requests.shutdownNow();}
}
