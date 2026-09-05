package engine

import (
	"strings"
	"testing"

	"github.com/EchoPing07/better-esurfing-for-android/core/portal"
)

// engine_internal_test.go — 引擎内部单元测试（UA 回退链状态机、ZSM 解析）。
// 端到端行为见 engine_test.go（package engine_test）。

func newTestEngine() *Engine { return NewEngine(nil, nil) }

// TestResolveUAChain 配置 → 回退链的解析规则。
func TestResolveUAChain(t *testing.T) {
	defaultChain := strings.Join(portal.DefaultUAChain, ",")
	tests := []struct {
		name      string
		ua        string
		preferred string
		want      string
	}{
		{"空 = 自动链", "", "", defaultChain},
		{"auto 大小写与空白归一", "  AuTo  ", "", defaultChain},
		{"固定预设走单元素链不回退", portal.UAPCLinux64, "", portal.UAPCLinux64},
		{"preferredUA 置自动链链首", "", portal.UAAndroid2089,
			strings.Join([]string{portal.UAAndroid2089, portal.UAAndroid2104, portal.UAAndroid2093}, ",")},
		{"preferredUA 不影响固定链", portal.UAAndroid2104, portal.UAAndroid2089, portal.UAAndroid2104},
		{"含空白回退自动链", "CCTP/bad ua", "", defaultChain},
		{"超长（>64）回退自动链", strings.Repeat("x", 65), "", defaultChain},
		{"控制字符回退自动链", "CCTP/x\x07y", "", defaultChain},
		{"恰好 64 字符合法", strings.Repeat("a", 64), "", strings.Repeat("a", 64)},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			e := newTestEngine()
			e.curAcc = &Account{Username: "u", UserAgent: tt.ua}
			e.preferredUA = tt.preferred
			e.resolveUAChain()
			if got := strings.Join(e.uaChain, ","); got != tt.want {
				t.Fatalf("chain = %q, want %q", got, tt.want)
			}
		})
	}
}

// TestCurrentUABounds 游标越界与空链兜底。
func TestCurrentUABounds(t *testing.T) {
	e := newTestEngine()
	if got := e.currentUA(); got != portal.DefaultUAChain[0] {
		t.Fatalf("empty chain fallback = %q, want %q", got, portal.DefaultUAChain[0])
	}
	e.uaChain = []string{"A", "B"}
	e.uaIdx = 5 // 链耗尽后的越界态
	e.preferredUA = "P"
	if got := e.currentUA(); got != "P" {
		t.Fatalf("out-of-range fallback = %q, want preferredUA", got)
	}
	e.uaIdx = 1
	if got := e.currentUA(); got != "B" {
		t.Fatalf("in-range = %q, want B", got)
	}
}

// TestResolveUAChainWarnOnce 非法配置告警按值去重（巡检周期内不刷屏）。
func TestResolveUAChainWarnOnce(t *testing.T) {
	e := newTestEngine()
	e.curAcc = &Account{Username: "u", UserAgent: "bad ua"}
	for i := 0; i < 3; i++ {
		e.resolveUAChain()
	}
	if n := strings.Count(e.DumpLogs(), "无效的 UA 配置"); n != 1 {
		t.Fatalf("same invalid UA must warn once, got %d", n)
	}
	e.curAcc.UserAgent = "also bad"
	e.resolveUAChain()
	if n := strings.Count(e.DumpLogs(), "无效的 UA 配置"); n != 2 {
		t.Fatalf("different invalid UA must warn again, got %d", n)
	}
	e.curAcc.UserAgent = portal.UAAndroid2104
	e.resolveUAChain()
	e.curAcc.UserAgent = "bad ua"
	e.resolveUAChain()
	if n := strings.Count(e.DumpLogs(), "无效的 UA 配置"); n != 3 {
		t.Fatalf("valid UA must reset dedup so repeat offender warns again, got %d", n)
	}
}

// TestParseZSMAlgoID ZSM 二进制解析与 GUID 形态校验（服务端可控字节防注入）。
func TestParseZSMAlgoID(t *testing.T) {
	goodGUID := "CAFBCBAD-B6E7-4CAB-8A67-14D39F00CE1E"
	mkZSM := func(guid string) []byte {
		b := []byte{0x01, 0x02, 0x03, 16}
		for i := 0; i < 16; i++ {
			b = append(b, byte('A'+i%26))
		}
		b = append(b, '$')
		b = append(b, guid...)
		b = append(b, '\'')
		return append(b, []byte("EXTRA1234")...)
	}
	if got, err := parseZSMAlgoID(mkZSM(goodGUID)); err != nil || got != goodGUID {
		t.Fatalf("valid zsm: got %q err %v", got, err)
	}
	if got, err := parseZSMAlgoID(mkZSM(zeroAlgoID)); err != nil || got != zeroAlgoID {
		t.Fatalf("zero uuid: got %q err %v", got, err)
	}
	bad := map[string][]byte{
		"空":       nil,
		"过短":      []byte{0x01, 0x02},
		"key 截断":  []byte{0x01, 0x02, 0x03, 16, 'A', '$'},
		"GUID 截断": append(append([]byte{0, 0, 0, 0}, '$'), "CAFBCBAD-B6E7-4CAB-8A67-14D39F00CE1"...),
		"换行注入":    mkZSM(goodGUID[:30] + "\n12\n34"),
		"非十六进制":   mkZSM("zzzzzzzz-zzzz-zzzz-zzzz-zzzzzzzzzzzz"),
		"分组错位":    mkZSM("CAFBCBAD-B6E7-4CAB-8A671-4D39F00CE1E"),
	}
	for name, zsm := range bad {
		if got, err := parseZSMAlgoID(zsm); err == nil {
			t.Fatalf("%s: expected error, got %q", name, got)
		}
	}
}
