package hu.ak91.sf.e2e.reporting;

import hu.ak91.sf.e2e.core.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Offline feasibility receipt. Runtime values and exception messages are deliberately excluded. */
public final class PreflightReport {
    private final Path root;
    private final String workflow;
    private Instant started = Instant.now();
    private final List<Map<String,Object>> checks = new ArrayList<>();
    private final List<Map<String,Object>> designs = new ArrayList<>();
    private String status = "CHECKING";

    public PreflightReport(Path root, String workflow) {
        this.root = root; this.workflow = workflow;
        if(Files.exists(root.resolve("preflight.json"))) {
            verify(root);
            var saved=Json.read(root.resolve("preflight.json"),Map.class);
            started=Instant.parse(saved.get("startedAt").toString());status=saved.get("status").toString();
            checks.addAll(Json.MAPPER.convertValue(saved.get("checks"),new tools.jackson.core.type.TypeReference<List<Map<String,Object>>>(){}));
            designs.addAll(Json.MAPPER.convertValue(saved.get("designs"),new tools.jackson.core.type.TypeReference<List<Map<String,Object>>>(){}));
        }
        publish();
    }
    public void check(String subject, Runnable validation) {
        try {
            validation.run(); checks.add(Map.of("subject",subject,"status","VALID","code",""));
        } catch(RuntimeException failure) {
            checks.add(Map.of("subject",subject,"status","BLOCKED","code",code(failure)));
            status = "BLOCKED"; throw failure;
        }
    }
    public void design(String id, Object steps) {
        // Callers pass specs/recipes/CaseInfo metadata, never parameter maps or arbitrary user Java.
        designs.add(Map.of("id",id,"steps",steps));
    }
    public void design(String id,Object steps,Map<String,String> parameters) {
        var secrets=new SecretRegistry();parameters.values().forEach(secrets::register);
        design(id,secrets.redactTree(Json.MAPPER.valueToTree(steps)));
    }
    public void ready() {
        Failure.require(checks.stream().noneMatch(c -> "BLOCKED".equals(c.get("status"))),"REPORT_RUN_MISMATCH","Blocked checks cannot become a ready design");
        status = "READY"; publish();
    }
    public void blocked(Throwable failure) {
        status = "BLOCKED";
        if(checks.stream().noneMatch(c -> "BLOCKED".equals(c.get("status"))))
            checks.add(Map.of("subject","Preparation","status","BLOCKED","code",code(failure)));
        publish();
    }
    public static String code(Throwable failure) { return failure instanceof Failure f ? f.code() : "ENGINE_FAILURE"; }
    private void publish() {
        var html = HtmlSummary.page("Test design preflight");
        html.append("<p>Workflow: ").append(HtmlSummary.escape(workflow)).append("; started: ").append(started)
            .append("; status: <strong>").append(status).append("</strong></p>")
            .append("<p>READY means the listed offline checks passed. Current visibility, permissions, application state and business outcomes still require live verification. Future controls may appear only after earlier actions. An unresolved live target is not proof that the design is impossible.</p>")
            .append("<p>No business action is dispatched by this preflight. Runtime parameter values and raw exception messages are omitted.</p>")
            .append("<table><tr><th>Subject</th><th>Feasibility</th><th>Failure code / next action</th></tr>");
        for(var check:checks) html.append("<tr><td>").append(HtmlSummary.escape(check.get("subject"))).append("</td><td>")
            .append(HtmlSummary.escape(check.get("status"))).append("</td><td>").append(HtmlSummary.escape(check.get("code")))
            .append("BLOCKED".equals(check.get("status")) ? " — Correct the supplied design, required parameters, recipe/map compatibility or configuration before execution." : "").append("</td></tr>");
        html.append("</table><h2>Selected designs in execution order</h2>");
        for(var design:designs) html.append("<h3>").append(HtmlSummary.escape(design.get("id"))).append("</h3><pre>")
            .append(HtmlSummary.escape(Json.string(design.get("steps")))).append("</pre>");
        if(designs.isEmpty()) html.append("<p>No interpretable design is available yet. Text learning establishes its plan later; invalid inputs can prevent design loading.</p>");
        html.append("<p><a href=\"results.html\">Execution results (available after finalization)</a></p></body></html>");
        HtmlSummary.publish(root,"preflight.html",html.toString());
        Json.write(root.resolve("preflight.json"),Map.of("schemaVersion",1,"workflow",workflow,"startedAt",started,"status",status,
            "checks",checks,"designs",designs,"htmlSha256",Json.hash(html.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }
    public static void verify(Path root) {
        if(!Files.exists(root.resolve("preflight.json"))) return; // Historical reports remain compatible.
        var receipt = Json.read(root.resolve("preflight.json"),Map.class);
        try {
            Failure.require(Objects.equals(receipt.get("schemaVersion"),1) && Set.of("READY","BLOCKED","CHECKING").contains(Objects.toString(receipt.get("status"),"")) &&
                Objects.equals(receipt.get("htmlSha256"),Json.hash(Files.readAllBytes(SafePaths.inside(root,"preflight.html")))),
                "REPORT_RUN_MISMATCH","Preflight HTML integrity mismatch");
        } catch(java.io.IOException e) { throw new Failure("REPORT_RUN_MISMATCH","Missing preflight HTML"); }
    }
}
