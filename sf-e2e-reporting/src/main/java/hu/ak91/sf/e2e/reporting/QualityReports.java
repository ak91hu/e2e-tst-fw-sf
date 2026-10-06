package hu.ak91.sf.e2e.reporting;

import hu.ak91.sf.e2e.core.*;
import java.nio.file.*;
import java.util.*;

/** Machine-readable defect, assertion and locator-drift evidence accompanies Allure. */
public final class QualityReports {
    private QualityReports() {}
    public static Map<String,Object> summarize(List<Map<String,Object>> events) {
        Map<String,Object> result=new LinkedHashMap<>();result.put("schemaVersion",1);
        for(var field:Map.of("assertionsPassed","assertion-passed","healingEvents","locator-healed","locatorRetries","locator-retry","modelCallAttempts","model-call-started").entrySet())
            result.put(field.getKey(),events.stream().filter(e -> field.getValue().equals(e.get("kind"))).count());
        var issues=events.stream().filter(e -> "browser-issue".equals(e.get("kind"))).toList();result.put("browserIssues",issues);
        result.put("unwaivedBrowserDefects",issues.stream().filter(e -> Boolean.FALSE.equals(e.get("waived")) && Boolean.TRUE.equals(e.get("gateEnabled"))).count());
        result.put("waivedBrowserIssues",issues.stream().filter(e -> Boolean.TRUE.equals(e.get("waived"))).count());
        result.put("qualityGateFailures",events.stream().filter(e -> "quality-gate".equals(e.get("kind")) && "FAILED".equals(e.get("status"))).count());
        Map<String,Map<String,Object>> workflows=new LinkedHashMap<>();
        for(var event:events) if("ai-workflow-summary".equals(event.get("kind"))) {
            @SuppressWarnings("unchecked") var metrics=(Map<String,Object>)event.get("metrics");workflows.put(Objects.toString(event.get("workflowId"),"legacy"),metrics);
        }
        Map<String,Object> totals=new LinkedHashMap<>();
        for(String field:List.of("providerCalls","escalations","reusedResponses","chargedTokens","unknownUsageCalls","inputBytesSent","compactBytesSaved","outputTokensReserved"))
            totals.put(field,workflows.values().stream().mapToLong(m -> ((Number)m.getOrDefault(field,0)).longValue()).sum());
        boolean known=workflows.values().stream().allMatch(m -> Boolean.TRUE.equals(m.get("costRatesConfigured")));
        totals.put("estimatedCostUsd",known?workflows.values().stream().map(m -> new java.math.BigDecimal(m.get("estimatedCostUsd").toString())).reduce(java.math.BigDecimal.ZERO,java.math.BigDecimal::add):null);
        totals.put("costRatesConfigured",!workflows.isEmpty() && known);totals.put("stopped",workflows.values().stream().anyMatch(m -> Boolean.TRUE.equals(m.get("stopped"))));
        totals.put("reusedTargets",events.stream().filter(e -> "ai-target-reused".equals(e.get("kind"))).count());result.put("aiWorkflow",totals);
        totals.put("batchedRequests",events.stream().filter(e -> "ai-targets-batched".equals(e.get("kind"))).count());
        totals.put("batchedTargetsReused",events.stream().filter(e -> "ai-batch-target-reused".equals(e.get("kind"))).count());
        totals.put("reusedScopes",events.stream().filter(e -> "ai-map-reused".equals(e.get("kind"))).count());
        result.put("durationNanosByPhase",hu.ak91.sf.e2e.core.quality.ExecutionMetrics.totals(events));
        result.put("qualityModules",events.stream().filter(e -> "quality-module".equals(e.get("kind"))).toList());
        result.put("apiRequests",events.stream().filter(e -> "api-request".equals(e.get("kind"))).count());
        return result;
    }
    public static Map<String,String> attach(Path root,String attempt,List<Map<String,Object>> events) {
        String source=SafePaths.segment(attempt)+"-quality.json";var summary=summarize(events);
        Json.write(root.resolve("allure-results").resolve(source),summary);
        Json.write(SafePaths.inside(root,"attempts/"+SafePaths.segment(attempt)+"/browser-diagnostics.json"),summary);
        return Map.of("name","Browser diagnostics and quality gates","type","application/json","source",source);
    }
}
