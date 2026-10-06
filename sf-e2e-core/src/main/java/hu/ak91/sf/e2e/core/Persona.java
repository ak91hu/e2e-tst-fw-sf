package hu.ak91.sf.e2e.core;
import com.fasterxml.jackson.annotation.*;
import java.util.Locale;
/** A project-defined identity alias, independent of Salesforce profiles, licenses or business roles. */
public record Persona(String name) {
    public static final Persona DEFAULT=new Persona("default"), SALES=new Persona("sales"), SERVICE=new Persona("service");
    public Persona { name=SafePaths.segment(name.toLowerCase(Locale.ROOT)); }
    @JsonCreator public static Persona valueOf(String name) { return new Persona(name); }
    @JsonValue @Override public String name() { return name; }
    @Override public String toString() { return name; }
}
