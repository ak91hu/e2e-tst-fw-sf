import hu.ak91.sf.e2e.agent.*;
import hu.ak91.sf.e2e.core.*;
import hu.ak91.sf.e2e.core.locator.*;
import hu.ak91.sf.e2e.core.scenario.*;
import hu.ak91.sf.e2e.engine.PlaywrightEngine;
import hu.ak91.sf.e2e.reporting.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.math.BigDecimal;

/** Credential-free internal benchmark, separate from the empty consumer test workspace. */
public class MeasureFrameworkEfficiency {
    static final Map<String,List<Double>> measurements=new LinkedHashMap<>();
    static final List<Map<String,Object>> rows=new ArrayList<>();
    static final List<Map<String,Object>> costRows=new ArrayList<>();
    static final AtomicBoolean changed=new AtomicBoolean();
    static Path root;
    static String origin;
    static final Map<String,String> values=Map.of("input","benchmark-value","expected","benchmark-value");
    static final CaseSpec spec=new CaseSpec("internal-efficiency","/app",List.of(
        new CaseSpec.Step("fill","Reference",null,null,"input",null),
        new CaseSpec.Step("expectValue","Reference",null,null,"expected",null)));
    record Learned(CaseRecipe recipe,LocatorStore store,Map<String,Object> metrics) {}

    public static void main(String[] args) throws Exception {
        int samples=args.length==0?5:Integer.parseInt(args[0]);
        if(samples<3 || samples>20 || args.length>1) throw new IllegalArgumentException("Usage: MeasureFrameworkEfficiency.java [samples:3..20]");
        root=ReportDirectories.create(Path.of("report"),Instant.now());
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange -> {
            byte[] html=("<!doctype html><html><body><label>"+(changed.get()?"Updated reference":"Reference")+
                "<input data-testid='reference'></label><label>Secondary<input data-testid='secondary'></label><label>Tertiary<input data-testid='tertiary'></label></body></html>").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","text/html; charset=utf-8");exchange.sendResponseHeaders(200,html.length);
            exchange.getResponseBody().write(html);exchange.close();
        });server.start();origin="http://127.0.0.1:"+server.getAddress().getPort();
        Instant started=Instant.now();
        Map<String,Object> result=new LinkedHashMap<>();
        try {
            // One complete warmup is excluded from statistics; fresh contexts remain part of every sample.
            var warm=learn(true,"warmup",false);replay(warm,"warmup",false,false,false);measurements.clear();rows.clear();
            for(int sample=1;sample<=samples;sample++) {
                changed.set(false);Learned enabled=null;
                // Alternate paired order to reduce systematic first-run bias.
                for(boolean reuse:sample%2==1?List.of(false,true):List.of(true,false)) {
                    var learned=learn(reuse,sample+"-"+(reuse?"reuse":"baseline"),true);
                    rows.add(Map.of("sample",sample,"reuseEnabled",reuse,"aiWorkflow",learned.metrics()));
                    if(reuse) enabled=learned;
                }
                replay(enabled,"replay-"+sample,false,true,true);
                changed.set(true);replay(enabled,"heal-"+sample,false,true,false);
                var persisted=enabled.store().get(enabled.recipe().steps().getFirst().page(),enabled.recipe().steps().getFirst().key());
                require("Updated reference".equals(persisted.primary().name()),"Repair did not persist the changed label");
                replay(enabled,"restart-"+sample,true,true,false);
                changed.set(false);
                for(boolean optimized:sample%2==1?List.of(false,true):List.of(true,false)) measureAiCost(optimized,sample);
            }
            var proof=learn(true,"wrong-expectation",false);
            try(var engine=new PlaywrightEngine(config(true,false),new SecretRegistry(),deadline())) {
                try {
                    new CaseRunner(engine,proof.store(),config(true,false),deadline(),e -> {}).run(proof.recipe(),"benchmark",proof.store(),Map.of("input","benchmark-value","expected","wrong"));
                    throw new AssertionError("Wrong expectation was accepted");
                } catch(Failure failure) {require("ASSERTION_FAILED".equals(failure.code()),"Unexpected wrong-result failure code");}
            }
            measureStore(proof);
            measureBrowserReuse(samples);
            var rendered=render(samples,started);
            result.put("schemaVersion",1);result.put("status","PASSED");result.put("startedAt",started.toString());result.put("completedAt",Instant.now().toString());
            result.put("java",System.getProperty("java.version"));result.put("os",System.getProperty("os.name"));result.put("samplesPerBrowserPhase",samples);
            result.put("method","Local synthetic HTML; zero-latency scripted provider with fixed usage; one excluded warmup; alternating paired learning order; fresh Chromium context per phase");
            result.put("exclusions",List.of("Salesforce authentication/network", "paid-provider latency/quality/prices", "complete CLI journal/evidence finalization", "hosted CI throughput"));
            Map<String,Object> statistics=new LinkedHashMap<>();measurements.forEach((key,data) -> statistics.put(key,stats(data)));result.put("timingsMs",statistics);
            result.put("learningSamples",rows);result.put("wrongExpectationRejected",true);result.put("freshReplayProviderCalls",0);result.put("healingAndRestartProviderCalls",0);
            result.put("aiCostComparison",costRows);
            result.put("illustrativeRates",Map.of("inputUsdPerMillionTokens",1,"outputUsdPerMillionTokens",10,"providerUsageIsSynthetic",true));
            result.put("allureHtml",rendered.toString());result.put("shadedCliBytes",Files.size(Path.of("sf-e2e-cli/target/sf-e2e.jar")));
            Json.write(root.resolve("efficiency.json"),result);
            System.out.println(Json.string(Map.of("status","PASSED","metrics",root.resolve("efficiency.json").toString(),"allure",root.resolve("index.html").toString(),"timingsMs",statistics)));
        } catch(Throwable failure) {
            Json.write(root.resolve("efficiency.json"),Map.of("status","FAILED","code",failure instanceof Failure f?f.code():failure.getClass().getSimpleName()));throw failure;
        } finally {server.stop(0);}
    }
    static Learned learn(boolean reuse,String id,boolean measured) throws Exception {
        long total=System.nanoTime();var config=config(reuse,false);var store=new LocatorStore(root.resolve("maps/"+id),"benchmark","read-write");
        var events=new ArrayList<Map<String,Object>>();
        var provider=new ScriptedModelClient(request -> {
            var node=request.observation().nodes().stream().filter(n -> "reference".equals(n.attributes().get("testId"))).findFirst().orElseThrow();
            return new ModelClient.Response("finish",null,null,null,"PASSED","Observed fixture input",null,List.of(),Json.MAPPER.valueToTree(Map.of("nodeId",node.id())),25L,20L,5L);
        });
        var model=new AiWorkflowClient(config.agent(),() -> provider,() -> {throw new AssertionError("Unexpected escalation");},e -> events.add(Map.copyOf(e)));
        long creation=System.nanoTime();CaseRecipe recipe;
        try(var engine=new PlaywrightEngine(config,new SecretRegistry(),deadline())) {
            if(measured) add("browserStartup",elapsed(creation));long operation=System.nanoTime();
            recipe=new CaseLearner(engine,store,model,config,new SecretRegistry(),events::add).learn(spec,"benchmark",values,deadline());
            if(measured) add(reuse?"learnReuseCore":"learnBaselineCore",elapsed(operation));
        }
        require(provider.calls()==(reuse?1:2),"Learning call count differs from the measured contract");
        require(events.stream().filter(e -> "assertion-passed".equals(e.get("kind"))).count()==1,"Learning did not verify the expected value");
        if(measured) add(reuse?"learnReuseTotal":"learnBaselineTotal",elapsed(total));
        Path path=root.resolve("recipes/"+id+".json");recipe.save(path);return new Learned(CaseRecipe.load(path),store,model.snapshot());
    }
    static void measureBrowserReuse(int samples) {
        for(int sample=1;sample<=samples;sample++) for(boolean pooled:sample%2==1?List.of(false,true):List.of(true,false)) {
            long start=System.nanoTime();var config=config(true,false);
            if(pooled) try(var runtime=new PlaywrightEngine.WorkerRuntime(config)) {freshContextPair(config);}else freshContextPair(config);
            add(pooled?"twoCasesPooledBrowser":"twoCasesFreshBrowsers",elapsed(start));
        }
    }
    static void freshContextPair(FrameworkConfig config) {
        for(int row=0;row<2;row++) try(var engine=new PlaywrightEngine(config,new SecretRegistry(),deadline())) {
            engine.open("/app",deadline());engine.harnessScript("if(window.previousCase) throw Error('Context was shared');window.previousCase=true");
            engine.action("fill",SemanticTarget.label("Reference","page"),"benchmark-value",deadline());
            require("benchmark-value".equals(engine.resolve(SemanticTarget.label("Reference","page"),engine.observe("page",deadline())).value()),"Browser reuse changed the expected outcome");require(engine.captureMaskedPng(deadline()).length>100,"Browser reuse lost evidence");
        }
    }
    static void measureAiCost(boolean optimized,int sample) {
        var base=config(true,false);var a=base.agent();var w=a.workflow();
        var policy=new FrameworkConfig.AiWorkflow(true,w.maxInputBytes(),w.maxOutputTokens(),w.outputTokenParameter(),1,0,BigDecimal.ZERO,BigDecimal.ONE,BigDecimal.TEN,null,optimized,optimized,optimized,8);
        var agent=new FrameworkConfig.Agent(a.endpoint(),a.model(),a.apiKeyRef(),a.maxActions(),a.maxModelCalls(),a.maxTokens(),a.actTimeoutSeconds(),policy);
        var config=new FrameworkConfig(1,base.projectId(),base.profile(),base.org(),base.ui(),base.auth(),base.personas(),base.execution(),agent,base.cache(),base.retention(),base.evidence(),base.report(),base.locators());
        var caseSpec=new CaseSpec("cost-comparison","/app",List.of(new CaseSpec.Step("fill","Reference",null,null,"input",null),
            new CaseSpec.Step("fill","Secondary",null,null,"input",null),new CaseSpec.Step("fill","Tertiary",null,null,"input",null),
            new CaseSpec.Step("expectValue","Tertiary",null,null,"expected",null)));
        var provider=new ScriptedModelClient(request -> {
            Object data;
            if(request.kind().equals("case-target-batch")) {
                var targets=new ArrayList<Map<String,String>>();
                for(var intent:Json.MAPPER.valueToTree(request.params().get("targets"))) {
                    String name=intent.path("instruction").asString();var node=request.observation().nodes().stream().filter(n -> name.equals(n.target().name())).findFirst().orElseThrow();
                    targets.add(Map.of("id",intent.path("id").asString(),"nodeId",node.id()));
                }
                data=Map.of("targets",targets);
            } else {
                String name=request.params().get("target").toString();var node=request.observation().nodes().stream().filter(n -> name.equals(n.target().name())).findFirst().orElseThrow();data=Map.of("nodeId",node.id());
            }
            return new ModelClient.Response("finish",null,null,null,"PASSED","Synthetic observed controls",null,List.of(),Json.MAPPER.valueToTree(data),25L,20L,5L);
        });
        var events=new ArrayList<Map<String,Object>>();var model=new AiWorkflowClient(agent,() -> provider,() -> {throw new AssertionError();},events::add);
        var store=new LocatorStore(root.resolve("cost-maps/"+sample+"-"+optimized),"benchmark","read-write");
        try(var engine=new PlaywrightEngine(config,new SecretRegistry(),deadline())) {
            new CaseLearner(engine,store,model,config,new SecretRegistry(),events::add).learn(caseSpec,"benchmark",values,deadline());
        }
        require(provider.calls()==(optimized?1:3),"Batch comparison has unexpected physical calls");
        require(events.stream().filter(e -> "assertion-passed".equals(e.get("kind"))).count()==1,"Batch learning omitted the caller assertion");
        costRows.add(Map.of("sample",sample,"optimizationsEnabled",optimized,"aiWorkflow",model.snapshot()));
    }
    static void replay(Learned learned,String id,boolean strict,boolean measured,boolean screenshots) throws Exception {
        long total=System.nanoTime();var config=config(true,strict);var events=new ArrayList<Map<String,Object>>();
        // Every replay reloads the repository from disk; no ModelClient exists on this path.
        var store=new LocatorStore(learned.store().path().getParent(),"benchmark",strict?"read-only":"read-write");
        try(var engine=new PlaywrightEngine(config,new SecretRegistry(),deadline())) {
            long operation=System.nanoTime();new CaseRunner(engine,store,config,deadline(),events::add).run(learned.recipe(),"benchmark",store,values);
            if(measured) add(strict?"healedRestartCore":changed.get()?"healReplayCore":"replayCore",elapsed(operation));
            require(events.stream().filter(e -> "assertion-passed".equals(e.get("kind"))).count()==1,"Replay did not verify the expected value");
            if(changed.get() && !strict) require(events.stream().filter(e -> "locator-healed".equals(e.get("kind"))).count()==1,"Expected one independent repair");
            if(screenshots) {
                long capture=System.nanoTime();byte[] png=engine.captureMaskedPng(deadline());
                require(png.length>8 && png[0]==(byte)137 && png[1]==80,"Missing PNG evidence");
                add("maskedScreenshot",elapsed(capture));Files.createDirectories(root.resolve("png"));Files.write(root.resolve("png/"+id+".png"),png);
            }
        }
        if(measured) add(strict?"healedRestartTotal":changed.get()?"healReplayTotal":"replayTotalWithOnePng",elapsed(total));
    }
    static void measureStore(Learned source) {
        var original=source.store().entries().values().iterator().next();
        for(int size:List.of(10,100,1000)) {
            var store=new LocatorStore(root.resolve("store-probes/"+size),"benchmark","read-write");var entries=new ArrayList<LocatorEntry>();
            for(int i=0;i<size;i++) entries.add(new LocatorEntry("probe","field."+i,original.route(),original.engineVersion(),original.primary(),original.attributes(),List.of(),1,List.of()));
            store.putAll(entries);store.get("probe","field.0");
            for(int lookup=0;lookup<30;lookup++) {
                long started=System.nanoTime();store.get("probe","field."+(lookup%size));add("storeLookup"+size,elapsed(started));
                var cold=new LocatorStore(store.path().getParent(),"benchmark","read-only");started=System.nanoTime();
                cold.get("probe","field."+(lookup%size));add("storeColdLookup"+size,elapsed(started));
            }
        }
    }
    static Path render(int samples,Instant started) throws Exception {
        Files.createDirectories(root.resolve("allure-results"));String uuid=UUID.randomUUID().toString();
        Map<String,Object> coreTimings=new LinkedHashMap<>();measurements.forEach((key,data) -> coreTimings.put(key,stats(data)));
        Json.write(root.resolve("allure-results/core-efficiency.json"),Map.of("method","Synthetic local benchmark; before Allure rendering","timingsMs",coreTimings,"learningSamples",rows,"aiCostComparison",costRows));
        Files.copy(root.resolve("png/replay-1.png"),root.resolve("allure-results/benchmark.png"));
        Json.write(root.resolve("allure-results/"+uuid+"-result.json"),Map.of("uuid",uuid,"name","Internal framework efficiency measurement","fullName","internal.framework.efficiency",
            "status","passed","stage","finished","start",started.toEpochMilli(),"stop",Instant.now().toEpochMilli(),
            "description","Synthetic local fixture: supplied value verified, model-free replay, persisted healing/restart and wrong-expectation rejection. Metrics exclude Salesforce/provider benchmarks.",
            "attachments",List.of(Map.of("name","Core efficiency before rendering","type","application/json","source","core-efficiency.json"),
                Map.of("name","Masked synthetic fixture","type","image/png","source","benchmark.png"))));
        Path output=null;
        for(int sample=0;sample<Math.min(samples,3);sample++) {long timer=System.nanoTime();output=AllureReports.generate(root);add("allureGenerate",elapsed(timer));}
        try(var files=Files.list(root)) {require(files.noneMatch(p -> p.getFileName().toString().startsWith(".allure-render-")),"Published render still contains temporary HTML copies");}
        AllureReports.verify(root);
        return output;
    }
    static FrameworkConfig config(boolean reuse,boolean strict) {
        var d=FrameworkConfig.defaults();var w=d.agent().workflow();
        var workflow=new FrameworkConfig.AiWorkflow(reuse,w.maxInputBytes(),w.maxOutputTokens(),w.outputTokenParameter(),1,0,BigDecimal.ZERO,BigDecimal.ONE,BigDecimal.TEN,null);
        var agent=new FrameworkConfig.Agent(d.agent().endpoint(),"scripted-internal",d.agent().apiKeyRef(),40,10,100000,30,workflow);
        var l=d.locators();var policy=new FrameworkConfig.Locators(l.dir(),strict?"read-only":"read-write",!strict,strict,false,l.retries(),l.retryDelayMillis(),l.timeoutSeconds(),l.minConfidence(),l.minMargin());
        var execution=new FrameworkConfig.Execution(1,0,120,10,1,false);
        return new FrameworkConfig(1,"internal-efficiency","checks",new FrameworkConfig.Org("local","auto",origin,List.of(),1,""),d.ui(),d.auth(),d.personas(),execution,agent,d.cache(),d.retention(),d.evidence(),d.report(),policy);
    }
    static Deadline deadline() {return Deadline.after(Duration.ofSeconds(120));}
    static double elapsed(long start) {return (System.nanoTime()-start)/1_000_000.0;}
    static void add(String key,double value) {measurements.computeIfAbsent(key,k -> new ArrayList<>()).add(value);}
    static Map<String,Object> stats(List<Double> data) {
        var sorted=data.stream().sorted().toList();int n=sorted.size();double median=n%2==0?(sorted.get(n/2-1)+sorted.get(n/2))/2:sorted.get(n/2);
        return Map.of("samples",n,"median",median,"min",sorted.getFirst(),"max",sorted.getLast());
    }
    static void require(boolean condition,String message) {if(!condition) throw new AssertionError(message);}
}
