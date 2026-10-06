package hu.ak91.sf.e2e.cli;
import hu.ak91.sf.e2e.reporting.TrendReports;
import picocli.CommandLine.*;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name="trends",mixinStandardHelpOptions=true,description="Rebuild bounded context-separated duration/retry/healing/AI trends from retained run evidence.")
public final class TrendCommands implements Callable<Integer> {
    @Option(names="--run",required=true) Path root;
    @Option(names="--window",defaultValue="50") int window;
    public Integer call() {var summary=TrendReports.capture(root,window);System.out.println("Trend samples="+summary.get("samples")+"; dashboard="+root.resolve("trends.html"));return 0;}
}
