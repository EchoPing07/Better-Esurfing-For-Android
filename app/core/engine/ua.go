package engine

import (
	"errors"
	"strings"

	"github.com/EchoPing07/better-esurfing-for-android/core/portal"
)

// ua.go — 认证 UA 回退链（对齐 Esurfing-go-webui auth.go/client.go 的落地形态）。
//
// 服务端按 UA 家族分派算法池且准入闸按版本拒绝（实测：2093 被 Error-Code 3 拒、
// 2104→新代 9 池、2089→旧代 9 池、1003→PC 6 池，池内 GUID 按会话随机轮换）：
// 握手被拒 / ZSM 空体或不可解析 / 未知算法 GUID 时换下一个 UA 整链重试才有意义；
// 网络层错误与业务拒绝（密码错误、欠费等）换 UA 无意义，不回退。

// UA 回退类错误（authRound 据此换下一个 UA）。
var (
	errZSMRejected        = errors.New("zsm handshake rejected")
	errZSMEmptyBody       = errors.New("zsm empty response")
	errZSMUnparseable     = errors.New("zsm response unparseable")
	errUnknownAlgoID      = errors.New("unknown algo id")
	errPortalPageUnusable = errors.New("portal page unusable for this user-agent")

	// errAuthNotNeeded 探测发现网络已连通（非错误，终止本轮认证回到监控态）。
	errAuthNotNeeded = errors.New("network open, auth not needed")
)

func isUAFallbackError(err error) bool {
	return errors.Is(err, errZSMRejected) ||
		errors.Is(err, errZSMEmptyBody) ||
		errors.Is(err, errZSMUnparseable) ||
		errors.Is(err, errUnknownAlgoID) ||
		errors.Is(err, errPortalPageUnusable)
}

// resolveUAChain 把当前账号的 UA 配置解析为回退链：
//   - 空/"auto"（缺省）→ 默认链 [2104→2089→2093]，上次成功值置链首以跳过已知会失败的尝试；
//   - 其他任意值（预设或手输）→ 单元素链，不做回退。
//
// 幂等：同一账号状态下重复调用结果一致。detectPhase 每个巡检周期都会调用，
// 非法配置的告警按配置值去重（lastUAWarn），避免日志刷屏。
func (e *Engine) resolveUAChain() {
	configured := ""
	if e.curAcc != nil {
		configured = strings.TrimSpace(e.curAcc.UserAgent)
	}
	auto := configured == "" || strings.EqualFold(configured, portal.UAAuto)
	if !auto && !portal.ValidUserAgent(configured) {
		if configured != e.lastUAWarn {
			e.lastUAWarn = configured
			e.log(LogWarn, "无效的 UA 配置 %q（≤64 字符、禁空白/控制字符），回退自动链", configured)
		}
		auto = true
	} else {
		e.lastUAWarn = ""
	}

	var chain []string
	if auto {
		chain = append(chain, portal.DefaultUAChain...)
		if e.preferredUA != "" {
			rest := make([]string, 0, len(chain))
			for _, ua := range chain {
				if ua != e.preferredUA {
					rest = append(rest, ua)
				}
			}
			chain = append([]string{e.preferredUA}, rest...)
		}
	} else {
		chain = []string{configured}
	}
	e.uaChain = chain
	e.uaIdx = 0
}

// currentUA 当前生效的 User-Agent：HTTP 头与 XML <user-agent> 报文体必须同源于此。
// 认证轮内 = 回退链当前游标；轮外（心跳/term）= 游标停在成功 UA 上。
func (e *Engine) currentUA() string {
	if e.uaIdx >= 0 && e.uaIdx < len(e.uaChain) {
		return e.uaChain[e.uaIdx]
	}
	if e.preferredUA != "" {
		return e.preferredUA
	}
	return portal.DefaultUAChain[0]
}
