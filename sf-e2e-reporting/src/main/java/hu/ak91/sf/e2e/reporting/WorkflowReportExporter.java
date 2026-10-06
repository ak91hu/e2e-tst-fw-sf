package hu.ak91.sf.e2e.reporting;

import hu.ak91.sf.e2e.core.*;
import tools.jackson.core.type.TypeReference;
import java.nio.file.*;
import java.util.*;

/** Automatic Allure exports even for failed authentication or an interrupted file workflow. */
public final class WorkflowReportExporter {
    private WorkflowReportExporter() {}
    public static void write(Path root,Map<String,Object> state,List<Map<String,Object>> events) {
        write(root,state,events,true);
    }
    public static void write(Path root,Map<String,Object> state,List<Map<String,Object>> events,boolean render) {
        Map<String,Object> report=new LinkedHashMap<>(state);report.put("events",events);report.put("reportEngine","Allure");report.put("allureVersion",AllureReports.VERSION);
        Json.write(root.resolve("case-report.json"),report);
        String run=state.get("runId").toString(),attempt=state.get("attemptId").toString(),id=state.get("caseId").toString();
        var artifacts=Json.MAPPER.convertValue(state.getOrDefault("artifacts",List.of()),new TypeReference<List<EvidenceService.Artifact>>(){});
        var attachments=new ArrayList<>(AllureResults.attachments(root,run,attempt,artifacts));Path results=root.resolve("allure-results");
        String eventsFile=SafePaths.segment(attempt)+"-events.json";Json.write(results.resolve(eventsFile),events);
        attachments.add(Map.of("name","Redacted event log","type","application/json","source",eventsFile));
        attachments.add(QualityReports.attach(root,attempt,events));Json.write(root.resolve("quality-summary.json"),QualityReports.summarize(events));
        String verdict=state.get("status").toString(),diagnostic=Objects.toString(state.get("diagnostic"),"");
        if(verdict.equals("FAILED") && !Set.of("APPLICATION_DEFECT","QUALITY_GATE_FAILED","ASSERTION_FAILED","API_ASSERTION_FAILED","FILE_VERIFICATION_FAILED","UI_ASSERTION_FAILED","VALUE_MISMATCH").contains(Objects.toString(state.get("code"),""))) verdict="BLOCKED";
        if("CANCELLED".equals(state.get("code"))) verdict="CANCELLED";
        String dataset=Objects.toString(state.get("datasetId"),"");
        Map<String,Object> result=new LinkedHashMap<>();result.put("uuid",attempt);result.put("historyId",Json.hash(id+"|"+dataset+"|"+state.get("mode")+"|"+state.getOrDefault("persona","default")));
        result.put("testCaseId",id+"|"+dataset);result.put("name",id+(dataset.isEmpty()?"":" ["+dataset+"]"));result.put("fullName",state.get("mode")+"."+id);result.put("status",AllureResults.status(verdict));
        result.put("stage","finished");result.put("start",AllureResults.time(state.get("startedAt")));result.put("stop",AllureResults.time(state.get("completedAt")));
        result.put("statusDetails",Map.of("message",Objects.toString(state.get("code"),"")+" "+diagnostic));result.put("attachments",attachments);
        result.put("steps",AllureResults.steps(events,artifacts,verdict,diagnostic));
        result.put("labels",List.of(Map.of("name","framework","value","sf-agentic-e2e-java"),Map.of("name","language","value","java"),
            Map.of("name","suite","value",state.get("mode").toString()),Map.of("name","parentSuite","value","Salesforce")));
        result.put("parameters",List.of(Map.of("name","persona","value",state.getOrDefault("persona","default")),
            Map.of("name","modelCalls","value",Objects.toString(state.getOrDefault("modelCalls",0)),"excluded",true)));
        Json.write(results.resolve(SafePaths.segment(attempt)+"-result.json"),result);
        if(render) AllureReports.generate(root);
    }
    public static void rebuild(Path root) {
        var state=Json.read(root.resolve("case-report.json"),Map.class);
        @SuppressWarnings("unchecked") var typed=(Map<String,Object>)state;
        var events=Json.MAPPER.convertValue(typed.getOrDefault("events",List.of()),new TypeReference<List<Map<String,Object>>>(){});
        write(root,typed,events);
    }
    public static void verify(Path root) {
        verifyEvidence(root);AllureReports.verify(root);
    }
    public static void verifyEvidence(Path root) {
        var state=Json.read(root.resolve("case-report.json"),Map.class);
        Failure.require(((Number)state.getOrDefault("schemaVersion",0)).intValue()==1 && state.get("completedAt")!=null &&
            Set.of("PASSED","FAILED","CANCELLED","BLOCKED","SKIPPED").contains(Objects.toString(state.get("status"),"")),"REPORT_RUN_MISMATCH","Workflow report is incomplete");
        var artifacts=Json.MAPPER.convertValue(state.getOrDefault("artifacts",List.of()),new TypeReference<List<EvidenceService.Artifact>>(){});
        Set<String> identities=new HashSet<>(),paths=new HashSet<>(),provenance=new HashSet<>();
        for(var artifact:artifacts) {
            EvidenceService.verifyArtifact(root,artifact,state.get("runId").toString(),state.get("attemptId").toString());
            Failure.require(identities.add(artifact.evidenceId()) && paths.add(artifact.path()) && provenance.add(artifact.provenance()),"EVIDENCE_INCOMPLETE","Workflow repeats a capture identity/path/provenance");
        }
        if("PASSED".equals(state.get("status"))) {
            Failure.require(artifacts.size()>=2 && artifacts.stream().anyMatch(a -> a.stage().equals("test-completion")),"EVIDENCE_INCOMPLETE","Passed workflow lacks mandatory completion evidence");
            Failure.require(state.get("code")==null,"REPORT_RUN_MISMATCH","Passed workflow retains a failure code");
            var events=Json.MAPPER.convertValue(state.getOrDefault("events",List.of()),new TypeReference<List<Map<String,Object>>>(){});
            Failure.require(events.stream().noneMatch(e -> "quality-gate".equals(e.get("kind")) && "FAILED".equals(e.get("status")) ||
                "browser-issue".equals(e.get("kind")) && Boolean.FALSE.equals(e.get("waived")) && Boolean.TRUE.equals(e.get("gateEnabled"))),"REPORT_RUN_MISMATCH","Passed workflow contains a failed application quality gate");
        }
    }
}
