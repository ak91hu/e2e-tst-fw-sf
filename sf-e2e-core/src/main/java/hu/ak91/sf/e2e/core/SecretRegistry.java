package hu.ak91.sf.e2e.core;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
public final class SecretRegistry {
    private final Set<String> values = new HashSet<>();
    public void register(String value) {
        if (value == null || value.isEmpty()) return;
        values.add(value);
        values.add(URLEncoder.encode(value, StandardCharsets.UTF_8));
        values.add(Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)));
    }
    public String resolve(SecretRef ref) {
        String value = System.getenv(ref.name());
        Failure.require(value != null && !value.isBlank(), "AUTH_CREDENTIAL_UNAVAILABLE", "Missing secret reference: " + ref.name());
        register(value); return value;
    }
    public String redact(String value) {
        if (value == null) return null;
        String result = value;
        for (String secret : values.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList())
            result = result.replace(secret, "[REDACTED]");
        result = result.replaceAll("(?i)(Bearer\\s+)[^\\s\\x22<>]+", "$1[REDACTED]");
        return result.replaceAll("(?i)((?:[?&]|&amp;)(?:sid|otp|cshc|access_token|refresh_token|assertion|code|startURL|retURL)=)[^&#\\s\\x22<>]*", "$1[REDACTED]");
    }
    public tools.jackson.databind.JsonNode redactTree(tools.jackson.databind.JsonNode node) {
        if(node.isString()) return Json.MAPPER.getNodeFactory().textNode(redact(node.asString()));
        if(node.isObject()) {
            var result=Json.MAPPER.createObjectNode();
            node.properties().forEach(e -> result.set(redact(e.getKey()),redactTree(e.getValue())));return result;
        }
        if(node.isArray()) { var result=Json.MAPPER.createArrayNode();node.forEach(child -> result.add(redactTree(child)));return result; }
        return node.deepCopy();
    }
    public boolean containsSecret(String value) { return values.stream().anyMatch(value::contains); }
    public List<String> maskingValues() { return List.copyOf(values); }
}
