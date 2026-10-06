package hu.ak91.sf.e2e.reporting;

import hu.ak91.sf.e2e.core.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Shared Allure result format for coordinated Java cases and file workflows. */
public final class AllureResults {
    private AllureResults() {}
    public static String status(String status) {
        return switch(status) { case "PASSED" -> "passed";case "FAILED" -> "failed";case "BLOCKED","EVIDENCE_FAILED" -> "broken";default -> "skipped"; };
    }
    public static List<Map<String,String>> attachments(Path root,String runId,String attemptId,List<EvidenceService.Artifact> artifacts) {
        Path target=root.resolve("allure-results");List<Map<String,String>> result=new ArrayList<>();
        try {
            Files.createDirectories(target);
            for(var artifact:artifacts) {
                EvidenceService.verifyArtifact(root,artifact,runId,attemptId);
                String source=SafePaths.segment(artifact.evidenceId())+"-attachment.png";
                Files.copy(SafePaths.inside(root,artifact.path()),target.resolve(source),StandardCopyOption.REPLACE_EXISTING);
                result.add(Map.of("name",artifact.stage(),"source",source,"type","image/png"));
            }
        } catch(Failure f) { throw f; } catch(Exception e) { throw new Failure("EVIDENCE_INCOMPLETE","Cannot attach verified screenshots to Allure"); }
        return List.copyOf(result);
    }
    public static List<Map<String,Object>> steps(List<Map<String,Object>> events,List<EvidenceService.Artifact> artifacts,String verdict,String diagnostic) {
        List<Map<String,Object>> result=new ArrayList<>();Map<Integer,Map<String,Object>> planned=new TreeMap<>();
        Map<String,EvidenceService.Artifact> captures=new HashMap<>();artifacts.forEach(a -> captures.put(a.evidenceId(),a));
        Map<String,Object> active=null;boolean structured=false;
        for(var event:events) {
            String kind=Objects.toString(event.get("kind"),"");long time=time(event.get("capturedAt"));
            if(kind.equals("case-plan-ready") || kind.equals("case-replay-plan")) {
                structured=true;
                var node=Json.MAPPER.valueToTree(event);var definitions=kind.equals("case-plan-ready")?node.path("plan").path("steps"):node.path("steps");
                int index=0;
                for(var definition:definitions) {
                    String label=definition.path("kind").asString()+" "+definition.path("instruction").asString(definition.path("key").asString(definition.path("route").asString("")));
                    var step=step((++index)+". "+label.trim(),"skipped",0);planned.put(index,step);result.add(step);
                }
                continue;
            }
            if(kind.equals("case-step-started") || kind.equals("discovery-page-started")) {
                structured=true;
                int index=((Number)event.get("step")).intValue();active=planned.get(index);
                if(active==null) {
                    active=step(index+". "+Objects.toString(event.get("action"),"step")+" "+Objects.toString(event.get("route"),""),"running",time);
                    planned.put(index,active);result.add(active);
                }
                active.put("status","running");active.put("start",time);continue;
            }
            if(Set.of("case-step-passed","case-step-learned","page-discovered").contains(kind)) {
                int index=((Number)event.get(kind.equals("page-discovered")?"visited":"step")).intValue();active=planned.get(index);
                if(active!=null) { active.put("status","passed");active.put("stop",time); }
                continue;
            }
            if(kind.equals("checkpoint")) {
                var artifact=captures.get(Objects.toString(event.get("evidenceId"),""));
                if(active!=null && artifact!=null) list(active,"attachments").add(Map.of("name",artifact.stage(),"source",artifact.evidenceId()+"-attachment.png","type","image/png"));
                continue;
            }
            if(Set.of("locator-action","locator-retry","locator-healed","model-call-started","model-call-completed","model-call-failed","failure","browser-issue","quality-gate","assertion-passed","ai-response-reused","ai-target-reused","ai-escalated","ai-quality-rejected","ai-workflow-summary","ai-targets-batched","ai-batch-target-reused","ai-batch-invalidated","ai-batch-deferred","ai-map-reused").contains(kind)) {
                String eventStatus=Set.of("failure","model-call-failed").contains(kind)?"broken":"passed";
                if(kind.equals("quality-gate") && "FAILED".equals(event.get("status"))) eventStatus="failed";
                if(kind.equals("browser-issue") && Boolean.FALSE.equals(event.get("waived")) && Boolean.TRUE.equals(event.get("gateEnabled"))) eventStatus="failed";
                var detail=step(Json.string(event),eventStatus,time);
                if(kind.equals("locator-action") && !structured) { result.add(detail);active=detail; }
                else if(active!=null) list(active,"steps").add(detail);else result.add(detail);
            }
        }
        boolean interrupted=false;
        for(var step:result) if(step.get("status").equals("running")) {
            step.put("status",status(verdict));step.put("stop",events.isEmpty()?time(step.get("start")):time(events.getLast().get("capturedAt")));
            step.put("statusDetails",Map.of("message",Objects.toString(diagnostic,"Execution did not finish")));interrupted=true;
        }
        if(!verdict.equals("PASSED") && !interrupted) {
            var failure=step("Run finalization / failure",status(verdict),events.isEmpty()?0:time(events.getLast().get("capturedAt")));
            failure.put("statusDetails",Map.of("message",Objects.toString(diagnostic,"Execution did not finish")));result.add(failure);
        }
        return result;
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> list(Map<String,Object> step,String key) { return (List<Map<String,Object>>)step.get(key); }
    private static Map<String,Object> step(String name,String status,long time) {
        Map<String,Object> step=new LinkedHashMap<>();step.put("name",name);step.put("status",status);step.put("stage","finished");
        step.put("start",time);step.put("stop",time);step.put("attachments",new ArrayList<Map<String,Object>>());step.put("steps",new ArrayList<Map<String,Object>>());return step;
    }
    static long time(Object value) {
        if(value==null) return 0;if(value instanceof Number n) return n.longValue();if(value instanceof Instant t) return t.toEpochMilli();
        try { return Instant.parse(value.toString()).toEpochMilli(); } catch(Exception e) { return 0; }
    }
}
