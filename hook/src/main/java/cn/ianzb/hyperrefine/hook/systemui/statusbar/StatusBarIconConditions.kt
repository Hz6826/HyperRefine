package cn.ianzb.hyperrefine.hook.systemui.statusbar

import android.app.AlarmManager
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager

/**
 * 按组件自身状态求值；返回 null 表示当前 ROM 无法可靠判断，调用方保持系统行为。
 *
 * 这里只读取系统服务，不注册轮询、不修改系统状态。调用频率由卡片/图标更新事件驱动。
 */
internal class StatusBarIconConditionEvaluator(
    private val context: Context,
    private val notificationCount: () -> Int,
    private val speedText: () -> String,
) {

    fun matches(
        component: StatusBarIconComponent,
        condition: StatusBarIconCondition,
        alarmWithinHours: Int = 24,
    ): Boolean? =
        when (condition) {
            StatusBarIconCondition.NONE -> true
            StatusBarIconCondition.CONNECTED -> connected(component.id)
            StatusBarIconCondition.DISCONNECTED -> connected(component.id)?.not()
            StatusBarIconCondition.ENABLED -> enabled(component.id)
            StatusBarIconCondition.DISABLED -> enabled(component.id)?.not()
            StatusBarIconCondition.DATA_ON -> dataEnabled()
            StatusBarIconCondition.DATA_OFF -> dataEnabled()?.not()
            StatusBarIconCondition.ALARM_WITHIN -> alarmWithin(alarmWithinHours)
            StatusBarIconCondition.NO_ALARM -> noAlarm()
            StatusBarIconCondition.CHARGING -> charging()
            StatusBarIconCondition.NOT_CHARGING -> charging()?.not()
            StatusBarIconCondition.TRAFFIC -> hasTraffic()
            StatusBarIconCondition.NO_TRAFFIC -> hasTraffic()?.not()
            StatusBarIconCondition.HAS_NOTIFICATIONS -> notificationCount() > 0
            StatusBarIconCondition.NO_NOTIFICATIONS -> notificationCount() == 0
        }

    private fun connected(id: String): Boolean? = when (id) {
        StatusBarIconRegistry.WIFI -> networkTransport(NetworkCapabilities.TRANSPORT_WIFI)
        StatusBarIconRegistry.MOBILE -> networkTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        StatusBarIconRegistry.BLUETOOTH -> bluetoothConnected()
        else -> null
    }

    private fun networkTransport(transport: Int): Boolean? = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        manager.allNetworks.any { network ->
            val capabilities = manager.getNetworkCapabilities(network) ?: return@any false
            capabilities.hasTransport(transport) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }.getOrNull()

    private fun enabled(id: String): Boolean? = when (id) {
        StatusBarIconRegistry.WIFI -> runCatching {
            context.getSystemService(android.net.wifi.WifiManager::class.java)?.isWifiEnabled
        }.getOrNull()
        StatusBarIconRegistry.MOBILE -> dataEnabled()
        StatusBarIconRegistry.BLUETOOTH -> runCatching {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled
        }.getOrNull()
        else -> null
    }

    private fun dataEnabled(): Boolean? = runCatching {
        android.provider.Settings.Global.getInt(
            context.contentResolver,
            "mobile_data",
            1,
        ) == 1
    }.getOrNull()

    private fun bluetoothConnected(): Boolean? = runCatching {
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return null
        val adapter = manager.adapter ?: return false
        if (!adapter.isEnabled) return false
        manager.getConnectedDevices(BluetoothProfile.GATT).isNotEmpty() ||
            manager.getConnectedDevices(BluetoothProfile.GATT_SERVER).isNotEmpty()
    }.getOrNull()

    private fun alarmWithin(hours: Int): Boolean? = runCatching {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return null
        val alarm = manager.nextAlarmClock ?: return false
        val delta = alarm.triggerTime - System.currentTimeMillis()
        if (hours <= 0) delta <= 0 else delta in 0..(hours * 3_600_000L)
    }.getOrNull()

    private fun noAlarm(): Boolean? = runCatching {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return null
        manager.nextAlarmClock == null
    }.getOrNull()

    private fun charging(): Boolean? = runCatching {
        val status = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        )?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: return null
        status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }.getOrNull()

    private fun hasTraffic(): Boolean? {
        val text = speedText().trim()
        if (text.isEmpty()) return null
        val number = NUMBER.find(text)?.value?.toFloatOrNull() ?: return null
        return number > 0.001f
    }

    private companion object {
        val NUMBER = Regex("""\d+(?:\.\d+)?""")
    }
}
