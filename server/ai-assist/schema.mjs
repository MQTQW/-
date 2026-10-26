const modes = new Set(['ONCE', 'EVERY_DAY', 'WEEKDAYS', 'CUSTOM']);
const durations = new Set([15, 25, 45]);
const categories = new Set(['日常', '工作', '学习', '健康', '紧急', '其他']);

export class InvalidData extends Error {}
const requireThat = (condition) => { if (!condition) throw new InvalidData(); };
const object = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);
const keys = (value, allowed, required = allowed) => {
  requireThat(object(value) && Object.keys(value).every((key) => allowed.includes(key)) &&
    required.every((key) => Object.hasOwn(value, key)));
};
const string = (value, maximum, empty = false) => {
  requireThat(typeof value === 'string' && value.length <= maximum &&
    (empty || value.trim().length > 0) && !/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/u.test(value));
  return value.trim();
};
export function validDate(value) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/u.test(value)) return false;
  const date = new Date(`${value}T00:00:00Z`);
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === value;
}
export function validateRequest(value) {
  keys(value, ['operation', 'text', 'now', 'timeZone', 'context'], ['operation', 'text', 'now', 'timeZone']);
  requireThat(value.operation === 'parse' || value.operation === 'split');
  const text = string(value.text, 1000);
  const now = string(value.now, 40);
  const timeZone = string(value.timeZone, 80);
  requireThat(/^\d{4}-\d{2}-\d{2}T(?:[01]\d|2[0-3]):[0-5]\d:[0-5]\d(?:\.\d{1,3})?(?:Z|[+-](?:0\d|1[0-4]):[0-5]\d)$/u.test(now));
  requireThat(validDate(now.slice(0, 10)) && Number.isFinite(Date.parse(now)));
  let parts;
  try {
    parts = new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' }).formatToParts(new Date(now));
  } catch { throw new InvalidData(); }
  const local = Object.fromEntries(parts.map(({ type, value: part }) => [type, part]));
  requireThat(`${local.year}-${local.month}-${local.day}T${local.hour}:${local.minute}:${local.second}` === now.slice(0, 19));
  let context;
  if (value.context !== undefined) {
    keys(value.context, ['title', 'description']);
    context = { title: string(value.context.title, 80), description: string(value.context.description, 1000, true) };
  }
  requireThat(value.operation !== 'split' || context !== undefined);
  return { operation: value.operation, text, now, timeZone, ...(context ? { context } : {}) };
}

export function validateResult(value, request) {
  if (request.operation === 'split') {
    keys(value, ['steps']);
    requireThat(Array.isArray(value.steps) && value.steps.length >= 1 && value.steps.length <= 8);
    const seen = new Set();
    return { steps: value.steps.map((step) => {
      keys(step, ['title', 'focusMinutes']);
      const title = string(step.title, 80);
      requireThat(durations.has(step.focusMinutes) && !seen.has(title));
      seen.add(title);
      return { title, focusMinutes: step.focusMinutes };
    }) };
  }
  keys(value, ['draft', 'question']);
  const question = value.question === null ? null : string(value.question, 160);
  if (value.draft === null) {
    requireThat(question !== null);
    return { draft: null, question };
  }
  const draft = value.draft;
  keys(draft, ['title', 'category', 'repeatMode', 'date', 'reminderTimes', 'customDays', 'focusMinutes']);
  const title = string(draft.title, 80);
  requireThat(categories.has(draft.category) && modes.has(draft.repeatMode));
  requireThat(draft.date === null || validDate(draft.date));
  requireThat(Array.isArray(draft.reminderTimes) && draft.reminderTimes.length <= 8 &&
    draft.reminderTimes.every((time) => typeof time === 'string' && /^(?:[01]\d|2[0-3]):[0-5]\d$/u.test(time)) &&
    new Set(draft.reminderTimes).size === draft.reminderTimes.length);
  requireThat(Array.isArray(draft.customDays) && draft.customDays.every((day) => Number.isInteger(day) && day >= 1 && day <= 7) &&
    new Set(draft.customDays).size === draft.customDays.length);
  requireThat(draft.focusMinutes === null || durations.has(draft.focusMinutes));
  requireThat(draft.repeatMode === 'CUSTOM' ? draft.customDays.length > 0 : draft.customDays.length === 0);
  requireThat(draft.repeatMode === 'ONCE' || draft.date === null);
  const complete = draft.reminderTimes.length > 0 && (draft.repeatMode !== 'ONCE' || draft.date !== null);
  requireThat(complete || question !== null);
  if (draft.repeatMode === 'ONCE' && draft.date !== null && draft.reminderTimes.length > 0) {
    requireThat(draft.reminderTimes.every((time) => `${draft.date}T${time}:00` > request.now.slice(0, 19)));
  }
  return { draft: { ...draft, title }, question };
}
