package com.mytvb.core.common.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import com.mytvb.R
import com.mytvb.core.common.log.AppLog
import com.mytvb.core.ui.base.AppToast

/**
 * PackageInstaller Session 安装结果回调（issue#129，U 盘安装包走 Session 通道）。
 *
 * [PackageInstaller.Session.commit] 是异步的，系统把后续状态回投到这个 receiver：
 * - [PackageInstaller.STATUS_PENDING_USER_ACTION]：必须由我们取出 EXTRA_INTENT 再拉起，
 *   否则安装流程静默停在「等待用户确认」——这是 Session 安装最容易漏的一步；
 * - 成功只记日志（系统安装器自己有关闭/打开应用的界面）；
 * - 失败提示原因，并说明安装包已保存到哪，用户可用文件管理器手动安装。
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "UpdateInstallReceiver"
        const val ACTION_INSTALL_RESULT = "com.mytvb.action.UPDATE_INSTALL_RESULT"
        const val EXTRA_APK_PATH = "apk_path"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val apkPath = intent.getStringExtra(EXTRA_APK_PATH).orEmpty()
        AppLog.i(TAG, "install result status=$status msg=$message path=$apkPath")

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm == null) {
                    AppLog.w(TAG, "pending user action but confirm intent is null")
                    return
                }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
                    .onFailure { AppLog.e(TAG, "launch install confirm failed", it) }
            }

            PackageInstaller.STATUS_SUCCESS -> Unit

            else -> {
                val appContext = context.applicationContext
                val hint = if (apkPath.isNotBlank()) {
                    appContext.getString(R.string.update_install_saved_to_format, apkPath)
                } else {
                    appContext.getString(R.string.download_failed_toast)
                }
                AppToast.show(appContext, hint, Toast.LENGTH_LONG)
            }
        }
    }
}
