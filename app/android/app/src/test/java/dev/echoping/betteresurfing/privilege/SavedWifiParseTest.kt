package dev.echoping.betteresurfing.privilege

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `cmd wifi list-networks` 输出解析单测。
 * 覆盖：Android 15+ 无定界符定宽表格（Security type 列、安全类型重复行）、
 * 旧版带引号格式、含空格 SSID。
 */
class SavedWifiParseTest {

    private fun parse(out: String): List<String> {
        val m = Privilege::class.java.getDeclaredMethod("parseSavedNetworks", String::class.java)
        m.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return m.invoke(Privilege, out) as List<String>
    }

    @Test
    fun `新版无定界符定宽表格`() {
        // 真机（Android 16 / HyperOS）实测输出
        val out = """
Network Id      SSID                         Security type
0            4G-WIFI                          wpa2-psk
0            4G-WIFI                          wpa3-sae^
1            CMCC-NFR3                        wpa2-psk
1            CMCC-NFR3                        wpa3-sae^
8            GDKM_student                     open
8            GDKM_student                     owe^
9            Momo                             wpa2-psk
""".trimIndent()
        assertEquals(listOf("4G-WIFI", "CMCC-NFR3", "GDKM_student", "Momo"), parse(out))
    }

    @Test
    fun `旧版带引号格式`() {
        val out = """
Network Id      SSID                          FQDN
0            "HomeWifi"
1            "My Home Wifi"
2            "<unknown ssid>"
""".trimIndent()
        assertEquals(listOf("HomeWifi", "My Home Wifi"), parse(out))
    }

    @Test
    fun `新版格式下含空格 SSID`() {
        val out = """
Network Id      SSID                         Security type
0            My Home Wifi                     wpa2-psk
12           学 校 网                            wpa2-psk
""".trimIndent()
        assertEquals(listOf("My Home Wifi", "学 校 网"), parse(out))
    }

    @Test
    fun `空输出`() {
        assertEquals(emptyList<String>(), parse(""))
        assertEquals(emptyList<String>(), parse("Network Id      SSID\n"))
    }
}
