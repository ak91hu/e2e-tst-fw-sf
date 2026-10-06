/** Optional launcher: load an external .env and reuse its installed e2e ChatGPT provider.
 * Credentials are never copied into this project. Deterministic Java commands need no Node provider.
 */
import { existsSync } from 'node:fs';
import { dirname, resolve, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createRequire } from 'node:module';
import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { randomBytes, timingSafeEqual } from 'node:crypto';
import { modelOutputLimit } from './model-output-limit.mjs';

const root=resolve(dirname(fileURLToPath(import.meta.url)),'..');
const input=process.argv.slice(2);const flags={};let cli=[];
for(let i=0;i<input.length;i++) {
    if(input[i]==='--') { cli=input.slice(i+1);break; }
    if(!['--env-file','--chatgpt-project','--java'].includes(input[i]) || !input[i+1]) throw new Error('Use --env-file <path> [--chatgpt-project <path>] [--java <executable>] -- <CLI arguments>');
    flags[input[i]]=input[++i];
}
if(!flags['--env-file'] || !cli.length) throw new Error('An external --env-file and CLI arguments after -- are required');
const envFile=resolve(flags['--env-file']);
process.loadEnvFile(envFile);
const env={...process.env};
if(env.SF_JWT_PRIVATE_KEY_FILE) env.SF_JWT_PRIVATE_KEY_FILE=resolve(dirname(envFile),env.SF_JWT_PRIVATE_KEY_FILE);
if(env.SF_STORAGE_STATE_FILE) env.SF_STORAGE_STATE_FILE=resolve(dirname(envFile),env.SF_STORAGE_STATE_FILE);
if(!env.SF_LIGHTNING_ORIGIN && env.SF_BASE_URL) env.SF_LIGHTNING_ORIGIN=env.SF_BASE_URL.replace('.my.salesforce.com','.lightning.force.com');
const java=flags['--java'] || (env.JAVA_HOME?join(env.JAVA_HOME,'bin',process.platform==='win32'?'java.exe':'java'):'java');
const jar=join(root,'sf-e2e-cli','target','sf-e2e.jar');
if(!existsSync(jar)) throw new Error('Build the Java CLI with Maven before launching');
let bridge;let modelCalls=0;let child;const activeCalls=new Set();
const secrets=Object.entries(env).filter(([key,value])=>value && /KEY|TOKEN|PASSWORD|CLIENT_ID|USERNAME|OAUTH/.test(key)).map(([,value])=>value);
const redact=text=>secrets.reduce((value,secret)=>value.split(secret).join('[REDACTED]'),text);
try {
    if(['map-ui','learn-case','discover-ui'].includes(cli[0]) && flags['--chatgpt-project'] && !env.E2E_MODEL_API_KEY) {
        if(env.E2E_MODEL_PROVIDER && env.E2E_MODEL_PROVIDER!=='chatgpt') throw new Error('The subscription adapter requires E2E_MODEL_PROVIDER=chatgpt');
        const require=createRequire(join(resolve(flags['--chatgpt-project']),'package.json'));
        const {chatgpt}=await import(pathToFileURL(require.resolve('e2e/oauth/chatgpt')).href);
        const {generateText}=await import(pathToFileURL(require.resolve('ai')).href);
        const key=randomBytes(32).toString('hex');secrets.push(key);
        bridge=createServer(async (request,response)=>{
            const auth=Buffer.from(request.headers.authorization||'');const expected=Buffer.from('Bearer '+key);
            if(request.method!=='POST' || request.url!=='/v1/chat/completions' || auth.length!==expected.length || !timingSafeEqual(auth,expected)) {
                response.writeHead(403).end();return;
            }
            const abort=new AbortController();activeCalls.add(abort);
            const cancel=()=>{if(!response.writableEnded) abort.abort();};response.once('close',cancel);
            try {
                let body='';for await(const chunk of request) { body+=chunk;if(Buffer.byteLength(body)>2*1024*1024) throw new Error('RequestTooLarge'); }
                const data=JSON.parse(body);
                if(data.model!==env.E2E_MODEL || !Array.isArray(data.messages) || data.messages.length!==2 || ++modelCalls>40) throw new Error('InvalidRequest');
                console.log('ChatGPT mapping request '+modelCalls+' started.');
                const instructions=data.messages.filter(message=>message.role==='system').map(message=>message.content).join('\n');
                const messages=data.messages.filter(message=>message.role!=='system');
                const result=await generateText({model:chatgpt(data.model),instructions,messages,
                    abortSignal:AbortSignal.any([AbortSignal.timeout(180_000),abort.signal]),maxOutputTokens:modelOutputLimit(data),maxRetries:0});
                let content=result.text.trim();
                // Normalize a whole-response JSON fence. Java validates JSON/schema before any write.
                const fenced=/^```(?:json)?\s*\n([\s\S]*?)\n```$/i.exec(content);
                if(fenced) content=fenced[1].trim();
                const tokens=result.usage?.totalTokens;
                let valid=false;try {JSON.parse(content);valid=true;} catch {}
                console.log('ChatGPT mapping request '+modelCalls+' '+(valid?'completed':'returned invalid JSON')+'; finish='+result.finishReason+'; chars='+content.length+'; tokens='+String(tokens??'unknown')+'.');
                const payload={choices:[{finish_reason:result.finishReason,message:{content}}]};
                if(Number.isSafeInteger(tokens) && tokens>=0) payload.usage={total_tokens:tokens};
                response.writeHead(200,{'Content-Type':'application/json'}).end(JSON.stringify(payload));
            } catch(error) {
                if(abort.signal.aborted) return;
                const code=Number.isInteger(error.statusCode)?'HTTP '+error.statusCode:String(error.name||'ProviderError');
                console.error('ChatGPT mapping request failed: '+code+' (provider response and credentials withheld).');
                try {
                    const detail=JSON.parse(error.responseBody).error;
                    const metadata={};
                    for(const field of ['code','type','param']) if(typeof detail?.[field]==='string' && /^[a-zA-Z0-9_.-]{1,100}$/.test(detail[field])) metadata[field]=detail[field];
                    if(Object.keys(metadata).length) console.error('Provider error metadata: '+JSON.stringify(metadata));
                    if(typeof detail?.message==='string' && /^Unsupported parameter: ['"]?[a-zA-Z0-9_]+['"]?\.?$/.test(detail.message)) console.error(detail.message);
                } catch {}
                response.writeHead(502,{'Content-Type':'application/json'}).end('{"error":"MODEL_PROVIDER_FAILED"}');
            } finally { response.off('close',cancel);activeCalls.delete(abort); }
        });
        await new Promise(resolve=>bridge.listen(0,'127.0.0.1',resolve));
        env.E2E_MODEL_ENDPOINT='http://127.0.0.1:'+bridge.address().port+'/v1/chat/completions';env.E2E_MODEL_API_KEY=key;
        console.log('Reusing the existing e2e ChatGPT login through a private loopback adapter.');
    }
    const jvm=[];
    if(env.SF_JWT_AUDIENCE) jvm.push('-Dsf.e2e.auth.audience='+env.SF_JWT_AUDIENCE);
    if(env.SF_TIME_ZONE) jvm.push('-Dsf.e2e.ui.personaTimeZone='+env.SF_TIME_ZONE);
    child=spawn(java,[...jvm,'-jar',jar,...cli],{cwd:root,env,windowsHide:true,stdio:['ignore','pipe','pipe']});
    child.stdout.on('data',data=>process.stdout.write(redact(data.toString())));
    child.stderr.on('data',data=>process.stderr.write(redact(data.toString())));
    process.once('SIGINT',()=>child.kill());
    process.exitCode=await new Promise((resolve,reject)=>{child.once('error',reject);child.once('exit',code=>resolve(code??130));});
} catch(error) {
    console.error('Launcher failed: '+redact(error.message));process.exitCode=2;
} finally { for(const call of activeCalls) call.abort();if(bridge) {bridge.closeAllConnections();await new Promise(resolve=>bridge.close(resolve));} }
