package dev.echoping.betteresurfing.shizuku;

/** Shizuku UserService：以 shell(uid 2000) 身份执行命令 */
interface IShellService {
    /**
     * 执行命令。
     * @return "###EXIT:<code>###\n" + stdout + stderr
     */
    String exec(String cmd) = 1;

    void destroy() = 16777114; // Shizuku 约定：服务端 removeUserService 时按此 code 调 destroy 杀进程
}
