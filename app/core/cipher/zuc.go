package cipher

import (
	"github.com/emmansun/gmsm/zuc"
)

// ZUCCipher ZUC-128 流密码：加解密同为与密钥流异或。
type ZUCCipher struct {
	key, iv []byte
}

func NewZUC(key, iv []byte) *ZUCCipher {
	return &ZUCCipher{key: key, iv: iv}
}

func (z *ZUCCipher) processZUC(input []byte) []byte {
	c, err := zuc.NewCipher(z.key, z.iv)
	if err != nil {
		panic(err)
	}
	output := make([]byte, len(input))
	c.XORKeyStream(output, input)
	return output
}

func (z *ZUCCipher) Encrypt(text string) string {
	data := []byte(text)
	// 补零到 4 字节倍数
	if len(data)%4 != 0 {
		padded := make([]byte, (len(data)/4+1)*4)
		copy(padded, data)
		data = padded
	}
	return hexEncode(z.processZUC(data))
}

func (z *ZUCCipher) Decrypt(hexStr string) string {
	data, err := hexDecode(hexStr)
	if err != nil || len(data) == 0 {
		return ""
	}
	result := z.processZUC(data)
	return string(stripTrailingZeros(result))
}
