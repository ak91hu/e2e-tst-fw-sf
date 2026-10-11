package hu.ak91.sf.e2e.reporting;

import hu.ak91.sf.e2e.core.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Small standalone HTML survives an unavailable Allure/Node runtime. Not a replacement verdict. */
public final class HtmlSummary {
    private HtmlSummary() {}
    static StringBuilder page(String title) {
        return new StringBuilder("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'\"><title>")
            .append(escape(title)).append("</title><style>body{font:16px system-ui;margin:2rem auto;padding:0 1rem;max-width:1200px}table{border-collapse:collapse;width:100%}td,th{border:1px solid #ccc;padding:.6rem;text-align:left;vertical-align:top}pre{white-space:pre-wrap;overflow-wrap:anywhere}td{overflow-wrap:anywhere}</style></head><body><h1>")
            .append(escape(title)).append("</h1>");
    }
    public static void blocked(Path root, String workflow, Throwable error) {
        write(root,workflow,"BLOCKED",List.of(Map.of("status","NOT EXECUTED","code",PreflightReport.code(error))),
            "Preparation failed. No business actions were dispatched. Inspect preflight.html and correct the blocked inputs.");
    }
    public static void workflow(Path root, Map<String,Object> state, List<Map<String,Object>> events) {
        var rows = new ArrayList<Map<String,Object>>();
        var completed = new HashSet<Integer>(); var started = new HashSet<Integer>();
        List<?> steps = List.of();
        for(var event:events) {
            String kind = Objects.toString(event.get("kind"),"");
            if("case-plan-ready".equals(kind)) steps = Json.MAPPER.valueToTree(event.get("plan")).path("steps").isArray()
                ? Json.MAPPER.convertValue(Json.MAPPER.valueToTree(event.get("plan")).path("steps"),List.class) : List.of();
            if("case-replay-plan".equals(kind)) steps = (List<?>)event.get("steps");
            if("case-step-started".equals(kind)) started.add(((Number)event.get("step")).intValue());
            if(Set.of("case-step-learned","case-step-passed").contains(kind)) completed.add(((Number)event.get("step")).intValue());
        }
        int index = 0;
        for(var step:steps) {
            index++; var node = Json.MAPPER.valueToTree(step); var row = new LinkedHashMap<String,Object>();
            row.put("step",index);row.put("operation",node.path("kind").asString());
            for(String field:List.of("instruction","page","key","route","scope","valueRef","recordNameRef"))
                if(node.path(field).isString()) row.put(field,node.path(field).asString());
            row.put("status",completed.contains(index)?"COMPLETED":started.contains(index)?"STARTED — NOT COMPLETED; dispatch outcome may be uncertain":"NOT EXECUTED");rows.add(row);
        }
        var totals = new LinkedHashMap<String,Object>();
        for(String key:List.of("caseId","mode","persona","startedAt","completedAt","modelCalls","code","diagnostic"))
            if(state.containsKey(key)) totals.put(key,state.get(key));
        rows.add(totals);
        write(root,Objects.toString(state.get("caseId"),"Case"),Objects.toString(state.get("status"),"UNKNOWN"),rows,
            "Completed steps do not override final teardown, browser quality, evidence or publication failures. Never blindly repeat an uncertain dispatched mutation. Masked screenshots and detailed events are retained in the Allure report and original attempt artifacts.");
    }
    public static void write(Path root,String title,String status,List<? extends Object> rows,String detail) {
        var html = page("Execution results: "+title);
        html.append("<p>Final status: <strong>").append(escape(status)).append("</strong></p><p>").append(escape(detail))
            .append("</p><p><a href=\"preflight.html\">Initial test design preflight</a></p><table><tr><th>Result / provenance / diagnostics</th></tr>");
        for(var row:rows) {
            html.append("<tr><td>");
            if(row instanceof Map<?,?> fields) {
                for(var entry:fields.entrySet()) html.append("<p><strong>").append(escape(entry.getKey())).append("</strong>: ")
                    .append(escape(entry.getValue() instanceof Map<?,?> || entry.getValue() instanceof Collection<?> ? Json.string(entry.getValue()) : entry.getValue())).append("</p>");
            } else html.append("<pre>").append(escape(Json.string(row))).append("</pre>");
            html.append("</td></tr>");
        }
        html.append("</table></body></html>");
        publishSummary(root,html.toString());
    }
    private static void publishSummary(Path root,String html) {
        publish(root,"results.html",html);
        Json.write(root.resolve("summary-generation.json"),Map.of("schemaVersion",1,"sha256",Json.hash(html.getBytes(StandardCharsets.UTF_8))));
        // Keep previously verified Allure publication intact when rebuilding fails.
        if(!Files.exists(root.resolve("allure-generation.json"))) publish(root,"index.html",html);
    }
    public static void render(Path root) {
        try { AllureReports.generate(root); }
        catch(RuntimeException error) {
            try { failed(root,error); } catch(Exception secondary) { error.addSuppressed(secondary); }
            throw error;
        }
    }
    public static void failed(Path root,Throwable error) {
        try {
            String html=Files.readString(root.resolve("results.html"));
            html=html.replaceFirst("<strong>[^<]*</strong>","<strong>BLOCKED</strong>")
                .replace("</body>","<p>Execution/report finalization failed: "+escape(PreflightReport.code(error))+". Raw execution results are retained; this run is not successful.</p></body>");
            publishSummary(root,html);
        } catch(java.io.IOException e) { throw new Failure("REPORT_GENERATION_FAILED","Cannot update final HTML status"); }
    }
    public static void verify(Path root) {
        if(!Files.exists(root.resolve("summary-generation.json"))) return;
        var receipt=Json.read(root.resolve("summary-generation.json"),Map.class);
        try { Failure.require(Objects.equals(receipt.get("schemaVersion"),1) && Objects.equals(receipt.get("sha256"),Json.hash(Files.readAllBytes(SafePaths.inside(root,"results.html")))),"REPORT_RUN_MISMATCH","Execution summary HTML integrity mismatch"); }
        catch(java.io.IOException e) { throw new Failure("REPORT_RUN_MISMATCH","Missing execution summary HTML"); }
    }
    static String escape(Object value) {
        return Objects.toString(value,"").replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");
    }
    static void publish(Path root,String filename,String content) {
        Path temporary = null;
        try {
            Path path = SafePaths.inside(root,filename);Files.createDirectories(path.getParent());
            temporary = Files.createTempFile(path.getParent(),".html-summary-",".tmp");Files.writeString(temporary,content,StandardCharsets.UTF_8);
            try { Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch(AtomicMoveNotSupportedException e) { Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING); }
        } catch(java.io.IOException e) { throw new Failure("REPORT_GENERATION_FAILED","Cannot publish standalone HTML summary"); }
        finally { if(temporary!=null) try {Files.deleteIfExists(temporary);}catch(java.io.IOException ignored) {} }
    }
}
