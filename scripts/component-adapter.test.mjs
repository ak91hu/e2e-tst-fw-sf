import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
const runner = fileURLToPath(new URL('./lwc-runner.mjs', import.meta.url));
function fixture() {
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'sf-component-contract-')),pkg=path.join(root,'node_modules/@salesforce/sfdx-lwc-jest');fs.mkdirSync(pkg,{recursive:true});
  fs.writeFileSync(path.join(root,'sfdx-project.json'),'{}');fs.writeFileSync(path.join(root,'package.json'),JSON.stringify({devDependencies:{'@salesforce/sfdx-lwc-jest':'1.2.3'}}));
  fs.writeFileSync(path.join(root,'package-lock.json'),JSON.stringify({packages:{'':{devDependencies:{'@salesforce/sfdx-lwc-jest':'1.2.3'}},'node_modules/@salesforce/sfdx-lwc-jest':{version:'1.2.3'}}}));
  fs.writeFileSync(path.join(pkg,'package.json'),JSON.stringify({name:'@salesforce/sfdx-lwc-jest',version:'1.2.3',bin:{'sfdx-lwc-jest':'bin.cjs'}}));
  fs.writeFileSync(path.join(pkg,'bin.cjs'),`const args=process.argv.slice(2);if(!args.includes('--ci')||!args.includes('--runInBand')||args.includes('-u')||process.env.SF_TEST_TOKEN||process.env.API_SECRET)process.exit(7);const Reporter=require(args[args.lastIndexOf('--reporters')+1]);const file=args.at(-1);new Reporter().onRunComplete(null,{numFailedTestSuites:0,testResults:[{testFilePath:file,testResults:[{fullName:'component contract',status:'passed',duration:4}]}]});`);
  const file='force-app/main/default/lwc/probe/__tests__/probe.test.js';fs.mkdirSync(path.dirname(path.join(root,file)),{recursive:true});fs.writeFileSync(path.join(root,file),'// selected fixture');
  const plan={projectDirectory:root,expectedNodeVersion:process.version,expectedLwcJestVersion:'1.2.3',tests:[file]},planFile=path.join(root,'plan.json'),xml=path.join(root,'results.xml'),detail=path.join(root,'diagnostics.json');
  const save=()=>fs.writeFileSync(planFile,JSON.stringify(plan));save();const run=mode=>spawnSync(process.execPath,[runner,mode,planFile,xml,detail],{encoding:'utf8',env:{...process.env,SF_TEST_TOKEN:'INTERNAL_TOKEN',API_SECRET:'INTERNAL_SECRET'},timeout:10000,windowsHide:true});
  return {root,plan,xml,detail,save,run};
}
test('adapter enforces pins, exact tests, CI snapshots and credential removal',()=>{const f=fixture();assert.equal(f.run('probe').stdout.trim(),`node=${process.version};sfdx-lwc-jest=1.2.3`);assert.equal(f.run('run').status,0);const xml=fs.readFileSync(f.xml,'utf8');assert.match(xml,/TST_component contract/);assert.match(xml,/tests="1"/);assert.doesNotMatch(xml,/INTERNAL_TOKEN|INTERNAL_SECRET/);});
test('wrong runtime or dependency pins cannot dispatch tests',()=>{for(const key of ['expectedNodeVersion','expectedLwcJestVersion']){const f=fixture();f.plan[key]='wrong';f.save();assert.equal(f.run('probe').status,2);assert.equal(fs.existsSync(f.xml),false);}});
test('a selected file missing from Jest results cannot certify a component layer',async()=>{
  const {createRequire}=await import('node:module');const Reporter=createRequire(import.meta.url)('./jest-reporter.cjs');const f=fixture(),file=path.join(f.root,f.plan.tests[0]);
  const names=['SF_E2E_JUNIT_OUT','SF_E2E_DIAGNOSTICS_OUT','SF_E2E_PROJECT','SF_E2E_SELECTED_TESTS'],old=Object.fromEntries(names.map(key=>[key,process.env[key]]));
  try {
    Object.assign(process.env,{SF_E2E_JUNIT_OUT:f.xml,SF_E2E_DIAGNOSTICS_OUT:f.detail,SF_E2E_PROJECT:f.root,SF_E2E_SELECTED_TESTS:JSON.stringify([file,path.join(f.root,'missing-from-results.test.js')])});
    new Reporter().onRunComplete(null,{numFailedTestSuites:0,testResults:[{testFilePath:file,testResults:[{fullName:'passed assertion',status:'passed',duration:1}]}]});
    assert.match(fs.readFileSync(f.xml,'utf8'),/failures="1"/);assert.match(fs.readFileSync(f.xml,'utf8'),/TST_ComponentSelectionIncomplete/);
  } finally {for(const key of names)if(old[key]===undefined)delete process.env[key];else process.env[key]=old[key];}
});
test('contained tests, duplicates and stale results are rejected',()=>{for(const tests of [['../private.test.js'],['force-app/main/default/lwc/probe/__tests__/probe.test.js','force-app/main/default/lwc/probe/__tests__/probe.test.js']]){const f=fixture();f.plan.tests=tests;f.save();assert.equal(f.run('run').status,2);assert.equal(fs.existsSync(f.xml),false);}const f=fixture();fs.writeFileSync(f.xml,'preserved');assert.equal(f.run('run').status,2);assert.equal(fs.readFileSync(f.xml,'utf8'),'preserved');});
test('reporter retains failure and skipped verdicts without publishing raw messages',async()=>{const {createRequire}=await import('node:module');const Reporter=createRequire(import.meta.url)('./jest-reporter.cjs');const f=fixture(),selected=path.join(f.root,f.plan.tests[0]);const names=['SF_E2E_JUNIT_OUT','SF_E2E_DIAGNOSTICS_OUT','SF_E2E_PROJECT','SF_E2E_SELECTED_TESTS'],old=Object.fromEntries(names.map(k=>[k,process.env[k]]));try{Object.assign(process.env,{SF_E2E_JUNIT_OUT:f.xml,SF_E2E_DIAGNOSTICS_OUT:f.detail,SF_E2E_PROJECT:f.root,SF_E2E_SELECTED_TESTS:JSON.stringify([selected])});new Reporter().onRunComplete(null,{numFailedTestSuites:1,testResults:[{testFilePath:selected,testResults:[{fullName:'wrong expectation',status:'failed',duration:2,failureMessages:['PRIVATE_ASSERTION_VALUE']},{fullName:'pending',status:'pending'}]}]});const xml=fs.readFileSync(f.xml,'utf8');assert.match(xml,/failures="1"/);assert.match(xml,/skipped="1"/);assert.doesNotMatch(xml,/PRIVATE_ASSERTION_VALUE/);assert.match(fs.readFileSync(f.detail,'utf8'),/PRIVATE_ASSERTION_VALUE/);}finally{for(const k of names)if(old[k]===undefined)delete process.env[k];else process.env[k]=old[k];}});
