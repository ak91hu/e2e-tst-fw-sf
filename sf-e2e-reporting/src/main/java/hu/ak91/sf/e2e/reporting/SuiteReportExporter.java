package hu.ak91.sf.e2e.reporting;

import hu.ak91.sf.e2e.core.*;
import java.nio.file.*;
import java.util.*;

/** One Allure report for isolated datasets; child evidence is verified before aggregation. */
public final class SuiteReportExporter {
    private SuiteReportExporter() {}
    @SuppressWarnings("unchecked") public static void write(Path root,Map<String,Object> suite) {
        verifyRows(root,suite);Json.write(root.resolve("suite-report.json"),suite);
        List<Map<String,Object>> events=new ArrayList<>();
        try {
            Path results=root.resolve("allure-results");Files.createDirectories(results);
            Map<String,Path> owned=new LinkedHashMap<>();
            for(var row:(List<Map<String,Object>>)suite.get("datasets")) {
                Path child=SafePaths.inside(root,row.get("directory").toString());
                var state=Json.read(child.resolve("case-report.json"),Map.class);var childEvents=(List<Map<String,Object>>)state.getOrDefault("events",List.of());events.addAll(childEvents);
                WorkflowReportExporter.write(child,state,childEvents,false);
                String attempt=SafePaths.segment(state.get("attemptId").toString());
                List<String> names=new ArrayList<>(List.of(attempt+"-result.json",attempt+"-events.json",attempt+"-quality.json"));
                for(var artifact:(List<Map<String,Object>>)state.getOrDefault("artifacts",List.of())) names.add(SafePaths.segment(artifact.get("evidenceId").toString())+"-attachment.png");
                for(String name:names) Failure.require(owned.putIfAbsent(name,child.resolve("allure-results").resolve(name))==null,"REPORT_RUN_MISMATCH","Duplicate suite attachment identity");
            }
            try(var files=Files.list(results)) { Failure.require(files.allMatch(file -> owned.containsKey(file.getFileName().toString()) && Files.isRegularFile(file)),"REPORT_RUN_MISMATCH","Unexpected files in suite Allure results"); }
            for(var file:owned.entrySet()) Files.copy(file.getValue(),results.resolve(file.getKey()),StandardCopyOption.REPLACE_EXISTING);
            Json.write(root.resolve("quality-summary.json"),QualityReports.summarize(events));AllureReports.generate(root);
        } catch(Failure f) { throw f; } catch(Exception e) { throw new Failure("EVIDENCE_INCOMPLETE","Cannot aggregate suite evidence"); }
    }
    @SuppressWarnings("unchecked") private static void verifyRows(Path root,Map<String,Object> suite) {
        Failure.require(((Number)suite.getOrDefault("schemaVersion",0)).intValue()==1 && Boolean.TRUE.equals(suite.get("complete")),"REPORT_RUN_MISMATCH","Suite is incomplete");
        String run=Objects.toString(suite.get("runId"),"");
        Failure.require(!run.isBlank() && suite.get("modelCalls") instanceof Number calls && calls.doubleValue()==0,
            "REPORT_RUN_MISMATCH","Suite requires an identified model-free run");
        var rows=(List<Map<String,Object>>)suite.get("datasets");
        Failure.require(rows!=null && !rows.isEmpty() && rows.size()==((Number)suite.get("selected")).intValue(),"REPORT_RUN_MISMATCH","Suite selected/completed count does not match");
        Set<String> attempts=new HashSet<>(),ids=new HashSet<>();int failed=0;
        for(var row:rows) {
            String id=SafePaths.segment(row.get("datasetId").toString());
            Failure.require(ids.add(id) && ("cases/"+id).equals(row.get("directory")),"REPORT_RUN_MISMATCH","Dataset identity/path mismatch");
            Path child=SafePaths.inside(root,"cases/"+id);WorkflowReportExporter.verifyEvidence(child);
            var state=Json.read(child.resolve("case-report.json"),Map.class);
            Failure.require(attempts.add(state.get("attemptId").toString()) && Objects.equals(row.get("checksum"),Json.hash(state)) &&
                Objects.equals(row.get("status"),state.get("status")) && Objects.equals(id,state.get("datasetId")) &&
                Objects.equals(row.get("caseId"),state.get("caseId")) && Objects.equals(run+"."+id,state.get("runId")) &&
                "replay".equals(state.get("mode")) && state.get("modelCalls") instanceof Number calls && calls.doubleValue()==0,
                "REPORT_RUN_MISMATCH","Dataset status, checksum, identity or model-free execution mismatch");
            if(!"PASSED".equals(state.get("status"))) failed++;
        }
        Failure.require((failed==0)=="PASSED".equals(suite.get("status")) && (failed==0)==(((Number)suite.get("exitCode")).intValue()==0),"REPORT_RUN_MISMATCH","Suite cannot claim success when a selected dataset did not pass");
    }
    public static void verify(Path root) { verifyRows(root,Json.read(root.resolve("suite-report.json"),Map.class));AllureReports.verify(root); }
    public static void rebuild(Path root) {
        var suite=Json.read(root.resolve("suite-report.json"),Map.class);verifyRows(root,suite);
        write(root,suite);
    }
}
