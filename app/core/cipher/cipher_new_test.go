package cipher

import (
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// cipher_new_test.go — 新增 15 个算法（新代 9 + PC 6）的 L1 测试。
//
// 覆盖（对齐 Esurfing-go-webui/cipher_test.go）：
//  1. KAT 对拍：testdata/kat/*.json（15 文件，真值由 kat_oracle.py 从
//     reference-c @0ffbf00 / reference-c-pc 独立转录生成），加解密双向逐字节断言；
//  2. 注册表 24 ID 全量 roundtrip（含边界长度）；
//  3. 未知 GUID 返回错误（引擎据此触发 UA 回退）；
//  4. 交叉断言：AES 系映射组新 GUID 与对应旧 GUID 同明文输出一致（同钥同构）；
//     3DES/SM4 系因旧实现历史填充偏差不做字节级断言（见 cipher_new.go 头注）；
//  5. 密钥等价性：新代 TEA blob / SNOW3G 密钥与旧代常量的镜像关系。

type katCase struct {
	Pt    string `json:"pt"`
	Ct    string `json:"ct"`
	PtOut string `json:"pt_out"`
}

type katDoc struct {
	Algo  string    `json:"algo"`
	Kind  string    `json:"kind"`
	Cases []katCase `json:"cases"`
}

func mustKatHex(t *testing.T, s string) []byte {
	t.Helper()
	b, err := hex.DecodeString(strings.TrimSpace(s))
	if err != nil {
		t.Fatalf("bad hex %q: %v", s, err)
	}
	return b
}

// TestKATVectors 逐字节对拍全部 KAT（加密方向 + 解密方向）
func TestKATVectors(t *testing.T) {
	files, err := filepath.Glob(filepath.Join("testdata", "kat", "*.json"))
	if err != nil || len(files) == 0 {
		t.Fatalf("no KAT files found: %v", err)
	}
	if len(files) != 15 {
		t.Fatalf("expected 15 KAT files (9 new-gen + 6 PC), got %d", len(files))
	}

	for _, file := range files {
		raw, err := os.ReadFile(file)
		if err != nil {
			t.Fatalf("read %s: %v", file, err)
		}
		var doc katDoc
		if err := json.Unmarshal(raw, &doc); err != nil {
			t.Fatalf("parse %s: %v", file, err)
		}
		if len(doc.Cases) == 0 {
			t.Fatalf("%s: empty cases", file)
		}

		c, err := NewCipher(doc.Algo)
		if err != nil {
			t.Fatalf("%s: algo %s not registered: %v", file, doc.Algo, err)
		}

		for i, tc := range doc.Cases {
			pt := mustKatHex(t, tc.Pt)

			ct := c.Encrypt(string(pt))
			if !strings.EqualFold(ct, tc.Ct) {
				t.Fatalf("%s case%d: encrypt mismatch\n  pt=%s\n  got=%s\n  want=%s",
					doc.Algo, i, tc.Pt, shortKatHex(ct), shortKatStr(tc.Ct))
			}

			dec := c.Decrypt(tc.Ct)
			if dec != string(mustKatHex(t, tc.PtOut)) {
				t.Fatalf("%s case%d: decrypt mismatch\n  got=%q\n  want=%s", doc.Algo, i, dec, tc.PtOut)
			}
		}
	}
}

func shortKatHex(s string) string {
	if len(s) > 48 {
		return s[:48] + "…"
	}
	return s
}

func shortKatStr(s string) string {
	if len(s) > 48 {
		return s[:48] + "…"
	}
	return s
}

// TestRegistry24IDs 注册表必须恰好覆盖 24 个 GUID（三代全集）
func TestRegistry24IDs(t *testing.T) {
	ids := KnownAlgoIDs()
	if len(ids) != 24 {
		t.Fatalf("registry must hold 24 algo ids (old 9 + new 9 + pc 6), got %d", len(ids))
	}
	seen := map[string]bool{}
	for _, id := range ids {
		if seen[id] {
			t.Fatalf("duplicate algo id: %s", id)
		}
		seen[id] = true
		if _, err := NewCipher(id); err != nil {
			t.Fatalf("NewCipher(%s): %v", id, err)
		}
	}
}

// TestRoundTripNewAlgorithms 新增 15 ID 全部加密→解密还原
func TestRoundTripNewAlgorithms(t *testing.T) {
	samples := []string{
		"<?xml version=\"1.0\" encoding=\"UTF-8\"?><request><user-agent>CCTP/android11_64/2104</user-agent><client-id>4395a527-1111-2222-3333-444444444444</client-id></request>",
		"a",
		"12345678",               // 8B 对齐
		"123456789",              // 8B+1
		"0123456789abcdef",       // 16B 对齐
		"\x00a\x00bc",            // 含零（不以零结尾：去零语义会截断尾部 NUL，协议 XML 不存在此形态）
		strings.Repeat("x", 257), // 跨多块
		"",                       // 空输入（SNOW3G/B306E770 输出空；其余按补块/前缀语义自洽）
	}

	for _, id := range KnownAlgoIDs()[9:] { // 新代 9 + PC 6
		c, err := NewCipher(id)
		if err != nil {
			t.Fatalf("algo %s: %v", id, err)
		}
		for _, pt := range samples {
			ct := c.Encrypt(pt)
			dec := c.Decrypt(ct)
			if dec != pt {
				t.Fatalf("algo %s: roundtrip mismatch (len=%d)\n  got=%q\n  want=%q", id, len(pt), dec, pt)
			}
		}
	}
}

// TestUnknownAlgoIDReturnsError 未知 GUID 必须报错且错误串含完整 GUID（诊断/回退触发条件）
func TestUnknownAlgoIDReturnsError(t *testing.T) {
	for _, id := range []string{
		"376412D4-0000-0000-0000-000000000000", // Windows 族未知 GUID（uaprobe 实测形态）
		"634D7C6F-0000-0000-0000-000000000000", // Mac 族未知 GUID
		"",
		"not-a-guid",
	} {
		c, err := NewCipher(id)
		if err == nil || c != nil {
			t.Fatalf("NewCipher(%q) must return error, got cipher=%v err=%v", id, c, err)
		}
		if id != "" && !strings.Contains(err.Error(), id) {
			t.Fatalf("error must carry the full GUID: %v", err)
		}
	}
}

// TestMappedNewEqualsOldAES 交叉断言：AES 系映射组新 GUID 输出 == 对应旧 GUID 输出
// （同钥同构的直接证明；3DES/SM4 系因旧实现历史填充偏差不做字节级断言）
func TestMappedNewEqualsOldAES(t *testing.T) {
	pairs := []struct{ newID, oldID string }{
		{AlgoNewAesCbc, "CAFBCBAD-B6E7-4CAB-8A67-14D39F00CE1E"},
		{AlgoNewAesEcb, "A474B1C2-3DE0-4EA2-8C5F-7093409CE6C4"},
	}
	pts := []string{
		"<?xml version=\"1.0\"?><request><ticket>990d847136ffac085d5c45b22e3a4eff</ticket></request>",
		"12345678",
		"1234567",
		strings.Repeat("y", 33),
	}
	for _, p := range pairs {
		nc, err := NewCipher(p.newID)
		if err != nil {
			t.Fatalf("%s: %v", p.newID, err)
		}
		oc, err := NewCipher(p.oldID)
		if err != nil {
			t.Fatalf("%s: %v", p.oldID, err)
		}
		for _, pt := range pts {
			nct := nc.Encrypt(pt)
			oct := oc.Encrypt(pt)
			if nct != oct {
				t.Fatalf("mapped pair mismatch: new %s != old %s\n  new=%s\n  old=%s",
					p.newID, p.oldID, shortKatHex(nct), shortKatHex(oct))
			}
		}
	}
}

// TestNewGenKeyEquivalence 新代密钥 blob 与旧代常量的等价性（算法验证/02 §1.1 的运行时证明）
func TestNewGenKeyEquivalence(t *testing.T) {
	// 319FC5AB blob = 旧 XTea(B3047D4E) 三钥大端镜像
	if !teaBlobEqualsOld(teaEcbBlob, KeyB3047D4E_Key1, KeyB3047D4E_Key2, KeyB3047D4E_Key3) {
		t.Fatal("319FC5AB blob != old XTea keys (big-endian mirror)")
	}
	// 35101415 blob = 旧 XTeaIv(C32C68F9) 三钥大端镜像
	if !teaBlobEqualsOld(teaCbcBlob, KeyC32C68F9_Key1, KeyC32C68F9_Key2, KeyC32C68F9_Key3) {
		t.Fatal("35101415 blob != old XTeaIv keys (big-endian mirror)")
	}
	// 35101415 iv[0:8] = 旧 XTeaIv IV 大端镜像
	ivBlob := teaCbcIVBlob[:8]
	if binary.BigEndian.Uint32(ivBlob[0:4]) != KeyC32C68F9_IV[0] ||
		binary.BigEndian.Uint32(ivBlob[4:8]) != KeyC32C68F9_IV[1] {
		t.Fatal("35101415 iv != old XTeaIv IV (big-endian mirror)")
	}
	// 07E824B2 密钥 = 旧 ZUC(B809531F) 字节原样（registry 已用同一常量，此处防御性断言）
	if !byteSlicesEqual(cipherSnow3gVariant.key, KeyB809531F_Key) ||
		!byteSlicesEqual(cipherSnow3gVariant.iv, KeyB809531F_IV) {
		t.Fatal("07E824B2 key/iv != old ZUC bytes")
	}
}

// teaBlobEqualsOld 断言 48B blob 按 BigEndian 读出 == 三组旧 uint32 密钥
func teaBlobEqualsOld(blob []byte, k1, k2, k3 [4]uint32) bool {
	if len(blob) != 48 {
		return false
	}
	for l, keys := range [][4]uint32{k1, k2, k3} {
		for i := 0; i < 4; i++ {
			if binary.BigEndian.Uint32(blob[l*16+i*4:l*16+i*4+4]) != keys[i] {
				return false
			}
		}
	}
	return true
}

func byteSlicesEqual(a, b []byte) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}
