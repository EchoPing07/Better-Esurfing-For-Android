package engine

import (
	"errors"
	"fmt"
	"strings"
	"sync"

	"github.com/EchoPing07/better-esurfing-for-android/core/cipher"
)

// session 封装一次 CCTP 会话的加密器：ZSM 握手响应 → 解析算法 ID → 选择固定密钥加密器。
type session struct {
	mu          sync.Mutex
	initialized bool
	cipher      cipher.Cipher
	algoID      string
}

// parseZSMAlgoID 从 ZSM 二进制响应中提取 36 字节算法 ID（纯函数，便于诊断测试）。
// 格式（docs/01 §4）：
//
//	[4 字节头，其中 byte[3]=keyLen] [keyLen 字节 key] ['$'] [36 字节 UUID=Algo-ID] ['\''] [其余忽略]
//
// 算法 ID 是服务端可控字节且会进错误日志：必须校验 8-4-4-4-12 十六进制形态，
// 不合规视为响应不可解析（防日志注入；24 个已知 GUID 全部合规，不会误伤）。
func parseZSMAlgoID(zsm []byte) (string, error) {
	if len(zsm) == 0 {
		return "", errors.New("empty body")
	}
	if len(zsm) < 4 {
		return "", errors.New("insufficient header length")
	}
	pos := 4
	keyLen := int(zsm[3])
	if pos+keyLen >= len(zsm) {
		return "", errors.New("insufficient header length")
	}
	pos += keyLen
	pos++ // 分隔符 '$'

	const uuidLen = 36
	if pos+uuidLen > len(zsm) {
		return "", errors.New("no algo id in body")
	}
	algoID := string(zsm[pos : pos+uuidLen])
	if !validAlgoIDFormat(algoID) {
		return "", errors.New("malformed algo id")
	}
	return algoID, nil
}

// validAlgoIDFormat 校验算法 ID 形态：36 字符、8-4-4-4-12 分组、十六进制（大小写均可）。
func validAlgoIDFormat(s string) bool {
	if len(s) != 36 || s[8] != '-' || s[13] != '-' || s[18] != '-' || s[23] != '-' {
		return false
	}
	for i := 0; i < len(s); i++ {
		switch i {
		case 8, 13, 18, 23:
			continue
		}
		c := s[i]
		if !(c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F') {
			return false
		}
	}
	return true
}

// initialize 按算法 ID 从注册表选择加密器并初始化会话。
// 算法 ID 不在已知 24 个之内时返回 errUnknownAlgoID（错误串含完整 GUID，
// 引擎据此触发 UA 回退）。失败保持未初始化状态。
func (s *session) initialize(algoID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	c, err := cipher.NewCipher(algoID)
	if err != nil {
		return fmt.Errorf("%w: %v", errUnknownAlgoID, err)
	}
	s.cipher = c
	s.algoID = algoID
	s.initialized = true
	return nil
}

func (s *session) isInitialized() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.initialized
}

func (s *session) encrypt(text string) (string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !s.initialized {
		return "", fmt.Errorf("cipher not initialized")
	}
	return s.cipher.Encrypt(text), nil
}

func (s *session) decrypt(hexStr string) (string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !s.initialized {
		return "", fmt.Errorf("cipher not initialized")
	}
	return s.cipher.Decrypt(hexStr), nil
}

// free 释放会话（登出/掉线时调用）。
func (s *session) free() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.initialized = false
	s.cipher = nil
	s.algoID = ""
}

// xmlEscape XML 特殊字符转义。
func xmlEscape(s string) string {
	r := strings.NewReplacer("&", "&amp;", "<", "&lt;", ">", "&gt;", `"`, "&quot;", "'", "&apos;")
	return r.Replace(s)
}
