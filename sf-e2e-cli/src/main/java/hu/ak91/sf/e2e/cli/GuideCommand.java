package hu.ak91.sf.e2e.cli;

import hu.ak91.sf.e2e.core.*;
import picocli.CommandLine.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Callable;

/** Public guides travel with the CLI so another agent can use the framework without a source checkout. */
@Command(name="guide",mixinStandardHelpOptions=true,description="Read bundled Markdown guidance offline, without configuration, credentials or a source checkout.")
public final class GuideCommand implements Callable<Integer> {
    static final Map<String,String> TOPICS=Map.ofEntries(
        Map.entry("start","README.md"),Map.entry("agents","AGENTS.md"),Map.entry("index","docs/README.md"),
        Map.entry("cases","docs/CASE_WORKFLOWS.md"),Map.entry("ai","docs/AI_WORKFLOW.md"),Map.entry("development","docs/DEVELOPMENT.md"),
        Map.entry("best-practices","docs/REFERENCE_PRACTICES.md"),Map.entry("autonomous","docs/AUTONOMOUS_WORKFLOWS.md"),Map.entry("verification","docs/VERIFICATION.md"),
        Map.entry("capabilities","docs/ADVANCED_CAPABILITIES.md"),Map.entry("reporting","docs/ALLURE_REPORTING.md"),
        Map.entry("implementation","docs/IMPLEMENTATION.md"),Map.entry("pom","docs/PAGE_OBJECT_MODEL.md"),
        Map.entry("requirements","docs/REQUIREMENTS.md"),Map.entry("dependencies","docs/DEPENDENCIES.md"),
        Map.entry("third-party","docs/THIRD_PARTY.md"),Map.entry("test-structure","docs/TEST_STRUCTURE_REVIEW.md"),
        Map.entry("salesforce-review","docs/SALESFORCE_CAPABILITY_REVIEW.md"),Map.entry("roadmap","docs/ROADMAP_IMPLEMENTATION.md"),
        Map.entry("reliability","docs/RELIABILITY_REVIEW.md"),Map.entry("quality-research","docs/QUALITY_RESEARCH.md"),
        Map.entry("efficiency","docs/EFFICIENCY.md"),Map.entry("efficiency-workflows","docs/EFFICIENCY_WORKFLOWS.md"));
    @Parameters(index="0",arity="0..1",defaultValue="start",description="Guide topic; use --list for the bundled topic/path index.") String topic;
    @Option(names="--list",description="Print the topic/path index as JSON.") boolean list;
    @Spec picocli.CommandLine.Model.CommandSpec command;
    public Integer call() {
        if(list) { command.commandLine().getOut().println(Json.string(new TreeMap<>(TOPICS)));return 0; }
        Failure.require(TOPICS.containsKey(topic),"CONFIG_INVALID","Unknown guide topic; use guide --list to select bundled guidance");
        try(var stream=GuideCommand.class.getResourceAsStream("/framework-guide/"+TOPICS.get(topic))) {
            Failure.require(stream!=null,"DOCUMENTATION_UNAVAILABLE","Bundled guide is missing; rebuild the CLI from the matching sources");
            byte[] bytes=stream.readNBytes(1024*1024+1);
            Failure.require(bytes.length<=1024*1024,"DOCUMENTATION_UNAVAILABLE","Bundled guide exceeds its size limit");
            var output=command.commandLine().getOut();output.print(new String(bytes,StandardCharsets.UTF_8));output.flush();return 0;
        } catch(Failure e) { throw e; } catch(java.io.IOException e) { throw new Failure("DOCUMENTATION_UNAVAILABLE","Cannot read bundled guide"); }
    }
}
