/* Cached discovery examples and a deterministic, selection-first scheduling demo. */
const Discovery = (() => {
  'use strict';
  const copy = value => JSON.parse(JSON.stringify(value));
  const categories = ['sights','food','experiences'];
  const reservedIds = new Set(['arrival','hotel','return']);
  const catalog = [
    {id:'pagoda',title:'雷峰塔',category:'sights',region:'西湖',description:'西湖南岸的一站，想登塔看湖可以先放进清单。',duration:90,sourceIds:['S1','S2'],photo:'pagoda',kind:'visit',lng:120.1501,lat:30.2318,locationStatus:'matched',notes:'登塔属于这一站的游览内容；开放与门票需另行核对。'},
    {id:'temple',title:'灵隐寺',category:'sights',region:'灵隐',description:'在寺院与山林间慢慢走，给这片区域多留一点时间。',duration:120,sourceIds:['S2'],photo:'temple',kind:'visit',lng:120.1015,lat:30.2403,locationStatus:'matched',notes:'出发前核对预约与开放安排。'},
    {id:'bridge',title:'拱宸桥',category:'sights',region:'运河',description:'参考运河线路，在桥边停一会儿。',duration:45,sourceIds:['S2'],photo:'canal',kind:'visit',lng:120.1398,lat:30.3212,locationStatus:'matched',notes:'具体步行线路与交通需另行确认。'},
    {id:'broken',title:'断桥残雪',category:'sights',region:'西湖',description:'沿西湖边走一段，按自己的体力决定停多久。',duration:40,sourceIds:['S2'],photo:null,kind:'visit',lng:120.1513,lat:30.2582,locationStatus:'matched',notes:'未附断桥实景照片；沿湖步行按体力调整。'},
    {id:'jiuxi',title:'九溪烟树',category:'sights',region:'九溪',description:'把山林与溪水放进想去清单，步行强度留待确认。',duration:90,sourceIds:['S2'],photo:null,kind:'visit',lng:120.1129,lat:30.2076,locationStatus:'matched',notes:'按体力调整步行，预留休息空档；未附九溪实景照片。'},
    {id:'meal',title:'油爆虾',category:'food',region:'门店待定',description:'从图片攻略里记下这道菜，先选想吃什么，再确认去哪家。',duration:60,sourceIds:['S1'],photo:null,kind:'meal',lng:null,lat:null,locationStatus:'pending',pending:true,notes:'这是菜品意向，尚未选择餐馆或分店；没有可用于路线计算的门店位置。'},
    {id:'canalwalk',title:'运河慢走',category:'experiences',region:'运河',description:'参考帖子中的运河线路，留一段沿河慢走的时间。',duration:75,sourceIds:['S2'],photo:null,kind:'visit',lng:null,lat:null,locationStatus:'pending',notes:'体验意向；具体起终点与步行线路待确认。'},
    {id:'teabreak',title:'喝茶与休息',category:'experiences',region:'自由安排',description:'示例灵感：给喝茶、发呆与休息留一段时间，并非从参考帖子提取。',duration:60,sourceIds:[],photo:null,kind:'visit',lng:null,lat:null,locationStatus:'pending',notes:'原型作者提供的示例灵感，并非从帖子提取；茶馆与位置待自行确认。',sourceType:'authored'}
  ];
  catalog.forEach(card => { Object.freeze(card.sourceIds); Object.freeze(card); });
  Object.freeze(catalog);
  const initial = () => ({city:'杭州',category:'sights',query:'',selected:{},custom:[],researched:false});
  const customCard = item => ({
    id:item.id,title:typeof item.title === 'string' ? item.title.trim() : '',
    category:item.category || 'sights',region:String(item.region || '自选地点'),
    description:String(item.description || '你手动加入的想去项。'),
    duration:item.duration == null ? 60 : Number(item.duration),
    sourceIds:[],photo:null,kind:item.category === 'food' ? 'meal' : 'visit',
    lng:null,lat:null,locationStatus:'manual',
    notes:String(item.notes || '用户手动添加；具体位置待确认。'),sourceType:'manual'
  });
  function cardMap(discovery) {
    const cards = new Map(catalog.map(card => [card.id,card]));
    for (const item of Array.isArray(discovery?.custom) ? discovery.custom : []) {
      if (item && typeof item.id === 'string' && item.id && !cards.has(item.id) && !reservedIds.has(item.id)) cards.set(item.id,customCard(item));
    }
    return cards;
  }
  function selected(discovery) {
    const cards = cardMap(discovery);
    const choices = discovery?.selected && typeof discovery.selected === 'object' && !Array.isArray(discovery.selected) ? discovery.selected : {};
    return Object.entries(choices).filter(([id]) => cards.has(id)).map(([id,choice]) => ({...copy(cards.get(id)),priority:choice?.priority === 'must' ? 'must' : 'want'}));
  }
  function validateSelection(discovery) {
    const choices = discovery?.selected;
    if (!choices || typeof choices !== 'object' || Array.isArray(choices) || !Object.keys(choices).length) return '请先选择至少一个想去、想吃或想体验的项目，再安排日程。';
    const cards = cardMap(discovery), seen = new Set(catalog.map(card => card.id));
    for (const item of Array.isArray(discovery?.custom) ? discovery.custom : []) {
      if (!item || typeof item.id !== 'string' || !item.id || reservedIds.has(item.id) || seen.has(item.id)) return '手动添加项目的标识重复或无效，请删除该项后重新添加。';
      seen.add(item.id);
    }
    for (const [id,choice] of Object.entries(choices)) {
      if (!cards.has(id)) return `选择的项目「${id}」已不存在，请重新选择。`;
      if (!choice || !['want','must'].includes(choice.priority)) return `请为「${cards.get(id).title}」选择“想去”或“一定要去”。`;
      const card = cards.get(id);
      if (!card.title || !categories.includes(card.category)) return '请补全手动添加项目的名称与分类。';
      if (!Number.isInteger(card.duration) || card.duration < 1) return `「${card.title}」的停留时长须为正整数分钟。`;
    }
    return '';
  }
  const unplacedCards = cards => cards.map(({id,title}) => ({id,title}));
  const preferredDay = (card,count) => Math.min(count - 1,card.region === '运河' ? 2 : ['灵隐','九溪'].includes(card.region) ? 1 : 0);
  function plan(input,discovery) {
    const wishes = selected(discovery), selectionError = validateSelection(discovery);
    if (selectionError) return {trip:null,error:selectionError,unplaced:unplacedCards(wishes)};
    const form = {...Model.fresh().form,...input,destination:discovery.city};
    const result = Model.createTrip(form);
    if (result.error || !result.trip) return {trip:null,error:result.error || '暂时无法创建行程，请检查条件。',unplaced:unplacedCards(wishes)};
    const trip = result.trip;
    const validSources = new Set(trip.sources.map(source => source.id));
    // Model supplies dates, constraints and sample source metadata, never the discovery itinerary.
    trip.days.forEach(day => { day.stops = day.stops.filter(stop => ['arrival','hotel','return'].includes(stop.kind)); day.routeDirty = true; });
    const windows = trip.days.map((day,index) => {
      const firstFixed = index === 0 ? day.stops.filter(stop => ['arrival','hotel'].includes(stop.kind)) : [];
      const start = Math.max(9 * 60,...firstFixed.map(stop => Model.timeMinutes(stop.time) + Number(stop.duration) + 30));
      const departure = day.stops.find(stop => stop.kind === 'return');
      const end = Math.min(20 * 60,departure ? Model.timeMinutes(departure.time) - 60 : 20 * 60);
      return {start,end,placed:[]};
    });
    const fits = (window,start,duration) => start >= window.start && start + duration <= window.end && window.placed.every(stop => start >= stop.end + 30 || start + duration + 30 <= stop.start);
    const place = (card,dayIndex,start) => {
      windows[dayIndex].placed.push({start,end:start + card.duration});
      trip.days[dayIndex].stops.push({
        id:card.id,title:card.title,time:Model.formatTime(start),duration:card.duration,
        notes:card.notes,sourceIds:card.sourceIds.filter(id => validSources.has(id)),
        locationStatus:card.locationStatus,lng:card.lng,lat:card.lat,photo:card.photo,
        locked:false,manuallyEdited:false,mustKeep:card.priority === 'must',kind:card.kind,
        selectionId:card.id,priority:card.priority,region:card.region,
        ...(card.pending ? {pending:true} : {}),
        planningReason:`按${card.region}区域与可用时段示例排布；预留空档不代表真实交通耗时。`
      });
    };
    const unplaced = [];
    // Reserve discrete meal slots before filling flexible visit slots. No restaurant is invented.
    for (const card of wishes.filter(card => card.kind === 'meal')) {
      let placed = false;
      for (let index = 0; index < windows.length && !placed; index++) {
        for (const start of [12 * 60,18 * 60]) {
          if (!fits(windows[index],start,card.duration)) continue;
          place(card,index,start); placed = true; break;
        }
      }
      if (!placed) unplaced.push(card);
    }
    const visits = wishes.filter(card => card.kind !== 'meal').sort((a,b) => preferredDay(a,windows.length) - preferredDay(b,windows.length) || Number(b.priority === 'must') - Number(a.priority === 'must'));
    for (const card of visits) {
      const preferred = preferredDay(card,windows.length);
      const indexes = [preferred,...windows.map((_,index) => index).filter(index => index > preferred),...windows.map((_,index) => index).filter(index => index < preferred)];
      let placed = false;
      for (const index of indexes) {
        const window = windows[index];
        const starts = [window.start,...window.placed.map(stop => stop.end + 30)].sort((a,b) => a-b);
        const start = starts.find(candidate => fits(window,candidate,card.duration));
        if (start === undefined) continue;
        place(card,index,start); placed = true; break;
      }
      if (!placed) unplaced.push(card);
    }
    if (unplaced.length) return {trip:null,error:`当前可用时间排不下：${unplaced.map(card => `「${card.title}」`).join('、')}。全部选择均已保留，请增加天数、调整到达或返程时间，或自行减少选择后重试。`,unplaced:unplacedCards(unplaced)};
    for (const day of trip.days) {
      day.stops.sort((a,b) => Model.timeMinutes(a.time) - Model.timeMinutes(b.time));
      const regions = [...new Set(day.stops.filter(stop => stop.selectionId).map(stop => stop.region))];
      day.name = regions.length ? regions.join(' · ') : '自由安排';
    }
    trip.title = `杭州${trip.days.length}日行程`;
    trip.wishes = wishes.map(({id,title,category,region,priority,sourceIds,kind}) => ({id,title,category,region,priority,sourceIds:sourceIds.filter(id => validSources.has(id)),kind}));
    const usedSources = new Set(trip.wishes.flatMap(wish => wish.sourceIds));
    trip.sources = trip.sources.filter(source => usedSources.has(source.id));
    trip.planningNote = '按区域与大致时段排布的本地规则演示，未计算真实路线、路程或实时交通；餐饮为菜品意向，具体门店待确认。';
    return {trip,error:'',unplaced:[]};
  }
  return {catalog,initial,selected,validateSelection,plan};
})();
