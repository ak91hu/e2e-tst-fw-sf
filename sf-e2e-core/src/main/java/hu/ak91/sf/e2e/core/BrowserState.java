package hu.ak91.sf.e2e.core;

import java.nio.file.*;
import java.net.URI;
import java.util.*;

/** Consumes an existing authorized session; never creates or exports authentication state. */
public final class BrowserState {
    private BrowserState() {}
    public static Path path(FrameworkConfig config,Persona persona) {
        String value=System.getenv(config.persona(persona).storageStateRef());
        Failure.require(value!=null && !value.isBlank(),"AUTH_CREDENTIAL_UNAVAILABLE","Missing external browser-state file reference");return Path.of(value);
    }
    public static String load(Path file,List<String> origins,SecretRegistry secrets) {
        try {
            Failure.require(Files.size(file)<=4*1024*1024,"AUTH_CREDENTIAL_INVALID","Browser-state file exceeds size limit");
            var state=Json.MAPPER.readTree(Files.readString(file));
            Failure.require(state.isObject() && state.path("cookies").isArray() && state.path("origins").isArray(),"AUTH_CREDENTIAL_INVALID","Invalid browser-state structure");
            var hosts=origins.stream().map(o -> URI.create(o).getHost()).toList();
            for(var cookie:state.path("cookies")) {
                String domain=cookie.path("domain").asText().replaceFirst("^\\.","");
                Failure.require(!domain.isBlank() && hosts.stream().anyMatch(host -> host.equals(domain) || host.endsWith("."+domain)),"AUTH_CREDENTIAL_INVALID","Browser-state cookie is outside trusted origins");
                String value=cookie.path("value").asText();
                if(cookie.path("name").asText().matches("(?i).*(sid|token|session|auth|password|secret|credential|BrowserId).*") || value.length()>=16) secrets.register(value);
            }
            for(var item:state.path("origins")) {
                Failure.require(origins.contains(item.path("origin").asText()),"AUTH_CREDENTIAL_INVALID","Browser-state storage is outside trusted origins");
                for(var value:item.path("localStorage")) {
                    String text=value.path("value").asText();
                    if(value.path("name").asText().matches("(?i).*(token|session|auth|password|secret|credential|key).*") || text.length()>=16) secrets.register(text);
                }
                if(item.has("indexedDB")) {
                    Failure.require(item.path("indexedDB").isArray(),"AUTH_CREDENTIAL_INVALID","Invalid IndexedDB state");
                    registerValues(item.path("indexedDB"),secrets);
                }
            }
            return Json.string(state);
        } catch(Failure e) { throw e; } catch(Exception e) { throw new Failure("AUTH_CREDENTIAL_INVALID","Cannot read external browser-state file"); }
    }
    private static void registerValues(com.fasterxml.jackson.databind.JsonNode node,SecretRegistry secrets) {
        if(node.isObject()) node.fields().forEachRemaining(e -> {
            if(e.getKey().equals("value")) registerLeaves(e.getValue(),secrets);else if(e.getValue().isContainerNode()) registerValues(e.getValue(),secrets);
        });
        else if(node.isArray()) node.forEach(child -> registerValues(child,secrets));
    }
    private static void registerLeaves(com.fasterxml.jackson.databind.JsonNode node,SecretRegistry secrets) {
        if(node.isTextual()) secrets.register(node.asText());else if(node.isContainerNode()) node.forEach(child -> registerLeaves(child,secrets));
    }
}
