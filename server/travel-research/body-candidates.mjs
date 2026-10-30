// A transparent non-AI fallback for the Hangzhou validation sample only.
// Every candidate must occur literally in an obtained public note body.
const NAMES = ['断桥残雪','白堤','苏堤春晓','花港观鱼','雷峰塔','河坊街','灵隐寺','飞来峰','永福寺','九溪烟树','小河直街','拱宸桥','孤山公园','曲院风荷','乌龟潭','三潭印月','南宋御街','法喜寺','龙井村','宋城','西溪湿地','武林夜市'];
export function extractFromBodies(sources, destination) {
  if (!/杭州/u.test(destination)) return { candidates: [] };
  const candidates = [];
  for (const name of NAMES) {
    const sourceRefs = [];
    for (const source of sources) {
      const index = source.body.indexOf(name);
      if (index >= 0) sourceRefs.push({ sourceId: source.id, evidenceType: 'body', evidenceText: source.body.slice(Math.max(0,index-12),index+name.length+24) });
    }
    if (sourceRefs.length) candidates.push({ name, city: '杭州', category: 'attraction', suggestedDurationMinutes: 60,
      sourceRefs, notes: '正文规则提取；60 分钟仅为可编辑默认停留建议，未读取图片或核实开放信息。' });
  }
  return { candidates };
}
