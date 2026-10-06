package hu.ak91.sf.e2e.core;

import hu.ak91.sf.e2e.core.quality.BrowserQuality;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BrowserQualityTest {
    private BrowserQuality.Issue issue(String kind,String path,String message) { return new BrowserQuality.Issue(kind,Instant.now(),"https://application.example",path,message,500); }
    @Test void eachApplicationErrorFailsAnOtherwiseSuccessfulRun() {
        for(String kind:List.of("PAGE_ERROR","CONSOLE_ERROR","HTTP_SERVER_ERROR","REQUEST_FAILED","DIAGNOSTICS_OVERFLOW")) {
            List<Map<String,Object>> events=new ArrayList<>();
            assertEquals("APPLICATION_DEFECT",assertThrows(Failure.class,() -> BrowserQuality.verify(FrameworkConfig.Quality.defaults(),List.of(issue(kind,"/app","Defect")),List.of(),events::add)).code());
            assertEquals("FAILED",events.getLast().get("status"));assertEquals(1,events.getLast().get("browserDefects"));
        }
    }
    @Test void waiversAreExactScopedAndDoNotHideAnotherDefect() {
        var waiver=new FrameworkConfig.IssueWaiver("PAGE_ERROR","/app","Known ticket", "Tracked defect T-123",LocalDate.now(ZoneOffset.UTC).plusDays(7).toString());
        var policy=new FrameworkConfig.Quality(true,true,true,true,200,-1,-1,List.of(waiver));List<Map<String,Object>> log=new ArrayList<>();
        BrowserQuality.verify(policy,List.of(issue("PAGE_ERROR","/app","Known ticket 123")),List.of(),log::add);assertEquals(true,log.getFirst().get("waived"));
        for(var other:List.of(issue("PAGE_ERROR","/other","Known ticket 123"),issue("CONSOLE_ERROR","/app","Known ticket 123"),issue("PAGE_ERROR","/app","Unexpected exception")))
            assertThrows(Failure.class,() -> BrowserQuality.verify(policy,List.of(other),List.of(),e -> {}));
    }
    @Test void expiredOrBlanketWaiversAndUnboundedPoliciesAreRejected() {
        var expired=new FrameworkConfig.IssueWaiver("PAGE_ERROR","/app","Known","Ticket",LocalDate.now(ZoneOffset.UTC).minusDays(1).toString());
        assertEquals("QUALITY_WAIVER_EXPIRED",assertThrows(Failure.class,() -> BrowserQuality.validate(new FrameworkConfig.Quality(true,true,true,true,200,-1,-1,List.of(expired)))).code());
        var broad=new FrameworkConfig.IssueWaiver("PAGE_ERROR","/app","","Ticket",LocalDate.now(ZoneOffset.UTC).plusDays(1).toString());
        assertThrows(Failure.class,() -> BrowserQuality.validate(new FrameworkConfig.Quality(true,true,true,true,200,-1,-1,List.of(broad))));
        assertThrows(Failure.class,() -> BrowserQuality.validate(new FrameworkConfig.Quality(true,true,true,true,0,-1,-1,List.of())));
    }
    @Test void driftLimitsFailEvenAfterHealingSucceededAndDisabledErrorGatesStillLogEvidence() {
        var policy=new FrameworkConfig.Quality(false,false,false,false,200,0,1,List.of());List<Map<String,Object>> log=new ArrayList<>();
        BrowserQuality.verify(policy,List.of(issue("PAGE_ERROR","/app","Defect")),List.of(),log::add);assertEquals(false,log.getFirst().get("gateEnabled"));
        assertEquals("QUALITY_GATE_FAILED",assertThrows(Failure.class,() -> BrowserQuality.verify(policy,List.of(),List.of(Map.of("kind","locator-healed")),e -> {})).code());
        assertThrows(Failure.class,() -> BrowserQuality.verify(policy,List.of(),List.of(Map.of("kind","locator-retry"),Map.of("kind","locator-retry")),e -> {}));
        assertThrows(Failure.class,() -> BrowserQuality.verify(policy,List.of(issue("DIAGNOSTICS_OVERFLOW","/app","Overflow")),List.of(),e -> {}));
    }
}
