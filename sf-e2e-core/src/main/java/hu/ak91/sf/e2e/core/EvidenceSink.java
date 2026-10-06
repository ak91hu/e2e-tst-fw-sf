package hu.ak91.sf.e2e.core;
import java.util.Map;
public interface EvidenceSink {
    String checkpoint(String stage);
    void event(Map<String,Object> event);
}
