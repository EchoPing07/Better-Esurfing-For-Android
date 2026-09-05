// Package engine 实现天翼校园网 CCTP 认证引擎：
// 探测 → ZSM 握手 → GetTicket → Login → Keepalive 循环，含断线重连、
// 账号切换、优雅登出与认证护盾。纯 Go 实现，不依赖任何 Android API。
package engine

import "time"

// State 引擎对外状态（数值稳定，供 gomobile 边界使用）。
type State int32

const (
	StateIdle        State = iota // 已创建未启动
	StateDetecting                // 探测网络中（未认证）
	StateAuthorizing              // 认证流程进行中（握手/Ticket/Login）
	StateOnline                   // 在线，心跳循环中
	StateLoggedOut                // 已登出（继续监控网络）
	StateError                    // 出错（detail 说明原因；引擎仍会按退避自动重试）
)

// String 状态名。
func (s State) String() string {
	switch s {
	case StateIdle:
		return "Idle"
	case StateDetecting:
		return "Detecting"
	case StateAuthorizing:
		return "Authorizing"
	case StateOnline:
		return "Online"
	case StateLoggedOut:
		return "LoggedOut"
	case StateError:
		return "Error"
	}
	return "Unknown"
}

// 日志级别。
const (
	LogDebug int32 = iota
	LogInfo
	LogWarn
	LogError
)

// Callback 引擎事件回调（可能从内部 goroutine 调用，接收方需自行保证线程安全/切线程）。
type Callback interface {
	// OnStateChanged 状态变化通知。
	OnStateChanged(state int32, detail string)
	// OnLog 日志流。
	OnLog(level int32, message string)
}

// Account 一个认证账号。
type Account struct {
	Username  string // 学号/手机号
	Password  string // 密码
	UserAgent string // 认证 UA；空 = "auto"（2104→2089→2093 自动回退链），预设值见 portal 包
	Note      string // 备注（仅展示用）
}

// Config 引擎配置。JSON 字段为空时使用内置默认值。
type Config struct {
	// ProbeURLsJSON 探测 URL 列表 JSON 数组，如
	// ["http://connect.rom.miui.com/generate_204","http://connectivitycheck.gstatic.com/generate_204"]
	ProbeURLsJSON string
	// DomainMapJSON 域名→IP 映射 JSON 对象，如 {"enet.10000.gd.cn":"125.88.59.131"}
	// （校园网内 DNS 无法解析门户域名时改写 URL）
	DomainMapJSON string
	// DetectIntervalSec 空闲巡检间隔秒数，默认 20
	DetectIntervalSec int
	// HeartbeatRetry 心跳失败重试次数（超过后判定掉线重新探测），默认 3
	HeartbeatRetry int
	// MaxBackoffSec 认证失败退避上限秒数，默认 60
	MaxBackoffSec int
	// ShieldWindowSec 认证护盾窗口秒数（登录成功后的短窗口内不因心跳失败立刻掉线重连），默认 30
	ShieldWindowSec int
	// TimeoutSec 单请求超时秒数，默认 10
	TimeoutSec int
}

// 默认常量。
const (
	DefaultProbeURL = "http://connect.rom.miui.com/generate_204"

	defaultDetectInterval = 20 * time.Second
	defaultHeartbeatRetry = 3
	defaultMaxBackoff     = 60 * time.Second
	defaultShield         = 30 * time.Second
	defaultTimeout        = 10 * time.Second

	// zeroAlgoID 首次 ZSM 握手使用的全零算法 ID。
	zeroAlgoID = "00000000-0000-0000-0000-000000000000"
)

// 内置域名映射（docs/01 §3：广东校园门户已知公网 IP）。
var builtinDomainMap = map[string]string{
	"enet.10000.gd.cn": "125.88.59.131",
}
