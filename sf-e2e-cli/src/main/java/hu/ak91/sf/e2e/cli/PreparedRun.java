package hu.ak91.sf.e2e.cli;

import hu.ak91.sf.e2e.core.*;
import hu.ak91.sf.e2e.reporting.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;

/** Reserve evidence before loading consumer inputs; preparation never starts a browser/model. */
final class PreparedRun {
    final Path root;
    final PreflightReport report;
    private boolean ready;
        private PreparedRun(FrameworkConfig config,String workflow) {
        root=ReportDirectories.create(Path.of(config.report().root()),java.time.Instant.now());
        report=new PreflightReport(root,workflow);
        System.out.println("Test design preflight: "+root.resolve("preflight.html"));
    }
    void ready() { report.ready();ready=true; }
    static int execute(FrameworkConfig config,String workflow,Function<PreparedRun,Integer> work) {
        var run=new PreparedRun(config,workflow);
        try { return work.apply(run); }
        catch(RuntimeException failure) {
            try {
                if(!run.ready) { run.report.blocked(failure);HtmlSummary.blocked(run.root,workflow,failure); }
                else if(!Files.exists(run.root.resolve("results.html")))
                    HtmlSummary.write(run.root,workflow,"BLOCKED",List.of(Map.of("code",PreflightReport.code(failure))),
                        "Execution or report finalization failed. Actions may already have been dispatched. Retained raw results and journals establish individual outcomes; this run is not successful.");
                else if(Files.readString(run.root.resolve("results.html")).contains("Final status: <strong>PASSED</strong>"))
                    HtmlSummary.failed(run.root,failure);
            } catch(Exception reporting) { failure.addSuppressed(reporting); }
            System.out.println("Execution results: "+run.root.resolve("results.html"));throw failure;
        }
    }
}
