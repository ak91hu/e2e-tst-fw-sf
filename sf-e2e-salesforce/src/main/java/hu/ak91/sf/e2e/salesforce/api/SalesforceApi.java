package hu.ak91.sf.e2e.salesforce.api;

import tools.jackson.databind.JsonNode;
import hu.ak91.sf.e2e.core.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.function.Predicate;

/** An explicitly authorized REST session. No cookies, redirects or mutation retries. */
public final class SalesforceApi {
    public record Policy(String version,int maxPages,int maxRecords,int maxResponseBytes,int maxRequests) {
        public Policy {
            Failure.require(version!=null && version.matches("[0-9]{2,3}\\.0") && maxPages>0 && maxPages<=1000 && maxRecords>0 && maxRecords<=100000 && maxResponseBytes>=1024 && maxResponseBytes<=16777216 && maxRequests>0,"CONFIG_INVALID","Invalid REST policy");
        }
    }
    public record Usage(int requests,long responseBytes,int orgUsed,int orgLimit) {}
    private final String origin,token,prefix;
    private final Policy policy;
    private final HttpClient http;
    private final EvidenceSink evidence;
    private int requests,orgUsed=-1,orgLimit=-1;
    private long bytes;
    public SalesforceApi(String origin,String accessToken,Policy policy,SecretRegistry secrets,EvidenceSink evidence) {
        URI uri=URI.create(origin);
        Failure.require(uri.getRawUserInfo()==null && uri.getRawQuery()==null && uri.getFragment()==null && Objects.toString(uri.getPath(),"").isEmpty() && ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()) && Set.of("localhost","127.0.0.1").contains(uri.getHost())),"CONFIG_INVALID","REST origin requires HTTPS or loopback HTTP");
        Failure.require(accessToken!=null && !accessToken.isBlank() && !accessToken.contains("\n") && !accessToken.contains("\r"),"AUTH_CREDENTIAL_INVALID","Missing REST authorization");
        secrets.register(accessToken);this.origin=origin;token=accessToken;this.policy=policy;this.evidence=evidence;prefix="/services/data/v"+policy.version()+"/";
        http=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(15)).build();
    }
    public Usage usage() { return new Usage(requests,bytes,orgUsed,orgLimit); }
    public static String apiName(String value) { Failure.require(value!=null && value.matches("[A-Za-z][A-Za-z0-9_]{0,127}"),"API_CONTRACT_INVALID","Invalid API name");return value; }
    public static String id(String value) { Failure.require(value!=null && value.matches("[A-Za-z0-9]{15}(?:[A-Za-z0-9]{3})?"),"API_CONTRACT_INVALID","Invalid record ID");return value; }
    public JsonNode describe(String object,Deadline deadline) { return request("GET",prefix+"sobjects/"+apiName(object)+"/describe/",null,deadline); }
    public Map<String,String> schemaFingerprints(List<String> objects,Deadline deadline) {
        Failure.require(objects!=null && !objects.isEmpty() && objects.size()<=200 && objects.stream().distinct().count()==objects.size(),"API_CONTRACT_INVALID","Declare unique objects for schema regression");Map<String,String> result=new TreeMap<>();
        for(String object:objects) {JsonNode metadata=describe(object,deadline);Failure.require(metadata.path("fields").isArray(),"API_CONTRACT_INVALID","Object describe omitted field metadata");Map<String,JsonNode> fields=new TreeMap<>();for(var field:metadata.path("fields")) {var selected=Json.MAPPER.createObjectNode();for(String key:List.of("name","type","length","precision","scale","nillable","createable","updateable","referenceTo","restrictedPicklist","picklistValues","defaultedOnCreate")) if(field.has(key)) selected.set(key,field.get(key));Failure.require(fields.put(apiName(field.path("name").asString()),selected)==null,"API_CONTRACT_INVALID","Duplicate metadata field");}result.put(object,Json.hash(fields));}return Map.copyOf(result);
    }
    public void expectSchema(Map<String,String> reviewed,Deadline deadline) {
        Failure.require(reviewed!=null && !reviewed.isEmpty(),"CONFIG_INVALID","Supply reviewed schema fingerprints");Failure.require(schemaFingerprints(new ArrayList<>(reviewed.keySet()),deadline).equals(reviewed),"API_ASSERTION_FAILED","Reviewed object schema/permission metadata drifted");
    }
    public JsonNode expectRecord(String object,String id,Map<String,Object> expected,Duration interval,Deadline deadline) {
        Failure.require(expected!=null && !expected.isEmpty(),"API_CONTRACT_INVALID","Explicit expected backend fields are required");return awaitRecord(object,id,new ArrayList<>(expected.keySet()),row -> expected.entrySet().stream().allMatch(e -> Json.MAPPER.valueToTree(e.getValue()).equals(row.path(e.getKey()))),interval,deadline);
    }
    public JsonNode record(String object,String id,List<String> fields,Deadline deadline) {
        Failure.require(fields!=null && !fields.isEmpty() && fields.size()<=200 && fields.stream().distinct().count()==fields.size(),"API_CONTRACT_INVALID","Explicit unique fields are required");
        var requested=new LinkedHashSet<String>();requested.add("Id");fields.stream().map(SalesforceApi::apiName).forEach(requested::add);String names=String.join(",",requested);
        JsonNode result=request("GET",prefix+"sobjects/"+apiName(object)+"/"+id(id)+"?fields="+names,null,deadline);
        Failure.require(result.isObject() && result.path("Id").asString().equals(id) && fields.stream().allMatch(result::has),"API_CONTRACT_INVALID","Record response omitted requested identity or fields");return result.deepCopy();
    }
    public List<JsonNode> query(String soql,Deadline deadline) {
        Failure.require(soql!=null && soql.length()<=20000 && soql.stripLeading().toUpperCase(Locale.ROOT).startsWith("SELECT "),"API_CONTRACT_INVALID","An explicit read-only SOQL SELECT is required");
        String next=prefix+"query/?q="+URLEncoder.encode(soql,StandardCharsets.UTF_8);Set<String> visited=new HashSet<>();List<JsonNode> records=new ArrayList<>();
        for(int page=0;page<policy.maxPages();page++) {
            Failure.require(visited.add(next),"API_CONTRACT_INVALID","Query pagination loop");JsonNode data=request("GET",next,null,deadline);
            Failure.require(data.isObject() && data.path("records").isArray() && data.path("done").isBoolean(),"API_CONTRACT_INVALID","Invalid query page");
            for(JsonNode row:data.path("records")) { Failure.require(row.isObject() && records.size()<policy.maxRecords(),"API_LIMIT","Query record limit exceeded");records.add(row.deepCopy()); }
            if(data.path("done").asBoolean()) return List.copyOf(records);
            next=data.path("nextRecordsUrl").asString();Failure.require(next.startsWith(prefix+"query/") && !next.contains("?") && next.matches("/services/data/v[0-9]+\\.0/query/[A-Za-z0-9-]+"),"API_CONTRACT_INVALID","Unsafe pagination destination");
        }
        throw new Failure("API_LIMIT","Query page limit exceeded");
    }
    /** Conditions read fresh state; only eventual assertions are retried. Permission/transport errors fail immediately. */
    public JsonNode awaitRecord(String object,String id,List<String> fields,Predicate<JsonNode> condition,Duration interval,Deadline deadline) {
        Failure.require(!interval.isNegative() && !interval.isZero() && interval.compareTo(Duration.ofSeconds(30))<=0,"CONFIG_INVALID","Invalid oracle interval");
        while(true) {
            JsonNode value;try { value=record(object,id,fields,deadline); } catch(Failure e) { if(Set.of("STEP_TIMEOUT","API_READ_TIMEOUT").contains(e.code())) throw new Failure("API_ASSERTION_FAILED","Backend assertion deadline expired");throw e; }if(condition.test(value.deepCopy())) return value;
            try { long millis=Math.min(interval.toMillis(),Math.max(1,deadline.remaining().toMillis()));Thread.sleep(millis);deadline.check(); }
            catch(InterruptedException e) { Thread.currentThread().interrupt();throw new Failure("CANCELLED","Oracle interrupted"); }
            catch(Failure e) { throw new Failure("API_ASSERTION_FAILED","Expected backend result did not become true before the deadline"); }
        }
    }
    /** Only the owned fixture provider can dispatch a create; exactly one HTTP request. */
    JsonNode create(String object,JsonNode values,Deadline deadline) { return request("POST",prefix+"sobjects/"+apiName(object)+"/",values,deadline); }
    private JsonNode request(String method,String path,JsonNode body,Deadline deadline) {
        Failure.require(path.startsWith(prefix) && !path.contains("..") && !path.contains("\\") && !path.contains("#"),"POLICY_DENIED","REST path is outside this API version");
        Failure.require(requests<policy.maxRequests(),"API_LIMIT","Attempt REST request budget exhausted");deadline.check();requests++;long started=System.nanoTime();String outcome="FAILED";
        try {
            var builder=HttpRequest.newBuilder(URI.create(origin+path)).timeout(deadline.child(Duration.ofSeconds(30)).remaining()).header("Authorization","Bearer "+token).header("Accept","application/json");
            if(body==null) builder.GET();else builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(Json.string(body)));
            var response=http.send(builder.build(),HttpBodies.bounded(policy.maxResponseBytes()));
            byte[] raw=response.body();
            bytes+=raw.length;Failure.require(raw.length<=policy.maxResponseBytes(),"API_LIMIT","REST response byte limit exceeded");
            response.headers().firstValue("Sforce-Limit-Info").ifPresent(value -> { var m=java.util.regex.Pattern.compile("(?:^|;|,|\\s)api-usage=(\\d+)/(\\d+)").matcher(value);if(m.find()) { try { orgUsed=Integer.parseInt(m.group(1));orgLimit=Integer.parseInt(m.group(2)); } catch(NumberFormatException ignored) {} } });
            int status=response.statusCode();
            Failure.require(status>=200 && status<300,status==401 || status==403?"API_PERMISSION_DENIED":status>=500 && body!=null?"ACTION_COMMIT_UNCERTAIN":"API_HTTP_FAILURE","REST returned HTTP "+status);
            JsonNode result=Json.MAPPER.readTree(raw);Failure.require(result!=null,"API_CONTRACT_INVALID","Empty REST response");outcome="PASSED";return result;
        } catch(Failure e) { throw e; }
        catch(HttpTimeoutException e) { throw new Failure(body==null?"API_READ_TIMEOUT":"ACTION_COMMIT_UNCERTAIN","REST request deadline expired"); }
        catch(InterruptedException e) { Thread.currentThread().interrupt();throw new Failure(body==null?"CANCELLED":"ACTION_COMMIT_UNCERTAIN","REST request interrupted"); }
        catch(Exception e) { try { deadline.check(); } catch(Failure timeout) { throw new Failure(body==null?"API_READ_TIMEOUT":"ACTION_COMMIT_UNCERTAIN","REST deadline expired"); }throw new Failure(body==null?"ENVIRONMENT_UNAVAILABLE":"ACTION_COMMIT_UNCERTAIN","REST response could not be verified"); }
        finally { if(evidence!=null) try { evidence.event(Map.of("kind","api-request","method",method,"outcome",outcome,"durationNanos",System.nanoTime()-started,"usage",usage())); } catch(RuntimeException ignored) { /* Optional timing cannot change a REST verdict. */ } }
    }
}
