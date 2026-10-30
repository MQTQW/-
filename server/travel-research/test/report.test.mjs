import test from 'node:test';
import assert from 'node:assert/strict';
import { Script } from 'node:vm';
import { createHash } from 'node:crypto';
import { publicReport, renderReport, validateEditorDraft } from '../report.mjs';

const source = { id: 'S1', postId: 'public-post', title: '杭州攻略', url: 'https://www.xiaohongshu.com/explore/public-post', body: '断桥旁散步，经过白堤。', images: [{ url: 'https://image.example/private-signed-query?token=not-for-artifact' }] };
const candidate = { id: 'C1', name: '断桥', city: '杭州', category: 'attraction', suggestedDurationMinutes: 90, sourceRefs: [{ sourceId: 'S1', evidenceType: 'body', evidenceText: '断桥旁散步' }], verification: { status: 'unverified' } };
const stop = (changes = {}) => ({ candidateId: 'C1', title: '断桥', startTime: '09:00', durationMinutes: 90, sourceRefs: candidate.sourceRefs, ...changes });
const base = () => ({ destination: '杭州', dayCount: 3, generatedAt: '2026-10-28T14:00:00+08:00', statuses: { retrieval: 'cached', ai: 'rules', map: 'not_configured', draft: 'blocked' }, sources: [source], candidates: [candidate], draft: { days: [{ day: 1, stops: [] }], warnings: [] } });

test('reused real sources and extraction are visibly marked as reused rather than freshly fetched',()=>{
  const input=base();input.statuses={retrieval:'success',ai:'success',map:'partial',draft:'partial'};
  input.provenance={sources:'saved_mcp_capture',extraction:'saved_validated_extraction'};
  const result=publicReport(input);assert.equal(result.statuses.retrieval,'cached');assert.equal(result.statuses.ai,'cached');
});

test('report exposes only public fields and bounded evidence, without bodies or credentials', () => {
  const input = base(); input.configuration = { apiKey: 'RESEARCH_SECRET_SHOULD_NOT_SHIP' }; input.sources[0] = { ...source, privateCookie: 'RESEARCH_COOKIE_SHOULD_NOT_SHIP' };
  input.candidates[0] = { ...candidate, sourceRefs: [{ ...candidate.sourceRefs[0], evidenceText: '证'.repeat(250) }] };
  const result = publicReport(input); const encoded = JSON.stringify(result);
  assert.equal(result.sources[0].bodyCharacters, source.body.length);
  assert.equal(result.sources[0].body, undefined);
  assert.equal(result.sources[0].images, undefined);
  assert.equal(result.candidates[0].sourceRefs[0].evidenceText.length, 100);
  assert.ok(!encoded.includes('RESEARCH_SECRET_SHOULD_NOT_SHIP'));
  assert.ok(!encoded.includes('RESEARCH_COOKIE_SHOULD_NOT_SHIP'));
  assert.ok(!encoded.includes('private-signed-query'));
  assert.equal(result.draft.days.length, 3);
});

test('untrusted report strings cannot close the data script or create HTML', () => {
  const input = base(); input.destination = '</script><img src=x onerror=alert(1)>'; input.sources[0] = { ...source, title: '</script><script>alert(1)</script>', url: 'javascript:alert(1)' };
  const html = renderReport(input);
  assert.ok(!html.includes('<img src=x onerror=alert(1)>'));
  assert.ok(!html.includes('javascript:alert(1)'));
  assert.equal((html.match(/<script(?:\s|>)/gu) ?? []).length, 2);
  const encoded = html.match(/<script id="report-data" type="application\/json">([\s\S]*?)<\/script>/u)[1];
  assert.equal(JSON.parse(encoded).destination, input.destination);
  assert.equal(JSON.parse(encoded).sources[0].url, '');
  const runtime = html.match(/<script>([\s\S]*?)<\/script><\/body>/u)[1];
  assert.doesNotThrow(() => new Script(runtime));
  // Browsers normalize CR/CRLF in inline scripts before checking their CSP hash.
  const digest = createHash('sha256').update(runtime.replace(/\r\n?/gu, '\n')).digest('base64');
  assert.ok(html.includes("script-src 'sha256-" + digest + "'"));
});

test('credential-bearing external URLs are omitted', () => {
  const input = base(); input.sources[0] = { ...source, url: 'https://user:password@example.com/post' };
  assert.equal(publicReport(input).sources[0].url, '');
});

test('edited draft rejects overlap, including a long containing interval', () => {
  const second = { ...candidate, id: 'C2', name: '白堤' }; const third = { ...candidate, id: 'C3', name: '孤山' };
  const draft = { days: [{ day: 1, stops: [stop({ durationMinutes: 240 }), stop({ candidateId: 'C2', title: '白堤', startTime: '10:00', durationMinutes: 30 }), stop({ candidateId: 'C3', title: '孤山', startTime: '12:00', durationMinutes: 30 })] }] };
  const issues = validateEditorDraft(draft, { candidates: [candidate, second, third], sources: [source] });
  assert.equal(issues.filter((issue) => issue.includes('时间重叠')).length, 2);
});

test('edited draft rejects invalid times, duration, cross-midnight and broken provenance', () => {
  for (const changes of [{ startTime: '24:00' }, { durationMinutes: 1.5 }, { startTime: '23:30', durationMinutes: 90 }, { sourceRefs: [{ sourceId: 'unknown' }] }, { candidateId: 'unknown' }]) {
    assert.ok(validateEditorDraft({ days: [{ day: 1, stops: [stop(changes)] }] }, { candidates: [candidate], sources: [source] }).length > 0);
  }
});

test('unverified manual draft is allowed without claiming map verification', () => {
  const draft = { days: [{ day: 1, stops: [stop({ verificationStatus: 'unverified', travelFromPreviousMinutes: null })] }] };
  assert.deepEqual(validateEditorDraft(draft, { candidates: [candidate], sources: [source] }), []);
  const data = publicReport({ ...base(), draft });
  assert.equal(data.draft.days[0].stops[0].verificationStatus, 'unverified');
  assert.equal(data.draft.days[0].stops[0].travelFromPreviousMinutes, null);
});

test('edited draft preserves and enforces map travel estimates and pending buffers', () => {
  const second = { ...candidate, id: 'C2', name: '白堤' };
  const draft = { days: [{ day: 1, stops: [stop(), stop({ candidateId: 'C2', title: '白堤', startTime: '11:00', planningBufferMinutes: 30 })] }] };
  const context = { candidates: [candidate, second], sources: [source], legs: [{ fromCandidateId: 'C1', toCandidateId: 'C2', status: 'verified', durationMinutes: 45 }] };
  assert.ok(validateEditorDraft(draft, context).some((issue) => issue.includes('地图估算的 45 分钟')));
  context.legs = [];
  assert.deepEqual(validateEditorDraft(draft, context), []);
  draft.days[0].stops[1].startTime = '10:45';
  assert.ok(validateEditorDraft(draft, context).some((issue) => issue.includes('建议缓冲的 30 分钟')));
});

test('report export state keeps planning buffer and leg provenance without private extra fields', () => {
  const input = base(); input.draft.days[0].stops = [stop({ planningBufferMinutes: 45 })];
  input.legs = [{ fromCandidateId: 'C1', toCandidateId: 'C2', status: 'verified', durationMinutes: 42, mode: 'walking', checkedAt: '2026-10-28T14:00:00+08:00', requestKey: 'DO_NOT_EXPORT' }];
  const result = publicReport(input);
  assert.equal(result.draft.days[0].stops[0].planningBufferMinutes, 45);
  assert.equal(result.legs[0].durationMinutes, 42);
  assert.equal(result.legs[0].checkedAt, '2026-10-28T14:00:00+08:00');
  assert.equal(result.legs[0].requestKey, undefined);
});
