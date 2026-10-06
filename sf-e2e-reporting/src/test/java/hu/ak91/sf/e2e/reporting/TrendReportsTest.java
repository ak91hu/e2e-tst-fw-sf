package hu.ak91.sf.e2e.reporting;
import hu.ak91.sf.e2e.core.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TrendReportsTest {
    @TempDir Path root;
    Path fixture(String id,int duration,String context,int calls) throws Exception {
        Path run=root.resolve(id);Files.createDirectories(run.resolve("allure-report"));String html="<!doctype html>Internal report receipt";Files.writeString(run.resolve("index.html"),html);Files.writeString(run.resolve("allure-report/index.html"),html);Json.write(run.resolve("allure-generation.json"),Map.of("engine","Allure","status","PASSED","sha256",Json.hash(html.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        var state=new LinkedHashMap<String,Object>();state.put("schemaVersion",1);state.put("runId",id);state.put("attemptId",id);state.put("caseId","TST_contract");state.put("persona","default");state.put("mode","replay");state.put("trendContext",Json.hash(context));state.put("trendScope",Json.hash("reviewed-steps"));state.put("status","FAILED");state.put("code","ASSERTION_FAILED");state.put("startedAt",Instant.parse("2026-10-06T00:00:00Z").plusSeconds(Integer.parseInt(id.substring(1))));state.put("completedAt",((Instant)state.get("startedAt")).plusMillis(duration));state.put("modelCalls",calls);state.put("artifacts",List.of());state.put("events",List.of(Map.of("kind","locator-retry"),Map.of("kind","locator-healed")));Json.write(run.resolve("case-report.json"),state);return run;
    }
    @Test void nearestRankPercentilesRejectInvalidSamplesAndPreserveMissingValues() {assertEquals(new TrendReports.Percentiles(3,20.0,30.0),TrendReports.percentiles(List.of(30.0,10.0,20.0)));assertNull(TrendReports.percentiles(List.of()).p95());assertThrows(Failure.class,() -> TrendReports.percentiles(List.of(Double.NaN)));}
    @Test void automaticCaptureComparesSameContextAndKeepsFailuresVisible() throws Exception {
        TrendReports.automatic(fixture("r1",10,"same",0));TrendReports.automatic(fixture("r2",20,"same",0));Path latest=fixture("r3",30,"same",0);var summary=TrendReports.capture(latest,50);
        assertEquals(3,summary.get("samples"));assertEquals(new TrendReports.Percentiles(3,20.0,30.0),((Map<?,?>)summary.get("allOutcomePercentiles")).get("durationMs"));assertEquals(0,((TrendReports.Percentiles)((Map<?,?>)summary.get("passedRunPercentiles")).get("durationMs")).samples());assertTrue(Files.readString(latest.resolve("trends.html")).contains("Metric over comparable completed runs"));assertEquals(3,TrendReports.capture(latest,50).get("samples"));
    }
    @Test void unknownAiUsageStaysUnknownAndDifferentContextsNeverMix() throws Exception {
        TrendReports.capture(fixture("r1",10,"other-org",0),50);Path run=fixture("r2",20,"org",2);var summary=TrendReports.capture(run,50);assertEquals(1,summary.get("samples"));assertEquals(1,summary.get("excludedRuns"));var metric=TrendReports.read(run);assertNull(metric.values().get("estimatedCostUsd"));assertNull(metric.values().get("chargedTokens"));assertEquals(2.0,metric.values().get("modelCalls"));
    }
    @Test void differentParameterSetsCannotShareAPerformanceBaseline() throws Exception {
        for(String id:List.of("r1","r2")) {Path run=fixture(id,10,"same",0);var state=Json.read(run.resolve("case-report.json"),Map.class);state.put("trendParametersHash",Json.hash(id));Json.write(run.resolve("case-report.json"),state);var summary=TrendReports.capture(run,50);assertEquals(1,summary.get("samples"));}
    }
    @Test void changedSourcesAndCorruptMetricChecksumsAreExcluded() throws Exception {
        Path prior=fixture("r1",10,"same",0);TrendReports.capture(prior,50);Files.writeString(prior.resolve("case-report.json"),"{}");Path latest=fixture("r2",20,"same",0);assertEquals(1,TrendReports.capture(latest,50).get("samples"));
        var doc=Json.read(latest.resolve("run-metrics.json"),Map.class);doc.put("checksum","changed");Json.write(latest.resolve("run-metrics.json"),doc);assertEquals("TREND_INVALID",assertThrows(Failure.class,() -> TrendReports.read(latest)).code());
    }
    @Test void observationalFailureCannotRewriteVerdictOrAllureEvidence() throws Exception {
        Path run=fixture("r1",10,"same",0);String original=Files.readString(run.resolve("case-report.json"));Json.write(run.resolve("render-timing.json"),Map.of("durationNanos",-1));TrendReports.automatic(run);assertEquals(original,Files.readString(run.resolve("case-report.json")));AllureReports.verify(run);assertEquals("UNAVAILABLE",Json.read(run.resolve("trend-error.json"),Map.class).get("status"));assertThrows(Failure.class,() -> TrendReports.capture(run,0));
    }
}
