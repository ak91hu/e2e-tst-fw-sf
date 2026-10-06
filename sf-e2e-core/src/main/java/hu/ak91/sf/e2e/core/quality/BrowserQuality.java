package hu.ak91.sf.e2e.core.quality;

import hu.ak91.sf.e2e.core.*;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;

/** Browser defects remain fatal even when the visible UI assertion succeeds. */
public final class BrowserQuality {
    public static final Set<String> KINDS=Set.of("PAGE_ERROR","CONSOLE_ERROR","HTTP_SERVER_ERROR","REQUEST_FAILED","DIAGNOSTICS_OVERFLOW");
    public record Issue(String kind,Instant capturedAt,String origin,String path,String message,int status) {}
    private BrowserQuality() {}
    public static void validate(FrameworkConfig.Quality policy) {
        Failure.require(policy!=null && policy.maxBrowserIssues()>=1 && policy.maxBrowserIssues()<=10000 &&
            policy.maxHealingEvents()>=-1 && policy.maxLocatorRetries()>=-1 && policy.waivers().size()<=100,"CONFIG_INVALID","Invalid quality policy limits");
        for(var waiver:policy.waivers()) {
            Failure.require(waiver!=null && KINDS.contains(waiver.kind()) && !waiver.kind().equals("DIAGNOSTICS_OVERFLOW") &&
                waiver.path()!=null && waiver.path().startsWith("/") && !waiver.path().contains("?") && !waiver.path().contains("#") &&
                waiver.path().length()<=500 && waiver.messageContains()!=null && !waiver.messageContains().isBlank() && waiver.messageContains().length()<=500 &&
                waiver.reason()!=null && !waiver.reason().isBlank() && waiver.reason().length()<=1000,
                "CONFIG_INVALID","Quality waivers require an exact path, kind, reason and expiration");
            try { Failure.require(!LocalDate.parse(waiver.expiresOn()).isBefore(LocalDate.now(ZoneOffset.UTC)),"QUALITY_WAIVER_EXPIRED","A quality waiver expired; review the underlying defect"); }
            catch(Failure f) { throw f; } catch(Exception e) { throw new Failure("CONFIG_INVALID","Quality waiver expiration must be an ISO date"); }
        }
    }
    public static void verify(FrameworkConfig.Quality policy,List<Issue> issues,List<Map<String,Object>> events,Consumer<Map<String,Object>> sink) {
        validate(policy);int defects=0;
        for(var issue:issues) {
            boolean enabled=switch(issue.kind()) {
                case "PAGE_ERROR" -> policy.failOnPageErrors();case "CONSOLE_ERROR" -> policy.failOnConsoleErrors();
                case "HTTP_SERVER_ERROR" -> policy.failOnHttpServerErrors();case "REQUEST_FAILED" -> policy.failOnRequestFailures();default -> true;
            };
            var waiver=policy.waivers().stream().filter(w -> w.kind().equals(issue.kind()) && w.path().equals(issue.path()) &&
                issue.message().contains(w.messageContains())).findFirst();
            Map<String,Object> event=new LinkedHashMap<>();event.put("kind","browser-issue");event.put("issue",issue);event.put("waived",waiver.isPresent());event.put("gateEnabled",enabled);
            waiver.ifPresent(w -> event.put("waiverReason",w.reason()));sink.accept(event);
            if(enabled && waiver.isEmpty()) defects++;
        }
        long heals=events.stream().filter(e -> "locator-healed".equals(e.get("kind"))).count();
        long retries=events.stream().filter(e -> "locator-retry".equals(e.get("kind"))).count();
        boolean drift=(policy.maxHealingEvents()>=0 && heals>policy.maxHealingEvents()) || (policy.maxLocatorRetries()>=0 && retries>policy.maxLocatorRetries()) || events.stream().anyMatch(e -> "quality-module".equals(e.get("kind")) && "FAILED".equals(e.get("status")));
        sink.accept(Map.of("kind","quality-gate","status",defects>0 || drift?"FAILED":"PASSED","browserDefects",defects,"healingEvents",heals,"locatorRetries",retries));
        Failure.require(defects==0,"APPLICATION_DEFECT","Browser quality gate detected "+defects+" unwaived application error(s); inspect browser diagnostics");
        Failure.require(!drift,"QUALITY_GATE_FAILED","Locator drift/retry count exceeds the configured quality limit");
    }
}
