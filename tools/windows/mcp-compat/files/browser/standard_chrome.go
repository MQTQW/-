package browser

import (
	"fmt"
	"os"
	"path/filepath"
)

// StandardChromeBinFromEnv validates an explicitly chosen local browser. An empty
// value keeps the upstream bundled mode; an invalid value is never a fallback.
// This does not import an existing browser profile or read any browser cookies.
func StandardChromeBinFromEnv() (string, error) {
	path := os.Getenv("POC_STANDARD_CHROME_BIN")
	if path == "" {
		return "", nil
	}
	if !filepath.IsAbs(path) {
		return "", fmt.Errorf("POC_STANDARD_CHROME_BIN must be an absolute file path")
	}
	info, err := os.Stat(path)
	if err != nil {
		return "", fmt.Errorf("POC_STANDARD_CHROME_BIN cannot be accessed: %w", err)
	}
	if !info.Mode().IsRegular() {
		return "", fmt.Errorf("POC_STANDARD_CHROME_BIN must point to a regular file")
	}
	return path, nil
}
