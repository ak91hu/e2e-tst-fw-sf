package hu.ak91.sf.e2e.salesforce.api;

import com.fasterxml.jackson.databind.JsonNode;
import hu.ak91.sf.e2e.core.*;
import hu.ak91.sf.e2e.salesforce.fixture.*;
import java.util.*;

/** Single-record creates are intentionally sequential. Journals survive success, refusal and uncertain commits. */
public final class OwnedFixtures {
    public record Seed(String key,String object,String nameField,Map<String,Object> values,String parentKey,String parentField,Persona persona) {}
    public record Result(String key,String status,OwnedRecord record,String code) {}
    private final SalesforceApi api;
    private final RecordRegistry registry;
    public OwnedFixtures(SalesforceApi api,RecordRegistry registry) { this.api=api;this.registry=registry; }
    public String uniqueName(String label,int maxLength) {
        return new FixtureDataFactory(java.time.Clock.systemUTC(),java.time.ZoneOffset.UTC,registry.attemptId()).uniqueName(label,maxLength);
    }
    /** Bounded sequential batch with explicit per-record outcomes. No cleanup, retry or implicit rollback. */
    public List<Result> createAll(List<Seed> seeds,Deadline deadline) {
        Failure.require(seeds!=null && !seeds.isEmpty() && seeds.size()<=200 && seeds.stream().map(Seed::key).distinct().count()==seeds.size(),"FIXTURE_INVALID","Fixture batch needs 1..200 unique keys");
        Set<String> prior=new HashSet<>();for(var seed:seeds) {SafePaths.segment(seed.key());Failure.require(seed.parentKey()==null || prior.contains(seed.parentKey()),"FIXTURE_INVALID","Order each fixture parent before its children");prior.add(seed.key());}
        Map<String,OwnedRecord> saved=new HashMap<>();List<Result> results=new ArrayList<>();boolean uncertain=false;
        for(var seed:seeds) {
            if(uncertain || seed.parentKey()!=null && !saved.containsKey(seed.parentKey())) {results.add(new Result(seed.key(),"SKIPPED",null,uncertain?"ACTION_COMMIT_UNCERTAIN":"PARENT_NOT_CREATED"));continue;}
            try {var record=create(seed.object(),seed.nameField(),seed.values(),seed.parentKey()==null?null:saved.get(seed.parentKey()),seed.parentField(),seed.persona(),deadline);saved.put(seed.key(),record);results.add(new Result(seed.key(),"PASSED",record,null));}
            catch(Failure failure) {uncertain=registry.manifest().records().stream().anyMatch(r -> r.state==OwnedRecord.State.SAVE_UNCERTAIN);results.add(new Result(seed.key(),"FAILED",null,failure.code()));}
        }return List.copyOf(results);
    }
    public OwnedRecord create(String object,String nameField,Map<String,Object> supplied,OwnedRecord parent,String parentField,Persona persona,Deadline deadline) {
        SalesforceApi.apiName(object);SalesforceApi.apiName(nameField);
        Failure.require(supplied!=null && !supplied.isEmpty() && supplied.size()<=200,"FIXTURE_INVALID","Explicit fixture values are required");
        var values=new LinkedHashMap<>(supplied);String name=Objects.toString(values.get(nameField),"");
        Failure.require(name.startsWith("E2E-TA-") && name.length()>=20,"FIXTURE_INVALID","Use a unique attempt/worker fixture name");
        if(parent!=null) { registry.owned(parent);SalesforceApi.apiName(parentField);Failure.require(!values.containsKey(parentField),"FIXTURE_INVALID","Parent identity is supplied by the ownership journal");values.put(parentField,parent.id()); }
        JsonNode metadata=api.describe(object,deadline);Failure.require(metadata.path("createable").asBoolean() && metadata.path("fields").isArray(),"API_PERMISSION_DENIED","Object create metadata is unavailable");
        Map<String,JsonNode> fields=new HashMap<>();metadata.path("fields").forEach(f -> fields.put(f.path("name").asText(),f));
        if(parent!=null) {var field=fields.get(parentField);boolean compatible=false;if(field!=null) for(var type:field.path("referenceTo")) if(type.asText().equals(parent.objectType)) compatible=true;Failure.require(compatible,"FIXTURE_INVALID","Parent relationship does not reference its owned object type");}
        for(var entry:values.entrySet()) {
            SalesforceApi.apiName(entry.getKey());JsonNode field=fields.get(entry.getKey());
            Failure.require(field!=null && field.path("createable").asBoolean() && !field.path("calculated").asBoolean(),"FIXTURE_INVALID","Fixture contains an unknown or non-createable field");
            JsonNode value=Json.MAPPER.valueToTree(entry.getValue());String type=field.path("type").asText();
            Failure.require(value.isNull()?field.path("nillable").asBoolean():switch(type) {
                case "boolean" -> value.isBoolean();
                case "int","double","currency","percent" -> value.isNumber();
                case "reference" -> value.isTextual() && value.asText().matches("[A-Za-z0-9]{15}(?:[A-Za-z0-9]{3})?");
                case "string","textarea","phone","email","url","picklist","multipicklist","date","datetime","base64","encryptedstring","combobox" -> value.isTextual() && (field.path("length").asInt()==0 || value.asText().length()<=field.path("length").asInt());
                default -> false;
            },"FIXTURE_INVALID","Fixture value violates field metadata");
            if(type.equals("picklist") && field.path("restrictedPicklist").asBoolean() && !value.isNull()) {
                boolean valid=false;for(var option:field.path("picklistValues")) if(option.path("active").asBoolean() && option.path("value").asText().equals(value.asText())) valid=true;
                Failure.require(valid,"FIXTURE_INVALID","Restricted picklist value is unavailable");
            }
        }
        for(var field:fields.values()) Failure.require(!field.path("createable").asBoolean() || field.path("nillable").asBoolean() || field.path("defaultedOnCreate").asBoolean() || field.path("autoNumber").asBoolean() || values.containsKey(field.path("name").asText()),"FIXTURE_INVALID","Required field is missing");
        Failure.require(registry.manifest().records().stream().noneMatch(r -> r.plannedName.equals(name)),"FIXTURE_INVALID","A fixture name cannot be created twice in this attempt; reconcile its journal");
        var record=registry.claim(object,name,parent,persona);registry.prepared(record);registry.saveRequested(record);
        try {
            JsonNode result=api.create(object,Json.MAPPER.valueToTree(values),deadline);
            Failure.require(result.path("success").asBoolean() && result.path("errors").isArray() && result.path("errors").isEmpty(),"ACTION_COMMIT_UNCERTAIN","Create success could not be verified");
            registry.saved(record,SalesforceApi.id(result.path("id").asText()),"api-create");return record;
        } catch(RuntimeException e) { registry.uncertain(record,e instanceof Failure f?f.code():"ACTION_COMMIT_UNCERTAIN");throw e; }
    }
    /** Reconcile by an exact consumer-owned unique field; duplicates or absence never authorize another write. */
    public void reconcile(OwnedRecord record,String field,Deadline deadline) {
        registry.owned(record);SalesforceApi.apiName(field);
        String escaped=record.plannedName.replace("\\","\\\\").replace("'","\\'");
        var rows=api.query("SELECT Id,"+field+" FROM "+SalesforceApi.apiName(record.objectType)+" WHERE "+field+" = '"+escaped+"'",deadline);
        Failure.require(rows.size()==1 && rows.getFirst().path(field).asText().equals(record.plannedName),"ACTION_COMMIT_UNCERTAIN","Fixture reconciliation requires exactly one matching persisted record");
        registry.saved(record,rows.getFirst().path("Id").asText(),"api-reconciliation");
    }
}
