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
  private final ExecutorService requests=Executors.newFixedThreadPool(8);
  private static final Map<String,String> ASSETS=Map.of("/","index.html","/index.html","index.html","/app.mjs","app.mjs","/graph.mjs","graph.mjs","/styles.css","styles.css");
  public Server(int port,Path data) throws Exception {
    jobs=new Jobs(data);http=HttpServer.create(new InetSocketAddress("127.0.0.1",port),32);
    http.createContext("/",this::handle);http.setExecutor(requests);http.start();
  }
  public int port(){return http.getAddress().getPort();}
  private void handle(HttpExchange x){
    try{
      String host=x.getRequestHeaders().getFirst("Host");
      // Browser previews can expose a different loopback port and may rewrite Host.
      if(!LocalAccess.allowsHost(host)){reply(x,403,Map.of("error","仅允许本地访问"));return;}
      String path=x.getRequestURI().getPath(),method=x.getRequestMethod();
      if(path.startsWith("/api/")){
        if(method.equals("POST")){
          String origin=x.getRequestHeaders().getFirst("Origin");
          if(!LocalAccess.allowsOrigin(origin)){reply(x,403,Map.of("error","请求来源不允许"));return;}
          String type=x.getRequestHeaders().getFirst("Content-Type");
          if(type==null||!type.toLowerCase(Locale.ROOT).startsWith("application/json")){reply(x,415,Map.of("error","请使用 application/json"));return;}
        }
        if(path.equals("/api/health") && method.equals("GET")){reply(x,200,Map.of("status","ok","version","0.1.0"));return;}
        if(path.equals("/api/catalog") && method.equals("GET")){reply(x,200,Map.of("rules",Rules.cards(),"examples",Input.examples(),"engineVersion","1.39.0","databases",List.of(Map.of("id","calcite","title","Apache Calcite","version","1.39.0")),"scope",Rules.SCOPE,"maxSelected",Rules.MAX_SELECTED));return;}
        if(path.equals("/api/runs")){
          if(method.equals("GET")){reply(x,200,jobs.list());return;}
          if(method.equals("POST")){reply(x,202,jobs.submit(Json.MAPPER.readValue(body(x,100_000),Request.class)));return;}
        }
        if(path.equals("/api/import") && method.equals("POST")){reply(x,201,jobs.imported(Json.MAPPER.readValue(body(x,8_000_000),Run.class)));return;}
        if(method.equals("GET") && path.matches("/api/runs/[0-9a-f-]{36}(/download)?")){
          String id=path.split("/")[3];var job=jobs.get(id);
          if(job==null){reply(x,404,Map.of("error","记录不存在"));return;}
          if(path.endsWith("/download")){
            if(job.result()==null){reply(x,409,Map.of("error","该运行还没有可导出的结果"));return;}
            x.getResponseHeaders().set("Content-Disposition","attachment; filename=optilens-"+id+".json");reply(x,200,job.result());
          }else reply(x,200,job);return;
        }
        reply(x,404,Map.of("error","接口或请求方法不存在"));return;
      }
      if(!method.equals("GET")||!ASSETS.containsKey(path)){reply(x,404,Map.of("error","页面不存在"));return;}
      String asset=ASSETS.get(path);
      try(var input=Server.class.getResourceAsStream("/web/"+asset)){
        if(input==null){reply(x,404,Map.of("error","页面资源不存在"));return;}
        String type=asset.endsWith(".css")?"text/css":asset.endsWith(".mjs")?"text/javascript":"text/html";
        send(x,200,type+"; charset=utf-8",input.readAllBytes());
      }
    }catch(RejectedExecutionException e){safeError(x,429,"优化队列已满，请稍后重试");}
    catch(IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException e){safeError(x,400,Worker.error(e));}
    catch(Exception e){System.err.println("Request failed: "+e.getClass().getSimpleName());safeError(x,500,"服务处理失败，请检查本地存储和服务日志");}
    finally{x.close();}
  }
  private byte[] body(HttpExchange x,int limit) throws Exception {
    byte[] b=x.getRequestBody().readNBytes(limit+1);Input.require(b.length<=limit,"请求内容超过大小上限");return b;
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
  @Override public void close(){http.stop(1);jobs.close();requests.shutdownNow();}
}
