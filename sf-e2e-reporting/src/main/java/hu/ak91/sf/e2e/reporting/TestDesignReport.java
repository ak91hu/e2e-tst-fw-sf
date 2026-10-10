package hu.ak91.sf.e2e.reporting;

import hu.ak91.sf.e2e.core.*;
import tools.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Human-readable interpreted intent, derived from redacted planning and execution events. */
final class TestDesignReport {
    private TestDesignReport() {}

    static Map<String,String> write(Path root,Map<String,Object> state,List<Map<String,Object>> events) {
        JsonNode plan=null,trace=null,maintenance=null,dependencies=null;
        var repairs=new ArrayList<JsonNode>();
        var completed=new HashMap<Integer,JsonNode>();var started=new HashSet<Integer>();
        for(var event:events) {
            var node=Json.MAPPER.valueToTree(event);String kind=node.path("kind").asString();
            if("case-plan-ready".equals(kind)) plan=node.path("plan");
            if("case-instructions-interpreted".equals(kind)) trace=node;
            if("case-maintenance-plan".equals(kind)) maintenance=node;
            if("case-maintenance-dependencies".equals(kind)) dependencies=node;
            if("locator-healed".equals(kind)) repairs.add(node);
            if("case-step-started".equals(kind)) started.add(node.path("step").asInt());
            if("case-step-learned".equals(kind)) completed.put(node.path("step").asInt(),node.path("implementation"));
        }
        if(plan==null) return Map.of();
        var html=new StringBuilder("""
            <!doctype html>
            <html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'">
            <title>Implemented test design</title>
            <style>body{font:16px system-ui,sans-serif;max-width:1200px;margin:2rem auto;padding:0 1rem;color:#172b4d}table{width:100%;min-width:1000px;table-layout:fixed;border-collapse:collapse}th:nth-child(1){width:7%}th:nth-child(2){width:12%}th:nth-child(3){width:18%}th:nth-child(4){width:16%}th:nth-child(5){width:28%}th:nth-child(6){width:19%}th,td{padding:.7rem;border:1px solid #ccd3dd;text-align:left;vertical-align:top;overflow-wrap:anywhere}th{background:#edf2f7}code{white-space:pre-wrap}p{line-height:1.6}.table{overflow-x:auto}</style>
            </head><body><h1>Implemented test design</h1>
            """);
        html.append("<p>Case: <strong>").append(escape(maintenance==null?plan.path("id").asString():Objects.toString(state.get("caseId"),plan.path("id").asString()))).append("</strong><br>Start route: <code>")
            .append(escape(plan.path("startRoute").asString())).append("</code><br>Final attempt status: <strong>")
            .append(escape(Objects.toString(state.get("status"),"UNKNOWN"))).append("</strong> ")
            .append(escape(Objects.toString(state.get("code"),""))).append("</p>");
        html.append("<p>This design was established before case actions. Steps retain their planned order. Expected results remain caller-owned parameter references; runtime values are omitted. A completed step does not override a failed final evidence, quality or cleanup verdict.</p>");
        if(trace!=null) html.append("<p>Source instruction lines: ").append(trace.path("sourceInstructionCount").asInt())
            .append(". Source numbers show ordered line coverage; they do not prove semantic equivalence. Review the interpreted targets and assertions against your original design.</p>");
        if(maintenance!=null) {
            html.append("<h2>Maintenance review</h2><p>Baseline recipe SHA-256: <code>").append(escape(maintenance.path("baselineChecksum").asString()))
                .append("</code><br>Changes SHA-256: <code>").append(escape(maintenance.path("changesChecksum").asString()))
                .append("</code><br>Retained steps: ").append(maintenance.path("retainedSteps").asInt()).append("; inserted steps: ")
                .append(maintenance.path("insertedSteps").asInt()).append(".</p><p>Existing operations, locator references and expected-result references are retained in order. Added steps are caller-supplied. Runtime values can change only through the supplied private parameters. The workflow executes once; a failed attempt can already have changed application data and shared locators.</p>");
            html.append("<h3>Potential shared-control dependencies</h3><p>Only explicitly supplied same-context related recipes are listed. They were not executed; sharing a definition does not establish a passing verdict or complete impact coverage.</p><ul>");
            if(dependencies!=null) for(var related:dependencies.path("potentialSharedCases"))
                html.append("<li>").append(escape(related.path("caseId").asString())).append(" — <code>")
                    .append(escape(related.path("recipeChecksum").asString())).append("</code>: ")
                    .append(escape(Json.string(related.path("sharedControls")))).append("</li>");
            html.append("</ul><h3>Recorded shared locator repairs</h3><ul>");
            for(var repair:repairs) html.append("<li>").append(escape(repair.path("page").asString())).append("/")
                .append(escape(repair.path("key").asString())).append("; persisted: ").append(repair.path("persisted").asBoolean())
                .append("; reason: ").append(escape(repair.path("reason").asString())).append("<br>Before: <code>")
                .append(escape(Json.string(repair.path("before")))).append("</code><br>After: <code>")
                .append(escape(Json.string(repair.path("after")))).append("</code></li>");
            html.append("</ul>");
        }
        html.append("<div class=\"table\"><table><thead><tr><th>Step / source</th><th>Planned operation</th><th>Target / route / scope</th><th>Input / expected result</th><th>Implementation</th><th>Step outcome</th></tr></thead><tbody>");
        int index=0;
        for(var step:plan.path("steps")) {
            index++;var implementation=completed.get(index);
            String outcome=completed.containsKey(index)?"COMPLETED":started.contains(index)?"STARTED — NOT COMPLETED":"NOT EXECUTED";
            html.append("<tr><td>").append(index);
            if(trace!=null) html.append(" / ").append(trace.path("sourceSteps").path(index-1).asInt());
            if(maintenance!=null) {
                int old=maintenance.path("steps").path(index-1).path("baselineStep").asInt();
                html.append(old>0?"<br>Retained baseline step "+old:"<br>New supplied step");
            }
            html.append("</td><td>").append(escape(step.path("kind").asString())).append("</td><td>");
            field(html,step,"instruction");field(html,step,"route");
            html.append("<br>Scope: ").append(escape(step.path("scope").isString()?step.path("scope").asString():"page"));
            html.append("</td><td>");
            for(String ref:List.of("valueRef","recordNameRef")) if(step.path(ref).isString())
                html.append(escape(ref)).append(": <code>${").append(escape(step.path(ref).asString())).append("}</code><br>");
            if(!step.path("valueRef").isString()) html.append("No runtime value required");
            html.append("</td><td>");
            if(implementation!=null) { field(html,implementation,"page");field(html,implementation,"key");field(html,implementation,"route"); }
            else html.append("No completed implementation recorded");
            html.append("</td><td>").append(outcome).append("</td></tr>");
        }
        html.append("</tbody></table></div><p>COMPLETED means the planned action or deterministic assertion returned successfully. STARTED — NOT COMPLETED can include an uncertain dispatched action; inspect the journal before any explicit rerun. NOT EXECUTED means no step start was recorded. Learning creates an executable JSON recipe, using Page Object Model, rather than generated Java source.</p></body></html>");
        byte[] bytes=html.toString().getBytes(StandardCharsets.UTF_8);
        String attachment=SafePaths.segment(state.get("attemptId").toString())+"-test-design.html";
        writeBytes(SafePaths.inside(root,"test-design.html"),bytes);writeBytes(SafePaths.inside(root,"allure-results/"+attachment),bytes);
        return Map.of("path","test-design.html","source",attachment,"sha256",Json.hash(bytes));
    }
    private static void field(StringBuilder html,JsonNode node,String field) {
        if(node.path(field).isString()) html.append(escape(field)).append(": <code>").append(escape(node.path(field).asString())).append("</code><br>");
    }
    private static String escape(String value) {
        return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");
    }
    private static void writeBytes(Path path,byte[] bytes) {
        Path temporary=null;
        try {
            Files.createDirectories(path.getParent());temporary=Files.createTempFile(path.getParent(),".test-design-",".tmp");
            Files.write(temporary,bytes);
            try { Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch(AtomicMoveNotSupportedException e) { Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING); }
        } catch(Exception e) { throw new Failure("REPORT_GENERATION_FAILED","Cannot publish test design HTML"); }
        finally { if(temporary!=null) try { Files.deleteIfExists(temporary); } catch(Exception ignored) {} }
    }
    static void verify(Path root,String attempt,Map<?,?> receipt) {
        Failure.require("test-design.html".equals(receipt.get("path")) &&
            (SafePaths.segment(attempt)+"-test-design.html").equals(receipt.get("source")) &&
            receipt.get("sha256") instanceof String hash && hash.matches("[a-f0-9]{64}"),
            "REPORT_RUN_MISMATCH","Invalid test design receipt or attempt ownership");
        try {
            String source=(String)receipt.get("source");
            for(Path path:List.of(SafePaths.inside(root,"test-design.html"),SafePaths.inside(root,"allure-results/"+source)))
                Failure.require(Json.hash(Files.readAllBytes(path)).equals(receipt.get("sha256")),"REPORT_RUN_MISMATCH","Test design HTML integrity mismatch");
        } catch(java.io.IOException e) { throw new Failure("REPORT_RUN_MISMATCH","Missing test design HTML"); }
    }
}
