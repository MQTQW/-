package browser

import (
	"os"
	"path/filepath"
	"testing"
)

func TestStandardChromeBinRequiresExplicitExistingRegularFile(t *testing.T) {
	dir := t.TempDir()
	file := filepath.Join(dir, "test-browser.exe")
	if err := os.WriteFile(file, []byte("configuration-only fixture"), 0600); err != nil {
		t.Fatal(err)
	}
	for _, test := range []struct {
		name      string
		path      string
		wantError bool
	}{
		{"default mode", "", false},
		{"relative path", "test-browser.exe", true},
		{"missing file", filepath.Join(dir, "missing.exe"), true},
		{"directory", dir, true},
		{"explicit existing file", file, false},
	} {
		t.Run(test.name, func(t *testing.T) {
			t.Setenv("POC_STANDARD_CHROME_BIN", test.path)
			got, err := StandardChromeBinFromEnv()
			if (err != nil) != test.wantError {
				t.Fatalf("unexpected validation result: %v", err)
			}
			if err == nil && got != test.path {
				t.Fatalf("browser path silently changed: %q", got)
			}
		})
	}
}
