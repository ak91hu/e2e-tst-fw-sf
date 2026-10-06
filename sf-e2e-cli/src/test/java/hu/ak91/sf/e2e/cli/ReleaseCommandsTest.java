package hu.ak91.sf.e2e.cli;

import hu.ak91.sf.e2e.core.*;
import hu.ak91.sf.e2e.reporting.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReleaseCommandsTest {
    @TempDir Path root;
    public static class Probe {
        public static void main(String[] args) throws Exception {
            if(args[0].equals("version")) {System.out.println("probe-v1");return;}
            if(args[0].equals("hang")) {Thread.sleep(20000);return;}
            Files.writeString(Path.of(args[1]),"<testsuite tests='1'><testcase name='external contract' time='0.001'/></testsuite>");if(args[0].equals("fail-exit")) System.exit(7);
        }
    }
    List<String> command(String... args) {var cmd=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString(),"-cp",System.getProperty("java.class.path"),Probe.class.getName()));cmd.addAll(List.of(args));return cmd;}
    ExternalLayers.Provenance provenance(String target,String environment) {return new ExternalLayers.Provenance(target,environment,"Winter-27","probe","probe-v1","commit",Json.hash("metadata"));}
    @Test void releaseMatrixCombinesTargetsAndNonzeroRunnerExitFailsEvenWithPassingXml() throws Exception {
        Path plan=root.resolve("matrix.json");Json.write(plan,new ReleaseCommands.Matrix(1,List.of(
            new ReleaseCommands.Entry("jest","jest",provenance("preview","preview-sandbox"),".",command("pass","preview.xml"),command("version"),"probe-v1","preview.xml",5,List.of()),
            new ReleaseCommands.Entry("apex","apex",provenance("current","production-sandbox"),".",command("fail-exit","current.xml"),command("version"),"probe-v1","current.xml",5,List.of()))));
        Path reports=root.resolve("report");assertEquals(1,new CommandLine(new ReleaseCommands()).execute("--matrix",plan.toString(),"--report-root",reports.toString()));try(var paths=Files.list(reports)) {Path report=paths.findFirst().orElseThrow();ExternalLayers.verify(report);var layers=ExternalLayers.read(report);assertEquals(List.of("PASSED","FAILED"),layers.stream().map(ExternalLayers.Layer::status).toList());assertEquals(2,layers.get(1).tests().size());}
    }
    @Test void versionAndRunnerTimeoutAreBoundedAndStaleResultsCannotPass() {
        long start=System.nanoTime();assertEquals("RUNNER_VERSION_CHANGED",assertThrows(Failure.class,() -> ReleaseCommands.version(command("hang"),root,1,List.of())).code());assertTrue(System.nanoTime()-start<java.time.Duration.ofSeconds(5).toNanos());
        assertEquals("RUNNER_TIMEOUT",assertThrows(Failure.class,() -> ReleaseCommands.execute(command("hang"),root,1,List.of())).code());
    }
    @Test void staleXmlAndWrongPinnedVersionCannotStartTheTestCommand() throws Exception {
        Path output=root.resolve("results.xml"),matrix=root.resolve("matrix.json");String old="<testsuite><testcase name='stale'/></testsuite>";Files.writeString(output,old);
        var entry=new ReleaseCommands.Entry("api","api",provenance("preview","preview-sandbox"),".",command("pass","results.xml"),command("version"),"probe-v1","results.xml",5,List.of());Json.write(matrix,new ReleaseCommands.Matrix(1,List.of(entry)));
        assertEquals(1,new CommandLine(new ReleaseCommands()).execute("--matrix",matrix.toString(),"--report-root",root.resolve("reports").toString()));assertEquals(old,Files.readString(output));
        var wrong=new ExternalLayers.Provenance("preview","preview-sandbox","Winter-27","probe","probe-v2","commit",Json.hash("metadata"));Json.write(matrix,new ReleaseCommands.Matrix(1,List.of(new ReleaseCommands.Entry("api","api",wrong,".",command("pass","fresh.xml"),command("version"),"probe-v2","fresh.xml",5,List.of()))));
        assertEquals(1,new CommandLine(new ReleaseCommands()).execute("--matrix",matrix.toString(),"--report-root",root.resolve("reports").toString()));assertFalse(Files.exists(root.resolve("fresh.xml")));
    }
}
