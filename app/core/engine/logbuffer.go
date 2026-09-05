package engine

import (
	"fmt"
	"strings"
	"sync"
	"time"
)

// logEntry 单条日志。
type logEntry struct {
	at    time.Time
	level int32
	msg   string
}

// ringBuffer 固定容量日志环形缓冲（防内存膨胀）。
type ringBuffer struct {
	mu      sync.Mutex
	buf     []logEntry
	max     int
	dropped int
}

func newRingBuffer(max int) *ringBuffer {
	if max <= 0 {
		max = 800
	}
	return &ringBuffer{buf: make([]logEntry, 0, max), max: max}
}

func (r *ringBuffer) add(level int32, msg string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if len(r.buf) == r.max {
		r.buf = r.buf[1:]
		r.dropped++
	}
	r.buf = append(r.buf, logEntry{at: time.Now(), level: level, msg: msg})
}

// dump 导出全部缓存日志（时间 级别 内容）。
func (r *ringBuffer) dump() string {
	r.mu.Lock()
	defer r.mu.Unlock()
	var b strings.Builder
	if r.dropped > 0 {
		fmt.Fprintf(&b, "...(已丢弃最早的 %d 条)...\n", r.dropped)
	}
	for _, e := range r.buf {
		b.WriteString(e.at.Format("15:04:05.000 "))
		switch e.level {
		case LogDebug:
			b.WriteString("D ")
		case LogInfo:
			b.WriteString("I ")
		case LogWarn:
			b.WriteString("W ")
		case LogError:
			b.WriteString("E ")
		default:
			b.WriteString("? ")
		}
		b.WriteString(e.msg)
		b.WriteByte('\n')
	}
	return b.String()
}

// sanitize 打码敏感字段（密码 / ticket），用于任何包含用户输入的日志。
func sanitize(kvs ...string) string {
	var b strings.Builder
	for i := 0; i+1 < len(kvs); i += 2 {
		v := kvs[i+1]
		if strings.Contains(strings.ToLower(kvs[i]), "pass") || strings.EqualFold(kvs[i], "passwd") {
			v = "***"
		} else if strings.EqualFold(kvs[i], "ticket") && len(v) > 8 {
			v = v[:8] + "…"
		}
		fmt.Fprintf(&b, "%s=%q ", kvs[i], v)
	}
	return strings.TrimSpace(b.String())
}
