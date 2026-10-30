import test from 'node:test';
import assert from 'node:assert/strict';
import { validateExtraction } from '../contract.mjs';
import { buildDraft,reconcileDraftTimes } from '../planner.mjs';
import { researchTravel } from '../pipeline.mjs';

test('晚间活动不会被排进早晨，地图重排后仍保留晚间建议',()=>{
  const sources=[{id:'S1',body:'晚上逛武林夜市。',images:[]}];
  const raw={name:'武林夜市',city:'杭州',category:'area',suggestedDurationMinutes:90,
    sourceRefs:[{sourceId:'S1',evidenceType:'body',evidenceText:'武林夜市'}],notes:'仅为建议'};
  const c=validateExtraction({candidates:[raw]},{sources}).candidates[0];
  c.verification={status:'verified',poiId:'OFFLINE_FIXTURE',location:{longitude:120,latitude:30}};
  const draft=buildDraft({destination:'杭州',dayCount:1,candidates:[c]});
  assert.equal(draft.days[0].stops[0].startTime,'17:00');
  assert.equal(reconcileDraftTimes(draft,{candidates:[c],legs:[],dayCount:1}).days[0].stops[0].startTime,'17:00');
});

test('地图复查复用已校验提取结果，不再次花费模型调用；伪造引用仍拒绝',async()=>{
  const sources=[{id:'S1',body:'西湖。',images:[]}];
  const raw={candidates:[{name:'西湖',city:'杭州',category:'attraction',suggestedDurationMinutes:60,
    sourceRefs:[{sourceId:'S1',evidenceType:'body',evidenceText:'西湖'}],notes:''}]};
  let aiCalls=0;
  const options={sources,destination:'杭州',dayCount:1,config:{aiKey:'offline-fixture'},
    aiImpl:async()=>{aiCalls++;throw new Error('should not be called');},savedExtraction:{raw,method:'deepseek'}};
  const result=await researchTravel(options);
  assert.equal(aiCalls,0);assert.equal(result.extractionMethod,'deepseek');assert.equal(result.candidates.length,1);
  raw.candidates[0].sourceRefs[0].sourceId='UNKNOWN';
  await assert.rejects(researchTravel(options),{code:'INVALID_EXTRACTION'});
  assert.equal(aiCalls,0);
});
