package dev.optilens;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static dev.optilens.Model.*;

public final class Jobs implements AutoCloseable {
  public record Job(String id,String status,boolean imported,String submittedAt,String error,Run result) {}
  private final Path directory;
  private final Map<String,Job> jobs=new ConcurrentHashMap<>();
  private final ThreadPoolExecutor executor=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8));
  private final int timeoutSeconds;
  public Jobs(Path directory) throws Exception {this(directory,25);}
  Jobs(Path directory,int timeoutSeconds) throws Exception {
    this.directory=directory.toAbsolutePath();this.timeoutSeconds=timeoutSeconds;Files.createDirectories(directory);
    try(var files=Files.list(directory)){
      for(var p:files.filter(f->f.getFileName().toString().matches("[0-9a-f-]{36}\\.json")).sorted().limit(200).toList()){
        try{
          if(Files.size(p)>8_500_000)continue;
          var job=Json.MAPPER.readValue(p.toFile(),Job.class);
          if(!p.getFileName().toString().equals(job.id()+".json"))continue;
          if("succeeded".equals(job.status()))Records.validate(job.result());
          if(!Set.of("succeeded","failed").contains(job.status()))job=new Job(job.id(),"failed",job.imported(),job.submittedAt(),"The service restarted and interrupted the previous run",null);
          save(job);
        }catch(Exception e){System.err.println("Skipped unreadable run: "+p.getFileName());}
      }
    }
  }
  public synchronized Job submit(Request request) throws Exception {
    Input.validate(request);capacity();
    String id=UUID.randomUUID().toString();
    Job job=new Job(id,"queued",false,Instant.now().toString(),null,null);
    Path input=directory.resolve(id+".input.json");Json.MAPPER.writeValue(input.toFile(),request);save(job);
    try{executor.execute(()->execute(job,input));}
    catch(RejectedExecutionException e){jobs.remove(id);Files.deleteIfExists(input);Files.deleteIfExists(directory.resolve(id+".json"));throw e;}
    return job;
  }
  public synchronized Job imported(Run run) throws Exception {
    Records.validate(run);capacity();
    Job job=new Job(UUID.randomUUID().toString(),"succeeded",true,Instant.now().toString(),null,run);save(job);return job;
  }
  private void capacity(){Input.require(jobs.size()<200,"The limit of 200 local records has been reached. Stop the service, then back up and clean data/runs.");}
  public Job get(String id){return jobs.get(id);}
  public List<Map<String,Object>> list(){
    return jobs.values().stream().sorted(Comparator.comparing(Job::submittedAt).reversed()).limit(30)
      .map(j->Map.<String,Object>of("id",j.id(),"status",j.status(),"imported",j.imported(),"submittedAt",j.submittedAt())).toList();
  }
  private void execute(Job original,Path input){
    Process process=null;Path output=directory.resolve(original.id()+".worker.json");
    try{
      save(new Job(original.id(),"running",false,original.submittedAt(),null,null));
      String java=Path.of(System.getProperty("java.home"),"bin","java").toString();
      String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
      process=new ProcessBuilder(java,"-Xmx384m","-Xss1m","-cp",cp,Worker.class.getName(),input.toString(),output.toString())
          .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
      if(!process.waitFor(timeoutSeconds,TimeUnit.SECONDS))throw new TimeoutException("Optimization exceeded "+timeoutSeconds+" seconds; the worker has been terminated");
      if(process.exitValue()!=0 || !Files.exists(output))throw new IllegalStateException("The optimization worker exited unexpectedly; memory or query complexity limits may have been exceeded");
      if(Files.size(output)>8_000_000)throw new IllegalStateException("Run record exceeds the size limit");
      var outcome=Json.MAPPER.readValue(output.toFile(),Worker.Outcome.class);
      if(outcome.error()!=null)throw new IllegalArgumentException(outcome.error());
      Records.validate(outcome.result());
      save(new Job(original.id(),"succeeded",false,original.submittedAt(),null,outcome.result()));
    }catch(Exception e){
      try{save(new Job(original.id(),"failed",false,original.submittedAt(),Worker.error(e),null));}
      catch(Exception storageError){jobs.put(original.id(),new Job(original.id(),"failed",false,original.submittedAt(),"Failed to save the run record",null));}
      if(e instanceof InterruptedException)Thread.currentThread().interrupt();
    }finally{
      if(process!=null && process.isAlive()){process.destroyForcibly();try{process.waitFor(5,TimeUnit.SECONDS);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}}
      try{Files.deleteIfExists(input);Files.deleteIfExists(output);}catch(Exception ignored){}
    }
  }
  private void save(Job job) throws Exception {
    Path target=directory.resolve(job.id()+".json"), temp=directory.resolve(job.id()+".tmp");
    Json.MAPPER.writeValue(temp.toFile(),job);
    try{Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
    catch(AtomicMoveNotSupportedException e){Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);}
    jobs.put(job.id(),job);
  }
  @Override public void close(){executor.shutdownNow();try{executor.awaitTermination(8,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}
