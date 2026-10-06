import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {execFileSync,spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
const script=fileURLToPath(new URL('./collect-changes.mjs',import.meta.url));
test('Git collector includes both sides of a rename and preserves outputs',()=>{
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'sf-impact-git-'));const git=args=>execFileSync('git',args,{cwd:root,encoding:'utf8',windowsHide:true});git(['init','-q']);fs.writeFileSync(path.join(root,'old.js'),'same');git(['add','.']);git(['-c','user.name=InternalFixture','-c','user.email=fixture@example.invalid','commit','-qm','initial']);const base=git(['rev-parse','HEAD']).trim();fs.renameSync(path.join(root,'old.js'),path.join(root,'new.js'));git(['add','-A']);git(['-c','user.name=InternalFixture','-c','user.email=fixture@example.invalid','commit','-qm','rename']);const output=path.join(root,'changes.json');const args=[script,'--repo',root,'--base',base,'--output',output];assert.equal(spawnSync(process.execPath,args,{windowsHide:true}).status,0);assert.deepEqual(JSON.parse(fs.readFileSync(output,'utf8')).files,['new.js','old.js']);assert.equal(spawnSync(process.execPath,args,{windowsHide:true}).status,2);
});
test('missing and option-like revisions cannot create a change manifest',()=>{const root=fs.mkdtempSync(path.join(os.tmpdir(),'sf-impact-invalid-')),output=path.join(root,'changes.json');assert.equal(spawnSync(process.execPath,[script,'--repo',root,'--base','--bad','--output',output],{windowsHide:true}).status,2);assert.equal(fs.existsSync(output),false);});
