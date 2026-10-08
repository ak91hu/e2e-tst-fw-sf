package hu.ak91.sf.e2e.cli;
import hu.ak91.sf.e2e.core.*;
import hu.ak91.sf.e2e.core.scenario.*;
import hu.ak91.sf.e2e.engine.PlaywrightEngine;
import hu.ak91.sf.e2e.salesforce.auth.JwtAuthenticator;
import picocli.CommandLine.*;
import tools.jackson.databind.JsonNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.Callable;

/** A local stdio MCP endpoint. Host startup policy, never model arguments, grants privileged capabilities. */
@Command(name="mcp",mixinStandardHelpOptions=true,description="Serve bounded JSON-RPC MCP tools over stdio; stdout contains protocol messages only.")
public final class McpCommands implements Callable<Integer> {
    @Option(names="--workspace",required=true) Path workspace;
    @Option(names="--config",defaultValue="sf-e2e.yaml",description="Host-approved configuration relative to the workspace.") String config;
    @Option(names="--allow-model",description="Expose model-planning/discovery tools; uses the host-approved model endpoint and budget.") boolean allowModel;
    @Option(names="--allow-execution",description="Expose UI learning/replay and managed authenticated browser sessions.") boolean allowExecution;
    @Option(names="--secret-ref",description="Allow an opaque fill of this exact environment reference; repeatable.") Set<String> secretRefs=new HashSet<>();
    @Option(names="--export-key-ref",description="Allow encrypted session export with this exact host key reference; repeatable.") Set<String> exportKeys=new HashSet<>();
    public Integer call() throws Exception {
        PrintStream protocol=System.out;System.setOut(System.err);
        try(var server=new Server(workspace,config,allowModel,allowExecution,secretRefs,exportKeys)) {
            server.serve(System.in,new PrintWriter(new OutputStreamWriter(protocol,StandardCharsets.UTF_8),true));return 0;
        } finally {System.setOut(protocol);}
    }
    static final class Server implements AutoCloseable {
        private record Tool(String description,JsonNode schema,boolean readOnly,java.util.function.Function<JsonNode,Object> call) {}
        private record Session(PlaywrightEngine engine,SecretRegistry secrets,Deadline deadline,Persona persona,ExecutionLocks lock) implements AutoCloseable {
            public void close() {try {engine.close();}finally {lock.close();}}
        }
        final Path root;final String config;final boolean model,execution;final Set<String> secretRefs,exportKeys;
        final Map<String,Tool> tools=new LinkedHashMap<>();final Map<String,Session> sessions=new LinkedHashMap<>();boolean initialized,ready;
        Server(Path workspace,String config,boolean model,boolean execution,Set<String> secretRefs,Set<String> exportKeys) throws IOException {
            root=workspace.toRealPath();this.config=config;this.model=model;this.execution=execution;this.secretRefs=Set.copyOf(secretRefs);this.exportKeys=Set.copyOf(exportKeys);
            Failure.require(Files.isDirectory(root),"POLICY_DENIED","MCP workspace must be an existing directory");
            register("guide","Read offline bundled framework guidance",true,Map.of("topic",string(80)),List.of(),a -> guide(a.path("topic").asString("index")));
            register("validate_case","Validate case intent and values offline",true,Map.of("spec",string(1000),"params",string(1000)),List.of("spec"),a -> cli("validate-case",paths(a,"spec","params")));
            register("validate_suite","Preflight a deterministic dataset suite",true,Map.of("suite",string(1000),"shard",string(20)),List.of("suite"),a -> cliConfigured("validate-suite",paths(a,"suite","shard")));
            register("sessions_list","List this server's managed isolated browser sessions",true,Map.of(),List.of(),a -> sessions.entrySet().stream().map(e -> Map.of("id",e.getKey(),"persona",e.getValue().persona().name(),"route",e.getValue().engine().route())).toList());
            if(model) {
                register("plan_case","Create editable unverified intent; no browser",false,Map.of("instructions",string(1000),"id",string(70),"route",string(2000),"params",string(1000),"output",string(1000)),List.of("instructions","id","route","output"),a -> cliConfigured("plan-case",paths(a,"instructions","id","route","params","output")));
                if(execution) register("explore_cases","Discover bounded workflows under caller-owned oracles; execute only when explicitly requested",false,
                    Map.of("charter",string(1000),"params",string(1000),"output-dir",string(1000),"execute",Map.of("type","boolean")),List.of("charter","output-dir"),a -> {
                        var args=paths(a,"charter","params","output-dir");args.add("--locator-mode");args.add("read-write");if(a.path("execute").asBoolean()) args.add("--execute");return cliConfigured("explore-cases",args);
                    });
            }
            if(execution) {
                if(model) register("learn_case","Execute supplied intent and publish a verified recipe; optional charter preserves exact authorization during reproduction",false,Map.of("spec",string(1000),"params",string(1000),"charter",string(1000),"output",string(1000)),List.of("spec","output"),a -> {var args=paths(a,"spec","params","charter","output");args.addAll(List.of("--locator-mode","read-write"));return cliConfigured("learn-case",args);});
                register("replay_case","Replay with unchanged deterministic expectations and no model",false,Map.of("file",string(1000),"params",string(1000)),List.of("file"),a -> cliConfigured("replay-case",paths(a,"file","params")));
                register("run_suite","Execute deterministic isolated datasets",false,Map.of("suite",string(1000),"shard",string(20),"last-failed",string(1000)),List.of("suite"),a -> cliConfigured("run-suite",paths(a,"suite","shard","last-failed")));
                register("session_open","Open an isolated authenticated session under host configuration",false,Map.of("route",string(2000),"persona",string(70)),List.of("route"),this::open);
                register("session_observe","Read fresh redacted semantic state; no arbitrary script execution",true,Map.of("session",string(70),"scope",string(200)),List.of("session"),a -> {var s=session(a);return s.secrets().redactTree(Json.MAPPER.valueToTree(s.engine().observe(a.path("scope").asString("page"),s.deadline())));});
                register("session_action","Perform one explicit semantic UI action; no blind mutation retry",false,
                    Map.of("session",string(70),"kind",Map.of("type","string","enum",List.of("click","fill","select","setChecked","press")),"role",string(100),"name",string(500),"scope",string(200),"value",Map.of("type","string","maxLength",2000)),List.of("session","kind","role","name"),a -> {
                        String kind=a.path("kind").asString();Failure.require(CaseSpec.needsValue(kind)==a.has("value"),"CASE_INVALID","The selected action requires exactly its declared value");
                        var s=session(a);s.engine().action(kind,SemanticTarget.role(a.path("role").asString(),a.path("name").asString(),a.path("scope").asString("page")),a.has("value")?a.path("value").asString():null,s.deadline());return Map.of("dispatched",true);
                    });
                register("session_close","Close the selected managed session on its owner thread",false,Map.of("session",string(70)),List.of("session"),a -> {var s=session(a);sessions.remove(a.path("session").asString());s.close();return Map.of("closed",true);});
                if(!secretRefs.isEmpty()) register("session_fill_secret","Host-resolved opaque fill; permanently prohibits screenshots in this context",false,
                    Map.of("session",string(70),"secretRef",string(128),"role",string(100),"name",string(500),"scope",string(200)),List.of("session","secretRef","role","name"),a -> {
                        String ref=a.path("secretRef").asString();Failure.require(this.secretRefs.contains(ref),"POLICY_DENIED","Secret reference is not approved by server startup policy");
                        var s=session(a);s.engine().fillSecret(SemanticTarget.role(a.path("role").asString(),a.path("name").asString(),a.path("scope").asString("page")),new SecretRef(ref,"opaque-ui-fill"),s.deadline());return Map.of("filled",true,"screenshotsAllowed",false);
                    });
                if(!exportKeys.isEmpty()) register("session_export","Export only an expiring authenticated encrypted session envelope",false,
                    Map.of("session",string(70),"keyRef",string(128),"output",string(1000),"ttlHours",Map.of("type","integer","minimum",1,"maximum",168)),List.of("session","keyRef","output"),a -> {
                        String ref=a.path("keyRef").asString();Failure.require(this.exportKeys.contains(ref),"POLICY_DENIED","Export key is not approved by server startup policy");
                        var s=session(a);s.engine().exportEncryptedSession(path(a.path("output").asString()),ref,Duration.ofHours(a.path("ttlHours").asInt(8)),s.deadline());return Map.of("encrypted",true);
                    });
            }
        }
        private static Map<String,Object> string(int maximum) {return Map.of("type","string","minLength",1,"maxLength",maximum);}
        private void register(String name,String description,boolean readOnly,Map<String,?> properties,List<String> required,java.util.function.Function<JsonNode,Object> call) {
            tools.put(name,new Tool(description,Json.MAPPER.valueToTree(Map.of("type","object","additionalProperties",false,"properties",properties,"required",required)),readOnly,call));
        }
        Path path(String input) {
            try {
                Path requested=Path.of(input);Failure.require(!requested.isAbsolute(),"POLICY_DENIED","MCP file arguments must be workspace-relative");
                Path result=root.resolve(requested).normalize();Failure.require(result.startsWith(root) && !result.equals(root),"POLICY_DENIED","MCP path escapes the workspace");
                Path existing=result;while(!Files.exists(existing)) existing=existing.getParent();
                Failure.require(existing.toRealPath().startsWith(root),"POLICY_DENIED","MCP path redirects outside the workspace");return result;
            } catch(Failure f) {throw f;}catch(Exception e) {throw new Failure("POLICY_DENIED","MCP path cannot be validated");}
        }
        private List<String> paths(JsonNode args,String... names) {
            var result=new ArrayList<String>();for(String name:names) if(args.has(name)) {result.add("--"+name);String value=args.path(name).asString();result.add(Set.of("spec","params","instructions","output","output-dir","charter","file","suite","last-failed").contains(name)?path(value).toString():value);}return result;
        }
        private Object cliConfigured(String name,List<String> args) {args.addAll(List.of("--config",path(config).toString()));return cli(name,args);}
        private Object cli(String name,List<String> args) {
            var command=SfE2eCli.commandLine();var out=new StringWriter();var err=new StringWriter();command.setOut(new PrintWriter(out));command.setErr(new PrintWriter(err));
            var all=new ArrayList<String>();all.add(name);all.addAll(args);var captured=new ByteArrayOutputStream();PrintStream previous=System.out;int exit;
            try(var stream=new PrintStream(captured,true,StandardCharsets.UTF_8)) {System.setOut(stream);exit=command.execute(all.toArray(String[]::new));}finally {System.setOut(previous);}
            if(!err.toString().isEmpty()) System.err.print(err);
            Failure.require(exit==0,"MCP_TOOL_FAILED","Framework command failed with exit "+exit+"; inspect workspace-owned reports and stderr diagnostics");return Map.of("exitCode",exit,"output",out+captured.toString(StandardCharsets.UTF_8));
        }
        private String guide(String topic) {
            Failure.require(GuideCommand.TOPICS.containsKey(topic),"CONFIG_INVALID","Unknown guide topic");
            try(var stream=GuideCommand.class.getResourceAsStream("/framework-guide/"+GuideCommand.TOPICS.get(topic))) {
                Failure.require(stream!=null,"DOCUMENTATION_UNAVAILABLE","Bundled guidance is missing");byte[] bytes=stream.readNBytes(1024*1024+1);Failure.require(bytes.length<=1024*1024,"DOCUMENTATION_UNAVAILABLE","Guide exceeds its bounded envelope");return new String(bytes,StandardCharsets.UTF_8);
            } catch(IOException e) {throw new Failure("DOCUMENTATION_UNAVAILABLE","Cannot read bundled guidance");}
        }
        private Object open(JsonNode args) {
            Failure.require(sessions.size()<4,"STEP_BUDGET_EXHAUSTED","At most four isolated managed sessions may be open");
            String route=args.path("route").asString();FrameworkConfig.validateRoute(route);Persona persona=Persona.valueOf(args.path("persona").asString("default"));
            var config=FrameworkConfig.load(path(this.config),Map.of("profile","deterministic"));var secrets=new SecretRegistry();var auth=new JwtAuthenticator(config,secrets);auth.preflight(Set.of(persona));
            ExecutionLocks lock;try {String identity=config.auth().mode().equals("jwt")?secrets.resolve(new SecretRef(config.persona(persona).usernameRef(),"persona-username")).toLowerCase(Locale.ROOT):BrowserState.path(config,persona).toRealPath().toString();lock=ExecutionLocks.shared(config.org().baseUrl(),identity);}catch(IOException e) {throw new Failure("AUTH_CREDENTIAL_INVALID","Cannot resolve authorized session identity");}
            var deadline=Deadline.after(Duration.ofSeconds(config.execution().testTimeoutSeconds()));PlaywrightEngine engine=null;
            try {engine=new PlaywrightEngine(config,secrets,deadline,persona);engine.bootstrap(auth.frontdoor(persona,deadline),deadline);engine.open(route,deadline);String id=UUID.randomUUID().toString();sessions.put(id,new Session(engine,secrets,deadline,persona,lock));return Map.of("session",id,"route",engine.route(),"persona",persona.name());}
            catch(RuntimeException e) {try {if(engine!=null)engine.close();}finally {lock.close();}throw e;}
        }
        private Session session(JsonNode args) {var session=sessions.get(args.path("session").asString());Failure.require(session!=null,"MCP_SESSION_MISSING","Unknown managed session");session.deadline().check();return session;}
        private Map<String,Object> error(Object id,int code,String message) {var response=new LinkedHashMap<String,Object>();response.put("jsonrpc","2.0");response.put("id",id);response.put("error",Map.of("code",code,"message",message));return response;}
        Object handle(String line) {
            JsonNode request;try {request=Json.MAPPER.readTree(line);}catch(Exception e) {return error(null,-32700,"Invalid JSON");}
            if(request==null) return error(null,-32600,"Invalid JSON-RPC request");
            Object id=request.path("id").isString()?request.path("id").asString():request.path("id").isNumber()?request.path("id").numberValue():null;
            if(!request.isObject() || !"2.0".equals(request.path("jsonrpc").asString()) || !request.path("method").isString() || request.has("id") && id==null) return error(id,-32600,"Invalid JSON-RPC request");
            String method=request.path("method").asString();var params=request.path("params");
            if(!request.has("id")) {if(method.equals("notifications/initialized") && initialized) ready=true;return null;}
            try {
                Object result;
                if(method.equals("initialize")) {Failure.require(!initialized && params.isObject() && params.path("protocolVersion").isString() && params.path("clientInfo").isObject() && params.path("capabilities").isObject(),"MCP_PROTOCOL_INVALID","Initialize requires clientInfo, capabilities and protocolVersion");initialized=true;
                    String version=params.path("protocolVersion").asString();if(!Set.of("2025-11-25","2025-06-18").contains(version)) version="2025-11-25";
                    result=Map.of("protocolVersion",version,"capabilities",Map.of("tools",Map.of("listChanged",false),"resources",Map.of("listChanged",false)),"serverInfo",Map.of("name","sf-e2e","version","1.0.0"));
                } else if(method.equals("ping")) result=Map.of();
                else {
                    Failure.require(ready,"MCP_PROTOCOL_INVALID","Initialize and send notifications/initialized first");
                    var expired=sessions.entrySet().iterator();while(expired.hasNext()) {var entry=expired.next();try {entry.getValue().deadline().check();}catch(Failure elapsed) {entry.getValue().close();expired.remove();}}
                    result=switch(method) {
                        case "tools/list" -> Map.of("tools",tools.entrySet().stream().map(e -> Map.of("name",e.getKey(),"description",e.getValue().description(),"inputSchema",e.getValue().schema(),"annotations",Map.of("readOnlyHint",e.getValue().readOnly(),"destructiveHint",!e.getValue().readOnly(),"idempotentHint",e.getValue().readOnly(),"openWorldHint",!e.getValue().readOnly()))).toList());
                        case "tools/call" -> invoke(params);
                        case "resources/list" -> Map.of("resources",GuideCommand.TOPICS.keySet().stream().sorted().map(topic -> Map.of("uri","sf-e2e://guide/"+topic,"name",topic,"mimeType","text/markdown")).toList());
                        case "resources/read" -> {String uri=params.path("uri").asString();Failure.require(uri.startsWith("sf-e2e://guide/"),"CONFIG_INVALID","Unknown resource URI");yield Map.of("contents",List.of(Map.of("uri",uri,"mimeType","text/markdown","text",guide(uri.substring(15)))));}
                        default -> throw new Failure("MCP_METHOD_MISSING","Unknown MCP method");
                    };
                }
                return Map.of("jsonrpc","2.0","id",id,"result",result);
            } catch(Failure f) {return error(id,f.code().equals("MCP_METHOD_MISSING")?-32601:-32602,f.getMessage());}
        }
        private Object invoke(JsonNode params) {
            String name=params.path("name").asString();var tool=tools.get(name);Failure.require(tool!=null,"CONFIG_INVALID","Tool is unavailable under server startup policy");
            var arguments=params.has("arguments")?params.get("arguments"):Json.MAPPER.createObjectNode();JsonSchema.validate(arguments,tool.schema());
            try {
                Object output=tool.call().apply(arguments);String encoded=Json.string(output);
                Failure.require(encoded.getBytes(StandardCharsets.UTF_8).length<=1024*1024,"STEP_BUDGET_EXHAUSTED","Tool output exceeds its bounded envelope; narrow the requested scope");
                return Map.of("content",List.of(Map.of("type","text","text",output instanceof String?output:encoded)),"isError",false);
            } catch(Exception e) {String code=e instanceof Failure f?f.code():"ENGINE_FAILURE";return Map.of("content",List.of(Map.of("type","text","text",code+": tool failed; inspect host diagnostics")),"isError",true);}
        }
        void serve(InputStream input,PrintWriter output) throws IOException {
            try(var reader=new BufferedInputStream(input)) {
                var line=new ByteArrayOutputStream();boolean oversized=false;int c;
                while((c=reader.read())!=-1) {
                    if(c=='\n') {Object result;try {result=oversized?error(null,-32600,"Message exceeds 1 MiB"):handle(StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(line.toByteArray())).toString());}catch(java.nio.charset.CharacterCodingException e) {result=error(null,-32700,"Invalid UTF-8");}if(result!=null) {output.println(Json.string(result));output.flush();}line.reset();oversized=false;}
                    else if(!oversized) {if(line.size()>=1024*1024) {oversized=true;line.reset();}else line.write(c);}
                }
            }
        }
        public void close() {for(var session:sessions.values()) try {session.close();}catch(RuntimeException ignored) {}sessions.clear();}
    }
}
