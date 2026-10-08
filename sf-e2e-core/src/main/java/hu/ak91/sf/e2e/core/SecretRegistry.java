package hu.ak91.sf.e2e.core;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
public final class SecretRegistry {
    private final Set<String> values = new HashSet<>();
    private record Mask(Pattern full,String normalized,Pattern prefix) {}
    private final Map<String,Mask> patterns = new HashMap<>();
    private static String collapsed(String value) { return value.replaceAll("(?U)\\s+"," "); }
    private static Pattern literal(String value) {
        return literal(value,value.length()>=6);
    }
    private static Pattern literal(String value,boolean foldCase) {
        String pattern=Arrays.stream(value.split("(?U)\\s+",-1)).map(Pattern::quote).collect(java.util.stream.Collectors.joining("(?U)\\s+"));
        return Pattern.compile(pattern,foldCase?Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE:0);
    }
    private void variant(String value) {
        if(values.add(value)) {
            String normalized=collapsed(value);int minimum=Math.min(8,(normalized.length()+1)/2);
            patterns.put(value,new Mask(literal(value),normalized,normalized.length()<6?null:literal(normalized.substring(0,minimum),true)));
        }
    }
    public void register(String value) {
        if (value == null || value.isEmpty()) return;
        variant(value);variant(collapsed(value));
        String encoded=URLEncoder.encode(value,StandardCharsets.UTF_8);variant(encoded);variant(encoded.replace("+","%20"));
        String json=Json.string(value);variant(json.substring(1,json.length()-1));
        variant(value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;"));
        variant(Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)));
    }
    public String resolve(SecretRef ref) {
        String value = System.getenv(ref.name());
        Failure.require(value != null && !value.isBlank(), "AUTH_CREDENTIAL_UNAVAILABLE", "Missing secret reference: " + ref.name());
        register(value); return value;
    }
    public String redact(String value) {
        if (value == null) return null;
        String result = value;
        for (String secret : values.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList()) {
            var mask=patterns.get(secret);result=mask.full().matcher(result).replaceAll("[REDACTED]");
            // A bounded observation may end halfway through a known value. Mask only a sufficiently long suffix.
            String normalized=mask.normalized();if(mask.prefix()==null) continue;
            var prefix=mask.prefix().matcher(result);
            while(prefix.find()) {
                String suffix=collapsed(result.substring(prefix.start()));
                if(suffix.length()<normalized.length() && normalized.regionMatches(true,0,suffix,0,suffix.length())) {
                    result=result.substring(0,prefix.start())+"[REDACTED]";break;
                }
            }
        }
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
    public boolean containsSecret(String value) { return value!=null && !Objects.equals(value,redact(value)); }
    public List<String> maskingValues() { return List.copyOf(values); }
}
