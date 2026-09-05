package cipher

import (
	"github.com/emmansun/gmsm/sm4"
)

// SM4ECB SM4-ECB 加密：先零填充到 16 字节，再 PKCS5 补齐（历史填充语义）。
type SM4ECB struct {
	key []byte
}

func NewSM4ECB(key []byte) *SM4ECB {
	return &SM4ECB{key: key}
}

func (s *SM4ECB) Encrypt(text string) string {
	data := padZero([]byte(text), sm4.BlockSize)
	padded := pkcs5Pad(data, sm4.BlockSize)
	block, err := sm4.NewCipher(s.key)
	if err != nil {
		panic(err)
	}
	encrypted := ecbEncrypt(block, padded)
	return hexEncode(encrypted)
}

func (s *SM4ECB) Decrypt(hexStr string) string {
	data, err := hexDecode(hexStr)
	if err != nil || len(data) == 0 {
		return ""
	}
	block, err := sm4.NewCipher(s.key)
	if err != nil {
		panic(err)
	}
	decrypted := ecbDecrypt(block, data)
	unpadded := pkcs5Unpad(decrypted)
	return string(stripTrailingZeros(unpadded))
}
