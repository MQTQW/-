import { createHash } from 'node:crypto';

const CATEGORIES = new Set(['attraction', 'food', 'area', 'other']);
const CONTROL = /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/u;

export class ResearchDataError extends Error {
  constructor(code, message) { super(message); this.name = 'ResearchDataError'; this.code = code; }
}

function requireValue(condition, message) {
  if (!condition) throw new ResearchDataError('INVALID_EXTRACTION', message);
}

function text(value, label, maximum, { empty = false, multiline = false } = {}) {
  requireValue(typeof value === 'string', `${label} must be text`);
  const result = value.trim();
  requireValue((empty || result.length > 0) && result.length <= maximum && !CONTROL.test(result) &&
    (multiline || !/[\r\n\t]/u.test(result)), `${label} is invalid`);
  return result;
}

export function normalizePlaceName(value) {
  return String(value).normalize('NFKC').trim().replace(/\s+/gu, ' ').toLocaleLowerCase('en-US');
}

export function candidateIdFor(city, name) {
  return `C-${createHash('sha256').update(`${normalizePlaceName(city)}\0${normalizePlaceName(name)}`).digest('hex').slice(0, 16)}`;
}

export function sourceRefKey(ref) {
  return JSON.stringify([ref?.sourceId, ref?.evidenceType, ref?.imageIndex ?? null, ref?.evidenceText]);
}

export function uniqueSourceRefs(refs) {
  const unique = new Map();
  for (const ref of refs) unique.set(sourceRefKey(ref), { ...ref });
  return [...unique.values()];
}

function sourceMap(sources) {
  requireValue(Array.isArray(sources) && sources.length <= 100, 'sources must be an array of at most 100 entries');
  const result = new Map();
  for (const source of sources) {
    requireValue(source && typeof source === 'object' && !Array.isArray(source), 'source must be an object');
    const id = text(source.id ?? source.sourceId, 'source ID', 80);
    requireValue(!result.has(id), 'source IDs must be unique');
    result.set(id, source);
  }
  return result;
}

function validateRef(raw, sources) {
  requireValue(raw && typeof raw === 'object' && !Array.isArray(raw), 'source reference must be an object');
  const sourceId = text(raw.sourceId, 'source reference ID', 80);
  const source = sources.get(sourceId);
  requireValue(source, 'source reference is not in the supplied source list');
  const evidenceText = text(raw.evidenceText, 'evidence quote', 300, { multiline: true });
  requireValue(raw.evidenceType === 'body' || raw.evidenceType === 'image', 'evidence type must be body or image');
  if (raw.evidenceType === 'body') {
    const body = source.body ?? source.text ?? source.desc ?? '';
    requireValue(typeof body === 'string' && body.includes(evidenceText), 'body evidence must be a verbatim substring of its source');
    requireValue(raw.imageIndex === undefined || raw.imageIndex === null, 'body evidence must not have an image index');
    return { sourceId, evidenceType: 'body', evidenceText };
  }
  const imageIndex = raw.imageIndex;
  const images = source.images ?? source.imageList ?? [];
  requireValue(Number.isInteger(imageIndex) && imageIndex >= 1 && Array.isArray(images) && imageIndex <= images.length,
    'image evidence must reference a supplied 1-based image index');
  const extracted = Array.isArray(source.imageEvidence) ? source.imageEvidence : [];
  const matches = extracted.filter((item) => item?.imageIndex === imageIndex && typeof item.text === 'string');
  requireValue(matches.some((item) => item.text.includes(evidenceText)), 'an image URL alone is not extracted image evidence');
  return { sourceId, evidenceType: 'image', imageIndex, evidenceText };
}

/** Treat model output as untrusted: IDs, coordinates, verification and URLs are never accepted from it. */
export function validateExtraction(raw, { sources } = {}) {
  requireValue(raw && typeof raw === 'object' && !Array.isArray(raw), 'extraction must be an object');
  requireValue(Array.isArray(raw.candidates) && raw.candidates.length <= 80, 'candidates must be an array of at most 80 entries');
  const knownSources = sourceMap(sources);
  const candidates = raw.candidates.map((item) => {
    requireValue(item && typeof item === 'object' && !Array.isArray(item), 'candidate must be an object');
    const name = text(item.name, 'candidate name', 120);
    const city = text(item.city, 'candidate city', 80);
    requireValue(CATEGORIES.has(item.category), 'candidate category is invalid');
    requireValue(Number.isInteger(item.suggestedDurationMinutes) && item.suggestedDurationMinutes >= 15 &&
      item.suggestedDurationMinutes <= 720, 'suggested duration must be 15–720 whole minutes');
    requireValue(Array.isArray(item.sourceRefs) && item.sourceRefs.length >= 1 && item.sourceRefs.length <= 30,
      'each candidate needs 1–30 evidence references');
    const sourceRefs = uniqueSourceRefs(item.sourceRefs.map((ref) => validateRef(ref, knownSources)));
    requireValue(sourceRefs.every((ref) => normalizePlaceName(ref.evidenceText).includes(normalizePlaceName(name))),
      'each place evidence quote must identify the candidate by its source name');
    return {
      id: candidateIdFor(city, name), name, city, category: item.category,
      suggestedDurationMinutes: item.suggestedDurationMinutes,
      sourceRefs,
      notes: text(item.notes ?? '', 'candidate notes', 1000, { empty: true, multiline: true }),
    };
  });
  return { candidates };
}

/** Only exact normalized names in the same city are merged; no fuzzy alias or substring matching. */
export function mergeCandidates(candidates) {
  requireValue(Array.isArray(candidates) && candidates.length <= 200, 'candidate list is invalid');
  const mappedPlaces = new Map();
  for (const candidate of candidates) {
    requireValue(candidate && typeof candidate === 'object' && !Array.isArray(candidate), 'candidate must be an object');
    const key = `${normalizePlaceName(candidate.city)}\0${normalizePlaceName(candidate.name)}`;
    const poiId = candidate.verification?.poiId ?? candidate.poiId;
    if ((candidate.verification?.status ?? candidate.verificationStatus) === 'verified' && typeof poiId === 'string' && poiId) {
      const ids = mappedPlaces.get(key) ?? new Set(); ids.add(poiId); mappedPlaces.set(key, ids);
    }
  }
  const groups = new Map();
  for (const candidate of candidates) {
    const city = text(candidate.city, 'candidate city', 80);
    const name = text(candidate.name, 'candidate name', 120);
    requireValue(Array.isArray(candidate.sourceRefs) && candidate.sourceRefs.length > 0, 'candidate source references are missing');
    requireValue(Number.isInteger(candidate.suggestedDurationMinutes) && candidate.suggestedDurationMinutes >= 15 &&
      candidate.suggestedDurationMinutes <= 720, 'candidate duration is invalid');
    const baseKey = `${normalizePlaceName(city)}\0${normalizePlaceName(name)}`;
    const conflictingPlaces = (mappedPlaces.get(baseKey)?.size ?? 0) > 1;
    const poiId = candidate.verification?.poiId ?? candidate.poiId;
    const branch = conflictingPlaces ? poiId ?? 'unmatched' : '';
    const key = `${baseKey}\0${branch}`;
    const group = groups.get(key) ?? { first: candidate, name, city, branch, refs: [], durations: [], notes: [] };
    group.refs.push(...candidate.sourceRefs);
    group.durations.push(candidate.suggestedDurationMinutes);
    if (candidate.notes?.trim() && !group.notes.includes(candidate.notes.trim())) group.notes.push(candidate.notes.trim());
    groups.set(key, group);
  }
  return [...groups.values()].map((group) => {
    group.durations.sort((a, b) => a - b);
    return {
      ...group.first,
      id: candidateIdFor(group.city, group.branch ? `${group.name}\0${group.branch}` : group.name), name: group.name, city: group.city,
      sourceRefs: uniqueSourceRefs(group.refs),
      suggestedDurationMinutes: group.durations[Math.floor((group.durations.length - 1) / 2)],
      notes: group.notes.join('\n').slice(0, 1000),
    };
  });
}
