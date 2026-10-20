// Rebuild README illustrations from the checked-in HTML and unedited app captures.
// Requires Playwright and Chrome; does not connect to the network.
const path = require('node:path');
const { pathToFileURL } = require('node:url');
let playwright;
try { playwright = require('playwright'); }
catch { playwright = require(path.join(require('node:os').homedir(), '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright')); }

(async () => {
  const browser = await playwright.chromium.launch({
    executablePath: process.env.CHROME_PATH || path.join(process.env.PROGRAMFILES || 'C:/Program Files', 'Google/Chrome/Application/chrome.exe'),
    headless: true,
  });
  try {
    const context = await browser.newContext({ viewport: { width: 1280, height: 1200 }, deviceScaleFactor: 1.5 });
    await context.setOffline(true);
    const page = await context.newPage();
    await page.goto(pathToFileURL(path.join(__dirname, 'source.html')).href);
    await page.evaluate(async () => {
      await document.fonts.ready;
      await Promise.all([...document.images].map(img => img.decode()));
    });
    for (const id of ['cover', 'screens-today', 'screens-organize']) {
      const section = page.locator('#' + id);
      await section.screenshot({ path: path.join(__dirname, id + '.png') });
      const clipped = await section.evaluate(el => {
        const bottom = el.getBoundingClientRect().bottom - 35;
        return el.classList.contains('board') && [...el.querySelectorAll('.phone')].some(phone => phone.getBoundingClientRect().bottom > bottom);
      });
      if (clipped) throw new Error(id + ': phone extends beyond the gallery');
      console.log('Rendered ' + id + '.png');
    }
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
