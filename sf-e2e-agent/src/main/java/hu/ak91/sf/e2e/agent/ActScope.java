package hu.ak91.sf.e2e.agent;
import hu.ak91.sf.e2e.core.*;
import java.util.*;
public record ActScope(String attemptId, String objectType, String recordId, Set<String> fields, String container,String route) {
    public ActScope(String attempt,String object,String id,Set<String> fields,String container) {
        this(attempt,object,id,fields,container,"/lightning/r/"+object+"/"+id+"/edit");
    }
    public ActScope {
        Failure.require(objectType.matches("[A-Za-z][A-Za-z0-9_]{0,127}") && recordId.matches("[A-Za-z0-9]{15}(?:[A-Za-z0-9]{3})?") && !fields.isEmpty() && container!=null && !container.isBlank(),
            "RECORD_SCOPE_VIOLATION","Agent scope must name an owned persisted record and explicit edit fields");
        FrameworkConfig.validateRoute(route);
        Failure.require(route.contains(recordId),"RECORD_SCOPE_VIOLATION","Record scope route must identify its declared record");
        fields=Set.copyOf(fields);
    }
    public void validate(Observation observation, Observation.Node node, String tool, String value, Map<String,StepParam> params) {
        Failure.require(observation.complete() && observation.nodes().contains(node),"LOCATOR_INCOMPLETE","Agent actions require a complete current observation and an observed target");
        var expected=java.net.URI.create(route);var actual=java.net.URI.create(observation.route());
        Failure.require(Objects.equals(expected.getPath(),actual.getPath()) && (expected.getRawQuery()==null || Objects.equals(expected.getRawQuery(),actual.getRawQuery())),
            "RECORD_SCOPE_VIOLATION","Current route is outside the declared record edit scope");
        var target=node.target(); String label=target.label()!=null?target.label():target.name();
        Failure.require(observation.nodes().stream().filter(candidate -> candidate.target().equals(target)).count()==1,
            "TARGET_AMBIGUOUS","Agent field identity must be unique before mutation");
        Failure.require(!node.disabled() && container.equals(target.container()) && fields.contains(label),"POLICY_DENIED","Action target is outside declared fields");
        Failure.require(Set.of("fill","select","setChecked","press","scroll").contains(tool),"POLICY_DENIED","Agent cannot save, cancel, delete, navigate, or activate");
        String role=target.role();
        if(tool.equals("fill") || tool.equals("press")) Failure.require(Set.of("textbox","spinbutton","combobox").contains(Objects.toString(role,"")),"POLICY_DENIED","Field is not editable");
        if(tool.equals("select")) Failure.require("combobox".equals(role),"POLICY_DENIED","Select requires a combobox");
        if(tool.equals("setChecked")) Failure.require("checkbox".equals(role) || "radio".equals(role),"POLICY_DENIED","Checked state requires a checkbox/radio");
        if(Set.of("combobox","checkbox","radio","switch").contains(Objects.toString(role,"")))
            Failure.require(params.values().stream().noneMatch(p -> p.unique() && String.valueOf(p.value()).equals(value)),"POLICY_DENIED","Branching values cannot be unique replay slots");
        if(tool.equals("press")) Failure.require(Set.of("Tab","ArrowDown","ArrowUp","Home","End","ControlOrMeta+A","Backspace").contains(value),"POLICY_DENIED","Key is outside the unsaved-field grammar");
        else if(!tool.equals("scroll"))
            Failure.require(params.values().stream().anyMatch(p -> String.valueOf(p.value()).equals(value)),"POLICY_DENIED","Mutation value must come from a declared parameter");
    }
}
