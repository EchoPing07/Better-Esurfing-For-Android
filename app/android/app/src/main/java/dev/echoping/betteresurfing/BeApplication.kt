package dev.echoping.betteresurfing

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import androidx.work.Configuration
import dev.echoping.betteresurfing.engine.Repo
import dev.echoping.betteresurfing.net.NetWatch
import dev.echoping.betteresurfing.privilege.Privilege
import dev.echoping.betteresurfing.store.Prefs
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku

/**
 * 应用入口：WorkManager 自定义初始化 + Shizuku Binder 生命周期监听。
 *
 * Shizuku 规范（Shizuku-API README / ShizukuProvider 源码）：
 * - Binder 由 Shizuku server 在客户端进程启动时推送到客户端的 ShizukuProvider，是异步的；
 * - 客户端必须注册 addBinderReceivedListenerSticky / addBinderDeadListener 跟踪生命周期，
 *   在 binder 就绪前调用 checkSelfPermission/requestPermission 会抛 IllegalStateException。
 */
class BeApplication : Application(), Configuration.Provider {

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku binder received")
        Privilege.onBinderEvent(true)
        Repo.notifyShizukuBinder(true)
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.i(TAG, "Shizuku binder dead")
        Privilege.onBinderEvent(false)
        Repo.notifyShizukuBinder(false)
    }

    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == Privilege.SHIZUKU_REQ_CODE) {
                val granted = grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED
                Privilege.invalidate()
                Repo.onLog(if (granted) 1 else 2, "Shizuku 授权" + if (granted) "成功" else "被拒绝")
            }
        }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.WARN)
            .build()

    override fun onCreate() {
        super.onCreate()
        // 尽早初始化持久层（密码 Keystore / 偏好）
        Prefs.init(this)
        Privilege.appContext = this

        // 桌面图标别名同步（幂等）：安装/恢复后把偏好落到两个 launcher alias 的启用态
        runCatching { LauncherIcon.apply(this) }

        // 预测性返回总闸（SukiSU Ultra 同款）：API 34+ 用反射按用户偏好 opt-in/out。
        // 反射而非清单声明的原因：清单 android:enableOnBackInvokedCallback 无法运行时
        // 切换，而本应用把「跟随手势预览」作为可关闭的设置项。API 33 上该 setter 不
        // 存在（34 加入），开关不生效，走传统返回。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/content/pm/ApplicationInfo;->setEnableOnBackInvokedCallback"
            )
            setEnableOnBackInvokedCallback(applicationInfo, Prefs.predictiveBack)
        }

        // 进程级 WiFi 事实源：UI 与认证服务共享，服务未运行时首页也能实时显示连接状态
        try {
            NetWatch.start(this)
        } catch (t: Throwable) {
            Log.w(TAG, "NetWatch start failed: ${t.message}")
        }

        // Shizuku：跟踪 Binder 生命周期（sticky = 若已就绪立即回调）
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        } catch (t: Throwable) {
            Log.w(TAG, "Shizuku listener register failed: ${t.message}")
        }
    }

    companion object {
        private const val TAG = "BetterEsurfing"

        /**
         * 运行时切换预测性返回总闸（SukiSU Ultra 同款）：反射调用
         * ApplicationInfo.setEnableOnBackInvokedCallback（API 34+ 隐藏 API，需先经
         * [HiddenApiBypass.addHiddenApiExemptions] 豁免）。失败静默（开关仅影响手势
         * 预览，不致命）。
         */
        fun setEnableOnBackInvokedCallback(appInfo: ApplicationInfo, enable: Boolean) {
            runCatching {
                val applicationInfoClass = ApplicationInfo::class.java
                val method = applicationInfoClass.getDeclaredMethod(
                    "setEnableOnBackInvokedCallback",
                    Boolean::class.javaPrimitiveType,
                )
                method.isAccessible = true
                method.invoke(appInfo, enable)
            }
        }
    }
}
