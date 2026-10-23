package dev.optilens;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Bounded Chat Completions streaming adapter. No provider SDK or browser credentials. */
final class AssistantModel {
  record Config(String endpoint,String model,String key,boolean tools,int timeoutSeconds){
    static Config environment(){var env=System.getenv();return new Config(env.getOrDefault("OPTILENS_ASSISTANT_ENDPOINT",""),env.getOrDefault("OPTILENS_ASSISTANT_MODEL",""),env.getOrDefault("OPTILENS_ASSISTANT_API_KEY",""),!"false".equalsIgnoreCase(env.getOrDefault("OPTILENS_ASSISTANT_TOOLS","true")),120);}
    boolean enabled(){return !endpoint.isBlank()&&!model.isBlank();}
    Config { if(!endpoint.isBlank()){URI uri=URI.create(endpoint);Input.require(Set.of("http","https").contains(uri.getScheme())&&uri.getHost()!=null&&uri.getUserInfo()==null&&uri.getFragment()==null&&uri.getQuery()==null,"Invalid model endpoint");} }
  }
  interface Sink {void event(String type,Object value) throws Exception;}
  static final class Cancellation {
    volatile boolean cancelled;volatile InputStream stream;volatile CompletableFuture<?> pending;
    void cancel(){cancelled=true;var p=pending;if(p!=null)p.cancel(true);try{if(stream!=null)stream.close();}catch(IOException ignored){}}
    void check(){Input.require(!cancelled,"Assistant request cancelled or timed out");}
  }
  record Call(String id,String name,String arguments){}
  record Reply(String text,List<Call> calls){}
  private static final HttpClient CLIENT=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
  private final Config config;
  AssistantModel(Config config){this.config=config;}
  ObjectNode requestBody(ArrayNode messages,ArrayNode tools){
    var body=Json.MAPPER.createObjectNode().put("model",config.model).put("stream",true).put("max_tokens",8192);
    body.set("messages",messages);if(config.tools){body.set("tools",tools);body.put("parallel_tool_calls",false);}
    // DeepSeek defaults to thinking mode, which requires replaying reasoning_content
    // in tool turns. This conversational adapter uses its supported non-thinking mode.
    if("api.deepseek.com".equalsIgnoreCase(URI.create(config.endpoint).getHost()))body.putObject("thinking").put("type","disabled");
    return body;
  }
  Reply complete(ArrayNode messages,ArrayNode tools,Sink sink,Cancellation cancellation) throws Exception {
    cancellation.check();var body=requestBody(messages,tools);
    var request=HttpRequest.newBuilder(URI.create(config.endpoint)).timeout(Duration.ofSeconds(config.timeoutSeconds)).header("Content-Type","application/json");
    if(!config.key.isBlank())request.header("Authorization","Bearer "+config.key);
    var pending=CLIENT.sendAsync(request.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),HttpResponse.BodyHandlers.ofInputStream());
    cancellation.pending=pending;if(cancellation.cancelled)pending.cancel(true);
    var response=pending.get(config.timeoutSeconds,TimeUnit.SECONDS);cancellation.stream=response.body();
    try(var input=response.body()){
      cancellation.check();
      Input.require(response.statusCode()==200,"Model gateway returned HTTP "+response.statusCode()+". Check the configured endpoint, model and capabilities.");
      Input.require(response.headers().firstValue("Content-Type").orElse("").contains("text/event-stream"),"Model gateway did not return a text event stream");
      var text=new StringBuilder();var callIds=new TreeMap<Integer,StringBuilder>();var names=new TreeMap<Integer,StringBuilder>();var args=new TreeMap<Integer,StringBuilder>();
      var line=new ByteArrayOutputStream();var event=new StringBuilder();int bytes=0;boolean done=false;String finish=null;
      // Parse bytes, not transport chunks: UTF-8 and JSON may be split anywhere by the gateway.
      for(int b;(b=input.read())!=-1;){
        cancellation.check();Input.require(++bytes<=2_000_000,"Model response exceeds the size limit");
        if(b!='\n'){line.write(b);Input.require(line.size()<=300_000,"Model event exceeds the size limit");continue;}
        String s=line.toString(StandardCharsets.UTF_8);line.reset();if(s.endsWith("\r"))s=s.substring(0,s.length()-1);
        if(!s.isEmpty()){if(s.startsWith("data:")){if(event.length()>0)event.append('\n');event.append(s.substring(5).stripLeading());}continue;}
        if(event.isEmpty())continue;String payload=event.toString();event.setLength(0);
        if(payload.equals("[DONE]")){done=true;break;}
        JsonNode data=Json.MAPPER.readTree(payload);Input.require(!data.has("error"),"Model gateway reported a streaming error");
        var choice=data.path("choices").path(0);var delta=choice.path("delta");
        if(choice.hasNonNull("finish_reason"))finish=choice.path("finish_reason").asText();
        String content=delta.path("content").asText("");if(!content.isEmpty()){text.append(content);Input.require(text.length()<=40_000,"Assistant answer exceeds the text limit");sink.event("text",Map.of("text",content));}
        for(var call:delta.path("tool_calls")){
          int index=call.path("index").asInt(-1);Input.require(index>=0&&index<16,"Too many or malformed model tool calls");
          if(call.hasNonNull("id"))callIds.computeIfAbsent(index,k->new StringBuilder()).append(call.get("id").asText());
          var fn=call.path("function");if(fn.hasNonNull("name"))names.computeIfAbsent(index,k->new StringBuilder()).append(fn.path("name").asText());
          if(fn.hasNonNull("arguments"))args.computeIfAbsent(index,k->new StringBuilder()).append(fn.path("arguments").asText());
          Input.require(args.getOrDefault(index,new StringBuilder()).length()<=110_000,"Tool arguments exceed the input limit");
        }
      }
      Input.require(done&&Set.of("stop","tool_calls").contains(finish==null?"":finish),"Model response was interrupted or truncated; no incomplete suggestions were accepted");
      var calls=new ArrayList<Call>();for(int index:names.keySet()){
        String id=callIds.getOrDefault(index,new StringBuilder()).toString();Input.require(!id.isBlank()&&id.length()<200,"Invalid tool call ID");
        calls.add(new Call(id,names.get(index).toString(),args.getOrDefault(index,new StringBuilder()).toString()));}
      Input.require(config.tools||calls.isEmpty(),"Gateway returned tool calls in text-only mode");
      return new Reply(text.toString(),calls);
    }finally{cancellation.stream=null;cancellation.pending=null;}
  }
  static ArrayNode tools(){
    var list=Json.MAPPER.createArrayNode();
    tool(list,"lookup_source","Search the bundled, pinned source index. Read-only; no arbitrary paths. Use rule class or helper names.",Map.of("query","Search terms"));
    tool(list,"cite_source","Attach an exact source excerpt already supplied by the server. Never invent an ID.",Map.of("id","Source excerpt ID"));
    tool(list,"cite_node","Attach a clickable node reference from nodeReferences. Never infer cross-snapshot IDs.",Map.of("id","Node reference key"));
    tool(list,"propose_input","Propose one complete candidate input as a JSON string matching the current input model. This only creates a preview, never executes or applies it.",Map.of("title","Short explanation","inputJson","Complete candidate Request JSON string"));
    return list;
  }
  private static void tool(ArrayNode list,String name,String description,Map<String,String> fields){
    var function=list.addObject().put("type","function").putObject("function");function.put("name",name).put("description",description);
    var schema=function.putObject("parameters").put("type","object").put("additionalProperties",false);var props=schema.putObject("properties");var required=schema.putArray("required");
    fields.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e->{props.putObject(e.getKey()).put("type","string").put("description",e.getValue());required.add(e.getKey());});
  }
}
