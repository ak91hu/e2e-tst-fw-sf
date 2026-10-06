package hu.ak91.sf.e2e.agent;
import hu.ak91.sf.e2e.core.*;
import tools.jackson.databind.JsonNode;
import java.util.*;
public interface ModelClient {
    record Request(String kind,String instruction,Map<String,Object> params,Observation observation,
                   List<Map<String,Object>> ledger, JsonNode schema,boolean fullOutput) {
        public Request(String kind,String instruction,Map<String,Object> params,Observation observation,List<Map<String,Object>> ledger,JsonNode schema) {
            this(kind,instruction,params,observation,ledger,schema,false);
        }
    }
    record Response(String tool,String nodeId,String paramRef,String value,String verdict,String reason,
                    String errorCode,List<String> evidenceRefs,JsonNode data,Long tokens,Long inputTokens,Long outputTokens) {
        public Response(String tool,String nodeId,String paramRef,String value,String verdict,String reason,
                        String errorCode,List<String> evidenceRefs,JsonNode data,Long tokens) {
            this(tool,nodeId,paramRef,value,verdict,reason,errorCode,evidenceRefs,data,tokens,null,null);
        }
    }
    Response complete(Request request,Deadline deadline);
    String identity();
}
