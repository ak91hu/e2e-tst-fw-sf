package hu.ak91.sf.e2e.core;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class SessionVaultTest {
    final String state="{\"cookies\":[{\"name\":\"sid\",\"value\":\"PRIVATE_SESSION_SENTINEL\",\"domain\":\"application.example\"}],\"origins\":[]}";
    final List<String> origins=List.of("https://application.example");final byte[] key=new byte[32];final Clock clock=Clock.fixed(Instant.parse("2026-10-08T00:00:00Z"),ZoneOffset.UTC);
    @Test void encryptionIsRandomizedAndRoundTripsWithoutPersistingSecrets() {
        String a=SessionVault.seal(state,"SESSION_KEY",key,origins,Duration.ofHours(1),clock),b=SessionVault.seal(state,"SESSION_KEY",key,origins,Duration.ofHours(1),clock);
        assertNotEquals(a,b);assertFalse(a.contains("PRIVATE_SESSION_SENTINEL"));assertEquals(Json.MAPPER.readTree(state),Json.MAPPER.readTree(SessionVault.open(a,key,origins,clock)));
    }
    @Test void tamperingWrongKeysOriginsAndExpiryCannotAuthenticate() {
        String a=SessionVault.seal(state,"SESSION_KEY",key,origins,Duration.ofHours(1),clock);byte[] wrong=new byte[32];wrong[0]=1;
        assertThrows(Failure.class,() -> SessionVault.open(a,wrong,origins,clock));assertThrows(Failure.class,() -> SessionVault.open(a,key,List.of("https://other.example"),clock));
        assertThrows(Failure.class,() -> SessionVault.open(a,key,origins,Clock.offset(clock,Duration.ofHours(1))));
        assertThrows(Failure.class,() -> SessionVault.open(a.replace("SESSION_KEY","OTHER_KEY"),key,origins,clock));
    }
}
