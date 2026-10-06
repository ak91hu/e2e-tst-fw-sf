package hu.ak91.sf.e2e.reporting;

import hu.ak91.sf.e2e.core.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ExternalLayersTest {
    @TempDir Path root;
    private ExternalLayers.Provenance provenance() {return new ExternalLayers.Provenance("preview","preview-sandbox","Winter-27","external-jest","30.0.0","reviewed-commit",Json.hash("metadata"));}
    @Test void aFailingLayerCannotBeHiddenByPassingLayers() throws Exception {
        Path file=root.resolve("results.xml");Files.writeString(file,"<testsuite tests='2'><testcase name='good' time='0.1'/><testcase name='bad'><failure message='PRIVATE_SENTINEL'/></testcase></testsuite>");var layer=ExternalLayers.junit(file,"lwc","jest",provenance(),new SecretRegistry());assertEquals("FAILED",layer.status());assertFalse(Json.string(layer).contains("PRIVATE_SENTINEL"));
        assertFalse(ExternalLayers.publish(root.resolve("report"),List.of(layer)));ExternalLayers.verify(root.resolve("report"));
        var report=Json.read(root.resolve("report/release-report.json"),Map.class);report.put("status","PASSED");Json.write(root.resolve("report/release-report.json"),report);assertThrows(Failure.class,() -> ExternalLayers.verify(root.resolve("report")));
    }
    @Test void unsafeEmptySkippedAndCountMismatchCannotBecomePassingEvidence() throws Exception {
        Path file=root.resolve("results.xml");for(String xml:List.of("<!DOCTYPE x [<!ENTITY a SYSTEM 'file:///private'>]><testsuite><testcase name='x'>&a;</testcase></testsuite>","<testsuite/>","<testsuite tests='2'><testcase name='x'/></testsuite>")) {Files.writeString(file,xml);assertThrows(Failure.class,() -> ExternalLayers.junit(file,"api","api",provenance(),new SecretRegistry()));}
        Files.writeString(file,"<testsuite><testcase name='skipped'><skipped/></testcase></testsuite>");assertEquals("FAILED",ExternalLayers.junit(file,"api","api",provenance(),new SecretRegistry()).status());
    }
    @Test void backendAndDownloadedContentDefectsAreReportedAsFailedAssertions() {
        for(String code:List.of("API_ASSERTION_FAILED","FILE_VERIFICATION_FAILED")) {Path report=root.resolve(code);String attempt=UUID.randomUUID().toString();var state=new LinkedHashMap<String,Object>();state.put("schemaVersion",1);state.put("runId","run");state.put("attemptId",attempt);state.put("caseId","consumer-case");state.put("mode","replay");state.put("startedAt",java.time.Instant.now());state.put("completedAt",java.time.Instant.now());state.put("status","FAILED");state.put("code",code);state.put("artifacts",List.of());WorkflowReportExporter.write(report,state,List.of(),false);assertEquals("failed",Json.read(report.resolve("allure-results/"+attempt+"-result.json"),Map.class).get("status"));}
    }
}
