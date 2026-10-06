package hu.ak91.sf.e2e.core;

import tools.jackson.databind.JsonNode;
import java.util.*;
/** Explicit, fail-closed JSON Schema subset for structured UI extraction. */
public final class JsonSchema {
    private static final Set<String> KEYS=Set.of("type","properties","required","additionalProperties","items","enum",
        "minimum","maximum","minLength","maxLength","pattern","minItems","maxItems","description","title");
    private JsonSchema() {}
    public static void validate(JsonNode data,JsonNode schema) { validate(data,schema,"$"); }
    private static void validate(JsonNode data,JsonNode schema,String path) {
        Failure.require(data!=null && schema!=null && schema.isObject(),"MODEL_OUTPUT_INVALID","Extraction data/schema missing at "+path);
        schema.propertyNames().forEach(key -> Failure.require(KEYS.contains(key),"INVALID_SCHEMA","Unsupported extraction schema keyword: "+key));
        var types=schema.path("type");
        boolean valid=types.isArray()?java.util.stream.StreamSupport.stream(types.spliterator(),false).anyMatch(type -> matchesType(data,type.asString())):matchesType(data,types.asString());
        Failure.require(valid,"MODEL_OUTPUT_INVALID","Extraction type mismatch at "+path);
        if(schema.has("enum")) {
            boolean found=false;for(var item:schema.get("enum")) if(item.equals(data)) found=true;
            Failure.require(found,"MODEL_OUTPUT_INVALID","Extraction enum mismatch at "+path);
        }
        if(data.isObject()) {
            var properties=schema.path("properties");
            for(var required:schema.path("required")) Failure.require(data.has(required.asString()),"MODEL_OUTPUT_INVALID","Missing extraction field at "+path+"."+required.asString());
            data.properties().forEach(entry -> {
                if(properties.has(entry.getKey())) validate(entry.getValue(),properties.get(entry.getKey()),path+"."+entry.getKey());
                else if(schema.path("additionalProperties").isObject()) validate(entry.getValue(),schema.get("additionalProperties"),path+"."+entry.getKey());
                else Failure.require(!schema.has("additionalProperties") || schema.get("additionalProperties").asBoolean(),"MODEL_OUTPUT_INVALID","Unexpected extraction field at "+path+"."+entry.getKey());
            });
        }
        if(data.isArray()) {
            Failure.require(schema.has("items"),"INVALID_SCHEMA","Array extraction schema requires items");
            if(schema.has("minItems")) Failure.require(data.size()>=schema.get("minItems").asInt(),"MODEL_OUTPUT_INVALID","Too few extraction items");
            if(schema.has("maxItems")) Failure.require(data.size()<=schema.get("maxItems").asInt(),"MODEL_OUTPUT_INVALID","Too many extraction items");
            for(int i=0;i<data.size();i++) validate(data.get(i),schema.get("items"),path+"["+i+"]");
        }
        if(data.isString()) {
            String value=data.asString();int length=value.codePointCount(0,value.length());
            if(schema.has("minLength")) Failure.require(length>=schema.get("minLength").asInt(),"MODEL_OUTPUT_INVALID","Extraction string too short");
            if(schema.has("maxLength")) Failure.require(length<=schema.get("maxLength").asInt(),"MODEL_OUTPUT_INVALID","Extraction string too long");
            if(schema.has("pattern")) Failure.require(java.util.regex.Pattern.compile(schema.get("pattern").asString()).matcher(value).find(),"MODEL_OUTPUT_INVALID","Extraction pattern mismatch");
        }
        if(data.isNumber()) {
            if(schema.has("minimum")) Failure.require(data.decimalValue().compareTo(schema.get("minimum").decimalValue())>=0,"MODEL_OUTPUT_INVALID","Extraction number below minimum");
            if(schema.has("maximum")) Failure.require(data.decimalValue().compareTo(schema.get("maximum").decimalValue())<=0,"MODEL_OUTPUT_INVALID","Extraction number above maximum");
        }
    }
    private static boolean matchesType(JsonNode data,String type) {
        return switch(type) {
            case "object"->data.isObject();case "array"->data.isArray();case "string"->data.isString();
            case "integer"->data.isIntegralNumber();case "number"->data.isNumber();case "boolean"->data.isBoolean();case "null"->data.isNull();default->false;
        };
    }
}
