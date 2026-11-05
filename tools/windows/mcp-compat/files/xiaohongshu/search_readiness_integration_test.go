//go:build integration

package xiaohongshu

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"runtime"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/go-rod/rod"
	"github.com/go-rod/rod/lib/launcher"
	"github.com/go-rod/rod/lib/proto"
	"github.com/sirupsen/logrus"
	appbrowser "github.com/xpzouying/xiaohongshu-mcp/browser"
	"github.com/xpzouying/xiaohongshu-mcp/humanize"
)

// Run explicitly with an installed standard Chromium. Linux requires a non-root
// user. The fixture uses about:blank and needs no network or real account.
//
//	POC_STANDARD_CHROME_BIN=/usr/bin/chromium go test -tags=integration \
//	  ./xiaohongshu -run '^TestSearchReadinessWithContinuouslyChangingDOM$' -timeout=45s
func TestSearchReadinessWithContinuouslyChangingDOM(t *testing.T) {
	page := newSearchIntegrationPage(t)
	page.MustNavigate("about:blank")
	page.MustSetDocumentContent(`<!doctype html>
<html><head><meta charset="utf-8"><title>Search readiness fixture</title></head>
<body><div id="pulse">tick-0</div><script>
window.__INITIAL_STATE__ = {
  search: {feeds: {value: [{id: "fixture-note", modelType: "note"}]}}
};
window.__pulseTicks = 0;
setInterval(() => {
  document.getElementById("pulse").textContent = "tick-" + (++window.__pulseTicks);
}, 25);
</script></body></html>`)
	page.Timeout(3 * time.Second).MustWait(`() => window.__pulseTicks >= 3`)

	// MustWaitStable uses this same one-second stable interval. The changing
	// DOM consumes its entire page budget even though feeds were ready already.
	// Rod can return nil here when WaitLoad wins its sync.Once error race, so
	// the exhausted context is the reliable regression signal.
	oldContext, cancelOld := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancelOld()
	_ = page.Context(oldContext).WaitStable(time.Second)
	if !errors.Is(oldContext.Err(), context.DeadlineExceeded) {
		t.Fatal("fixture unexpectedly stabilized before the old page budget expired")
	}

	parentContext, cancelParent := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancelParent()
	searchPage := page.Context(parentContext)
	started := time.Now()
	waitSearchFirstScreenStable(searchPage)
	elapsed := time.Since(started)
	if err := parentContext.Err(); err != nil {
		t.Fatalf("first-screen stability wait exhausted the parent search context: %v", err)
	}
	if elapsed > 8*time.Second {
		t.Fatalf("first-screen wait exceeded its short budget: %s", elapsed)
	}

	// Exercise the same state readiness condition used immediately after the
	// helper, and decode the fixture with the real Feed representation.
	if err := searchPage.Wait(rod.Eval(`() => window.__INITIAL_STATE__ !== undefined`)); err != nil {
		t.Fatalf("ready search state became unreadable: %v", err)
	}
	result, err := searchPage.Eval(`() => JSON.stringify(window.__INITIAL_STATE__.search.feeds.value)`)
	if err != nil {
		t.Fatalf("could not read feeds after bounded stability wait: %v", err)
	}
	var feeds []Feed
	if err := json.Unmarshal([]byte(result.Value.Str()), &feeds); err != nil {
		t.Fatalf("could not decode ready feeds: %v", err)
	}
	if len(feeds) != 1 || feeds[0].ID != "fixture-note" || feeds[0].ModelType != modelTypeNote {
		t.Fatal("bounded wait did not preserve the ready search data")
	}
}

func TestSearchPhaseDiagnosticsExcludePrivatePageData(t *testing.T) {
	page := newSearchIntegrationPage(t)
	page.MustNavigate("about:blank?fixture_secret=query-private-marker#fragment-private-marker")
	page.MustSetDocumentContent(`<!doctype html>
<html><head><meta charset="utf-8"><title>title-private-marker</title></head>
<body><div class="login-container">body-private-marker</div>
<div id="captcha-fixture">captcha-private-marker</div><script>
window.__INITIAL_STATE__ = {
  secret: "state-private-marker",
  search: {secret: "search-private-marker", feeds: {
    value: [{id: "note-private-marker", secret: "value-private-marker"}],
    _value: [{secret: "raw-private-marker"}, {secret: "another-private-marker"}]
  }}
};
</script></body></html>`)
	page.Timeout(3 * time.Second).MustWait(`() => document.readyState === "complete"`)

	var output bytes.Buffer
	logger := logrus.StandardLogger()
	previousOutput, previousFormatter, previousLevel := logger.Out, logger.Formatter, logger.GetLevel()
	logger.SetOutput(&output)
	logger.SetFormatter(&logrus.JSONFormatter{DisableTimestamp: true})
	logger.SetLevel(logrus.InfoLevel)
	defer func() {
		logger.SetOutput(previousOutput)
		logger.SetFormatter(previousFormatter)
		logger.SetLevel(previousLevel)
	}()

	logSearchPhase(page, "read_feeds", time.Now())
	if strings.Contains(output.String(), "private-marker") || strings.Contains(output.String(), "fixture_secret") {
		t.Fatal("search diagnostics exposed private query, fragment, body or state data")
	}
	var entry map[string]any
	if err := json.Unmarshal(output.Bytes(), &entry); err != nil {
		t.Fatalf("diagnostic was not one JSON log entry: %v", err)
	}
	if len(entry) != 6 || entry["phase"] != "read_feeds" || entry["context_done"] != false || entry["msg"] != "search phase completed" {
		t.Fatal("diagnostic did not retain its fixed log fields")
	}
	summary, ok := entry["page_state"].(string)
	if !ok {
		t.Fatal("diagnostic page summary was missing")
	}
	var state map[string]any
	if err := json.Unmarshal([]byte(summary), &state); err != nil {
		t.Fatalf("diagnostic page summary was invalid: %v", err)
	}
	expected := map[string]any{
		"pathname": "[other]", "readyState": "complete", "initialState": true,
		"search": true, "feedsArray": false, "feedsLength": float64(0),
		"valueArray": true, "valueLength": float64(1),
		"rawValueArray": true, "rawValueLength": float64(2),
		"loginPanelPresent": true, "captchaElementPresent": true,
	}
	if !reflect.DeepEqual(state, expected) {
		t.Fatal("diagnostic summary did not match the allowed readiness and array metrics")
	}

	// The inspected page controls JavaScript globals. Even a forged response
	// with allowed field names must not inject private strings into the log.
	page.MustEval(`() => {
		JSON.stringify = () => '{"pathname":"/forged-private-marker","readyState":"forged-private-marker","initialState":true,"search":true,"feedsArray":false,"feedsLength":0,"valueArray":true,"valueLength":1,"rawValueArray":true,"rawValueLength":2,"loginPanelPresent":true,"captchaElementPresent":true,"extra":"forged-private-marker"}';
	}`)
	output.Reset()
	logSearchPhase(page, "read_feeds", time.Now())
	if strings.Contains(output.String(), "private-marker") {
		t.Fatal("page-controlled diagnostic strings escaped the fixed value allowlist")
	}
	if err := json.Unmarshal(output.Bytes(), &entry); err != nil {
		t.Fatalf("forged page response broke the diagnostic entry: %v", err)
	}
	summary, ok = entry["page_state"].(string)
	if !ok {
		t.Fatal("allowlisted summary of the forged page response was missing")
	}
	state = nil
	if err := json.Unmarshal([]byte(summary), &state); err != nil {
		t.Fatalf("forged page response broke the summary: %v", err)
	}
	expected["readyState"] = "unknown"
	if !reflect.DeepEqual(state, expected) {
		t.Fatal("page-controlled strings or extra fields bypassed the diagnostic allowlist")
	}
}

func TestSearchWaitsForDelayedFeedsAfterLimitedStability(t *testing.T) {
	useSearchFixtureTiming(t)
	for _, valueKey := range []string{"value", "_value"} {
		t.Run(valueKey, func(t *testing.T) {
			page := newSearchIntegrationPage(t)
			interceptSearchFixture(t, page, fmt.Sprintf(`<!doctype html>
<html><head><meta charset="utf-8"><title>Delayed search fixture</title></head>
<body><div id="pulse">tick-0</div><img src="https://fixture.invalid/blocked.png">
<script>
const valueKey = %q;
window.__INITIAL_STATE__ = {search: {feeds: {[valueKey]: []}}};
let ticks = 0;
setInterval(() => {
  document.getElementById("pulse").textContent = "tick-" + (++ticks);
}, 25);
setTimeout(() => {
  window.__INITIAL_STATE__.search.feeds[valueKey] = [
    {id: "delayed-fixture-note", modelType: "note"}
  ];
}, 6500);
</script></body></html>`, valueKey))
			ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
			defer cancel()
			started := time.Now()
			feeds, err := NewSearchAction(page).Search(ctx, "fixture")
			if err != nil {
				t.Fatalf("search failed before delayed feeds became ready: %v", err)
			}
			if len(feeds) != 1 || feeds[0].ID != "delayed-fixture-note" {
				t.Fatal("search returned before the delayed note reached its supported feeds array")
			}
			if elapsed := time.Since(started); elapsed < 6*time.Second || elapsed > 15*time.Second {
				t.Fatalf("delayed feeds did not complete within the expected parent budget: %s", elapsed)
			}
			if ctx.Err() != nil {
				t.Fatal("delayed search exhausted its parent context")
			}
		})
	}
}

func TestSearchPreservesStableEmptyFeeds(t *testing.T) {
	useSearchFixtureTiming(t)
	for _, valueKey := range []string{"value", "_value"} {
		t.Run(valueKey, func(t *testing.T) {
			page := newSearchIntegrationPage(t)
			interceptSearchFixture(t, page, fmt.Sprintf(`<!doctype html>
<html><head><meta charset="utf-8"><title>Empty search fixture</title></head>
<body><img src="https://fixture.invalid/blocked.png"><script>
window.__INITIAL_STATE__ = {search: {feeds: {%q: []}}};
</script></body></html>`, valueKey))
			ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
			defer cancel()
			started := time.Now()
			feeds, err := NewSearchAction(page).Search(ctx, "fixture")
			if err != nil {
				t.Fatalf("stable empty search result failed: %v", err)
			}
			if len(feeds) != 0 {
				t.Fatal("stable empty search produced unexpected notes")
			}
			if elapsed := time.Since(started); elapsed > 8*time.Second {
				t.Fatalf("stable empty result waited for a nonempty array: %s", elapsed)
			}
			if ctx.Err() != nil {
				t.Fatal("stable empty search exhausted its parent context")
			}
		})
	}
}

// Fixed navigation timing keeps the delayed-data regression independent of the
// production random delay, which could otherwise conceal an early empty read.
type searchFixtureTimingProvider struct{}

func (searchFixtureTimingProvider) Timing() humanize.TimingProfile {
	profile := (humanize.DefaultProvider{}).Timing()
	profile[humanize.AfterNavigate] = humanize.LogNormal{Min: 10 * time.Millisecond, Max: 10 * time.Millisecond}
	return profile
}

func useSearchFixtureTiming(t *testing.T) {
	t.Helper()
	humanize.SetProvider(searchFixtureTimingProvider{})
	t.Cleanup(func() { humanize.SetProvider(humanize.DefaultProvider{}) })
}

func interceptSearchFixture(t *testing.T, page *rod.Page, html string) {
	t.Helper()
	var navigations, blockedRequests, interceptionErrors atomic.Int32
	router := page.HijackRequests()
	err := router.Add("*", "", func(hijack *rod.Hijack) {
		hijack.OnError = func(error) { interceptionErrors.Add(1) }
		url := hijack.Request.URL()
		if hijack.Request.IsNavigation() && url != nil && url.Scheme == "https" &&
			url.Host == "www.xiaohongshu.com" && url.Path == "/search_result" && url.Query().Get("keyword") == "fixture" {
			navigations.Add(1)
			hijack.Response.SetHeader("Content-Type", "text/html; charset=utf-8").SetBody(html)
			return
		}
		blockedRequests.Add(1)
		hijack.Response.Fail(proto.NetworkErrorReasonAborted)
	})
	if err != nil {
		t.Fatal("could not install the local search fixture interceptor")
	}
	go router.Run()
	t.Cleanup(func() {
		if err := router.Stop(); err != nil {
			t.Error("could not stop the local search fixture interceptor")
		}
		if navigations.Load() != 1 || blockedRequests.Load() == 0 || interceptionErrors.Load() != 0 {
			t.Error("search did not use exactly one local navigation and block all fixture subresource requests")
		}
	})
}

func newSearchIntegrationPage(t *testing.T) *rod.Page {
	t.Helper()
	binPath, err := appbrowser.StandardChromeBinFromEnv()
	if err != nil {
		t.Fatal(err)
	}
	if binPath == "" {
		t.Skip("set POC_STANDARD_CHROME_BIN to an installed standard Chromium")
	}
	if runtime.GOOS == "linux" && os.Geteuid() == 0 {
		t.Fatal("run this browser integration test as a non-root user with the sandbox enabled")
	}

	// Match the production standard-browser launcher policy, but keep its
	// handle here so fixture cleanup cannot wait forever on a Windows process.
	// This profile is created solely for the fixture and contains no cookies.
	launchContext, cancelLaunch := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancelLaunch()
	fixtureLauncher := launcher.New().Context(launchContext).
		Bin(binPath).
		Headless(true).
		NoSandbox(false).
		Leakless(false).
		UserDataDir(filepath.Join(t.TempDir(), "chrome-profile")).
		RemoteDebuggingPort(0).
		Set("remote-debugging-address", "127.0.0.1").
		Delete("disable-site-isolation-trials").
		Delete("disable-features").
		Delete("disable-client-side-phishing-detection").
		Delete("disable-ipc-flooding-protection").
		Delete("enable-features")
	var browser *rod.Browser
	browserContext, cancelBrowser := context.WithTimeout(context.Background(), 30*time.Second)
	t.Cleanup(func() {
		defer cancelBrowser()
		if browser != nil {
			closeContext, cancelClose := context.WithTimeout(context.Background(), 2*time.Second)
			_ = browser.Context(closeContext).Close()
			cancelClose()
		}
		if fixtureLauncher.PID() == 0 {
			return
		}
		cleaned := make(chan struct{})
		go func() {
			fixtureLauncher.Cleanup()
			close(cleaned)
		}()
		select {
		case <-cleaned:
			return
		case <-time.After(2 * time.Second):
			// Kill only the process started by this launcher for this profile.
			fixtureLauncher.Kill()
		}
		select {
		case <-cleaned:
		case <-time.After(5 * time.Second):
			t.Error("owned fixture browser did not finish cleanup after termination")
		}
	})
	controlURL, err := fixtureLauncher.Launch()
	if err != nil {
		t.Fatal("could not launch the isolated standard browser fixture")
	}
	if fixtureLauncher.PID() == 0 {
		t.Fatal("fixture launcher did not create its own browser process")
	}
	fixtureBrowser := rod.New().Context(browserContext).ControlURL(controlURL).Trace(false)
	if err := fixtureBrowser.Connect(); err != nil {
		t.Fatal("could not connect to the isolated standard browser fixture")
	}
	browser = fixtureBrowser
	page, err := browser.Page(proto.TargetCreateTarget{})
	if err != nil {
		t.Fatal("could not create the isolated fixture page")
	}
	return page
}
