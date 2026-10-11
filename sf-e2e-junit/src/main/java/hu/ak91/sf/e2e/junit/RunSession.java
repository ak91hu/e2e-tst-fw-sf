package hu.ak91.sf.e2e.junit;

import hu.ak91.sf.e2e.core.*;
import hu.ak91.sf.e2e.reporting.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
public final class RunSession {
    static final ThreadLocal<RunSession> CURRENT=new ThreadLocal<>();
    public final FrameworkConfig config;
    public final String runId;
    public final Path root;
    public final List<CaseInfo> selection;
    final PreflightReport preflight;
    public final Instant started=Instant.now();
    private final Map<String,AttemptResult> results=new LinkedHashMap<>();
    @FunctionalInterface interface ContextFactory { SfTestContext create(RunSession session,CaseInfo info); }
    private final ContextFactory factory;
    private final Map<String,SfTestContext> contexts=new HashMap<>();
    public RunSession(FrameworkConfig config,List<CaseInfo> selection) {
        this(config,selection,(session,info) -> new SfTestContext(session.config,session.runId,info,session.root));
    }
    RunSession(FrameworkConfig config,List<CaseInfo> selection,ContextFactory factory) { this(config,selection,factory,null); }
    RunSession(FrameworkConfig config,List<CaseInfo> selection,ContextFactory factory,Path preparedRoot) {
        this.factory=factory;
        this.config=config;this.selection=List.copyOf(selection);root=preparedRoot==null?ReportDirectories.create(Path.of(config.report().root()),started):preparedRoot;
        runId=root.getFileName().toString();
        preflight=new PreflightReport(root,"JUnit run");
        if(preparedRoot==null) preflight.design("Selected Java cases (method bodies require execution)",this.selection);
        Json.write(root.resolve("selection.json"),Map.of("schemaVersion",1,"runId",runId,"profile",config.profile(),"selected",selection.size(),"cases",selection));
        Json.write(root.resolve("resolved-config.safe.json"),config);
        Json.write(root.resolve("trend-context.json"),Map.of("contextHash",Json.hash(Map.of("selection",selection,"namespaces",selection.stream().map(CaseInfo::persona).distinct().map(p -> hu.ak91.sf.e2e.core.locator.LocatorStore.namespace(config,p)).sorted().toList(),"workers",config.execution().workers()))));
        Json.write(root.resolve("completion.json"),Map.of("runId",runId,"complete",false,"selected",selection.size(),"completed",0));
    }
    public void accept(AttemptResult result) {
        Failure.require(results.putIfAbsent(result.caseInfo().id(),result)==null,"REPORT_RUN_MISMATCH","Duplicate result");
        if(result.retainedRecords()!=null) Json.write(SafePaths.inside(root,"attempts/"+result.attemptId()+"/retained-records.json"),result.retainedRecords());
        Json.write(root.resolve("results.partial.json"),results.values());
    }
    public boolean has(String id) { return results.containsKey(id); }
    public boolean passed(String id) { return results.containsKey(id) && results.get(id).status().equals("PASSED"); }
    SfTestContext context(CaseInfo info) {
        return contexts.computeIfAbsent(info.id(),id -> factory.create(this,info));
    }
    /** Reconcile failures reported after our callback, including class-level teardown. */
    void junitFailure(CaseInfo info,Throwable failure) {
        var previous=results.get(info.id());
        if(previous==null) {
            notRun(info,"BLOCKED","JUNIT_LIFECYCLE_FAILED","JUnit setup/container failed before case finalization");return;
        }
        var sf=contexts.get(info.id());
        String detail=sf==null?"JUnit lifecycle failed":sf.redact(Objects.toString(failure.getMessage(),"JUnit lifecycle failed"));
        boolean passed=previous.status().equals("PASSED");
        if(!passed && Objects.equals(previous.errorCode(),SfTestContext.code(failure)) && Objects.equals(previous.errorMessage(),detail)) return;
        var secondary=new ArrayList<>(previous.secondaryErrors());
        if(!passed && !secondary.contains(detail)) secondary.add(detail);
        String status=passed?SfTestContext.classify(failure):previous.status();
        results.put(info.id(),new AttemptResult(previous.runId(),previous.attemptId(),previous.caseInfo(),previous.startedAt(),Instant.now(),
            passed?status:previous.businessVerdict(),previous.evidenceVerdict(),status,
            passed?SfTestContext.code(failure):previous.errorCode(),passed?detail:previous.errorMessage(),List.copyOf(secondary),
            previous.artifacts(),previous.events(),previous.retainedRecords()));
        Json.write(root.resolve("results.partial.json"),results.values());
    }
    public void notRun(CaseInfo info,String status,String code,String reason) {
        accept(new AttemptResult(runId,UUID.randomUUID().toString(),info,null,Instant.now(),"NOT_RUN","NOT_REQUIRED",status,code,reason,List.of(),List.of(),List.of(),null));
    }
    public RunReport complete() {
        boolean complete=results.size()==selection.size();
        var report=new RunReport(1,runId,started,Instant.now(),selection,List.copyOf(results.values()),complete,RunReport.exitCode(List.copyOf(results.values())));
        if(complete) {
            try { ReportIntegrityVerifier.verify(root,report,config.evidence().minPngPerPassedResult());Json.write(root.resolve("integrity.json"),Map.of("passed",true,"runId",runId)); }
            catch(Failure e) { Json.write(root.resolve("integrity.json"),Map.of("passed",false,"runId",runId,"code",e.code(),"message",e.getMessage()));
                report=new RunReport(1,runId,started,Instant.now(),selection,List.copyOf(results.values()),complete,4); }
        }
        ReportExporter.write(root,report);return report;
    }
}
