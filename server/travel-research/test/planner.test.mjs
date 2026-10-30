import test from 'node:test';
import assert from 'node:assert/strict';
import { candidateIdFor, mergeCandidates, validateExtraction } from '../contract.mjs';
import { buildDraft, validateDraft } from '../planner.mjs';

const sources = [{ id: 'S1', body: '杭州西湖与灵隐寺值得慢慢游览。', images: [{ url: 'https://example.invalid/a.png' }] },
  { id: 'S2', body: '杭州西湖游览建议。', images: [{ url: 'https://example.invalid/b.png' }],
    imageEvidence: [{ imageIndex: 1, text: 'Day 1 杭州西湖，随后参观灵隐寺。' }] }];
function raw(name = '西湖', sourceId = 'S1') {
  return { name, city: '杭州', category: 'attraction', suggestedDurationMinutes: 60,
    sourceRefs: [{ sourceId, evidenceType: 'body', evidenceText: name }], notes: '' };
}
const extract = (items) => validateExtraction({ candidates: items }, { sources }).candidates;
function verified(name, index = 0, duration = 60) {
  const candidate = extract([raw(name)])[0];
  return { ...candidate, suggestedDurationMinutes: duration, verification: { status: 'verified', poiId: `POI-${index}`,
    location: { longitude: 120.1 + index / 100, latitude: 30.2 + index / 100 } } };
}

test('model cannot invent sources, body quotes or image evidence', () => {
  assert.throws(() => extract([raw('西湖', 'S999')]), /source reference is not/u);
  assert.throws(() => extract([{ ...raw(), sourceRefs: [{ sourceId: 'S1', evidenceType: 'body', evidenceText: '门票免费' }] }]), /verbatim/u);
  assert.throws(() => extract([{ ...raw(), sourceRefs: [{ sourceId: 'S1', evidenceType: 'image', imageIndex: 1, evidenceText: '西湖' }] }]), /URL alone/u);
  assert.throws(() => extract([{ ...raw(), sourceRefs: [{ sourceId: 'S2', evidenceType: 'image', imageIndex: 2, evidenceText: '西湖' }] }]), /image index/u);
  assert.throws(() => extract([{ ...raw(), sourceRefs: [{ sourceId: 'S2', evidenceType: 'image', imageIndex: 1, evidenceText: '灵隐寺' }] }]), /identify the candidate/u);
  const candidate = extract([{ ...raw('灵隐寺'), sourceRefs: [{ sourceId: 'S2', evidenceType: 'image', imageIndex: 1, evidenceText: '灵隐寺' }] }])[0];
  assert.equal(candidate.sourceRefs[0].imageIndex, 1);
});

test('candidate IDs are program generated and model verification and URLs are discarded', () => {
  const candidate = extract([{ ...raw(), id: 'invented', verificationStatus: 'verified', latitude: 1, url: 'https://untrusted.invalid' }])[0];
  assert.equal(candidate.id, candidateIdFor('杭州', '西湖'));
  assert.equal(candidate.verificationStatus, undefined);
  assert.equal(candidate.latitude, undefined);
  assert.equal(candidate.url, undefined);
});

test('merging preserves distinct places and cities while combining exact names and sources', () => {
  const first = extract([raw(), raw('灵隐寺'), raw('西湖', 'S2')]);
  const otherCity = { ...first[0], city: '福州', id: 'temporary' };
  const merged = mergeCandidates([...first, otherCity]);
  assert.equal(merged.length, 3);
  const westLake = merged.find((item) => item.city === '杭州' && item.name === '西湖');
  assert.deepEqual(westLake.sourceRefs.map((ref) => ref.sourceId), ['S1', 'S2']);
  assert.equal(first[0].sourceRefs.length, 1, 'input must not be mutated');
  const mappedBranches = mergeCandidates([verified('西湖', 1), verified('西湖', 2)]);
  assert.equal(mappedBranches.length, 2, 'different mapped POI IDs must not be merged only because their labels match');
  assert.notEqual(mappedBranches[0].id, mappedBranches[1].id);
});

test('without map matching, days remain empty and all candidates stay in alternatives', () => {
  const candidates = extract([raw(), raw('灵隐寺')]);
  candidates[0].verification = { status: 'verified', poiId: 'id-without-coordinates' };
  const draft = buildDraft({ destination: '杭州', dayCount: 3, candidates });
  assert.equal(draft.days.length, 3);
  assert.ok(draft.days.every((day) => day.stops.length === 0));
  assert.equal(draft.alternatives.length, 2);
  assert.equal(draft.validation.valid, true);
});

test('unknown travel stays null and suggested buffer is clearly distinguished', () => {
  const candidates = [verified('西湖', 0), verified('灵隐寺', 1)];
  const draft = buildDraft({ destination: '杭州', dayCount: 1, candidates });
  const second = draft.days[0].stops[1];
  assert.equal(second.travelFromPreviousMinutes, null);
  assert.equal(second.planningBufferMinutes, 30);
  assert.match(second.notes, /不是地图交通耗时/u);
  assert.ok(draft.validation.issues.some((item) => item.code === 'UNVERIFIED_TRAVEL' && item.severity === 'warning'));
  assert.equal(draft.validation.valid, true);
});

test('known direction-specific route cost reserves sufficient time; zero and unverified costs do not become facts', () => {
  const candidates = [verified('西湖', 0), verified('灵隐寺', 1)];
  const leg = { fromCandidateId: candidates[0].id, toCandidateId: candidates[1].id, durationMinutes: 70, status: 'verified' };
  const draft = buildDraft({ destination: '杭州', dayCount: 1, candidates, legs: [leg] });
  assert.equal(draft.days[0].stops[1].travelFromPreviousMinutes, 70);
  assert.equal(draft.days[0].stops[1].startTime, '11:10');
  for (const bad of [{ ...leg, durationMinutes: 0 }, { ...leg, status: 'unverified' }, { ...leg, fromCandidateId: leg.toCandidateId, toCandidateId: leg.fromCandidateId }]) {
    const result = buildDraft({ destination: '杭州', dayCount: 1, candidates, legs: [bad] });
    assert.equal(result.days[0].stops[1].travelFromPreviousMinutes, null);
  }
});

test('oversized visits stay alternatives instead of crossing midnight or having durations shortened', () => {
  const candidates = [verified('西湖', 0, 720), verified('灵隐寺', 1)];
  const draft = buildDraft({ destination: '杭州', dayCount: 1, candidates, startDate: '2026-10-31' });
  assert.equal(draft.days[0].date, '2026-10-31');
  assert.deepEqual(draft.days[0].stops.map((stop) => stop.title), ['灵隐寺']);
  assert.equal(draft.alternatives[0].candidateId, candidates[0].id);
  assert.equal(candidates[0].suggestedDurationMinutes, 720);
  assert.throws(() => buildDraft({ destination: '杭州', dayCount: 1, candidates, startDate: '2026-02-30' }), /real/u);
  assert.throws(() => buildDraft({ destination: '杭州', dayCount: 2, candidates, startDate: '9999-12-31' }), /0001–9999/u);
});

test('verified visits are spread across requested dates and daily pace limit is respected', () => {
  const localSources = [{ id: 'S', body: '东门、西门、南门、北门、中山公园、文化街。', images: [] }];
  const names = ['东门', '西门', '南门', '北门', '中山公园', '文化街'];
  const candidates = validateExtraction({ candidates: names.map((name) => ({ ...raw(name), sourceRefs: [
    { sourceId: 'S', evidenceType: 'body', evidenceText: name },
  ] })) }, { sources: localSources }).candidates.map((candidate, index) => ({
    ...candidate, verification: { status: 'verified', poiId: `P${index}`, location: [120 + index / 100, 30] },
  }));
  const draft = buildDraft({ destination: '杭州', dayCount: 3, startDate: '2026-10-31', candidates, pace: 'relaxed' });
  assert.deepEqual(draft.days.map((day) => day.stops.length), [2, 2, 2]);
  assert.deepEqual(draft.days.map((day) => day.date), ['2026-10-31', '2026-11-01', '2026-11-02']);
  const crowded = buildDraft({ destination: '杭州', dayCount: 1, candidates, pace: 'relaxed' });
  assert.equal(crowded.days[0].stops.length, 3);
  assert.equal(crowded.alternatives.length, 3);
});

test('edited draft detects overlap, missing travel gaps, forged citations and midnight crossings', () => {
  const candidates = [verified('西湖', 0), verified('灵隐寺', 1)];
  const base = buildDraft({ destination: '杭州', dayCount: 1, candidates });
  const copy = () => structuredClone(base);
  const overlap = copy(); overlap.days[0].stops[1].startTime = '09:30';
  assert.ok(validateDraft(overlap, { candidates }).issues.some((issue) => issue.code === 'OVERLAP'));
  const gap = copy(); gap.days[0].stops[1].travelFromPreviousMinutes = 90;
  assert.ok(validateDraft(gap, { candidates }).issues.some((issue) => issue.code === 'TRAVEL_GAP'));
  const citation = copy(); citation.days[0].stops[0].sourceRefs[0].evidenceText = '伪造来源';
  assert.equal(validateDraft(citation, { candidates }).valid, false);
  const brokenCitation = copy(); brokenCitation.days[0].stops[0].sourceRefs[0] = null;
  assert.equal(validateDraft(brokenCitation, { candidates }).valid, false);
  const fakeTravel = copy(); fakeTravel.days[0].stops[1].travelFromPreviousMinutes = 1;
  assert.ok(validateDraft(fakeTravel, { candidates, legs: [] }).issues.some((issue) => issue.code === 'UNSUPPORTED_TRAVEL_TIME'));
  const firstTravel = copy(); firstTravel.days[0].stops[0].travelFromPreviousMinutes = 20;
  assert.ok(validateDraft(firstTravel, { candidates }).issues.some((issue) => issue.code === 'FIRST_STOP_TRAVEL'));
  const midnight = copy(); midnight.days[0].stops[1].startTime = '23:30';
  assert.ok(validateDraft(midnight, { candidates }).issues.some((issue) => issue.code === 'CROSSES_MIDNIGHT'));
});
