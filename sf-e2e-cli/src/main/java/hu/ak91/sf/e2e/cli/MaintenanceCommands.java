package hu.ak91.sf.e2e.cli;

import hu.ak91.sf.e2e.agent.CaseLearner;
import hu.ak91.sf.e2e.core.*;
import hu.ak91.sf.e2e.core.locator.LocatorStore;
import hu.ak91.sf.e2e.core.scenario.*;
import picocli.CommandLine.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;

/** Review additive changes offline, then verify a new version without editing the baseline. */
public final class MaintenanceCommands {
    private MaintenanceCommands() {}
    static class FilesOptions {
        @Option(names="--file",required=true,description="Existing checksummed recipe; never overwritten.") Path file;
        @Option(names="--changes",description="Additive schemaVersion 1 changes bound to the baseline recipe checksum. Omit for locator repair only.") Path changes;
        @Option(names="--related-recipe",description="Repeatable related recipe to identify same-context shared controls; no related case is executed.") List<Path> related=new ArrayList<>();
        CaseMaintenance.Plan plan() {
            var baseline=CaseRecipe.load(file);
            try {
                var patch=changes==null?null:Json.MAPPER.treeToValue(CaseCommands.readJson(changes),CaseMaintenance.Changes.class);
                return CaseMaintenance.plan(baseline,patch);
            } catch(Failure failure) {throw failure;}
            catch(Exception invalid) {throw new Failure("CASE_MAINTENANCE_INVALID","Invalid maintenance changes; supply schemaVersion, baselineChecksum and insertions");}
        }
        List<CaseMaintenance.SharedCase> shared(CaseMaintenance.Plan plan) {
            Failure.require(related.size()<=200,"CASE_MAINTENANCE_INVALID","Supply at most 200 related recipes");
            return plan.sharedCases(related.stream().map(CaseRecipe::load).toList());
        }
    }
    @Command(name="maintenance-plan",mixinStandardHelpOptions=true,description="Review retained/inserted steps and shared-control dependencies offline. No config, browser, model or writes.")
    public static final class Plan implements Callable<Integer> {
        @Mixin FilesOptions files;
        @Option(names="--params",description="Optional private runtime values to validate; never echoed.") Path params;
        @Spec picocli.CommandLine.Model.CommandSpec command;
        public Integer call() {
            var plan=files.plan();if(params!=null) plan.validateParams(CaseCommands.readParameters(params));
            command.commandLine().getOut().println(Json.string(Map.of("schemaVersion",1,"status","PLANNED","uiVerified",false,
                "caseId",plan.baseline().id(),"baselineChecksum",Json.hash(plan.baseline()),"changesChecksum",Json.hash(plan.changes()),
                "requiredParameters",plan.requiredParams(),"steps",plan.steps(),"potentialSharedCases",files.shared(plan),"needsLearning",plan.needsLearning())));
            return 0;
        }
    }
    @Command(name="maintain-case",mixinStandardHelpOptions=true,description="Verify a maintained recipe once, preserving old actions/oracles and reusing shared controls. Saves a new recipe and HTML design after cleanup/report validation.")
    public static final class Apply implements Callable<Integer> {
        @Mixin FilesOptions files;
        @Mixin CaseCommands.InputOptions input;
        @Option(names="--output",required=true,description="New versioned recipe path; existing artifacts are preserved.") Path output;
        public Integer call() {
            var plan=files.plan();var params=input.parameters();plan.validateParams(params);
            var shared=files.shared(plan);output=CaseCommands.preparedOutput(output);
            var config=input.options.config(plan.needsLearning()?"mapping":"deterministic");
            Failure.require(config.profile().equals(plan.needsLearning()?"mapping":"deterministic"),
                "CONFIG_INVALID","Locator-only maintenance uses deterministic; added steps use mapping");
            Failure.require(config.locators().mode().equals("read-write"),"LOCATOR_READ_ONLY","Use --locator-mode read-write for shared locator maintenance");
            String namespace=LocatorStore.namespace(config,input.persona);
            var store=new LocatorStore(Path.of(config.locators().dir()),namespace,config.locators().mode());
            plan.baseline().preflight(namespace,store,params);
            return CaseCommands.session(config,input.persona,plan.baseline().id(),plan.baseline().startRoute(),params,
                plan.needsLearning()?"maintenance-learning":"maintenance",output,(engine,disk,model,context,deadline,secrets,events) -> {
                    Failure.require(CaseRecipe.load(files.file).equals(plan.baseline()),"CASE_MAINTENANCE_CONFLICT","Baseline recipe changed after maintenance preflight");
                    events.accept(Map.of("kind","case-maintenance-dependencies","potentialSharedCases",shared,"relatedCasesExecuted",false));
                    return new CaseLearner(engine,disk,model,config,secrets,events).maintain(plan,context,params,deadline);
                });
        }
    }
}
