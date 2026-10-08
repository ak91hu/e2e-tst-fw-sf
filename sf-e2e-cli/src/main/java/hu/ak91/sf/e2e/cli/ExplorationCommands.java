package hu.ak91.sf.e2e.cli;
import hu.ak91.sf.e2e.core.*;
import hu.ak91.sf.e2e.core.scenario.*;
import hu.ak91.sf.e2e.agent.*;
import picocli.CommandLine.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;

@Command(name="explore-cases",mixinStandardHelpOptions=true,description="Discover business workflows from UI and caller-owned charters; optionally learn them with fresh isolated sessions.")
public final class ExplorationCommands implements Callable<Integer> {
    @Mixin CaseCommands.InputOptions input;
    @Option(names="--charter",required=true) Path charterFile;
    @Option(names="--output-dir",required=true,description="New private bundle directory; existing bundles are preserved.") Path directory;
    @Option(names="--execute",description="Execute the generated workflows, including charter-authorized mutations. Stops on first failure; never automatically replays a mutation.") boolean execute;
    @FunctionalInterface interface SessionRunner {int run(FrameworkConfig config,Persona persona,String id,String route,Map<String,String> values,String mode,Path output,CaseCommands.Work work);}
    @FunctionalInterface interface Learner {int learn(CaseCommands.Learn command);}
    SessionRunner sessions=CaseCommands::session;
    Learner learner=CaseCommands.Learn::call;
    public Integer call() throws Exception {
        var charter=Json.MAPPER.treeToValue(CaseCommands.readJson(charterFile),ExplorationCharter.class);
        var values=input.parameters();for(var oracle:charter.oracles()) for(String ref:new String[]{oracle.valueRef(),oracle.recordNameRef()})
            Failure.require(ref==null || values.containsKey(ref),"CASE_PARAMETER_REQUIRED","Charter oracle needs a supplied runtime expectation");
        var config=input.options.config("mapping");Failure.require(config.profile().equals("mapping") && config.locators().mode().equals("read-write"),"CONFIG_INVALID","Exploration requires mapping and writable locators");
        directory=directory.toAbsolutePath().normalize();Failure.require(!Files.exists(directory),"CASE_RECIPE_EXISTS","Exploration output directory already exists");
        Files.createDirectories(directory.getParent());Files.createDirectory(directory);
        String owner=UUID.randomUUID().toString();PrivateArtifacts.create(directory,owner,168);Json.write(directory.resolve("charter.local.json"),charter);
        Path valuesFile=directory.resolve("parameters.local.json");Json.write(valuesFile,values);
        List<CaseSpec> plans=new ArrayList<>();List<Map<String,Object>> outcomes=new ArrayList<>();
        int exit;try {exit=sessions.run(config,input.persona,charter.id(),charter.routes().getFirst(),values,"exploration",directory.resolve(charter.id()+"_surface.json"),
            (engine,store,model,namespace,deadline,secrets,events) -> {
                plans.addAll(new CaseExplorer(engine,model,config.agent(),secrets).plan(charter,values.keySet(),deadline));
                for(var plan:plans) {CaseCommands.validateSpecParams(plan,values);Json.write(directory.resolve(plan.id()+".spec.local.json"),plan);}
                Json.write(directory.resolve("exploration.json"),Map.of("schemaVersion",1,"charterHash",charter.fingerprint(),"uiVerified",false,"cases",plans.stream().map(CaseSpec::id).toList(),"executeRequested",execute));
                return new UiExplorer(engine,store,model,config,secrets,events).discover("TST_surface_"+charter.fingerprint().substring(0,12),charter.routes().getFirst(),namespace,1,deadline);
            });}catch(RuntimeException failure) {
                Json.write(directory.resolve("findings.json"),Map.of("schemaVersion",1,"charterHash",charter.fingerprint(),"complete",false,"phase","planning-or-surface-verification","code",failure instanceof Failure f?f.code():"ENGINE_FAILURE","uiVerified",false,"cases",plans.stream().map(CaseSpec::id).toList()));
                manifest(directory,owner);throw failure;
            }
        boolean stopped=false;List<CaseSuite.Row> verified=new ArrayList<>();
        for(var plan:plans) {
            String status="PLANNED",code="";if(execute && !stopped) {
                var learn=new CaseCommands.Learn();learn.charter=charter;learn.input=input;learn.input.params=valuesFile;learn.spec=directory.resolve(plan.id()+".spec.local.json");learn.output=directory.resolve(plan.id()+".recipe.local.json");
                try {int result=learner.learn(learn);Failure.require(result==0,"ENGINE_FAILURE","Learning returned an unsuccessful result");status="PASSED";verified.add(new CaseSuite.Row(plan.id(),learn.output.getFileName().toString(),valuesFile.getFileName().toString(),input.persona.name(),List.of("explored")));}
                catch(RuntimeException failure) {code=failure instanceof Failure f?f.code():"ENGINE_FAILURE";status="FAILED";stopped=true;exit=Math.max(exit,failureExit(code));}
            } else if(execute) status="SKIPPED";
            outcomes.add(Map.of("caseId",plan.id(),"status",status,"uiVerified",status.equals("PASSED"),"code",code,"spec",plan.id()+".spec.local.json","classification",
                Set.of("ASSERTION_FAILED","UI_ASSERTION_FAILED","VALUE_MISMATCH","APPLICATION_DEFECT","QUALITY_GATE_FAILED").contains(code)?"application-outcome":"framework-or-unverified"));
        }
        if(!verified.isEmpty()) Json.write(directory.resolve("verified-suite.local.json"),new CaseSuite(1,charter.id(),verified));
        Json.write(directory.resolve("findings.json"),Map.of("schemaVersion",1,"charterHash",charter.fingerprint(),"complete",!stopped,"applicationCoverageComplete",false,"exitCode",exit,"outcomes",outcomes,"parameterFile",valuesFile.getFileName().toString()));
        Files.writeString(directory.resolve("REPRODUCTION.md"),"# Private workflow reproduction\n\nInspect findings.json and the retained specs before execution. A planned or failed spec is not a verified recipe.\n\nFor a failed case, invoke `learn-case --config <consumer-config> --charter charter.local.json --spec <case-id>.spec.local.json --params parameters.local.json --output <new-output>.recipe.local.json --locator-mode read-write` from this directory with the matching CLI JAR and authorized identity. Use a new output and repeatable consumer data; never automatically rerun an uncertain mutation.\n\nSuccessful cases appear in verified-suite.local.json. Replay this suite with the deterministic profile; keep expected outcomes unchanged. Inspect the separately generated case report and failure code before calling a finding an application defect.\n\nThe bundle is confidential raw material, not an Allure attachment. See guide autonomous for retention and replay limits.\n");
        manifest(directory,owner);
        System.out.println("Exploration bundle: "+directory+"; plans="+plans.size()+"; verified="+verified.size()+"; exit="+exit);return exit;
    }
    private static int failureExit(String code) {return switch(code) {
        case "CANCELLED" -> 130;
        case "ASSERTION_FAILED","UI_ASSERTION_FAILED","VALUE_MISMATCH","APPLICATION_DEFECT","QUALITY_GATE_FAILED" -> 1;
        case "ENVIRONMENT_UNAVAILABLE" -> 3;
        case "EVIDENCE_INCOMPLETE","PNG_INVALID","REPORT_RUN_MISMATCH","REPORT_GENERATION_FAILED","JOURNAL_WRITE_FAILED" -> 4;
        default -> 2;
    };}
    private static void manifest(Path directory,String owner) throws java.io.IOException {
        try(var files=Files.list(directory)) {for(Path file:files.filter(Files::isRegularFile).filter(p -> !p.getFileName().toString().equals("private-policy.json") && !p.getFileName().toString().endsWith(".manifest.json")).toList()) PrivateArtifacts.manifest(directory,owner,file,8*1024*1024);}
    }
}
