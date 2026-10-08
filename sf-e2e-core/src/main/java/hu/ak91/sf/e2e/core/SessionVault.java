package hu.ak91.sf.e2e.core;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** AES-256-GCM session envelopes; plaintext is never exported to a file by this class. */
public final class SessionVault {
    private SessionVault() {}
    public record Envelope(int schemaVersion,String algorithm,String keyRef,String originsHash,Instant createdAt,Instant expiresAt,String nonce,String ciphertext) {}
    public static byte[] key(String ref,SecretRegistry secrets) {
        try {byte[] key=Base64.getDecoder().decode(secrets.resolve(new SecretRef(ref,"session-encryption")));Failure.require(key.length==32,"AUTH_CREDENTIAL_INVALID","Session key must be a base64-encoded 256-bit key");return key;}
        catch(Failure f) {throw f;}catch(Exception e) {throw new Failure("AUTH_CREDENTIAL_INVALID","Invalid session encryption key");}
    }
    private static byte[] aad(Envelope e) {return Json.string(List.of(e.schemaVersion(),e.algorithm(),e.keyRef(),e.originsHash(),e.createdAt(),e.expiresAt())).getBytes(StandardCharsets.UTF_8);}
    public static String seal(String state,String keyRef,byte[] key,List<String> origins,Duration ttl,Clock clock) {
        Failure.require(key.length==32 && keyRef!=null && keyRef.matches("[A-Za-z_][A-Za-z0-9_]{0,127}") && !ttl.isNegative() && !ttl.isZero() && ttl.compareTo(Duration.ofHours(168))<=0,"AUTH_CREDENTIAL_INVALID","Invalid encrypted session key/reference/lifetime");
        Failure.require(state.getBytes(StandardCharsets.UTF_8).length<=4*1024*1024,"AUTH_CREDENTIAL_INVALID","Session state exceeds 4 MiB");
        BrowserState.validate(state,origins,new SecretRegistry());
        byte[] nonce=new byte[12];new SecureRandom().nextBytes(nonce);Instant created=clock.instant();
        var header=new Envelope(1,"AES-256-GCM",keyRef,Json.hash(origins.stream().sorted().toList()),created,created.plus(ttl),Base64.getEncoder().encodeToString(nonce),"");
        try {
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));cipher.updateAAD(aad(header));
            return Json.string(new Envelope(header.schemaVersion(),header.algorithm(),keyRef,header.originsHash(),created,header.expiresAt(),header.nonce(),Base64.getEncoder().encodeToString(cipher.doFinal(state.getBytes(StandardCharsets.UTF_8)))));
        } catch(Exception e) {throw new Failure("AUTH_CREDENTIAL_INVALID","Could not encrypt authorized session state");}
    }
    public static String open(String encoded,byte[] key,List<String> origins,Clock clock) {
        try {
            Failure.require(encoded.getBytes(StandardCharsets.UTF_8).length<=6*1024*1024,"AUTH_CREDENTIAL_INVALID","Encrypted session exceeds its size limit");
            var e=Json.MAPPER.readValue(encoded,Envelope.class);
            Failure.require(e.schemaVersion()==1 && "AES-256-GCM".equals(e.algorithm()) && key.length==32 && e.originsHash().equals(Json.hash(origins.stream().sorted().toList())) &&
                !clock.instant().isBefore(e.createdAt()) && clock.instant().isBefore(e.expiresAt()) && Duration.between(e.createdAt(),e.expiresAt()).compareTo(Duration.ofHours(168))<=0,
                "AUTH_CREDENTIAL_INVALID","Encrypted session context or lifetime is invalid");
            byte[] nonce=Base64.getDecoder().decode(e.nonce());Failure.require(nonce.length==12,"AUTH_CREDENTIAL_INVALID","Invalid encrypted session nonce");
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));cipher.updateAAD(aad(e));
            String state=new String(cipher.doFinal(Base64.getDecoder().decode(e.ciphertext())),StandardCharsets.UTF_8);BrowserState.validate(state,origins,new SecretRegistry());return state;
        } catch(Failure f) {throw f;}catch(Exception e) {throw new Failure("AUTH_CREDENTIAL_INVALID","Encrypted session authentication failed");}
    }
}
