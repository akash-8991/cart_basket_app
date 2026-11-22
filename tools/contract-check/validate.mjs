#!/usr/bin/env node
// Validates:
//  1. Every fixture in contracts/fixtures/manifest.json against its OpenAPI component schema.
//  2. Every standalone JSON Schema in contracts/schemas compiles.
//  3. Contract invariants that JSON Schema cannot express (see checkInvariants).
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';
import YAML from 'yaml';

const HERE = dirname(fileURLToPath(import.meta.url));
const CONTRACTS = resolve(HERE, '../../contracts');
const openapi = YAML.parse(readFileSync(join(CONTRACTS, 'openapi/openapi.yaml'), 'utf8'));

// Rewrite OpenAPI refs to a JSON Schema $defs document.
const defs = JSON.parse(JSON.stringify(openapi.components.schemas).replaceAll('#/components/schemas/', 'api#/$defs/'));
const ajv = new Ajv2020({ allErrors: true, strict: true, strictRequired: false });
addFormats(ajv);
ajv.addSchema({ $id: 'api', $defs: defs });

let failures = 0;
const fail = (msg) => { failures++; console.error(`✗ ${msg}`); };

// 1. fixtures
const manifest = JSON.parse(readFileSync(join(CONTRACTS, 'fixtures/manifest.json'), 'utf8'));
const errorCodes = YAML.parse(readFileSync(join(CONTRACTS, 'error-codes.yaml'), 'utf8')).codes;
for (const { file, schema, status } of manifest.fixtures) {
  const data = JSON.parse(readFileSync(join(CONTRACTS, 'fixtures', file), 'utf8'));
  const validate = ajv.getSchema(`api#/$defs/${schema}`);
  if (!validate) { fail(`${file}: unknown schema ${schema}`); continue; }
  if (!validate(data)) {
    fail(`${file} (${schema}):\n    ${validate.errors.map((e) => `${e.instancePath} ${e.message}`).join('\n    ')}`);
    continue;
  }
  if (schema === 'ErrorEnvelope') {
    const meta = errorCodes[data.error.code];
    if (!meta) fail(`${file}: error code ${data.error.code} missing from error-codes.yaml`);
    else if (meta.http !== status) fail(`${file}: status ${status} != catalogue ${meta.http} for ${data.error.code}`);
  }
  checkInvariants(file, schema, data);
}

// Error-code enum and catalogue must match exactly.
const enumCodes = new Set(openapi.components.schemas.ErrorCode.enum);
const catCodes = new Set(Object.keys(errorCodes));
for (const c of enumCodes) if (!catCodes.has(c)) fail(`ErrorCode ${c} missing from error-codes.yaml`);
for (const c of catCodes) if (!enumCodes.has(c)) fail(`error-codes.yaml ${c} missing from ErrorCode enum`);

// 2. standalone schemas compile
const schemaAjv = new Ajv2020({ strict: true });
addFormats(schemaAjv);
const schemaFiles = [];
(function walk(dir) {
  for (const f of readdirSync(dir)) {
    const p = join(dir, f);
    if (statSync(p).isDirectory()) walk(p); else if (f.endsWith('.json')) schemaFiles.push(p);
  }
})(join(CONTRACTS, 'schemas'));
const loaded = schemaFiles.map((p) => JSON.parse(readFileSync(p, 'utf8')));
for (const s of loaded) schemaAjv.addSchema(s);
for (const s of loaded) {
  try { schemaAjv.getSchema(s.$id); } catch (e) { fail(`${s.$id}: ${e.message}`); }
}

// 3. invariants
function checkInvariants(file, schema, d) {
  if (schema === 'ComparisonQuote') {
    if (d.recommendation.label === 'cheapest' && (!d.coverage.complete || d.missing_fields.length > 0)) {
      fail(`${file}: 'cheapest' requires complete coverage and no missing fields`);
    }
    for (const r of d.provider_results) {
      if (!r.total_estimate) continue;
      const c = r.components;
      const add = ['item_subtotal', 'delivery_fee', 'packaging_fee', 'platform_fee', 'small_cart_fee', 'surge_or_booking_fee', 'taxes']
        .reduce((a, k) => a + (c[k]?.amount_minor ?? 0), 0);
      const total = add - (c.discounts?.amount_minor ?? 0);
      if (total !== r.total_estimate.amount_minor) fail(`${file}: ${r.provider} total ${r.total_estimate.amount_minor} != components ${total}`);
      if (total < 0) fail(`${file}: ${r.provider} negative total`);
    }
    if (d.status === 'price_changed' && !d.previous_total_estimate) fail(`${file}: price_changed requires previous_total_estimate`);
    if (d.recommendation.provider && !d.provider_results.some((r) => r.provider === d.recommendation.provider && r.rank === 1) && d.objective === 'lowest_total') {
      fail(`${file}: recommended provider is not rank 1 for lowest_total`);
    }
  }
  if (schema === 'CartTask') {
    if (d.status === 'CART_READY' && !d.cart?.verified) fail(`${file}: CART_READY requires a verified cart`);
    if (d.status === 'CHECKPOINT' && !d.checkpoint) fail(`${file}: CHECKPOINT requires checkpoint`);
    if (d.status === 'NEEDS_USER_ACTION' && !d.needs_user_action) fail(`${file}: NEEDS_USER_ACTION requires needs_user_action`);
    if (['FAILED_SAFE', 'BLOCKED_POLICY'].includes(d.status) && !d.failure) fail(`${file}: ${d.status} requires failure`);
    if (d.planned_actions.some((a) => a.risk === 'prohibited')) fail(`${file}: prohibited actions may never be planned`);
  }
  if (schema === 'Handoff' && !/^smartcart:\/\/handoff\/[A-Za-z0-9_-]{16,128}$/.test(d.handoff_url)) {
    fail(`${file}: handoff_url must be an opaque token URL`);
  }
}

// Recovery responses must be indistinguishable apart from message wording.
const fp = JSON.parse(readFileSync(join(CONTRACTS, 'fixtures/auth/forgot-password.accepted.json'), 'utf8'));
const fu = JSON.parse(readFileSync(join(CONTRACTS, 'fixtures/auth/forgot-user-id.accepted.json'), 'utf8'));
if (Object.keys(fp).join() !== Object.keys(fu).join()) fail('recovery responses differ in shape');

if (failures) { console.error(`\n${failures} contract check(s) failed.`); process.exit(1); }
console.log(`✓ ${manifest.fixtures.length} fixtures valid, ${loaded.length} schemas compile, invariants hold.`);
