package engine

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/EchoPing07/better-esurfing-for-android/core/portal"
)

type cmdKind int

const (
	cmdLoginNow cmdKind = iota // 切号/重登：先 Term 旧会话再走完整认证
	cmdLogout                  // 登出并回到监控态
	cmdStop                    // 停止引擎（若在线先 Term）
)

type command struct {
	kind cmdKind
	acc  *Account
}

// ErrNilAccount 账号为空。
var ErrNilAccount = fmt.Errorf("account is nil or empty username")

// ErrAlreadyStarted 引擎已启动。
var ErrAlreadyStarted = fmt.Errorf("engine already started")

// Engine CCTP 认证引擎。一个实例内部运行一个 goroutine；公开方法线程安全。
type Engine struct {
	cfg    Config
	cb     Callback
	logBuf *ringBuffer

	mu      sync.Mutex
	started bool
	curAcc  *Account
	state   State
	detail  string
	cmdCh   chan command
	notify  chan struct{}
	cancel  context.CancelFunc
	done    chan struct{}

	// ---- 以下字段仅由内部 loop goroutine 读写 ----
	http     *http.Client
	httpUA   string // e.http 对应的 UA（变化时重建客户端，独立 CookieJar）
	sess     *session
	clientID string
	algoID   string
	mac      string
	hostName string

	// UA 回退链（ua.go）：认证轮内 uaIdx 指向当前尝试；成功后游标停在
	// 成功 UA 上，心跳/term 全程沿用；preferredUA 为上次成功值（auto 链置链首）。
	uaChain     []string
	uaIdx       int
	preferredUA string
	lastUAWarn  string // 已告警过的非法 UA 配置值（巡检周期内去重刷屏）

	schoolID string
	domain   string
	area     string
	userIP   string
	acIP     string

	authURL   string
	ticketURL string
	keepURL   string
	termURL   string
	keepRetry string
	ticket    string

	online      bool
	loginAt     time.Time
	nextBeat    time.Time
	nextAction  time.Time
	backoff     time.Duration
	beatFailCnt int
	probeURLs   []string
	domainMap   map[string]string
}

// NewEngine 创建引擎（不启动）。启动用 Start / LoginNow。
func NewEngine(cfg *Config, cb Callback) *Engine {
	if cfg == nil {
		cfg = &Config{}
	}
	e := &Engine{
		cfg:    *cfg,
		cb:     cb,
		logBuf: newRingBuffer(800),
		cmdCh:  make(chan command, 16),
		notify: make(chan struct{}, 1),
		done:   make(chan struct{}),
		sess:   &session{},
	}
	e.loadConfig()
	return e
}

func (e *Engine) loadConfig() {
	if e.cfg.ProbeURLsJSON != "" {
		_ = json.Unmarshal([]byte(e.cfg.ProbeURLsJSON), &e.probeURLs)
	}
	if len(e.probeURLs) == 0 {
		e.probeURLs = []string{DefaultProbeURL}
	}
	e.domainMap = map[string]string{}
	for k, v := range builtinDomainMap {
		e.domainMap[k] = v
	}
	if e.cfg.DomainMapJSON != "" {
		var m map[string]string
		if err := json.Unmarshal([]byte(e.cfg.DomainMapJSON), &m); err == nil {
			for k, v := range m {
				e.domainMap[k] = v
			}
		}
	}
}

// ---------- 公开 API ----------

// Start 启动引擎并以指定账号发起认证。重复 Start 返回 ErrAlreadyStarted。
func (e *Engine) Start(acc *Account) error {
	if acc == nil || acc.Username == "" {
		return ErrNilAccount
	}
	e.mu.Lock()
	if e.started {
		e.mu.Unlock()
		return ErrAlreadyStarted
	}
	// 保险：若上次 Stop 超时退出导致旧 loop 仍在跑，先取消其 ctx
	if e.cancel != nil {
		e.cancel()
	}
	e.started = true
	e.curAcc = acc
	ctx, cancel := context.WithCancel(context.Background())
	e.cancel = cancel
	// 修复闪退（close of closed channel）：done 是一次性 channel，
	// 上次 loop 退出时已 close；必须换新，否则本次 loop 退出时二次 close 直接 panic。
	e.done = make(chan struct{})
	// 修复“启动即自停”：清空上次会话残留的命令（如未被消费的 cmdStop），
	// 否则新 loop 启动后会立刻读到它而直接进入停止流程。
	for {
		select {
		case <-e.cmdCh:
			continue
		default:
		}
		break
	}
	e.mu.Unlock()

	go e.loop(ctx)
	e.submit(command{kind: cmdLoginNow, acc: acc})
	return nil
}

// LoginNow 立即用指定账号重新认证/切号（引擎未启动则自动启动）。
func (e *Engine) LoginNow(acc *Account) error {
	if acc == nil || acc.Username == "" {
		return ErrNilAccount
	}
	e.mu.Lock()
	started := e.started
	e.mu.Unlock()
	if !started {
		return e.Start(acc)
	}
	e.submit(command{kind: cmdLoginNow, acc: acc})
	return nil
}

// Logout 登出当前账号（在线时发送 Term），随后继续网络监控。
func (e *Engine) Logout() {
	e.mu.Lock()
	started := e.started
	e.mu.Unlock()
	if !started {
		return
	}
	e.submit(command{kind: cmdLogout})
}

// Stop 优雅停止：若在线先尝试 Term，然后结束内部 goroutine。阻塞至多 5 秒。
func (e *Engine) Stop() {
	e.mu.Lock()
	if !e.started {
		e.mu.Unlock()
		return
	}
	e.cancel()
	e.mu.Unlock()
	e.submit(command{kind: cmdStop})
	select {
	case <-e.done:
	case <-time.After(5 * time.Second):
	}
	e.mu.Lock()
	e.started = false
	e.mu.Unlock()
}

// IsRunning 引擎是否在运行。
func (e *Engine) IsRunning() bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.started
}

// CurrentState 当前状态与说明。
func (e *Engine) CurrentState() (State, string) {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.state, e.detail
}

// DumpLogs 导出环形缓冲内全部日志文本。
func (e *Engine) DumpLogs() string { return e.logBuf.dump() }

func (e *Engine) submit(c command) {
	select {
	case <-e.done:
	case e.cmdCh <- c:
	default:
		e.log(LogWarn, "命令队列已满，丢弃 %v", c.kind)
	}
	select {
	case e.notify <- struct{}{}:
	default:
	}
}

// ---------- 内部 ----------

func (e *Engine) log(level int32, format string, args ...any) {
	msg := format
	if len(args) > 0 {
		msg = fmt.Sprintf(format, args...)
	}
	e.logBuf.add(level, msg)
	if e.cb != nil {
		func() {
			defer func() { recover() }()
			e.cb.OnLog(level, msg)
		}()
	}
}

// setState 状态变化通知（总是触发回调）。
func (e *Engine) setState(s State, detail string) {
	e.mu.Lock()
	e.state, e.detail = s, detail
	st, dt := e.state, e.detail
	e.mu.Unlock()
	e.log(LogInfo, "状态 → %s %s", st, dt)
	e.fireStateChanged(st, dt)
}

// setStateIfChanged 仅在状态或说明变化时通知（用于周期性路径防刷屏）。
func (e *Engine) setStateIfChanged(s State, detail string) {
	e.mu.Lock()
	if e.state == s && e.detail == detail {
		e.mu.Unlock()
		return
	}
	e.state, e.detail = s, detail
	st, dt := e.state, e.detail
	e.mu.Unlock()
	e.log(LogInfo, "状态 → %s %s", st, dt)
	e.fireStateChanged(st, dt)
}

func (e *Engine) fireStateChanged(s State, detail string) {
	if e.cb != nil {
		func() {
			defer func() { recover() }()
			e.cb.OnStateChanged(int32(s), detail)
		}()
	}
}

func (e *Engine) detectInterval() time.Duration {
	if e.cfg.DetectIntervalSec > 0 {
		return time.Duration(e.cfg.DetectIntervalSec) * time.Second
	}
	return defaultDetectInterval
}

func (e *Engine) beatRetryN() int {
	if e.cfg.HeartbeatRetry > 0 {
		return e.cfg.HeartbeatRetry
	}
	return defaultHeartbeatRetry
}

func (e *Engine) maxBackoff() time.Duration {
	if e.cfg.MaxBackoffSec > 0 {
		return time.Duration(e.cfg.MaxBackoffSec) * time.Second
	}
	return defaultMaxBackoff
}

func (e *Engine) shieldWindow() time.Duration {
	if e.cfg.ShieldWindowSec > 0 {
		return time.Duration(e.cfg.ShieldWindowSec) * time.Second
	}
	return defaultShield
}

func (e *Engine) reqTimeout() time.Duration {
	if e.cfg.TimeoutSec > 0 {
		return time.Duration(e.cfg.TimeoutSec) * time.Second
	}
	return defaultTimeout
}

// keepRetrySec 服务端下发的心跳间隔秒数（异常时回退 25s）。
func (e *Engine) keepRetrySec() int {
	n, err := strconv.Atoi(e.keepRetry)
	if err != nil || n <= 0 {
		return 25
	}
	if n < 1 {
		n = 1
	}
	return n
}

// loop 主循环：单一 goroutine 拥有全部会话字段；命令驱动 + 定时驱动。
func (e *Engine) loop(ctx context.Context) {
	defer close(e.done)
	e.refreshIdentity()
	e.setState(StateDetecting, "启动")
	e.nextAction = time.Now()

	tick := time.NewTicker(300 * time.Millisecond)
	defer tick.Stop()

	for {
		// 命令优先处理
	pending:
		for {
			select {
			case c := <-e.cmdCh:
				switch c.kind {
				case cmdLoginNow:
					e.handleLoginNow(c.acc)
				case cmdLogout:
					e.handleLogout(false)
				case cmdStop:
					e.handleLogout(true)
					e.setStateIfChanged(StateIdle, "已停止")
					return
				}
			default:
				break pending
			}
		}

		select {
		case <-ctx.Done():
			e.handleLogout(true)
			e.setStateIfChanged(StateIdle, "已停止")
			return
		default:
		}

		now := time.Now()
		if e.online {
			if !now.Before(e.nextBeat) {
				e.beatPhase()
			}
		} else if !now.Before(e.nextAction) && e.curAcc != nil {
			e.detectPhase()
		}

		select {
		case <-ctx.Done():
			e.handleLogout(true)
			e.setStateIfChanged(StateIdle, "已停止")
			return
		case <-tick.C:
		}
	}
}

// handleLoginNow 切号/立即登录：在线先登出旧号，再走完整认证轮。
// 显式认证请求重置 preferredUA：从链头重新协商（账号/UA 配置可能已变化）；
// 会话复位与探测由 authRound 的首次尝试统一完成。
func (e *Engine) handleLoginNow(acc *Account) {
	if e.online {
		e.log(LogInfo, "切换账号：先登出 %s", maskUser(e.curAcc.Username))
		e.setState(StateLoggedOut, "正在登出旧账号")
		if err := e.term(); err != nil {
			e.log(LogWarn, "旧账号 Term 失败(忽略): %v", err)
		}
		e.online = false
	} else if e.curAcc != nil && e.curAcc.Username == acc.Username {
		e.log(LogInfo, "重新认证: %s", maskUser(acc.Username))
	} else {
		e.log(LogInfo, "登录请求: %s", maskUser(acc.Username))
	}
	e.curAcc = acc
	e.preferredUA = ""
	e.authRound()
}

// handleLogout 用户登出或停止。isStop=true 时进入 Idle 终态。
func (e *Engine) handleLogout(isStop bool) {
	if e.online {
		e.setStateIfChanged(StateLoggedOut, "正在登出")
		if err := e.term(); err != nil {
			e.log(LogWarn, "Term 失败(忽略): %v", err)
		} else {
			e.log(LogInfo, "已发送登出")
		}
		e.online = false
	}
	e.sess.free()
	if isStop {
		return // 由调用方置 Idle
	}
	e.nextAction = time.Now().Add(e.detectInterval())
	e.setStateIfChanged(StateDetecting, "已登出，继续监控网络")
}

// resetRound 每轮认证尝试前的会话复位（对齐 go-webui resetSession）：
// 身份标识全部换新、AlgoID 归零、CookieJar 一律新建（跨尝试不复用门户 Cookie，
// 失败尝试种下的会话状态不污染下一轮）；CDC 路由头保留复用，门户参数由
// authAttempt 每轮重新探测获取（authAttempt 不再复用上轮探测结果）。
func (e *Engine) resetRound(ua string) {
	e.sess.free()
	e.ticket = ""
	e.keepURL, e.termURL, e.keepRetry = "", "", ""
	e.refreshIdentity()
	e.http = portal.NewClient(e, ua, e.reqTimeout(), detectLogger{e})
	e.httpUA = ua
}

// ensureHTTP 为指定 UA 准备探测用 HTTP 客户端（仅 UA 变化时重建；
// 认证尝试用 resetRound，那里每轮强制新建）。
func (e *Engine) ensureHTTP(ua string) {
	if e.http == nil || e.httpUA != ua {
		e.http = portal.NewClient(e, ua, e.reqTimeout(), detectLogger{e})
		e.httpUA = ua
	}
}

// detectPhase 未在线时的探测：发现门户则自动认证；已连通/出错则安排下次探测。
// 探测 UA 取回退链当前头；门户页对当前 UA 不可用（页面形态随 UA 族不同）时
// 同样进入认证轮，由回退链换 UA 重试。
func (e *Engine) detectPhase() {
	e.resolveUAChain()
	ua := e.currentUA()
	e.ensureHTTP(ua)
	res := e.probeOnce()
	switch res.Status {
	case portal.StatusSuccess:
		e.setStateIfChanged(StateDetecting, "网络已连通，无需认证")
		e.backoff = 0
		e.nextAction = time.Now().Add(e.detectInterval())

	case portal.StatusRequireAuthorization:
		e.applyPortalResult(res)
		e.authRound()

	case portal.StatusRequestError:
		if res.Unusable {
			// 2xx 但解析不出配置块：多半是页面形态与 UA 不匹配，交回退链裁决
			e.log(LogWarn, "门户页对 UA %s 不可用，进入回退链重试", ua)
			e.authRound()
			return
		}
		e.backoff = 0
		e.nextAction = time.Now().Add(e.detectInterval())
		e.setStateIfChanged(StateError, "网络探测失败（不在校园网？）")
	}
}

// probeOnce 执行一次门户探测（不触发认证）。
func (e *Engine) probeOnce() portal.ConfigResult {
	return portal.Detect(e.http, e, portal.Options{
		ProbeURLs: e.probeURLs,
		UserAgent: e.currentUA(),
		DomainMap: e.domainMap,
		Timeout:   e.reqTimeout(),
		Logger:    detectLogger{e},
	})
}

// applyPortalResult 把探测结果中的门户参数写入会话状态。
func (e *Engine) applyPortalResult(res portal.ConfigResult) {
	e.log(LogInfo, "检测到强制门户 auth=%s ticket=%s ip=%s ac=%s",
		res.AuthURL, res.TicketURL, res.UserIP, res.AcIP)
	e.authURL, e.ticketURL = res.AuthURL, res.TicketURL
	e.userIP, e.acIP = res.UserIP, res.AcIP
	if res.SchoolID != "" {
		e.schoolID = res.SchoolID
	}
	if res.Domain != "" {
		e.domain = res.Domain
	}
	if res.Area != "" {
		e.area = res.Area
	}
}

// authRound 完整认证轮：逐 UA 尝试（探测 → ZSM 握手 → Ticket → Login）。
// 服务端按 UA 家族分派算法池且准入闸按版本拒绝（ua.go 说明）：ZSM 被拒 /
// 空体 / 不可解析 / 未知 GUID 时换下一个 UA 整链重试（每轮新身份 + 独立会话，
// 门户页形态随 UA 族不同必须重走探测）；网络层错误与业务拒绝不回退，
// 按指数退避重试。链耗尽时把全部尝试记录写入日志。
func (e *Engine) authRound() {
	defer func() {
		if r := recover(); r != nil {
			e.log(LogError, "认证流程 panic: %v", r)
			e.scheduleAuthFailure("内部错误")
		}
	}()

	e.resolveUAChain()

	var attempts []string
	for e.uaIdx = 0; e.uaIdx < len(e.uaChain); e.uaIdx++ {
		ua := e.uaChain[e.uaIdx]
		err := e.authAttempt(ua)
		switch {
		case err == nil:
			// 链内成功：记忆本 UA，后续 ticket/心跳/term 全程沿用
			e.preferredUA = ua
			e.online = true
			e.loginAt = time.Now()
			e.nextBeat = e.loginAt.Add(time.Duration(e.keepRetrySec()) * time.Second)
			e.beatFailCnt = 0
			e.nextAction = time.Time{} // 在线期间不探测
			e.log(LogInfo, "认证成功 ua=%s keep间隔=%ss", ua, e.keepRetry)
			e.setState(StateOnline, "已在线 "+maskUser(e.curAcc.Username))
			return

		case errors.Is(err, errAuthNotNeeded):
			// 网络已连通：非错误，回到监控态
			e.backoff = 0
			e.nextAction = time.Now().Add(e.detectInterval())
			return

		case isUAFallbackError(err):
			attempts = append(attempts, fmt.Sprintf("ua=%s: %v", ua, err))
			e.log(LogWarn, "%v，尝试下一个 UA", err)
			continue

		default:
			e.scheduleAuthFailure(err.Error())
			return
		}
	}

	e.log(LogError, "认证失败，全部 UA 未通过:\n  %s", strings.Join(attempts, "\n  "))
	e.scheduleAuthFailure("认证失败：所有 UA 均未通过")
}

// authAttempt 单个 UA 的完整认证尝试。返回 UA 回退类错误（isUAFallbackError）
// 时由调用方换下一个 UA；网络已连通返回 errAuthNotNeeded。
//
// 每轮尝试都是独立会话（resetRound：新身份 + AlgoID 归零 + 新 CookieJar）后
// 重新探测门户——门户页形态随 UA 族不同，且 AlgoID 绝不能带入下一轮握手
// （带旧 GUID 再握会被拒 Error-Code 1，docs/05 §3.2）。探测与握手同用本轮
// 新身份。每轮多一次探测 GET，换取重试路径上会话状态零残留。
func (e *Engine) authAttempt(ua string) error {
	e.resetRound(ua)

	res := e.probeOnce()
	switch res.Status {
	case portal.StatusRequireAuthorization:
		e.applyPortalResult(res)
	case portal.StatusSuccess:
		e.log(LogInfo, "网络已连通，无需登录")
		e.setStateIfChanged(StateDetecting, "网络已连通，无需认证")
		return errAuthNotNeeded
	default:
		if res.Unusable {
			// 2xx 但解析不出配置块：页面形态与 UA 不匹配，换 UA 裁决
			return fmt.Errorf("%w: ua=%s", errPortalPageUnusable, ua)
		}
		e.log(LogWarn, "补探未发现门户，稍后重试")
		return errors.New("未检测到认证门户")
	}

	e.setStateIfChanged(StateAuthorizing, "开始认证 "+maskUser(e.curAcc.Username))

	if err := e.handshake(); err != nil {
		e.log(LogWarn, "握手失败: %v", err)
		return err
	}
	if err := e.getTicket(); err != nil {
		e.log(LogWarn, "GetTicket 失败: %v", err)
		return err
	}
	if err := e.login(); err != nil {
		return err
	}
	return nil
}

func (e *Engine) scheduleAuthFailure(detail string) {
	if e.backoff == 0 {
		e.backoff = 3 * time.Second
	} else {
		e.backoff *= 2
	}
	if e.backoff > e.maxBackoff() {
		e.backoff = e.maxBackoff()
	}
	e.nextAction = time.Now().Add(e.backoff)
	e.setState(StateError, fmt.Sprintf("%s，%d秒后重试", detail, int64((e.backoff+999*time.Millisecond)/time.Second)))
}

// beatPhase 在线心跳阶段：护盾窗口内快速重试；连续失败判定掉线回到探测。
func (e *Engine) beatPhase() {
	err := e.heartbeat()
	now := time.Now()
	shield := now.Sub(e.loginAt) < e.shieldWindow()
	if err == nil {
		if e.beatFailCnt > 0 {
			e.log(LogInfo, "心跳恢复")
		}
		e.beatFailCnt = 0
		e.nextBeat = now.Add(time.Duration(e.keepRetrySec()) * time.Second)
		return
	}
	e.beatFailCnt++
	e.log(LogWarn, "心跳失败(%d/%d): %v", e.beatFailCnt, e.beatRetryN(), err)
	switch {
	case shield:
		e.nextBeat = now.Add(3 * time.Second) // 护盾窗口内视为瞬时抖动
	case e.beatFailCnt < e.beatRetryN():
		e.nextBeat = now.Add(2 * time.Second)
	default:
		e.log(LogWarn, "心跳连续失败，判定掉线，重新探测")
		e.online = false
		e.sess.free()
		e.ticket = ""
		e.beatFailCnt = 0
		e.nextAction = time.Now() // 立即重新探测
		e.setState(StateDetecting, "连接断开，自动重连中")
	}
}

// maskUser 用户名打码：保留前2后2。
func maskUser(u string) string {
	r := []rune(u)
	if len(r) <= 4 {
		return u
	}
	return string(r[:2]) + "***" + string(r[len(r)-2:])
}

// ---- engine 实现 portal.State ----

func (e *Engine) GetClientID() string { return e.clientID }
func (e *Engine) GetSchoolID() string { return e.schoolID }
func (e *Engine) GetDomain() string   { return e.domain }
func (e *Engine) GetArea() string     { return e.area }

func (e *Engine) SetSchoolID(v string) {
	if v != "" {
		e.schoolID = v
	}
}
func (e *Engine) SetDomain(v string) {
	if v != "" {
		e.domain = v
	}
}
func (e *Engine) SetArea(v string) {
	if v != "" {
		e.area = v
	}
}

type detectLogger struct{ e *Engine }

func (l detectLogger) Logf(format string, args ...any) {
	msg := "[portal] " + fmt.Sprintf(format, args...)
	l.e.log(LogDebug, "%s", msg)
}
