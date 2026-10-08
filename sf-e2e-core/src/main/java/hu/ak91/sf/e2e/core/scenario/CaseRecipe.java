package hu.ak91.sf.e2e.core.scenario;

import hu.ak91.sf.e2e.core.*;
import hu.ak91.sf.e2e.core.locator.*;
import java.nio.file.*;
import java.util.*;

/** Portable execution format. UI maps remain isolated by deployment/persona; the recipe stores no values. */
public record CaseRecipe(int schemaVersion,String id,String namespaceHash,String engineVersion,String startRoute,List<Step> steps) {
    public record Step(String kind,String page,String key,String route,String valueRef,String recordNameRef) {
        public Step {
            Failure.require(kind!=null && CaseSpec.KINDS.contains(kind),"CASE_INVALID","Unsupported recipe step");
            boolean navigation=kind.equals("open") || kind.equals("expectRoute");
            Failure.require(kind.equals("switchPage")?route==null && page==null && key==null && recordNameRef==null:navigation ? route!=null && page==null && key==null && valueRef==null && recordNameRef==null :
                route==null && page!=null && page.matches("[a-zA-Z0-9_.-]{1,100}") && key!=null && key.matches("[a-zA-Z0-9_.-]{1,100}"),
                "CASE_INVALID","Recipe step has invalid navigation or locator fields");
            if(navigation) FrameworkConfig.validateRoute(route);
            Failure.require(CaseSpec.needsValue(kind)==(valueRef!=null),"CASE_INVALID","Recipe value reference is missing or unexpected");
            CaseSpec.validateRef(valueRef);CaseSpec.validateRef(recordNameRef);
        }
    }
    public CaseRecipe {
        Failure.require(schemaVersion==1 && id!=null && id.matches("[a-zA-Z0-9_.-]{1,70}") && namespaceHash!=null && namespaceHash.matches("[a-f0-9]{64}") &&
            engineVersion!=null && !engineVersion.isBlank(),"CASE_INVALID","Invalid recipe version, identity or namespace");
        FrameworkConfig.validateRoute(startRoute);
        Failure.require(steps!=null && !steps.isEmpty() && steps.size()<=200 && steps.stream().allMatch(Objects::nonNull) &&
            CaseSpec.assertion(steps.getLast().kind()),"CASE_INVALID","Recipe needs bounded steps ending in an assertion");
        steps=List.copyOf(steps);
    }
    public record Document(CaseRecipe recipe,String checksum) {}
    public void save(Path file) { Json.write(file,new Document(this,Json.hash(this))); }
    public static CaseRecipe load(Path file) {
        try {
            Failure.require(Files.isRegularFile(file) && Files.size(file)<=1024*1024,"CASE_INVALID","Recipe must be a file of at most 1 MiB");
            var document=Json.read(file,Document.class);
            Failure.require(document.recipe()!=null && Json.hash(document.recipe()).equals(document.checksum()),"CASE_INVALID","Recipe checksum mismatch");
            return document.recipe();
        } catch(Failure e) { throw e; } catch(Exception e) { throw new Failure("CASE_INVALID","Cannot read recipe"); }
    }
    public Set<String> requiredParams() {
        Set<String> refs=new TreeSet<>();
        for(var step:steps) { if(step.valueRef()!=null) refs.add(step.valueRef());if(step.recordNameRef()!=null) refs.add(step.recordNameRef()); }
        return Set.copyOf(refs);
    }
    /** The stored page/control references are the reusable POM definitions; no duplicate locator file. */
    public Map<String,List<String>> pageObjects() {
        var grouped=new TreeMap<String,Set<String>>();
        for(var step:steps) if(step.page()!=null) grouped.computeIfAbsent(step.page(),p -> new TreeSet<>()).add(step.key());
        var result=new TreeMap<String,List<String>>();grouped.forEach((page,keys) -> result.put(page,List.copyOf(keys)));
        return Collections.unmodifiableMap(result);
    }
    public void preflight(String namespace,LocatorStore store,Map<String,String> params) {
        Failure.require(namespaceHash.equals(Json.hash(namespace)),"CASE_WRONG_CONTEXT","Learn this case for the configured org, persona and UI settings first");
        validateParams(requiredParams(),params,steps);
        for(var step:steps) if(step.page()!=null) {
            var entry=store.get(step.page(),step.key());
            Failure.require(entry.engineVersion().equals(engineVersion) && entry.parameterized()==(step.recordNameRef()!=null),"CASE_INVALID","Recipe and locator map do not agree");
        }
    }
    public static void validateParams(Set<String> required,Map<String,String> params,List<Step> steps) {
        if(params!=null) {
            var missing=new TreeSet<>(required);missing.removeAll(params.keySet());
            Failure.require(missing.isEmpty(),"CASE_PARAMETER_REQUIRED","Missing case parameters: "+String.join(", ",missing)+". Supply --params with explicit inputs and expected values; values are never inferred.");
        }
        Failure.require(params!=null && params.keySet().containsAll(required) && params.size()<=200 && params.values().stream().allMatch(v -> v!=null && v.length()<=10000),
            "CASE_PARAMETER_REQUIRED","Supply all required case parameters (values are never stored in recipes)");
        for(var step:steps) if(Set.of("setChecked","expectChecked","press").contains(step.kind())) {
            String value=params.get(step.valueRef());
            Failure.require(step.kind().equals("press") ? Set.of("Enter","Tab","Escape","ArrowDown","ArrowUp","ArrowLeft","ArrowRight","Home","End","Space").contains(value) :
                Set.of("true","false").contains(value),"CASE_INVALID","Invalid checked state or unsupported key parameter");
        }
        for(var step:steps) if(step.kind().equals("expectCount"))
            Failure.require(params.get(step.valueRef()).matches("0|[1-9][0-9]{0,3}|10000"),"CASE_INVALID","Expected count must be an integer from 0 to 10000");
        for(var step:steps) validateOperation(step.kind(),step.valueRef()==null?null:params.get(step.valueRef()));
    }
    private static void validateOperation(String kind,String value) {
        if(kind.equals("clickPopup")) {FrameworkConfig.validateRoute(value);return;}
        if(kind.equals("switchPage")) {SafePaths.segment(value);return;}
        if(kind.equals("upload")) {try {Failure.require(!value.isBlank() && !Path.of(value).isAbsolute() && !Path.of(value).normalize().startsWith("..") && !value.matches("^[A-Za-z]:.*"),"CASE_INVALID","Upload input must be a relative contained file path");}catch(java.nio.file.InvalidPathException e) {throw new Failure("CASE_INVALID","Invalid upload input path");}return;}
        if(!Set.of("lookup","download","clickDialog","gridFind","multiSelect").contains(kind)) return;
        try {
            var tree=Json.MAPPER.readTree(value);Failure.require(tree!=null,"CASE_INVALID","Missing structured operation value");
            if(kind.equals("multiSelect")) {Set<String> labels=new HashSet<>();Failure.require(tree.isArray() && !tree.isEmpty() && tree.size()<=100,"CASE_INVALID","Multi-select needs 1..100 unique labels");for(var item:tree) Failure.require(item.isString() && !item.asString().isBlank() && labels.add(item.asString()),"CASE_INVALID","Invalid/duplicate multi-select label");return;}
            Set<String> keys=switch(kind) {case "lookup" -> Set.of("query","option");case "download" -> Set.of("sha256","text");case "gridFind" -> Set.of("row","maxScrolls");default -> Set.of("type","message","decision","prompt");};
            Failure.require(tree.isObject() && tree.size()==keys.size() && keys.stream().allMatch(k -> tree.path(k).isString()),"CASE_INVALID","Operation value has unknown/missing keys or wrong types");
            if(kind.equals("lookup")) Failure.require(!tree.path("option").asString().isBlank(),"CASE_INVALID","Lookup needs an exact option");
            if(kind.equals("download")) Failure.require(tree.path("sha256").asString().matches("[a-f0-9]{64}"),"CASE_INVALID","Download needs an expected SHA-256");
            if(kind.equals("gridFind")) Failure.require(!tree.path("row").asString().isBlank() && tree.path("maxScrolls").asString().matches("0|[1-9][0-9]?|100"),"CASE_INVALID","Grid needs an exact row and bounded scroll count");
            if(kind.equals("clickDialog")) Failure.require(Set.of("alert","confirm","prompt","beforeunload").contains(tree.path("type").asString()) && Set.of("accept","dismiss").contains(tree.path("decision").asString()),"CASE_INVALID","Invalid dialog type/decision");
        }catch(Failure e) {throw e;}catch(Exception e) {throw new Failure("CASE_INVALID","Operation value is not valid JSON");}
    }
}
