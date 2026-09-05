package cipher

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/des"
	"fmt"

	"github.com/emmansun/gmsm/sm4"
)

// cipher_new.go — 第二代（PC 通道 6 算法）与第三代（新 Android 通道 9 算法）落地。
//
// 结构决策（对齐 Esurfing-go-webui/cipher_new.go）：本文件只新增内容，
// cipher.go 的旧 9 个 GUID 注册项与实现**一行不动**（回归红线）。
// 与 go-webui 不同的是，本仓库旧实现的填充语义与 C 参考存在历史偏差
// （3DES 按 16 字节块补零、SM4 先补零再 PKCS5——服务端去零容忍故一直可用），
// 而新代 KAT 由 C 参考逐字节生成，因此 15 个新 ID 全部使用本文件的
// C 精确语义参数化实现，而非复用旧类型：
//   - AES/3DES/TEA：零填充到 8/16 字节块；
//   - SM4：标准 PKCS7（对齐输入恒补一个整块）；
//   - 密文为大写 HEX，解密去尾部零。
//
// 算法归约证据：算法验证/01-算法归约验证报告（映射组 6 个与旧代逐字节等价——
// 该等价性在 go-webui 成立；本仓库因上述填充偏差，交叉断言仅对 AES 系成立）。
// PC 源：算法验证/reference-c-pc/（BadGhost520/ESurfingClient-CVersion master @7f7618d，
// esp32_esurfing 同源交叉验证；AB6C8EBE 首轮密钥仲裁取 k[1]，docs/07 §4）。

// ── 算法 ID 常量：第三代（新 Android 通道，0ffbf00 同步） ──
const (
	AlgoNewAesCbc     = "BB2EA626-590B-4C42-82BE-E052FCBBB88E" // = 旧 AesCbc 换 GUID
	AlgoNewAesEcb     = "DEABB8C8-A2BC-48CA-8ED0-8CDF1BD62F61" // = 旧 AesEcb 换 GUID
	AlgoNewDesEdeCbc  = "9ABF4D29-34DB-4CE9-BB8C-7E371D637758" // = 旧 DesEdeCbc 换 GUID（blob=k2‖k1 层序绕回）
	AlgoNewDesEdeEcb  = "AD8BB5B0-0E72-4198-A362-96D52C1B7ED1" // = 旧 DesEdeEcb 换 GUID（EDE_k1→EDE_k2）
	AlgoNewSm4Cbc     = "D6544CFE-F2DE-459B-9B77-0F2B367EF169" // = 标准 SM4-CBC（PKCS7）
	AlgoNewSm4Ecb     = "D755A536-B551-468C-BD87-322182B223D4" // = 标准 SM4-ECB（PKCS7）
	AlgoTeaVariantEcb = "319FC5AB-EC0E-46B9-A252-2285F9DAE813" // 变体 TEA 三层 ECB（L3 实战确证）
	AlgoTeaVariantCbc = "35101415-A20F-4DFE-B00B-0B4F3B2F8C66" // 变体 TEA 三层 CBC（层序反转）
	AlgoSnow3gVariant = "07E824B2-9E5C-4D1B-BBB0-5E07C251E4AA" // SNOW3G 变体流（密钥=旧 ZUC）
)

// ── 算法 ID 常量：第二代（PC 通道，CCTP/Linux64/1003） ──
const (
	AlgoPCDesEdeCbc = "1A7343EC-7F9B-4570-BF58-16279A81116B" // 双层 3DES-CBC（k2 层在前，与 Android 旧代层序相反）
	AlgoPCAesEcb    = "4BA5496A-2123-46A7-85F2-35956EA7BE39" // 双层 AES-ECB（E_k2 在前）
	AlgoPCAesCbc    = "45433DCF-9ECA-4BE5-83F2-F92BA0B4F291" // 双层 AES-CBC（双 IV 前缀，IV=全零）
	AlgoPCXTeaEcb   = "60639D8B-272E-4A4D-976E-AA270987A169" // XTEA 三层 ECB（变体 TEA 块函数）
	AlgoPCTeaCbc    = "AB6C8EBE-B8F8-4C08-8222-69A3B5E86A91" // 变体 TEA 三层 CBC（本校 PC 池轮换成员）
	AlgoPCDesSixEcb = "B306E770-B7D5-49F2-A574-BCE2C5C650ED" // DES 六层 ECB（L3 实战确证）
)

// ── 第二代 PC 通道密钥（reference-c-pc/KeyData-pc.c 逐字节转录） ──
var (
	pcDesCbcK1 = []byte{0x3C, 0x27, 0x73, 0x5A, 0x65, 0x2A, 0x54, 0x5E, 0x64, 0x41, 0x3D, 0x57, 0x73, 0x3A, 0x2F, 0x3B, 0x53, 0x28, 0x52, 0x47, 0x52, 0x44, 0x48, 0x51}
	pcDesCbcK2 = []byte{0x43, 0x74, 0x5C, 0x56, 0x5E, 0x6A, 0x22, 0x61, 0x65, 0x6E, 0x7A, 0x7A, 0x61, 0x7A, 0x2F, 0x63, 0x51, 0x44, 0x4E, 0x6B, 0x28, 0x2D, 0x27, 0x2E}
	pcDesCbcIV = []byte{0x41, 0x54, 0x74, 0x5A, 0x4C, 0x5E, 0x42, 0x5D} // iv1 == iv2（KeyData 同值）

	pcAesEcbK1 = []byte{0x77, 0x75, 0x79, 0x3E, 0x4A, 0x47, 0x56, 0x58, 0x6A, 0x5A, 0x53, 0x45, 0x66, 0x48, 0x6F, 0x27}
	pcAesEcbK2 = []byte{0x64, 0x24, 0x55, 0x75, 0x69, 0x6E, 0x2A, 0x25, 0x27, 0x59, 0x6D, 0x69, 0x57, 0x28, 0x65, 0x41}

	pcAesCbcK1 = []byte{0x46, 0x2D, 0x68, 0x78, 0x6B, 0x4F, 0x68, 0x73, 0x41, 0x50, 0x7E, 0x25, 0x22, 0x27, 0x49, 0x6F}
	pcAesCbcK2 = []byte{0x46, 0x58, 0x7D, 0x7A, 0x26, 0x4C, 0x59, 0x57, 0x42, 0x56, 0x48, 0x62, 0x41, 0x4F, 0x5F, 0x55}

	// B306E770 六钥（KeyData.c @0ffbf00；L3 实战确证）
	desSixPcK1 = []byte{0x43, 0x4D, 0x75, 0x3F, 0x3D, 0x54, 0x72, 0x49}
	desSixPcK2 = []byte{0x79, 0x7A, 0x7A, 0x49, 0x23, 0x53, 0x55, 0x51}
	desSixPcK3 = []byte{0x6C, 0x25, 0x5C, 0x7B, 0x41, 0x73, 0x77, 0x62}
	desSixPcK4 = []byte{0x75, 0x6C, 0x6A, 0x28, 0x3A, 0x48, 0x22, 0x68}
	desSixPcK5 = []byte{0x79, 0x24, 0x6C, 0x41, 0x77, 0x6F, 0x52, 0x2A}
	desSixPcK6 = []byte{0x70, 0x6A, 0x45, 0x58, 0x76, 0x73, 0x6D, 0x27}
)

// ═══════════════ 参数化构造器（照 Esurfing-go-webui/cipher_new.go 逐语句搬运，
// 适配本仓库 Encrypt(string) string / Decrypt(string) string 接口） ═══════════════

type aesCbcCipher struct{ k1, k2, iv []byte }

func newAesCbc(k1, k2, iv []byte) *aesCbcCipher { return &aesCbcCipher{k1, k2, iv} }

func (c *aesCbcCipher) Encrypt(text string) string {
	padded := padZero([]byte(text), aes.BlockSize)

	b1, err := aes.NewCipher(c.k1)
	if err != nil {
		panic(err)
	}
	e1 := make([]byte, len(padded))
	cipher.NewCBCEncrypter(b1, c.iv).CryptBlocks(e1, padded)
	r1 := append(append([]byte{}, c.iv...), e1...)

	b2, err := aes.NewCipher(c.k2)
	if err != nil {
		panic(err)
	}
	e2 := make([]byte, len(r1))
	cipher.NewCBCEncrypter(b2, c.iv).CryptBlocks(e2, r1)

	return hexEncode(append(append([]byte{}, c.iv...), e2...))
}

func (c *aesCbcCipher) Decrypt(hexStr string) string {
	ct, err := hexDecode(hexStr)
	if err != nil || len(ct) < 2*aes.BlockSize || (len(ct)-aes.BlockSize)%aes.BlockSize != 0 {
		return ""
	}
	b2, err := aes.NewCipher(c.k2)
	if err != nil {
		panic(err)
	}
	d1 := make([]byte, len(ct)-aes.BlockSize)
	cipher.NewCBCDecrypter(b2, c.iv).CryptBlocks(d1, ct[aes.BlockSize:])
	if len(d1) < aes.BlockSize || (len(d1)-aes.BlockSize)%aes.BlockSize != 0 {
		return ""
	}
	b1, err := aes.NewCipher(c.k1)
	if err != nil {
		panic(err)
	}
	d2 := make([]byte, len(d1)-aes.BlockSize)
	cipher.NewCBCDecrypter(b1, c.iv).CryptBlocks(d2, d1[aes.BlockSize:])
	return string(stripTrailingZeros(d2))
}

type aesEcbCipher struct{ k1, k2 []byte }

func newAesEcb(k1, k2 []byte) *aesEcbCipher { return &aesEcbCipher{k1, k2} }

func (c *aesEcbCipher) Encrypt(text string) string {
	padded := padZero([]byte(text), aes.BlockSize)
	b1, err := aes.NewCipher(c.k1)
	if err != nil {
		panic(err)
	}
	e1 := make([]byte, len(padded))
	for i := 0; i < len(padded); i += aes.BlockSize {
		b1.Encrypt(e1[i:i+aes.BlockSize], padded[i:i+aes.BlockSize])
	}
	b2, err := aes.NewCipher(c.k2)
	if err != nil {
		panic(err)
	}
	e2 := make([]byte, len(e1))
	for i := 0; i < len(e1); i += aes.BlockSize {
		b2.Encrypt(e2[i:i+aes.BlockSize], e1[i:i+aes.BlockSize])
	}
	return hexEncode(e2)
}

func (c *aesEcbCipher) Decrypt(hexStr string) string {
	ct, err := hexDecode(hexStr)
	if err != nil || len(ct) < aes.BlockSize || len(ct)%aes.BlockSize != 0 {
		return ""
	}
	b2, err := aes.NewCipher(c.k2)
	if err != nil {
		panic(err)
	}
	d1 := make([]byte, len(ct))
	for i := 0; i < len(ct); i += aes.BlockSize {
		b2.Decrypt(d1[i:i+aes.BlockSize], ct[i:i+aes.BlockSize])
	}
	b1, err := aes.NewCipher(c.k1)
	if err != nil {
		panic(err)
	}
	d2 := make([]byte, len(d1))
	for i := 0; i < len(d1); i += aes.BlockSize {
		b1.Decrypt(d2[i:i+aes.BlockSize], d1[i:i+aes.BlockSize])
	}
	return string(stripTrailingZeros(d2))
}

type desEdeCbcCipher struct{ k1, k2, iv []byte }

func newDesEdeCbc(k1, k2, iv []byte) *desEdeCbcCipher { return &desEdeCbcCipher{k1, k2, iv} }

func (c *desEdeCbcCipher) Encrypt(text string) string {
	padded := padZero([]byte(text), des.BlockSize)
	b1, err := des.NewTripleDESCipher(c.k1)
	if err != nil {
		panic(err)
	}
	e1 := make([]byte, len(padded))
	cipher.NewCBCEncrypter(b1, c.iv).CryptBlocks(e1, padded)
	b2, err := des.NewTripleDESCipher(c.k2)
	if err != nil {
		panic(err)
	}
	e2 := make([]byte, len(e1))
	cipher.NewCBCEncrypter(b2, c.iv).CryptBlocks(e2, e1)
	return hexEncode(e2)
}

func (c *desEdeCbcCipher) Decrypt(hexStr string) string {
	ct, err := hexDecode(hexStr)
	if err != nil || len(ct) < des.BlockSize || len(ct)%des.BlockSize != 0 {
		return ""
	}
	b2, err := des.NewTripleDESCipher(c.k2)
	if err != nil {
		panic(err)
	}
	d1 := make([]byte, len(ct))
	cipher.NewCBCDecrypter(b2, c.iv).CryptBlocks(d1, ct)
	b1, err := des.NewTripleDESCipher(c.k1)
	if err != nil {
		panic(err)
	}
	d2 := make([]byte, len(d1))
	cipher.NewCBCDecrypter(b1, c.iv).CryptBlocks(d2, d1)
	return string(stripTrailingZeros(d2))
}

type desEdeEcbCipher struct{ k1, k2 []byte }

func newDesEdeEcb(k1, k2 []byte) *desEdeEcbCipher { return &desEdeEcbCipher{k1, k2} }

func (c *desEdeEcbCipher) Encrypt(text string) string {
	padded := padZero([]byte(text), des.BlockSize)
	b1, err := des.NewTripleDESCipher(c.k1)
	if err != nil {
		panic(err)
	}
	e1 := make([]byte, len(padded))
	for i := 0; i < len(padded); i += des.BlockSize {
		b1.Encrypt(e1[i:i+des.BlockSize], padded[i:i+des.BlockSize])
	}
	b2, err := des.NewTripleDESCipher(c.k2)
	if err != nil {
		panic(err)
	}
	e2 := make([]byte, len(e1))
	for i := 0; i < len(e1); i += des.BlockSize {
		b2.Encrypt(e2[i:i+des.BlockSize], e1[i:i+des.BlockSize])
	}
	return hexEncode(e2)
}

func (c *desEdeEcbCipher) Decrypt(hexStr string) string {
	ct, err := hexDecode(hexStr)
	if err != nil || len(ct) < des.BlockSize || len(ct)%des.BlockSize != 0 {
		return ""
	}
	b2, err := des.NewTripleDESCipher(c.k2)
	if err != nil {
		panic(err)
	}
	d1 := make([]byte, len(ct))
	for i := 0; i < len(ct); i += des.BlockSize {
		b2.Decrypt(d1[i:i+des.BlockSize], ct[i:i+des.BlockSize])
	}
	b1, err := des.NewTripleDESCipher(c.k1)
	if err != nil {
		panic(err)
	}
	d2 := make([]byte, len(d1))
	for i := 0; i < len(d1); i += des.BlockSize {
		b1.Decrypt(d2[i:i+des.BlockSize], d1[i:i+des.BlockSize])
	}
	return string(stripTrailingZeros(d2))
}

type sm4CbcCipher struct{ key, iv []byte }

func newSm4Cbc(key, iv []byte) *sm4CbcCipher { return &sm4CbcCipher{key, iv} }

func (c *sm4CbcCipher) Encrypt(text string) string {
	block, err := sm4.NewCipher(c.key)
	if err != nil {
		panic(err)
	}
	padded := pkcs5Pad([]byte(text), block.BlockSize())
	encrypted := make([]byte, len(padded))
	cipher.NewCBCEncrypter(block, c.iv).CryptBlocks(encrypted, padded)
	return hexEncode(encrypted)
}

func (c *sm4CbcCipher) Decrypt(hexStr string) string {
	ct, err := hexDecode(hexStr)
	if err != nil {
		return ""
	}
	block, err := sm4.NewCipher(c.key)
	if err != nil {
		panic(err)
	}
	if len(ct) < block.BlockSize() || len(ct)%block.BlockSize() != 0 {
		return ""
	}
	decrypted := make([]byte, len(ct))
	cipher.NewCBCDecrypter(block, c.iv).CryptBlocks(decrypted, ct)
	// SM4 按 PKCS7 精确长度还原（C 参考不做去尾零——去尾零仅适用零填充算法）
	return string(pkcs5Unpad(decrypted))
}

type sm4EcbCipher struct{ key []byte }

func newSm4Ecb(key []byte) *sm4EcbCipher { return &sm4EcbCipher{key} }

func (c *sm4EcbCipher) Encrypt(text string) string {
	block, err := sm4.NewCipher(c.key)
	if err != nil {
		panic(err)
	}
	padded := pkcs5Pad([]byte(text), block.BlockSize())
	encrypted := make([]byte, len(padded))
	for i := 0; i < len(padded); i += block.BlockSize() {
		block.Encrypt(encrypted[i:i+block.BlockSize()], padded[i:i+block.BlockSize()])
	}
	return hexEncode(encrypted)
}

func (c *sm4EcbCipher) Decrypt(hexStr string) string {
	ct, err := hexDecode(hexStr)
	if err != nil {
		return ""
	}
	block, err := sm4.NewCipher(c.key)
	if err != nil {
		panic(err)
	}
	if len(ct) < block.BlockSize() || len(ct)%block.BlockSize() != 0 {
		return ""
	}
	decrypted := make([]byte, len(ct))
	for i := 0; i < len(ct); i += block.BlockSize() {
		block.Decrypt(decrypted[i:i+block.BlockSize()], ct[i:i+block.BlockSize()])
	}
	return string(pkcs5Unpad(decrypted))
}

// ═══════════════ PC 六层 DES-ECB（第二代 B306E770，实测下发，L3 实战确证） ═══════════════
// 照 reference-c-pc/des_ecb_six_pc.c 层序：
//
//	加密 = E(K4)D(K5)E(K6) 接 E(K1)D(K2)E(K3) = EDE_456 后 EDE_123
//	解密 = D(K3)E(K2)D(K1) 接 D(K6)E(K5)D(K4)（逆序，标准 3DES 逆）
//
// 零填充到 8（空输入 C 返回 NULL，本实现输出空串）；解密去尾零。

type desSixPcCipher struct{ b123, b456 cipher.Block }

func newDesSixPc(k1, k2, k3, k4, k5, k6 []byte) (*desSixPcCipher, error) {
	b123, err := des.NewTripleDESCipher(concat(k1, k2, k3))
	if err != nil {
		return nil, err
	}
	b456, err := des.NewTripleDESCipher(concat(k4, k5, k6))
	if err != nil {
		return nil, err
	}
	return &desSixPcCipher{b123: b123, b456: b456}, nil
}

func (c *desSixPcCipher) Encrypt(text string) string {
	data := []byte(text)
	if len(data) == 0 {
		return "" // C pad_2_multiple 返回 NULL
	}
	padded := padZero(data, des.BlockSize)
	out := make([]byte, len(padded))
	t := make([]byte, des.BlockSize)
	for i := 0; i < len(padded); i += des.BlockSize {
		c.b456.Encrypt(t, padded[i:i+des.BlockSize]) // EDE(K4,K5,K6)
		c.b123.Encrypt(out[i:i+des.BlockSize], t)    // EDE(K1,K2,K3)
	}
	return hexEncode(out)
}

func (c *desSixPcCipher) Decrypt(hexStr string) string {
	ct, err := hexDecode(hexStr)
	if err != nil || len(ct) < des.BlockSize || len(ct)%des.BlockSize != 0 {
		return ""
	}
	out := make([]byte, len(ct))
	t := make([]byte, des.BlockSize)
	for i := 0; i < len(ct); i += des.BlockSize {
		c.b123.Decrypt(t, ct[i:i+des.BlockSize])  // D(K3)E(K2)D(K1)
		c.b456.Decrypt(out[i:i+des.BlockSize], t) // D(K6)E(K5)D(K4)
	}
	return string(stripTrailingZeros(out))
}

func concat(parts ...[]byte) []byte {
	out := make([]byte, 0, 24)
	for _, p := range parts {
		out = append(out, p...)
	}
	return out
}

// ═══════════════ 注册表扩展（三代 24 全集） ═══════════════
// 变体/PC 实例为包级单例：构造后不可变（Encrypt/Decrypt 只读状态），并发安全。

func mustTeaVariant(blob, iv []byte) *TeaVariant {
	c, err := NewTeaVariant(blob, iv)
	if err != nil {
		panic("cipher_new: " + err.Error()) // 常量错误，包初始化即暴露
	}
	return c
}

func mustDesSixPc(k1, k2, k3, k4, k5, k6 []byte) *desSixPcCipher {
	c, err := newDesSixPc(k1, k2, k3, k4, k5, k6)
	if err != nil {
		panic("cipher_new: " + err.Error())
	}
	return c
}

func mustSnow3gVariant(key, iv []byte) *Snow3gVariant {
	c, err := NewSnow3gVariant(key, iv)
	if err != nil {
		panic("cipher_new: " + err.Error()) // 常量错误，包初始化即暴露
	}
	return c
}

var (
	cipherTeaVariantEcb = mustTeaVariant(teaEcbBlob, nil)
	cipherTeaVariantCbc = mustTeaVariant(teaCbcBlob, teaCbcIVBlob[:8])
	cipherSnow3gVariant = mustSnow3gVariant(KeyB809531F_Key, KeyB809531F_IV)
	cipherPCDesSix      = mustDesSixPc(desSixPcK1, desSixPcK2, desSixPcK3, desSixPcK4, desSixPcK5, desSixPcK6)
	cipherPCXTeaEcb     = mustTeaVariant(pcXTeaBlob, nil)
	cipherPCTeaCbc      = mustTeaVariant(pcTeaCbcBlob, pcTeaCbcIV)
)

// newCipherExt 承接第三代 9 + 第二代 6 个新 GUID；cipher.go 的旧 9 项未命中时由此兜底。
func newCipherExt(algoID string) (Cipher, error) {
	switch algoID {
	// ── 第三代：映射组（结构=旧代 C 语义，密钥复用旧常量；算法验证/02 §1）──
	case AlgoNewAesCbc:
		return newAesCbc(KeyCAFBCBAD_Key1, KeyCAFBCBAD_Key2, KeyCAFBCBAD_IV), nil
	case AlgoNewAesEcb:
		return newAesEcb(KeyA474B1C2_Key1, KeyA474B1C2_Key2), nil
	case AlgoNewDesEdeCbc:
		return newDesEdeCbc(Key5BFBA864_Key1, Key5BFBA864_Key2, Key5BFBA864_IV), nil
	case AlgoNewDesEdeEcb:
		return newDesEdeEcb(Key6E0B65FF_Key1, Key6E0B65FF_Key2), nil
	case AlgoNewSm4Cbc:
		return newSm4Cbc(KeyF3974434_Key, KeyF3974434_IV), nil
	case AlgoNewSm4Ecb:
		return newSm4Ecb(KeyED382482_Key), nil

	// ── 第三代：移植组（真变体，逐语句照 reference-c @0ffbf00 转录）──
	case AlgoTeaVariantEcb:
		return cipherTeaVariantEcb, nil // L3 实战确证（docs/05 §11）
	case AlgoTeaVariantCbc:
		return cipherTeaVariantCbc, nil
	case AlgoSnow3gVariant:
		return cipherSnow3gVariant, nil

	// ── 第二代：PC 通道 6 算法 ──
	case AlgoPCDesSixEcb:
		return cipherPCDesSix, nil // L3 实战确证（docs/05 §10）
	// 1A7343EC：C 加密先 CBC_k2 后 CBC_k1（与 Android 旧 5BFBA864 层序相反）→ 密钥交换传入
	case AlgoPCDesEdeCbc:
		return newDesEdeCbc(pcDesCbcK2, pcDesCbcK1, pcDesCbcIV), nil
	// 4BA5496A：C 加密先 E_k2 后 E_k1（与 Android 旧 A474B1C2 层序相反）→ 密钥交换传入
	case AlgoPCAesEcb:
		return newAesEcb(pcAesEcbK2, pcAesEcbK1), nil
	// 45433DCF：内层 CBC_k1（IV 前缀）、外层 CBC_k2（IV 前缀），双 IV 全零
	case AlgoPCAesCbc:
		return newAesCbc(pcAesCbcK1, pcAesCbcK2, make([]byte, 16)), nil
	// 60639D8B：块函数≡新代变体 TEA（加法混合公式、32 轮），加密层序 k1→k2→k3
	case AlgoPCXTeaEcb:
		return cipherPCXTeaEcb, nil
	// AB6C8EBE：层序同新代 35101415（加密 k2→k1→k0）；首轮密钥取通式 k[1]
	// （上游 C 加密首轮写 k[3] 与其自身解密矛盾，判为转录噪声，docs/07 §4；待实战裁决）
	case AlgoPCTeaCbc:
		return cipherPCTeaCbc, nil
	default:
		return nil, fmt.Errorf("unknown algorithm: %s（不在已知 24 个算法 ID 内，可能是新代际，可反馈该 GUID）", algoID)
	}
}

// KnownAlgoIDs 三代全集 24 个算法 ID（旧 9 + 新代 9 + PC 6），供测试与诊断使用。
func KnownAlgoIDs() []string {
	return []string{
		// 旧代 9（Android 通道，2089 池）
		"CAFBCBAD-B6E7-4CAB-8A67-14D39F00CE1E",
		"A474B1C2-3DE0-4EA2-8C5F-7093409CE6C4",
		"5BFBA864-BBA9-42DB-8EAD-49B5F412BD81",
		"6E0B65FF-0B5B-459C-8FCE-EC7F2BEA9FF5",
		"B809531F-0007-4B5B-923B-4BD560398113",
		"F3974434-C0DD-4C20-9E87-DDB6814A1C48",
		"ED382482-F72C-4C41-A76D-28EEA0F1F2AF",
		"B3047D4E-67DF-4864-A6A5-DF9B9E525C79",
		"C32C68F9-CA81-4260-A329-BBAFD1A9CCD1",
		// 新代 9（Android 通道，2104 池）
		AlgoNewAesCbc, AlgoNewAesEcb, AlgoNewDesEdeCbc, AlgoNewDesEdeEcb,
		AlgoNewSm4Cbc, AlgoNewSm4Ecb, AlgoTeaVariantEcb, AlgoTeaVariantCbc, AlgoSnow3gVariant,
		// PC 6（Linux64/1003 池）
		AlgoPCDesEdeCbc, AlgoPCAesEcb, AlgoPCAesCbc, AlgoPCXTeaEcb, AlgoPCTeaCbc, AlgoPCDesSixEcb,
	}
}
