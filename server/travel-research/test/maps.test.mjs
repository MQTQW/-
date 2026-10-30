import test from 'node:test';
import assert from 'node:assert/strict';
import { createAmap, matchPoi, routeMinutes } from '../maps.mjs';
const candidate = { id: 'C1', name: '灵隐寺', city: '杭州' };
const poi = { id: 'B1', name: '灵隐寺', cityname: '杭州市', location: '120.1,30.2' };
test('同名多地点、不同城市及无效坐标不能自动核验通过', () => {
  assert.equal(matchPoi(candidate, [poi]).status, 'verified');
  assert.equal(matchPoi(candidate, [poi,{...poi,id:'B2'}]).status, 'ambiguous');
  assert.equal(matchPoi(candidate, [{...poi,cityname:'南京市'}]).status, 'ambiguous');
  assert.equal(matchPoi(candidate, [{...poi,cityname:''}]).status, 'ambiguous');
  assert.equal(matchPoi(candidate, [{...poi,location:'bad,30'}]).status, 'not_found');
});
test('分店与入口括号信息不能被删除后冒充同一地点', () => {
  assert.equal(matchPoi({name:'某咖啡(西湖店)',city:'杭州'}, [{...poi,name:'某咖啡(城西店)'}]).status,'ambiguous');
  assert.equal(matchPoi({name:'灵隐寺（入口）',city:'杭州'}, [poi]).status,'ambiguous');
  assert.equal(matchPoi({name:'灵隐寺（入口）',city:'杭州'}, [{...poi,name:'灵隐寺(入口)'}]).status,'verified');
  assert.equal(matchPoi({name:'',city:'杭州'}, [{...poi,name:''}]).status,'ambiguous');
});
test('缺少或为零的路线耗时保留未知，公交与步行读取各自结果', () => {
  assert.equal(routeMinutes({route:{paths:[{cost:{duration:'0'}}]}},'步行'),null);
  assert.equal(routeMinutes({route:{transits:[{cost:{duration:'125'}}]}},'公共交通'),3);
  assert.equal(routeMinutes({route:{paths:[{cost:{duration:'125'}}]}},'步行'),3);
});
test('缺地图配置时不发请求；远程错误不能暴露 Key', async () => {
  let called = false;
  await assert.rejects(createAmap({fetchImpl:()=>{called=true;}}).verify(candidate), {code:'MAP_NOT_CONFIGURED'});
  assert.equal(called,false);
  const secret = 'private-key-fixture';
  const client = createAmap({apiKey:secret,intervalMillis:0,fetchImpl:()=>{throw new Error(`failed URL ?key=${secret}`);}});
  await assert.rejects(client.verify(candidate), (error) => error.code === 'MAP_UNAVAILABLE' && !error.message.includes(secret));
});

test('近距离无公交结果时实际查询步行，不以直线距离伪造交通耗时', async () => {
  const paths=[];
  const maps=createAmap({apiKey:'fixture-key',intervalMillis:0,fetchImpl:async(url)=>{
    paths.push(url.pathname);
    return Response.json({status:'1',route:url.pathname.includes('walking')?{paths:[{cost:{duration:'301'}}]}:{transits:[]}});
  }});
  const from={id:'C1',verification:{status:'verified',poiId:'B1',citycode:'0571',location:{longitude:120,latitude:30}}};
  const to={id:'C2',verification:{status:'verified',poiId:'B2',citycode:'0571',location:{longitude:120.001,latitude:30.001}}};
  const leg=await maps.leg(from,to,'公共交通');
  assert.deepEqual(paths,['/v5/direction/transit/integrated','/v5/direction/walking']);
  assert.equal(leg.mode,'步行');assert.equal(leg.durationMinutes,6);assert.equal(leg.status,'verified');
});

test('景区父级展示前缀可匹配独立景点，但不丢弃入口与分店信息',()=>{
  assert.equal(matchPoi({name:'断桥残雪',city:'杭州'},[{...poi,name:'杭州西湖风景名胜区-断桥残雪'}]).status,'verified');
  assert.equal(matchPoi({name:'某咖啡(东店)',city:'杭州'},[{...poi,name:'杭州西湖风景名胜区-某咖啡(西店)'}]).status,'ambiguous');
});
