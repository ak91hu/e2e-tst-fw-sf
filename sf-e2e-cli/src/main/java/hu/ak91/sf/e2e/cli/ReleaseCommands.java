package hu.ak91.sf.e2e.cli;

import hu.ak91.sf.e2e.core.*;
import hu.ak91.sf.e2e.reporting.*;
import picocli.CommandLine.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Execute explicitly supplied, version-pinned external layers without mixing their credentials or outcomes. */
@Command(name="run-release-matrix",mixinStandardHelpOptions=true,description="Run pinned Apex/Jest/API/web/mobile tools and combine their JUnit results in timestamped Allure.")
public final class ReleaseCommands implements Callable<Integer> {
    public record Entry(String id,String type,ExternalLayers.Provenance provenance,String workingDirectory,List<String> command,List<String> versionCommand,String exactVersionOutput,String results,int timeoutSeconds,List<String> credentialRefs,String componentPlan) {
        public Entry {
            SafePaths.segment(id);
            boolean component=componentPlan!=null;
            Failure.require(provenance!=null && type!=null && Set.of("apex","jest","api","web","mobile").contains(type) && (component
                ? type!=null && type.equals("jest") && !componentPlan.isBlank() && workingDirectory==null && command==null && versionCommand==null && exactVersionOutput==null && results==null && timeoutSeconds==0 && (credentialRefs==null || credentialRefs.isEmpty())
                : workingDirectory!=null && command!=null && !command.isEmpty() && versionCommand!=null && !versionCommand.isEmpty() && exactVersionOutput!=null && exactVersionOutput.equals(provenance.runnerVersion()) && results!=null && timeoutSeconds>=1 && timeoutSeconds<=3600 && credentialRefs!=null && credentialRefs.stream().allMatch(s -> s.matches("[A-Z][A-Z0-9_]{1,100}"))),"CONFIG_INVALID","Release entry needs either a componentPlan or explicit pinned commands, credentials and bounds");
            credentialRefs=credentialRefs==null?List.of():List.copyOf(credentialRefs);
        }
        public Entry(String id,String type,ExternalLayers.Provenance provenance,String workingDirectory,List<String> command,List<String> versionCommand,String exactVersionOutput,String results,int timeoutSeconds,List<String> credentialRefs) {this(id,type,provenance,workingDirectory,command,versionCommand,exactVersionOutput,results,timeoutSeconds,credentialRefs,null);}
    }
    public record Matrix(int schemaVersion,List<Entry> entries) { public Matrix { Failure.require(schemaVersion==1 && entries!=null && !entries.isEmpty() && entries.size()<=100 && entries.stream().map(e -> e.provenance().target()+"|"+e.id()).distinct().count()==entries.size(),"CONFIG_INVALID","Invalid release matrix");entries=List.copyOf(entries); } }
    @Option(names="--matrix",required=true) Path matrix;
    @Option(names="--report-root",defaultValue="report") Path reportRoot;
    public Integer call() {
        Failure.require(Files.isRegularFile(matrix),"CONFIG_INVALID","Release matrix is missing");Matrix plan=Json.read(matrix,Matrix.class);Path parent=matrix.toAbsolutePath().getParent();SecretRegistry secrets=new SecretRegistry();
        // Preflight the complete plan before any external runner starts.
        Map<Entry,ComponentCommands.Plan> components=new HashMap<>();
        for(var entry:plan.entries()) {
            if(entry.componentPlan()!=null) {var component=ComponentCommands.readPlan(parent.resolve(entry.componentPlan()).normalize());Failure.require(component.provenance().equals(entry.provenance()),"CONFIG_INVALID","Component and matrix provenance must match exactly");components.put(entry,component);}
            else {Failure.require(Files.isDirectory(parent.resolve(entry.workingDirectory()).normalize()),"CONFIG_INVALID","Runner working directory is unavailable");for(String ref:entry.credentialRefs()) secrets.resolve(new SecretRef(ref,"external-runner"));}
        }
        Instant started=Instant.now();Path root=ReportDirectories.create(reportRoot,started);List<ExternalLayers.Layer> layers=new ArrayList<>();List<Map<String,Object>> failures=new ArrayList<>();
        for(var entry:plan.entries()) try {
            if(components.containsKey(entry)) {
                Path owned=root.resolve("layers").resolve(Json.hash(entry.provenance().target()).substring(0,16)).resolve(entry.id());
                var outcome=ComponentCommands.executePlan(components.get(entry),owned,entry.id());layers.add(outcome.layer());
                for(var error:outcome.errors()) failures.add(Map.of("target",entry.provenance().target(),"layer",entry.id(),"code",error.get("code")));continue;
            }
            Path cwd=parent.resolve(entry.workingDirectory()).normalize(),output=SafePaths.inside(cwd,entry.results());
            Failure.require(!Files.exists(output),"RESULT_IMPORT_INVALID","Runner result file already exists; stale results cannot certify a new release");
            String version=version(entry.versionCommand(),cwd,Math.min(entry.timeoutSeconds(),30),entry.credentialRefs());Failure.require(version.equals(entry.exactVersionOutput()),"RUNNER_VERSION_CHANGED","External runner version does not match the pinned output");
            int exit=execute(entry.command(),cwd,entry.timeoutSeconds(),entry.credentialRefs());Failure.require(Files.isRegularFile(output),"RESULT_IMPORT_INVALID","External runner did not produce its required JUnit file");
            var layer=ExternalLayers.junit(output,entry.id(),entry.type(),entry.provenance(),secrets);
            if(exit!=0) {var tests=new ArrayList<>(layer.tests());tests.add(new ExternalLayers.Test("TST_ExternalRunnerExit_"+exit,"infrastructure","FAILED",0));layer=new ExternalLayers.Layer(layer.id(),layer.type(),layer.provenance(),layer.inputChecksum(),List.copyOf(tests),"FAILED");}layers.add(layer);
        } catch(RuntimeException e) { String code=e instanceof Failure f?f.code():"ENVIRONMENT_UNAVAILABLE";failures.add(Map.of("target",entry.provenance().target(),"layer",entry.id(),"code",code));layers.add(new ExternalLayers.Layer(entry.id(),entry.type(),entry.provenance(),Json.hash(entry),List.of(new ExternalLayers.Test("TST_"+code,"infrastructure","BLOCKED",0)),"FAILED")); }
        Json.write(root.resolve("runner-failures.json"),failures);
        Json.write(root.resolve("trend-context.json"),Map.of("contextHash",Json.hash(Map.of("matrix",plan,"components",components.values().stream().sorted(Comparator.comparing(ComponentCommands.Plan::projectDirectory).thenComparing(p -> Json.hash(p))).toList()))));
        Json.write(root.resolve("run-timing.json"),Map.of("startedAt",started,"completedAt",Instant.now()));
        boolean passed=ExternalLayers.publish(root,layers) && failures.isEmpty();
        if(!failures.isEmpty()) { Json.write(root.resolve("release-gate.json"),Map.of("status","FAILED","failures",failures));passed=false; }
        System.out.println("Release matrix: "+(passed?"PASSED":"FAILED")+"; report="+root);return passed?0:1;
    }
    static ProcessBuilder builder(List<String> command,Path cwd,List<String> refs) {
        var builder=new ProcessBuilder(command).directory(cwd.toFile());builder.environment().keySet().removeIf(key -> key.matches("(?i).*(TOKEN|PASSWORD|SECRET|PRIVATE_KEY|PRIVATE_KEY_FILE|API_KEY|CLIENT_ID|USERNAME)$"));for(String ref:refs) builder.environment().put(ref,System.getenv(ref));return builder;
    }
    static int execute(List<String> command,Path cwd,int timeout,List<String> refs) {
        Process process=null;try { process=builder(command,cwd,refs).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();if(!process.waitFor(timeout,TimeUnit.SECONDS)) throw new Failure("RUNNER_TIMEOUT","External runner exceeded its deadline");return process.exitValue(); }
        catch(Failure e) { throw e; }catch(InterruptedException e) {Thread.currentThread().interrupt();throw new Failure("CANCELLED","External runner interrupted");}catch(Exception e) {throw new Failure("ENVIRONMENT_UNAVAILABLE","External runner could not start");}
        finally {if(process!=null && process.isAlive()) {process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();} }
    }
    static String version(List<String> command,Path cwd,int timeout,List<String> refs) {
        Process process=null;var executor=Executors.newVirtualThreadPerTaskExecutor();try {
            process=builder(command,cwd,refs).redirectError(ProcessBuilder.Redirect.DISCARD).start();final Process current=process;
            var output=executor.submit(() -> current.getInputStream().readNBytes(4097));byte[] bytes=output.get(timeout,TimeUnit.SECONDS);Failure.require(bytes.length<=4096 && process.waitFor(timeout,TimeUnit.SECONDS) && process.exitValue()==0,"RUNNER_VERSION_CHANGED","Version probe failed or exceeded its limit");return new String(bytes,java.nio.charset.StandardCharsets.UTF_8).strip();
        } catch(Failure e) { throw e; }catch(Exception e) {if(e instanceof InterruptedException) Thread.currentThread().interrupt();throw new Failure("RUNNER_VERSION_CHANGED","Version probe could not be verified");}finally {if(process!=null && process.isAlive()) {process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();}executor.shutdownNow();}
    }
}
