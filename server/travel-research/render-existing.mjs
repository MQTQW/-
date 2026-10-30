import { readFile, writeFile } from 'node:fs/promises';
import { renderReport } from './report.mjs';
const [input, output] = process.argv.slice(2);
if (!input || !output) throw new Error('Usage: node render-existing.mjs research.json index.html');
const report = JSON.parse(await readFile(input, 'utf8'));
await writeFile(output, renderReport(report), 'utf8');
console.log('Rebuilt local preview from the saved report; no external API calls.');
