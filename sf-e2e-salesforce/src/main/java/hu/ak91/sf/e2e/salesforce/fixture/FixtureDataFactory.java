package hu.ak91.sf.e2e.salesforce.fixture;
import hu.ak91.sf.e2e.core.*;
import java.time.*;
import java.util.UUID;
public final class FixtureDataFactory {
    private final Clock clock;
    private final ZoneId zone;
    private final String attempt;
    public FixtureDataFactory(Clock clock,ZoneId zone,String attempt) { this.clock=clock;this.zone=zone;SafePaths.segment(attempt);String compact=attempt.replace("-","");this.attempt=compact.substring(0,Math.min(8,compact.length())); }
    public String uniqueName(String label) { return uniqueName(label,80); }
    public String uniqueName(String label,int maxLength) {
        String prefix="E2E-TA-"+label.replaceAll("[^A-Za-z0-9]","")+"-"+attempt;
        String suffix="-"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        Failure.require(maxLength>=suffix.length()+7,"VALUE_INVALID","Field too short for unique ownership identity");
        return prefix.substring(0,Math.min(prefix.length(),maxLength-suffix.length()))+suffix;
    }
    public LocalDate date(int days) { return LocalDate.now(clock.withZone(ZoneOffset.UTC)).plusDays(days); }
    public LocalDate personaToday() { return LocalDate.now(clock.withZone(zone)); }
}
