package engine_test

import (
	"encoding/json"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/EchoPing07/better-esurfing-for-android/core/engine"
	"github.com/EchoPing07/better-esurfing-for-android/core/mockportal"
	"github.com/EchoPing07/better-esurfing-for-android/core/portal"
)

// recorder 收集状态变化与日志（线程安全）。
type recorder struct {
	mu     sync.Mutex
	events []string
	logs   []string
}

func (r *recorder) OnStateChanged(state int32, detail string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.events = append(r.events, time.Now().Format("15:04:05.000")+fmtState(state)+" "+detail)
}

func (r *recorder) OnLog(level int32, message string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.logs = append(r.logs, message)
}

func fmtState(s int32) string {
	switch engine.State(s) {
	case engine.StateIdle:
		return "[Idle]"
	case engine.StateDetecting:
		return "[Detecting]"
	case engine.StateAuthorizing:
		return "[Auth]"
	case engine.StateOnline:
		return "[Online]"
	case engine.StateLoggedOut:
		return "[Out]"
	case engine.StateError:
		return "[Error]"
	}
	return "?"
}

func (r *recorder) snapshot() []string {
	r.mu.Lock()
	defer r.mu.Unlock()
	out := make([]string, len(r.events))
	copy(out, r.events)
	return out
}

func (r *recorder) hasEvent(substr string) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	for _, e := range r.events {
		if strings.Contains(e, substr) {
			return true
		}
	}
	return false
}

func (r *recorder) hasLog(substr string) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	for _, l := range r.logs {
		if strings.Contains(l, substr) {
			return true
		}
	}
	return false
}

func (r *recorder) dump() string {
	return strings.Join(r.snapshot(), "\n") + "\n--- logs ---\n" +
		strings.Join(func() []string {
			r.mu.Lock()
			defer r.mu.Unlock()
			return r.logs
		}(), "\n")
}

// waitFor 轮询直到条件成立或超时。
func waitFor(t *testing.T, timeout time.Duration, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(25 * time.Millisecond)
	}
	t.Fatalf("timeout waiting for: %s", what)
}

// newEngine 构造测试引擎：探针必须指向本地 mock（否则会探测真实互联网）。
func newEngine(t *testing.T, rec *recorder) *engine.Engine {
	t.Helper()
	return engine.NewEngine(&engine.Config{
		ProbeURLsJSON:     probeJSON(t, mockLastURL),
		TimeoutSec:        3,
		DetectIntervalSec: 1,
		ShieldWindowSec:   1,
	}, rec)
}

// mockLastURL 由每个测试在创建 mock 后设置。
var mockLastURL string

func probeJSON(t *testing.T, url string) string {
	t.Helper()
	b, err := json.Marshal([]string{url + "/generate_204"})
	if err != nil {
		t.Fatal(err)
	}
	return string(b)
}

func TestFullAuthFlow(t *testing.T) {
	mock := mockportal.New()
	defer mock.Close()

	mockLastURL = mock.URL()
	rec := &recorder{}
	eng := newEngine(t, rec)
	defer eng.Stop()

	if err := eng.Start(&engine.Account{Username: "20230001", Password: "secret1"}); err != nil {
		t.Fatalf("start: %v", err)
	}
	waitFor(t, 5*time.Second, "online", func() bool {
		s, _ := eng.CurrentState()
		return s == engine.StateOnline
	})

	u, p := mock.LastUser()
	if u != "20230001" || p != "secret1" {
		t.Fatalf("login creds mismatch: %q %q", u, p)
	}
	waitFor(t, 5*time.Second, "heartbeats>=2", func() bool { return mock.Keeps() >= 2 })

	eng.Logout()
	waitFor(t, 5*time.Second, "logged out", func() bool { return mock.Terms() >= 1 })
	s, _ := eng.CurrentState()
	if s != engine.StateDetecting && s != engine.StateLoggedOut {
		t.Fatalf("state after logout = %v", s)
	}
	if !rec.hasEvent("[Online]") {
		t.Fatalf("missing Online event:\n%s", rec.dump())
	}
}

func TestHeartbeatFailureAutoReconnect(t *testing.T) {
	mock := mockportal.New()
	defer mock.Close()

	mockLastURL = mock.URL()
	rec := &recorder{}
	eng := newEngine(t, rec)
	defer eng.Stop()

	if err := eng.Start(&engine.Account{Username: "stu_reconnect", Password: "pw"}); err != nil {
		t.Fatalf("start: %v", err)
	}
	waitFor(t, 5*time.Second, "first online", func() bool {
		s, _ := eng.CurrentState()
		return s == engine.StateOnline
	})
	firstLogins := mock.Logins()

	// 注入心跳故障 → 引擎应掉线回到探测并自动重连
	mock.SetKeepFailing(true)
	waitFor(t, 12*time.Second, "drop to detecting", func() bool {
		s, _ := eng.CurrentState()
		return s == engine.StateDetecting || s == engine.StateError
	})
	mock.SetKeepFailing(false)
	waitFor(t, 12*time.Second, "re-online", func() bool {
		s, _ := eng.CurrentState()
		return s == engine.StateOnline
	})
	if mock.Logins() <= firstLogins {
		t.Fatalf("expected a second login, logins=%d", mock.Logins())
	}
}

func TestSwitchAccount(t *testing.T) {
	mock := mockportal.New()
	defer mock.Close()

	mockLastURL = mock.URL()
	rec := &recorder{}
	eng := newEngine(t, rec)
	defer eng.Stop()

	if err := eng.Start(&engine.Account{Username: "userA", Password: "pwA"}); err != nil {
		t.Fatalf("start A: %v", err)
	}
	waitFor(t, 5*time.Second, "A online", func() bool {
		s, _ := eng.CurrentState()
		return s == engine.StateOnline
	})

	if err := eng.LoginNow(&engine.Account{Username: "userB", Password: "pwB"}); err != nil {
		t.Fatalf("switch B: %v", err)
	}
	waitFor(t, 5*time.Second, "B online", func() bool {
		s, _ := eng.CurrentState()
		return s == engine.StateOnline && mock.Logins() >= 2
	})
	u, _ := mock.LastUser()
	if u != "userB" {
		t.Fatalf("last user = %q, want userB", u)
	}
	if mock.Terms() < 1 {
		t.Fatalf("expected Term before switching, terms=%d", mock.Terms())
	}
}

func TestWrongPasswordThenFix(t *testing.T) {
	mock := mockportal.New()
	defer mock.Close()

	mockLastURL = mock.URL()
	rec := &recorder{}
	eng := newEngine(t, rec)
	defer eng.Stop()

	if err := eng.Start(&engine.Account{Username: "stu_bad", Password: "WRONG"}); err != nil {
		t.Fatalf("start: %v", err)
	}
	waitFor(t, 8*time.Second, "error state", func() bool {
		s, d := eng.CurrentState()
		return s == engine.StateError && strings.Contains(d, "登录失败")
	})

	if err := eng.LoginNow(&engine.Account{Username: "stu_bad", Password: "right"}); err != nil {
		t.Fatalf("retry: %v", err)
	}
	waitFor(t, 8*time.Second, "online after fix", func() bool {
		s, _ := eng.CurrentState()
		return s == engine.StateOnline
	})
}

func TestOpenNetworkNoAuth(t *testing.T) {
	mock := mockportal.New()
	mock.SetRequireAuth(false)
	defer mock.Close()

	mockLastURL = mock.URL()
	rec := &recorder{}
	eng := newEngine(t, rec)
	defer eng.Stop()

	if err := eng.Start(&engine.Account{Username: "stu_open", Password: "pw"}); err != nil {
		t.Fatalf("start: %v", err)
	}
	time.Sleep(2500 * time.Millisecond)
	if s, _ := eng.CurrentState(); s == engine.StateOnline {
		t.Fatalf("should not go online on open network (already authed externally)")
	}
	if mock.Logins() != 0 {
		t.Fatalf("unexpected login attempts: %d", mock.Logins())
	}
	s, d := eng.CurrentState()
	if d == "" || !strings.Contains(d, "无需认证") {
		t.Fatalf("expect idle-connected detail, got %v/%q", s, d)
	}
}

// TestUAFallbackOnZSMReject UA 准入拒绝触发回退：链首 2104 被拒（Error-Code 3 +
// 空体，对齐实测拒绝形态）→ 自动落到 2089 成功；链在成功处截断，2093 不应被尝试。
func TestUAFallbackOnZSMReject(t *testing.T) {
	mock := mockportal.New()
	defer mock.Close()
	mock.SetRejectedUAs(portal.UAAndroid2104, portal.UAAndroid2093)

	mockLastURL = mock.URL()
	rec := &recorder{}
	eng := newEngine(t, rec)
	defer eng.Stop()

	if err := eng.Start(&engine.Account{Username: "stu_ua", Password: "pw"}); err != nil {
		t.Fatalf("start: %v", err)
	}
	waitFor(t, 8*time.Second, "online after UA fallback", func() bool {
		s, _ := eng.CurrentState()
		return s == engine.StateOnline
	})

	uas := mock.HandshakeUAs()
	if len(uas) < 2 || uas[0] != portal.UAAndroid2104 || uas[len(uas)-1] != portal.UAAndroid2089 {
		t.Fatalf("unexpected handshake UA sequence: %v", uas)
	}
	for _, ua := range uas {
		if ua == portal.UAAndroid2093 {
			t.Fatalf("2093 must not be tried after 2089 succeeded: %v", uas)
		}
	}
}

// TestUnknownGUIDTriggersFallback 未知 GUID（第四代兜底场景）触发回退：
// 2104 下发未知 GUID → 换 2089（合法 GUID）成功。
func TestUnknownGUIDTriggersFallback(t *testing.T) {
	mock := mockportal.New()
	defer mock.Close()
	mock.SetAlgoIDForUA(portal.UAAndroid2104, "376412D4-0000-0000-0000-000000000000")

	mockLastURL = mock.URL()
	rec := &recorder{}
	eng := newEngine(t, rec)
	defer eng.Stop()

	if err := eng.Start(&engine.Account{Username: "stu_guid", Password: "pw"}); err != nil {
		t.Fatalf("start: %v", err)
	}
	waitFor(t, 8*time.Second, "online after unknown-GUID fallback", func() bool {
		s, _ := eng.CurrentState()
		return s == engine.StateOnline
	})

	uas := mock.HandshakeUAs()
	if len(uas) < 2 || uas[0] != portal.UAAndroid2104 || uas[len(uas)-1] != portal.UAAndroid2089 {
		t.Fatalf("unexpected handshake UA sequence: %v", uas)
	}
}

// TestFixedUserAgent 手选 UA 走单元素链：全程只用该 UA，不做回退。
func TestFixedUserAgent(t *testing.T) {
	mock := mockportal.New()
	defer mock.Close()

	mockLastURL = mock.URL()
	rec := &recorder{}
	eng := newEngine(t, rec)
	defer eng.Stop()

	if err := eng.Start(&engine.Account{Username: "stu_pc", Password: "pw", UserAgent: portal.UAPCLinux64}); err != nil {
		t.Fatalf("start: %v", err)
	}
	waitFor(t, 8*time.Second, "online with fixed UA", func() bool {
		s, _ := eng.CurrentState()
		return s == engine.StateOnline
	})

	for _, ua := range mock.HandshakeUAs() {
		if ua != portal.UAPCLinux64 {
			t.Fatalf("fixed UA must not fall back, saw %v", mock.HandshakeUAs())
		}
	}
}

// TestPortalPageUnusableTriggersFallback 门户页对指定 UA 不可用（2xx 但无配置块）
// 触发 UA 回退：2104 页面异形（失败发生在探测阶段，无握手产生）→ 自动落到
// 2089 完成认证。
// 回归锚：Unusable 标志曾因 Detect 聚合丢标志而在主路径不可达（死代码）。
func TestPortalPageUnusableTriggersFallback(t *testing.T) {
	mock := mockportal.New()
	defer mock.Close()
	mock.SetUnusableUAs(portal.UAAndroid2104)

	mockLastURL = mock.URL()
	rec := &recorder{}
	eng := newEngine(t, rec)
	defer eng.Stop()

	if err := eng.Start(&engine.Account{Username: "stu_page", Password: "pw"}); err != nil {
		t.Fatalf("start: %v", err)
	}
	waitFor(t, 8*time.Second, "online after unusable-page fallback", func() bool {
		s, _ := eng.CurrentState()
		return s == engine.StateOnline
	})

	// 唯一的握手来自回退后的 2089；2104 的尝试止步于探测并留下回退日志
	if uas := mock.HandshakeUAs(); len(uas) != 1 || uas[0] != portal.UAAndroid2089 {
		t.Fatalf("fallback must complete auth on 2089, got %v", uas)
	}
	if !rec.hasLog("portal page unusable for this user-agent: ua=" + portal.UAAndroid2104) {
		t.Fatalf("missing unusable-page attempt log:\n%s", rec.dump())
	}
}

// TestCustomUserAgentXMLEscaped 含 XML 特殊字符的自定义 UA 必须被转义后
// 写入报文体且无损回读：HTTP 头保持原值，login 报文内 <user-agent> 反转义后
// 与账号配置逐字节相等（缺失转义时报文 XML 直接畸形，本用例即失败）。
func TestCustomUserAgentXMLEscaped(t *testing.T) {
	mock := mockportal.New()
	defer mock.Close()

	const customUA = "CCTP/a&b<1>/123"
	mockLastURL = mock.URL()
	rec := &recorder{}
	eng := newEngine(t, rec)
	defer eng.Stop()

	if err := eng.Start(&engine.Account{Username: "stu_esc", Password: "pw", UserAgent: customUA}); err != nil {
		t.Fatalf("start: %v", err)
	}
	waitFor(t, 8*time.Second, "online with special-char custom UA", func() bool {
		s, _ := eng.CurrentState()
		return s == engine.StateOnline
	})

	// HTTP 头未被转义（握手 UA 记录的是头原值）
	if uas := mock.HandshakeUAs(); len(uas) == 0 || uas[0] != customUA {
		t.Fatalf("HTTP header UA must stay raw, got %v", uas)
	}
	// XML 报文体经转义+反转义后无损回读
	if got := mock.LastAuthXMLUA(); got != customUA {
		t.Fatalf("XML <user-agent> roundtrip mismatch: got %q, want %q", got, customUA)
	}
	u, _ := mock.LastUser()
	if u != "stu_esc" {
		t.Fatalf("login user = %q, want stu_esc", u)
	}
}

// TestAllUARejected 全链被拒：进入错误态且说明指向 UA，按退避重试。
func TestAllUARejected(t *testing.T) {
	mock := mockportal.New()
	defer mock.Close()
	mock.SetRejectedUAs(portal.UAAndroid2104, portal.UAAndroid2089, portal.UAAndroid2093)

	mockLastURL = mock.URL()
	rec := &recorder{}
	eng := newEngine(t, rec)
	defer eng.Stop()

	if err := eng.Start(&engine.Account{Username: "stu_none", Password: "pw"}); err != nil {
		t.Fatalf("start: %v", err)
	}
	waitFor(t, 8*time.Second, "error state listing all rejected UAs", func() bool {
		s, d := eng.CurrentState()
		return s == engine.StateError && strings.Contains(d, "UA")
	})
	if n := len(mock.HandshakeUAs()); n < 3 {
		t.Fatalf("expected full chain attempts (>=3 handshakes), got %d: %v",
			n, mock.HandshakeUAs())
	}
}

// TestLoginFailureAutoRetryFreshHandshake 自动重试轮必须归零 AlgoID。
// 严格握手模式下（body 非全零 UUID → Error-Code 1，对齐真实服务端语义），
// 注入一次 Ticket 失败：重试轮握手若带上一轮服务器下发的 GUID 会被拒并
// 误触发 UA 回退（历史缺陷），正确序列应为两轮都落在链首 UA 上。
func TestLoginFailureAutoRetryFreshHandshake(t *testing.T) {
	mock := mockportal.New()
	defer mock.Close()
	mock.SetStrictHandshake(true)
	mock.SetFailTickets(1)

	mockLastURL = mock.URL()
	rec := &recorder{}
	eng := newEngine(t, rec)
	defer eng.Stop()

	if err := eng.Start(&engine.Account{Username: "stu_retry", Password: "pw"}); err != nil {
		t.Fatalf("start: %v", err)
	}
	waitFor(t, 10*time.Second, "online after ticket failure retry", func() bool {
		s, _ := eng.CurrentState()
		return s == engine.StateOnline
	})

	uas := mock.HandshakeUAs()
	if len(uas) != 2 || uas[0] != portal.UAAndroid2104 || uas[1] != portal.UAAndroid2104 {
		t.Fatalf("retry must re-handshake with zero algo id on the same UA (no UA fallback), got %v", uas)
	}
}

// TestPreferredUAMemoryAcrossReconnect 成功 UA 的记忆与全程一致性：
// 2104 被拒 → 2089 成功（preferredUA=2089）→ 心跳/重连握手/term 全部沿用
// 2089，重连轮回退链以 2089 为链首（不得回落 2104）。
func TestPreferredUAMemoryAcrossReconnect(t *testing.T) {
	mock := mockportal.New()
	defer mock.Close()
	mock.SetRejectedUAs(portal.UAAndroid2104)

	mockLastURL = mock.URL()
	rec := &recorder{}
	eng := newEngine(t, rec)
	defer eng.Stop()

	if err := eng.Start(&engine.Account{Username: "stu_mem", Password: "pw"}); err != nil {
		t.Fatalf("start: %v", err)
	}
	waitFor(t, 8*time.Second, "first online via 2089", func() bool {
		s, _ := eng.CurrentState()
		return s == engine.StateOnline
	})
	if uas := mock.HandshakeUAs(); len(uas) != 2 || uas[0] != portal.UAAndroid2104 || uas[1] != portal.UAAndroid2089 {
		t.Fatalf("unexpected first-round handshakes: %v", uas)
	}

	// 心跳 UA 必须与登录会话同族（不能回落链首 2104）
	waitFor(t, 5*time.Second, "heartbeats>=2", func() bool { return mock.Keeps() >= 2 })
	for _, ua := range mock.KeepUAs() {
		if ua != portal.UAAndroid2089 {
			t.Fatalf("heartbeat must reuse session UA 2089, saw %v", mock.KeepUAs())
		}
	}

	// 心跳故障掉线 → 自动重连：链首应为记忆的 2089
	mock.SetKeepFailing(true)
	waitFor(t, 12*time.Second, "reconnect handshake fired", func() bool {
		return len(mock.HandshakeUAs()) >= 3
	})
	mock.SetKeepFailing(false)
	waitFor(t, 12*time.Second, "re-online", func() bool {
		s, _ := eng.CurrentState()
		return s == engine.StateOnline
	})
	if uas := mock.HandshakeUAs(); len(uas) != 3 || uas[2] != portal.UAAndroid2089 {
		t.Fatalf("preferredUA must head the chain on reconnect: %v", uas)
	}

	// term 同样沿用会话 UA
	eng.Logout()
	waitFor(t, 5*time.Second, "term sent", func() bool { return mock.Terms() >= 1 })
	if ua := mock.LastTermUA(); ua != portal.UAAndroid2089 {
		t.Fatalf("term must reuse session UA 2089, got %q", ua)
	}
}
