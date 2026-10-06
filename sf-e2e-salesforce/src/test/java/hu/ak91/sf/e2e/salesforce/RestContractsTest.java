package hu.ak91.sf.e2e.salesforce;

import hu.ak91.sf.e2e.core.*;
import hu.ak91.sf.e2e.salesforce.api.*;
import hu.ak91.sf.e2e.salesforce.fixture.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RestContractsTest {
    @TempDir Path root;
    private HttpServer server;
    private final AtomicInteger posts=new AtomicInteger();
    private String response="{\"records\":[],\"done\":true}",nextResponse=response;
    private int status=200;
    private static final String ID="001000000000001AAA";
    private Deadline deadline() { return Deadline.after(Duration.ofSeconds(3)); }
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange -> {
            assertEquals("Bearer private-token",exchange.getRequestHeaders().getFirst("Authorization"));
            String body=response;
            if(exchange.getRequestMethod().equals("POST")) { posts.incrementAndGet();body="{\"success\":true,\"id\":\""+ID+"\",\"errors\":[]}"; }
            else if(exchange.getRequestURI().getPath().endsWith("describe/")) body="{\"createable\":true,\"fields\":[{\"name\":\"Name\",\"type\":\"string\",\"createable\":true,\"nillable\":false,\"length\":80}]}";
            else if(exchange.getRequestURI().getPath().endsWith("next-1")) body=nextResponse;
            byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Sforce-Limit-Info","api-usage=2/1000");exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
    }
    @AfterEach void stop() { server.stop(0); }
    private SalesforceApi api(EvidenceSink sink) { return new SalesforceApi("http://127.0.0.1:"+server.getAddress().getPort(),"private-token",new SalesforceApi.Policy("65.0",3,10,4096,20),new SecretRegistry(),sink); }
    @Test void pagesAndUsage() {
        response="{\"records\":[{\"Id\":\""+ID+"\"}],\"done\":false,\"nextRecordsUrl\":\"/services/data/v65.0/query/next-1\"}";
        nextResponse="{\"records\":[{\"Id\":\"001000000000002AAA\"}],\"done\":true}";
        var api=api(null);assertEquals(2,api.query("SELECT Id FROM Account",deadline()).size());assertEquals(2,api.usage().requests());assertEquals(1000,api.usage().orgLimit());assertEquals(0,posts.get());
    }
    @Test void maliciousPaginationAndPermissionNeverRetry() {
        response="{\"records\":[],\"done\":false,\"nextRecordsUrl\":\"https://other.invalid/token\"}";
        assertEquals("API_CONTRACT_INVALID",assertThrows(Failure.class,() -> api(null).query("SELECT Id FROM Account",deadline())).code());
        status=403;var api=api(null);assertEquals("API_PERMISSION_DENIED",assertThrows(Failure.class,() -> api.query("SELECT Id FROM Account",deadline())).code());assertEquals(1,api.usage().requests());
    }
    @Test void wrongFieldsAndEventualWrongResultFail() {
        response="{\"Id\":\""+ID+"\"}";assertThrows(Failure.class,() -> api(null).record("Account",ID,List.of("Name"),deadline()));
        response="{\"Id\":\""+ID+"\",\"Name\":\"wrong\"}";
        assertEquals("API_ASSERTION_FAILED",assertThrows(Failure.class,() -> api(null).awaitRecord("Account",ID,List.of("Name"),r -> r.path("Name").asString().equals("expected"),Duration.ofMillis(10),Deadline.after(Duration.ofMillis(100)))).code());
    }
    @Test void optionalMetricsCannotChangeVerdict() {
        var sink=new EvidenceSink() { public String checkpoint(String s) { return ""; }public void event(Map<String,Object> e) { assertFalse(Json.string(e).contains("private-token"));throw new IllegalStateException(); } };
        assertTrue(api(sink).query("SELECT Id FROM Account",deadline()).isEmpty());
    }
    @Test void metadataAndOwnedCreate() {
        FrameworkConfig c=FrameworkConfig.load(null,Map.of("retention.journalDir",root.toString()));var registry=new RecordRegistry(c,"run","attempt","case");var fixtures=new OwnedFixtures(api(null),registry);
        assertThrows(Failure.class,() -> fixtures.create("Account","Name",Map.of("Name","E2E-TA-unique-worker-001","Unknown","secret"),null,null,Persona.DEFAULT,deadline()));assertEquals(0,posts.get());
        var record=fixtures.create("Account","Name",Map.of("Name","E2E-TA-unique-worker-001"),null,null,Persona.DEFAULT,deadline());assertEquals(ID,record.id());assertEquals(1,posts.get());assertTrue(Files.isRegularFile(registry.journalPath()));
        assertThrows(Failure.class,() -> fixtures.create("Account","Name",Map.of("Name",record.name()),null,null,Persona.DEFAULT,deadline()));assertEquals(1,posts.get());
    }
    @Test void uncertainWriteIsJournaledAndCannotRepeat() {
        FrameworkConfig c=FrameworkConfig.load(null,Map.of("retention.journalDir",root.toString()));var registry=new RecordRegistry(c,"run","attempt","case");var fixtures=new OwnedFixtures(api(null),registry);status=503;
        // Metadata permission must pass; inject the outage only after the describe response via a separate transport endpoint.
        server.removeContext("/");server.createContext("/",e -> {String body=e.getRequestMethod().equals("POST")?"{}":"{\"createable\":true,\"fields\":[{\"name\":\"Name\",\"type\":\"string\",\"createable\":true,\"nillable\":false,\"length\":80}]}";if(e.getRequestMethod().equals("POST")) posts.incrementAndGet();byte[] b=body.getBytes();e.sendResponseHeaders(e.getRequestMethod().equals("POST")?503:200,b.length);e.getResponseBody().write(b);e.close();});
        assertEquals("ACTION_COMMIT_UNCERTAIN",assertThrows(Failure.class,() -> fixtures.create("Account","Name",Map.of("Name","E2E-TA-uncertain-worker-001"),null,null,Persona.DEFAULT,deadline())).code());
        assertEquals(OwnedRecord.State.SAVE_UNCERTAIN,registry.manifest().records().getFirst().state);
        assertThrows(Failure.class,() -> fixtures.create("Account","Name",Map.of("Name","E2E-TA-uncertain-worker-001"),null,null,Persona.DEFAULT,deadline()));assertEquals(1,posts.get());
    }
    @Test void partialBatchAndMissingParentsHaveExplicitResults() {
        var registry=new RecordRegistry(FrameworkConfig.load(null,Map.of("retention.journalDir",root.toString())),"run","attempt","case");var provider=new OwnedFixtures(api(null),registry);
        var results=provider.createAll(List.of(new OwnedFixtures.Seed("invalid","Account","Name",Map.of("Name",provider.uniqueName("Parent",80),"Unknown","value"),null,null,Persona.DEFAULT),new OwnedFixtures.Seed("child","Account","Name",Map.of("Name",provider.uniqueName("Child",80)),"invalid","ParentId",Persona.DEFAULT),new OwnedFixtures.Seed("independent","Account","Name",Map.of("Name",provider.uniqueName("Independent",80)),null,null,Persona.DEFAULT)),deadline());
        assertEquals(List.of("FAILED","SKIPPED","PASSED"),results.stream().map(OwnedFixtures.Result::status).toList());assertEquals(1,posts.get());assertEquals(1,registry.manifest().records().size());
    }
    @Test void metadataDriftCannotCertifyARelease() {
        var api=api(null);var reviewed=api.schemaFingerprints(List.of("Account"),deadline());api.expectSchema(reviewed,deadline());assertEquals("API_ASSERTION_FAILED",assertThrows(Failure.class,() -> api.expectSchema(Map.of("Account",Json.hash("wrong schema")),deadline())).code());assertEquals(0,posts.get());
    }
}
