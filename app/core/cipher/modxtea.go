package cipher

import (
	"encoding/binary"
)

const (
	xteaNumRounds = 32
	xteaDelta     = 0x9E3779B9 // -1640531527 as uint32
)

// modXTEAEncryptBlock 修改版 XTEA 加密单个 8 字节块。
func modXTEAEncryptBlock(v0In, v1In uint32, key [4]uint32) (uint32, uint32) {
	v0, v1 := v0In, v1In
	var sum uint32
	for i := 0; i < xteaNumRounds; i++ {
		sum += xteaDelta
		v0 += (v1 ^ sum) + key[sum&3] + (v1<<4 ^ v1>>5)
		v1 += key[sum>>11&3] + (v0 ^ sum) + (v0<<4 ^ v0>>5)
	}
	return v0, v1
}

// modXTEADecryptBlock 修改版 XTEA 解密单个 8 字节块。
func modXTEADecryptBlock(v0In, v1In uint32, key [4]uint32) (uint32, uint32) {
	v0, v1 := v0In, v1In
	// sum 累加走 uint32 溢出语义（与 Java 实现一致）
	var sum uint32
	for i := 0; i < xteaNumRounds; i++ {
		sum += xteaDelta
	}
	for i := 0; i < xteaNumRounds; i++ {
		v1 -= key[sum>>11&3] + (v0 ^ sum) + (v0<<4 ^ v0>>5)
		v0 -= (v1 ^ sum) + key[sum&3] + (v1<<4 ^ v1>>5)
		sum -= xteaDelta
	}
	return v0, v1
}

// getUint32BE 从 offset 处按大端读出 uint32。
func getUint32BE(data []byte, offset int) uint32 {
	return binary.BigEndian.Uint32(data[offset:])
}

// setUint32BE 在 offset 处按大端写入 uint32。
func setUint32BE(data []byte, offset int, value uint32) {
	binary.BigEndian.PutUint32(data[offset:], value)
}

// padToMultipleOf8 零填充至 8 字节倍数（已对齐不补）。
func padToMultipleOf8(data []byte) []byte {
	padding := (8 - len(data)%8) % 8
	if padding == 0 {
		return data
	}
	result := make([]byte, len(data)+padding)
	copy(result, data)
	return result
}

// ModXTEA 三密钥 ModXTEA-ECB：加密层序 k1→k2→k3，解密相反。
type ModXTEA struct {
	key1, key2, key3 [4]uint32
}

func NewModXTEA(key1, key2, key3 [4]uint32) *ModXTEA {
	return &ModXTEA{key1: key1, key2: key2, key3: key3}
}

func (m *ModXTEA) Encrypt(text string) string {
	blocks := padToMultipleOf8([]byte(text))
	for i := 0; i+7 < len(blocks); i += 8 {
		v0 := getUint32BE(blocks, i)
		v1 := getUint32BE(blocks, i+4)
		v0, v1 = modXTEAEncryptBlock(v0, v1, m.key1)
		v0, v1 = modXTEAEncryptBlock(v0, v1, m.key2)
		v0, v1 = modXTEAEncryptBlock(v0, v1, m.key3)
		setUint32BE(blocks, i, v0)
		setUint32BE(blocks, i+4, v1)
	}
	return hexEncode(blocks)
}

func (m *ModXTEA) Decrypt(hexStr string) string {
	blocks, err := hexDecode(hexStr)
	if err != nil || len(blocks) == 0 {
		return ""
	}
	for i := 0; i+7 < len(blocks); i += 8 {
		v0 := getUint32BE(blocks, i)
		v1 := getUint32BE(blocks, i+4)
		v0, v1 = modXTEADecryptBlock(v0, v1, m.key3)
		v0, v1 = modXTEADecryptBlock(v0, v1, m.key2)
		v0, v1 = modXTEADecryptBlock(v0, v1, m.key1)
		setUint32BE(blocks, i, v0)
		setUint32BE(blocks, i+4, v1)
	}
	return string(stripTrailingZeros(blocks))
}
