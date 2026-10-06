package hu.ak91.sf.e2e.core.scenario;

import hu.ak91.sf.e2e.core.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/** Central scheduling owns dependency/resource state; worker tasks never share mutable case inputs. */
public final class DatasetScheduler {
    public record Task<T>(String id,List<String> after,Set<String> resources,T input) {
        public Task { SafePaths.segment(id);after=List.copyOf(after);resources=Set.copyOf(resources); }
    }
    public record Result<R>(String id,String status,R value,Throwable error) {}
    private DatasetScheduler() {}
    public static <T> void validate(List<Task<T>> tasks) {
        Failure.require(!tasks.isEmpty() && tasks.size()<=500 && tasks.stream().map(Task::id).distinct().count()==tasks.size(),"CASE_INVALID","Invalid scheduling tasks");
        Map<String,Task<T>> byId=new HashMap<>();tasks.forEach(t -> byId.put(t.id(),t));Set<String> visited=new HashSet<>(),active=new HashSet<>();for(var t:tasks) visit(t,byId,visited,active);
    }
    private static <T> void visit(Task<T> task,Map<String,Task<T>> all,Set<String> visited,Set<String> active) {
        if(visited.contains(task.id())) return;Failure.require(active.add(task.id()),"CASE_INVALID","Dataset dependency cycle");
        for(String dep:task.after()) { Failure.require(all.containsKey(dep),"CASE_INVALID","Dataset dependency is not selected");visit(all.get(dep),all,visited,active); }active.remove(task.id());visited.add(task.id());
    }
    public static <T,R> List<Result<R>> run(List<Task<T>> tasks,int workers,boolean failFast,Function<T,R> execute,java.util.function.Predicate<R> passed,Runnable workerCleanup) {
        validate(tasks);Failure.require(workers>=1 && workers<=8,"CONFIG_INVALID","Scheduler supports 1..8 workers");
        var pool=Executors.newFixedThreadPool(workers,r -> new Thread(() -> { try { r.run(); } finally { workerCleanup.run(); } },"sf-e2e-worker"));
        var completions=new ExecutorCompletionService<Result<R>>(pool);Map<String,Task<T>> pending=new LinkedHashMap<>(),running=new HashMap<>();tasks.forEach(t -> pending.put(t.id(),t));Map<String,Result<R>> done=new HashMap<>();Set<String> held=new HashSet<>();boolean stop=false;
        try {
            while(!pending.isEmpty() || !running.isEmpty()) {
                boolean skipped=false;
                for(var iterator=pending.values().iterator();iterator.hasNext();) {
                    var task=iterator.next();boolean failedDependency=task.after().stream().anyMatch(id -> done.containsKey(id) && !done.get(id).status().equals("PASSED"));
                    if(stop || failedDependency) { done.put(task.id(),new Result<>(task.id(),"SKIPPED",null,null));iterator.remove();skipped=true;continue; }
                    if(running.size()>=workers || !task.after().stream().allMatch(done::containsKey) || task.resources().stream().anyMatch(held::contains)) continue;
                    iterator.remove();running.put(task.id(),task);held.addAll(task.resources());
                    completions.submit(() -> { try { R result=execute.apply(task.input());return new Result<>(task.id(),passed.test(result)?"PASSED":"FAILED",result,null); } catch(Throwable e) { return new Result<>(task.id(),e instanceof Failure f && f.code().equals("CANCELLED")?"CANCELLED":"FAILED",null,e); } });
                }
                if(running.isEmpty()) { if(pending.isEmpty()) break;if(skipped) continue;throw new Failure("CASE_INVALID","Scheduler made no dependency progress"); }
                Result<R> result=completions.take().get();var task=running.remove(result.id());held.removeAll(task.resources());done.put(result.id(),result);
                if(result.status().equals("CANCELLED") || failFast && !result.status().equals("PASSED")) stop=true;
            }
            return tasks.stream().map(t -> done.get(t.id())).toList();
        } catch(InterruptedException e) { Thread.currentThread().interrupt();throw new Failure("CANCELLED","Suite coordinator interrupted; dispatched actions are never replayed"); }
        catch(ExecutionException e) { throw new Failure("ENGINE_FAILURE","Worker could not return an execution verdict"); }
        finally { pool.shutdown();try { if(!pool.awaitTermination(60,TimeUnit.SECONDS)) { pool.shutdownNow();throw new Failure("ENVIRONMENT_UNAVAILABLE","Workers did not release their resources"); } } catch(InterruptedException e) { pool.shutdownNow();Thread.currentThread().interrupt(); } }
    }
}
