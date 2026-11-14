import assert from 'node:assert/strict';
import { test } from 'node:test';
import { isValidHostname } from './site-manager';

const suffix = 'sites.example.test';

test('accepts a single generated hostname under the configured suffix', () => {
  assert.equal(isValidHostname('demo-1234.sites.example.test', suffix), true);
});

test('rejects hostnames outside the suffix or with additional subdomains', () => {
  assert.equal(isValidHostname('demo.sites.example.test.attacker.invalid', suffix), false);
  assert.equal(isValidHostname('nested.demo.sites.example.test', suffix), false);
  assert.equal(isValidHostname('sites.example.test', suffix), false);
});
