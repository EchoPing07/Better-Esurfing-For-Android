package dev.echoping.betteresurfing.shizuku

import dev.echoping.betteresurfing.shizuku.IShellService

/**
 * Shizuku UserService：运行在 shell(uid 2000) 权限的独立进程，
 * 由 [dev.echoping.betteresurfing.privilege.Privilege.runAsShizuku] 通过 bindUserService 调用。
 */
class ShellService : IShellService.Stub() {

    override fun exec(cmd: String): String {
        return try {
            // sh -c 以便使用管道（dumpsys wifi 全量输出 >5MB 超出 binder 事务上限，必须 shell 过滤后再返回）
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            // stdout/stderr 并发排水，防止任一侧管道缓冲满死锁
            val out = StringBuilder()
            val err = StringBuilder()
            val tOut = kotlin.concurrent.thread { p.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }
            val tErr = kotlin.concurrent.thread { p.errorStream.bufferedReader().forEachLine { err.appendLine(it) } }
            p.waitFor()
            tOut.join(2000); tErr.join(2000)
            "###EXIT:${p.exitValue()}###\n$out$err"
        } catch (t: Throwable) {
            "###EXIT:-1###\n${t.message ?: "exec error"}"
        }
    }

    override fun destroy() {
        System.exit(0)
    }
}
