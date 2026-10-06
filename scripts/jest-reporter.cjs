const fs = require('node:fs');
const path = require('node:path');
const escape = value => String(value).replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&apos;' }[char]));

/** Deterministic interchange only. Raw Jest failure messages go exclusively to the private diagnostics file. */
module.exports = class FrameworkJestReporter {
  onRunComplete(contexts, result) {
    const project = process.env.SF_E2E_PROJECT;
    const selected = new Set(JSON.parse(process.env.SF_E2E_SELECTED_TESTS));
    const tests = [], diagnostics = [], identities = new Set(), seenFiles = new Set();
    for (const suite of result.testResults) {
      const real = fs.realpathSync(suite.testFilePath);
      if (!selected.has(real)) throw new Error('COMPONENT_UNSELECTED_RESULT');
      if (seenFiles.has(real)) throw new Error('COMPONENT_DUPLICATE_FILE_RESULT');
      seenFiles.add(real);
      const className = path.relative(project, real).split(path.sep).join('/');
      for (const assertion of suite.testResults) {
        const name = assertion.fullName.startsWith('TST_') ? assertion.fullName : `TST_${assertion.fullName}`;
        const identity = className + '|' + name;
        if (identities.has(identity)) throw new Error('COMPONENT_DUPLICATE_RESULT');
        identities.add(identity);
        const status = assertion.status === 'passed' ? 'PASSED' : assertion.status === 'failed' ? 'FAILED' : 'SKIPPED';
        const duration = assertion.duration ?? 0;
        if (!Number.isFinite(duration) || duration < 0 || duration > 86400000) throw new Error('COMPONENT_INVALID_DURATION');
        tests.push({ name, className, status, duration });
        if (status === 'FAILED') diagnostics.push({ name, className, messages: (assertion.failureMessages ?? []).map(text => String(text).slice(0,8000)) });
      }
      if (suite.testExecError) { tests.push({ name: 'TST_ComponentInfrastructure', className, status: 'FAILED', duration: 0 }); diagnostics.push({ className, error: String(suite.failureMessage ?? suite.testExecError.message ?? '').slice(0,16000) }); }
    }
    if ([...selected].some(file => !seenFiles.has(file))) {
      tests.push({ name: 'TST_ComponentSelectionIncomplete', className: 'infrastructure', status: 'FAILED', duration: 0 });
      diagnostics.push({ code: 'COMPONENT_SELECTION_INCOMPLETE', missing: [...selected].filter(file => !seenFiles.has(file)) });
    }
    if (result.numFailedTestSuites > 0 && !tests.some(test => test.status === 'FAILED')) tests.push({ name: 'TST_ComponentRunnerFailure', className: 'infrastructure', status: 'FAILED', duration: 0 });
    if (tests.length > 100000) throw new Error('COMPONENT_RESULT_LIMIT');
    const failed = tests.filter(test => test.status === 'FAILED').length, skipped = tests.filter(test => test.status === 'SKIPPED').length;
    let xml = `<?xml version="1.0" encoding="UTF-8"?><testsuite name="LWC component checks" tests="${tests.length}" failures="${failed}" skipped="${skipped}">`;
    for (const test of tests) xml += `<testcase name="${escape(test.name)}" classname="${escape(test.className)}" time="${test.duration/1000}">${test.status === 'FAILED' ? '<failure message="Component assertion or infrastructure failed"/>' : test.status === 'SKIPPED' ? '<skipped/>' : ''}</testcase>`;
    xml += '</testsuite>';
    const detail = JSON.stringify({ schemaVersion: 1, tests: tests.length, failed, skipped, diagnostics });
    if (Buffer.byteLength(xml) > 8*1024*1024 || Buffer.byteLength(detail) > 8*1024*1024) throw new Error('COMPONENT_RESULT_LIMIT');
    fs.writeFileSync(process.env.SF_E2E_DIAGNOSTICS_OUT, detail, { flag: 'wx' });
    fs.writeFileSync(process.env.SF_E2E_JUNIT_OUT, xml, { flag: 'wx' });
  }
};
