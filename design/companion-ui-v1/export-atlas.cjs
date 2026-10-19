let playwright;
try {playwright=require('playwright');} catch {playwright=require(require('node:path').join(require('node:os').homedir(),'.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright'));}
const {chromium}=playwright;
const {pathToFileURL}=require('node:url');
const path=require('node:path');
(async()=>{
  const browser=await chromium.launch({executablePath:process.env.CHROME_PATH||path.join(process.env.PROGRAMFILES||'C:/Program Files','Google/Chrome/Application/chrome.exe'),headless:true});
  const page=await browser.newPage({viewport:{width:1800,height:1100},deviceScaleFactor:1,colorScheme:'light'});
  await page.goto(pathToFileURL(path.join(__dirname,'atlas.html')).href);
  await page.evaluate(()=>Promise.all([...document.images].map(image=>image.decode())));
  await page.locator('#all-pages').screenshot({path:path.join(__dirname,'exports/overview.png')});
  await page.locator('#all-dialogs').screenshot({path:path.join(__dirname,'exports/dialogs-overview.png')});
  console.log('Exported overview: 20 pages and 6 dialogs');
  const report=require('./qa-report.json');
  if(report.errors.length||report.layout.some(p=>p.overflow>0)||report.narrow.some(p=>p.overflow>0||p.horizontal>0)||report.expanded>0)throw new Error('Layout or runtime checks failed');
  await page.context().setOffline(true);
  await page.goto(pathToFileURL(path.join(__dirname,'index.html')).href);
  const frame=page.frames().find(f=>f!==page.mainFrame());
  await frame.waitForFunction(()=>window.MPDesign&&document.querySelector('.mp-phone svg'));
  console.log('Offline preview passed; runtime and layout checks passed');
  await browser.close();
})().catch(e=>{console.error(e);process.exit(1);});
