package hu.ak91.sf.e2e.cli;

import hu.ak91.sf.e2e.core.*;
import hu.ak91.sf.e2e.core.locator.*;
import hu.ak91.sf.e2e.core.scenario.*;
import hu.ak91.sf.e2e.reporting.*;
import picocli.CommandLine.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.Callable;

/** Model-free datasets: owner-thread runtimes, isolated contexts and central dependency scheduling. */
public final class SuiteCommands {
    private SuiteCommands() {}
    static class Inputs {
        @Mixin SfE2eCli.Options options;
        @Option(names="--suite",required=true,description="Private JSON suite manifest. Recipe/params paths resolve relative to this file.") Path file;
        @Option(names="--dataset",description="Dataset ID filter; repeatable.") Set<String> datasets=new LinkedHashSet<>();
        @Option(names="--include-tag",description="Dataset tag filter; repeatable (OR semantics).") Set<String> tags=new LinkedHashSet<>();
        @Option(names="--coverage",description="Reviewed change-impact coverage JSON; requires --changes and excludes manual filters.") Path coverage;
        @Option(names="--changes",description="JSON {schemaVersion:1,files:[repository-relative paths]}.") Path changes;
        @Option(names="--shard",description="One-based index/count; dependency components stay together, for example 1/3.") String shard;
        @Option(names="--last-failed",description="Verified previous suite report directory; selects unsuccessful rows and prerequisite closure.") Path lastFailed;
        ChangeImpact.Plan impact;
        Map<String,String> previousInputs;
        List<Prepared> prepare(FrameworkConfig config) { return prepare(config,null); }
        List<Prepared> prepare(FrameworkConfig config,PreflightReport report) {
            Failure.require(config.profile().equals("deterministic"),"CONFIG_INVALID","Suites execute only in the deterministic profile");
            Failure.require(options.caseId==null && options.tag==null && options.testClasses.isEmpty(),"CONFIG_INVALID","Use dataset/include-tag filters for file suites");
            var suite=CaseSuite.load(file);Path parent=file.toAbsolutePath().getParent();List<Prepared> result=new ArrayList<>();
            Failure.require((coverage==null)==(changes==null),"IMPACT_INVALID","Supply both --coverage and --changes");
            List<CaseSuite.Row> selection;
            if(lastFailed!=null) {
                Failure.require(coverage==null && tags.isEmpty() && datasets.isEmpty(),"CASE_INVALID","Failure selection excludes impact and manual filters");
                SuiteReportExporter.verify(lastFailed);var receipt=Json.read(lastFailed.resolve("suite-report.json"),Map.class);
                Failure.require(suite.id().equals(receipt.get("suiteId")),"SELECTION_STALE","Failure receipt has a different suite ID");
                Map<String,String> outcomes=new LinkedHashMap<>();
                previousInputs=new HashMap<>();for(Object item:(List<?>)receipt.get("datasets")) {var row=(Map<?,?>)item;String id=row.get("datasetId").toString();outcomes.put(id,row.get("status").toString());previousInputs.put(id,Objects.toString(row.get("inputHash"),""));}
                selection=SuiteSelection.failed(suite,Objects.toString(receipt.get("suiteSourceHash"),""),outcomes);
            } else if(coverage!=null) {
                Failure.require(tags.isEmpty() && datasets.isEmpty(),"IMPACT_INVALID","Impact selection cannot be narrowed by manual filters");
                impact=ChangeImpact.evaluate(suite,ChangeImpact.read(coverage,ChangeImpact.Coverage.class),ChangeImpact.read(changes,ChangeImpact.Changes.class));
                Failure.require(!impact.selected().isEmpty(),"NO_TESTS","No changed datasets were selected; no execution or green report was created");
                Map<String,CaseSuite.Row> indexed=new HashMap<>();suite.cases().forEach(row -> indexed.put(row.id(),row));selection=impact.selected().stream().map(indexed::get).toList();
            }else selection=suite.select(tags,datasets);
            selection=SuiteSelection.shard(selection,shard);
            RuntimeException firstFailure=null;
            for(var row:selection) {
                try {
                Persona persona=Persona.valueOf(row.persona());config.persona(persona);
                var recipe=CaseRecipe.load(parent.resolve(row.recipe()).normalize());
                var paramsInput=new CaseCommands.InputOptions();paramsInput.params=row.params()==null?null:parent.resolve(row.params()).normalize();
                var params=paramsInput.parameters();String namespace=LocatorStore.namespace(config,persona);
                if(report!=null) report.design(row.id(),recipe.steps(),params);
                recipe.preflight(namespace,new LocatorStore(Path.of(config.locators().dir()),namespace,config.locators().mode()),params);
                var prepared=new Prepared(row,recipe,params,persona);
                if(lastFailed!=null) Failure.require(inputHash(prepared,config).equals(previousInputs.get(row.id())),"SELECTION_STALE","Failure selection inputs changed; review recipes/values and create a fresh selection");
                result.add(prepared);
                if(report!=null) report.check("Dataset "+row.id(),() -> {});
                } catch(RuntimeException failure) {
                    if(report==null) throw failure;
                    try { report.check("Dataset "+row.id(),() -> {throw failure;}); } catch(RuntimeException recorded) { if(firstFailure==null) firstFailure=recorded; }
                }
            }
            if(firstFailure!=null) throw firstFailure;
            return List.copyOf(result);
        }
    }
    record Prepared(CaseSuite.Row row,CaseRecipe recipe,Map<String,String> params,Persona persona) {}
    static String inputHash(Prepared p,FrameworkConfig config) {return Json.hash(Map.of("row",SuiteSelection.rowSnapshot(p.row()),"recipe",Json.hash(p.recipe()),"parametersHash",Json.hash(p.params()),"namespace",Json.hash(LocatorStore.namespace(config,p.persona()))));}
    @Command(name="validate-suite",mixinStandardHelpOptions=true,description="Preflight selected datasets, recipes, maps and parameters without login or AI.")
    public static final class Validate implements Callable<Integer> {
        @Mixin Inputs input;
        public Integer call() { var config=input.options.config("deterministic");var rows=input.prepare(config);System.out.println("Suite preflight passed: "+rows.size()+" dataset(s); no browser or model started.");return 0; }
    }
    @Command(name="run-suite",mixinStandardHelpOptions=true,description="Execute saved recipes across datasets in isolated browsers; produce one Allure report without AI.")
    public static final class Run implements Callable<Integer> {
        @Mixin Inputs input;
        @Option(names="--fail-fast",description="Stop after the first failure; remaining selected rows are reported as skipped and the suite fails.") boolean failFast;
        @FunctionalInterface interface Executor { int run(FrameworkConfig c,Prepared p,Path root); }
        Executor executor=(c,p,root) -> { hu.ak91.sf.e2e.engine.PlaywrightEngine.ensureWorkerRuntime(c);return CaseCommands.replayDataset(c,p.persona(),p.recipe(),p.params(),root,p.row().id()); };
        public Integer call() {
            var config=input.options.config("deterministic");
            return PreparedRun.execute(config,"run-suite",preparation -> {
                var rows=input.prepare(config,preparation.report);var spec=CaseSuite.load(input.file);Instant started=Instant.now();
                var tasks=rows.stream().map(p -> {Set<String> locks=new HashSet<>(p.row().resources());String ref=config.auth().mode().equals("jwt")?config.persona(p.persona()).usernameRef():config.persona(p.persona()).storageStateRef();String identity=Objects.toString(System.getenv(ref),ref);if(config.auth().mode().equals("jwt")) identity=identity.toLowerCase(Locale.ROOT);else if(System.getenv(ref)!=null) try {identity=Path.of(identity).toRealPath().toString();}catch(java.io.IOException ignored) {}locks.add("identity:"+Json.hash(config.org().baseUrl()+"|"+identity));return new DatasetScheduler.Task<>(p.row().id(),p.row().after(),locks,p);}).toList();
                DatasetScheduler.validate(tasks);
                preparation.ready();
                Path root=preparation.root;List<Map<String,Object>> completed=new ArrayList<>();int exit=0;
                if(input.impact!=null) Json.write(root.resolve("impact-plan.json"),input.impact);
                var results=DatasetScheduler.run(tasks,config.execution().workers(),failFast,p -> executeRow(config,root,p),code -> code==0,hu.ak91.sf.e2e.engine.PlaywrightEngine::closeWorkerRuntime);
                int resultIndex=0;
                for(var prepared:rows) {
                    var row=prepared.row();Path child=SafePaths.inside(root,"cases/"+row.id());
                    var execution=results.get(resultIndex++);
                    if(execution.status().equals("SKIPPED")) placeholder(root,child,prepared,"SKIPPED","NOT_RUN_DEPENDENCY_OR_FAIL_FAST");
                    else if(execution.error()!=null) { String code=execution.error() instanceof Failure f?f.code():"ENGINE_FAILURE";if(!Files.isRegularFile(child.resolve("case-report.json"))) placeholder(root,child,prepared,"BLOCKED",code);exit=Math.max(exit,code.equals("CANCELLED")?130:2); }
                    else exit=Math.max(exit,execution.value());
                    var state=Json.read(child.resolve("case-report.json"),Map.class);
                    if(!"PASSED".equals(state.get("status"))) exit=Math.max(exit,1);
                    completed.add(Map.of("datasetId",row.id(),"caseId",prepared.recipe().id(),"directory","cases/"+row.id(),"status",state.get("status"),"checksum",Json.hash(state),"tags",row.tags(),"inputHash",inputHash(prepared,config)));
                }
                Map<String,Object> suite=new LinkedHashMap<>();suite.put("schemaVersion",1);suite.put("suiteId",spec.id());suite.put("runId",root.getFileName().toString());
                suite.put("startedAt",started);suite.put("completedAt",Instant.now());suite.put("selected",rows.size());suite.put("complete",true);suite.put("status",exit==0?"PASSED":"FAILED");
                suite.put("exitCode",exit);suite.put("modelCalls",0);suite.put("datasets",completed);
                suite.put("suiteSourceHash",SuiteSelection.sourceHash(spec));if(input.shard!=null) suite.put("shard",input.shard);
                if(input.lastFailed!=null) suite.put("selectionSource","last-failed");
                suite.put("trendContext",Json.hash(Map.of("workers",config.execution().workers(),"rows",rows.stream().map(p -> Map.of("row",p.row(),"recipe",Json.hash(p.recipe()),"parametersHash",Json.hash(p.params()),"namespace",Json.hash(LocatorStore.namespace(config,p.persona())))).toList())));
                SuiteReportExporter.write(root,suite);
                System.out.println("Suite "+spec.id()+": "+suite.get("status")+"; datasets="+rows.size()+"; model calls=0");System.out.println("Allure report: "+root.resolve("index.html"));return exit;
            });
        }
        private int executeRow(FrameworkConfig config,Path root,Prepared prepared) {
                Path child=SafePaths.inside(root,"cases/"+prepared.row().id());int exit=0;
                try { exit=executor.run(config,prepared,child); }
                catch(RuntimeException error) {
                    String code=error instanceof Failure f?f.code():"ENGINE_FAILURE";
                    if(!Files.isRegularFile(child.resolve("case-report.json"))) placeholder(root,child,prepared,"BLOCKED",code);
                    else {
                        var saved=Json.read(child.resolve("case-report.json"),Map.class);
                        if("PASSED".equals(saved.get("status"))) {
                            saved.put("businessStatus","PASSED");saved.put("status","BLOCKED");saved.put("code",code);saved.put("diagnostic","Dataset evidence/report finalization failed after its UI work");
                            @SuppressWarnings("unchecked") var events=(List<Map<String,Object>>)saved.getOrDefault("events",List.of());WorkflowReportExporter.write(child,saved,events,false);
                        }
                    }
                    int failureExit=switch(code) {
                        case "ASSERTION_FAILED","API_ASSERTION_FAILED","FILE_VERIFICATION_FAILED","UI_ASSERTION_FAILED","VALUE_MISMATCH","APPLICATION_DEFECT","QUALITY_GATE_FAILED" -> 1;
                        case "ENVIRONMENT_UNAVAILABLE" -> 3;
                        case "PNG_INVALID","EVIDENCE_INCOMPLETE","REPORT_RUN_MISMATCH","RETENTION_VIOLATION","JOURNAL_WRITE_FAILED","REPORT_GENERATION_FAILED" -> 4;
                        case "CANCELLED" -> 130;default -> 2;
                    };
                    exit=Math.max(exit,failureExit);
                }
                var state=Json.read(child.resolve("case-report.json"),Map.class);
                if(!"PASSED".equals(state.get("status"))) exit=Math.max(exit,1);if(exit==130) throw new Failure("CANCELLED","Dataset cancellation stops queued work");return exit;
        }
        private static void placeholder(Path root,Path child,Prepared prepared,String status,String code) {
            Map<String,Object> state=new LinkedHashMap<>();state.put("schemaVersion",1);state.put("runId",root.getFileName()+"."+prepared.row().id());state.put("attemptId",UUID.randomUUID().toString());
            state.put("caseId",prepared.recipe().id());state.put("datasetId",prepared.row().id());state.put("persona",prepared.persona().name());state.put("mode","replay");
            state.put("startedAt",Instant.now());state.put("completedAt",Instant.now());state.put("status",status);state.put("code",code);state.put("modelCalls",0);state.put("artifacts",List.of());
            state.put("diagnostic","Dataset was not executed successfully; inspect the suite status and error code");
            WorkflowReportExporter.write(child,state,List.of(Map.of("kind","case-replay-plan","steps",prepared.recipe().steps())),false);
        }
    }
}
