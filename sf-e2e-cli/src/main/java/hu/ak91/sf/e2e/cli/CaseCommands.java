package hu.ak91.sf.e2e.cli;

import hu.ak91.sf.e2e.core.*;
import hu.ak91.sf.e2e.core.locator.*;
import hu.ak91.sf.e2e.core.scenario.*;
import hu.ak91.sf.e2e.agent.*;
import hu.ak91.sf.e2e.engine.PlaywrightEngine;
import hu.ak91.sf.e2e.salesforce.auth.JwtAuthenticator;
import hu.ak91.sf.e2e.reporting.EvidenceService;
import hu.ak91.sf.e2e.reporting.WorkflowReportExporter;
import hu.ak91.sf.e2e.reporting.ReportDirectories;
import hu.ak91.sf.e2e.core.quality.BrowserQuality;
import picocli.CommandLine.*;
import java.nio.file.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** File-based workflows do not require generated Java classes or recompilation. */
public final class CaseCommands {
    private CaseCommands() {}
    public static class InputOptions {
        @Mixin SfE2eCli.Options options;
        @Option(names="--persona",defaultValue="default") Persona persona;
        @Option(names="--params",description="Private JSON object of runtime inputs and expected values. Values are not stored in the recipe.") Path params;
        Map<String,String> parameters() { return readParameters(params); }
    }
    static Map<String,String> readParameters(Path params) {
            if(params==null) return Map.of();
            var node=readJson(params);Failure.require(node.isObject() && node.size()<=200,"CASE_INVALID","Parameters must be a JSON object with at most 200 string values");
            Map<String,String> values=new TreeMap<>();
            node.properties().forEach(e -> {
                CaseSpec.validateRef(e.getKey());Failure.require(e.getValue().isString() && e.getValue().asString().length()<=10000,"CASE_INVALID","Parameter values must be strings of at most 10000 characters");
                values.put(e.getKey(),e.getValue().asString());
            });return Map.copyOf(values);
    }
    @Command(name="validate-case",mixinStandardHelpOptions=true,description="Validate a supplied specification and its runtime values offline. No configuration, browser or model required.")
    public static final class Validate implements Callable<Integer> {
        @Option(names="--spec",required=true,description="Structured case specification, not a learned recipe.") Path spec;
        @Option(names="--params",description="Private JSON runtime inputs and expected values.") Path params;
        @Spec picocli.CommandLine.Model.CommandSpec command;
        public Integer call() {
            var supplied=readSpec(spec);var values=readParameters(params);validateSpecParams(supplied,values);
            command.commandLine().getOut().println(Json.string(Map.of("status","VALID","caseId",supplied.id(),"steps",supplied.steps().size(),
                "assertions",supplied.steps().stream().filter(s -> CaseSpec.assertion(s.kind())).count(),"requiredParameters",requiredParams(supplied),
                "uiVerified",false,"nextStep","Review intent, then use learn-case --spec with target configuration and runtime parameters.")));
            return 0;
        }
    }
    @Command(name="plan-case",mixinStandardHelpOptions=true,description="Translate text into an editable specification using AI. No target login, browser, actions or locator writes.")
    public static final class Plan implements Callable<Integer> {
        @Mixin SfE2eCli.Options options;
        @Option(names="--instructions",required=true,description="UTF-8 case with explicit actions and expected results.") Path instructions;
        @Option(names="--id",required=true) String id;
        @Option(names="--route",required=true) String route;
        @Option(names="--params",description="Private JSON runtime inputs and expected values.") Path params;
        @Option(names="--output",required=true,description="New editable specification JSON; TST_ is added to its filename. This is not a verified recipe.") Path output;
        public Integer call() {
            output=preparedOutput(output);requireNewOutput(parameterOutput(output));id=CaseNames.generated(id);FrameworkConfig.validateRoute(route);
            String text=readText(instructions,40000);Failure.require(!text.isBlank(),"CASE_INVALID","Test instructions are empty");
            var values=new TreeMap<>(readParameters(params));var config=options.config("mapping");
            Failure.require(config.profile().equals("mapping"),"CONFIG_INVALID","plan-case requires the mapping profile");
            var secrets=new SecretRegistry();var model=AiWorkflowClient.create(config.agent(),secrets,e -> {});
            var deadline=Deadline.after(Duration.ofSeconds(Math.min(config.execution().testTimeoutSeconds(),config.agent().actTimeoutSeconds())));
            var plan=new CasePlanner(model,config.agent(),secrets).plan(id,route,text,values.keySet(),deadline);
            values.putAll(plan.parameters());return savePlan(output,plan.spec(),values);
        }
    }
    static int savePlan(Path output,CaseSpec supplied,Map<String,String> values) {
        validateSpecParams(supplied,values);requireNewOutput(output);requireNewOutput(parameterOutput(output));
        if(!values.isEmpty()) Json.write(parameterOutput(output),values);
        Json.write(output,supplied);
        System.out.println("Editable specification (UI not verified): "+output.toAbsolutePath());
        if(!values.isEmpty()) System.out.println("Private runtime parameters: "+parameterOutput(output).toAbsolutePath());
        System.out.println("Review the specification and expected results; validate-case, then learn-case --spec to create a verified recipe.");
        return 0;
    }
    @Command(name="learn-case",mixinStandardHelpOptions=true,description="Learn a supplied JSON/text test case with AI; save a verified recipe for model-free replay.")
    public static final class Learn implements Callable<Integer> {
        ExplorationCharter charter;
        @Option(names="--charter",description="Optional consumer charter for a reviewed --spec; enforces exact authorized target names and unchanged oracles.") Path charterFile;
        @Mixin InputOptions input;
        @Option(names="--spec",description="JSON {id,startRoute,steps:[{kind,instruction,scope,valueRef,...}]}.") Path spec;
        @Option(names="--instructions",description="UTF-8 test case; requires --id/--route. Literal values are extracted into a separate private parameter file.") Path instructions;
        @Option(names="--id",description="Stable ID for text input; TST_ is added when absent.") String id;
        @Option(names="--route",description="Application-relative start route for text input.") String route;
        @Option(names="--output",required=true,description="New recipe JSON file; its filename gets TST_ when absent. Existing recipes are preserved.") Path output;
        public Integer call() {
            var config=input.options.config("mapping");requireMapping(config);output=preparedOutput(output);
            Failure.require(charterFile==null || spec!=null,"CASE_INVALID","Charter learning requires a reviewed specification");
            if(charterFile!=null) charter=Json.MAPPER.treeToValue(readJson(charterFile),ExplorationCharter.class);
            Failure.require((spec!=null) != (instructions!=null),"CASE_INVALID","Supply exactly one of --spec or --instructions");
            var params=new TreeMap<>(input.parameters());CaseSpec supplied=null;String text=null;
            if(spec!=null) {
                Failure.require(id==null && route==null,"CASE_INVALID","--id/--route are supplied by --spec");
                supplied=readSpec(spec);
                validateSpecParams(supplied,params);id=supplied.id();route=supplied.startRoute();
                if(charter!=null) charter.validate(supplied,params.keySet());
            } else {
                requireNewOutput(parameterOutput(output));
                Failure.require(id!=null && route!=null,"CASE_INVALID","Text input requires --id and --route");id=CaseNames.generated(id);
                FrameworkConfig.validateRoute(route);text=readText(instructions,40000);
                Failure.require(!text.isBlank(),"CASE_INVALID","Test instructions are empty");
            }
            final CaseSpec prepared=supplied;final String content=text;
            return session(config,input.persona,id,route,params,"learning",output,(engine,store,model,namespace,deadline,secrets,events) -> {
                var learner=new CaseLearner(engine,store,model,config,secrets,events);
                CaseSpec plan=prepared;
                if(plan==null) {
                    var translated=learner.planWithParameters(id,route,content,params.keySet(),deadline.child(Duration.ofSeconds(config.agent().actTimeoutSeconds())));
                    plan=translated.spec();params.putAll(translated.parameters());
                    if(!translated.parameters().isEmpty()) events.accept(Map.of("kind","case-parameters-extracted","parameterNames",params.keySet()));
                }
                return learner.learn(plan,namespace,params,deadline,charter);
            });
        }
    }
    @Command(name="discover-ui",mixinStandardHelpOptions=true,description="Autonomously map visible navigation pages with AI and save model-free presence checks. No form submission.")
    public static final class Discover implements Callable<Integer> {
        @Mixin InputOptions input;
        @Option(names="--id",required=true,description="Case ID; TST_ is added when absent.") String id;
        @Option(names="--route",required=true) String route;
        @Option(names="--max-pages",defaultValue="5",description="Hard page limit, 1..50; model/action/token/time limits also apply.") int pages;
        @Option(names="--output",required=true,description="New recipe filename; TST_ is added when absent.") Path output;
        public Integer call() {
            var config=input.options.config("mapping");requireMapping(config);output=preparedOutput(output);id=CaseNames.generated(id);
            Failure.require(pages>0 && pages<=50 && input.params==null,"CASE_INVALID","Discovery requires 1..50 pages and no business parameters");
            FrameworkConfig.validateRoute(route);
            return session(config,input.persona,id,route,Map.of(),"discovery",output,(engine,store,model,namespace,deadline,secrets,events) ->
                new UiExplorer(engine,store,model,config,secrets,events).discover(id,route,namespace,pages,deadline));
        }
    }
    @Command(name="replay-case",mixinStandardHelpOptions=true,description="Execute a learned recipe with runtime parameters and deterministic self-healing. No AI client is constructed.")
    public static final class Replay implements Callable<Integer> {
        @Mixin InputOptions input;
        @Option(names="--file",required=true,description="Verified recipe produced by learn-case or discover-ui.") Path file;
        public Integer call() {
            var config=input.options.config("deterministic");
            Failure.require(config.profile().equals("deterministic"),"CONFIG_INVALID","replay-case requires the deterministic profile");
            var recipe=CaseRecipe.load(file);var params=input.parameters();String namespace=LocatorStore.namespace(config,input.persona);
            var store=new LocatorStore(Path.of(config.locators().dir()),namespace,config.locators().mode());recipe.preflight(namespace,store,params);
            return session(config,input.persona,recipe.id(),recipe.startRoute(),params,"replay",null,(engine,disk,unused,context,deadline,secrets,events) -> {
                new CaseRunner(engine,disk,config,deadline,events).run(recipe,context,disk,params);return recipe;
            });
        }
    }
    @FunctionalInterface interface Work {
        CaseRecipe execute(PlaywrightEngine engine,LocatorStore store,ModelClient model,String namespace,Deadline deadline,SecretRegistry secrets,Consumer<Map<String,Object>> events);
    }
    /** A verified value cannot escape while its owned session still needs successful cleanup. */
    static CaseRecipe verifiedAfterClose(AutoCloseable session,Callable<CaseRecipe> verification) throws Exception {
        try(session) { return verification.call(); }
    }
    /** Publication requires successful report finalization and never repeats application actions. */
    static void publishVerifiedOutput(Path output,CaseRecipe recipe,Map<String,String> params,boolean generated,Runnable report) {
        requireNewOutput(output);
        Path values=generated?parameterOutput(output):null;
        if(values!=null) requireNewOutput(values);
        report.run();
        try {
            Path absolute=output.toAbsolutePath();Files.createDirectories(absolute.getParent());
            try(var channel=FileChannel.open(absolute.resolveSibling(absolute.getFileName()+".publish.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
                var lock=channel.tryLock()) {
                Failure.require(lock!=null,"CASE_OUTPUT_BUSY","Another process is publishing this recipe output");
                requireNewOutput(output);if(values!=null) requireNewOutput(values);
                if(values!=null) Json.write(values,params);
                recipe.save(output);
            }
        } catch(OverlappingFileLockException busy) { throw new Failure("CASE_OUTPUT_BUSY","Another process is publishing this recipe output"); }
        catch(java.io.IOException failure) { throw new Failure("JOURNAL_WRITE_FAILED","Cannot lock or publish verified recipe output"); }
    }
    static int session(FrameworkConfig config,Persona persona,String id,String startRoute,Map<String,String> params,String mode,Path output,Work work) {
        return session(config,persona,id,startRoute,params,mode,output,work,null,null);
    }
    static int replayDataset(FrameworkConfig config,Persona persona,CaseRecipe recipe,Map<String,String> params,Path root,String datasetId) {
        return session(config,persona,recipe.id(),recipe.startRoute(),params,"replay",null,(engine,store,unused,namespace,deadline,secrets,events) -> {
            new CaseRunner(engine,store,config,deadline,events).run(recipe,namespace,store,params);return recipe;
        },root,datasetId);
    }
    private static int session(FrameworkConfig config,Persona persona,String id,String startRoute,Map<String,String> params,String mode,Path output,Work work,Path reservedRoot,String datasetId) {
        boolean learning=!mode.equals("replay");
        var deadline=Deadline.after(Duration.ofSeconds(config.execution().testTimeoutSeconds()));var secrets=new SecretRegistry();
        Instant startedAt=Instant.now();Path reportRoot=reservedRoot==null?ReportDirectories.create(Path.of(config.report().root()),startedAt):reservedRoot;
        String runId=reservedRoot==null?reportRoot.getFileName().toString():reportRoot.getParent().getParent().getFileName()+"."+datasetId,attemptId=UUID.randomUUID().toString();
        Path journal=Path.of(config.retention().journalDir(),"case-attempts",attemptId+".json");
        var active=new PlaywrightEngine[1];var evidence=new EvidenceService(reportRoot,runId,attemptId,() -> active[0],deadline,secrets,config.evidence().screenshotsAfterSteps());
        var state=new LinkedHashMap<String,Object>();state.put("schemaVersion",1);state.put("runId",runId);state.put("attemptId",attemptId);state.put("caseId",id);
        state.put("mode",mode);state.put("executionModel","page-object-model");state.put("status","RUNNING");state.put("startedAt",startedAt);state.put("parameterNames",params.keySet());
        state.put("persona",persona.name());
        state.put("trendContext",Json.hash(Map.of("namespace",LocatorStore.namespace(config,persona),"ui",config.ui(),"execution",config.execution())));
        if(datasetId!=null) state.put("datasetId",datasetId);
        state.put("reportRoot",reportRoot.toAbsolutePath().toString());state.put("modelCalls",0);
        Consumer<Map<String,Object>> events=event -> {
            evidence.event(event);
            if("model-call-started".equals(event.get("kind"))) state.put("modelCalls",event.get("call"));
            if("case-parameters-extracted".equals(event.get("kind"))) state.put("generatedParameters",true);
            if("case-plan-ready".equals(event.get("kind"))) state.put("trendScope",Json.hash(event.get("plan")));
            if("case-replay-plan".equals(event.get("kind"))) state.put("trendScope",Json.hash(event.get("steps")));
            if("discovery-plan-ready".equals(event.get("kind"))) state.put("trendScope",Json.hash(event));
            if("discovery-completed".equals(event.get("kind"))) state.put("discovery",secrets.redactTree(Json.MAPPER.valueToTree(event)));
            state.put("lastEvent",secrets.redactTree(Json.MAPPER.valueToTree(event)));Json.write(journal,state);
        };
        Json.write(journal,state);
        CaseRecipe verifiedRecipe=null;
        RuntimeException primaryFailure=null;
        try {
            var auth=new JwtAuthenticator(config,secrets);auth.preflight(Set.of(persona));
            String identity=config.auth().mode().equals("jwt")?secrets.resolve(new SecretRef(config.persona(persona).usernameRef(),"persona-username")).toLowerCase(Locale.ROOT):BrowserState.path(config,persona).toRealPath().toString();
            try(var lock=ExecutionLocks.shared(config.org().baseUrl(),identity)) {
                ModelClient model=learning?AiWorkflowClient.create(config.agent(),secrets,events):null;
                String namespace=LocatorStore.namespace(config,persona);var store=new LocatorStore(Path.of(config.locators().dir()),namespace,config.locators().mode());
                var engine=new PlaywrightEngine(config,secrets,deadline,persona);
                try {
                    verifiedRecipe=verifiedAfterClose(engine,() -> {
                        engine.operationEvents(events);
                        if(!config.ui().fileInputRoot().isBlank()) engine.configureFiles(Path.of(config.ui().fileInputRoot()),evidence.attemptRoot().resolve("private-downloads"),evidence::event);
                        active[0]=engine;
                        try {
                            engine.bootstrap(auth.frontdoor(persona,deadline),deadline);engine.startQualityMonitoring();engine.open(startRoute,deadline);
                            if(config.evidence().privateTraceOnFailure()) engine.startPrivateTrace(evidence.attemptRoot().resolve("private-trace"),attemptId);
                            UiReadiness.stable(engine,"page",deadline.child(Duration.ofSeconds(config.locators().timeoutSeconds())));evidence.checkpoint("case-start");
                            var recipe=work.execute(engine,store,model,namespace,deadline,secrets,events);
                            evidence.checkpoint("test-completion");evidence.verifyMinimum(config.evidence().minPngPerPassedResult());
                            BrowserQuality.verify(config.quality(),engine.browserIssues(),evidence.events(),events);
                            return recipe;
                        } catch(RuntimeException failure) {
                            try { engine.finishPrivateTrace(true); } catch(RuntimeException traceFailure) { failure.addSuppressed(traceFailure); }
                            if(!Set.of("APPLICATION_DEFECT","QUALITY_GATE_FAILED").contains(failure instanceof Failure f?f.code():""))
                                try { BrowserQuality.verify(config.quality(),engine.browserIssues(),evidence.events(),events); } catch(RuntimeException secondary) { failure.addSuppressed(secondary); }
                            try { evidence.checkpoint("case-failure"); } catch(RuntimeException withheld) {
                                failure.addSuppressed(withheld);evidence.event(Map.of("kind","failure-capture-unavailable","code",withheld instanceof Failure f?f.code():"ENGINE_FAILURE"));
                            }
                            throw failure;
                        }
                    });
                } finally { active[0]=null; }
            }
            state.put("recipeChecksum",Json.hash(verifiedRecipe));state.put("pageObjects",verifiedRecipe.pageObjects());state.put("steps",verifiedRecipe.steps().size());state.put("status","PASSED");
            if(output!=null) {
                state.put("recipe",output.toAbsolutePath().toString());
                if(Boolean.TRUE.equals(state.get("generatedParameters"))) state.put("parameterFile",parameterOutput(output).toAbsolutePath().toString());
            }
        } catch(Exception failure) {
            String code=failure instanceof Failure f?f.code():failure instanceof OverlappingFileLockException?"ENVIRONMENT_UNAVAILABLE":"ENGINE_FAILURE";
            state.put("status","FAILED");state.put("code",code);state.put("diagnostic",secrets.redact(Objects.toString(failure.getMessage(),failure.getClass().getSimpleName())));
            primaryFailure=failure instanceof Failure f?f:new Failure(code,"Case workflow failed; inspect the permanent attempt journal and report");
            throw primaryFailure;
        } finally {
            try {
                state.put("completedAt",Instant.now());state.put("artifacts",evidence.artifacts());state.put("trendParametersHash",Json.hash(params));
                Json.write(journal,state);
                Runnable report=() -> {
                    WorkflowReportExporter.write(reportRoot,state,evidence.events(),reservedRoot==null);
                    WorkflowReportExporter.verifyEvidence(reportRoot);
                };
                if(output!=null && "PASSED".equals(state.get("status")))
                    publishVerifiedOutput(output,verifiedRecipe,params,Boolean.TRUE.equals(state.get("generatedParameters")),report);
                else report.run();
            } catch(RuntimeException finalizationFailure) {
                if("PASSED".equals(state.get("status"))) {
                    state.put("status","FAILED");state.put("code",finalizationFailure instanceof Failure f?f.code():"REPORT_GENERATION_FAILED");
                    state.put("diagnostic",secrets.redact(Objects.toString(finalizationFailure.getMessage(),"Workflow finalization failed")));
                    state.remove("recipe");state.remove("parameterFile");
                    try { Json.write(journal,state);WorkflowReportExporter.write(reportRoot,state,evidence.events(),reservedRoot==null); }
                    catch(RuntimeException secondary) { finalizationFailure.addSuppressed(secondary); }
                }
                if(primaryFailure!=null) primaryFailure.addSuppressed(finalizationFailure);
                else throw finalizationFailure;
            }
            if(reservedRoot==null && Files.isRegularFile(reportRoot.resolve("index.html"))) System.out.println("Allure report: "+reportRoot.resolve("index.html"));
            System.out.println("Case data: "+reportRoot.resolve("case-report.json"));System.out.println("Permanent attempt journal: "+journal);
            if(Files.isRegularFile(reportRoot.resolve("test-design.html"))) System.out.println("Implemented test design: "+reportRoot.resolve("test-design.html"));
        }
        if(output!=null) System.out.println("Verified recipe: "+output.toAbsolutePath());
        if(state.containsKey("parameterFile")) System.out.println("Private runtime parameters: "+state.get("parameterFile"));
        if(state.containsKey("discovery")) System.out.println("Discovery scope (navigation presence only): "+Json.string(state.get("discovery")));
        System.out.println("Case "+id+" PASSED; model calls="+state.get("modelCalls"));return 0;
    }
    static void validateSpecParams(CaseSpec spec,Map<String,String> params) {
        var validation=spec.steps().stream().map(s -> new CaseRecipe.Step(s.kind(),CaseSpec.targeted(s.kind())?"validation":null,CaseSpec.targeted(s.kind())?"validation":null,s.route(),s.valueRef(),s.recordNameRef())).toList();
        CaseRecipe.validateParams(requiredParams(spec),params,validation);
    }
    private static Set<String> requiredParams(CaseSpec spec) {
        Set<String> required=new TreeSet<>();spec.steps().forEach(s -> { if(s.valueRef()!=null) required.add(s.valueRef());if(s.recordNameRef()!=null) required.add(s.recordNameRef()); });return required;
    }
    private static CaseSpec readSpec(Path file) {
        try { return Json.MAPPER.treeToValue(readJson(file),CaseSpec.class); }
        catch(Failure e) { throw e; } catch(Exception e) { throw new Failure("CASE_INVALID","Case specification has invalid or unknown fields; supply {id,startRoute,steps} ending in an explicit assertion"); }
    }
    private static void requireMapping(FrameworkConfig config) {
        Failure.require(config.profile().equals("mapping"),"CONFIG_INVALID","Discovery/learning requires the mapping profile");
        Failure.require(config.locators().mode().equals("read-write"),"LOCATOR_READ_ONLY","Use --locator-mode read-write for discovery/learning");
    }
    private static void requireNewOutput(Path file) {
        Failure.require(!Files.exists(file),"CASE_RECIPE_EXISTS","Recipe already exists; use a new output path to preserve earlier learned cases");
    }
    static Path preparedOutput(Path requested) {
        requireNewOutput(requested);var named=CaseNames.output(requested);requireNewOutput(named);return named;
    }
    private static Path parameterOutput(Path file) { return file.resolveSibling(file.getFileName()+".params.local.json"); }
    static tools.jackson.databind.JsonNode readJson(Path file) {
        try { return Json.MAPPER.readTree(readText(file,1024*1024)); }
        catch(Failure e) { throw e; } catch(Exception e) { throw new Failure("CASE_INVALID","Invalid case JSON"); }
    }
    static String readText(Path file,int limit) {
        try {
            Failure.require(Files.isRegularFile(file) && Files.size(file)<=limit,"CASE_INVALID","Input file is missing or exceeds its size limit");
            return Files.readString(file,StandardCharsets.UTF_8);
        } catch(Failure e) { throw e; } catch(Exception e) { throw new Failure("CASE_INVALID","Cannot read input file"); }
    }
}
