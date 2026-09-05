package cipher

import (
	"bytes"
	"encoding/hex"
	"strings"
)

// padZero 用零字节填充数据至 blockSize 整数倍（已对齐不补）。
func padZero(data []byte, blockSize int) []byte {
	if len(data)%blockSize == 0 {
		return data
	}
	padded := make([]byte, (len(data)/blockSize+1)*blockSize)
	copy(padded, data)
	return padded
}

// stripTrailingZeros 去除尾部零字节。
func stripTrailingZeros(data []byte) []byte {
	return bytes.TrimRight(data, "\x00")
}

// hexEncode 字节序列编码为大写 HEX 字符串。
func hexEncode(data []byte) string {
	return strings.ToUpper(hex.EncodeToString(data))
}

// hexDecode 解码 HEX 字符串为字节序列。
func hexDecode(s string) ([]byte, error) {
	return hex.DecodeString(s)
}
