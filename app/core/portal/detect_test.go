package portal_test

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/EchoPing07/better-esurfing-for-android/core/portal"
)

// detect_test.go — Detect 聚合语义测试：Unusable 标志必须穿透探针循环
// 到达调用方（回归锚：曾因聚合时丢标志导致引擎的页面不可用回退成为死代码）。

const noConfigPage = `<!DOCTYPE html><html><head><title>portal</title></head><body>plain page</body></html>`

// jsLoopPage 永远返回 JS 跳转页（跳转环）。
const jsLoopPage = `<html><script>location.href="/next"</script></html>`

func TestDetectUnusableSingleProbe(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html")
		fmt.Fprint(w, noConfigPage)
	}))
	defer srv.Close()

	st := &fakeState{}
	client := portal.NewClient(st, portal.UAAndroid2104, 3*time.Second, nopLog{})
	res := portal.Detect(client, st, portal.Options{
		ProbeURLs: []string{srv.URL},
		UserAgent: portal.UAAndroid2104,
	})
	if res.Status != portal.StatusRequestError || !res.Unusable {
		t.Fatalf("status=%v unusable=%v, want RequestError+Unusable", res.Status, res.Unusable)
	}
}

func TestDetectUnusablePreservedAcrossMixedProbes(t *testing.T) {
	cfgLess := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprint(w, noConfigPage)
	}))
	defer cfgLess.Close()
	unreachable := "http://127.0.0.1:1/" // 连接必然被拒

	st := &fakeState{}
	client := portal.NewClient(st, portal.UAAndroid2104, 2*time.Second, nopLog{})

	// 配置块缺失在前、网络失败在后：聚合结果仍须保留 Unusable
	res := portal.Detect(client, st, portal.Options{
		ProbeURLs: []string{cfgLess.URL, unreachable},
		UserAgent: portal.UAAndroid2104,
	})
	if res.Status != portal.StatusRequestError || !res.Unusable {
		t.Fatalf("configless-first: status=%v unusable=%v, want Unusable kept", res.Status, res.Unusable)
	}

	// 反序同理
	res = portal.Detect(client, st, portal.Options{
		ProbeURLs: []string{unreachable, cfgLess.URL},
		UserAgent: portal.UAAndroid2104,
	})
	if res.Status != portal.StatusRequestError || !res.Unusable {
		t.Fatalf("configless-last: status=%v unusable=%v, want Unusable kept", res.Status, res.Unusable)
	}
}

func TestDetectPureNetworkFailureNotUnusable(t *testing.T) {
	st := &fakeState{}
	client := portal.NewClient(st, portal.UAAndroid2104, 2*time.Second, nopLog{})
	res := portal.Detect(client, st, portal.Options{
		ProbeURLs: []string{"http://127.0.0.1:1/"},
		UserAgent: portal.UAAndroid2104,
	})
	if res.Status != portal.StatusRequestError || res.Unusable {
		t.Fatalf("status=%v unusable=%v, want plain network failure", res.Status, res.Unusable)
	}
}

func TestDetectJSRedirectLoopNotUnusable(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprint(w, jsLoopPage)
	}))
	defer srv.Close()

	st := &fakeState{}
	client := portal.NewClient(st, portal.UAAndroid2104, 3*time.Second, nopLog{})
	res := portal.Detect(client, st, portal.Options{
		ProbeURLs: []string{srv.URL},
		UserAgent: portal.UAAndroid2104,
	})
	// 跳转环与 go-webui "too many redirects" 同语义：普通失败，不触发 UA 回退
	if res.Status != portal.StatusRequestError || res.Unusable {
		t.Fatalf("status=%v unusable=%v, want plain failure for redirect loop", res.Status, res.Unusable)
	}
}
