package portal_test

import (
	"strings"
	"testing"

	"github.com/EchoPing07/better-esurfing-for-android/core/portal"
)

// TestValidUserAgent 自定义 UA 校验边界：HTTP 头与 XML 报文体双用途，
// 空白/控制字符会破坏报文或注入头。
func TestValidUserAgent(t *testing.T) {
	valid := []string{
		"CCTP/android11_64/2104",
		"CCTP/WinSVR5/1068",
		strings.Repeat("a", 64), // 恰好 64
	}
	invalid := []string{
		"",                      // 空
		strings.Repeat("a", 65), // 超长
		"has space",
		"tab\there",
		"nl\ninjection",
		"ctrl\x07char",
		"del\x7fchar",
	}
	for _, ua := range valid {
		if !portal.ValidUserAgent(ua) {
			t.Fatalf("ValidUserAgent(%q) = false, want true", ua)
		}
	}
	for _, ua := range invalid {
		if portal.ValidUserAgent(ua) {
			t.Fatalf("ValidUserAgent(%q) = true, want false", ua)
		}
	}
}
