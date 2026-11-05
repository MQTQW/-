// Based on xpzouying/xiaohongshu-mcp commit a5c8f7799980ba1fdd501999843eb2d17e4c9a9f.
// Local compatibility change: bound first-screen stability, await delayed
// unfiltered results, and log allowlisted phase summaries. Upstream Apache-2.0.
package xiaohongshu

import (
	"context"
	"encoding/json"
	"fmt"
	"net/url"
	"slices"
	"strings"
	"time"

	"github.com/go-rod/rod"
	"github.com/sirupsen/logrus"
	"github.com/xpzouying/xiaohongshu-mcp/errors"
	"github.com/xpzouying/xiaohongshu-mcp/humanize"
)

type SearchResult struct {
	Search struct {
		Feeds FeedsValue `json:"feeds"`
	} `json:"search"`
}

// FilterOption 筛选选项结构体
type FilterOption struct {
	SortBy      string `json:"sort_by,omitempty" jsonschema:"排序依据: 综合|最新|最多点赞|最多评论|最多收藏,默认为'综合'"`
	NoteType    string `json:"note_type,omitempty" jsonschema:"笔记类型: 不限|视频|图文,默认为'不限'"`
	PublishTime string `json:"publish_time,omitempty" jsonschema:"发布时间: 不限|一天内|一周内|半年内,默认为'不限'"`
	SearchScope string `json:"search_scope,omitempty" jsonschema:"搜索范围: 不限|已看过|未看过|已关注,默认为'不限'"`
	Location    string `json:"location,omitempty" jsonschema:"位置距离: 不限|同城|附近,默认为'不限'"`
}

// filterGroup 面板上的一个筛选组：标签是什么、对应入参的哪个字段、允许哪些取值。
//
// 组和选项一律按文本定位，不用序号。面板里同一个选项可能渲染成多个 div.tags
// （数量随视口而变），首项是否重复各组也不一致，下标对不齐。
type filterGroup struct {
	label        string                    // 面板上这一组的标签文本
	defaultValue string                    // 页面初始选中的值，不需要重复点击
	pick         func(FilterOption) string // 从入参里取这一组的值
	allowed      []string                  // 合法取值；在打开页面之前就能挡掉写错的值
}

var filterGroups = []filterGroup{
	{"排序依据", "综合", func(f FilterOption) string { return f.SortBy },
		[]string{"综合", "最新", "最多点赞", "最多评论", "最多收藏"}},
	{"笔记类型", "不限", func(f FilterOption) string { return f.NoteType },
		[]string{"不限", "视频", "图文"}},
	{"发布时间", "不限", func(f FilterOption) string { return f.PublishTime },
		[]string{"不限", "一天内", "一周内", "半年内"}},
	{"搜索范围", "不限", func(f FilterOption) string { return f.SearchScope },
		[]string{"不限", "已看过", "未看过", "已关注"}},
	{"位置距离", "不限", func(f FilterOption) string { return f.Location },
		[]string{"不限", "同城", "附近"}},
}

// pendingFilter 一个待应用的筛选项。
type pendingFilter struct {
	group  string // 组标签
	option string // 选项文本
}

// collectFilters 把入参整理成待应用的筛选项，同一组以最后一个非空值为准。
//
// 校验放在这里是为了在打开浏览器之前就挡掉写错的值——否则要等导航、悬停、
// 在面板里找不到之后才能报错，等于为了说一句"你写错了"先向平台发一次请求。
func collectFilters(filters []FilterOption) ([]pendingFilter, error) {
	selected := make(map[string]string, len(filterGroups))

	for _, f := range filters {
		for _, g := range filterGroups {
			value := g.pick(f)
			if value == "" {
				continue
			}
			if !slices.Contains(g.allowed, value) {
				return nil, fmt.Errorf("%s 不支持 %q，可选：%s",
					g.label, value, strings.Join(g.allowed, "、"))
			}
			selected[g.label] = value
		}
	}

	var pending []pendingFilter
	for _, g := range filterGroups {
		value, ok := selected[g.label]
		if !ok || value == g.defaultValue {
			continue
		}
		pending = append(pending, pendingFilter{group: g.label, option: value})
	}

	return pending, nil
}

type SearchAction struct {
	page *rod.Page
}

func NewSearchAction(page *rod.Page) *SearchAction {
	pp := page.Timeout(60 * time.Second)

	return &SearchAction{page: pp}
}

// A continuously updating page must not consume the entire search deadline
// merely because its DOM or network traffic never becomes fully stable.
func waitSearchFirstScreenStable(page *rod.Page) bool {
	stableContext, cancel := context.WithTimeout(page.GetContext(), 5*time.Second)
	defer cancel()
	_ = page.Context(stableContext).WaitStable(time.Second)
	// Rod may return nil after WaitLoad succeeds even if another stability wait
	// exhausts this context. Keep the parent page/context for all later work.
	return stableContext.Err() != nil && page.GetContext().Err() == nil
}

func logSearchPhase(page *rod.Page, phase string, started time.Time) {
	fields := logrus.Fields{
		"phase":        phase,
		"elapsed_ms":   time.Since(started).Milliseconds(),
		"context_done": page.GetContext().Err() != nil,
	}
	diagnosticContext, cancel := context.WithTimeout(page.GetContext(), time.Second)
	defer cancel()
	result, err := page.Context(diagnosticContext).Eval(`() => {
		const state = window.__INITIAL_STATE__;
		const search = state && state.search;
		const feeds = search && search.feeds;
		return JSON.stringify({
			pathname: location.pathname.slice(0, 256),
			readyState: document.readyState,
			initialState: state !== undefined,
			search: search != null,
			feedsArray: Array.isArray(feeds),
			feedsLength: Array.isArray(feeds) ? feeds.length : 0,
			valueArray: Array.isArray(feeds && feeds.value),
			valueLength: Array.isArray(feeds && feeds.value) ? feeds.value.length : 0,
			rawValueArray: Array.isArray(feeds && feeds._value),
			rawValueLength: Array.isArray(feeds && feeds._value) ? feeds._value.length : 0,
			loginPanelPresent: document.querySelector('.login-container') !== null,
			captchaElementPresent: document.querySelector('[class*="captcha"], [id*="captcha"], iframe[src*="captcha"]') !== null
		});
	}`)
	if err == nil {
		// Decode into a fixed schema instead of logging arbitrary page-returned
		// objects. Query strings, DOM text, credentials and images are excluded.
		var state struct {
			Pathname              string `json:"pathname"`
			ReadyState            string `json:"readyState"`
			InitialState          bool   `json:"initialState"`
			Search                bool   `json:"search"`
			FeedsArray            bool   `json:"feedsArray"`
			FeedsLength           int    `json:"feedsLength"`
			ValueArray            bool   `json:"valueArray"`
			ValueLength           int    `json:"valueLength"`
			RawValueArray         bool   `json:"rawValueArray"`
			RawValueLength        int    `json:"rawValueLength"`
			LoginPanelPresent     bool   `json:"loginPanelPresent"`
			CaptchaElementPresent bool   `json:"captchaElementPresent"`
		}
		if json.Unmarshal([]byte(result.Value.Str()), &state) == nil {
			switch state.Pathname {
			case "/", "/explore", "/search_result", "/search_result_ai", "/login":
			default:
				state.Pathname = "[other]"
			}
			switch state.ReadyState {
			case "loading", "interactive", "complete":
			default:
				state.ReadyState = "unknown"
			}
			summary, _ := json.Marshal(state)
			fields["page_state"] = string(summary)
		}
	}
	logrus.WithFields(fields).Info("search phase completed")
}

func (s *SearchAction) Search(ctx context.Context, keyword string, filters ...FilterOption) ([]Feed, error) {
	// 先校验筛选取值，必须在导航之前——写错的值不该先向平台发一次请求再报错。
	pending, err := collectFilters(filters)
	if err != nil {
		return nil, err
	}

	// 注意 .Context(ctx) 会替换掉 NewSearchAction 里设的 60s deadline，必须在其后重新 Timeout，
	// 否则搜索页不 stable 时 MustWaitStable/MustWait 会永久挂起（无 deadline 可依赖）。
	page := s.page.Context(ctx).Timeout(60 * time.Second)
	phase := "navigate"
	phaseStarted := time.Now()
	advancePhase := func(next string) {
		logSearchPhase(page, phase, phaseStarted)
		phase = next
		phaseStarted = time.Now()
	}
	defer func() { logSearchPhase(page, phase, phaseStarted) }()

	searchURL := makeSearchURL(keyword)
	page.MustNavigate(searchURL)
	advancePhase("first_screen_stability")
	stabilityLimited := waitSearchFirstScreenStable(page)
	if stabilityLimited {
		logrus.WithField("phase", phase).Info("search first-screen stability reached its short deadline")
	}
	advancePhase("initial_state")
	page.MustWait(`() => window.__INITIAL_STATE__ !== undefined`)
	if stabilityLimited && len(pending) == 0 {
		// An unstable shell can expose its state before the first search results
		// arrive. Use the remaining parent budget instead of reporting it empty.
		// Stable empty results and the existing filter-refresh path keep their
		// original behavior.
		advancePhase("initial_feeds")
		page.MustWait(`() => {
			const feeds = window.__INITIAL_STATE__?.search?.feeds;
			const data = feeds && (feeds.value !== undefined ? feeds.value : feeds._value);
			return Array.isArray(data) && data.length > 0;
		}`)
	}
	advancePhase("filters")
	humanize.Delay(ctx, humanize.AfterNavigate)

	if len(pending) > 0 {
		// 悬停在筛选按钮上展开面板
		filterButton := page.MustElement(`div.filter`)
		if err := humanize.Hover(filterButton); err != nil {
			return nil, fmt.Errorf("悬停筛选按钮失败: %w", err)
		}
		humanize.Delay(ctx, humanize.BeforeClick)

		// 等待筛选面板出现
		page.MustWait(`() => document.querySelector('div.filter-panel') !== null`)

		// 记下筛选前的结果，用来判断筛选后的数据什么时候到位
		before := readFeedIDs(page)

		// 指针先进面板，之后的移动都限制在面板内。
		// 从按钮直接拉到选项的话，路径会有一段既不在按钮上、也还没进面板。
		bounds, err := panelBounds(page)
		if err != nil {
			return nil, err
		}
		if err := humanize.MoveInto(page, bounds); err != nil {
			return nil, fmt.Errorf("移入筛选面板失败: %w", err)
		}

		// 用 ClickNoWait：筛选面板是 hover 浮层，rod 的 WaitInteractable 会误判被遮挡而死等。
		for _, pf := range pending {
			option, err := findFilterOption(page, pf)
			if err != nil {
				return nil, err
			}
			// 每轮重取：点完一项后面板可能重排。
			if b, err := panelBounds(page); err == nil {
				bounds = b
			}
			humanize.Delay(ctx, humanize.BeforeClick)
			if err := humanize.ClickNoWaitInside(option, bounds); err != nil {
				return nil, fmt.Errorf("点击筛选选项「%s」失败: %w", pf.option, err)
			}
		}

		waitFeedsChanged(page, before, 15*time.Second)
	}

	advancePhase("read_feeds")
	result := page.MustEval(`() => {
		if (window.__INITIAL_STATE__ &&
		    window.__INITIAL_STATE__.search &&
		    window.__INITIAL_STATE__.search.feeds) {
			const feeds = window.__INITIAL_STATE__.search.feeds;
			const feedsData = feeds.value !== undefined ? feeds.value : feeds._value;
			if (feedsData) {
				return JSON.stringify(feedsData);
			}
		}
		return "";
	}`).String()

	if result == "" {
		return nil, errors.ErrNoFeeds
	}

	var feeds []Feed
	if err := json.Unmarshal([]byte(result), &feeds); err != nil {
		return nil, fmt.Errorf("failed to unmarshal feeds: %w", err)
	}

	return onlyNotes(feeds), nil
}

// feedIDsJS 读当前结果集的 id 列表，用来判断数据有没有换一批。
const feedIDsJS = `() => {
	const f = window.__INITIAL_STATE__?.search?.feeds;
	const v = f ? (f.value !== undefined ? f.value : f._value) : null;
	return v ? v.map(x => x.id).join(",") : "";
}`

func readFeedIDs(page *rod.Page) string {
	res, err := page.Eval(feedIDsJS)
	if err != nil {
		return ""
	}
	return res.Value.Str()
}

// waitFeedsChanged 等筛选后的数据到位。
//
// 点完筛选项之后不能立刻读结果：站点是先把 feeds 清空、再灌入新数据，
// 中间这段时间读到的要么是空，要么还是筛选前那一批。原先用
// MustWait(__INITIAL_STATE__ !== undefined) 等，而这个条件从首屏起就为真、
// 立即返回，等于没等——多个筛选项一起用时表现为只有一部分生效。
//
// 超时不报错：筛选已经点上了，宁可返回可能偏旧的数据，也不要整个搜索失败。
func waitFeedsChanged(page *rod.Page, before string, timeout time.Duration) {
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if now := readFeedIDs(page); now != "" && now != before {
			return
		}
		time.Sleep(300 * time.Millisecond)
	}
	logrus.Warnf("筛选后等待结果刷新超时（%s），返回的可能是筛选前的数据", timeout)
}

// panelBounds 取筛选面板当前占据的矩形。
func panelBounds(page *rod.Page) (humanize.Rect, error) {
	panel, err := page.Element("div.filter-panel")
	if err != nil {
		return humanize.Rect{}, fmt.Errorf("读取筛选面板失败: %w", err)
	}
	shape, err := panel.Shape()
	if err != nil {
		return humanize.Rect{}, fmt.Errorf("读取筛选面板失败: %w", err)
	}
	if len(shape.Quads) == 0 {
		return humanize.Rect{}, fmt.Errorf("筛选面板没有可用区域")
	}
	q := shape.Quads[0]
	return humanize.Rect{Left: q[0], Top: q[1], Right: q[4], Bottom: q[5]}, nil
}

// findFilterOption 在筛选面板里定位一个选项：按标签找到组，再在组内按文本找选项。
//
// 全程不用序号。同一个选项在面板里可能渲染成多个 div.tags（数量随视口而变，
// 且首项是否重复各组不一致），下标对不齐；早前用 div.tags:nth-child(N) 会选错项。
// 同一个文本可能对应多个节点，只取其中可见的那个。
//
// 作用域必须限定在 div.filter-panel 内且只认 div.tags：页面别处存在同文本的
// 可见元素（顶部频道栏的「图文」「视频」、标签「综合」），放宽会点错地方。
func findFilterOption(page *rod.Page, pf pendingFilter) (*rod.Element, error) {
	groups, err := page.Elements("div.filter-panel div.filters")
	if err != nil {
		return nil, fmt.Errorf("读取筛选面板失败: %w", err)
	}

	for _, group := range groups {
		// 组标签是 div.filters 下的直接子 span
		label, err := group.Element(":scope > span")
		if err != nil {
			continue
		}
		text, err := label.Text()
		if err != nil || strings.TrimSpace(text) != pf.group {
			continue
		}

		options, err := group.Elements("div.tags")
		if err != nil {
			return nil, fmt.Errorf("读取「%s」的选项失败: %w", pf.group, err)
		}

		var available []string
		matched := false
		for _, opt := range options {
			t, err := opt.Text()
			if err != nil {
				continue
			}
			t = strings.TrimSpace(t)
			available = append(available, t)
			if t != pf.option {
				continue
			}

			matched = true
			if humanize.Visible(opt) {
				return opt, nil
			}
		}
		if matched {
			return nil, fmt.Errorf("「%s」里的选项「%s」当前不可见", pf.group, pf.option)
		}
		return nil, fmt.Errorf("「%s」里没有选项「%s」，页面上是：%s",
			pf.group, pf.option, strings.Join(available, "、"))
	}

	return nil, fmt.Errorf("筛选面板里没有「%s」这一组", pf.group)
}

func makeSearchURL(keyword string) string {

	values := url.Values{}
	values.Set("keyword", keyword)
	values.Set("source", "web_explore_feed")

	//https://www.xiaohongshu.com/search_result?keyword=%25E7%258E%258B%25E5%25AD%2590&source=web_search_result_notes
	//https://www.xiaohongshu.com/search_result?keyword=%25E7%258E%258B%25E5%25AD%2590&source=web_explore_feed
	return fmt.Sprintf("https://www.xiaohongshu.com/search_result?%s", values.Encode())
}
