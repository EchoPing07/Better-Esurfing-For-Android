// Package portal 实现天翼校园网 CCTP 强制门户探测与门户配置解析。
//
// 移植自 xxmod/EsurfingGo（MIT）network 包，并按本项目文档 docs/01 做了工程化改造：
//   - 多探针 URL 依次尝试；
//   - 手动跟随 HTTP 重定向链并在每一跳收集 CDC-* 路由头（兼容裸头变体）；
//   - 跟随页面内 JS 跳转（location.href 等）；
//   - 域名→IP 映射表（校园网内 DNS 无法解析门户域名时改写 URL，保留 Host 头）；
//   - 解析门户配置块（auth-url / ticket-url / funcfg）。
package portal

import (
	"crypto/md5"
	"encoding/hex"
	"fmt"
	"io"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"strings"
	"time"
)

const (
	// UA 预设表（对齐 Esurfing-go-webui/xml.go；实测语义见 Esurfing 仓库 docs/05 §12）。
	// 服务端按 UA 家族分派算法池且按会话随机轮换：2104→新代 9、2089→旧代 9、
	// 1003→PC 6；2093 已被 UA 准入闸拒绝（Error-Code 3），保留作最老学校兜底。
	UAAndroid2104 = "CCTP/android11_64/2104"  // 主选：新代 9 池（官方 App v4.0.2104 同源）
	UAAndroid2089 = "CCTP/android64_vpn/2089" // 回退①：旧代 9 池（本校 + WYU 实测）
	UAAndroid2093 = "CCTP/android64_vpn/2093" // 回退②：历史默认，更老的学校兜底
	UAPCLinux64   = "CCTP/Linux64/1003"       // PC 通道：PC 6 池
	UAAuto        = "auto"                    // 配置值：自动回退链（缺省）

	requestAccept = "text/html,text/xml,application/xhtml+xml,application/x-javascript,*/*"

	portalStartTag = "<!--//config.campus.js.chinatelecom.com"
	portalEndTag   = "//config.campus.js.chinatelecom.com-->"

	maxRedirects = 6
)

// DefaultUAChain 自动模式的 UA 回退链：每级都有实测落点。
// 握手被拒 / ZSM 空体或不可解析 / 未知算法 GUID 时换下一个 UA 整链重试。
var DefaultUAChain = []string{UAAndroid2104, UAAndroid2089, UAAndroid2093}

// ValidUserAgent 校验自定义 UA：非空、≤64 字符、无控制字符与空白
// （HTTP 头与 XML 报文体双用途，空白会破坏报文格式）。
func ValidUserAgent(ua string) bool {
	if ua == "" || len(ua) > 64 {
		return false
	}
	for _, r := range ua {
		if r < 0x20 || r == 0x7f || r == ' ' {
			return false
		}
	}
	return true
}

// Status 表示探测结论。
type Status int

const (
	// StatusSuccess 网络已连通（无需认证）。
	StatusSuccess Status = iota
	// StatusRequireAuthorization 需要认证（已解析出门户配置）。
	StatusRequireAuthorization
	// StatusRequestError 探测失败（网络错误 / 非本协议门户等）。
	StatusRequestError
)

// ConfigResult 是一次探测的完整结果。
type ConfigResult struct {
	Status      Status
	AuthURL     string
	TicketURL   string
	UserIP      string // ticket-url query 中的 wlanuserip
	AcIP        string // ticket-url query 中的 wlanacip
	SchoolID    string
	Domain      string
	Area        string
	ExtraCfgURL map[string]string // funcfg 中 enable=1 的扩展功能 URL
	FinalURL    string            // 重定向链终点 URL（诊断用）

	// Unusable 为 true 表示拿到了 2xx 页面但解析不出配置块（或配置块缺
	// auth-url/ticket-url）：多半是页面形态与 UA 族不匹配，属 UA 回退类失败；
	// 与网络层失败（StatusRequestError 且 Unusable=false）区分开。
	Unusable bool
}

// State 提供探测过程中需要读写的会话级路由状态。
type State interface {
	GetClientID() string
	GetSchoolID() string
	GetDomain() string
	GetArea() string
	SetSchoolID(string)
	SetDomain(string)
	SetArea(string)
}

// Options 探测配置。
type Options struct {
	ProbeURLs []string          // 探测 URL 列表（依次尝试）
	UserAgent string            // 本轮认证使用的 UA（门户页形态随 UA 族不同，须与后续握手同源）
	DomainMap map[string]string // 域名 → 公网 IP 映射（DNS 不通时改写）
	Timeout   time.Duration     // 单请求超时，默认 10s
	Logger    Logger
}

// Logger 探测日志出口。
type Logger interface {
	Logf(format string, args ...any)
}

type nopLogger struct{}

func (nopLogger) Logf(string, ...any) {}

func (o *Options) fill() {
	if len(o.ProbeURLs) == 0 {
		o.ProbeURLs = []string{"http://connect.rom.miui.com/generate_204"}
	}
	if o.UserAgent == "" {
		o.UserAgent = DefaultUAChain[0]
	}
	if o.Timeout <= 0 {
		o.Timeout = 10 * time.Second
	}
	if o.Logger == nil {
		o.Logger = nopLogger{}
	}
}

// ---------- CDC 路由头收集型 HTTP 客户端 ----------

// redirectTransport 手动处理重定向：每一跳都检查并记录 CDC-* 头（及裸变体），
// 后续跳携带已知的路由头。不使用自动重定向（CheckRedirect 返回 ErrUseLastResponse）。
type redirectTransport struct {
	inner http.RoundTripper
	state State
	ua    string
	log   Logger
}

func (t *redirectTransport) RoundTrip(req *http.Request) (*http.Response, error) {
	resp, err := t.inner.RoundTrip(req)
	if err != nil {
		return nil, err
	}
	for i := 0; i < maxRedirects && isRedirect(resp.StatusCode); i++ {
		t.collectCDC(resp)

		location := resp.Header.Get("Location")
		if location == "" {
			t.log.Logf("[redirect] #%d %d -> empty Location, stop", i+1, resp.StatusCode)
			break
		}
		next, err := req.URL.Parse(location)
		if err != nil {
			resp.Body.Close()
			return nil, fmt.Errorf("parse redirect location %q: %w", location, err)
		}
		t.log.Logf("[redirect] #%d %d -> %s", i+1, resp.StatusCode, next)

		var body io.ReadCloser
		if req.Body != nil {
			if req.GetBody != nil {
				body, err = req.GetBody()
			} else {
				err = fmt.Errorf("request body not replayable")
			}
			if err != nil {
				resp.Body.Close()
				return nil, err
			}
		}
		resp.Body.Close()

		nreq, err := http.NewRequestWithContext(req.Context(), req.Method, next.String(), body)
		if err != nil {
			return nil, err
		}
		nreq.Header = req.Header.Clone()
		if s := t.state.GetSchoolID(); s != "" && nreq.Header.Get("CDC-SchoolId") == "" {
			nreq.Header.Set("CDC-SchoolId", s)
		}
		if d := t.state.GetDomain(); d != "" && nreq.Header.Get("CDC-Domain") == "" {
			nreq.Header.Set("CDC-Domain", d)
		}
		if a := t.state.GetArea(); a != "" && nreq.Header.Get("CDC-Area") == "" {
			nreq.Header.Set("CDC-Area", a)
		}
		req = nreq
		resp, err = t.inner.RoundTrip(req)
		if err != nil {
			return nil, err
		}
	}
	return resp, nil
}

func isRedirect(code int) bool {
	return code == 301 || code == 302 || code == 303 || code == 307 || code == 308
}

func getCDCHeader(resp *http.Response, name string) string {
	if v := resp.Header.Get("CDC-" + name); v != "" {
		return v
	}
	return resp.Header.Get(name)
}

func (t *redirectTransport) collectCDC(resp *http.Response) {
	for _, it := range []struct {
		name string
		set  func(string)
	}{
		{"SchoolId", t.state.SetSchoolID},
		{"Domain", t.state.SetDomain},
		{"Area", t.state.SetArea},
	} {
		if v := getCDCHeader(resp, it.name); v != "" {
			it.set(v)
			t.log.Logf("[redirect] header %s=%s", strings.ToLower(it.name), v)
		}
	}
}

// NewClient 构造带路由头收集能力的 HTTP 客户端（含 CookieJar、手动重定向）。
func NewClient(state State, userAgent string, timeout time.Duration, log Logger) *http.Client {
	if timeout <= 0 {
		timeout = 10 * time.Second
	}
	jar, _ := cookiejar.New(nil)
	return &http.Client{
		Transport: &redirectTransport{inner: http.DefaultTransport, state: state, ua: userAgent, log: log},
		Timeout:   timeout,
		Jar:       jar,
		CheckRedirect: func(*http.Request, []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}
}

// ---------- 探测主流程 ----------

// Detect 按探针列表依次探测，识别强制门户并解析门户配置。
// 全部探针失败时：只要有一个探针拿到"2xx 但无配置块"的页面（Unusable），
// 就返回该结果——页面形态与 UA 族不匹配属 UA 回退类失败，供上层换 UA 裁决；
// 纯网络失败则返回无标志的 StatusRequestError。
func Detect(client *http.Client, state State, opt Options) ConfigResult {
	opt.fill()
	var (
		lastErrStatus Status = StatusRequestError
		lastUnusable  ConfigResult
		anyUnusable   bool
	)
	for _, probe := range opt.ProbeURLs {
		res := detectOne(client, state, probe, opt)
		if res.Status != StatusRequestError {
			return res
		}
		if res.Unusable {
			anyUnusable = true
			lastUnusable = res
		}
		lastErrStatus = res.Status
		opt.Logger.Logf("[detect] probe %s failed, trying next", probe)
	}
	if anyUnusable {
		return lastUnusable
	}
	return ConfigResult{Status: lastErrStatus}
}

func detectOne(client *http.Client, state State, probeURL string, opt Options) ConfigResult {
	current := applyDomainMap(probeURL, opt.DomainMap, opt.Logger)
	var (
		resp         *http.Response
		content      string
		portalConfig string
		err          error
	)
	exhausted := true // 循环自然耗尽 = JS 跳转环（非页面形态问题）
	for attempt := 0; attempt < maxRedirects; attempt++ {
		req, rerr := http.NewRequest(http.MethodGet, current, nil)
		if rerr != nil {
			return ConfigResult{Status: StatusRequestError}
		}
		req.Header.Set("User-Agent", opt.UserAgent)
		req.Header.Set("Accept", requestAccept)
		req.Header.Set("Client-ID", state.GetClientID())

		resp, err = client.Do(req)
		if err != nil {
			opt.Logger.Logf("[detect] GET %s error: %v", current, err)
			return ConfigResult{Status: StatusRequestError}
		}
		b, rerr := io.ReadAll(resp.Body)
		resp.Body.Close()
		if rerr != nil {
			return ConfigResult{Status: StatusRequestError}
		}
		content = string(b)

		if resp.StatusCode == http.StatusNoContent {
			return ConfigResult{Status: StatusSuccess}
		}
		if resp.StatusCode < 200 || resp.StatusCode >= 400 {
			opt.Logger.Logf("[detect] unexpected status %d from %s", resp.StatusCode, current)
			return ConfigResult{Status: StatusRequestError}
		}

		// 本响应也提取一次 CDC 头（transport 只在重定向时收集）
		collectFromResponse(resp, state)

		if pc := extractBetween(content, portalStartTag, portalEndTag); pc != "" {
			portalConfig = pc
			exhausted = false
			break
		}
		js := extractJSRedirect(content)
		if js == "" {
			exhausted = false
			break
		}
		// 相对跳转基于最终请求 URL 解析
		base := current
		if resp.Request != nil && resp.Request.URL != nil {
			base = resp.Request.URL.String()
		}
		if ref, perr := url.Parse(base); perr == nil {
			if abs, rerr := ref.Parse(js); rerr == nil {
				js = abs.String()
			}
		}
		current = applyDomainMap(js, opt.DomainMap, opt.Logger)
	}

	if portalConfig == "" {
		if exhausted {
			// JS 跳转环耗尽：与 go-webui "too many redirects" 同语义，按普通失败处理
			opt.Logger.Logf("[detect] js redirect loop exhausted (final=%s)", current)
			return ConfigResult{Status: StatusRequestError}
		}
		// 2xx 终页但无门户配置：可能是其他门户、已连通的普通页面，
		// 或该 UA 族的异形页面 —— 标记 Unusable 供上层做 UA 回退裁决
		opt.Logger.Logf("[detect] no portal config found (final=%s)", current)
		return ConfigResult{Status: StatusRequestError, Unusable: true}
	}

	res := parsePortalConfig(portalConfig)
	res.FinalURL = current
	if res.Status != StatusRequireAuthorization {
		return res
	}
	// 路由头以 transport 收集到的为准，其次最后一跳响应
	for _, it := range []struct {
		get func() string
		hdr string
		dst *string
	}{
		{state.GetSchoolID, "SchoolId", &res.SchoolID},
		{state.GetDomain, "Domain", &res.Domain},
		{state.GetArea, "Area", &res.Area},
	} {
		if v := it.get(); v != "" {
			*it.dst = v
		} else if resp != nil {
			if v := getCDCHeader(resp, it.hdr); v != "" {
				*it.dst = v
			}
		}
	}
	return res
}

func collectFromResponse(resp *http.Response, state State) {
	if v := getCDCHeader(resp, "SchoolId"); v != "" {
		state.SetSchoolID(v)
	}
	if v := getCDCHeader(resp, "Domain"); v != "" {
		state.SetDomain(v)
	}
	if v := getCDCHeader(resp, "Area"); v != "" {
		state.SetArea(v)
	}
}

// parsePortalConfig 从门户配置块解析 auth-url/ticket-url/funcfg 与 IP 参数。
func parsePortalConfig(config string) ConfigResult {
	res := ConfigResult{Status: StatusRequireAuthorization, ExtraCfgURL: map[string]string{}}
	// 部分门户输出 HTML 转义的 &amp;，统一还原
	xu := func(s string) string { return strings.ReplaceAll(s, "&amp;", "&") }
	res.AuthURL = xu(extractXMLTag(config, "auth-url"))
	res.TicketURL = xu(extractXMLTag(config, "ticket-url"))
	parseFuncCfg(config, res.ExtraCfgURL)
	if res.AuthURL == "" || res.TicketURL == "" {
		return ConfigResult{Status: StatusRequestError, Unusable: true}
	}
	u, err := url.Parse(res.TicketURL)
	if err != nil {
		return ConfigResult{Status: StatusRequestError}
	}
	res.UserIP = u.Query().Get("wlanuserip")
	res.AcIP = u.Query().Get("wlanacip")
	if res.UserIP == "" || res.AcIP == "" {
		return ConfigResult{Status: StatusRequestError}
	}
	return res
}

// applyDomainMap 若 URL host 命中映射表则改写为映射 IP（保留原域名作 Host 头语义由调用方保证；
// 这里直接改写 URL host，同时保留 path/query）。
func applyDomainMap(rawURL string, m map[string]string, log Logger) string {
	if len(m) == 0 {
		return rawURL
	}
	u, err := url.Parse(rawURL)
	if err != nil {
		return rawURL
	}
	if ip, ok := m[u.Hostname()]; ok && ip != "" {
		nu := *u
		if u.Port() != "" {
			nu.Host = ip + ":" + u.Port()
		} else {
			nu.Host = ip
		}
		log.Logf("[detect] domain map: %s -> %s", u.Host, nu.Host)
		return nu.String()
	}
	return rawURL
}

// ---------- 小工具 ----------

// extractBetween 提取 startTag 与 endTag 之间的内容。
func extractBetween(text, startTag, endTag string) string {
	i := strings.Index(text, startTag)
	if i == -1 {
		return ""
	}
	i += len(startTag)
	j := strings.Index(text[i:], endTag)
	if j == -1 {
		return ""
	}
	return text[i : i+j]
}

// ExtractXMLTag 简单 XML 标签值提取，兼容 CDATA 包裹。（导出供 engine 复用）
func ExtractXMLTag(xml, tag string) string { return extractXMLTag(xml, tag) }

func extractXMLTag(xml, tag string) string {
	startTag := "<" + tag + ">"
	endTag := "</" + tag + ">"
	i := strings.Index(xml, startTag)
	if i == -1 {
		return ""
	}
	i += len(startTag)
	j := strings.Index(xml[i:], endTag)
	if j == -1 {
		return ""
	}
	v := xml[i : i+j]
	const cp, cs = "<![CDATA[", "]]>"
	if strings.HasPrefix(v, cp) && strings.HasSuffix(v, cs) {
		v = v[len(cp) : len(v)-len(cs)]
	}
	return v
}

// extractJSRedirect 从 HTML 中提取 JS 跳转 URL。
func extractJSRedirect(content string) string {
	for _, pat := range []string{
		`location.href="`, `location.replace("`, `window.location="`, `window.location.href="`,
	} {
		i := strings.Index(content, pat)
		if i == -1 {
			continue
		}
		s := i + len(pat)
		e := strings.Index(content[s:], `"`)
		if e > 0 {
			return content[s : s+e]
		}
	}
	return ""
}

// parseFuncCfg 解析 <funcfg> 内 enable="1" 且带 url 属性的元素。
func parseFuncCfg(config string, result map[string]string) {
	pos := 0
	for {
		fs := strings.Index(config[pos:], "<funcfg>")
		if fs == -1 {
			break
		}
		fe := strings.Index(config[pos+fs:], "</funcfg>")
		if fe == -1 {
			break
		}
		section := config[pos+fs : pos+fs+fe+len("</funcfg>")]
		parseFuncCfgElements(section, result)
		pos += fs + fe + len("</funcfg>")
	}
}

func parseFuncCfgElements(section string, result map[string]string) {
	pos := 0
	for pos < len(section) {
		ts := strings.Index(section[pos:], "<")
		if ts == -1 {
			break
		}
		te := strings.Index(section[pos+ts:], ">")
		if te == -1 {
			break
		}
		elem := section[pos+ts : pos+ts+te+1]
		if strings.Contains(elem, `enable="1"`) && strings.Contains(elem, `url="`) {
			ne := strings.IndexAny(elem[1:], " />")
			if ne > 0 {
				name := elem[1 : 1+ne]
				us := strings.Index(elem, `url="`)
				if us != -1 {
					us += 5
					ue := strings.Index(elem[us:], `"`)
					if ue > 0 {
						result[name] = elem[us : us+ue]
					}
				}
			}
		}
		pos += ts + te + 1
	}
}

// MD5HexLower 计算小写 hex MD5（CDC-Checksum 用）。
func MD5HexLower(s string) string {
	h := md5.Sum([]byte(s))
	return hex.EncodeToString(h[:])
}

// DetectConfigString 直接解析门户配置块文本（诊断/测试用）。
func DetectConfigString(config string) ConfigResult {
	return parsePortalConfig(config)
}
