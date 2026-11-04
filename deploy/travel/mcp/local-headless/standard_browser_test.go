package headless_browser

import (
	"strings"
	"testing"

	"github.com/go-rod/rod/lib/launcher/flags"
)

func TestStandardBrowserPolicyCannotBeOverriddenByLaterOptions(t *testing.T) {
	cfg := newConfig(
		WithStandardBrowser(),
		WithChromeBinPath("explicit-browser"),
		WithUserAgent("pretend-browser"),
		WithFingerprint("windows"),
		WithFingerprintSeed(12345),
		WithStealthJS(true),
		WithLanguage("zh-CN"),
		WithExtraFlags(map[string]string{"no-sandbox": "", "remote-allow-origins": "*"}),
	)
	if cfg.Fingerprint || cfg.FingerprintSeed != 0 || cfg.FingerprintPlatform != "" ||
		cfg.UserAgent != "" || cfg.StealthJS || cfg.Language != "" || len(cfg.ExtraFlags) != 0 {
		t.Fatal("standard browser policy allowed an identity override or arbitrary flags")
	}
	l := newLauncher(cfg)
	for _, flag := range []flags.Flag{
		flags.NoSandbox, flags.Leakless, "disable-site-isolation-trials", "disable-features",
		"disable-client-side-phishing-detection", "disable-ipc-flooding-protection",
		"enable-features", "user-agent", "fingerprint", "fingerprint-platform", "remote-allow-origins",
	} {
		if l.Has(flag) {
			t.Errorf("standard browser must not set %s", flag)
		}
	}
	if !l.Has("enable-automation") {
		t.Error("standard mode must retain Chrome's automation disclosure")
	}
}

func TestStandardBrowserUsesExplicitBinaryAndIsolatedLoopbackDebugging(t *testing.T) {
	cfg := newConfig(WithChromeBinPath("explicit-browser"), WithStandardBrowser())
	first := newLauncher(cfg)
	second := newLauncher(cfg)
	if first.Get(flags.Bin) != "explicit-browser" || first.Get("remote-debugging-address") != "127.0.0.1" ||
		first.Get(flags.RemoteDebuggingPort) != "0" {
		t.Fatal("standard browser must use the supplied binary and a random loopback debug port")
	}
	firstDir, secondDir := first.Get(flags.UserDataDir), second.Get(flags.UserDataDir)
	if firstDir == "" || firstDir == secondDir || !strings.Contains(firstDir, "user-data") {
		t.Fatal("standard browser must use a fresh independent profile for each launch")
	}
}

func TestBundledBrowserLauncherPolicyRemainsUnchanged(t *testing.T) {
	cfg := newConfig(WithFingerprint("windows"), WithStealthJS(false))
	l := newLauncher(cfg)
	if !l.Has(flags.NoSandbox) || !l.Has(flags.Leakless) || !l.Has("fingerprint") || cfg.StealthJS {
		t.Fatal("bundled browser behavior changed outside the explicit standard mode")
	}
}
