package hu.ak91.sf.e2e.core.page;

import hu.ak91.sf.e2e.core.*;
import hu.ak91.sf.e2e.core.locator.*;
import java.util.*;

/** Session/persona-scoped POM registry. Caches Java wrappers, never observations or resolved nodes. */
public final class PageObjects {
    private final HealingLocators locators;
    private final Deadline deadline;
    private final Thread owner=Thread.currentThread();
    private final Map<String,PageObject> pages=new HashMap<>();
    public PageObjects(HealingLocators locators,Deadline deadline) {
        this.locators=Objects.requireNonNull(locators);this.deadline=Objects.requireNonNull(deadline);
    }
    public PageObject page(String id) {
        Failure.require(owner==Thread.currentThread(),"POLICY_DENIED","Page registry belongs to its session's owner thread");
        Failure.require(id!=null && id.matches("[a-zA-Z0-9_.-]{1,100}"),"LOCATOR_INVALID","Invalid page object ID");
        deadline.check();return pages.computeIfAbsent(id,key -> new PageObject(key,locators,deadline));
    }
    public void open(String route) {
        Failure.require(owner==Thread.currentThread(),"POLICY_DENIED","Page registry belongs to its session's owner thread");
        FrameworkConfig.validateRoute(route);deadline.check();locators.open(route);
    }
    /** One model per route context and learning revision; failed learning cannot replace an older model. */
    public static String modelId(String route,String revision) {
        Failure.require(revision!=null && revision.matches("[a-zA-Z0-9_.-]{1,40}"),"LOCATOR_INVALID","Invalid page model revision");
        return "page."+Json.hash(LocatorEntry.routeKey(route)).substring(0,24)+"."+revision;
    }
}
