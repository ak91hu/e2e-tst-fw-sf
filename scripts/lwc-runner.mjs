import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

// Invoked by the version-pinned Java component runner. Consumer code/config is explicitly authorized code.
const [mode, planFile, junitFile, diagnosticsFile] = process.argv.slice(2);
function requireCondition(condition, code) { if (!condition) throw new Error(code); }
function contained(root, candidate) { const relative = path.relative(root, candidate); return relative !== '' && !relative.startsWith(`..${path.sep}`) && relative !== '..' && !path.isAbsolute(relative); }
try {
  requireCondition(['probe', 'run'].includes(mode), 'COMPONENT_PLAN_INVALID');
  requireCondition(fs.statSync(planFile).size <= 1024 * 1024, 'COMPONENT_PLAN_INVALID');
  const plan = JSON.parse(fs.readFileSync(planFile, 'utf8'));
  const project = fs.realpathSync(plan.projectDirectory);
  requireCondition(fs.statSync(path.join(project, 'sfdx-project.json')).isFile(), 'COMPONENT_PROJECT_REQUIRED');
  requireCondition(process.version === plan.expectedNodeVersion, 'RUNNER_VERSION_CHANGED');
  const resolver = createRequire(path.join(project, 'package.json'));
  const packageFile = resolver.resolve('@salesforce/sfdx-lwc-jest/package.json');
  const pkg = JSON.parse(fs.readFileSync(packageFile, 'utf8'));
  const manifest = JSON.parse(fs.readFileSync(path.join(project, 'package.json'), 'utf8'));
  requireCondition(pkg.name === '@salesforce/sfdx-lwc-jest' && pkg.version === plan.expectedLwcJestVersion && manifest.devDependencies?.[pkg.name] === pkg.version, 'RUNNER_VERSION_CHANGED');
  requireCondition(fs.statSync(path.join(project, 'package-lock.json')).isFile(), 'COMPONENT_LOCK_REQUIRED');
  const lock = JSON.parse(fs.readFileSync(path.join(project, 'package-lock.json'), 'utf8'));
  requireCondition(lock.packages?.['node_modules/@salesforce/sfdx-lwc-jest']?.version === pkg.version && lock.packages?.['']?.devDependencies?.[pkg.name] === pkg.version, 'COMPONENT_LOCK_CHANGED');
  const version = `node=${process.version};sfdx-lwc-jest=${pkg.version}`;
  if (mode === 'probe') { process.stdout.write(version + '\n'); }
  else {
    requireCondition(Array.isArray(plan.tests) && plan.tests.length > 0 && plan.tests.length <= 1000, 'NO_TESTS');
    const tests = plan.tests.map(test => {
      requireCondition(typeof test === 'string' && !path.isAbsolute(test) && !test.includes('\\') && !test.includes(':') && !test.split('/').some(part => !part || part === '.' || part === '..') && /(?:^|\/)__tests__\/[^/]+\.(?:test|spec)\.js$/.test(test), 'COMPONENT_TEST_PATH_INVALID');
      const real = fs.realpathSync(path.join(project, test));
      requireCondition(contained(project, real) && fs.statSync(real).isFile(), 'COMPONENT_TEST_PATH_INVALID');
      return real;
    });
    requireCondition(new Set(tests).size === tests.length, 'COMPONENT_DUPLICATE_TEST');
    requireCondition(!fs.existsSync(junitFile) && !fs.existsSync(diagnosticsFile), 'RESULT_ALREADY_EXISTS');
    const bin = typeof pkg.bin === 'string' ? pkg.bin : pkg.bin?.['sfdx-lwc-jest'];
    requireCondition(typeof bin === 'string', 'COMPONENT_RUNNER_INVALID');
    const executable = fs.realpathSync(path.resolve(path.dirname(packageFile), bin));
    requireCondition(contained(fs.realpathSync(path.dirname(packageFile)), executable), 'COMPONENT_RUNNER_INVALID');
    const reporter = path.join(path.dirname(fileURLToPath(import.meta.url)), 'jest-reporter.cjs');
    const environment = { ...process.env };
    for (const key of Object.keys(environment)) if (/^(?:SF_|SFDX_|SALESFORCE_)|(?:TOKEN|PASSWORD|SECRET|PRIVATE_KEY|API_KEY|CLIENT_ID|USERNAME)$/i.test(key)) delete environment[key];
    Object.assign(environment, { SF_E2E_JUNIT_OUT: path.resolve(junitFile), SF_E2E_DIAGNOSTICS_OUT: path.resolve(diagnosticsFile), SF_E2E_PROJECT: project, SF_E2E_SELECTED_TESTS: JSON.stringify(tests) });
    const child = spawn(process.execPath, [executable, '--', '--runInBand', '--ci', '--reporters', 'default', '--reporters', reporter, '--runTestsByPath', ...tests], { cwd: project, env: environment, stdio: 'ignore', windowsHide: true });
    child.on('error', () => { process.exitCode = 2; });
    child.on('exit', code => { process.exitCode = code === null ? 2 : code; });
  }
} catch (failure) { process.stderr.write((failure.message || 'COMPONENT_RUNNER_FAILED') + '\n'); process.exitCode = 2; }
