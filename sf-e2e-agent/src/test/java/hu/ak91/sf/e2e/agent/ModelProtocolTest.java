package hu.ak91.sf.e2e.agent;
import hu.ak91.sf.e2e.core.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ModelProtocolTest {
    private HttpServer server;
    private ModelClient.Request request() {
        var node=new Observation.Node("current-node",SemanticTarget.role("text","Current name","page"),"Current name","",false,false);
        return new ModelClient.Request("assert","Read current name",Map.of(),new Observation(1,Instant.now(),"safe-route",List.of(node),false,"COMPLETE","SAFE",null),List.of(),null);
    }
    private JsonModelClient client(int status,String body) throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat",exchange -> {
            assertEquals("POST",exchange.getRequestMethod());
            assertEquals("Bearer PRIVATE_PROTOCOL_SENTINEL",exchange.getRequestHeaders().getFirst("Authorization"));
            var sent=Json.MAPPER.readTree(exchange.getRequestBody());
            assertEquals("json_object",sent.path("response_format").path("type").asString());assertEquals(2,sent.path("messages").size());
            assertEquals(4096,sent.path("max_completion_tokens").asInt());assertFalse(sent.has("max_tokens"));
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        var base=FrameworkConfig.defaults().agent();
        var config=new FrameworkConfig.Agent("http://127.0.0.1:"+server.getAddress().getPort()+"/chat","configured-model",base.apiKeyRef(),40,40,1000,120);
        return new JsonModelClient(config,new SecretRegistry(),HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),"PRIVATE_PROTOCOL_SENTINEL");
    }
    private String body(String finish,Object usage) {
        var verdict=Map.of("tool","finish","verdict","PASSED","reason","Current UI","evidenceRefs",List.of("current-node"));
        var root=new LinkedHashMap<String,Object>();root.put("choices",List.of(Map.of("finish_reason",finish,"message",Map.of("content",Json.string(verdict)))));
        if(usage!=null) root.put("usage",Map.of("total_tokens",usage));return Json.string(root);
    }
    @AfterEach void close() { if(server!=null) server.stop(0); }
    @Test void translatesJsonResponseAndProviderUsage() throws Exception {
        var response=client(200,body("stop",25)).complete(request(),Deadline.after(Duration.ofSeconds(5)));
        assertEquals("PASSED",response.verdict());assertEquals(25L,response.tokens());
    }
    @Test void missingUsageRemainsUnknown() throws Exception {
        assertNull(client(200,body("stop",null)).complete(request(),Deadline.after(Duration.ofSeconds(5))).tokens());
    }
    @Test void incompleteCompletionCannotBecomeVerdict() throws Exception {
        var client=client(200,body("length",10));assertEquals("MODEL_OUTPUT_LIMIT",assertThrows(Failure.class,() -> client.complete(request(),Deadline.after(Duration.ofSeconds(5)))).code());
    }
    @Test void malformedUsageCannotBecomeZero() throws Exception {
        var client=client(200,body("stop","unknown"));assertEquals("AI_USAGE_INVALID",assertThrows(Failure.class,() -> client.complete(request(),Deadline.after(Duration.ofSeconds(5)))).code());
    }
    @Test void providerErrorsNeverEchoResponseBodyOrCredential() throws Exception {
        var client=client(429,"PRIVATE_PROTOCOL_SENTINEL");
        var error=assertThrows(Failure.class,() -> client.complete(request(),Deadline.after(Duration.ofSeconds(5))));
        assertEquals("MODEL_PROVIDER_FAILED",error.code());assertFalse(error.getMessage().contains("PRIVATE"));
    }
    @Test void providerBreakdownOverridesAnyModelGeneratedAccountingFields() throws Exception {
        var completion=Map.of("tool","finish","verdict","PASSED","reason","Read","evidenceRefs",List.of(),"tokens",0,"inputTokens",0,"outputTokens",0);
        var body=Json.string(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",Json.string(completion)))),"usage",Map.of("total_tokens",25,"prompt_tokens",20,"completion_tokens",5)));
        var response=client(200,body).complete(request(),Deadline.after(Duration.ofSeconds(5)));assertEquals(25L,response.tokens());assertEquals(20L,response.inputTokens());assertEquals(5L,response.outputTokens());
    }
    @Test void inconsistentProviderBreakdownCannotBecomeCheapValidUsage() throws Exception {
        var body=Json.MAPPER.readTree(body("stop",25));((tools.jackson.databind.node.ObjectNode)body.get("usage")).put("prompt_tokens",20).put("completion_tokens",10);
        var client=client(200,Json.string(body));assertEquals("AI_USAGE_INVALID",assertThrows(Failure.class,() -> client.complete(request(),Deadline.after(Duration.ofSeconds(5)))).code());
    }
}
