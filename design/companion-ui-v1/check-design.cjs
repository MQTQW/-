let playwright;
try {playwright=require('playwright');} catch {playwright=require(require('node:path').join(require('node:os').homedir(),'.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright'));}
const { chromium } = playwright;
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

(async () => {
  const output = __dirname;
  fs.mkdirSync(path.join(output, 'exports/screens'), {recursive:true});
  fs.mkdirSync(path.join(output, 'exports/dialogs'), {recursive:true});
  const browser = await chromium.launch({executablePath:process.env.CHROME_PATH||path.join(process.env.PROGRAMFILES||'C:/Program Files','Google/Chrome/Application/chrome.exe'),headless:true});
  const page = await browser.newPage({viewport:{width:736,height:1100},deviceScaleFactor:2,colorScheme:'light'});
  const errors=[];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto(pathToFileURL(path.join(output,'index.html')).href);
  const frame = page.frames().find(item=>item!==page.mainFrame());
  await frame.waitForFunction(()=>Boolean(window.MPDesign));
  await frame.waitForFunction(()=>document.querySelector('.mp-phone svg'));
  const catalog=await frame.evaluate(()=>window.MPDesign.catalog);
  const layout=[];
  for (let i=0;i<catalog.length;i++) {
    const entry=catalog[i];
    await frame.evaluate(id=>{MPDesign.reset();MPDesign.go(id);},entry.id);
    await frame.locator('.mp-phone').screenshot({path:path.join(output,'exports/screens',String(i+1).padStart(2,'0')+'-'+entry.id+'.png')});
    const metrics=await frame.evaluate(()=>{
      const main=document.querySelector('.mp-main');
      const box=main.getBoundingClientRect();
      return {page:MPDesign.state.page,width:document.querySelector('.mp-phone').clientWidth,mainHeight:main.clientHeight,scrollHeight:main.scrollHeight,overflow:Math.max(0,main.scrollHeight-main.clientHeight),bottom:Math.max(...[...main.children].map(el=>el.getBoundingClientRect().bottom))-box.bottom,iconsMissing:document.querySelectorAll('.mp-phone i[data-lucide]').length};
    });
    layout.push(metrics);
  }
  const dialogs=['time','repeat','delete','unsaved','sound','backup'];
  for (let i=0;i<dialogs.length;i++) {
    const type=dialogs[i];
    await frame.evaluate(type=>{MPDesign.reset();MPDesign.go(['sound','backup'].includes(type)?'settings':'edit');MPDesign.modal(type);},type);
    await frame.locator('.mp-phone').screenshot({path:path.join(output,'exports/dialogs',String(i+1).padStart(2,'0')+'-'+type+'.png')});
  }
  await page.setViewportSize({width:320,height:1100});
  const narrow=[];
  for(const item of catalog){
    await frame.evaluate(id=>{MPDesign.reset();MPDesign.go(id);},item.id);
    narrow.push(await frame.evaluate(()=>({page:MPDesign.state.page,phone:document.querySelector('.mp-phone').clientWidth,overflow:document.querySelector('.mp-main').scrollHeight-document.querySelector('.mp-main').clientHeight,horizontal:document.documentElement.scrollWidth-document.documentElement.clientWidth})));
  }
  await frame.evaluate(()=>{MPDesign.reset();MPDesign.go('today');});
  await frame.locator('.mp-phone').screenshot({path:path.join(output,'exports/narrow-home.png')});
  await page.setViewportSize({width:736,height:1100});
  await frame.evaluate(()=>{MPDesign.reset();MPDesign.go('today');});
  fs.writeFileSync(path.join(output,'initial-layout.json'),JSON.stringify({layout,narrow},null,2));
  const assert = (condition,label) => {if(!condition)throw new Error(label);};
  await frame.locator('[data-route="create"]').first().click();
  await frame.locator('[data-draft="name"]').fill('');
  await frame.locator('[data-action="save-plan"]').click();
  assert(await frame.locator('[role="alert"]').isVisible(),'Empty-name validation');
  await frame.locator('[data-draft="name"]').fill('预览测试计划');
  await frame.locator('[data-time-index]').first().click();
  await frame.locator('[data-time-hour]').selectOption('22');
  await frame.locator('[data-time-minute]').selectOption('15');
  await frame.locator('[data-action="confirm-time"]').click();
  assert(await frame.locator('[data-time-index]').first().innerText() === '22:15','Time selector');
  await frame.locator('[data-repeat="CUSTOM"]').click();
  await frame.locator('[data-repeat-day="6"]').click();
  await frame.locator('[data-action="confirm-repeat"]').click();
  await frame.locator('[data-action="save-plan"]').click();
  assert(await frame.evaluate(()=>MPDesign.state.tasks.some(t=>t.name==='预览测试计划'&&t.time==='22:15')),'Save new plan');
  await frame.evaluate(()=>{MPDesign.reset();MPDesign.go('plans');});
  await frame.locator('[data-delete="review"]').click();
  await frame.locator('[data-action="confirm-delete"]').click();
  assert(await frame.evaluate(()=>MPDesign.state.tasks.length===4),'Delete plan');
  await frame.locator('[data-action="undo-delete"]').click();
  assert(await frame.evaluate(()=>MPDesign.state.tasks.length===5),'Undo delete');
  await frame.evaluate(()=>MPDesign.go('app-picker'));
  await frame.locator('[data-app="wechat"]').click();
  await frame.locator('[data-route="limit-edit"]').click();
  await frame.locator('[data-limit="60"]').click();
  await frame.locator('[data-action="save-limit"]').click();
  assert(await frame.evaluate(()=>MPDesign.state.page==='focus'&&MPDesign.state.limit===60),'Focus limit flow');
  await frame.evaluate(()=>MPDesign.go('permissions'));
  await frame.locator('[data-permission="battery"]').click();
  assert(await frame.evaluate(()=>MPDesign.state.permissions.battery),'Permission state');
  await frame.evaluate(()=>MPDesign.go('voice-listening'));
  await frame.locator('[data-action="voice-next"]').click();
  await frame.locator('[data-action="voice-save"]').click();
  assert(await frame.evaluate(()=>MPDesign.state.page==='today'&&MPDesign.state.tasks.length===6),'Voice confirmation flow');
  await frame.evaluate(()=>{MPDesign.reset();MPDesign.go('create');});
  await frame.locator('[data-draft="name"]').fill('尚未保存');
  await frame.locator('[data-action="back"]').click();
  assert(await frame.locator('[data-action="discard-edit"]').isVisible(),'Unsaved edits flow');
  await frame.locator('[data-action="close-modal"]').click();
  await frame.evaluate(()=>{MPDesign.reset();});
  await frame.locator('[data-action="toggle-completed"]').click();
  const expanded=await frame.locator('.mp-main').evaluate(el=>el.scrollHeight-el.clientHeight);
  await page.emulateMedia({colorScheme:'dark'});
  await frame.locator('.mp-phone').screenshot({path:path.join(output,'exports/dark-home.png')});
  await page.emulateMedia({colorScheme:'light'});
  const report={screens:catalog.length,dialogs:dialogs.length,errors,layout,narrow,expanded,interactions:'passed'};
  fs.writeFileSync(path.join(output,'qa-report.json'),JSON.stringify(report,null,2));
  console.log(JSON.stringify(report,null,2));
  await browser.close();
})().catch(error=>{console.error(error);process.exit(1);});
