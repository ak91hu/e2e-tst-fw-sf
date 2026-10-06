package hu.ak91.sf.e2e.core;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Bounded subscriber keeps the request timeout active until the entire response body completes. */
public final class HttpBodies {
    private HttpBodies() {}
    public static HttpResponse.BodyHandler<byte[]> bounded(int maximum) {
        return info -> new HttpResponse.BodySubscriber<>() {
            private final CompletableFuture<byte[]> body=new CompletableFuture<>();
            private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            private Flow.Subscription subscription;
            public CompletionStage<byte[]> getBody() { return body; }
            public void onSubscribe(Flow.Subscription value) { subscription=value;value.request(1); }
            public void onNext(List<ByteBuffer> buffers) {
                try { for(var buffer:buffers) { if(buffer.remaining()>maximum-bytes.size()) throw new Failure("API_LIMIT","HTTP response byte limit exceeded");byte[] chunk=new byte[buffer.remaining()];buffer.get(chunk);bytes.writeBytes(chunk); }subscription.request(1); }
                catch(RuntimeException e) { subscription.cancel();body.completeExceptionally(e); }
            }
            public void onError(Throwable error) { body.completeExceptionally(error); }
            public void onComplete() { body.complete(bytes.toByteArray()); }
        };
    }
}
