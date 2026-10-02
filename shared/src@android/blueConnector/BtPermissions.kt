package ink.lipoly.app.sunrise.blueConnector

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Android 运行时权限检查助手，仅返回缺失权限，不请求授权、不检查适配器或定位开关。
 *
 * API 31+ 使用 CONNECT（所有操作）与 SCAN（需要扫描时）；更低版本只在扫描检查时要求
 * ACCESS_FINE_LOCATION。Manifest 声明和授权 UI 仍由宿主应用负责。
 */
object BtPermissions {
    /**
     * 返回按当前 Android 版本及操作类别需要、但尚未授予的完整权限名。
     * @param context 用于 checkSelfPermission 的宿主上下文。
     * @param scan 是否包含 LE 扫描权限，默认 `true`；仅连接/枚举可传 `false`。
     * @return 缺失权限集合，元素例如 `android.permission.BLUETOOTH_CONNECT`；
     * 空集合只表示这套检查通过，不保证蓝牙开启或平台操作成功。
     */
    fun missing(context: Context, scan: Boolean = true): Set<String> = buildSet {
        if (Build.VERSION.SDK_INT >= 31) {
            if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            if (scan && context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                add(Manifest.permission.BLUETOOTH_SCAN)
        } else if (scan && context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
}
