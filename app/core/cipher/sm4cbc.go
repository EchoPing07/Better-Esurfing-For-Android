package cipher

import (
	"crypto/cipher"

	"github.com/emmansun/gmsm/sm4"
)

// SM4CBC SM4-CBC 加密：先零填充到 16 字节，再 PKCS5 补齐（历史填充语义）。
type SM4CBC struct {
	key, iv []byte
}

func NewSM4CBC(key, iv []byte) *SM4CBC {
	return &SM4CBC{key: key, iv: iv}
}

func pkcs5Pad(data []byte, blockSize int) []byte {
	padding := blockSize - len(data)%blockSize
	padText := make([]byte, padding)
	for i := range padText {
		padText[i] = byte(padding)
	}
	return append(data, padText...)
}

func pkcs5Unpad(data []byte) []byte {
	if len(data) == 0 {
		return data
	}
	padding := int(data[len(data)-1])
	if padding > len(data) || padding == 0 {
		return data
	}
	return data[:len(data)-padding]
}

func (s *SM4CBC) Encrypt(text string) string {
	// 历史语义：先补零到 16 字节对齐，PKCS5 再追加一个整块
	data := padZero([]byte(text), sm4.BlockSize)
	padded := pkcs5Pad(data, sm4.BlockSize)
	block, err := sm4.NewCipher(s.key)
	if err != nil {
		panic(err)
	}
	mode := cipher.NewCBCEncrypter(block, s.iv)
	encrypted := make([]byte, len(padded))
	mode.CryptBlocks(encrypted, padded)
	return hexEncode(encrypted)
}

func (s *SM4CBC) Decrypt(hexStr string) string {
	data, err := hexDecode(hexStr)
	if err != nil || len(data) == 0 {
		return ""
	}
	block, err := sm4.NewCipher(s.key)
	if err != nil {
		panic(err)
	}
	mode := cipher.NewCBCDecrypter(block, s.iv)
	decrypted := make([]byte, len(data))
	mode.CryptBlocks(decrypted, data)
	unpadded := pkcs5Unpad(decrypted)
	return string(stripTrailingZeros(unpadded))
}
