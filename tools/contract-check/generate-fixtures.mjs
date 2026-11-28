#!/usr/bin/env node
// Generates contracts/fixtures/** deterministically so totals are always arithmetically consistent.
// Run: npm run fixtures   (then `npm run validate` checks every file against the OpenAPI schemas)
// All provider data here is fictional mock data for UI states. Prices, fees and ETAs are NOT real.
import { mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../../contracts/fixtures');
const manifest = [];

function out(file, schema, data, status = 200) {
  const path = join(ROOT, file);
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, JSON.stringify(data, null, 2) + '\n');
  manifest.push({ file, schema, status });
}

const INR = (rupees) => ({ amount_minor: Math.round(rupees * 100), currency: 'INR' });
const sum = (xs) => xs.reduce((a, b) => a + b, 0);
const NOW = '2026-11-22T05:00:00Z';
const LATER = '2026-11-22T05:05:00Z';
const at = (min) => new Date(Date.parse(NOW) + min * 60000).toISOString().replace('.000', '');

function err(code, message, extra = {}) {
  return { error: { code, message, correlation_id: '6f1c2a90-5a7b-4c1e-9d0a-2b7e4f8c1a33', retry_after_seconds: null, ...extra } };
}

// ---------------------------------------------------------------- auth
const tokens = { access_token: 'mock-access-token', refresh_token: 'mock-refresh-token', access_expires_in_seconds: 900 };
out('auth/login.success.json', 'AuthSession', { user_id: 'priya.k', session_id: 'ses_01', tokens, email_verification_required: false, session_expires_at: '2026-12-22T05:00:00Z' });
out('auth/register.success.json', 'AuthSession', { user_id: 'priya.k', session_id: 'ses_01', tokens, email_verification_required: true, session_expires_at: '2026-12-22T05:00:00Z' }, 201);
out('auth/refresh.success.json', 'TokenPair', { ...tokens, access_token: 'mock-access-token-2', refresh_token: 'mock-refresh-token-2' });
out('auth/login.invalid-credentials.json', 'ErrorEnvelope', err('invalid_credentials', 'User ID or password is incorrect.'), 401);
out('auth/login.account-locked.json', 'ErrorEnvelope', err('account_locked', 'Too many attempts. Try again later.', { retry_after_seconds: 300 }), 429);
out('auth/refresh.session-expired.json', 'ErrorEnvelope', err('session_expired', 'Please sign in again.'), 401);
out('auth/register.user-id-unavailable.json', 'ErrorEnvelope', err('user_id_unavailable', 'That user ID is not available.', { field_errors: [{ field: 'user_id', code: 'user_id_unavailable' }] }), 409);
out('auth/register.weak-password.json', 'ErrorEnvelope', err('weak_password', 'Choose a stronger password.', { field_errors: [{ field: 'password', code: 'weak_password' }] }), 400);
const recovery = { message: 'If the details match an account, we have sent instructions to the registered email.', challenge_id: 'chl_7Qm2', expires_in_seconds: 900 };
out('auth/forgot-password.accepted.json', 'RecoveryAccepted', recovery, 202);
out('auth/forgot-user-id.accepted.json', 'RecoveryAccepted', { ...recovery, message: 'If the email is registered, we have sent your user ID to it.' }, 202);
out('auth/recovery.rate-limited.json', 'ErrorEnvelope', err('rate_limited', 'Too many requests. Try again later.', { retry_after_seconds: 1800 }), 429);
out('auth/reset-password.success.json', 'ResetPasswordResponse', { password_reset: true, all_other_sessions_revoked: true });
out('auth/reset-password.expired.json', 'ErrorEnvelope', err('challenge_expired', 'This code has expired. Start again.'), 410);
out('auth/sessions.json', 'SessionList', {
  sessions: [
    { session_id: 'ses_01', device_label: 'Pixel 8', platform: 'android', app_version: '1.0.0', approximate_location: 'Bengaluru, IN', last_seen_at: NOW, current: true },
    { session_id: 'ses_02', device_label: 'Galaxy S23', platform: 'android', app_version: '0.9.4', approximate_location: 'Pune, IN', last_seen_at: '2026-11-10T14:22:00Z', current: false },
  ],
});
out('auth/revoke-all.json', 'RevokeAllResponse', { revoked_count: 1 });

// ---------------------------------------------------------------- me
out('me/profile.json', 'Profile', { user_id: 'priya.k', email_masked: 'p***@e***.com', email_verified: true, privacy_mode: 'normalized_upload', created_at: '2026-06-02T09:00:00Z' });
const consentKeys = ['ai_processing', 'notifications', 'automation.blinkit', 'automation.zomato', 'history.blinkit', 'history.swiggy'];
out('me/consents.json', 'ConsentList', { consents: consentKeys.map((key, i) => ({ key, granted: i !== 5, version: '2026-11', updated_at: '2026-11-01T10:00:00Z' })) });
out('me/activity.json', 'ActivityPage', {
  events: [
    // summary_key / summary_args (CR-002 E) let the app render its own copy; summary is the English fallback.
    { event_id: 'evt_5', at: at(-30), type: 'automation_completed', summary: 'Cart prepared in Blinkit (4 items).', summary_key: 'automation_completed', summary_args: { provider_name: 'Blinkit', count: '4' }, provider: 'blinkit', task_id: 'tsk_grocery_01' },
    { event_id: 'evt_4', at: at(-34), type: 'automation_approved', summary: 'You approved adding 4 items in Blinkit.', summary_key: 'automation_approved', summary_args: { provider_name: 'Blinkit', count: '4' }, provider: 'blinkit', task_id: 'tsk_grocery_01' },
    { event_id: 'evt_3', at: '2026-11-18T08:12:00Z', type: 'automation_stopped', summary: 'You pressed Stop during a Swiggy task.', summary_key: 'automation_stopped', summary_args: { provider_name: 'Swiggy' }, provider: 'swiggy', task_id: 'tsk_food_07' },
    { event_id: 'evt_2', at: '2026-11-15T19:40:00Z', type: 'history_imported', summary: 'Imported 18 orders from Blinkit and Zomato.', summary_key: 'history_imported', summary_args: { count: '18', provider_names: 'Blinkit and Zomato' }, provider: null, task_id: null },
    { event_id: 'evt_1', at: '2026-11-01T10:00:00Z', type: 'consent_granted', summary: 'Allowed automation for Blinkit.', summary_key: 'consent_granted.automation', summary_args: { provider_name: 'Blinkit' }, provider: 'blinkit', task_id: null },
  ],
  next_cursor: null,
});
out('me/activity.empty.json', 'ActivityPage', { events: [], next_cursor: null });
out('me/export.accepted.json', 'DataRequestAccepted', { request_id: 'exp_01', status: 'queued', message: 'Your export will be emailed to your registered address.', sla_days: 7 }, 202);
out('me/delete.accepted.json', 'DataRequestAccepted', { request_id: 'del_01', status: 'queued', message: 'Deletion has started.', sla_days: 30 }, 202);

// ---------------------------------------------------------------- providers
const P = {
  uber: ['Uber', 'travel'], ola: ['Ola', 'travel'], rapido: ['Rapido', 'travel'],
  zomato: ['Zomato', 'food'], swiggy: ['Swiggy', 'food'],
  blinkit: ['Blinkit', 'grocery'], bigbasket: ['BigBasket', 'grocery'], swiggy_instamart: ['Swiggy Instamart', 'grocery'],
  amazon: ['Amazon', 'shopping'], flipkart: ['Flipkart', 'shopping'], zepto: ['Zepto', 'grocery'],
};
function provider(key) {
  const [display_name, category] = P[key];
  const travel = category === 'travel';
  const status = key === 'zepto' ? 'disabled' : (category === 'shopping' ? 'pending_approval' : 'enabled');
  return {
    key, display_name, category,
    capabilities: {
      cart_automation: travel ? 'ride_setup_only' : 'supported',
      history_import: 'supported',
      deep_link: key === 'bigbasket' ? 'app_launch' : 'cart',
      comparison: key === 'bigbasket' ? 'partial' : 'supported',
    },
    status, installed: null,
    consent: { automation: ['blinkit', 'zomato'].includes(key), history: ['blinkit', 'zomato'].includes(key) },
    adapter_version: status === 'enabled' ? '0.1.0-mock' : null,
    hidden: key === 'zepto', // CR-002 F (D-005)
  };
}
out('providers/capabilities.json', 'ProviderList', { providers: Object.keys(P).map(provider) });

// ---------------------------------------------------------------- config (CR-002 A)
out('config/config.json', 'ClientConfig', {
  flags: {
    'home.category.travel': true, 'home.category.food': true, 'home.category.grocery': true,
    'home.category.shopping': false, 'chat.ai.enabled': true, 'comparison.enabled': true, 'history.import.enabled': false,
  },
  policy_versions: { terms: '2026-11', privacy: '2026-11', consent: '2026-11' },
});

// ---------------------------------------------------------------- comparison
const GROCERY_LINES = [
  { line_id: 'l1', name: 'Toned milk', quantity: 2, size: '1 L', brand: null, modifiers: [] },
  { line_id: 'l2', name: 'Brown bread', quantity: 1, size: '400 g', brand: null, modifiers: [] },
  { line_id: 'l3', name: 'Eggs', quantity: 1, size: '12 pack', brand: null, modifiers: [] },
  { line_id: 'l4', name: 'Bananas', quantity: 1, size: '6 pcs', brand: null, modifiers: [] },
];
const FEES = ['delivery_fee', 'packaging_fee', 'platform_fee', 'small_cart_fee', 'surge_or_booking_fee', 'taxes'];

/** spec.lines: [[line_id, unitPrice|null, match?, matched_name?, matched_size?, note?]] ; spec.fees {name: rupees|null} */
function result(providerKey, linesSrc, spec) {
  const lines = spec.lines.map(([id, unit, match = 'exact', mName, mSize, note = null]) => {
    const src = linesSrc.find((l) => l.line_id === id);
    const available = unit !== null && match !== 'unavailable';
    return {
      line_id: id,
      requested_name: `${src.name}${src.size ? ' ' + src.size : ''}`,
      matched_name: available ? (mName ?? src.name) : null,
      matched_size: available ? (mSize ?? src.size) : null,
      quantity: src.quantity,
      unit_price: available ? INR(unit) : null,
      line_total: available ? INR(unit * src.quantity) : null,
      match: available ? match : 'unavailable',
      note,
    };
  });
  const subtotal = sum(lines.map((l) => l.line_total?.amount_minor ?? 0));
  const components = { item_subtotal: { amount_minor: subtotal, currency: 'INR' } };
  const unavailable_fields = [];
  for (const f of FEES) {
    const v = spec.fees?.[f];
    if (v === undefined) components[f] = null;
    else if (v === null) { components[f] = null; unavailable_fields.push(f); }
    else components[f] = INR(v);
  }
  components.discounts = spec.discount ? INR(spec.discount) : null;
  const total = subtotal + sum(FEES.map((f) => components[f]?.amount_minor ?? 0)) - (components.discounts?.amount_minor ?? 0);
  const anyUnavailable = lines.some((l) => l.match === 'unavailable');
  return {
    provider: providerKey,
    availability: spec.availability ?? (anyUnavailable ? 'partial' : 'available'),
    captured_at: spec.captured_at ?? NOW,
    lines,
    components,
    total_estimate: spec.noTotal ? null : { amount_minor: total, currency: 'INR' },
    eta_minutes: spec.eta ?? null,
    rank: null,
    unavailable_fields,
    notes: spec.notes ?? [],
  };
}
function notChecked(providerKey, availability = 'not_checked', note = 'Not checked.') {
  return { provider: providerKey, availability, captured_at: null, lines: [], components: {}, total_estimate: null, eta_minutes: null, rank: null, unavailable_fields: [], notes: [note] };
}
function rank(results) {
  const priced = results.filter((r) => r.total_estimate).sort((a, b) => a.total_estimate.amount_minor - b.total_estimate.amount_minor);
  priced.forEach((r, i) => { r.rank = i + 1; });
  return results;
}
function quote(id, category, status, results, extra) {
  rank(results);
  const best = results.find((r) => r.rank === 1);
  const selected = extra.selected_provider ?? null;
  const selectedResult = results.find((r) => r.provider === selected && r.total_estimate);
  const savings = best && selectedResult && selectedResult.provider !== best.provider
    ? { amount_minor: selectedResult.total_estimate.amount_minor - best.total_estimate.amount_minor, currency: 'INR' } : null;
  const checked = results.filter((r) => !['not_checked', 'failed'].includes(r.availability)).map((r) => r.provider);
  const failed = results.filter((r) => r.availability === 'failed').map((r) => r.provider);
  const missing = [...new Set(results.flatMap((r) => r.unavailable_fields))];
  const complete = failed.length === 0 && checked.length === results.length;
  let label = extra.label ?? (best ? (complete && missing.length === 0 ? 'cheapest' : 'lowest_observed_estimate') : 'none');
  if (label === 'cheapest' && !(complete && missing.length === 0)) throw new Error(`${id}: 'cheapest' not allowed without complete coverage`);
  return {
    quote_id: id, category, status, currency: 'INR',
    created_at: extra.created_at ?? NOW, expires_at: extra.expires_at ?? LATER,
    objective: extra.objective ?? 'lowest_total', selected_provider: selected,
    recommendation: {
      provider: label === 'none' ? null : best?.provider ?? null,
      label,
      reason: extra.reason,
      savings_vs_selected: label === 'none' ? null : savings,
    },
    coverage: { checked, failed, complete },
    provider_results: results,
    missing_fields: missing,
    assumptions: extra.assumptions ?? [],
    confidence: extra.confidence ?? 'medium',
    requires_user_confirmation: true,
    previous_total_estimate: extra.previous_total_estimate ?? null,
  };
}

const gBlinkit = (o = {}) => result('blinkit', GROCERY_LINES, { lines: [['l1', 28], ['l2', 45], ['l3', 84], ['l4', 42]], fees: { delivery_fee: 0, platform_fee: 2, small_cart_fee: 0, packaging_fee: 0, taxes: 0 }, eta: 11, ...o });
const gBigbasket = (o = {}) => result('bigbasket', GROCERY_LINES, { lines: [['l1', 27], ['l2', 50], ['l3', 90], ['l4', 40]], fees: { delivery_fee: 30, platform_fee: 0, small_cart_fee: 0, packaging_fee: 0, taxes: 0 }, eta: 45, ...o });
const gInstamart = (o = {}) => result('swiggy_instamart', GROCERY_LINES, { lines: [['l1', 29], ['l2', 44], ['l3', 86], ['l4', 45]], fees: { delivery_fee: 15, platform_fee: 5, small_cart_fee: 0, packaging_fee: 0, taxes: 0 }, eta: 14, discount: 10, ...o });
const assumptionsGrocery = ['Delivery to your saved Home label', 'No membership benefits applied', 'Coupons not applied unless shown'];

out('comparison/grocery.complete.json', 'ComparisonQuote', quote('qt_grocery_complete', 'grocery', 'complete', [gBlinkit(), gBigbasket(), gInstamart()],
  { selected_provider: 'bigbasket', reason: 'Lowest estimated complete-cart total across all 3 apps checked.', confidence: 'high', assumptions: assumptionsGrocery }), 201);

out('comparison/grocery.partial-coverage.json', 'ComparisonQuote', quote('qt_grocery_partial', 'grocery', 'partial_coverage',
  [gBlinkit(), notChecked('bigbasket', 'failed', 'BigBasket could not be checked: the app did not respond.'), gInstamart({ fees: { delivery_fee: 15, platform_fee: null, small_cart_fee: 0, packaging_fee: 0, taxes: 0 } })],
  { selected_provider: null, reason: 'Lowest observed estimate. BigBasket could not be checked and one fee was not visible.', confidence: 'medium', assumptions: assumptionsGrocery }), 201);

out('comparison/grocery.stale.json', 'ComparisonQuote', quote('qt_grocery_stale', 'grocery', 'stale',
  [gBlinkit({ captured_at: '2026-11-22T02:40:00Z' }), gBigbasket({ captured_at: '2026-11-22T02:40:00Z' }), gInstamart({ captured_at: '2026-11-22T02:40:00Z' })],
  { created_at: '2026-11-22T02:40:00Z', expires_at: '2026-11-22T02:45:00Z', label: 'lowest_observed_estimate', reason: 'Prices were checked more than 2 hours ago and may have changed.', confidence: 'low', assumptions: assumptionsGrocery }), 200);

out('comparison/grocery.incomparable.json', 'ComparisonQuote', quote('qt_grocery_incomparable', 'grocery', 'incomparable',
  [gBlinkit(), result('swiggy_instamart', GROCERY_LINES, { lines: [['l1', 15, 'different_pack', 'Toned milk', '500 ml', 'Only 500 ml packs available'], ['l2', 44], ['l3', 86, 'different_pack', 'Eggs', '6 pack', 'Only 6-packs available'], ['l4', 45]], fees: { delivery_fee: 15, platform_fee: 5, small_cart_fee: 0, packaging_fee: 0, taxes: 0 }, eta: 14 })],
  { label: 'none', reason: 'Pack sizes differ between apps, so totals are not directly comparable. Choose which option you prefer.', confidence: 'low', assumptions: ['Different pack sizes are never substituted automatically'] }), 201);

out('comparison/grocery.unavailable.json', 'ComparisonQuote', quote('qt_grocery_unavailable', 'grocery', 'unavailable',
  [notChecked('blinkit', 'unavailable', 'Store closed at your location right now.'), notChecked('bigbasket', 'unavailable', 'No delivery slots today.'), notChecked('swiggy_instamart', 'unavailable', 'Not serviceable at your location.')],
  { label: 'none', reason: 'None of the grocery apps can deliver this cart right now.', confidence: 'high' }), 201);

const pcBlinkit = gBlinkit({ lines: [['l1', 28], ['l2', 45], ['l3', 92, 'exact', null, null, 'Price increased since last check'], ['l4', 42]] });
const pcPrev = gBlinkit().total_estimate;
out('comparison/grocery.price-changed.json', 'ComparisonQuote', quote('qt_grocery_price_changed', 'grocery', 'price_changed', [pcBlinkit, gBigbasket(), gInstamart()],
  { selected_provider: 'blinkit', reason: 'Blinkit is still the lowest estimate, but its total went up since you last looked.', confidence: 'high', previous_total_estimate: pcPrev, assumptions: assumptionsGrocery }), 200);

out('comparison/grocery.in-progress.json', 'ComparisonQuote', quote('qt_grocery_in_progress', 'grocery', 'in_progress',
  [notChecked('blinkit', 'not_checked', 'Checking…'), notChecked('bigbasket', 'not_checked', 'Checking…'), notChecked('swiggy_instamart', 'not_checked', 'Checking…')],
  { label: 'none', reason: 'Checking prices in your apps.', confidence: 'low' }), 201);

const FOOD_LINES = [
  { line_id: 'f1', name: 'Masala dosa', quantity: 2, size: null, brand: null, modifiers: [] },
  { line_id: 'f2', name: 'Filter coffee', quantity: 2, size: null, brand: null, modifiers: [] },
];
const foodAssume = ['Same restaurant on both apps: "Udupi Corner, Indiranagar" (mock)', 'Delivery to your saved Home label', 'No membership benefits applied'];
out('comparison/food.complete.json', 'ComparisonQuote', quote('qt_food_complete', 'food', 'complete', [
  result('zomato', FOOD_LINES, { lines: [['f1', 110], ['f2', 45]], fees: { delivery_fee: 25, packaging_fee: 20, platform_fee: 6, small_cart_fee: 0, surge_or_booking_fee: 0, taxes: 16 }, discount: 40, eta: 32 }),
  result('swiggy', FOOD_LINES, { lines: [['f1', 115], ['f2', 45]], fees: { delivery_fee: 30, packaging_fee: 15, platform_fee: 6, small_cart_fee: 0, surge_or_booking_fee: 0, taxes: 17 }, eta: 28 }),
], { selected_provider: 'swiggy', reason: 'Lowest estimated total including fees and a visible ₹40 discount.', confidence: 'high', assumptions: foodAssume }), 201);

out('comparison/food.manual-only.json', 'ComparisonQuote', quote('qt_food_manual', 'food', 'manual_only', [
  notChecked('zomato', 'not_checked', 'Automatic price checks are not available for this app yet.'),
  notChecked('swiggy', 'not_checked', 'Automatic price checks are not available for this app yet.'),
], { label: 'none', reason: 'Prices cannot be checked automatically. You can open an app and continue manually.', confidence: 'low' }), 201);

const RIDE = { pickup_label: 'Home', drop_label: 'Airport Terminal 1', ride_class: 'auto', when: null };
const rideLine = [{ line_id: 'r1', name: 'Auto ride', quantity: 1, size: null, brand: null, modifiers: [] }];
out('comparison/travel.complete.json', 'ComparisonQuote', quote('qt_travel_complete', 'travel', 'complete', [
  result('uber', rideLine, { lines: [['r1', 612]], fees: { surge_or_booking_fee: 40, taxes: 0 }, eta: 4 }),
  result('ola', rideLine, { lines: [['r1', 598]], fees: { surge_or_booking_fee: 65, taxes: 0 }, eta: 7 }),
  result('rapido', rideLine, { lines: [['r1', 575]], fees: { surge_or_booking_fee: 30, taxes: 0 }, eta: 6 }),
], { selected_provider: 'uber', reason: 'Lowest estimated fare for an auto, including booking and peak charges.', confidence: 'medium', assumptions: ['Auto ride class on all apps', 'Tolls not included', 'Fares are estimates; final fare is set by the app'] }), 201);

// ---------------------------------------------------------------- chat
out('chat/session.created.json', 'ChatSession', { session_id: 'chs_01', category: 'grocery', title: 'Weekly groceries', created_at: NOW, updated_at: null }, 201);
out('chat/sessions.json', 'ChatSessionPage', { sessions: [
  { session_id: 'chs_01', category: 'grocery', title: 'Weekly groceries', created_at: NOW, updated_at: at(3) },
  { session_id: 'chs_00', category: 'food', title: 'Dosa for two', created_at: '2026-11-20T13:10:00Z', updated_at: '2026-11-20T13:14:00Z' },
  { session_id: 'chs_99', category: 'travel', title: 'Airport ride', created_at: '2026-11-17T03:00:00Z', updated_at: '2026-11-17T03:02:00Z' },
], next_cursor: null });
out('chat/sessions.empty.json', 'ChatSessionPage', { sessions: [], next_cursor: null });

const turnBase = (id, o) => ({ message_id: id, degraded: false, clarification: null, cart_draft: null, ...o });
const clar = { clarification_id: 'clr_01', question: 'Which milk should I add?', options: [{ option_id: 'o1', label: 'Toned milk 1 L' }, { option_id: 'o2', label: 'Full cream milk 1 L' }, { option_id: 'o3', label: 'Toned milk 500 ml' }] };
out('chat/turn.grocery-clarification.json', 'AssistantTurn', turnBase('msg_a1', { reply_text: 'Sure. Quick check before I compare prices:', intent: { type: 'order', category: 'grocery', confidence: 0.62 }, next_step: 'clarify', clarification: clar }));
out('chat/turn.grocery-cart.json', 'AssistantTurn', turnBase('msg_a2', { reply_text: 'Here is your cart. I will compare the full cart across Blinkit, BigBasket and Swiggy Instamart.', intent: { type: 'order', category: 'grocery', confidence: 0.91 }, next_step: 'compare', cart_draft: { category: 'grocery', selected_provider: 'bigbasket', lines: GROCERY_LINES, ride: null } }));
out('chat/turn.food-cart.json', 'AssistantTurn', turnBase('msg_a3', { reply_text: 'Two masala dosas and two filter coffees from your usual place. I will compare Zomato and Swiggy.', intent: { type: 'order', category: 'food', confidence: 0.88 }, next_step: 'compare', cart_draft: { category: 'food', selected_provider: 'swiggy', lines: FOOD_LINES, ride: null } }));
out('chat/turn.travel-ride.json', 'AssistantTurn', turnBase('msg_a4', { reply_text: 'An auto from Home to Airport Terminal 1, now. I will compare Uber, Ola and Rapido.', intent: { type: 'book_ride', category: 'travel', confidence: 0.9 }, next_step: 'compare', cart_draft: { category: 'travel', selected_provider: 'uber', lines: [], ride: RIDE } }));
out('chat/turn.degraded.json', 'AssistantTurn', turnBase('msg_a5', { reply_text: 'The assistant is temporarily limited. Tell me the items and quantities one per line, for example "2 x toned milk 1 L".', intent: { type: 'unknown', category: null, confidence: 0 }, next_step: 'none', degraded: true }));
out('chat/turn.general.json', 'AssistantTurn', turnBase('msg_a6', { reply_text: 'I can help you build a cart, compare prices across your apps, or show your monthly spending.', intent: { type: 'general', category: null, confidence: 0.8 }, next_step: 'none' }));
out('chat/session.detail.json', 'ChatSessionDetail', { session: { session_id: 'chs_01', category: 'grocery', title: 'Weekly groceries', created_at: NOW, updated_at: at(3) }, messages: [
  { message_id: 'msg_u1', role: 'user', text: 'Milk, brown bread, eggs and bananas', created_at: NOW, turn: null },
  { message_id: 'msg_a1', role: 'assistant', text: 'Sure. Quick check before I compare prices:', created_at: at(1), turn: null },
] });

// ---------------------------------------------------------------- cart tasks
const planned = [
  { action_id: 'a1', type: 'open_app', description: 'Open Blinkit', risk: 'low' },
  { action_id: 'a2', type: 'search', description: 'Search for each item', risk: 'low' },
  { action_id: 'a3', type: 'select_item', description: 'Add Toned milk 1 L × 2', risk: 'medium' },
  { action_id: 'a4', type: 'select_item', description: 'Add Brown bread 400 g × 1', risk: 'medium' },
  { action_id: 'a5', type: 'select_item', description: 'Add Eggs 12 pack × 1', risk: 'medium' },
  { action_id: 'a6', type: 'select_item', description: 'Add Bananas 6 pcs × 1', risk: 'medium' },
  { action_id: 'a7', type: 'verify_cart', description: 'Check the cart matches what you approved', risk: 'low' },
];
const limits = { max_actions: 25, max_runtime_seconds: 180, inactivity_timeout_seconds: 30 };
const ev = (seq, type, description, action_id = null) => ({ seq, at: at(seq * 0.2), type, description, action_id });
const runEvents = [
  ev(1, 'state_changed', 'Approved by you'),
  ev(2, 'state_changed', 'Started'),
  ev(3, 'action_succeeded', 'Opened Blinkit', 'a1'),
  ev(4, 'action_succeeded', 'Added Toned milk 1 L × 2', 'a3'),
  ev(5, 'action_started', 'Adding Brown bread 400 g × 1', 'a4'),
];
// approval_nonce (CR-002 G): issued only while the task awaits approval; single use.
const task = (status, o = {}) => ({ task_id: 'tsk_grocery_01', provider: 'blinkit', quote_id: 'qt_grocery_complete', status, created_at: NOW, expires_at: '2026-11-22T05:30:00Z', limits, actions_used: 0, planned_actions: planned, events: [], checkpoint: null, needs_user_action: null, cart: null, failure: null, approval_nonce: status === 'AWAITING_APPROVAL' ? 'apn_mock_7Qm2xK' : null, ...o });
const cartLines = gBlinkit().lines;
out('cart-tasks/awaiting-approval.json', 'CartTask', task('AWAITING_APPROVAL'), 201);
out('cart-tasks/running.json', 'CartTask', task('RUNNING', { actions_used: 4, events: runEvents }));
out('cart-tasks/checkpoint.json', 'CartTask', task('CHECKPOINT', { actions_used: 5, events: [...runEvents, ev(6, 'checkpoint_raised', 'Brown bread 400 g is out of stock')], checkpoint: { checkpoint_id: 'cp_01', prompt: 'Brown bread 400 g is out of stock in Blinkit. Skip it and continue with the other items?', risk: 'high' } }));
out('cart-tasks/needs-user-action.json', 'CartTask', task('NEEDS_USER_ACTION', { actions_used: 1, events: [...runEvents.slice(0, 3), ev(4, 'halt', 'Blinkit is asking you to sign in')], needs_user_action: { reason: 'login_required', message: 'Blinkit needs you to sign in. Sign in yourself, then tap Resume. I never enter passwords or OTPs.' } }));
out('cart-tasks/cart-ready.json', 'CartTask', task('CART_READY', { actions_used: 9, events: [...runEvents, ev(6, 'action_succeeded', 'Added Brown bread 400 g × 1', 'a4'), ev(7, 'action_succeeded', 'Added Eggs 12 pack × 1', 'a5'), ev(8, 'action_succeeded', 'Added Bananas 6 pcs × 1', 'a6'), ev(9, 'cart_verified', 'Cart matches what you approved')], cart: { verified: true, verified_at: at(2), lines: cartLines, total_estimate: gBlinkit().total_estimate, differs_from_quote: false } }));
out('cart-tasks/handed-off.json', 'CartTask', task('HANDED_OFF', { actions_used: 9, events: [ev(9, 'cart_verified', 'Cart matches what you approved'), ev(10, 'handoff_created', 'Opened Blinkit cart')], cart: { verified: true, verified_at: at(2), lines: cartLines, total_estimate: gBlinkit().total_estimate, differs_from_quote: false } }));
out('cart-tasks/failed-safe.json', 'CartTask', task('FAILED_SAFE', { actions_used: 4, events: [...runEvents, ev(6, 'halt', 'Stopped: unfamiliar screen')], failure: { code: 'unknown_screen', message: 'Blinkit showed a screen I do not recognise, so I stopped without changing anything else. You can continue manually.' } }));
out('cart-tasks/blocked-policy.json', 'CartTask', task('BLOCKED_POLICY', { actions_used: 8, events: [...runEvents, ev(6, 'halt', 'Stopped: payment screen detected')], failure: { code: 'policy_blocked', message: 'A payment screen appeared. I never handle payments, so I stopped. Your cart is unchanged from the last verified step.' } }));
out('cart-tasks/cancelled.json', 'CartTask', task('CANCELLED', { actions_used: 4, events: [...runEvents, ev(6, 'halt', 'Stopped by you (emergency stop)')] }));
out('cart-tasks/expired.json', 'CartTask', task('EXPIRED', { events: [ev(1, 'state_changed', 'Approval window expired')] }));
out('cart-tasks/state-conflict.json', 'ErrorEnvelope', err('task_state_conflict', 'This task has already changed. Refreshing.'), 409);

// ---------------------------------------------------------------- deep links
out('deep-links/cart.json', 'Handoff', { handoff_url: 'smartcart://handoff/hX9k2LmQ7rT4vB8nW3pZ', strategy: 'cart_deep_link', target_package: null, expires_at: '2026-11-22T05:15:00Z', manual_steps: [] }, 201);
out('deep-links/app-launch.json', 'Handoff', { handoff_url: 'smartcart://handoff/k2Lm9QrT4vB8nW3pZhX7', strategy: 'app_launch', target_package: null, expires_at: '2026-11-22T05:15:00Z', manual_steps: ['Open the app', 'Tap the cart icon', 'Check items and pay in the app'] }, 201);
out('deep-links/manual.json', 'Handoff', { handoff_url: 'smartcart://handoff/Q7rT4vB8nW3pZhX9k2Lm', strategy: 'manual', target_package: null, expires_at: '2026-11-22T05:15:00Z', manual_steps: ['Open the app yourself', 'Add the items listed below', 'Pay in the app'] }, 201);

// ---------------------------------------------------------------- history
const orders = [];
const items = {
  blinkit: [['Toned milk 1 L', 2, 56], ['Eggs 12 pack', 1, 84], ['Bananas 6 pcs', 1, 42]],
  zomato: [['Masala dosa', 2, 220], ['Filter coffee', 2, 90]],
  swiggy: [['Paneer biryani', 1, 289]],
  bigbasket: [['Atta 5 kg', 1, 265], ['Toor dal 1 kg', 1, 168]],
  uber: [['Auto ride', 1, 214]],
  rapido: [['Bike ride', 1, 96]],
};
const schedule = [
  ['blinkit', '2026-11-20T03:10:00Z'], ['zomato', '2026-11-19T14:00:00Z'], ['uber', '2026-11-18T04:30:00Z'], ['blinkit', '2026-11-16T03:05:00Z'],
  ['swiggy', '2026-11-14T15:20:00Z'], ['bigbasket', '2026-11-12T06:00:00Z'], ['blinkit', '2026-11-09T03:00:00Z'], ['rapido', '2026-11-07T12:10:00Z'],
  ['zomato', '2026-11-05T14:30:00Z'], ['blinkit', '2026-11-02T02:55:00Z'], ['zomato', '2026-10-29T14:10:00Z'], ['bigbasket', '2026-10-24T06:20:00Z'],
];
schedule.forEach(([p, when], i) => {
  const its = items[p];
  orders.push({
    order_id: `ord_${String(i + 1).padStart(3, '0')}`, provider: p, category: P[p][1], ordered_at: when,
    status: i === 4 ? 'REFUNDED' : (i === 7 ? 'CANCELLED' : 'DELIVERED'),
    total: INR(sum(its.map((x) => x[2]))),
    items: its.map(([name, quantity, amt]) => ({ name, quantity, amount: INR(amt) })),
    location_label: P[p][1] === 'travel' ? null : 'Home',
    extraction_confidence: i === 11 ? 'medium' : 'high',
  });
});
out('history/orders.json', 'OrderPage', { orders, next_cursor: null });
out('history/orders.empty.json', 'OrderPage', { orders: [], next_cursor: null });
out('history/import.running.json', 'ImportSession', { session_id: 'imp_01', status: 'running', upload_mode: 'normalized_upload', providers: [
  { provider: 'blinkit', status: 'completed', orders_found: 9, confidence: 'high', message: null },
  { provider: 'zomato', status: 'running', orders_found: 4, confidence: null, message: null },
  { provider: 'swiggy', status: 'pending', orders_found: 0, confidence: null, message: null },
], review_sample: [] }, 201);
out('history/import.review.json', 'ImportSession', { session_id: 'imp_01', status: 'awaiting_review', upload_mode: 'normalized_upload', providers: [
  { provider: 'blinkit', status: 'completed', orders_found: 9, confidence: 'high', message: null },
  { provider: 'zomato', status: 'completed', orders_found: 6, confidence: 'high', message: null },
  { provider: 'swiggy', status: 'failed', orders_found: 0, confidence: null, message: 'Swiggy order history screen changed. Skipped safely.' },
], review_sample: orders.slice(0, 4) });

// ---------------------------------------------------------------- recommendations
const recs = [
  { recommendation_id: 'rec_1', label: 'Your usual weekly groceries', category: 'grocery', confidence: 'high', reason_codes: ['frequent', 'regular_interval', 'due_soon'], explanation: 'You ordered milk, eggs and bananas 4 times in the last 4 weeks, usually every 7 days. Your last order was 2 days ago.', usual_provider: 'blinkit', prefill_lines: GROCERY_LINES.filter((l) => l.line_id !== 'l2') },
  { recommendation_id: 'rec_2', label: 'Atta and dal restock', category: 'grocery', confidence: 'medium', reason_codes: ['regular_interval'], explanation: 'You bought atta and toor dal about every 3 weeks. It has been 10 days.', usual_provider: 'bigbasket', prefill_lines: [{ line_id: 'l9', name: 'Atta', quantity: 1, size: '5 kg', brand: null, modifiers: [] }, { line_id: 'l10', name: 'Toor dal', quantity: 1, size: '1 kg', brand: null, modifiers: [] }] },
  { recommendation_id: 'rec_3', label: 'Thursday dinner order', category: 'food', confidence: 'low', reason_codes: ['weekday_pattern'], explanation: 'You ordered dinner on 2 of the last 3 Thursdays.', usual_provider: 'zomato', prefill_lines: FOOD_LINES },
];
const disclaimer = 'Suggestions are based on your past orders and may not match what you need. Nothing is ordered without your approval.';
out('recommendations/list.json', 'RecommendationList', { recommendations: recs, disclaimer });
out('recommendations/empty.json', 'RecommendationList', { recommendations: [], disclaimer });

// ---------------------------------------------------------------- dashboard
const months = ['2026-06', '2026-07', '2026-08', '2026-09', '2026-10', '2026-11'];
const monthData = {
  '2026-06': [9, 7420], '2026-07': [11, 9105], '2026-08': [10, 8650], '2026-09': [13, 11240], '2026-10': [12, 10380], '2026-11': [14, 12470],
};
function kpis([orders, amount]) {
  const weeks = 30 / 7;
  return {
    total_orders: orders, total_amount: INR(amount),
    average_order_value: { amount_minor: Math.round((amount * 100) / orders), currency: 'INR' },
    orders_per_week: Math.round((orders / weeks) * 10) / 10,
    days_between_orders: Math.round((30 / orders) * 10) / 10,
  };
}
const pct = (a, b) => Math.round(((a - b) / b) * 1000) / 10;
const pSplit = [['blinkit', 'Blinkit', 6, 3920], ['zomato', 'Zomato', 3, 2310], ['swiggy', 'Swiggy', 1, 289], ['bigbasket', 'BigBasket', 2, 4180], ['uber', 'Uber', 1, 1480], ['rapido', 'Rapido', 1, 291]]
  .map(([key, label, o, a]) => ({ key, label, orders: o, amount: INR(a) }));
const cSplit = [['grocery', 'Grocery', 8, 8100], ['food', 'Food ordering', 4, 2599], ['travel', 'Travel', 2, 1771]].map(([key, label, o, a]) => ({ key, label, orders: o, amount: INR(a) }));
const cards = [
  { category: 'travel', title: 'Travel', providers: ['uber', 'rapido', 'ola'], status: 'ready' },
  { category: 'food', title: 'Food ordering', providers: ['zomato', 'swiggy'], status: 'ready' },
  { category: 'grocery', title: 'Grocery', providers: ['blinkit', 'bigbasket', 'swiggy_instamart'], status: 'ready' },
  { category: 'shopping', title: 'Shopping', providers: ['amazon', 'flipkart'], status: 'setup_required' },
];
const recent = [
  { quote_id: 'qt_grocery_complete', category: 'grocery', title: 'Weekly groceries · 4 items', recommended_provider: 'blinkit', total_estimate: gBlinkit().total_estimate, created_at: NOW, status: 'complete' },
  { quote_id: 'qt_travel_complete', category: 'travel', title: 'Auto to Airport T1', recommended_provider: 'rapido', total_estimate: INR(605), created_at: '2026-11-17T03:01:00Z', status: 'complete' },
];
const cur = monthData['2026-11'], prev = monthData['2026-10'];
const summary = {
  month: '2026-11', available_months: months, currency: 'INR',
  kpis: kpis(cur), previous_kpis: kpis(prev),
  delta_vs_previous: { total_orders_pct: pct(cur[0], prev[0]), total_amount_pct: pct(cur[1], prev[1]), orders_per_week_pct: pct(kpis(cur).orders_per_week, kpis(prev).orders_per_week) },
  provider_split: pSplit, category_split: cSplit,
  freshness: { status: 'fresh', last_imported_at: '2026-11-21T19:40:00Z' },
  categories: cards, suggestions_preview: recs.slice(0, 2), recent_comparisons: recent,
};
out('dashboard/summary.success.json', 'DashboardSummary', summary);
out('dashboard/summary.stale.json', 'DashboardSummary', { ...summary, freshness: { status: 'stale', last_imported_at: '2026-10-30T19:40:00Z' }, provider_split: pSplit.slice(0, 3) });
const zeroK = { total_orders: 0, total_amount: INR(0), average_order_value: INR(0), orders_per_week: 0, days_between_orders: null };
out('dashboard/summary.empty.json', 'DashboardSummary', {
  month: '2026-11', available_months: ['2026-11'], currency: 'INR', kpis: zeroK, previous_kpis: null,
  delta_vs_previous: { total_orders_pct: null, total_amount_pct: null, orders_per_week_pct: null },
  provider_split: [], category_split: [], freshness: { status: 'never_imported', last_imported_at: null },
  categories: cards.map((c) => ({ ...c, status: c.category === 'shopping' ? 'setup_required' : 'ready' })), suggestions_preview: [], recent_comparisons: [],
});
out('dashboard/monthly.json', 'MonthlyMetrics', { currency: 'INR', months: months.map((m) => ({ month: m, kpis: kpis(monthData[m]), provider_split: m === '2026-11' ? pSplit : pSplit.slice(0, 4), category_split: cSplit })) });

// ---------------------------------------------------------------- CR-005: what the phone reports
// Request bodies have no HTTP status; they are listed with status 0.
const adapterRef = { key: 'teststore', version: '1' };
out('comparison/grocery.device-pending.json', 'ComparisonQuote', quote('qt_grocery_device', 'grocery', 'in_progress',
  [{ ...notChecked('blinkit', 'not_checked', 'Waiting for prices from your phone'), source: 'device' }, { ...gBigbasket(), source: 'server' }, { ...gInstamart(), source: 'server' }],
  { selected_provider: 'bigbasket', label: 'none', reason: 'Still checking prices on your phone.', confidence: 'low', assumptions: assumptionsGrocery }), 201);
const observed = (line_id, name, rupees, in_stock = true) => ({ line_id, found: true, name, unit_price: INR(rupees), in_stock });
out('device/observation.read.json', 'PriceObservation', {
  provider: 'blinkit', captured_at: at(1), outcome: 'read', not_read_reason: null, adapter: adapterRef,
  offers: [observed('l1', 'Toned milk 1 L', 28), observed('l2', 'Brown bread 400 g', 45, false), observed('l3', 'Eggs 12 pack', 84), { line_id: 'l4', found: false, name: null, unit_price: null, in_stock: false }],
  fees: { delivery_fee: INR(5), packaging_fee: INR(0), platform_fee: INR(0), small_cart_fee: INR(0), surge_or_booking_fee: null, taxes: INR(0), discounts: null },
  eta_minutes: null,
}, 0);
out('device/observation.not-read.json', 'PriceObservation', {
  provider: 'blinkit', captured_at: at(1), outcome: 'not_read', not_read_reason: 'login_required', adapter: adapterRef, offers: [], fees: null, eta_minutes: null,
}, 0);
const signIn = 'Test Store wants you to sign in. Sign in yourself in Test Store, then come back and resume. I never type passwords or codes.';
out('device/progress.needs-user.json', 'TaskProgressReport', {
  report_seq: 2, status: 'NEEDS_USER_ACTION', actions_used: 1, events: [ev(3, 'halt', signIn)],
  checkpoint: null, needs_user_action: { reason: 'login_required', message: signIn }, failure: null, cart: null, adapter: adapterRef,
}, 0);
out('device/progress.cart-ready.json', 'TaskProgressReport', {
  report_seq: 5, status: 'CART_READY', actions_used: 12, events: [ev(9, 'cart_verified', 'Cart checked: 4 items match'), ev(10, 'state_changed', 'Your cart is ready in Test Store')],
  checkpoint: null, needs_user_action: null, failure: null,
  cart: { verified: true, verified_at: at(2), lines: cartLines, total_estimate: gBlinkit().total_estimate, differs_from_quote: false }, adapter: adapterRef,
}, 0);

// ---------------------------------------------------------------- CR-006: a redeemed handoff link
out('deep-links/redeemed.json', 'HandoffRedemption', {
  task_id: 'tsk_grocery_01', provider: 'blinkit', target_package: null, strategy: 'manual',
  manual_steps: ['Open Blinkit', 'Open the cart', 'Check the items and pay in Blinkit'],
});

// ---------------------------------------------------------------- generic errors
out('errors/service-unavailable.json', 'ErrorEnvelope', err('service_unavailable', 'Something went wrong on our side. Please try again.'), 503);
out('errors/ai-unavailable.json', 'ErrorEnvelope', err('ai_unavailable', 'The assistant is temporarily limited.'), 503);
out('errors/rate-limited.json', 'ErrorEnvelope', err('rate_limited', 'You are going a bit fast. Try again shortly.', { retry_after_seconds: 60 }), 429);
out('errors/provider-disabled.json', 'ErrorEnvelope', err('provider_disabled', 'This app is temporarily unavailable for automation.'), 403);
out('errors/consent-required.json', 'ErrorEnvelope', err('consent_required', 'Allow automation for this app first.'), 403);

// ---------------------------------------------------------------- manifest
manifest.sort((a, b) => a.file.localeCompare(b.file));
writeFileSync(join(ROOT, 'manifest.json'), JSON.stringify({ version: 1, fixtures: manifest }, null, 2) + '\n');
console.log(`Wrote ${manifest.length} fixtures to ${ROOT}`);
