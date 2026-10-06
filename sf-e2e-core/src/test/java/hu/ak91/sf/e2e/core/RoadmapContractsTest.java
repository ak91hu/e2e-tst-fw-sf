package hu.ak91.sf.e2e.core;

import hu.ak91.sf.e2e.core.scenario.*;
import hu.ak91.sf.e2e.core.quality.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class RoadmapContractsTest {
    @TempDir Path root;
    @Test void dependenciesLocksAndParallelismHaveIndependentVerdicts() {
        AtomicInteger busy=new AtomicInteger(),maximum=new AtomicInteger();CountDownLatch two=new CountDownLatch(2);List<String> order=Collections.synchronizedList(new ArrayList<>());
        var tasks=List.of(new DatasetScheduler.Task<>("a",List.of(),Set.of("record-a"),"a"),new DatasetScheduler.Task<>("b",List.of(),Set.of("record-b"),"b"),new DatasetScheduler.Task<>("c",List.of("a"),Set.of("record-a"),"c"));
        var results=DatasetScheduler.run(tasks,2,false,id -> { int current=busy.incrementAndGet();maximum.accumulateAndGet(current,Math::max);try {if(!id.equals("c")) {two.countDown();assertTrue(two.await(3,TimeUnit.SECONDS));}order.add(id);return !id.equals("a");}catch(InterruptedException e) {throw new RuntimeException(e);}finally {busy.decrementAndGet();} },Boolean::booleanValue,() -> {});
        assertEquals(2,maximum.get());assertEquals(List.of("FAILED","PASSED","SKIPPED"),results.stream().map(DatasetScheduler.Result::status).toList());assertFalse(order.contains("c"));
    }
    @Test void aSharedResourceNeverOverlapsAndCancellationStopsQueuedTasks() {
        AtomicInteger busy=new AtomicInteger();var tasks=List.of(new DatasetScheduler.Task<>("a",List.of(),Set.of("user"),"a"),new DatasetScheduler.Task<>("b",List.of(),Set.of("user"),"b"));
        var results=DatasetScheduler.run(tasks,2,false,id -> {assertEquals(1,busy.incrementAndGet());busy.decrementAndGet();throw new Failure("CANCELLED","stop");},v -> true,() -> {});assertEquals(List.of("CANCELLED","SKIPPED"),results.stream().map(DatasetScheduler.Result::status).toList());
        assertThrows(Failure.class,() -> DatasetScheduler.validate(List.of(new DatasetScheduler.Task<>("a",List.of("b"),Set.of(),1),new DatasetScheduler.Task<>("b",List.of("a"),Set.of(),2))));
    }
    @Test void timingCannotTurnAnActionFailureIntoSuccessOrReplaceIt() {
        var original=new Failure("ASSERTION_FAILED","expected");var actual=assertThrows(Failure.class,() -> ExecutionMetrics.measure("step",1,e -> {throw new IllegalStateException();},() -> {throw original;}));assertSame(original,actual);
        ExecutionMetrics.measure("step",1,e -> {throw new IllegalStateException();},() -> {});assertEquals("assertion",ExecutionMetrics.category(original));assertEquals("environment",ExecutionMetrics.category(new Failure("API_PERMISSION_DENIED","")));
    }
    @Test void visualBaselineAndPerformanceCannotAcceptInjectedDefects() throws Exception {
        var image=new java.awt.image.BufferedImage(10,10,java.awt.image.BufferedImage.TYPE_INT_RGB);byte[] before=png(image);var env=new QualityModules.Environment("chromium",10,10,"en-US",Json.hash("fonts"),Json.hash("data"));Path baseline=root.resolve("reviewed.png");QualityModules.approveBaseline(baseline,before,env);assertEquals(0,QualityModules.visual(baseline,before,env,0,0));
        image.setRGB(5,5,0xffffff);assertThrows(Failure.class,() -> QualityModules.visual(baseline,png(image),env,0,0));assertThrows(Failure.class,() -> QualityModules.approveBaseline(baseline,before,env));assertThrows(Failure.class,() -> QualityModules.performance(Map.of("loadMs",101d),Map.of("loadMs",100d)));assertThrows(Failure.class,() -> QualityModules.performance(Map.of(),Map.of("loadMs",100d)));
    }
    private byte[] png(java.awt.image.BufferedImage image) {try {var out=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"PNG",out);return out.toByteArray();}catch(Exception e) {throw new RuntimeException(e);} }
    @Test void accessibilityWaiversAreExactAndExpireAndCaughtFailuresRemainFatal() throws Exception {
        String hash=Json.hash("target");var result=Json.MAPPER.readTree("{\"violations\":[{\"id\":\"button-name\",\"impact\":\"critical\",\"nodes\":[{\"targetHash\":\""+hash+"\"}]}],\"incomplete\":[]}");Clock clock=Clock.fixed(Instant.parse("2026-10-06T00:00:00Z"),ZoneOffset.UTC);
        assertThrows(Failure.class,() -> QualityModules.accessibility(result,Set.of("critical"),List.of(),clock));QualityModules.accessibility(result,Set.of("critical"),List.of(new QualityModules.Waiver("button-name",hash,"Reviewed specific defect",LocalDate.of(2026,10,7))),clock);
        assertThrows(Failure.class,() -> QualityModules.accessibility(result,Set.of("critical"),List.of(new QualityModules.Waiver("button-name",hash,"Reviewed specific defect",LocalDate.of(2026,10,5))),clock));
        assertThrows(Failure.class,() -> BrowserQuality.verify(FrameworkConfig.Quality.defaults(),List.of(),List.of(Map.of("kind","quality-module","status","FAILED")),e -> {}));
    }
    @Test void privateArtifactsHaveOwnershipAndNoPublicAttachment() throws Exception {
        Path directory=PrivateArtifacts.create(root.resolve("private"),"attempt",24),file=directory.resolve("trace.zip");Files.writeString(file,"PRIVATE_SENTINEL");PrivateArtifacts.manifest(directory,"attempt",file,1024);
        assertThrows(Failure.class,() -> PrivateArtifacts.manifest(directory,"other",file,1024));assertEquals(0,PrivateArtifacts.purgeExpired(directory));assertFalse((Boolean)Json.read(directory.resolve("private-policy.json"),Map.class).get("publishToAllure"));
        assertThrows(Failure.class,() -> PrivateArtifacts.create(directory,"other",24));
    }
    @Test void engineMapsAndCoveragePolicyCannotBeReusedAcrossBrowsers() {
        var c=FrameworkConfig.load(null,Map.of());assertThrows(Failure.class,() -> FrameworkConfig.load(null,Map.of("ui.browser","firefox")));
        var f=FrameworkConfig.load(null,Map.of("ui.browser","firefox","ui.shadowPolicy","tracked"));assertNotEquals(hu.ak91.sf.e2e.core.locator.LocatorStore.namespace(c,Persona.DEFAULT),hu.ak91.sf.e2e.core.locator.LocatorStore.namespace(f,Persona.DEFAULT));
    }
    @Test void orgAndIdentityLeasesExcludeLegacyRunsAndReleaseOnRefusal() throws Exception {
        String origin="https://locks-"+UUID.randomUUID()+".example";Path orgLock=Path.of(System.getProperty("user.home"),".sf-e2e","locks",Json.hash(origin)+".lock");
        try(var a=ExecutionLocks.shared(origin,"user-a");var b=ExecutionLocks.shared(origin,"user-b")) {
            assertEquals("ENVIRONMENT_UNAVAILABLE",assertThrows(Failure.class,() -> ExecutionLocks.shared(origin,"user-a")).code());
            try(var channel=java.nio.channels.FileChannel.open(orgLock,StandardOpenOption.WRITE)) {assertThrows(java.nio.channels.OverlappingFileLockException.class,channel::tryLock);}
        }
        try(var channel=java.nio.channels.FileChannel.open(orgLock,StandardOpenOption.WRITE);var exclusive=channel.tryLock()) {assertNotNull(exclusive);assertThrows(Failure.class,() -> ExecutionLocks.shared(origin,"user-a"));}
        try(var recovered=ExecutionLocks.shared(origin,"user-a")) {assertNotNull(recovered);}
    }
    @Test void malformedAdvancedParametersFailBeforeAnyCaseExecution() {
        for(String kind:List.of("lookup","download","gridFind","clickDialog","multiSelect")) {var step=new CaseRecipe.Step(kind,"page","key",null,"value",null);assertThrows(Failure.class,() -> CaseRecipe.validateParams(Set.of("value"),Map.of("value","{}"),List.of(step)));}
        var switchPage=new CaseSpec.Step("switchPage",null,null,null,"pageHandle",null);assertFalse(CaseSpec.targeted(switchPage.kind()));var step=new CaseRecipe.Step("switchPage",null,null,null,"pageHandle",null);CaseRecipe.validateParams(Set.of("pageHandle"),Map.of("pageHandle","main"),List.of(step));
    }
}
