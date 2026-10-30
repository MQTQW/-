const INITIAL_DAYS=[
 {day:1,date:'2026-11-07',name:'西湖经典线',summary:'断桥、白堤与雷峰夕照',stops:[
  {id:'broken',title:'断桥残雪',time:'09:00',duration:40,sourceIds:['S2'],locationStatus:'matched',lng:120.1513,lat:30.2582,photo:'westlake',notes:'湖边散步，按体力调整停留。',travelMinutes:null},
  {id:'bai',title:'白堤',time:'10:10',duration:60,sourceIds:['S2'],locationStatus:'matched',lng:120.1456,lat:30.2556,photo:'westlake',notes:'不用赶路，给拍照和休息留空档。',travelMinutes:20},
  {id:'meal',title:'午餐 · 杭鸽油爆虾',time:'12:30',duration:60,sourceIds:['S1'],locationStatus:'pending',lng:null,lat:null,photo:null,notes:'攻略未明确分店，先保留用餐位置。',travelMinutes:null},
  {id:'pagoda',title:'雷峰塔景区',time:'16:00',duration:90,sourceIds:['S1','S2'],locationStatus:'matched',lng:120.1501,lat:30.2318,photo:'pagoda',notes:'登塔属于这站的游览内容，不另排成一个目的地。',travelMinutes:null}
 ]},
 {day:2,date:'2026-11-08',name:'寺庙与茶园',summary:'灵隐、永福寺与九溪',stops:[
  {id:'temple',title:'灵隐寺',time:'09:00',duration:120,sourceIds:['S2'],locationStatus:'matched',lng:120.1015,lat:30.2403,photo:'temple',notes:'出发前核对预约和开放安排。',travelMinutes:null},
  {id:'yongfu',title:'永福寺',time:'11:45',duration:60,sourceIds:['S2'],locationStatus:'matched',lng:120.0980,lat:30.2370,photo:'temple',notes:'寺院与山林间慢慢走。',travelMinutes:25},
  {id:'jiuxi',title:'九溪烟树',time:'14:00',duration:90,sourceIds:['S2'],locationStatus:'matched',lng:120.1129,lat:30.2076,photo:'westlake',notes:'步行时长只是规划建议，保留休息时间。',travelMinutes:45}
 ]},
 {day:3,date:'2026-11-09',name:'运河烟火线',summary:'小河直街、古桥与夜市',stops:[
  {id:'xiaohe',title:'小河直街',time:'10:00',duration:90,sourceIds:['S2'],locationStatus:'matched',lng:120.1371,lat:30.3171,photo:'canal',notes:'逛逛沿河的小店。',travelMinutes:null},
  {id:'bridge',title:'拱宸桥',time:'12:00',duration:45,sourceIds:['S2'],locationStatus:'matched',lng:120.1398,lat:30.3212,photo:'canal',notes:'在运河边停一会儿。',travelMinutes:15},
  {id:'night',title:'武林夜市',time:'17:00',duration:80,sourceIds:['S1'],locationStatus:'matched',lng:120.1596,lat:30.2617,photo:'canal',notes:'按晚间活动安排；具体营业仍需核对。',travelMinutes:35}
 ]}
];
const FEATURED=[
 {id:'pagoda',title:'雷峰塔景区',photo:'pagoda',day:0,time:'16:00',duration:90,description:'西湖边的地标。景区与登塔活动合在同一站，按兴趣调整。',sourceIds:['S1','S2']},
 {id:'temple',title:'灵隐寺',photo:'temple',day:1,time:'09:00',duration:120,description:'寺院与山林相伴，给参观和休息留出充足时间。',sourceIds:['S2']},
 {id:'bridge',title:'拱宸桥与运河',photo:'canal',day:2,time:'12:00',duration:45,description:'从古桥看运河，再去小河街区感受城市日常。',sourceIds:['S2']},
 {id:'westlake',title:'西湖漫步',photo:'westlake',day:0,time:'09:00',duration:100,description:'从断桥到白堤，沿湖慢走。具体节点保留在每日安排里。',sourceIds:['S2']}
];
const SOURCES=[
 {id:'S1',title:'本J人耗时6h做的杭州攻略，满意的睡不着',author:'豆鲨包',date:'2026.05.18',description:'图片中的时段、餐饮和晚间活动，作为安排参考。',excerpt:'图文攻略用于参考地点和活动；本原型不复现完整帖子。'},
 {id:'S2',title:'杭州3天2夜懒人攻略｜西湖、灵隐、九溪路线',author:'奶油小方',date:'2026.07.30',description:'西湖、寺庙茶园、运河三条线路，适合分区安排。',excerpt:'正文提到断桥、白堤、雷峰塔、灵隐、永福、九溪、小河直街和拱宸桥。'}
];
const makeState=()=>({theme:'light',tab:'overview',page:null,pageStack:[],tripStartDate:'2026-11-07',title:'杭州3日行程',activeDay:0,selectedNode:null,carouselIndex:0,scrolls:{},days:JSON.parse(JSON.stringify(INITIAL_DAYS)),sources:JSON.parse(JSON.stringify(SOURCES)),places:INITIAL_DAYS.flatMap(day=>day.stops),budget:{stay:880,food:480,transport:180,tickets:0,misc:100},weatherPreview:'far',relationMode:'place',relationSelected:'S1',editMode:false,editDraft:null,sheet:null,sheetTab:null,saved:false,confirmChoice:'',searchQuery:'',error:'',message:'',mapZoom:1,preparation:{umbrella:false,shoes:false,booking:false},climb:true});
