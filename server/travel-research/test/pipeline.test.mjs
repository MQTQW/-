import test from 'node:test';
import assert from 'node:assert/strict';
import { researchTravel } from '../pipeline.mjs';

const names = ['断桥残雪', '白堤', '苏堤春晓', '花港观鱼'];
const sources = [{ id: 'S1', title: '离线规则样例', body: names.join('、'), images: [{ url: 'https://example.invalid/image.png' }] }];
function mapsFixture(duration = 10) {
  const calls = [];
  return { calls,
    verify: async (candidate) => ({ ...candidate, verification: { status: 'verified', poiId: candidate.id,
      location: { longitude: 120 + names.indexOf(candidate.name) / 100, latitude: 30 } } }),
    leg: async (from, to) => {
      calls.push([from.id, to.id]);
      return { fromCandidateId: from.id, toCandidateId: to.id,
        durationMinutes: typeof duration === 'function' ? duration(from, to) : duration,
        status: 'verified' };
    },
  };
}

test('invalid trip and source input is rejected before any AI or map request', async () => {
  let aiCalls = 0, mapCalls = 0;
  const options = { sources, config: { aiKey: 'offline-ai-fixture', amapKey: 'offline-map-fixture' },
    aiImpl: async () => { aiCalls++; }, mapsImpl: { verify: async () => { mapCalls++; } } };
  for (const invalid of [{ dayCount: NaN }, { dayCount: 0 }, { pace: 'fast' }, { transport: 'teleport' }, { maxCandidates: 100 }, { destination: '' }]) {
    await assert.rejects(researchTravel({ ...options, ...invalid }), { code: 'INVALID_RESEARCH_INPUT' });
  }
  await assert.rejects(researchTravel({ ...options, sources: [{ id: 'S1', body: 'text' }] }), { code: 'INVALID_RESEARCH_INPUT' });
  assert.equal(aiCalls, 0); assert.equal(mapCalls, 0);
});

test('known long route cannot be avoided by changing the final order to unknown shortcuts', async () => {
  const maps = mapsFixture((from, to) => from.name === names[0] && to.name === names[1] ? 500 : 10);
  const result = await researchTravel({ sources, destination: '杭州', dayCount: 1,
    config: { amapKey: 'offline-map-fixture' }, mapsImpl: maps });
  assert.equal(maps.calls.length, 3);
  assert.deepEqual(result.draft.days[0].stops.map((stop) => stop.title), [names[0]]);
  assert.equal(result.draft.reconciliation.overflowCount, 3);
  assert.deepEqual(result.draft.alternatives.map((item) => item.title), names.slice(1));
  assert.equal(result.statuses.routes, 'success');
  assert.equal(result.statuses.draft, 'partial');
  assert.equal(result.draft.validation.valid, true);
});

test('failed route costs stay unknown and have an independent partial route status', async () => {
  const maps = mapsFixture(null);
  const result = await researchTravel({ sources, dayCount: 1, config: { amapKey: 'offline-map-fixture' }, mapsImpl: maps });
  assert.equal(result.statuses.map, 'success');
  assert.equal(result.statuses.routes, 'partial');
  assert.equal(result.statuses.draft, 'partial');
  assert.equal(result.stats.queriedLegCount, 3);
  assert.equal(result.stats.availableLegCount, 0);
  assert.ok(result.draft.days[0].stops.slice(1).every((stop) => stop.travelFromPreviousMinutes === null));
  assert.equal(result.draft.validation.valid, true);
});

test('only a populated complete draft can be ready; empty requested days remain partial', async () => {
  const complete = await researchTravel({ sources, dayCount: 1, config: { amapKey: 'offline-map-fixture' }, mapsImpl: mapsFixture() });
  assert.equal(complete.statuses.draft, 'ready');
  const partial = await researchTravel({ sources: [{ ...sources[0], body: names[0] }], dayCount: 3,
    config: { amapKey: 'offline-map-fixture' }, mapsImpl: mapsFixture() });
  assert.equal(partial.statuses.draft, 'partial');
  assert.equal(partial.draft.days.filter((day) => !day.stops.length).length, 2);
  const missing = await researchTravel({ sources, dayCount: 3, config: {} });
  assert.equal(missing.statuses.routes, 'not_configured');
  assert.equal(missing.statuses.draft, 'blocked');
});

test('AI failure preserves safe usage statistics but never raw messages or credentials', async () => {
  const secret = 'fixture-sensitive-key-do-not-export';
  const error = Object.assign(new Error(`request URL/header contained ${secret}`), {
    stats: { aiRequests: 2, imagesAttempted: 1, unsafeSecret: secret,
      imageWarnings: [{ sourceId: 'S1', imageIndex: 1, code: 'AI_TIMEOUT', message: secret }] },
    usage: { total_tokens: 50, unknownPrivateValue: secret },
  });
  const result = await researchTravel({ sources, config: { aiKey: secret }, aiImpl: async () => { throw error; } });
  assert.equal(result.statuses.ai, 'failed');
  assert.equal(result.extractionMethod, 'body_rules');
  assert.equal(result.stats.ai.aiRequests, 2);
  assert.equal(result.stats.ai.usage.total_tokens, 50);
  assert.ok(result.warnings.every((item) => typeof item === 'string'));
  assert.equal(JSON.stringify(result).includes(secret), false);
});
