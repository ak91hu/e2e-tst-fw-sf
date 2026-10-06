package hu.ak91.sf.e2e.salesforce;

import hu.ak91.sf.e2e.core.*;
import hu.ak91.sf.e2e.salesforce.auth.*;
import hu.ak91.sf.e2e.salesforce.fixture.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AuthenticationJournalTest {
    @TempDir Path root;
    FrameworkConfig config() {
        var c=FrameworkConfig.defaults();
        return new FrameworkConfig(c.schemaVersion(),c.projectId(),c.profile(),new FrameworkConfig.Org("test-org","developer-edition","https://test.develop.my.salesforce.com",List.of(),1,""),
            c.ui(),c.auth(),c.personas(),c.execution(),c.agent(),c.cache(),new FrameworkConfig.Retention("permanent",root.resolve("journal").toString()),c.evidence(),c.report());
    }
    RecordRegistry registry(String attempt) { return new RecordRegistry(config(),"run",attempt,"SF-OPP-001"); }
    @Test void jwtSignatureAudienceAndExpiryAreVerifiedWithPublicKey() throws Exception {
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);var pair=generator.generateKeyPair();
        Clock clock=Clock.fixed(Instant.parse("2026-10-04T22:00:00Z"),ZoneOffset.UTC);
        String token=JwtAuthenticator.assertion("client","business@example.com","https://login.salesforce.com",pair.getPrivate(),clock);
        String[] parts=token.split("\\.");
        var signature=Signature.getInstance("SHA256withRSA");signature.initVerify(pair.getPublic());signature.update((parts[0]+"."+parts[1]).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(signature.verify(Base64.getUrlDecoder().decode(parts[2])));
        var claims=Json.MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertEquals(clock.instant().getEpochSecond()+120,claims.get("exp").asLong());
        assertEquals("business@example.com",claims.get("sub").asText());assertEquals("https://login.salesforce.com",claims.get("aud").asText());
    }
    @Test void acceptsPkcs8AndRejectsWeakOrMalformedKeys() throws Exception {
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);
        var pair=generator.generateKeyPair();String pem="-----BEGIN PRIVATE KEY-----\n"+Base64.getMimeEncoder().encodeToString(pair.getPrivate().getEncoded())+"\n-----END PRIVATE KEY-----";
        assertEquals("RSA",JwtAuthenticator.parseKey(pem).getAlgorithm());assertThrows(Failure.class,() -> JwtAuthenticator.parseKey("not a key"));
        generator.initialize(1024);var weak=generator.generateKeyPair();
        String weakPem="-----BEGIN PRIVATE KEY-----\n"+Base64.getMimeEncoder().encodeToString(weak.getPrivate().getEncoded())+"\n-----END PRIVATE KEY-----";
        assertThrows(Failure.class,() -> JwtAuthenticator.parseKey(weakPem));
    }
    @Test void uiBridgeRejectsOriginInjectionDuplicatesAndWrongRedirect() {
        String origin=config().org().baseUrl(),start="lightning/o/Opportunity/list";
        String valid=origin+"/secur/frontdoor.jsp?otp=123456&cshc=abcdef&startURL=lightning%2Fo%2FOpportunity%2Flist";
        assertDoesNotThrow(() -> JwtAuthenticator.validateFrontdoor(valid,origin,start));
        for(String invalid:List.of(valid.replace(origin,"https://evil.com"),valid+"&otp=second",valid+"&sid=token",valid.replace("Opportunity","Account"),valid+"#fragment"))
            assertThrows(Failure.class,() -> JwtAuthenticator.validateFrontdoor(invalid,origin,start));
    }
    @Test void saveIntentSurvivesFailureAndCannotBeSavedAgainBlindly() {
        var registry=registry("attempt");
        var record=registry.claim("Opportunity","E2E-TA-Deal",null,Persona.SALES);registry.prepared(record);registry.saveRequested(record);registry.uncertain(record,"Connection lost after click");
        var disk=Json.read(registry.journalPath(),RecordRegistry.Manifest.class);
        assertEquals(OwnedRecord.State.SAVE_UNCERTAIN,disk.records().getFirst().state);
        assertThrows(Failure.class,() -> registry.saveRequested(record));
        assertEquals("permanent",disk.retention());
    }
    @Test void renameIsIntentBeforeSaveAndHistoryAfterCurrentProof() {
        var registry=registry("attempt");var record=registry.claim("Opportunity","E2E-TA-First",null,Persona.SALES);
        registry.prepared(record);registry.saveRequested(record);registry.saved(record,"006000000000001","created");registry.verified(record,record.name(),record.marker,"details");
        registry.rename(record,"E2E-TA-Second");assertEquals("E2E-TA-First",record.name());
        registry.saveRequested(record);registry.saved(record,record.id(),"saved");
        registry.verified(record,"E2E-TA-Second",record.marker,"new-details");
        assertEquals(List.of("E2E-TA-First","E2E-TA-Second"),record.nameHistory);
        assertEquals("006000000000001",record.id());
    }
    @Test void failedOrCancelledRenameDoesNotChangeOfficialName() {
        var registry=registry("attempt");var record=registry.claim("Quote","E2E-TA-First",null,Persona.SALES);
        registry.prepared(record);registry.saveRequested(record);registry.saved(record,"0Q0000000000001","created");
        registry.rename(record,"E2E-TA-Unsaved");registry.cancelIntent(record);
        assertEquals("E2E-TA-First",record.name());assertEquals(1,record.nameHistory.size());
    }
    @Test void foreignRecordsExistingRecordAbsenceAndUnsupportedObjectsAreDenied() {
        var registry=registry("one");var other=registry("two");
        var record=registry.claim("Account","E2E-TA-Account",null,Persona.SALES);
        assertThrows(Failure.class,() -> other.owned(record));
        assertThrows(Failure.class,() -> registry.claim("Invalid/Object","E2E-TA-User",null,Persona.SALES));
        registry.prepared(record);registry.saveRequested(record);registry.saved(record,"001000000000001","created");
        assertThrows(Failure.class,() -> registry.absent(record,"absence"));
    }
    @Test void supportingChildRecordsHaveExactRelatedListProvenance() {
        var registry=registry("attempt");var parent=registry.claim("Quote","E2E-TA-Quote",null,Persona.SALES);
        registry.prepared(parent);registry.saveRequested(parent);registry.saved(parent,"0Q0000000000001","created");
        var child=registry.claimChild("QuoteLineItem",parent,"E2E-TA-Product | quantity 2 | USD 125.50",config().org().baseUrl()+"/lightning/r/Quote/"+parent.id()+"/related/QuoteLineItems/view",Persona.SALES);
        registry.saveRequested(child);registry.verifyChild(child,"line-ui");
        assertNull(child.recordId);assertEquals(parent.claimId,child.parentClaimId);assertEquals(OwnedRecord.State.VERIFIED,child.state);
    }
    @Test void fixtureClockSeparatesUtcAndPersonaCalendarAndKeepsUniqueSuffix() {
        var data=new FixtureDataFactory(Clock.fixed(Instant.parse("2024-02-28T23:30:00Z"),ZoneOffset.UTC),ZoneId.of("Europe/Budapest"),"attempt01");
        assertEquals(LocalDate.of(2024,2,28),data.date(0));assertEquals(LocalDate.of(2024,2,29),data.personaToday());
        assertEquals(LocalDate.of(2025,2,27),data.date(365));
        String one=data.uniqueName("VeryLongLabel".repeat(5),30),two=data.uniqueName("VeryLongLabel".repeat(5),30);
        assertEquals(30,one.length());assertTrue(one.startsWith("E2E-TA-"));assertNotEquals(one,two);
    }
    @Test void customAndManagedPackageObjectsCanBeJournaledWithoutHardcodedBusinessSchemas() {
        var registry=registry("custom");var record=registry.claim("package__Widget__c","E2E-TA-Widget",null,Persona.valueOf("reviewer"));
        registry.prepared(record);registry.saveRequested(record);registry.saved(record,"a0Z000000000001AAA","custom-ui");
        registry.verified(record,record.name(),record.marker,"details");assertEquals("package__Widget__c",record.objectType);assertEquals("reviewer",record.persona.name());
    }
}
