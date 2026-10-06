package hu.ak91.sf.e2e.core.page;

import hu.ak91.sf.e2e.core.*;
import hu.ak91.sf.e2e.core.locator.HealingLocators;
import hu.ak91.sf.e2e.core.scenario.CaseSpec;
import hu.ak91.sf.e2e.core.scenario.CaseRecipe;
import java.util.*;

/** A reusable page API over persisted locator keys. Never retains DOM nodes or business values. */
public final class PageObject {
    private final String id;
    private final HealingLocators locators;
    private final Deadline deadline;
    private final Thread owner=Thread.currentThread();
    PageObject(String id,HealingLocators locators,Deadline deadline) {
        this.id=id;this.locators=locators;this.deadline=deadline;
    }
    public String id() { return id; }
    public void click(String key) { action(key,"click",null); }
    public void fill(String key,String value) { action(key,"fill",value); }
    public void select(String key,String value) { action(key,"select",value); }
    public void check(String key,boolean checked) { action(key,"setChecked",Boolean.toString(checked)); }
    public void action(String key,String kind,String value) { action(key,kind,value,Map.of()); }
    public void action(String key,String kind,String value,Map<String,String> bindings) {
        checkKey(key);
        Failure.require(kind!=null && CaseSpec.KINDS.contains(kind) && CaseSpec.targeted(kind) && !CaseSpec.assertion(kind),
            "CASE_INVALID","Page actions must be supported targeted operations; assertions belong to the case");
        Failure.require(CaseSpec.needsValue(kind)==(value!=null),"CASE_INVALID","Page action value is missing or unexpected");
        if(value!=null) CaseRecipe.validateParams(Set.of("operation"),Map.of("operation",value),
            List.of(new CaseRecipe.Step(kind,id,key,null,"operation",null)));
        locators.action(id,key,kind,value,bindings);
    }
    public Observation.Node read(String key) { return read(key,Map.of(),deadline); }
    public Observation.Node read(String key,Map<String,String> bindings,Deadline wait) {
        checkKey(key);return locators.read(id,key,bindings,wait);
    }
    public long visibleCount(String key,Map<String,String> bindings,Deadline wait,boolean provingAbsence) {
        checkKey(key);return locators.visibleCount(id,key,bindings,wait,provingAbsence);
    }
    private void checkKey(String key) {
        Failure.require(owner==Thread.currentThread(),"POLICY_DENIED","Page objects belong to their session's owner thread");
        Failure.require(key!=null && key.matches("[a-zA-Z0-9_.-]{1,100}"),"LOCATOR_INVALID","Invalid page control key");
        deadline.check();
    }
}
