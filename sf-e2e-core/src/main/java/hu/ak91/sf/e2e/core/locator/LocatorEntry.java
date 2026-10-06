package hu.ak91.sf.e2e.core.locator;

import hu.ak91.sf.e2e.core.*;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Persistent descriptions only: never DOM node IDs, field values or model conversations. */
public record LocatorEntry(String page, String key, String route, String engineVersion,
                           SemanticTarget primary, Map<String,String> attributes,
                           List<SemanticTarget> alternatives, long revision, List<Change> history) {
    public record Change(Instant at, String reason, SemanticTarget before, SemanticTarget after) {}
    public LocatorEntry {
        Failure.require(page != null && page.matches("[a-zA-Z0-9_.-]{1,100}") && key != null && key.matches("[a-zA-Z0-9_.-]{1,100}"),
            "LOCATOR_INVALID", "Page and locator keys must be stable identifiers (1..100 characters)");
        Failure.require(primary != null && route != null && engineVersion != null && revision > 0,
            "LOCATOR_INVALID", "Incomplete locator entry");
        attributes=Map.copyOf(attributes); alternatives=List.copyOf(alternatives); history=List.copyOf(history);
        Failure.require(alternatives.size()<=8 && history.size()<=50 && attributes.size()<=12,
            "LOCATOR_INVALID", "Locator limits exceeded");
    }
    public static String routeKey(String route) {
        URI uri=URI.create(route.replace("[REDACTED]","%5BREDACTED%5D"));
        // Preserve screen-changing query context without storing raw values. Credentials and volatile
        // transport parameters are excluded; Salesforce record IDs are contextual parameters.
        String path=Objects.toString(uri.getPath(), "").replaceAll("(?<=/)[a-zA-Z0-9]{15}(?:[a-zA-Z0-9]{3})?(?=/|$)", "{record}");
        Map<String,List<String>> query=new TreeMap<>();
        Set<String> volatileKeys=Set.of("sid","otp","cshc","starturl","token","access_token","id_token","refresh_token",
            "authorization","password","code","state","_","t","ts","timestamp","cachebuster","cache_buster");
        for(String pair:Objects.toString(uri.getRawQuery(),"").split("&")) {
            if(pair.isEmpty()) continue;
            String[] parts=pair.split("=",2);
            String key=URLDecoder.decode(parts[0],StandardCharsets.UTF_8);
            if(volatileKeys.contains(key.toLowerCase(Locale.ROOT))) continue;
            String value=parts.length==2?URLDecoder.decode(parts[1],StandardCharsets.UTF_8):"";
            if(key.matches("(?i)(id|recordid|record_id|sfdcid)") && value.matches("[a-zA-Z0-9]{15}(?:[a-zA-Z0-9]{3})?")) value="{record}";
            query.computeIfAbsent(key,ignored -> new ArrayList<>()).add(value);
        }
        return Objects.toString(uri.getScheme(), "")+"://"+Objects.toString(uri.getRawAuthority(), "")+path+
            (query.isEmpty()?"":"?context="+Json.hash(query));
    }
    public static LocatorEntry mapped(String page,String key,Observation observation,Observation.Node node,String engine) {
        return new LocatorEntry(page,key,routeKey(observation.route()),engine,node.target(),node.attributes(),
            alternatives(node.target(),observation),1,List.of());
    }
    public boolean parameterized() { return "${recordName}".equals(primary.name()) && "true".equals(attributes.get("recordLink")); }
    public LocatorEntry bind(Map<String,String> parameters) {
        Failure.require(parameterized() && parameters.size()==1 && parameters.get("recordName")!=null && !parameters.get("recordName").isBlank(),
            "LOCATOR_PARAMETER_REQUIRED","Record link requires one nonempty recordName parameter");
        return new LocatorEntry(page,key,route,engineVersion,new SemanticTarget(primary.role(),parameters.get("recordName"),null,primary.container()),
            attributes,List.of(),revision,history);
    }
    public LocatorEntry healed(Observation.Node node,Observation observation,String reason) {
        List<Change> changes=new ArrayList<>(history);
        changes.add(new Change(Instant.now(),reason,primary,node.target()));
        if(changes.size()>50) changes.removeFirst();
        return new LocatorEntry(page,key,route,engineVersion,node.target(),node.attributes(),
            alternatives(node.target(),observation),revision+1,changes);
    }
    public static List<SemanticTarget> alternatives(SemanticTarget target,Observation observation) {
        List<SemanticTarget> candidates=new ArrayList<>();
        if(target.name()!=null) candidates.add(new SemanticTarget(target.role(),target.name(),null,target.container()));
        if(target.label()!=null) candidates.add(new SemanticTarget(target.role(),null,target.label(),target.container()));
        return candidates.stream().distinct().filter(t -> !t.equals(target) &&
            observation.nodes().stream().filter(n -> matches(t,n.target())).count()==1).toList();
    }
    public static boolean matches(SemanticTarget expected,SemanticTarget actual) {
        return (expected.role()==null || Objects.equals(expected.role(),actual.role())) &&
            (expected.name()==null || Objects.equals(expected.name(),actual.name())) &&
            (expected.label()==null || Objects.equals(expected.label(),actual.label())) &&
            (expected.container()==null || Objects.equals(expected.container(),actual.container()));
    }
}
