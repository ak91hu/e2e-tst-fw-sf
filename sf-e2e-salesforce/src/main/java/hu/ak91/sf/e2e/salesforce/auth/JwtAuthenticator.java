package hu.ak91.sf.e2e.salesforce.auth;

import hu.ak91.sf.e2e.core.*;
import org.bouncycastle.openssl.*;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.net.*;
import java.net.http.*;
import java.security.*;
import java.security.interfaces.RSAPrivateKey;
import java.time.*;
import java.util.*;

public final class JwtAuthenticator {
    private final FrameworkConfig config;
    private final SecretRegistry secrets;
    private final HttpClient http;
    public JwtAuthenticator(FrameworkConfig config,SecretRegistry secrets) {
        this(config,secrets,HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build());
    }
    public JwtAuthenticator(FrameworkConfig config,SecretRegistry secrets,HttpClient http) { this.config=config;this.secrets=secrets;this.http=http; }
    public static PrivateKey parseKey(String pem) {
        try(PEMParser parser=new PEMParser(new StringReader(pem))) {
            Object object=parser.readObject(); JcaPEMKeyConverter converter=new JcaPEMKeyConverter();
            PrivateKey key=object instanceof PEMKeyPair pair?converter.getKeyPair(pair).getPrivate():
                object instanceof PrivateKeyInfo info?converter.getPrivateKey(info):null;
            Failure.require(key instanceof RSAPrivateKey rsa && rsa.getModulus().bitLength()>=2048,"AUTH_CREDENTIAL_INVALID","An unencrypted RSA key of at least 2048 bits is required");
            return key;
        } catch(Failure e) { throw e; } catch(Exception e) { throw new Failure("AUTH_CREDENTIAL_INVALID","Invalid PKCS#8 or PKCS#1 private key"); }
    }
    public static String assertion(String clientId,String username,String audience,PrivateKey key,Clock clock) {
        try {
            Base64.Encoder encoder=Base64.getUrlEncoder().withoutPadding();
            String header=encoder.encodeToString(Json.string(Map.of("alg","RS256","typ","JWT")).getBytes(StandardCharsets.UTF_8));
            String payload=encoder.encodeToString(Json.string(Map.of("iss",clientId,"sub",username,"aud",audience,"exp",clock.instant().getEpochSecond()+120,"jti",UUID.randomUUID().toString())).getBytes(StandardCharsets.UTF_8));
            String data=header+"."+payload; Signature signature=Signature.getInstance("SHA256withRSA");
            signature.initSign(key);signature.update(data.getBytes(StandardCharsets.UTF_8)); return data+"."+encoder.encodeToString(signature.sign());
        } catch(Exception e) { throw new Failure("AUTH_CREDENTIAL_INVALID","JWT signing failed"); }
    }
    public PrivateKey key() {
        String pem=System.getenv(config.auth().privateKeyRef());
        if(pem==null || pem.isBlank()) {
            String path=System.getenv(config.auth().privateKeyFileRef());
            Failure.require(path!=null && !path.isBlank(),"AUTH_CREDENTIAL_UNAVAILABLE","Missing private key reference");
            try { pem=Files.readString(Path.of(path)); } catch(IOException e) { throw new Failure("AUTH_CREDENTIAL_UNAVAILABLE","Cannot read private key file"); }
        }
        secrets.register(pem); return parseKey(pem);
    }
    public void preflight(Set<Persona> personas) {
        Failure.require(!config.org().baseUrl().isBlank(),"CONFIG_INVALID","SF_BASE_URL is required for live execution");
        FrameworkConfig.validateOrigin(config.org().baseUrl());
        if(config.auth().mode().equals("storage-state")) {
            for(var persona:personas) BrowserState.load(BrowserState.path(config,persona),config.origins(),secrets);
            return;
        }
        key(); secrets.resolve(new SecretRef(config.auth().clientIdRef(),"jwt-issuer"));
        for(Persona persona:personas) {
            secrets.resolve(new SecretRef(config.persona(persona).usernameRef(),"persona-username"));
        }
    }
    public String frontdoor(Persona persona,Deadline deadline) {
        preflight(Set.of(persona));
        if(config.auth().mode().equals("storage-state")) return config.org().baseUrl()+config.auth().startRoute();
        String client=secrets.resolve(new SecretRef(config.auth().clientIdRef(),"jwt-issuer"));
        String user=secrets.resolve(new SecretRef(config.persona(persona).usernameRef(),"persona-username"));
        String jwt=assertion(client,user,config.jwtAudience(),key(),Clock.systemUTC());secrets.register(jwt);
        var token=post(config.tokenOrigin(),"/services/oauth2/token",Map.of("grant_type","urn:ietf:params:oauth:grant-type:jwt-bearer","assertion",jwt),null,deadline);
        String access=token.path("access_token").asString();
        Failure.require(!access.isBlank() && token.path("instance_url").asString().equals(config.org().baseUrl()),"AUTH_CREDENTIAL_INVALID","Token response org identity mismatch");
        secrets.register(access);
        String start=config.auth().startRoute().substring(1);
        var bridge=post(config.org().baseUrl(),"/services/oauth2/singleaccess",Map.of("redirect_uri",start),access,deadline);
        String url=bridge.path("frontdoor_uri").asString();
        validateFrontdoor(url,config.org().baseUrl(),start);
        registerFrontdoorSecrets(url,secrets);
        return url;
    }
    /** REST authorization is independent of browser storage state and must be explicitly provisioned. */
    public String apiAccess(Persona persona,Deadline deadline) {
        Failure.require(config.auth().mode().equals("jwt"),"AUTH_CREDENTIAL_UNAVAILABLE","Browser storage state does not authorize a REST session; supply a separate access token");
        preflight(Set.of(persona));
        String jwt=assertion(secrets.resolve(new SecretRef(config.auth().clientIdRef(),"jwt-issuer")),secrets.resolve(new SecretRef(config.persona(persona).usernameRef(),"persona-username")),config.jwtAudience(),key(),Clock.systemUTC());secrets.register(jwt);
        var response=post(config.tokenOrigin(),"/services/oauth2/token",Map.of("grant_type","urn:ietf:params:oauth:grant-type:jwt-bearer","assertion",jwt),null,deadline);
        String token=response.path("access_token").asString();
        Failure.require(!token.isBlank() && response.path("instance_url").asString().equals(config.org().baseUrl()),"AUTH_CREDENTIAL_INVALID","REST session org identity mismatch");secrets.register(token);return token;
    }
    static void registerFrontdoorSecrets(String url,SecretRegistry secrets) {
        URI uri=URI.create(url);
        for(String part:uri.getRawQuery().split("&")) {
            int split=part.indexOf('=');
            String name=URLDecoder.decode(part.substring(0,split),StandardCharsets.UTF_8);
            // startURL is public application context, not a credential. Registering it would
            // redact every observation route and control label that contains that route.
            if(name.equals("otp") || name.equals("cshc"))
                secrets.register(URLDecoder.decode(part.substring(split+1),StandardCharsets.UTF_8));
        }
        secrets.register(url);
    }
    public static void validateFrontdoor(String value,String origin,String start) {
        try {
            URI uri=URI.create(value);
            Failure.require((uri.getScheme()+"://"+uri.getRawAuthority()).equals(origin) && uri.getRawUserInfo()==null &&
                uri.getPath().equals("/secur/frontdoor.jsp") && uri.getFragment()==null && uri.getRawQuery()!=null,
                "AUTH_CREDENTIAL_INVALID","Unexpected UI Bridge destination");
            Map<String,String> query=new HashMap<>();
            for(String pair:uri.getRawQuery().split("&")) {
                int split=pair.indexOf('=');Failure.require(split>0,"AUTH_CREDENTIAL_INVALID","Malformed UI Bridge parameter");
                String name=URLDecoder.decode(pair.substring(0,split),StandardCharsets.UTF_8);
                Failure.require(query.put(name,URLDecoder.decode(pair.substring(split+1),StandardCharsets.UTF_8))==null,"AUTH_CREDENTIAL_INVALID","Duplicate UI Bridge parameter");
            }
            Failure.require(query.keySet().equals(Set.of("otp","cshc","startURL")) && query.get("otp").length()>=6 &&
                query.get("cshc").length()>=6 && query.get("startURL").equals(start),"AUTH_CREDENTIAL_INVALID","Invalid UI Bridge parameters");
        } catch(Failure e) { throw e; } catch(Exception e) { throw new Failure("AUTH_CREDENTIAL_INVALID","Malformed UI Bridge URL"); }
    }
    private tools.jackson.databind.JsonNode post(String origin,String path,Map<String,String> params,String access,Deadline deadline) {
        String body=params.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(),StandardCharsets.UTF_8)+"="+URLEncoder.encode(e.getValue(),StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));
        try {
            var request=HttpRequest.newBuilder(URI.create(origin+path)).timeout(deadline.child(Duration.ofSeconds(30)).remaining())
                .header("Accept","application/json").header("Content-Type","application/x-www-form-urlencoded");
            if(access!=null) request.header("Authorization","Bearer "+access);
            var response=http.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
            deadline.check();
            Failure.require(response.statusCode()==200,"AUTH_CREDENTIAL_INVALID","Salesforce authentication returned HTTP "+response.statusCode());
            Failure.require(response.body().length()<=1048576,"AUTH_CREDENTIAL_INVALID","Oversized authentication response");
            var tree=Json.MAPPER.readTree(response.body());
            Failure.require(tree.isObject(),"AUTH_CREDENTIAL_INVALID","Authentication response is not an object");return tree;
        } catch(Failure e) { throw e; }
        catch(InterruptedException e) { Thread.currentThread().interrupt();throw new Failure("CANCELLED","Authentication interrupted"); }
        catch(Exception e) { throw new Failure("ENVIRONMENT_UNAVAILABLE","Salesforce authentication transport failed"); }
    }
}
