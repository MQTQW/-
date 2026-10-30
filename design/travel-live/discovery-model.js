/* Live research is the only candidate pool; no cached sample fallback. */
const Discovery = (() => {
  const categories = ['sights', 'food', 'experiences'];
  const initial = () => ({city: '', category: 'sights', query: '', selected: {}, custom: [], researched: false, liveResearch: null});
  const catalog = discovery => Array.isArray(discovery?.liveResearch?.candidates) ? discovery.liveResearch.candidates : [];
  function all(discovery) {const cards = new Map(catalog(discovery).map(card => [card.id, card])); for (const item of discovery?.custom || []) if (item?.id && !cards.has(item.id)) cards.set(item.id, {...item, sourceIds: [], sourceType: 'manual', photo: null, image: null}); return [...cards.values()];}
  function selected(discovery) {const choices = discovery?.selected || {}; return all(discovery).filter(card => choices[card.id]).map(card => ({...card, priority: choices[card.id]?.priority === 'must' ? 'must' : 'want'}));}
  function validateSelection(discovery) {
    const ids = Object.keys(discovery?.selected || {});
    if (!ids.length) return '请先选择至少一项想去、想吃或想体验的内容。';
    const cards = new Map(all(discovery).map(card => [card.id, card]));
    for (const id of ids) {
      const card = cards.get(id);
      if (!card) return '有选择已不在本次攻略中，请返回清单重新挑选。';
      if (!['want', 'must'].includes(discovery.selected[id]?.priority)) return '请重新设置清单中的优先级。';
      if (!card.title || !categories.includes(card.category)) return '清单内容缺少名称或分类，请重新选择。';
    }
    return '';
  }
  function validatePlan(discovery) {
    const error = validateSelection(discovery);
    if (error) return error;
    if (!discovery.liveResearch?.id) return '请先联网检索攻略，再编排行程。';
    const known = new Set(catalog(discovery).map(card => card.id));
    const unsupported = selected(discovery).filter(card => !known.has(card.id));
    return unsupported.length ? `手动补充的「${unsupported.map(card => card.title).join('、')}」暂不支持首次 AI 编排。内容会保留，请先移出本次选择，生成后可在行程中手动添加。` : '';
  }
  return {initial, catalog, all, selected, validateSelection, validatePlan};
})();
