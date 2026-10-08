package hu.ak91.sf.e2e.core;

import java.nio.file.*;
import java.net.URI;
import java.util.*;

/** Consumes authorized external plaintext or authenticated encrypted session envelopes. */
public final class BrowserState {
    private BrowserState() {}
    public static Path path(FrameworkConfig config,Persona persona) {
        String value=System.getenv(config.persona(persona).storageStateRef());
        Failure.require(value!=null && !value.isBlank(),"AUTH_CREDENTIAL_UNAVAILABLE","Missing external browser-state file reference");return Path.of(value);
    }
    public static String load(Path file,List<String> origins,SecretRegistry secrets) {
        try {
            Failure.require(Files.size(file)<=6*1024*1024,"AUTH_CREDENTIAL_INVALID","Browser-state file exceeds size limit");
            String text=Files.readString(file);var node=Json.MAPPER.readTree(text);
            if(node.has("ciphertext")) text=SessionVault.open(text,SessionVault.key(node.path("keyRef").asString(),secrets),origins,java.time.Clock.systemUTC());
            return validate(text,origins,secrets);
        } catch(Failure e) { throw e; } catch(Exception e) { throw new Failure("AUTH_CREDENTIAL_INVALID","Cannot read external browser-state file"); }
    }
    public static String validate(String input,List<String> origins,SecretRegistry secrets) {
        try {
            Failure.require(origins!=null && !origins.isEmpty() && origins.stream().allMatch(o -> {
                var uri=URI.create(o);return uri.getHost()!=null && uri.getUserInfo()==null && uri.getQuery()==null && uri.getFragment()==null && (uri.getPath().isEmpty() || uri.getPath().equals("/")) && (uri.getScheme().equals("https") || uri.getScheme().equals("http") && Set.of("localhost","127.0.0.1","::1","[::1]").contains(uri.getHost()));
            }),"AUTH_CREDENTIAL_INVALID","Session origins must be explicit HTTPS or loopback origins");
            Failure.require(input.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=4*1024*1024,"AUTH_CREDENTIAL_INVALID","Browser-state exceeds 4 MiB");
            var state=Json.MAPPER.readTree(input);
            Failure.require(state.isObject() && state.path("cookies").isArray() && state.path("origins").isArray(),"AUTH_CREDENTIAL_INVALID","Invalid browser-state structure");
            var hosts=origins.stream().map(o -> URI.create(o).getHost()).toList();
            for(var cookie:state.path("cookies")) {
                String domain=cookie.path("domain").asString().replaceFirst("^\\.","");
                Failure.require(!domain.isBlank() && hosts.stream().anyMatch(host -> host.equals(domain) || host.endsWith("."+domain)),"AUTH_CREDENTIAL_INVALID","Browser-state cookie is outside trusted origins");
                String value=cookie.path("value").asString();
                if(cookie.path("name").asString().matches("(?i).*(sid|token|session|auth|password|secret|credential|BrowserId).*") || value.length()>=16) secrets.register(value);
            }
            for(var item:state.path("origins")) {
                Failure.require(origins.contains(item.path("origin").asString()),"AUTH_CREDENTIAL_INVALID","Browser-state storage is outside trusted origins");
                for(var value:item.path("localStorage")) {
                    String text=value.path("value").asString();
                    if(value.path("name").asString().matches("(?i).*(token|session|auth|password|secret|credential|key).*") || text.length()>=16) secrets.register(text);
                }
                if(item.has("indexedDB")) {
                    Failure.require(item.path("indexedDB").isArray(),"AUTH_CREDENTIAL_INVALID","Invalid IndexedDB state");
                    registerValues(item.path("indexedDB"),secrets);
                }
            }
            return Json.string(state);
        } catch(Failure e) { throw e; } catch(Exception e) { throw new Failure("AUTH_CREDENTIAL_INVALID","Cannot read external browser-state file"); }
    }
    private static void registerValues(tools.jackson.databind.JsonNode node,SecretRegistry secrets) {
        if(node.isObject()) node.properties().forEach(e -> {
            if(e.getKey().equals("value")) registerLeaves(e.getValue(),secrets);else if(e.getValue().isContainer()) registerValues(e.getValue(),secrets);
        });
        else if(node.isArray()) node.forEach(child -> registerValues(child,secrets));
    }
    private static void registerLeaves(tools.jackson.databind.JsonNode node,SecretRegistry secrets) {
        if(node.isString()) secrets.register(node.asString());else if(node.isContainer()) node.forEach(child -> registerLeaves(child,secrets));
    }
}
