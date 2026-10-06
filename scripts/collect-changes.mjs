import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { parseArgs } from 'node:util';
try {
  const { values } = parseArgs({ options: { repo: { type: 'string', default: '.' }, base: { type: 'string' }, head: { type: 'string', default: 'HEAD' }, output: { type: 'string' } } });
  if (!values.base || !values.output || fs.existsSync(values.output)) throw new Error('Supply --base and a new --output file');
  const repo = fs.realpathSync(values.repo);
  const git = args => execFileSync('git', args, { cwd: repo, encoding: 'utf8', maxBuffer: 2*1024*1024, windowsHide: true });
  const commit = ref => {
    if (ref.startsWith('-') || ref.length > 200 || /[\x00-\x1f]/.test(ref)) throw new Error('Invalid Git revision');
    const resolved = git(['rev-parse', '--verify', '--end-of-options', `${ref}^{commit}`]).trim();
    if (!/^[a-f0-9]{40,64}$/.test(resolved)) throw new Error('Unverified Git commit');
    return resolved;
  };
  const files = [...new Set(git(['diff', '--name-only', '--no-renames', '-z', commit(values.base), commit(values.head), '--']).split('\0').filter(Boolean))].sort();
  if (files.length > 10000 || files.some(file => file.startsWith('/') || file.includes('\\') || file.includes(':') || /[\x00-\x1f*?]/.test(file) || file.split('/').some(part => !part || part === '.' || part === '..'))) throw new Error('Unsupported changed path or limit');
  fs.mkdirSync(path.dirname(path.resolve(values.output)), { recursive: true });fs.writeFileSync(values.output, JSON.stringify({ schemaVersion: 1, files }, null, 2), { flag: 'wx' });
  process.stdout.write(`Collected ${files.length} changed path(s)\n`);
} catch (error) { process.stderr.write((error.message || 'Change collection failed') + '\n'); process.exitCode = 2; }
