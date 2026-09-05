package cipher

import (
	"encoding/binary"
	"fmt"
)

// tea_variant.go — 变体 TEA 三层加密（新代 319FC5AB/35101415 与 PC 60639D8B/AB6C8EBE 共用块函数）。
//
// 逐语句移植自 算法验证/reference-c/tea_triple_ecb_android.c / tea_triple_cbc_android.c
// @ Ironjhin/EsurfingClient_Android 0ffbf00（git blob SHA 校验，上游经 unicorn 对官方 .so 验证），
// 与 Esurfing-go-webui/cipher_tea.go 同源（该校 319FC5AB 全链路实战确证，docs/05 §11）。
//
// 关键陷阱（照 C 实现，禁止"顺手优化"——每处与 oracle 的差异都必须能被 KAT 解释）：
//  1. delta = 0x61C88647（即 -0x9E3779B9），sum 从 0 递减至 0xC6EF3720，32 轮；
//  2. 解密层 kidx 初值 0x28B7BD67（=31δ，来自 asm；IDA 伪码此处是错的，不可由加密代数逆推）；
//  3. CBC 加密层序 k2→k1→k0、解密层序 k0→k1→k2（与 ECB 相反）；
//  4. 空输入补一个 8B 全零块（C 语义）；
//  5. blob 字节按 BigEndian 读出即旧 uint32 常量值（cipher_test.go 断言此等价性）。

const teaVariantDelta = uint32(0x61C88647)

func teaEncLayer(v0, v1 uint32, k *[4]uint32) (uint32, uint32) {
	a, b := v0, v1
	sum := uint32(0)
	for {
		a += (b ^ sum) + k[sum&3] + ((b << 4) ^ (b >> 5))
		sum -= teaVariantDelta
		b += k[(sum>>11)&3] + (a ^ sum) + ((a << 4) ^ (a >> 5))
		if sum == 0xC6EF3720 {
			break
		}
	}
	return a, b
}

func teaDecLayer(v0, v1 uint32, k *[4]uint32) (uint32, uint32) {
	a, b := v0, v1
	sum := uint32(0xC6EF3720)
	kidx := uint32(0x28B7BD67) // -31*delta（来自 asm；IDA 伪码此处是错的）
	for {
		b -= (a ^ sum) + ((a << 4) ^ (a >> 5)) + k[(sum>>11)&3]
		sum += teaVariantDelta
		a -= k[kidx&3] + (b ^ kidx) + ((b << 4) ^ (b >> 5))
		kidx += teaVariantDelta
		if sum == 0 {
			break
		}
	}
	return a, b
}

// TeaVariant 实现变体 TEA 三层 ECB（iv=nil）或 CBC（iv=8 字节）。
type TeaVariant struct {
	k  [3][4]uint32
	iv []byte // CBC（8B）；ECB 为 nil
}

// NewTeaVariant 从 48B blob 装载三层密钥：
// C: k[l][i] = bswap32(native-LE word of blob) ≡ Go BigEndian 读。
func NewTeaVariant(blob, iv []byte) (*TeaVariant, error) {
	if len(blob) != 48 {
		return nil, fmt.Errorf("tea blob len %d != 48", len(blob))
	}
	if iv != nil && len(iv) != 8 {
		return nil, fmt.Errorf("tea iv len %d != 8", len(iv))
	}
	t := &TeaVariant{iv: iv}
	for l := 0; l < 3; l++ {
		for i := 0; i < 4; i++ {
			t.k[l][i] = binary.BigEndian.Uint32(blob[l*16+i*4 : l*16+i*4+4])
		}
	}
	return t, nil
}

func (t *TeaVariant) Encrypt(text string) string {
	data := []byte(text)
	total := (len(data) + 7) &^ 7
	if total == 0 {
		total = 8 // C 语义：空输入补一个全零块
	}
	buf := make([]byte, total)
	copy(buf, data)
	if t.iv == nil {
		for off := 0; off < total; off += 8 {
			v0 := binary.BigEndian.Uint32(buf[off : off+4])
			v1 := binary.BigEndian.Uint32(buf[off+4 : off+8])
			v0, v1 = teaEncLayer(v0, v1, &t.k[0])
			v0, v1 = teaEncLayer(v0, v1, &t.k[1])
			v0, v1 = teaEncLayer(v0, v1, &t.k[2])
			binary.BigEndian.PutUint32(buf[off:], v0)
			binary.BigEndian.PutUint32(buf[off+4:], v1)
		}
	} else {
		prev := make([]byte, 8)
		copy(prev, t.iv)
		for off := 0; off < total; off += 8 {
			v0 := binary.BigEndian.Uint32(buf[off:off+4]) ^ binary.BigEndian.Uint32(prev[0:4])
			v1 := binary.BigEndian.Uint32(buf[off+4:off+8]) ^ binary.BigEndian.Uint32(prev[4:8])
			// ⚠️ CBC 加密层序 k2→k1→k0（与 ECB 相反）
			v0, v1 = teaEncLayer(v0, v1, &t.k[2])
			v0, v1 = teaEncLayer(v0, v1, &t.k[1])
			v0, v1 = teaEncLayer(v0, v1, &t.k[0])
			binary.BigEndian.PutUint32(buf[off:], v0)
			binary.BigEndian.PutUint32(buf[off+4:], v1)
			copy(prev, buf[off:off+8])
		}
	}
	return hexEncode(buf)
}

func (t *TeaVariant) Decrypt(hexStr string) string {
	ct, err := hexDecode(hexStr)
	if err != nil || len(ct) < 8 || len(ct)%8 != 0 {
		return ""
	}
	buf := make([]byte, len(ct))
	if t.iv == nil {
		for off := 0; off < len(ct); off += 8 {
			v0 := binary.BigEndian.Uint32(ct[off : off+4])
			v1 := binary.BigEndian.Uint32(ct[off+4 : off+8])
			v0, v1 = teaDecLayer(v0, v1, &t.k[2])
			v0, v1 = teaDecLayer(v0, v1, &t.k[1])
			v0, v1 = teaDecLayer(v0, v1, &t.k[0])
			binary.BigEndian.PutUint32(buf[off:], v0)
			binary.BigEndian.PutUint32(buf[off+4:], v1)
		}
	} else {
		prev := make([]byte, 8)
		copy(prev, t.iv)
		for off := 0; off < len(ct); off += 8 {
			v0 := binary.BigEndian.Uint32(ct[off : off+4])
			v1 := binary.BigEndian.Uint32(ct[off+4 : off+8])
			// ⚠️ CBC 解密层序 k0→k1→k2
			v0, v1 = teaDecLayer(v0, v1, &t.k[0])
			v0, v1 = teaDecLayer(v0, v1, &t.k[1])
			v0, v1 = teaDecLayer(v0, v1, &t.k[2])
			v0 ^= binary.BigEndian.Uint32(prev[0:4])
			v1 ^= binary.BigEndian.Uint32(prev[4:8])
			binary.BigEndian.PutUint32(buf[off:], v0)
			binary.BigEndian.PutUint32(buf[off+4:], v1)
			copy(prev, ct[off:off+8]) // prev = 输入密文块
		}
	}
	return string(stripTrailingZeros(buf))
}

// ═══════════════ 密钥 blob ═══════════════
// 第三代 KeyData（reference-c/KeyData.c @ 0ffbf00 原样字节）。
// TEA blob 字节 = 旧 uint32 常量的大端镜像（C 按 native-LE 读字再 bswap 还原数值，
// 等价于 Go 直接 BigEndian 读）；cipher_test.go 断言此等价性。

var (
	// 319FC5AB：= 旧 XTea(B3047D4E) key1‖key2‖key3 大端镜像
	teaEcbBlob = []byte{
		0x7A, 0x7A, 0x67, 0x6A, 0x27, 0x7E, 0x4A, 0x73, 0x3E, 0x43, 0x29, 0x6C, 0x57, 0x7D, 0x7D, 0x7A,
		0x3D, 0x3C, 0x69, 0x5F, 0x71, 0x79, 0x7A, 0x74, 0x44, 0x5F, 0x57, 0x63, 0x6F, 0x69, 0x27, 0x65,
		0x5B, 0x5A, 0x68, 0x3D, 0x2E, 0x57, 0x2A, 0x77, 0x4A, 0x47, 0x44, 0x65, 0x66, 0x3D, 0x7E, 0x5C,
	}
	// 35101415：= 旧 XTeaIv(C32C68F9) key1‖key2‖key3 大端镜像
	teaCbcBlob = []byte{
		0x79, 0x6D, 0x78, 0x55, 0x29, 0x7B, 0x23, 0x55, 0x58, 0x7D, 0x72, 0x6E, 0x4D, 0x3D, 0x44, 0x23,
		0x7C, 0x70, 0x52, 0x5D, 0x5A, 0x58, 0x5D, 0x3D, 0x41, 0x3E, 0x40, 0x29, 0x28, 0x75, 0x5D, 0x6A,
		0x42, 0x5E, 0x5F, 0x6E, 0x46, 0x75, 0x4E, 0x24, 0x50, 0x7B, 0x23, 0x3D, 0x2D, 0x64, 0x46, 0x41,
	}
	// 16B blob，实际仅前 8B 生效（C struct iv[8] 拷贝前 8B）
	teaCbcIVBlob = []byte{0x54, 0x4C, 0x2F, 0x3F, 0x6F, 0x48, 0x51, 0x21, 0xA3, 0x76, 0xB0, 0x9B, 0x61, 0xF0, 0x96, 0x39}
)

// PC XTEA/TEA 算法（60639D8B/AB6C8EBE）：C 侧密钥为 uint32 数组，块函数内部逐字
// bswap32 后使用 → Go 侧 blob = 各 uint32 的小端序列化（BigEndian 读出即 bswap 值）。
var (
	pcXTeaBlob = []byte{ // 60639D8B key1‖key2‖key3（三组 4×uint32 LE 序列化）
		0x6B, 0x59, 0x29, 0x52, 0x62, 0x5E, 0x5D, 0x2F, 0x3C, 0x5C, 0x50, 0x5E, 0x7E, 0x59, 0x41, 0x78,
		0x3E, 0x6E, 0x21, 0x21, 0x4F, 0x68, 0x3A, 0x27, 0x46, 0x49, 0x5C, 0x4B, 0x55, 0x4E, 0x78, 0x6A,
		0x44, 0x7C, 0x52, 0x5B, 0x54, 0x43, 0x21, 0x24, 0x41, 0x2E, 0x60, 0x22, 0x2D, 0x3F, 0x42, 0x56,
	}
	pcTeaCbcBlob = []byte{ // AB6C8EBE key1‖key2‖key3（三组 4×uint32 LE 序列化）
		0x70, 0x3A, 0x25, 0x46, 0x3E, 0x65, 0x54, 0x73, 0x60, 0x62, 0x3A, 0x4C, 0x3C, 0x4F, 0x47, 0x79,
		0x56, 0x26, 0x68, 0x65, 0x64, 0x44, 0x55, 0x76, 0x6A, 0x4C, 0x28, 0x79, 0x6C, 0x2B, 0x21, 0x4D,
		0x40, 0x56, 0x2F, 0x7D, 0x40, 0x52, 0x63, 0x4B, 0x2F, 0x7E, 0x6F, 0x4D, 0x7D, 0x28, 0x76, 0x5B,
	}
	pcTeaCbcIV = []byte{0x48, 0x28, 0x23, 0x7E, 0x3B, 0x51, 0x2D, 0x3E} // AB6C8EBE iv 两字 LE 序列化（前 8B）
)
