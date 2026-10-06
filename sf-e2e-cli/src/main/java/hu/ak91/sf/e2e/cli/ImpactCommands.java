package hu.ak91.sf.e2e.cli;

import hu.ak91.sf.e2e.core.*;
import hu.ak91.sf.e2e.core.scenario.*;
import picocli.CommandLine.*;
import java.nio.file.*;
import java.util.concurrent.Callable;

@Command(name="plan-impact",mixinStandardHelpOptions=true,description="Plan reviewed change-impact selection and dependency closure without credentials, browser or AI.")
public final class ImpactCommands implements Callable<Integer> {
    @Option(names="--suite",required=true) Path suite;
    @Option(names="--coverage",required=true) Path coverage;
    @Option(names="--changes",required=true) Path changes;
    @Option(names="--output",required=true) Path output;
    public Integer call() {
        Failure.require(!Files.exists(output),"IMPACT_INVALID","Impact output already exists");
        var plan=ChangeImpact.evaluate(CaseSuite.load(suite),ChangeImpact.read(coverage,ChangeImpact.Coverage.class),ChangeImpact.read(changes,ChangeImpact.Changes.class));
        Json.write(output,plan);System.out.println("Impact "+plan.decision()+": "+plan.selected().size()+" dataset(s); plan="+output);return 0;
    }
}
