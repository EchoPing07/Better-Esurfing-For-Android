package cipher

import (
	"crypto/aes"
	"crypto/cipher"
)

// AESCBC 双层 AES-CBC：先 k1 后 k2，两层均前置 IV。
type AESCBC struct {
	key1, key2, iv []byte
}

func NewAESCBC(key1, key2, iv []byte) *AESCBC {
	return &AESCBC{key1: key1, key2: key2, iv: iv}
}

func (a *AESCBC) aesEncrypt(data, key []byte) []byte {
	padded := padZero(data, aes.BlockSize)
	block, err := aes.NewCipher(key)
	if err != nil {
		panic(err)
	}
	mode := cipher.NewCBCEncrypter(block, a.iv)
	encrypted := make([]byte, len(padded))
	mode.CryptBlocks(encrypted, padded)
	// 前置 IV
	return append(append([]byte{}, a.iv...), encrypted...)
}

func (a *AESCBC) aesDecrypt(data, key []byte) []byte {
	block, err := aes.NewCipher(key)
	if err != nil {
		panic(err)
	}
	mode := cipher.NewCBCDecrypter(block, a.iv)
	decrypted := make([]byte, len(data))
	mode.CryptBlocks(decrypted, data)
	return decrypted
}

func (a *AESCBC) Encrypt(text string) string {
	r1 := a.aesEncrypt([]byte(text), a.key1)
	r2 := a.aesEncrypt(r1, a.key2)
	return hexEncode(r2)
}

func (a *AESCBC) Decrypt(hexStr string) string {
	data, err := hexDecode(hexStr)
	if err != nil || len(data) < 32 {
		return ""
	}
	// 每层先剥离前 16 字节 IV
	r1 := a.aesDecrypt(data[16:], a.key2)
	if len(r1) < 16 {
		return ""
	}
	r2 := a.aesDecrypt(r1[16:], a.key1)
	return string(stripTrailingZeros(r2))
}
