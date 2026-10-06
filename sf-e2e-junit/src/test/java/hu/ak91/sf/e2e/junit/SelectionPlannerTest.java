package hu.ak91.sf.e2e.junit;

import org.junit.jupiter.api.*;
import hu.ak91.sf.e2e.core.Failure;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SelectionPlannerTest {
    static class UserCases {
        @Test @SfCase(id="CUSTOM-SMOKE",name="User smoke",tags={"smoke"}) void smoke(SfTestContext sf) {}
        @Test @SfCase(id="CUSTOM-AI",name="User AI",tags={"ai"}) void ai(SfTestContext sf) {}
    }
    @Test void emptyFrameworkHasNoSelectedCasesAndNoImplicitAuthDependency() {
        assertTrue(SelectionPlanner.plan(SelectionPlanner.discover(List.of()),"deterministic",null,null).isEmpty());
        var plan=SelectionPlanner.plan(SelectionPlanner.discover(List.of(UserCases.class)),"deterministic",null,null);
        assertEquals(1,plan.size());assertTrue(plan.getFirst().info().dependencies().isEmpty());
    }
    @Test void customSmokeTagsReplacePinnedBusinessIdsAndExplicitAiCannotBypassDeterministicProfile() {
        var catalog=SelectionPlanner.discover(List.of(UserCases.class));
        assertEquals("CUSTOM-SMOKE",SelectionPlanner.plan(catalog,"smoke",null,null).getFirst().info().id());
        assertTrue(SelectionPlanner.plan(catalog,"deterministic","CUSTOM-AI",null).isEmpty());
    }
    static class ForbiddenDependency {
        @Test @SfCase(id="AI-SETUP",name="AI setup",tags={"ai"}) void setup(SfTestContext sf) {}
        @Test @SfCase(id="PLAIN",name="Plain",dependencies={"AI-SETUP"}) void plain(SfTestContext sf) {}
    }
    @Test void noModelBoundaryAppliesToTransitiveDependenciesDuringPlanning() {
        for(String profile:List.of("deterministic","regression")) {
            var failure=assertThrows(Failure.class,() -> SelectionPlanner.plan(SelectionPlanner.discover(List.of(ForbiddenDependency.class)),profile,"PLAIN",null));
            assertEquals("CONFIG_INVALID",failure.code());assertTrue(failure.getMessage().contains("AI-SETUP"));
        }
    }
    static class SharedSetup {
        @Test @SfCase(id="SETUP",name="Shared") void setup(SfTestContext sf) {}
        @Test @SfCase(id="FIRST",name="First",tags={"selected"},dependencies={"SETUP"}) void first(SfTestContext sf) {}
        @Test @SfCase(id="SECOND",name="Second",tags={"selected"},dependencies={"SETUP"}) void second(SfTestContext sf) {}
    }
    @Test void filteredChildrenIncludeOneSharedSetupInDependencyOrder() {
        var plan=SelectionPlanner.plan(SelectionPlanner.discover(List.of(SharedSetup.class)),"deterministic",null,"selected");
        assertEquals(List.of("SETUP","FIRST","SECOND"),plan.stream().map(c -> c.info().id()).toList());
    }
    static class Parent {
        @Test @SfCase(id="Z-PARENT",name="Parent") void parent(SfTestContext sf) {}
    }
    static class Child {
        @Test @SfCase(id="A-CHILD",name="Child",dependencies={"Z-PARENT"}) void child(SfTestContext sf) {}
    }
    @Test void crossClassDependenciesKeepEachClassContiguousAndParentsFirst() {
        var plan=SelectionPlanner.plan(SelectionPlanner.discover(List.of(Child.class,Parent.class)),"deterministic",null,null);
        assertEquals(List.of("Z-PARENT","A-CHILD"),plan.stream().map(c -> c.info().id()).toList());
    }
    static class InterleavedA {
        @Test @SfCase(id="A-START",name="Start") void start(SfTestContext sf) {}
        @Test @SfCase(id="A-END",name="End",dependencies={"B-MIDDLE"}) void end(SfTestContext sf) {}
    }
    static class InterleavedB {
        @Test @SfCase(id="B-MIDDLE",name="Middle",dependencies={"A-START"}) void middle(SfTestContext sf) {}
    }
    @Test void impossibleClassInterleavingIsRejectedInsteadOfRepeatingFixtures() {
        var failure=assertThrows(Failure.class,() -> SelectionPlanner.plan(SelectionPlanner.discover(List.of(InterleavedA.class,InterleavedB.class)),"deterministic",null,null));
        assertEquals("CONFIG_INVALID",failure.code());assertTrue(failure.getMessage().contains("interleaving"));
    }
    static class Cycle {
        @Test @SfCase(id="CYCLE-A",name="A",dependencies={"CYCLE-B"}) void first(SfTestContext sf) {}
        @Test @SfCase(id="CYCLE-B",name="B",dependencies={"CYCLE-A"}) void second(SfTestContext sf) {}
    }
    @Test void caseDependencyCyclesFailBeforeAnyLaunch() {
        assertEquals("CONFIG_INVALID",assertThrows(Failure.class,() -> SelectionPlanner.plan(SelectionPlanner.discover(List.of(Cycle.class)),"deterministic",null,null)).code());
    }
    @TestMethodOrder(MethodOrderer.MethodName.class)
    static class ConflictingOrder {
        @Test @SfCase(id="ORDER",name="Order") void sample(SfTestContext sf) {}
    }
    @Test void conflictingJUnitMethodOrderingCannotSilentlyOverrideDependencies() {
        assertThrows(Failure.class,() -> SelectionPlanner.plan(SelectionPlanner.discover(List.of(ConflictingOrder.class)),"deterministic",null,null));
    }
}
