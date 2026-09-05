package portal_test

import (
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/EchoPing07/better-esurfing-for-android/core/mockportal"
	"github.com/EchoPing07/better-esurfing-for-android/core/portal"
)

type fakeState struct {
	mu               sync.Mutex
	clientID         string
	schoolID, domain string
	area             string
}

func (s *fakeState) GetClientID() string  { return "test-client-id" }
func (s *fakeState) GetSchoolID() string  { s.mu.Lock(); defer s.mu.Unlock(); return s.schoolID }
func (s *fakeState) GetDomain() string    { s.mu.Lock(); defer s.mu.Unlock(); return s.domain }
func (s *fakeState) GetArea() string      { s.mu.Lock(); defer s.mu.Unlock(); return s.area }
func (s *fakeState) SetSchoolID(v string) { s.mu.Lock(); s.schoolID = v; s.mu.Unlock() }
func (s *fakeState) SetDomain(v string)   { s.mu.Lock(); s.domain = v; s.mu.Unlock() }
func (s *fakeState) SetArea(v string)     { s.mu.Lock(); s.area = v; s.mu.Unlock() }

func TestDetectFullChain(t *testing.T) {
	mock := mockportal.New()
	defer mock.Close()

	st := &fakeState{}
	client := portal.NewClient(st, portal.UAAndroid2104, 3*time.Second, nopLog{})

	res := portal.Detect(client, st, portal.Options{
		ProbeURLs: []string{mock.URL() + "/generate_204"},
		UserAgent: portal.UAAndroid2104,
	})
	if res.Status != portal.StatusRequireAuthorization {
		t.Fatalf("status = %v, want RequireAuthorization", res.Status)
	}
	if !strings.HasSuffix(res.AuthURL, "/auth") || !strings.Contains(res.TicketURL, "/ticket?") {
		t.Fatalf("urls = %q %q", res.AuthURL, res.TicketURL)
	}
	if res.UserIP != "10.20.30.40" || res.AcIP != "172.16.0.1" {
		t.Fatalf("ips = %q %q", res.UserIP, res.AcIP)
	}
	if st.schoolID != "GDTEST01" || st.domain != "campus.gd.cn" || st.area != "GZ" {
		t.Fatalf("cdc headers not collected: %+v", st)
	}
}

func TestDetectOpenNetwork(t *testing.T) {
	mock := mockportal.New()
	mock.SetRequireAuth(false)
	defer mock.Close()

	client := portal.NewClient(&fakeState{}, portal.UAAndroid2104, 3*time.Second, nopLog{})
	res := portal.Detect(client, &fakeState{}, portal.Options{
		ProbeURLs: []string{mock.URL() + "/generate_204"},
	})
	if res.Status != portal.StatusSuccess {
		t.Fatalf("status = %v, want Success", res.Status)
	}
}

// 域名映射：探针 URL 使用不可解析域名，映射表改写到本地 mock 的 127.0.0.1。
func TestDomainMapRewrite(t *testing.T) {
	mock := mockportal.New()
	defer mock.Close()

	host := strings.TrimPrefix(mock.URL(), "http://") // 127.0.0.1:PORT
	port := host[strings.LastIndex(host, ":")+1:]

	client := portal.NewClient(&fakeState{}, portal.UAAndroid2104, 3*time.Second, nopLog{})
	res := portal.Detect(client, &fakeState{}, portal.Options{
		ProbeURLs: []string{"http://enet.10000.gd.cn:" + port + "/generate_204"},
		DomainMap: map[string]string{"enet.10000.gd.cn": "127.0.0.1"},
	})
	if res.Status != portal.StatusRequireAuthorization {
		t.Fatalf("status = %v, want RequireAuthorization", res.Status)
	}
}

func TestParsePortalConfigFuncfg(t *testing.T) {
	cfg := `
<auth-url>http://x/auth</auth-url>
<ticket-url>http://x/ticket?wlanuserip=1.2.3.4&amp;wlanacip=5.6.7.8</ticket-url>
<funcfg>
<QueryVerificateCodeStatus url="http://x/sms/status" enable="1"/>
<QueryAuthCode url="http://x/sms/send" enable="0"/>
</funcfg>`
	res := portal.DetectConfigString(cfg)
	if res.Status != portal.StatusRequireAuthorization {
		t.Fatalf("status=%v", res.Status)
	}
	if res.ExtraCfgURL["QueryVerificateCodeStatus"] != "http://x/sms/status" {
		t.Fatalf("funcfg parse wrong: %v", res.ExtraCfgURL)
	}
	if _, ok := res.ExtraCfgURL["QueryAuthCode"]; ok {
		t.Fatalf("disabled funcfg should not be included")
	}
}

func TestExtractXMLTagCDATA(t *testing.T) {
	got := portal.ExtractXMLTag("<a><![CDATA[hello world]]></a>", "a")
	if got != "hello world" {
		t.Fatalf("got %q", got)
	}
}

type nopLog struct{}

func (nopLog) Logf(string, ...any) {}
