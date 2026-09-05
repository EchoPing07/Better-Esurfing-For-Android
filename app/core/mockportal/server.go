// Package mockportal 提供本地模拟 CCTP 强制门户服务器，用于引擎端到端测试。
//
// 流程与真实门户一致：
//
//	GET  /generate_204        → 302（携带 CDC-* 路由头）→ JS 跳转页 → 门户配置页
//	POST /ticket              → 首次返回 ZSM 二进制；其后处理加密的 GetTicket XML
//	POST /auth                → 处理加密 Login XML，返回 keep/term URL
//	POST /keep                → 心跳（可注入故障）
//	POST /term                → 登出
package mockportal

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"

	"github.com/EchoPing07/better-esurfing-for-android/core/cipher"
)

const testAlgoID = "CAFBCBAD-B6E7-4CAB-8A67-14D39F00CE1E" // AES-128-CBC
const zeroAlgoID = "00000000-0000-0000-0000-000000000000"
const ticketValue = "MOCKTICKET-0123456789abcdef"

// Server 模拟门户。
type Server struct {
	srv *httptest.Server

	mu            sync.Mutex
	requireAuth   bool
	keepFailing   bool
	logins        int
	keeps         int
	terms         int
	lastUser      string
	lastPass      string
	ticketReqs    int
	wrongPassword string // 非空时记录最近一次错误密码登录

	// UA 回退链测试面：按 UA 拒绝握手（Error-Code 3 + 空体）、按 UA 定制下发
	// GUID、GUID 池轮换（真实服务端按 UA 家族分池且会话级随机轮换）。
	algoIDs       []string          // ZSM 下发 GUID 池（空 = 固定 testAlgoID）
	algoByUA      map[string]string // 指定 UA 强制下发的 GUID（优先于池）
	rejectedUAs   map[string]bool   // 握手被拒的 UA（Error-Code 3 + 空体）
	unusableUAs   map[string]bool   // 指定 UA 返回无配置块门户页（页面形态不匹配）
	strict        bool              // 严格握手：body 非全零 UUID → Error-Code 1 + 空体（默认开启）
	failTickets   int               // 待注入的加密 Ticket 请求失败次数（500）
	zsmCount      int
	handshakeUAs  []string // 每次 ZSM 握手的 UA（按到达顺序）
	keepUAs       []string // 每次心跳请求的 UA（按到达顺序）
	lastTermUA    string   // 最近一次 term 请求的 UA
	lastAuthXMLUA string   // 最近一次 login 报文内解密出的 <user-agent>（已反转义）
}

// New 启动模拟门户（默认要求认证、心跳正常、接受任意 UA、固定下发 testAlgoID、
// 严格握手——与真实服务端一致：非全零 UUID 的握手 body 被 Error-Code 1 拒绝）。
func New() *Server {
	m := &Server{
		requireAuth: true,
		algoByUA:    map[string]string{},
		rejectedUAs: map[string]bool{},
		unusableUAs: map[string]bool{},
		strict:      true,
	}
	mux := http.NewServeMux()
	mux.HandleFunc("/generate_204", m.handleProbe)
	mux.HandleFunc("/portal/step1", m.handleStep1)
	mux.HandleFunc("/portal/index.html", m.handlePortalPage)
	mux.HandleFunc("/ticket", m.handleTicket)
	mux.HandleFunc("/auth", m.handleAuth)
	mux.HandleFunc("/keep", m.handleKeep)
	mux.HandleFunc("/term", m.handleTerm)
	m.srv = httptest.NewServer(mux)
	return m
}

// Close 关闭服务器。
func (m *Server) Close() { m.srv.Close() }

// URL 服务器根地址。
func (m *Server) URL() string { return m.srv.URL }

// ---- 测试控制面 ----

func (m *Server) SetRequireAuth(v bool) { m.mu.Lock(); m.requireAuth = v; m.mu.Unlock() }
func (m *Server) SetKeepFailing(v bool) { m.mu.Lock(); m.keepFailing = v; m.mu.Unlock() }
func (m *Server) Logins() int           { m.mu.Lock(); defer m.mu.Unlock(); return m.logins }
func (m *Server) Keeps() int            { m.mu.Lock(); defer m.mu.Unlock(); return m.keeps }
func (m *Server) Terms() int            { m.mu.Lock(); defer m.mu.Unlock(); return m.terms }
func (m *Server) LastUser() (string, string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.lastUser, m.lastPass
}

// SetAlgoIDs 设置 ZSM 下发 GUID 池（按会话轮换）；空恢复固定 testAlgoID。
func (m *Server) SetAlgoIDs(ids ...string) {
	m.mu.Lock()
	m.algoIDs = ids
	m.zsmCount = 0
	m.mu.Unlock()
}

// SetAlgoIDForUA 指定 UA 强制下发某 GUID（优先于池；空串取消）。
func (m *Server) SetAlgoIDForUA(ua, guid string) {
	m.mu.Lock()
	if guid == "" {
		delete(m.algoByUA, ua)
	} else {
		m.algoByUA[ua] = guid
	}
	m.mu.Unlock()
}

// SetRejectedUAs 设置握手被拒的 UA（Error-Code 3 + 空体，对齐实测拒绝形态）。
func (m *Server) SetRejectedUAs(uas ...string) {
	m.mu.Lock()
	m.rejectedUAs = map[string]bool{}
	for _, ua := range uas {
		m.rejectedUAs[ua] = true
	}
	m.mu.Unlock()
}

// SetStrictHandshake 覆盖严格握手默认值（默认开启；关闭仅用于模拟不校验的异常门户）。
func (m *Server) SetStrictHandshake(v bool) { m.mu.Lock(); m.strict = v; m.mu.Unlock() }

// SetUnusableUAs 指定 UA 的门户配置页返回无配置块的普通页面（对齐
// "门户页形态随 UA 族不同"的实测形态），驱动引擎的页面不可用回退路径。
func (m *Server) SetUnusableUAs(uas ...string) {
	m.mu.Lock()
	m.unusableUAs = map[string]bool{}
	for _, ua := range uas {
		m.unusableUAs[ua] = true
	}
	m.mu.Unlock()
}

// SetFailTickets 注入接下来 N 次加密 Ticket 请求失败（500），驱动
// "握手成功但 Ticket 失败 → 自动重试"路径（重试不得携带上一轮 AlgoID）。
func (m *Server) SetFailTickets(n int) { m.mu.Lock(); m.failTickets = n; m.mu.Unlock() }

// HandshakeUAs 返回历次 ZSM 握手使用的 UA（按到达顺序）。
func (m *Server) HandshakeUAs() []string {
	m.mu.Lock()
	defer m.mu.Unlock()
	return append([]string(nil), m.handshakeUAs...)
}

// KeepUAs 返回历次心跳请求的 UA（按到达顺序）。
func (m *Server) KeepUAs() []string {
	m.mu.Lock()
	defer m.mu.Unlock()
	return append([]string(nil), m.keepUAs...)
}

// LastTermUA 返回最近一次 term 请求的 UA。
func (m *Server) LastTermUA() string {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.lastTermUA
}

// LastAuthXMLUA 返回最近一次 login 报文内解密出的 <user-agent>（已做实体反转义，
// 用于断言引擎的 XML 转义无损回读）。
func (m *Server) LastAuthXMLUA() string {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.lastAuthXMLUA
}

// xmlUnescape 报文内文本的实体反转义（与引擎 xmlEscape 对应；&amp; 必须最后替换）。
func xmlUnescape(s string) string {
	return strings.NewReplacer(
		"&lt;", "<", "&gt;", ">", "&quot;", `"`, "&apos;", "'", "&amp;", "&",
	).Replace(s)
}

// ---- 探测链 ----

func (m *Server) handleProbe(w http.ResponseWriter, r *http.Request) {
	m.mu.Lock()
	auth := m.requireAuth
	m.mu.Unlock()
	if !auth {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	w.Header().Set("CDC-SchoolId", "GDTEST01")
	w.Header().Set("CDC-Domain", "campus.gd.cn")
	w.Header().Set("CDC-Area", "GZ")
	w.Header().Set("Location", m.srv.URL+"/portal/step1")
	w.WriteHeader(http.StatusFound)
}

func (m *Server) handleStep1(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/html")
	fmt.Fprint(w, `<html><script>location.href="/portal/index.html"</script></html>`)
}

func (m *Server) handlePortalPage(w http.ResponseWriter, r *http.Request) {
	m.mu.Lock()
	unusable := m.unusableUAs[r.Header.Get("User-Agent")]
	m.mu.Unlock()
	if unusable {
		// 2xx 但无门户配置块：引擎应归为页面形态与 UA 不匹配并回退
		w.Header().Set("Content-Type", "text/html")
		fmt.Fprint(w, `<!DOCTYPE html><html><head><title>portal</title></head><body>plain page</body></html>`)
		return
	}
	base := m.srv.URL
	w.Header().Set("Content-Type", "text/html")
	fmt.Fprintf(w, `<!DOCTYPE html><html><head>
<!--//config.campus.js.chinatelecom.com
<portal-status>1</portal-status>
<auth-url>%s/auth</auth-url>
<ticket-url>%s/ticket?wlanuserip=10.20.30.40&amp;wlanacip=172.16.0.1</ticket-url>
<funcfg>
<QueryVerificateCodeStatus url="%s/sms/status" enable="0"/>
</funcfg>
//config.campus.js.chinatelecom.com-->
<title>广东电信校园网认证</title></head><body>portal page</body></html>`, base, base, base)
}

// ---- CCTP 业务端点 ----

func (m *Server) handleTicket(w http.ResponseWriter, r *http.Request) {
	body := readAll(r)
	algo := r.Header.Get("Algo-ID")

	// 握手语义：body 是算法 ID 字符串（首轮全零 UUID，重试轮可能是上次值）。
	// 与真实服务端一致：收到算法 ID 即返回新 ZSM 密钥材料；被拒 UA 回
	// 200 + Error-Code: 3 + 空体（docs/05 §2 实测拒绝形态）。
	if isAlgoID(body) {
		ua := r.Header.Get("User-Agent")
		m.mu.Lock()
		m.handshakeUAs = append(m.handshakeUAs, ua)
		rejected := m.rejectedUAs[ua]
		strict := m.strict
		m.mu.Unlock()
		if rejected {
			w.Header().Set("Error-Code", "3")
			w.WriteHeader(http.StatusOK)
			return
		}
		// 严格模式：非全零 UUID 的握手 body 视为协议违规（引擎重认证时
		// 必须归零 AlgoID，带旧 GUID 再握被拒 Error-Code 1）
		if strict && body != zeroAlgoID {
			w.Header().Set("Error-Code", "1")
			w.WriteHeader(http.StatusOK)
			return
		}
		m.zsmResponse(w, ua)
		return
	}

	// 加密 Ticket 请求：按注入计数返回 500
	m.mu.Lock()
	fail := m.failTickets > 0
	if fail {
		m.failTickets--
	}
	m.mu.Unlock()
	if fail {
		http.Error(w, "ticket unavailable", http.StatusServiceUnavailable)
		return
	}

	c, err := cipher.NewCipher(algo)
	if err != nil {
		http.Error(w, "bad algo", http.StatusBadRequest)
		return
	}
	xml := c.Decrypt(string(body))
	if !strings.Contains(xml, "<ipv4>10.20.30.40</ipv4>") || !strings.Contains(xml, "<gwip>172.16.0.1</gwip>") {
		http.Error(w, "unexpected ticket payload", http.StatusBadRequest)
		return
	}
	m.mu.Lock()
	m.ticketReqs++
	m.mu.Unlock()
	resp := `<?xml version="1.0" encoding="utf-8"?><response><resultcode>100</resultcode><ticket>` +
		ticketValue + `</ticket></response>`
	_, _ = w.Write([]byte(c.Encrypt(resp)))
}

// zsmResponse 构造 ZSM 握手二进制响应：4字节头(byte[3]=keyLen) + key + '$' + 36字节UUID + '\'' + extra。
// GUID 取舍：algoByUA[ua] 优先，其次 GUID 池轮换，缺省固定 testAlgoID。
func (m *Server) zsmResponse(w http.ResponseWriter, ua string) {
	m.mu.Lock()
	guid := testAlgoID
	if g, ok := m.algoByUA[ua]; ok {
		guid = g
	} else if len(m.algoIDs) > 0 {
		guid = m.algoIDs[m.zsmCount%len(m.algoIDs)]
		m.zsmCount++
	}
	m.mu.Unlock()

	keyLen := 16
	blob := make([]byte, 0, 4+keyLen+1+36+1+8)
	blob = append(blob, 0x01, 0x02, 0x03, byte(keyLen))
	for i := 0; i < keyLen; i++ {
		blob = append(blob, byte('A'+i%26))
	}
	blob = append(blob, '$')
	blob = append(blob, []byte(guid)...)
	blob = append(blob, '\'')
	blob = append(blob, []byte("EXTRA1234")...)
	w.Header().Set("Content-Type", "application/octet-stream")
	_, _ = w.Write(blob)
}

// isAlgoID 判断字符串是否形如算法 ID（8-4-4-4-12 十六进制 UUID）。
func isAlgoID(s string) bool {
	if len(s) != 36 || s[8] != '-' || s[13] != '-' || s[18] != '-' || s[23] != '-' {
		return false
	}
	for _, c := range s {
		if c == '-' {
			continue
		}
		if !(c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F') {
			return false
		}
	}
	return true
}

func (m *Server) handleAuth(w http.ResponseWriter, r *http.Request) {
	c, err := cipher.NewCipher(r.Header.Get("Algo-ID"))
	if err != nil {
		http.Error(w, "bad algo", http.StatusBadRequest)
		return
	}
	xml := c.Decrypt(readAll(r))
	user := tagValue(xml, "userid")
	pass := tagValue(xml, "passwd")

	m.mu.Lock()
	m.logins++
	if user != "" {
		m.lastUser, m.lastPass = user, pass
	}
	m.lastAuthXMLUA = xmlUnescape(tagValue(xml, "user-agent"))
	bad := pass == "WRONG" || pass == ""
	m.mu.Unlock()

	var resp string
	if bad {
		resp = `<?xml version="1.0" encoding="utf-8"?><response><resultcode>1</resultcode><message>用户名或密码错误</message></response>`
	} else {
		base := m.srv.URL
		resp = fmt.Sprintf(`<?xml version="1.0" encoding="utf-8"?><response><resultcode>100</resultcode>`+
			`<keep-url>%s/keep</keep-url><term-url>%s/term</term-url><keep-retry>1</keep-retry></response>`, base, base)
	}
	_, _ = w.Write([]byte(c.Encrypt(resp)))
}

func (m *Server) handleKeep(w http.ResponseWriter, r *http.Request) {
	m.mu.Lock()
	failing := m.keepFailing
	m.mu.Unlock()
	if failing {
		http.Error(w, "gateway down", http.StatusBadGateway)
		return
	}
	c, err := cipher.NewCipher(r.Header.Get("Algo-ID"))
	if err != nil {
		http.Error(w, "bad algo", http.StatusBadRequest)
		return
	}
	xml := c.Decrypt(readAll(r))
	if !strings.Contains(xml, "<ticket>"+ticketValue+"</ticket>") {
		http.Error(w, "unexpected keepalive payload", http.StatusBadRequest)
		return
	}
	m.mu.Lock()
	m.keeps++
	m.keepUAs = append(m.keepUAs, r.Header.Get("User-Agent"))
	m.mu.Unlock()
	_, _ = w.Write([]byte(c.Encrypt(`<?xml version="1.0" encoding="utf-8"?><response><resultcode>100</resultcode><interval>1</interval></response>`)))
}

func (m *Server) handleTerm(w http.ResponseWriter, r *http.Request) {
	m.mu.Lock()
	m.terms++
	m.lastTermUA = r.Header.Get("User-Agent")
	m.mu.Unlock()
	w.WriteHeader(http.StatusOK)
}

// ---- 工具 ----

func readAll(r *http.Request) string {
	buf := make([]byte, 0, 512)
	tmp := make([]byte, 4096)
	for {
		n, err := r.Body.Read(tmp)
		buf = append(buf, tmp[:n]...)
		if err != nil {
			break
		}
	}
	return string(buf)
}

func tagValue(xml, tag string) string {
	s := "<" + tag + ">"
	e := "</" + tag + ">"
	i := strings.Index(xml, s)
	if i < 0 {
		return ""
	}
	i += len(s)
	j := strings.Index(xml[i:], e)
	if j < 0 {
		return ""
	}
	return xml[i : i+j]
}
