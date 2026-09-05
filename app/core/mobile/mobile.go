// Package mobile 是 gomobile 导出层：把 engine 能力以 gomobile 兼容的
// 受限类型（string/int32/struct/接口回调）暴露给 Android Kotlin 侧。
//
// 注意：
//   - 回调从 Go goroutine 触发，Kotlin 侧需自行切主线程；
//   - Kotlin 必须持有 Callback 的强引用（gomobile GC 边界）。
package mobile

import (
	"fmt"

	core "github.com/EchoPing07/better-esurfing-for-android/core/engine"
)

// 状态常量（与 engine.State 数值一致，Kotlin 侧用 int 判断）。
const (
	StateIdle        int32 = 0 // 已创建未启动
	StateDetecting   int32 = 1 // 探测网络中
	StateAuthorizing int32 = 2 // 认证流程进行中
	StateOnline      int32 = 3 // 在线心跳中
	StateLoggedOut   int32 = 4 // 已登出
	StateError       int32 = 5 // 出错（自动重试中）
)

// 日志级别常量。
const (
	LogDebug int32 = 0
	LogInfo  int32 = 1
	LogWarn  int32 = 2
	LogError int32 = 3
)

// Callback Kotlin 实现此接口接收引擎事件。
type Callback interface {
	OnStateChanged(state int32, detail string)
	OnLog(level int32, message string)
}

// Config 引擎配置（JSON 字段为空使用默认值）。
type Config struct {
	ProbeURLsJSON     string // 探针 URL JSON 数组
	DomainMapJSON     string // 域名→IP 映射 JSON 对象
	DetectIntervalSec int    // 空闲巡检间隔秒
	HeartbeatRetry    int    // 心跳失败重试次数
	MaxBackoffSec     int    // 重试退避上限秒
	ShieldWindowSec   int    // 认证护盾窗口秒
	TimeoutSec        int    // 单请求超时秒
}

// Account 认证账号。
type Account struct {
	Username  string
	Password  string
	UserAgent string // 认证 UA；空 = auto（2104→2089→2093 自动回退链）
	Note      string
}

// Engine 引擎门面。
type Engine struct{ inner *core.Engine }

// NewEngine 创建引擎。
func NewEngine(cfg *Config, cb Callback) (*Engine, error) {
	if cfg == nil {
		cfg = &Config{}
	}
	var c *core.Config
	if *cfg != (Config{}) {
		c = &core.Config{
			ProbeURLsJSON:     cfg.ProbeURLsJSON,
			DomainMapJSON:     cfg.DomainMapJSON,
			DetectIntervalSec: cfg.DetectIntervalSec,
			HeartbeatRetry:    cfg.HeartbeatRetry,
			MaxBackoffSec:     cfg.MaxBackoffSec,
			ShieldWindowSec:   cfg.ShieldWindowSec,
			TimeoutSec:        cfg.TimeoutSec,
		}
	}
	inner := core.NewEngine(c, cbAdapter{cb})
	return &Engine{inner: inner}, nil
}

// Start 启动并认证。
func (e *Engine) Start(acc *Account) error {
	if acc == nil {
		return fmt.Errorf("account is nil")
	}
	return e.inner.Start(&core.Account{
		Username: acc.Username, Password: acc.Password, UserAgent: acc.UserAgent, Note: acc.Note,
	})
}

// LoginNow 切号/立即重登。
func (e *Engine) LoginNow(acc *Account) error {
	if acc == nil {
		return fmt.Errorf("account is nil")
	}
	return e.inner.LoginNow(&core.Account{
		Username: acc.Username, Password: acc.Password, UserAgent: acc.UserAgent, Note: acc.Note,
	})
}

// Logout 登出当前账号。
func (e *Engine) Logout() { e.inner.Logout() }

// Stop 停止引擎（在线则先 Term）。
func (e *Engine) Stop() { e.inner.Stop() }

// IsRunning 是否运行中。
func (e *Engine) IsRunning() bool { return e.inner.IsRunning() }

// State 当前状态值。
func (e *Engine) State() int32 {
	s, _ := e.inner.CurrentState()
	return int32(s)
}

// Detail 当前状态说明文本。
func (e *Engine) Detail() string {
	_, d := e.inner.CurrentState()
	return d
}

// DumpLogs 导出全部缓冲日志。
func (e *Engine) DumpLogs() string { return e.inner.DumpLogs() }

// Version 引擎版本号。
func Version() string { return "0.1.0" }

// cbAdapter 把 Callback 的空指针风险收口到 Go 侧。
type cbAdapter struct{ cb Callback }

func (a cbAdapter) OnStateChanged(state int32, detail string) {
	if a.cb != nil {
		a.cb.OnStateChanged(state, detail)
	}
}

func (a cbAdapter) OnLog(level int32, message string) {
	if a.cb != nil {
		a.cb.OnLog(level, message)
	}
}
