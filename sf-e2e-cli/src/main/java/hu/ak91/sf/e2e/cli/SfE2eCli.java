package hu.ak91.sf.e2e.cli;

import hu.ak91.sf.e2e.core.*;
import hu.ak91.sf.e2e.core.locator.*;
import hu.ak91.sf.e2e.engine.PlaywrightEngine;
import hu.ak91.sf.e2e.agent.*;
import hu.ak91.sf.e2e.replay.*;
import hu.ak91.sf.e2e.junit.*;
import hu.ak91.sf.e2e.reporting.*;
import hu.ak91.sf.e2e.salesforce.auth.*;
import hu.ak91.sf.e2e.salesforce.fixture.*;
import picocli.CommandLine;
import picocli.CommandLine.*;
import java.nio.file.*;
import java.util.*;
import java.time.*;
import java.util.concurrent.Callable;

@Command(name="sf-e2e",mixinStandardHelpOptions=true,version="1.0.0-SNAPSHOT",
    description="Java Salesforce UI mapping and self-healing framework.",subcommands={ImpactCommands.class,ComponentCommands.class,ReleaseCommands.class,SuiteCommands.Run.class,SuiteCommands.Validate.class,CaseCommands.Learn.class,CaseCommands.Discover.class,CaseCommands.Replay.class,SfE2eCli.MapUi.class,SfE2eCli.InspectUi.class,SfE2eCli.VerifyUi.class,SfE2eCli.Locators.class,SfE2eCli.ListCases.class,SfE2eCli.Run.class,SfE2eCli.Doctor.class,SfE2eCli.Cache.class,SfE2eCli.Report.class,SfE2eCli.Data.class})
public final class SfE2eCli implements Runnable {
    @Spec CommandLine.Model.CommandSpec spec;
    public void run() { spec.commandLine().usage(System.out); }
    public static void main(String[] args) { System.exit(commandLine().execute(args)); }
    public static CommandLine commandLine() {
        return new CommandLine(new SfE2eCli()).registerConverter(Persona.class,Persona::valueOf).setExecutionExceptionHandler((error,cmd,parse) -> {
            String code=error instanceof Failure failure?failure.code():"CONFIG_INVALID";
            cmd.getErr().println(code+": "+(error instanceof Failure?error.getMessage():"Command failed ("+error.getClass().getSimpleName()+")"));
            if(Set.of("PNG_INVALID","EVIDENCE_INCOMPLETE","REPORT_RUN_MISMATCH","RETENTION_VIOLATION","JOURNAL_WRITE_FAILED","REPORT_GENERATION_FAILED").contains(code)) return 4;
            if(code.equals("ENVIRONMENT_UNAVAILABLE")) return 3;
            if(Set.of("ASSERTION_FAILED","API_ASSERTION_FAILED","FILE_VERIFICATION_FAILED","APPLICATION_DEFECT","QUALITY_GATE_FAILED").contains(code)) return 1;
            if(code.equals("CANCELLED")) return 130;
            return 2;
        });
    }
    static boolean validRoute(String route) { FrameworkConfig.validateRoute(route);return true; }
    public static class Options {
        @Option(names="--config",description="Project YAML configuration.",defaultValue="sf-e2e.yaml") Path file;
        @Option(names="--profile",description="deterministic, mapping, checks; legacy selection aliases supported") String profile;
        @Option(names="--test-class",description="User case class on the Java classpath; repeatable.") List<String> testClasses=new ArrayList<>();
        @Option(names="--case",description="One exact stable case ID.") String caseId;
        @Option(names="--tag",description="Select a tag.") String tag;
        @Option(names="--headed",description="Show the configured browser.") boolean headed;
        @Option(names="--no-cache") boolean noCache;
        @Option(names="--strict-cache") boolean strict;
        @Option(names="--strict-locators",description="Fail on changed locators, without healing or model calls.") boolean strictLocators;
        @Option(names="--locator-mode",description="read-write or read-only") String locatorMode;
        FrameworkConfig config(String fallback) {
            Map<String,String> overrides=new HashMap<>();overrides.put("profile",profile==null?fallback:profile);
            if("ai-cache".equals(profile)) overrides.put("cache.mode",System.getenv("CI")==null?"read-write":"read-only");
            if(headed) overrides.put("execution.headed","true");
            if(noCache) overrides.put("cache.mode","off");
            if(strict) overrides.put("cache.strict","true");
            if(strictLocators) overrides.put("locators.strict","true");
            if(locatorMode!=null) overrides.put("locators.mode",locatorMode);
            return FrameworkConfig.load(file,overrides);
        }
        List<SelectionPlanner.Discovered> plan(FrameworkConfig config) {
            List<Class<?>> classes=new ArrayList<>();
            for(String name:testClasses) try { classes.add(Class.forName(name,false,Thread.currentThread().getContextClassLoader())); }
            catch(ClassNotFoundException e) { throw new Failure("CONFIG_INVALID","User test class is not on the classpath: "+name); }
            return SelectionPlanner.plan(SelectionPlanner.discover(classes),config.profile(),caseId,tag);
        }
    }
    @Command(name="list",mixinStandardHelpOptions=true,description="List selected cases and deduplicated setups without credentials.")
    public static final class ListCases implements Callable<Integer> {
        @Mixin Options options;@Option(names="--json") boolean json;
        public Integer call() {
            var config=options.config("deterministic");var plan=options.plan(config);
            if(json) System.out.println(Json.string(plan.stream().map(SelectionPlanner.Discovered::info).toList()));
            else { for(var c:plan) System.out.printf("%s | %s | %s | dependencies=%s%n",c.info().id(),c.info().persona(),c.info().name(),c.info().dependencies());System.out.println("Selected: "+plan.size()); }
            return 0;
        }
    }
    @Command(name="run",mixinStandardHelpOptions=true,description="Execute explicitly supplied user JUnit cases; no bundled business tests.")
    public static final class Run implements Callable<Integer> {
        @Mixin Options options;
        public Integer call() {
            var config=options.config("deterministic");var report=RunCoordinator.run(config,options.plan(config));
            System.out.println("Run "+report.runId()+": "+report.counts()+"; exit "+report.exitCode());
            System.out.println("Report: "+Path.of(config.report().root(),report.runId(),"index.html").toAbsolutePath());
            return report.exitCode();
        }
    }
    @Command(name="doctor",mixinStandardHelpOptions=true,description="Validate runtime/configuration and required live secret references.")
    public static final class Doctor implements Callable<Integer> {
        @Mixin Options options;
        public Integer call() {
            var config=options.config("checks");
            Failure.require(Runtime.version().feature()>=21,"CONFIG_INVALID","Java 21 or newer is required");
            System.out.println("Java "+Runtime.version()+"; Maven Wrapper 3.10.0; "+config.ui().browser()+" engine 1.63.0; shadow policy "+config.ui().shadowPolicy());
            if(!config.profile().equals("checks")) {
                var plan=options.plan(config);Set<Persona> personas=new HashSet<>();plan.forEach(c -> personas.add(c.info().persona()));
                if(personas.isEmpty()) personas.add(Persona.DEFAULT);
                var secrets=new SecretRegistry();new JwtAuthenticator(config,secrets).preflight(personas);
                if(config.profile().equals("mapping") || plan.stream().anyMatch(c -> c.info().tags().contains("ai"))) new JsonModelClient(config.agent(),secrets);
                System.out.println("Live configuration and required secret references valid. No login or mutation performed.");
            } else System.out.println("Credential-free checks configuration valid.");
            System.out.println("Permanent journals: "+Path.of(config.retention().journalDir()).toAbsolutePath());return 0;
        }
    }
    public record MappingPage(String page,String route,List<String> scopes,SemanticTarget ready) {}
    @Command(name="map-ui",mixinStandardHelpOptions=true,description="Read-only AI mapping of explicit application routes/scopes; creates reusable locators, no test cases.")
    public static final class MapUi implements Callable<Integer> {
        @Mixin Options options;
        @Option(names="--page",description="Stable logical page key, e.g. workspace") String page;
        @Option(names="--route",description="application route to observe.") String route;
        @Option(names="--scope",description="Observation scope; repeatable. Default: page.") List<String> scopes;
        @Option(names="--ready-name",description="Exact accessible name to await before mapping.") String readyName;
        @Option(names="--ready-role",defaultValue="heading",description="Role for --ready-name (default: heading).") String readyRole;
        @Option(names="--plan",description="JSON array of {page,route,scopes} for multiple pages.") Path plan;
        @Option(names="--persona",defaultValue="default") Persona persona;
        public Integer call() {
            var config=options.config("mapping");
            Failure.require(config.profile().equals("mapping"),"CONFIG_INVALID","map-ui requires the mapping profile");
            Failure.require(config.locators().mode().equals("read-write"),"LOCATOR_READ_ONLY","Use --locator-mode read-write for UI mapping");
            List<MappingPage> pages;
            if(plan!=null) {
                Failure.require(page==null && route==null && scopes==null && readyName==null,"CONFIG_INVALID","Choose --plan or single-page mapping options");
                pages=List.of(Json.read(plan,MappingPage[].class));
            } else {
                Failure.require(page!=null && route!=null,"CONFIG_INVALID","Supply --page and --route, or --plan");
                pages=List.of(new MappingPage(page,route,scopes,readyName==null?null:SemanticTarget.role(readyRole,readyName,"page")));
            }
            Failure.require(!pages.isEmpty() && pages.size()<=50,"CONFIG_INVALID","Mapping plan must contain 1..50 explicit pages");
            Set<String> pageNames=new HashSet<>();
            for(var item:pages) Failure.require(item.page()!=null && item.page().matches("[a-zA-Z0-9_.-]{1,100}") && pageNames.add(item.page()) &&
                item.route()!=null && validRoute(item.route()) && (item.scopes()==null || !item.scopes().isEmpty() && item.scopes().size()<=10),
                "CONFIG_INVALID","Each mapping page needs a unique stable name, application route and 1..10 scopes");
            Deadline deadline=Deadline.after(Duration.ofSeconds(config.execution().testTimeoutSeconds()));
            var secrets=new SecretRegistry();var auth=new JwtAuthenticator(config,secrets);auth.preflight(Set.of(persona));
            var store=new LocatorStore(Path.of(config.locators().dir()),LocatorStore.namespace(config,persona),config.locators().mode());
            List<Map<String,Object>> events=new ArrayList<>();String runId=UUID.randomUUID().toString();
            var model=AiWorkflowClient.create(config.agent(),secrets,events::add);
            Path reportRoot=ReportDirectories.create(Path.of(config.report().root()),Instant.now());
            String phase="browser-launch";
            try(var engine=new PlaywrightEngine(config,secrets,deadline,persona)) {
                phase="authentication";
                engine.bootstrap(auth.frontdoor(persona,deadline),deadline);
                System.out.println("Salesforce authentication completed.");
                var mapper=new UiMapper(engine,store,model,config.agent(),secrets,event -> { events.add(event);System.out.println(Json.string(event)); });
                for(var item:pages) {
                    phase="opening:"+item.page();
                    engine.open(item.route(),deadline);
                    phase="readiness:"+item.page();
                    if(item.ready()!=null) awaitReady(engine,item.ready(),deadline.child(Duration.ofSeconds(config.locators().timeoutSeconds())));
                    for(String scope:item.scopes()==null?List.of("page"):item.scopes()) {
                        awaitStableScope(engine,scope,deadline.child(Duration.ofSeconds(config.locators().timeoutSeconds())));
                        phase="mapping:"+item.page()+":"+scope;
                        mapper.map(item.page(),scope,deadline.child(Duration.ofSeconds(config.agent().actTimeoutSeconds())));
                    }
                }
                Json.write(reportRoot.resolve("mapping-report.json"),Map.of("schemaVersion",1,"runId",runId,"status","PASSED","events",events,"repository",store.path().toString()));
            } catch(RuntimeException e) {
                Json.write(reportRoot.resolve("mapping-report.json"),Map.of("schemaVersion",1,"runId",runId,"status","FAILED","events",events,
                    "code",e instanceof Failure f?f.code():"ENGINE_FAILURE","phase",phase,"exceptionType",e.getClass().getSimpleName(),
                    "diagnostic",secrets.redact(Objects.toString(e.getMessage(),"No diagnostic"))));throw e;
            }
            System.out.println("Locator repository: "+store.path());System.out.println("Mapping report: "+reportRoot.resolve("mapping-report.json"));return 0;
        }
        static void awaitReady(UiEngine engine,SemanticTarget target,Deadline deadline) {
            while(true) {
                deadline.check();
                try {
                    var observation=engine.observe(Objects.toString(target.container(),"page"),deadline);
                    if(observation.complete()) { engine.resolve(target,observation);return; }
                } catch(Failure e) { if(!Set.of("TARGET_NOT_FOUND","NODE_STALE","AUTOMATION_UNSUPPORTED").contains(e.code())) throw e; }
                engine.poll(deadline);
            }
        }
        static void awaitStableScope(UiEngine engine,String scope,Deadline deadline) {
            String previous=null;int stable=0;
            while(true) {
                deadline.check();
                try {
                    var observation=engine.observe(scope,deadline);
                    if(observation.complete() && !observation.nodes().isEmpty() && observation.nodes().stream().noneMatch(n -> "true".equals(n.attributes().get("busy")))) {
                        String current=Json.hash(observation.nodes().stream().map(n -> Map.of("target",n.target(),"attributes",n.attributes())).toList());
                        stable=current.equals(previous)?stable+1:0;previous=current;
                        if(stable>=5) return;
                    } else { stable=0;previous=null; }
                } catch(Failure e) {
                    if(!Set.of("TARGET_NOT_FOUND","NODE_STALE","AUTOMATION_UNSUPPORTED").contains(e.code())) throw e;
                    stable=0;previous=null;
                }
                engine.poll(deadline);
            }
        }
    }
    @Command(name="inspect-ui",mixinStandardHelpOptions=true,description="Read-only redacted UI diagnostics, without AI or test cases.")
    public static final class InspectUi implements Callable<Integer> {
        @Mixin Options options;
        @Option(names="--route",required=true) String route;
        @Option(names="--scope",defaultValue="page") String scope;
        @Option(names="--persona",defaultValue="default") Persona persona;
        public Integer call() {
            var config=options.config("deterministic");
            Failure.require(config.profile().equals("deterministic") && validRoute(route),"CONFIG_INVALID","inspect-ui requires a deterministic application route");
            var deadline=Deadline.after(Duration.ofSeconds(config.execution().testTimeoutSeconds()));
            var secrets=new SecretRegistry();var auth=new JwtAuthenticator(config,secrets);auth.preflight(Set.of(persona));
            Path reportRoot=ReportDirectories.create(Path.of(config.report().root()),Instant.now());
            try(var engine=new PlaywrightEngine(config,secrets,deadline,persona)) {
                engine.bootstrap(auth.frontdoor(persona,deadline),deadline);engine.open(route,deadline);
                String readiness="STABLE";
                try { MapUi.awaitStableScope(engine,scope,deadline.child(Duration.ofSeconds(config.locators().timeoutSeconds()))); }
                catch(Failure e) { readiness=e.code(); }
                var observation=engine.observe(scope,deadline);
                var projection=new Observation(observation.revision(),observation.capturedAt(),LocatorEntry.routeKey(observation.route()),
                    observation.nodes().stream().map(n -> new Observation.Node(n.id(),n.target(),"","",n.disabled(),n.checked(),n.attributes())).toList(),
                    observation.truncated(),observation.coverage(),observation.redactionStatus(),observation.degradationReason());
                Json.write(reportRoot.resolve("inspection-report.json"),Map.of("schemaVersion",1,"modelCalls",0,"readiness",readiness,"observation",projection));
                System.out.println("UI coverage="+observation.coverage()+"; truncated="+observation.truncated()+"; nodes="+observation.nodes().size()+"; readiness="+readiness);
                System.out.println("Visible headings: "+Json.string(observation.nodes().stream().filter(n -> "heading".equals(n.target().role())).map(n -> n.target().name()).toList()));
                if(observation.complete()) Files.write(reportRoot.resolve("screen.png"),engine.captureMaskedPng(deadline));
                System.out.println("Inspection report: "+reportRoot.resolve("inspection-report.json"));
                return observation.complete()?0:2;
            } catch(java.io.IOException e) { throw new Failure("EVIDENCE_INCOMPLETE","Cannot save inspection evidence"); }
        }
    }
    @Command(name="verify-ui",mixinStandardHelpOptions=true,description="Read-only live validation of mapped locator keys. No model, test cases or business mutations.")
    public static final class VerifyUi implements Callable<Integer> {
        @Mixin Options options;
        @Option(names="--plan",required=true,description="The JSON mapping plan to revisit.") Path plan;
        @Option(names="--persona",defaultValue="default") Persona persona;
        @Option(names="--key",description="Optional stored locator key filter, repeatable.") List<String> keys;
        public Integer call() {
            var config=options.config("deterministic");
            Failure.require(config.profile().equals("deterministic"),"CONFIG_INVALID","verify-ui requires the deterministic profile");
            var pages=List.of(Json.read(plan,MappingPage[].class));
            Failure.require(!pages.isEmpty() && pages.size()<=50,"CONFIG_INVALID","Validation plan needs 1..50 pages");
            var store=new LocatorStore(Path.of(config.locators().dir()),LocatorStore.namespace(config,persona),config.locators().mode());
            var entries=store.entries();
            Failure.require(!entries.isEmpty(),"LOCATOR_NOT_MAPPED","Map this org/persona namespace first");
            for(var page:pages) Failure.require(page.page()!=null && page.route()!=null && validRoute(page.route()),"CONFIG_INVALID","Invalid validation page");
            Deadline deadline=Deadline.after(Duration.ofSeconds(config.execution().testTimeoutSeconds()));
            var secrets=new SecretRegistry();var auth=new JwtAuthenticator(config,secrets);auth.preflight(Set.of(persona));
            List<Map<String,Object>> events=new ArrayList<>(),results=new ArrayList<>();
            Path reportRoot=ReportDirectories.create(Path.of(config.report().root()),Instant.now());
            int failed=0;
            try(var engine=new PlaywrightEngine(config,secrets,deadline,persona)) {
                engine.bootstrap(auth.frontdoor(persona,deadline),deadline);
                var ui=new HealingLocators(engine,store,config.locators(),deadline,events::add);
                for(var page:pages) {
                    var selected=entries.values().stream().filter(e -> e.page().equals(page.page()) && (keys==null || keys.contains(e.key())) &&
                        (page.scopes()==null || page.scopes().contains(e.primary().container()))).sorted(Comparator.comparing(LocatorEntry::key)).toList();
                    Failure.require(!selected.isEmpty(),"LOCATOR_NOT_MAPPED","No mapped keys for page "+page.page());
                    engine.open(page.route(),deadline);
                    if(page.ready()!=null) MapUi.awaitReady(engine,page.ready(),deadline.child(Duration.ofSeconds(config.locators().timeoutSeconds())));
                    for(String scope:selected.stream().map(e -> e.primary().container()).distinct().toList())
                        MapUi.awaitStableScope(engine,scope,deadline.child(Duration.ofSeconds(config.locators().timeoutSeconds())));
                    for(var entry:selected) {
                        Map<String,Object> result;
                        try {
                            if(entry.parameterized()) {
                                var current=engine.observe(entry.primary().container(),deadline);
                                var records=current.nodes().stream().filter(n -> "true".equals(n.attributes().get("recordLink")) && n.target().name()!=null &&
                                    !Json.string(n.target()).contains("[REDACTED]") && current.nodes().stream().filter(other -> LocatorEntry.matches(n.target(),other.target())).count()==1).toList();
                                if(records.isEmpty()) { result=Map.of("page",entry.page(),"key",entry.key(),"status","SKIPPED","reason","No uniquely named visible record to bind");results.add(result);System.out.println(Json.string(result));continue; }
                                ui.read(entry.page(),entry.key(),Map.of("recordName",records.getFirst().target().name()));
                            } else ui.read(entry.page(),entry.key());
                            result=Map.of("page",entry.page(),"key",entry.key(),"status","PASSED");
                        }
                        catch(Failure e) { failed++;result=Map.of("page",entry.page(),"key",entry.key(),"status","FAILED","code",e.code()); }
                        results.add(result);System.out.println(Json.string(result));
                    }
                    try { Files.createDirectories(reportRoot);Files.write(reportRoot.resolve(page.page()+".png"),engine.captureMaskedPng(deadline)); }
                    catch(java.io.IOException e) { throw new Failure("EVIDENCE_INCOMPLETE","Cannot save validation screenshot"); }
                }
                Json.write(reportRoot.resolve("validation-report.json"),Map.of("schemaVersion",1,"status",failed==0?"PASSED":"FAILED","modelCalls",0,"results",results,"events",events));
            } catch(RuntimeException e) {
                Json.write(reportRoot.resolve("validation-report.json"),Map.of("schemaVersion",1,"status","FAILED","modelCalls",0,"results",results,"events",events,
                    "code",e instanceof Failure f?f.code():"ENGINE_FAILURE"));throw e;
            }
            System.out.println("Validated "+results.size()+" locators; failed="+failed+"; model calls=0.");
            System.out.println("Validation report: "+reportRoot.resolve("validation-report.json"));return failed==0?0:1;
        }
    }
    @Command(name="locators",mixinStandardHelpOptions=true,subcommands={LocatorList.class,LocatorHistory.class})
    public static final class Locators implements Runnable { public void run() { System.out.println("Use locators list or history."); } }
    public static class LocatorOptions {
        @Option(names="--config",defaultValue="sf-e2e.yaml") Path file;
        @Option(names="--persona",defaultValue="default") Persona persona;
        LocatorStore store() { var config=FrameworkConfig.load(file,Map.of());return new LocatorStore(Path.of(config.locators().dir()),LocatorStore.namespace(config,persona),"read-only"); }
    }
    @Command(name="list",mixinStandardHelpOptions=true,description="Inspect mapped locators without model credentials.")
    public static final class LocatorList implements Callable<Integer> {
        @Mixin LocatorOptions options;
        public Integer call() { System.out.println(Json.string(new TreeMap<>(options.store().entries())));return 0; }
    }
    @Command(name="history",mixinStandardHelpOptions=true)
    public static final class LocatorHistory implements Callable<Integer> {
        @Mixin LocatorOptions options;
        @Option(names="--page",required=true) String page;
        @Option(names="--key",required=true) String key;
        public Integer call() { System.out.println(Json.string(options.store().get(page,key).history()));return 0; }
    }
    @Command(name="cache",mixinStandardHelpOptions=true,subcommands={CacheList.class,CacheStats.class,CacheClear.class})
    public static final class Cache implements Runnable { public void run() { System.out.println("Use cache list, stats, or clear."); } }
    public static class CacheOptions {
        @Option(names="--config",defaultValue="sf-e2e.yaml") Path file;
        CacheStore store() { return new CacheStore(Path.of(FrameworkConfig.load(file,Map.of()).cache().dir())); }
    }
    @Command(name="list",mixinStandardHelpOptions=true)
    public static final class CacheList implements Callable<Integer> {
        @Mixin CacheOptions options;public Integer call() { options.store().list().forEach(System.out::println);return 0; }
    }
    @Command(name="stats",mixinStandardHelpOptions=true)
    public static final class CacheStats implements Callable<Integer> {
        @Mixin CacheOptions options;public Integer call() { System.out.println("Entries: "+options.store().list().size());return 0; }
    }
    @Command(name="clear",mixinStandardHelpOptions=true,description="Clear only local replay cache entries; journals are preserved.")
    public static final class CacheClear implements Callable<Integer> {
        @Mixin CacheOptions options;public Integer call() { options.store().clear();System.out.println("Replay cache cleared.");return 0; }
    }
    @Command(name="report",mixinStandardHelpOptions=true,subcommands={ReportVerify.class,ReportBuild.class,TrendCommands.class})
    public static final class Report implements Runnable { public void run() { System.out.println("Use report verify or build --run <directory>."); } }
    public static class ReportOptions {
        @Option(names="--run",required=true,description="Exact completed run directory.") Path root;
        RunReport read() { return Json.read(root.resolve("run-report.json"),RunReport.class); }
        void verify(RunReport report) { ReportIntegrityVerifier.verify(root,report,2); }
    }
    @Command(name="verify",mixinStandardHelpOptions=true)
    public static final class ReportVerify implements Callable<Integer> {
        @Mixin ReportOptions options;public Integer call() {
            if(Files.isRegularFile(options.root.resolve("release-report.json"))) { ExternalLayers.verify(options.root);System.out.println("Release layer and Allure integrity verified."); }
            else if(Files.isRegularFile(options.root.resolve("suite-report.json"))) { SuiteReportExporter.verify(options.root);System.out.println("Suite and Allure integrity verified."); }
            else if(Files.isRegularFile(options.root.resolve("case-report.json"))) { WorkflowReportExporter.verify(options.root);System.out.println("Workflow and Allure integrity verified."); }
            else { var report=options.read();options.verify(report);AllureReports.verify(options.root);System.out.println("Integrity verified: "+report.runId()+" ("+report.results().size()+" results)"); }
            return 0;
        }
    }
    @Command(name="build",mixinStandardHelpOptions=true,description="Rebuild HTML/JSON/XML/Markdown/Allure results from one verified run.")
    public static final class ReportBuild implements Callable<Integer> {
        @Mixin ReportOptions options;public Integer call() {
            if(Files.isRegularFile(options.root.resolve("release-report.json"))) ExternalLayers.publish(options.root,ExternalLayers.read(options.root));
            else if(Files.isRegularFile(options.root.resolve("suite-report.json"))) SuiteReportExporter.rebuild(options.root);
            else if(Files.isRegularFile(options.root.resolve("case-report.json"))) WorkflowReportExporter.rebuild(options.root);
            else { var report=options.read();options.verify(report);ReportExporter.write(options.root,report); }
            System.out.println(options.root.resolve("index.html").toAbsolutePath());return 0;
        }
    }
    @Command(name="data",mixinStandardHelpOptions=true,subcommands=DataInspect.class)
    public static final class Data implements Runnable { public void run() { System.out.println("Use data inspect --attempt <journal.json>."); } }
    @Command(name="inspect",mixinStandardHelpOptions=true,description="Read a permanent journal. No Salesforce mutation.")
    public static final class DataInspect implements Callable<Integer> {
        @Option(names="--attempt",required=true) Path file;
        public Integer call() { System.out.println(Json.string(Json.read(file,RecordRegistry.Manifest.class)));return 0; }
    }
}
