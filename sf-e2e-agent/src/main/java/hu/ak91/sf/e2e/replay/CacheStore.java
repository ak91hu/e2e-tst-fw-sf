package hu.ak91.sf.e2e.replay;

import hu.ak91.sf.e2e.core.*;
import java.nio.file.*;
import java.util.*;
import java.io.IOException;
public final class CacheStore {
    public record Action(String kind,SemanticTarget target,String paramRef,String literal) {}
    public record Trace(int schemaVersion,int algorithmVersion,String key,List<Action> actions,String integrity) {}
    private final Path root;
    public CacheStore(Path root) { this.root=root.toAbsolutePath().normalize(); }
    private Path path(String key) { Failure.require(key.matches("[a-f0-9]{64}"),"INVALID_SCHEMA","Invalid cache hash"); return SafePaths.inside(root,key+".json"); }
    private static String integrity(String key,List<Action> actions) { return Json.hash(Map.of("schemaVersion",1,"algorithmVersion",1,"key",key,"actions",actions)); }
    public Optional<Trace> get(String key) {
        Path path=path(key); if(!Files.exists(path)) return Optional.empty();
        try { Failure.require(Files.size(path)<=1048576,"INVALID_SCHEMA","Cache entry exceeds 1 MiB"); }
        catch(IOException e) { throw new Failure("CACHE_STORE_FAILED","Cannot stat cache"); }
        Trace trace=Json.read(path,Trace.class);
        Failure.require(trace.schemaVersion()==1 && trace.algorithmVersion()==1 && key.equals(trace.key()) &&
            trace.actions()!=null && !trace.actions().isEmpty() && trace.actions().size()<=50 &&
            integrity(key,trace.actions()).equals(trace.integrity()),"INVALID_SCHEMA","Invalid cache schema or integrity");
        for(Action action:trace.actions()) Failure.require(Set.of("fill","select","setChecked","press","scroll").contains(action.kind()) &&
            action.target()!=null && (action.paramRef()!=null ^ action.literal()!=null) &&
            (action.literal()==null || action.literal().length()<=4096),"INVALID_SCHEMA","Invalid cached action");
        return Optional.of(trace);
    }
    public void put(String key,List<Action> actions) {
        Failure.require(!actions.isEmpty() && actions.size()<=50,"INVALID_SCHEMA","Empty or oversized trace");
        Trace trace=new Trace(1,1,key,List.copyOf(actions),integrity(key,actions));
        Failure.require(Json.string(trace).length()<=1048576,"INVALID_SCHEMA","Cache trace exceeds maximum size"); Json.write(path(key),trace);
    }
    public void invalidate(String key) {
        try { Files.deleteIfExists(path(key)); } catch(IOException e) { throw new Failure("CACHE_STORE_FAILED","Cache invalidation failed"); }
    }
    public List<String> list() {
        if(!Files.exists(root)) return List.of();
        try(var files=Files.list(root)) { return files.filter(p -> p.getFileName().toString().matches("[a-f0-9]{64}\\.json")).map(p -> p.getFileName().toString()).sorted().toList(); }
        catch(IOException e) { throw new Failure("CACHE_STORE_FAILED","Cannot list cache"); }
    }
    public void clear() { for(String name:list()) invalidate(name.substring(0,64)); }
}
