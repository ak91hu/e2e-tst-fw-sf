package hu.ak91.sf.e2e.mobile;

import tools.jackson.databind.JsonNode;
import hu.ak91.sf.e2e.core.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Explicit W3C/Appium native backend, separate from browser/CDP recipes and their evidence claims. */
public final class MobileSession implements AutoCloseable {
    public record Target(String using,String value,String exactAccessibleName) {
        public Target { Failure.require(Set.of("accessibility id","id").contains(using) && value!=null && !value.isBlank() && value.length()<=500 && exactAccessibleName!=null && !exactAccessibleName.isBlank(),"MOBILE_TARGET_INVALID","Native targets need an explicit ID strategy and exact accessible identity"); }
    }
    public record MapDocument(int schemaVersion,String namespace,Map<String,List<Target>> targets,String checksum) {}
    private record Resolved(String id,int index) {}
    private final Thread owner=Thread.currentThread();
    private final HttpClient http=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(15)).build();
    private final String endpoint,namespace;
    private final Deadline deadline;
    private final SecretRegistry secrets;
    private final java.util.function.Consumer<Map<String,Object>> events;
    private String session;
    private boolean closed;
    private int requests;
    public MobileSession(String endpoint,Map<String,Object> capabilities,Deadline deadline,SecretRegistry secrets,java.util.function.Consumer<Map<String,Object>> events) {
        URI uri=URI.create(endpoint);Failure.require(uri.getRawUserInfo()==null && uri.getRawQuery()==null && uri.getFragment()==null && (uri.getScheme().equals("https") || uri.getScheme().equals("http") && Set.of("127.0.0.1","localhost").contains(uri.getHost())),"CONFIG_INVALID","Appium endpoint needs HTTPS or loopback HTTP");
        Failure.require(Set.of("Android","iOS").contains(capabilities.get("platformName")) && capabilities.get("appium:automationName") instanceof String && capabilities.size()<=50 && !capabilities.containsKey("browserName"),"CONFIG_INVALID","Native mobile requires platform and driver capabilities; browser sessions use the web engine");
        this.endpoint=endpoint.replaceAll("/$","");this.deadline=deadline;this.secrets=secrets;this.events=events;
        JsonNode created=request("POST","/session",Map.of("capabilities",Map.of("alwaysMatch",capabilities,"firstMatch",List.of(Map.of()))),true);
        session=created.path("sessionId").asString();var confirmed=created.path("capabilities");
        try { Failure.require(session.matches("[A-Za-z0-9_.-]{1,200}") && confirmed.isObject() && confirmed.path("platformName").asString().equals(capabilities.get("platformName")) && capability(confirmed,"automationName").equals(capabilities.get("appium:automationName")),"MOBILE_CAPABILITY_CHANGED","Appium did not confirm the required native platform/driver"); }
        catch(Failure e) {if(session.matches("[A-Za-z0-9_.-]{1,200}")) try {request("DELETE","/session/"+session,null,true);}catch(Failure ignored) {}throw e;}
        namespace="native-w3c-v1|"+Json.hash(capabilities)+"|"+Json.hash(Map.of("platformVersion",capability(confirmed,"platformVersion"),"automationName",capability(confirmed,"automationName"),"appPackage",capability(confirmed,"appPackage"),"bundleId",capability(confirmed,"bundleId")));
    }
    private static String capability(JsonNode node,String key) { return node.has("appium:"+key)?node.path("appium:"+key).asString():node.path(key).asString(); }
    public String namespace() { return namespace; }
    private void check() { Failure.require(Thread.currentThread()==owner && !closed,"ENGINE_THREAD_VIOLATION","Native session must use its open owner thread");deadline.check(); }
    public void saveMap(Path file,Map<String,List<Target>> targets) {
        check();Failure.require(!targets.isEmpty() && targets.size()<=5000 && targets.entrySet().stream().allMatch(e -> e.getKey().matches("[A-Za-z0-9_.-]{1,100}") && !e.getValue().isEmpty() && e.getValue().size()<=10 && e.getValue().stream().map(Target::exactAccessibleName).distinct().count()==1),"MOBILE_TARGET_INVALID","Native maps require bounded named targets and alternatives with the same exact identity");Json.write(file,new MapDocument(1,namespace,Map.copyOf(targets),Json.hash(targets)));
    }
    public MapDocument loadMap(Path file) {
        check();try { Failure.require(Files.isRegularFile(file) && Files.size(file)<=1048576,"MOBILE_TARGET_INVALID","Native map is unavailable or oversized"); } catch(java.io.IOException e) {throw new Failure("MOBILE_TARGET_INVALID","Native map cannot be read");}
        var map=Json.read(file,MapDocument.class);Failure.require(map.schemaVersion()==1 && namespace.equals(map.namespace()) && map.targets()!=null && Json.hash(map.targets()).equals(map.checksum()),"MOBILE_CAPABILITY_CHANGED","Native map identity/checksum differs from this device/app capability namespace");return map;
    }
    /** Resolve alternatives before dispatch; duplicate/wrong/disabled matches never become healing candidates. */
    private Resolved resolve(List<Target> targets) {
        check();int index=-1;for(var target:targets) {index++;
            JsonNode matches=request("POST","/session/"+session+"/elements",Map.of("using",target.using(),"value",target.value()),false);Failure.require(matches.isArray(),"MOBILE_PROTOCOL_INVALID","Element lookup returned an invalid array");
            if(matches.isEmpty()) continue;Failure.require(matches.size()==1,"TARGET_AMBIGUOUS","Native target has multiple matches");String id=matches.get(0).path("element-6066-11e4-a52e-4f735466cecf").asString();Failure.require(id.matches("[A-Za-z0-9_.-]{1,200}"),"MOBILE_PROTOCOL_INVALID","Invalid native element identity");
            String path="/session/"+session+"/element/"+id;String name=request("GET",path+"/attribute/name",null,false).asString();Failure.require(name.equals(target.exactAccessibleName()),"TARGET_IDENTITY_MISMATCH","Native element accessible name differs from the reviewed target");
            Failure.require(request("GET",path+"/displayed",null,false).asBoolean() && request("GET",path+"/enabled",null,false).asBoolean(),"TARGET_NOT_ACTIONABLE","Native target is hidden or disabled");return new Resolved(id,index);
        }throw new Failure("TARGET_NOT_FOUND","No reviewed native locator matches");
    }
    public void click(MapDocument map,String key) { action(map,key,"click",null); }
    public void click(Path file,String key) { action(file,key,"click",null); }
    public void fill(Path file,String key,String value) { Failure.require(value!=null && value.length()<=10000,"MOBILE_TARGET_INVALID","Invalid native input value");secrets.register(value);action(file,key,"value",Map.of("text",value)); }
    public void fill(MapDocument map,String key,String value) { Failure.require(value!=null && value.length()<=10000,"MOBILE_TARGET_INVALID","Invalid native input value");secrets.register(value);action(map,key,"value",Map.of("text",value)); }
    private void action(MapDocument map,String key,String action,Object value) {
        Failure.require(namespace.equals(map.namespace()) && Json.hash(map.targets()).equals(map.checksum()) && map.targets().containsKey(key),"MOBILE_CAPABILITY_CHANGED","Native target is outside this map/session");String id=resolve(map.targets().get(key)).id();
        request("POST","/session/"+session+"/element/"+id+"/"+action,value==null?Map.of():value,true);
    }
    private void action(Path file,String key,String action,Object value) {
        var map=loadMap(file);Failure.require(map.targets().containsKey(key),"MOBILE_TARGET_INVALID","Native key is missing");var resolved=resolve(map.targets().get(key));
        if(resolved.index()>0) {
            Path lock=Path.of(file.toAbsolutePath()+".lock");try(var channel=java.nio.channels.FileChannel.open(lock,StandardOpenOption.CREATE,StandardOpenOption.WRITE);var held=channel.tryLock()) {
                Failure.require(held!=null && loadMap(file).checksum().equals(map.checksum()),"LOCATOR_CONFLICT","Native map changed during healing");
                var targets=new LinkedHashMap<>(map.targets());var alternatives=new ArrayList<>(targets.get(key));var promoted=alternatives.remove(resolved.index());alternatives.addFirst(promoted);targets.put(key,List.copyOf(alternatives));
                Json.write(file.toAbsolutePath().getParent().resolve("history").resolve(map.checksum()+".json"),map);saveMap(file,targets);events.accept(Map.of("kind","native-locator-healed","key",key,"namespaceHash",Json.hash(namespace)));
            }catch(java.io.IOException | java.nio.channels.OverlappingFileLockException e) {throw new Failure("LOCATOR_CONFLICT","Native map lock is unavailable");}
        }
        request("POST","/session/"+session+"/element/"+resolved.id()+"/"+action,value==null?Map.of():value,true);
    }
    public void expectText(MapDocument map,String key,String expected) {
        Failure.require(map.targets().containsKey(key) && namespace.equals(map.namespace()) && Json.hash(map.targets()).equals(map.checksum()),"MOBILE_CAPABILITY_CHANGED","Native assertion requires a valid capability-specific map");String id=resolve(map.targets().get(key)).id();
        Failure.require(request("GET","/session/"+session+"/element/"+id+"/text",null,false).asString().equals(expected),"ASSERTION_FAILED","Native text differs from the supplied expectation");
    }
    /** Raw native screenshots are private; no claim that browser PNG masking applies to this backend. */
    public Path privateScreenshot(Path directory,String attemptId,int retentionHours) {
        check();Path root=PrivateArtifacts.create(directory,attemptId,retentionHours);String encoded=request("GET","/session/"+session+"/screenshot",null,false).asString();
        try {byte[] bytes=Base64.getDecoder().decode(encoded);Failure.require(bytes.length<=8*1024*1024 && bytes.length>=8 && bytes[0]==(byte)137 && bytes[1]==80,"PNG_INVALID","Native screenshot must be a bounded PNG");Path output=SafePaths.inside(root,UUID.randomUUID()+".png");Files.write(output,bytes,StandardOpenOption.CREATE_NEW);PrivateArtifacts.manifest(root,attemptId,output,8*1024*1024);return output;}catch(java.io.IOException | IllegalArgumentException e) {throw new Failure("EVIDENCE_INCOMPLETE","Native screenshot cannot be persisted");}
    }
    private JsonNode request(String method,String path,Object body,boolean mutation) {
        Failure.require(Thread.currentThread()==owner,"ENGINE_THREAD_VIOLATION","Native commands require their owner thread");Failure.require(method.equals("DELETE") || requests++<2000,"API_LIMIT","Native session request budget exceeded");var commandDeadline=method.equals("DELETE")?Deadline.after(Duration.ofSeconds(10)):deadline;commandDeadline.check();long start=System.nanoTime();String outcome="FAILED";
        try {var builder=HttpRequest.newBuilder(URI.create(endpoint+path)).timeout(commandDeadline.child(Duration.ofSeconds(30)).remaining()).header("Content-Type","application/json");if(method.equals("GET")) builder.GET();else if(method.equals("DELETE")) builder.DELETE();else builder.POST(HttpRequest.BodyPublishers.ofString(Json.string(body)));var response=http.send(builder.build(),HttpBodies.bounded(12*1024*1024));JsonNode data=Json.MAPPER.readTree(response.body());
            Failure.require(response.statusCode()>=200 && response.statusCode()<300 && data!=null && data.has("value") && !data.path("value").has("error"),mutation?"ACTION_COMMIT_UNCERTAIN":"MOBILE_PROTOCOL_INVALID","Native command response could not be verified");outcome="PASSED";return data.path("value");
        }catch(Failure e) {throw e;}catch(InterruptedException e) {Thread.currentThread().interrupt();throw new Failure(mutation?"ACTION_COMMIT_UNCERTAIN":"CANCELLED","Native command interrupted");}catch(Exception e) {throw new Failure(mutation?"ACTION_COMMIT_UNCERTAIN":"ENVIRONMENT_UNAVAILABLE","Native command transport failed");}
        finally {try {events.accept(Map.of("kind","native-command","method",method,"durationNanos",System.nanoTime()-start,"outcome",outcome,"mutation",mutation));}catch(RuntimeException ignored) {} }
    }
    @Override public void close() { if(!closed) {Failure.require(Thread.currentThread()==owner,"ENGINE_THREAD_VIOLATION","Close native session on its owner thread");try {if(session!=null) request("DELETE","/session/"+session,null,true);}finally {closed=true;} } }
}
