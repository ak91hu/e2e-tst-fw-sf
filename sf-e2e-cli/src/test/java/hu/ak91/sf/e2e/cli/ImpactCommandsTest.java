package hu.ak91.sf.e2e.cli;

import hu.ak91.sf.e2e.core.*;
import hu.ak91.sf.e2e.core.scenario.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ImpactCommandsTest {
    @TempDir Path root;
    SuiteCommandsTest fixture;
    Path coverage,changes;
    void prepare(List<String> files) throws Exception {
        fixture=new SuiteCommandsTest();fixture.root=root;fixture.prepare();
        Json.write(fixture.manifest,new CaseSuite(1,"regression",List.of(
            new CaseSuite.Row("first","recipe.json",null,null,List.of()),
            new CaseSuite.Row("second","recipe.json",null,null,List.of(),List.of("first"),Set.of()),
            new CaseSuite.Row("unrelated","missing.json",null,null,List.of()))));
        coverage=root.resolve("coverage.json");changes=root.resolve("changes.json");
        Json.write(coverage,new ChangeImpact.Coverage(1,"reviewed-v1",List.of("shared/**"),Set.of(),List.of(
            new ChangeImpact.Rule("first",List.of("setup/**"),Set.of("first")),
            new ChangeImpact.Rule("second",List.of("components/**"),Set.of("second")),
            new ChangeImpact.Rule("unrelated",List.of("other/**"),Set.of("unrelated")))));
        Json.write(changes,new ChangeImpact.Changes(1,files));
    }
    SuiteCommands.Run command(String... extra) {
        var command=new SuiteCommands.Run();var args=new ArrayList<>(List.of("--config",fixture.configFile.toString(),"--suite",fixture.manifest.toString(),"--coverage",coverage.toString(),"--changes",changes.toString()));args.addAll(List.of(extra));new CommandLine(command).parseArgs(args.toArray(String[]::new));return command;
    }
    @Test void selectedReplayIncludesSetupAndPersistsPlanWithoutReadingUnselectedRecipes() throws Exception {
        prepare(List.of("components/widget.html"));var command=command();var executed=new ArrayList<String>();command.executor=(config,row,path) -> {executed.add(row.row().id());fixture.state(path,row,"PASSED",null);return 0;};
        assertEquals(0,command.call());assertEquals(List.of("first","second"),executed);
        var plan=Json.read(fixture.runRoot().resolve("impact-plan.json"),ChangeImpact.Plan.class);assertEquals("IMPACTED",plan.decision());assertEquals(executed,plan.selected());
        assertTrue(Files.isRegularFile(fixture.runRoot().resolve("trends.html")));
    }
    @Test void unknownChangesRequireFullPreflightBeforeAnyExecution() throws Exception {
        prepare(List.of("unknown.xml"));var command=command();command.executor=(config,row,path) -> {fail("Invalid full-suite preflight must stop dispatch");return 0;};assertThrows(Failure.class,command::call);assertFalse(Files.exists(root.resolve("report")));
    }
    @Test void noChangesCreateAnExplicitOfflinePlanAndCannotBecomeAGreenExecution() throws Exception {
        prepare(List.of());Path output=root.resolve("impact.json");assertEquals(0,new CommandLine(new ImpactCommands()).execute("--suite",fixture.manifest.toString(),"--coverage",coverage.toString(),"--changes",changes.toString(),"--output",output.toString()));assertEquals("NO_CHANGES",Json.read(output,ChangeImpact.Plan.class).decision());
        assertEquals("NO_TESTS",assertThrows(Failure.class,command()::call).code());assertFalse(Files.exists(root.resolve("report")));String saved=Files.readString(output);assertNotEquals(0,new CommandLine(new ImpactCommands()).execute("--suite",fixture.manifest.toString(),"--coverage",coverage.toString(),"--changes",changes.toString(),"--output",output.toString()));assertEquals(saved,Files.readString(output));
    }
    @Test void manualFiltersCannotNarrowSafeImpactFallback() throws Exception {prepare(List.of("unknown.xml"));assertEquals("IMPACT_INVALID",assertThrows(Failure.class,command("--dataset","first")::call).code());assertFalse(Files.exists(root.resolve("report")));}
}
