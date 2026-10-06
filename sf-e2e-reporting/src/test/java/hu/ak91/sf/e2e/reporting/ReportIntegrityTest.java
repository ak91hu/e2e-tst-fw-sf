package hu.ak91.sf.e2e.reporting;

import hu.ak91.sf.e2e.core.*;
import hu.ak91.sf.e2e.salesforce.fixture.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

class ReportIntegrityTest {
    @TempDir Path root;
    static class Engine implements UiEngine {
        private final byte[] png;
        Engine() throws Exception { var image=new BufferedImage(12,12,BufferedImage.TYPE_INT_RGB);var output=new ByteArrayOutputStream();ImageIO.write(image,"PNG",output);png=output.toByteArray(); }
        public void open(String route,Deadline d) {}
        public String route() { return "about:blank"; }
        public Observation observe(String c,Deadline d) { throw new UnsupportedOperationException(); }
        public Observation.Node resolve(SemanticTarget t,Observation o) { throw new UnsupportedOperationException(); }
        public void perform(Action a,Observation o,Deadline d) {}
        public Map<String,String> readVisibleFields(Deadline d) { return Map.of(); }
        public byte[] captureMaskedPng(Deadline d) { return png.clone(); }
        public void poll(Deadline d) {}
        public String capabilityVersion() { return "fake-capture"; }
        public void close() {}
    }
    private AttemptResult result(String id,String status,int captures,boolean record) throws Exception {
        String run="run",attempt="attempt-"+id;
        var secrets=new SecretRegistry();var engine=new Engine();
        var evidence=new EvidenceService(root,run,attempt,() -> engine,Deadline.after(Duration.ofSeconds(5)),secrets);
        var config=FrameworkConfig.defaults();
        config=new FrameworkConfig(1,config.projectId(),config.profile(),new FrameworkConfig.Org("org","developer-edition","https://org.develop.my.salesforce.com",List.of(),1,""),config.ui(),config.auth(),config.personas(),config.execution(),config.agent(),config.cache(),new FrameworkConfig.Retention("permanent",root.resolve("journals").toString()),config.evidence(),config.report());
        var registry=new RecordRegistry(config,run,attempt,id);
        List<String> refs=new ArrayList<>();for(int i=0;i<captures;i++) refs.add(evidence.checkpoint(i==captures-1?"test-completion":"form-before-save"));
        if(record) {
            var owned=registry.claim("Opportunity","E2E-TA-Exact-name",null,Persona.SALES);registry.prepared(owned);registry.saveRequested(owned);
            registry.saved(owned,"006000000000001",refs.getFirst());registry.verified(owned,owned.name(),owned.marker,refs.getLast());
        }
        Json.write(root.resolve("attempts/"+attempt+"/retained-records.json"),registry.manifest());
        var info=new CaseInfo(id,"A <quoted> & Unicode őű case",Persona.SALES,List.of("opportunity"),List.of(),"Example#method");
        return new AttemptResult(run,attempt,info,Instant.now(),Instant.now(),status.equals("PASSED")?"PASSED":"FAILED",status.equals("PASSED")?"PASSED":"NOT_REQUIRED",
            status,status.equals("PASSED")?null:"VALUE_MISMATCH",status.equals("PASSED")?null:"Expected <value> & failed",List.of(),evidence.artifacts(),evidence.events(),registry.manifest());
    }
    private RunReport report(AttemptResult... results) {
        return new RunReport(1,"run",Instant.now(),Instant.now(),Arrays.stream(results).map(AttemptResult::caseInfo).toList(),List.of(results),true,RunReport.exitCode(List.of(results)));
    }
    @Test void verifiedReportRoundTripsAndExportsMatchingCountsLinksAndAttachments() throws Exception {
        var report=report(result("SF-OPP-001","PASSED",2,true),result("SF-OPP-002","FAILED",1,false));
        ReportIntegrityVerifier.verify(root,report,2);ReportExporter.write(root,report);
        var restored=Json.read(root.resolve("run-report.json"),RunReport.class);assertEquals(report.counts(),restored.counts());assertEquals(1,restored.exitCode());
        var factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        var xml=factory.newDocumentBuilder().parse(root.resolve("junit.xml").toFile());assertEquals("2",xml.getDocumentElement().getAttribute("tests"));
        assertEquals(2,xml.getElementsByTagName("testcase").getLength());assertEquals(1,xml.getElementsByTagName("failure").getLength());
        var allure=Json.read(root.resolve("allure-results/attempt-SF-OPP-001-result.json"),Map.class);assertEquals("passed",allure.get("status"));
        assertTrue(Files.readString(root.resolve("summary.html")).contains("006000000000001"));assertTrue(Files.readString(root.resolve("junit.xml")).contains("&lt;value&gt;"));
        assertEquals(Files.readString(root.resolve("allure-report/index.html")),Files.readString(root.resolve("index.html")));
    }
    @Test void passedResultNeedsTwoCapturesAndCompletion() throws Exception {
        assertEquals("EVIDENCE_INCOMPLETE",assertThrows(Failure.class,() -> ReportIntegrityVerifier.verify(root,report(result("SF-OPP-001","PASSED",1,false)),2)).code());
    }
    @Test void disabledUnstartedCaseCanSkipWithoutJournalButStartedCaseCannot() {
        var info=new CaseInfo("DISABLED","Disabled",Persona.DEFAULT,List.of(),List.of(),"Example#disabled");
        var skipped=new AttemptResult("run","disabled",info,null,Instant.now(),"NOT_RUN","NOT_REQUIRED","SKIPPED","TEST_DISABLED","Disabled",
            List.of(),List.of(),List.of(),null);
        assertDoesNotThrow(() -> ReportIntegrityVerifier.verify(root,report(skipped),2));
        var started=new AttemptResult("run","started",info,Instant.now(),Instant.now(),"SKIPPED","NOT_REQUIRED","SKIPPED","TEST_ABORTED","Aborted",
            List.of(),List.of(),List.of(),null);
        assertEquals("RETENTION_VIOLATION",assertThrows(Failure.class,() -> ReportIntegrityVerifier.verify(root,report(started),2)).code());
    }
    @Test void foreignRunAndDuplicateCaseCannotBeMergedIntoSuccessfulRun() throws Exception {
        var one=result("SF-OPP-001","PASSED",2,false);
        assertThrows(Failure.class,() -> ReportIntegrityVerifier.verify(root,report(one,one),2));
        var wrong=new RunReport(1,"different",Instant.now(),Instant.now(),List.of(one.caseInfo()),List.of(one),true,0);
        assertThrows(Failure.class,() -> ReportIntegrityVerifier.verify(root,wrong,2));
    }
    @Test void corruptPngAndHashMismatchFailIntegrity() throws Exception {
        var one=result("SF-OPP-001","PASSED",2,false);Path png=root.resolve(one.artifacts().getFirst().path());Files.write(png,new byte[]{1,2,3});
        assertEquals("PNG_INVALID",assertThrows(Failure.class,() -> ReportIntegrityVerifier.verify(root,report(one),2)).code());
    }
    @Test void missingOrAlteredRetainedRecordManifestFailsIntegrity() throws Exception {
        var one=result("SF-OPP-001","PASSED",2,true);Files.writeString(root.resolve("attempts/"+one.attemptId()+"/retained-records.json"),"{}");
        assertThrows(Failure.class,() -> ReportIntegrityVerifier.verify(root,report(one),2));
    }
    @Test void reporterRedactsSentinelsBeforeAnyPublicEventSink() throws Exception {
        var secrets=new SecretRegistry();secrets.register("PRIVATE_SENTINEL");var engine=new Engine();
        var evidence=new EvidenceService(root,"run","attempt",() -> engine,Deadline.after(Duration.ofSeconds(3)),secrets);
        evidence.event(Map.of("reason","PRIVATE_SENTINEL","route","https://org.salesforce.com/?otp=PRIVATE_OTHER"));
        assertFalse(Files.readString(root.resolve("attempts/attempt/events.redacted.jsonl")).contains("PRIVATE"));
        assertFalse(Json.string(evidence.events()).contains("PRIVATE"));
    }
    @Test void exitPriorityKeepsBusinessFailuresBelowIntegrityAndCancellation() throws Exception {
        var failed=result("SF-OPP-001","FAILED",0,false);
        var integrity=new AttemptResult("run","attempt-error",failed.caseInfo(),Instant.now(),Instant.now(),"PASSED","FAILED","EVIDENCE_FAILED","PNG_INVALID","Corrupt",List.of(),List.of(),List.of(),null);
        assertEquals(4,RunReport.exitCode(List.of(failed,integrity)));
        var cancelled=new AttemptResult("run","attempt-cancel",failed.caseInfo(),Instant.now(),Instant.now(),"NOT_RUN","NOT_REQUIRED","CANCELLED","CANCELLED","Stopped",List.of(),List.of(),List.of(),null);
        assertEquals(130,RunReport.exitCode(List.of(failed,integrity,cancelled)));
    }
}
