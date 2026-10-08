package hu.ak91.sf.e2e.cli;
import hu.ak91.sf.e2e.core.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class McpCommandsTest {
    @TempDir Path root;
    McpCommands.Server server(boolean model,boolean execution) throws Exception {return new McpCommands.Server(root,"project.yaml",model,execution,Set.of(),Set.of());}
    JsonNode request(McpCommands.Server s,String method,Object params) {return Json.MAPPER.valueToTree(s.handle(Json.string(Map.of("jsonrpc","2.0","id",1,"method",method,"params",params))));}
    void initialize(McpCommands.Server s) {
        assertEquals("2025-11-25",request(s,"initialize",Map.of("protocolVersion","2025-11-25","clientInfo",Map.of("name","fixture","version","1"),"capabilities",Map.of())).path("result").path("protocolVersion").asString());
        assertNull(s.handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"));
    }
    @Test void handshakeAndHostCapabilitiesControlAvailableTools() throws Exception {
        try(var s=server(false,false)) {
            assertTrue(request(s,"tools/list",Map.of()).has("error"));initialize(s);String list=Json.string(request(s,"tools/list",Map.of()));
            assertTrue(list.contains("validate_case"));assertFalse(list.contains("plan_case"));assertFalse(list.contains("session_open"));assertFalse(list.contains("session_fill_secret"));
            assertTrue(request(s,"initialize",Map.of()).has("error"));
        }
        try(var s=server(true,true)) {initialize(s);String list=Json.string(request(s,"tools/list",Map.of()));assertTrue(list.contains("plan_case"));assertTrue(list.contains("session_open"));assertFalse(list.contains("session_fill_secret"));assertFalse(list.contains("session_export"));}
    }
    @Test void closedArgumentsRejectConfigOverridesAndUnavailableTools() throws Exception {
        try(var s=server(false,false)) {initialize(s);
            assertTrue(request(s,"tools/call",Map.of("name","guide","arguments",Map.of("topic","start","config","outside.yaml"))).has("error"));
            assertTrue(request(s,"tools/call",Map.of("name","run_suite","arguments",Map.of())).has("error"));
            assertEquals(-32601,request(s,"eval",Map.of()).path("error").path("code").asInt());
            assertTrue(request(s,"tools/call",Map.of("name","guide","arguments",Map.of("topic","../../secret"))).path("result").path("isError").asBoolean());
        }
    }
    @Test void resourcesReadOnlyWhitelistedBundledMarkdown() throws Exception {
        try(var s=server(false,false)) {initialize(s);
            assertEquals(GuideCommand.TOPICS.size(),request(s,"resources/list",Map.of()).path("result").path("resources").size());
            var read=request(s,"resources/read",Map.of("uri","sf-e2e://guide/index"));assertTrue(read.path("result").path("contents").get(0).path("text").asString().startsWith("# Documentation index"));
            assertTrue(request(s,"resources/read",Map.of("uri","file:///private.txt")).has("error"));
        }
    }
    @Test void pathsCannotEscapeAndInvalidIdsCannotCrashServer() throws Exception {
        try(var s=server(false,false)) {
            assertEquals(root.toRealPath().resolve("new/artifact.json"),s.path("new/artifact.json"));
            assertThrows(Failure.class,() -> s.path("../private.json"));assertThrows(Failure.class,() -> s.path(root.resolve("private.json").toString()));
            assertTrue(Json.MAPPER.valueToTree(s.handle("{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"ping\"}")).has("error"));
            assertTrue(Json.MAPPER.valueToTree(s.handle("[]")).has("error"));assertTrue(Json.MAPPER.valueToTree(s.handle("bad")).has("error"));
            assertEquals(1,request(s,"ping",Map.of()).path("id").asInt());
        }
    }
    @Test void utf8WireCapIsBytesAndOversizedMessagesRecoverWithoutNoise() throws Exception {
        String ping="{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"ping\"}";
        try(var s=server(false,false)) {
            var result=new StringWriter();s.serve(new ByteArrayInputStream(("é".repeat(600000)+"\n"+ping+"\n").getBytes(StandardCharsets.UTF_8)),new PrintWriter(result));
            var lines=result.toString().lines().toList();assertEquals(2,lines.size());assertEquals(-32600,Json.MAPPER.readTree(lines.get(0)).path("error").path("code").asInt());assertEquals(7,Json.MAPPER.readTree(lines.get(1)).path("id").asInt());
        }
    }
}
