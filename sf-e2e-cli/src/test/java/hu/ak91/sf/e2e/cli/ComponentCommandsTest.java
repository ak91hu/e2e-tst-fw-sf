package hu.ak91.sf.e2e.cli;

import hu.ak91.sf.e2e.core.*;
import hu.ak91.sf.e2e.reporting.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Adapter fault fixtures, separate from consumer-owned LWC business checks. */
class ComponentCommandsTest {
    @TempDir Path root;
    ComponentCommands.Plan plan;
    Path planFile,project;
    void fixture(String status,int exit) throws Exception {
        project=root.resolve("project");Path pkg=project.resolve("node_modules/@salesforce/sfdx-lwc-jest");Files.createDirectories(pkg);
        Json.write(project.resolve("sfdx-project.json"),Map.of());Json.write(project.resolve("package.json"),Map.of("devDependencies",Map.of("@salesforce/sfdx-lwc-jest","1.2.3")));
        Json.write(project.resolve("package-lock.json"),Map.of("packages",Map.of("",Map.of("devDependencies",Map.of("@salesforce/sfdx-lwc-jest","1.2.3")),"node_modules/@salesforce/sfdx-lwc-jest",Map.of("version","1.2.3"))));
        Json.write(pkg.resolve("package.json"),Map.of("name","@salesforce/sfdx-lwc-jest","version","1.2.3","bin",Map.of("sfdx-lwc-jest","bin.cjs")));
        Files.writeString(pkg.resolve("bin.cjs"),"const args=process.argv.slice(2);const Reporter=require(args[args.lastIndexOf('--reporters')+1]);new Reporter().onRunComplete(null,{numFailedTestSuites:0,testResults:[{testFilePath:args.at(-1),testResults:"+(status.equals("empty")?"[]":"[{fullName:'adapter contract',status:'"+status+"',duration:3,failureMessages:['PRIVATE_EXPECTATION']}]")+"}]});process.exit("+exit+");");
        String test="force-app/main/default/lwc/probe/__tests__/probe.test.js";Path file=project.resolve(test);Files.createDirectories(file.getParent());Files.writeString(file,"// Internal adapter fixture");
        String nodeVersion=ReleaseCommands.version(List.of("node","--version"),root,10,List.of());
        var provenance=new ExternalLayers.Provenance("internal-component","component","local","sfdx-lwc-jest","node="+nodeVersion+";sfdx-lwc-jest=1.2.3","internal-fixture",Json.hash("metadata"));
        plan=new ComponentCommands.Plan(1,"project","node",nodeVersion,"1.2.3",List.of(test),10,provenance);planFile=root.resolve("components.json");Json.write(planFile,plan);
    }
    Path report(Path reports) throws Exception {try(var paths=Files.list(reports)) {return paths.sorted().reduce((a,b) -> b).orElseThrow();}}
    @Test void concreteAdapterProducesAllurePrivateEvidenceAndComparableTrends() throws Exception {
        fixture("passed",0);Path reports=root.resolve("report");for(int i=0;i<2;i++) assertEquals(0,new CommandLine(new ComponentCommands()).execute("--plan",planFile.toString(),"--report-root",reports.toString()));Path latest=report(reports);ExternalLayers.verify(latest);assertEquals(2,Json.read(latest.resolve("trend-summary.json"),Map.class).get("samples"));assertEquals(0.0,TrendReports.read(latest).values().get("modelCalls"));
        var layer=ExternalLayers.read(latest).getFirst();assertTrue(layer.tests().getFirst().name().startsWith("TST_"));assertTrue(Files.isRegularFile(latest.resolve("private-components/invocation.json")));
        try(var files=Files.list(latest.resolve("allure-results"))) {for(Path file:files.toList()) assertFalse(Files.readString(file).contains("PRIVATE_EXPECTATION"));}
    }
    @Test void failedSkippedEmptyAndNonzeroExitChecksNeverPass() throws Exception {
        fixture("failed",0);Path reports=root.resolve("reports");assertEquals(1,new CommandLine(new ComponentCommands()).execute("--plan",planFile.toString(),"--report-root",reports.toString()));assertEquals("FAILED",ExternalLayers.read(report(reports)).getFirst().status());
        Path bin=project.resolve("node_modules/@salesforce/sfdx-lwc-jest/bin.cjs");String original=Files.readString(bin);
        for(String status:List.of("pending","empty","exit")) {Files.writeString(bin,status.equals("empty")?original.replace("[{fullName:'adapter contract',status:'failed',duration:3,failureMessages:['PRIVATE_EXPECTATION']}]","[]"):status.equals("exit")?original.replace("status:'failed'","status:'passed'").replace("process.exit(0)","process.exit(7)"):original.replace("status:'failed'","status:'pending'"));assertEquals(1,new CommandLine(new ComponentCommands()).execute("--plan",planFile.toString(),"--report-root",reports.toString()),status);ExternalLayers.verify(report(reports));}
    }
    @Test void wrongVersionCannotDispatchAndIncompletePlanCannotCreateReport() throws Exception {
        fixture("passed",0);var provenance=plan.provenance();var wrong=new ExternalLayers.Provenance(provenance.target(),provenance.environment(),provenance.release(),provenance.runner(),"node=v0.0.0;sfdx-lwc-jest=1.2.3",provenance.commit(),provenance.metadataHash());Json.write(planFile,new ComponentCommands.Plan(1,"project","node","v0.0.0","1.2.3",plan.tests(),10,wrong));Path reports=root.resolve("reports");assertEquals(1,new CommandLine(new ComponentCommands()).execute("--plan",planFile.toString(),"--report-root",reports.toString()));assertFalse(Files.exists(report(reports).resolve("private-components/results.xml")));assertEquals("BLOCKED",ExternalLayers.read(report(reports)).getFirst().tests().getFirst().status());assertThrows(Failure.class,() -> new ComponentCommands.Plan(1,"project","node",plan.expectedNodeVersion(),"1.2.3",List.of(),10,provenance));
    }
    @Test void nativeComponentLayerCombinesWithApiAndPreservesBothVerdicts() throws Exception {
        fixture("passed",0);var support=new ReleaseCommandsTest();support.root=root;
        Path matrix=root.resolve("matrix.json"),reports=root.resolve("reports");Json.write(matrix,new ReleaseCommands.Matrix(1,List.of(
            new ReleaseCommands.Entry("components","jest",plan.provenance(),null,null,null,null,null,0,List.of(),"components.json"),
            new ReleaseCommands.Entry("api","api",support.provenance("api","scratch"),".",support.command("fail-exit","api.xml"),support.command("version"),"probe-v1","api.xml",10,List.of()))));
        assertEquals(1,new CommandLine(new ReleaseCommands()).execute("--matrix",matrix.toString(),"--report-root",reports.toString()));Path report=report(reports);ExternalLayers.verify(report);assertEquals(List.of("PASSED","FAILED"),ExternalLayers.read(report).stream().map(ExternalLayers.Layer::status).toList());assertTrue(Files.isRegularFile(report.resolve("trends.html")));
        assertThrows(Failure.class,() -> new ReleaseCommands.Entry("components","jest",plan.provenance(),".",null,null,null,null,0,List.of(),"components.json"));
    }
}
