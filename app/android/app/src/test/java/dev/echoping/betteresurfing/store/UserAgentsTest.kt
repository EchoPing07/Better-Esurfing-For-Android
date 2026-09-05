package dev.echoping.betteresurfing.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UA 迁移与自定义 UA 校验单测（纯函数，不依赖 Android 框架）。
 */
class UserAgentsTest {

    @Test
    fun migrateLegacy_keepsExistingUserAgent() {
        assertEquals(UserAgents.PC, UserAgents.migrateLegacy(UserAgents.PC, "phone"))
        assertEquals(
            "CCTP/WinSVR5/1068",
            UserAgents.migrateLegacy("CCTP/WinSVR5/1068", "pc")
        )
    }

    @Test
    fun migrateLegacy_pcChannelMapsToPcUa() {
        assertEquals(UserAgents.PC, UserAgents.migrateLegacy("", "pc"))
        assertEquals(UserAgents.PC, UserAgents.migrateLegacy(null, "pc"))
    }

    @Test
    fun migrateLegacy_phoneChannelMapsToAutoChain() {
        // 旧默认 2093 已被服务器准入闸拒绝，phone 存量账号迁到自动链
        assertEquals(UserAgents.AUTO, UserAgents.migrateLegacy("", "phone"))
        assertEquals(UserAgents.AUTO, UserAgents.migrateLegacy(null, ""))
    }

    @Test
    fun isValidCustom_boundaries() {
        assertTrue(UserAgents.isValidCustom("CCTP/WinSVR5/1068"))
        assertTrue(UserAgents.isValidCustom("a".repeat(64)))
        assertFalse(UserAgents.isValidCustom(""))
        assertFalse(UserAgents.isValidCustom("a".repeat(65)))
        assertFalse(UserAgents.isValidCustom("has space"))
        assertFalse(UserAgents.isValidCustom("tab\there"))
        assertFalse(UserAgents.isValidCustom("nl\ninjection"))
        assertFalse(UserAgents.isValidCustom("ctrl\u0007char"))
        assertFalse(UserAgents.isValidCustom("del\u007fchar"))
    }
}
