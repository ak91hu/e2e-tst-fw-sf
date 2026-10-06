package hu.ak91.sf.e2e.junit;
import hu.ak91.sf.e2e.core.Persona;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
public @interface SfCase {
    String id();String name();String persona() default "default";String[] tags() default {};
    String[] dependencies() default {};
}
