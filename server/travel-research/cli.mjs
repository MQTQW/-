import { readFile, readdir, mkdir, writeFile, stat } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadConfig } from './config.mjs';
import { createMcpClient, collectSources, normalizeNote } from './mcp.mjs';
import { researchTravel, validateResearchInput } from './pipeline.mjs';
import { renderReport } from './report.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
function parseArgs() {
  const args = {};
  const allowed = new Set(['destination','days','pace','transport','max-candidates','config','out','source-file','capture-dir','mcp-config','count','resume-report']);
  for (let index=2;index<process.argv.length;index+=2) {
    const name = process.argv[index]?.slice(2);
    if (!process.argv[index]?.startsWith('--') || !allowed.has(name) || !process.argv[index+1] || process.argv[index+1].startsWith('--'))
      throw Object.assign(new Error('参数无效'),{code:'INVALID_ARGUMENTS'});
    args[name] = process.argv[index+1];
  }
  return args;
}
const progress = (message) => console.log(message);
async function main() {
  const args = parseArgs();
  const options = validateResearchInput({destination:args.destination??'杭州',dayCount:Number(args.days??3),
    pace:args.pace??'normal',transport:args.transport??'公共交通',maxCandidates:Number(args['max-candidates']??12)});
  const { destination, dayCount } = options;
  const count=Number(args.count??5);
  if (!Number.isInteger(count) || count<1 || count>5) throw Object.assign(new Error('来源数量无效'),{code:'INVALID_RETRIEVAL_INPUT'});
  if (['source-file','capture-dir','mcp-config','resume-report'].filter((name)=>Boolean(args[name])).length !== 1)
    throw Object.assign(new Error('必须且只能选择一种来源输入'),{code:'SOURCES_REQUIRED'});
  const config=await loadConfig(args.config||path.join(here,'.env.local'));
  const output=path.resolve(args.out||path.join(here,'../../delivery/travel-research-20261028'));
  let sources, retrieval, savedExtraction;
  if(args['resume-report']) {
    const saved=JSON.parse(await readFile(path.resolve(args['resume-report']),'utf8'));
    if(saved.destination!==options.destination) throw new Error('复用的报告目的地不一致');
    sources=saved.sources;savedExtraction={raw:{candidates:saved.candidates},method:saved.extractionMethod,stats:saved.stats?.ai};
    retrieval={mode:'saved_real_mcp_capture',warnings:['复用此前取得的真实攻略，不代表本次实时检索成功。'],sourceCount:sources?.length};
    progress('复用已校验的提取结果，本次只重新查询地图。');
  } else if (args['source-file']) {
    const saved=JSON.parse(await readFile(path.resolve(args['source-file']),'utf8'));
    sources=saved.sources;
    retrieval={mode:'saved_real_mcp_capture',warnings:[...(Array.isArray(saved.warnings)?saved.warnings:[]),
      '这是此前真实 MCP 捕获；不代表本次实时检索成功。'],sourceCount:sources?.length};
    progress(`使用 ${sources?.length??0} 篇已获取的真实 MCP 攻略来源。`);
  } else if (args['capture-dir']) {
    const capture=path.resolve(args['capture-dir']);
    const files=(await readdir(capture)).filter((name)=>/^mcp-detail-[a-f0-9]{24}\.json$/u.test(name)).sort().slice(0,5);
    sources=[];
    for (const file of files) {
      const absolute=path.join(capture,file); const value=JSON.parse(await readFile(absolute,'utf8'));
      const expectedPostId=file.match(/^mcp-detail-([a-f0-9]{24})\.json$/u)[1];
      sources.push(normalizeNote(value.data,`S${sources.length+1}`,(await stat(absolute)).mtime.toISOString(),expectedPostId));
    }
    retrieval={mode:'saved_real_mcp_capture',warnings:['这是此前真实 MCP 捕获；不代表本次实时检索成功。'],sourceCount:sources.length};
    progress(`使用 ${sources.length} 篇此前真实 MCP 捕获的攻略，不重复检索。`);
  } else if (args['mcp-config']) {
    const client=await createMcpClient(args['mcp-config']);
    const collected=await collectSources(client,{keyword:`${destination} ${dayCount}天 攻略`,count,onProgress:progress});
    sources=collected.sources; retrieval={mode:'live_mcp',warnings:collected.warnings,resultCount:collected.resultCount};
  } else throw Object.assign(new Error('请指定 --capture-dir 或 --mcp-config'),{code:'SOURCES_REQUIRED'});
  await mkdir(output,{recursive:true});
  const result=await researchTravel({sources,...options,config,savedExtraction,onProgress:progress,onAiCheckpoint:async(checkpoint)=>{
    if(checkpoint.stage==='vision') await writeFile(path.join(output,'sources-with-images.json'),JSON.stringify({sources:checkpoint.sources,warnings:['此前取得的公开资料及模型图片转录，转录内容仍需对照原图检查。']},null,2),'utf8');
    if(checkpoint.stage==='extraction') await writeFile(path.join(output,'ai-extraction-debug.json'),JSON.stringify({stage:checkpoint.stage,raw:checkpoint.raw},null,2),'utf8');
  }});
  result.retrieval=retrieval; result.warnings.push(...retrieval.warnings);
  result.provenance={sources:args['mcp-config']?'live_mcp':'saved_mcp_capture',extraction:savedExtraction?'saved_validated_extraction':result.extractionMethod,maps:config.amapKey?'queried_this_run':'not_configured'};
  await mkdir(output,{recursive:true});
  await writeFile(path.join(output,'research.json'),JSON.stringify(result,null,2),'utf8');
  await writeFile(path.join(output,'index.html'),renderReport(result),'utf8');
  console.log(JSON.stringify({output,sources:result.sources.length,candidates:result.candidates.length,
    matched:result.stats.matchedPlaceCount,statuses:result.statuses}));
}
main().catch((error)=>{console.error(JSON.stringify({error:error.code||'RESEARCH_FAILED',message:'研究样例未完成，请检查配置、输入或本机 MCP 状态。'}));process.exitCode=1;});
