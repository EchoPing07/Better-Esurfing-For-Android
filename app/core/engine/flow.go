package engine

import (
	"crypto/md5"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/EchoPing07/better-esurfing-for-android/core/portal"
	"github.com/google/uuid"
)

// ---------- 每轮认证的身份标识 ----------

// refreshIdentity 生成新一轮的 Client-ID / MAC / host-name（docs/01 §9.4：避免被服务端关联旧会话）。
func (e *Engine) refreshIdentity() {
	e.clientID = strings.ToLower(uuid.New().String())
	e.algoID = zeroAlgoID
	e.mac = randomMAC()
	e.hostName = randomString(10)
}

// ---------- HTTP POST（带 CCTP 头） ----------

func (e *Engine) postText(client *http.Client, rawURL, data string) (string, error) {
	s, _, err := e.doPostHeaders(client, rawURL, data)
	return s, err
}

// postRawHeaders 同 postText，但额外返回响应头（ZSM 诊断需要 Error-Code 等头）。
func (e *Engine) postRawHeaders(client *http.Client, rawURL, data string) ([]byte, http.Header, error) {
	s, hdr, err := e.doPostHeaders(client, rawURL, data)
	return []byte(s), hdr, err
}

func (e *Engine) doPostHeaders(client *http.Client, rawURL, data string) (string, http.Header, error) {
	req, err := http.NewRequest(http.MethodPost, rawURL, strings.NewReader(data))
	if err != nil {
		return "", nil, fmt.Errorf("create request: %w", err)
	}
	ua := e.currentUA()
	req.Header.Set("User-Agent", ua)
	req.Header.Set("Accept", "text/html,text/xml,application/xhtml+xml,application/x-javascript,*/*")
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	sum := md5.Sum([]byte(data))
	req.Header.Set("CDC-Checksum", hex.EncodeToString(sum[:]))
	req.Header.Set("Client-ID", e.clientID)
	req.Header.Set("Algo-ID", e.algoID)
	if v := e.schoolID; v != "" {
		req.Header.Set("CDC-SchoolId", v)
	}
	if v := e.domain; v != "" {
		req.Header.Set("CDC-Domain", v)
	}
	if v := e.area; v != "" {
		req.Header.Set("CDC-Area", v)
	}

	resp, err := client.Do(req)
	if err != nil {
		return "", nil, fmt.Errorf("request failed: %w", err)
	}
	defer resp.Body.Close()
	buf := make([]byte, 0, 1024)
	tmp := make([]byte, 4096)
	for {
		n, rerr := resp.Body.Read(tmp)
		buf = append(buf, tmp[:n]...)
		if rerr != nil {
			break
		}
		if len(buf) > 1<<20 {
			break // 防御异常大响应
		}
	}
	// 4xx/5xx 一律视为请求失败（密文端点返回非 2xx 时解密必然无意义）
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return "", nil, fmt.Errorf("HTTP %d from %s: %.120s", resp.StatusCode, rawURL, string(buf))
	}
	return string(buf), resp.Header, nil
}

// ---------- 认证各步骤（移植自 EsurfingGo client.go，字段与格式逐字对齐） ----------

// handshake ZSM 会话握手：POST ticket-url，body 为当前 Algo-ID（首轮全零 UUID），
// 响应为二进制密钥材料 + 新 Algo-ID。
// 失败分类（ua.go）：空体/不可解析/未知 GUID 均为 UA 回退类（换 UA 重试有意义），
// 并把 Error-Code / Sub-Error / Server-Tag 诊断头写进错误信息——用户贴一行日志
// 即可区分「UA 被拒 / 算法未实现」。
func (e *Engine) handshake() error {
	body, hdr, err := e.postRawHeaders(e.http, e.ticketURL, e.algoID)
	if err != nil {
		return fmt.Errorf("zsm request: %w", err) // 网络层错误：换 UA 无意义
	}
	e.log(LogDebug, "ZSM 响应 %d 字节", len(body))

	algoID, perr := parseZSMAlgoID(body)
	if perr != nil {
		return fmt.Errorf("%w: ua=%s: %v%s",
			classifyZSMFailure(body, hdr), e.currentUA(), perr, zsmDiag(hdr))
	}
	if err := e.sess.initialize(algoID); err != nil {
		return err // errUnknownAlgoID，错误串含完整 GUID
	}
	e.algoID = algoID
	e.log(LogInfo, "ZSM 握手成功 AlgoID=%s ClientID=%s MAC=%s IP=%s AC=%s",
		e.algoID, e.clientID, e.mac, e.userIP, e.acIP)
	return nil
}

// classifyZSMFailure ZSM 响应不可用时归类为 UA 回退类错误：
// 空体 + Error-Code 非 200 = 准入拒绝；空体无头 = 空响应；其余 = 不可解析。
// ⚠️ Error-Code 头缺失可能是普通门户形态（go-webui 同款兼容规则），不单独判成功——
// 此处仅在响应不可解析时才走到，body 合法即成功。
func classifyZSMFailure(body []byte, hdr http.Header) error {
	if len(body) == 0 {
		if ec := hdr.Get("Error-Code"); ec != "" && ec != "200" {
			return errZSMRejected
		}
		return errZSMEmptyBody
	}
	return errZSMUnparseable
}

// zsmDiag 汇总 ZSM 拒绝诊断头（服务端可控字节，仅 %q 引用防日志伪造）。
func zsmDiag(hdr http.Header) string {
	var parts []string
	for _, h := range []string{"Error-Code", "Sub-Error", "Server-Tag"} {
		if v := hdr.Get(h); v != "" {
			parts = append(parts, fmt.Sprintf("%s=%q", strings.ToLower(h), v))
		}
	}
	if len(parts) == 0 {
		return ""
	}
	return " (" + strings.Join(parts, " ") + ")"
}

// getTicket 阶段三：加密 GetTicket XML → 取 <ticket>。
func (e *Engine) getTicket() error {
	payload := fmt.Sprintf(`<?xml version="1.0" encoding="utf-8"?>
<request>
    <user-agent>%s</user-agent>
    <client-id>%s</client-id>
    <local-time>%s</local-time>
    <host-name>%s</host-name>
    <ipv4>%s</ipv4>
    <ipv6></ipv6>
    <mac>%s</mac>
    <ostag>%s</ostag>
    <gwip>%s</gwip>
</request>`,
		xmlEscape(e.currentUA()), e.clientID, localTime(), e.hostName,
		e.userIP, e.mac, e.hostName, e.acIP)

	enc, err := e.sess.encrypt(payload)
	if err != nil {
		return err
	}
	data, err := e.postText(e.http, e.ticketURL, enc)
	if err != nil {
		return fmt.Errorf("ticket request: %w", err)
	}
	dec, err := e.sess.decrypt(data)
	if err != nil {
		return fmt.Errorf("ticket 解密失败: %w", err)
	}
	e.ticket = portal.ExtractXMLTag(dec, "ticket")
	if e.ticket == "" {
		return fmt.Errorf("响应中无 ticket: %.120s", dec)
	}
	e.log(LogDebug, "取得 %s", sanitize("ticket", e.ticket))
	return nil
}

// login 阶段四：Login XML → keep-url / term-url / keep-retry。
func (e *Engine) login() error {
	payload := fmt.Sprintf(`<?xml version="1.0" encoding="utf-8"?>
<request>
    <user-agent>%s</user-agent>
    <client-id>%s</client-id>
    <ticket>%s</ticket>
    <local-time>%s</local-time>
    <userid>%s</userid>
    <passwd>%s</passwd>
</request>`,
		xmlEscape(e.currentUA()), e.clientID, e.ticket, localTime(),
		xmlEscape(e.curAcc.Username), xmlEscape(e.curAcc.Password))

	enc, err := e.sess.encrypt(payload)
	if err != nil {
		return err
	}
	data, err := e.postText(e.http, e.authURL, enc)
	if err != nil {
		return fmt.Errorf("login request: %w", err)
	}
	dec, err := e.sess.decrypt(data)
	if err != nil {
		return fmt.Errorf("login 解密失败: %w", err)
	}
	e.keepURL = portal.ExtractXMLTag(dec, "keep-url")
	e.termURL = portal.ExtractXMLTag(dec, "term-url")
	e.keepRetry = portal.ExtractXMLTag(dec, "keep-retry")
	if e.keepURL == "" {
		return fmt.Errorf("登录失败（无 keep-url）: %.160s", dec)
	}
	return nil
}

// heartbeat 心跳；服务端可用 <interval> 更新间隔。
func (e *Engine) heartbeat() error {
	payload := fmt.Sprintf(`<?xml version="1.0" encoding="utf-8"?>
<request>
    <user-agent>%s</user-agent>
    <client-id>%s</client-id>
    <local-time>%s</local-time>
    <host-name>%s</host-name>
    <ipv4>%s</ipv4>
    <ticket>%s</ticket>
    <ipv6></ipv6>
    <mac>%s</mac>
    <ostag>%s</ostag>
</request>`,
		xmlEscape(e.currentUA()), e.clientID, localTime(), e.hostName,
		e.userIP, e.ticket, e.mac, e.hostName)

	enc, err := e.sess.encrypt(payload)
	if err != nil {
		return err
	}
	data, err := e.postText(e.http, e.keepURL, enc)
	if err != nil {
		return fmt.Errorf("keepalive request: %w", err)
	}
	dec, err := e.sess.decrypt(data)
	if err != nil {
		return fmt.Errorf("keepalive 解密失败: %w", err)
	}
	if iv := portal.ExtractXMLTag(dec, "interval"); iv != "" {
		if _, perr := strconv.Atoi(iv); perr == nil {
			e.keepRetry = iv
		}
	}
	return nil
}

// term 登出（用户登出或切号前调用）。
func (e *Engine) term() error {
	if e.termURL == "" || !e.sess.isInitialized() {
		return nil
	}
	payload := fmt.Sprintf(`<?xml version="1.0" encoding="utf-8"?>
<request>
    <user-agent>%s</user-agent>
    <client-id>%s</client-id>
    <local-time>%s</local-time>
    <host-name>%s</host-name>
    <ipv4>%s</ipv4>
    <ticket>%s</ticket>
    <ipv6></ipv6>
    <mac>%s</mac>
    <ostag>%s</ostag>
</request>`,
		xmlEscape(e.currentUA()), e.clientID, localTime(), e.hostName,
		e.userIP, e.ticket, e.mac, e.hostName)

	enc, err := e.sess.encrypt(payload)
	if err != nil {
		return err
	}
	_, err = e.postText(e.http, e.termURL, enc)
	return err
}

// ---------- 小工具 ----------

// localTime 东八区 yyyy-MM-dd HH:mm:ss。
func localTime() string {
	loc := time.FixedZone("CST", 8*3600)
	return time.Now().In(loc).Format("2006-01-02 15:04:05")
}

const macCharset = "0123456789abcdef"

func randomMAC() string {
	b := make([]byte, 6)
	_, _ = rand.Read(b)
	b[0] &= 0xFE // 单播
	parts := make([]string, 6)
	for i, v := range b {
		parts[i] = string([]byte{macCharset[v>>4], macCharset[v&0x0f]})
	}
	return strings.Join(parts, ":")
}

const alnum = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

func randomString(n int) string {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	for i := range b {
		b[i] = alnum[int(b[i])%len(alnum)]
	}
	return string(b)
}
