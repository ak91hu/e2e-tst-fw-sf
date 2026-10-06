package hu.ak91.sf.e2e.cli;

import hu.ak91.sf.e2e.core.*;
import hu.ak91.sf.e2e.reporting.*;
import picocli.CommandLine.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.Callable;

/** Concrete, installed/pinned Salesforce LWC/Jest adapter. No browser, AI or org credentials are required. */
@Command(name="run-components",mixinStandardHelpOptions=true,description="Run explicitly selected LWC/Jest checks with pinned Node/Jest versions and timestamped Allure/private diagnostics.")
public final class ComponentCommands implements Callable<Integer> {
    public record Plan(int schemaVersion,String projectDirectory,String nodeExecutable,String expectedNodeVersion,String expectedLwcJestVersion,
                       List<String> tests,int timeoutSeconds,ExternalLayers.Provenance provenance) {
        public Plan {
            Failure.require(schemaVersion==1 && projectDirectory!=null && !projectDirectory.isBlank() && nodeExecutable!=null && !nodeExecutable.isBlank() && expectedNodeVersion!=null && expectedNodeVersion.matches("v[0-9]+\\.[0-9]+\\.[0-9]+") && expectedLwcJestVersion!=null && expectedLwcJestVersion.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?") && tests!=null && !tests.isEmpty() && tests.size()<=1000 && tests.stream().allMatch(t -> t!=null && t.length()<=1000) && tests.stream().distinct().count()==tests.size() && timeoutSeconds>=1 && timeoutSeconds<=3600 && provenance!=null && provenance.environment().equals("component") && provenance.runner().equals("sfdx-lwc-jest") && provenance.runnerVersion().equals("node="+expectedNodeVersion+";sfdx-lwc-jest="+expectedLwcJestVersion),"CONFIG_INVALID","Component plan needs explicit project/tests, pinned versions, component provenance and a bounded deadline");
            tests=List.copyOf(tests);
            for(String test:tests) Failure.require(!test.contains("\\") && !test.contains(":") && test.matches("(?:[^/]+/)*__tests__/[^/]+\\.(?:test|spec)\\.js") && Arrays.stream(test.split("/",-1)).noneMatch(part -> part.isBlank() || part.equals(".") || part.equals("..")),"CONFIG_INVALID","Component tests need explicit safe repository-relative __tests__ paths");
        }
    }
    @Option(names="--plan",required=true) Path planFile;
    @Option(names="--report-root",defaultValue="report") Path reportRoot;
    static Plan readPlan(Path planFile) {
        Failure.require(Files.isRegularFile(planFile),"CONFIG_INVALID","Component plan is missing");Plan supplied=hu.ak91.sf.e2e.core.scenario.ChangeImpact.read(planFile,Plan.class);
        Path project=planFile.toAbsolutePath().getParent().resolve(supplied.projectDirectory()).normalize();
        Failure.require(Files.isDirectory(project),"CONFIG_INVALID","Component project is unavailable");
        return new Plan(supplied.schemaVersion(),project.toString(),supplied.nodeExecutable(),supplied.expectedNodeVersion(),supplied.expectedLwcJestVersion(),supplied.tests(),supplied.timeoutSeconds(),supplied.provenance());
    }
    record Outcome(ExternalLayers.Layer layer,List<Map<String,Object>> errors) {}
    static Outcome executePlan(Plan plan,Path root,String layerId) {
        String attempt=UUID.randomUUID().toString();List<Map<String,Object>> errors=new ArrayList<>();ExternalLayers.Layer layer;
        Path project=Path.of(plan.projectDirectory());
        try {
            Path tools=root.resolve("component-tools");Files.createDirectories(tools);
            for(String name:List.of("lwc-runner.mjs","jest-reporter.cjs")) try(var input=ComponentCommands.class.getResourceAsStream("/component-tools/"+name)) {
                Failure.require(input!=null,"CONFIG_INVALID","Bundled component adapter is unavailable");Files.write(tools.resolve(name),input.readAllBytes(),StandardOpenOption.CREATE_NEW);
            }
            Path privateRoot=PrivateArtifacts.create(root.resolve("private-components"),attempt,24),invocation=privateRoot.resolve("invocation.json"),xml=privateRoot.resolve("results.xml"),diagnostics=privateRoot.resolve("diagnostics.json");
            Json.write(invocation,plan);PrivateArtifacts.manifest(privateRoot,attempt,invocation,1024*1024);
            String actual=ReleaseCommands.version(List.of(plan.nodeExecutable(),tools.resolve("lwc-runner.mjs").toString(),"probe",invocation.toString()),project,Math.min(plan.timeoutSeconds(),30),List.of());
            Failure.require(actual.equals(plan.provenance().runnerVersion()),"RUNNER_VERSION_CHANGED","Component runtime/package pins do not match");
            int exit=ReleaseCommands.execute(List.of(plan.nodeExecutable(),tools.resolve("lwc-runner.mjs").toString(),"run",invocation.toString(),xml.toString(),diagnostics.toString()),project,plan.timeoutSeconds(),List.of());
            PrivateArtifacts.manifest(privateRoot,attempt,xml,8L*1024*1024);PrivateArtifacts.manifest(privateRoot,attempt,diagnostics,8L*1024*1024);
            layer=ExternalLayers.junit(xml,layerId,"jest",plan.provenance(),new SecretRegistry());
            if(exit!=0) {var tests=new ArrayList<>(layer.tests());tests.add(new ExternalLayers.Test("TST_ComponentRunnerExit_"+exit,"infrastructure","FAILED",0));layer=new ExternalLayers.Layer(layer.id(),layer.type(),layer.provenance(),layer.inputChecksum(),tests,"FAILED");}
        }catch(Exception failure) {
            String code=failure instanceof Failure f?f.code():"COMPONENT_RUNNER_FAILED";errors.add(Map.of("code",code));
            layer=new ExternalLayers.Layer(layerId,"jest",plan.provenance(),Json.hash(plan),List.of(new ExternalLayers.Test("TST_"+code,"infrastructure","BLOCKED",0)),"FAILED");
        }
        return new Outcome(layer,List.copyOf(errors));
    }
    public Integer call() {
        Plan plan=readPlan(planFile);Instant started=Instant.now();Path root=ReportDirectories.create(reportRoot,started);
        Outcome outcome=executePlan(plan,root,"lwc-components");
        Json.write(root.resolve("trend-context.json"),Map.of("contextHash",Json.hash(plan)));
        Json.write(root.resolve("runner-failures.json"),outcome.errors());Json.write(root.resolve("run-timing.json"),Map.of("startedAt",started,"completedAt",Instant.now()));
        boolean passed=ExternalLayers.publish(root,List.of(outcome.layer()));System.out.println("Components "+(passed?"PASSED":"FAILED")+"; Allure="+root.resolve("index.html"));return passed?0:1;
    }
}
