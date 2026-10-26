import { createServer } from 'node:http';
import { createAssistHandler } from './app.mjs';

const token = process.env.ASSIST_ACCESS_TOKEN?.trim() ?? '';
if (!process.env.DEEPSEEK_API_KEY?.trim() || token.length < 32 || token.length > 256 || !/^[!-~]+$/u.test(token)) {
  console.error('Set DEEPSEEK_API_KEY and ASSIST_ACCESS_TOKEN (at least 32 characters) in the server environment.');
  process.exitCode = 1;
} else {
  const port = Number(process.env.PORT ?? 8787);
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('Invalid PORT');
  const server = createServer({ requestTimeout: 25_000, headersTimeout: 10_000, maxHeaderSize: 8192 }, createAssistHandler());
  server.listen(port, process.env.HOST ?? '127.0.0.1', () => console.info('MY PLAN assist service started.'));
}
