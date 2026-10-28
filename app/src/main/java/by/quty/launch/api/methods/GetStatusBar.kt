// *** api/methods/GetStatusBar.kt *** //
package by.quty.launch.api.methods

import by.quty.launch.R
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import by.quty.launch.api.base.BaseApiMethod
import by.quty.launch.api.base.ApiResponse
import by.quty.launch.api.model.StatusBarInfo
import by.quty.launch.configs.ApiConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/**
 * Данные статус-бара: температура, скорость сети, громкость, сигнал, Wi-Fi, GPS, USB.
 */
class GetStatusBar(
    private val context: Context
) : BaseApiMethod<Unit>() {

    override val descriptionRes: Int
        get() = R.string.api_getstatusbar_desc

    override val iconRes: Int
        get() = R.drawable.ic_api_getstatusbar

    private var lastRxBytes = 0L
    private var lastTxBytes = 0L
    private var lastTimestamp = 0L

    private val speedHistory = mutableListOf<Double>()
    private val tempHistory = mutableListOf<Float>()

    private val speedHistorySize = ApiConfig.SPEED_HISTORY_SIZE
    private val tempHistorySize = ApiConfig.TEMP_HISTORY_SIZE

    private val thermalPaths = listOf(
        "/sys/class/thermal/thermal_zone0/temp",
        "/sys/class/thermal/thermal_zone1/temp",
        "/sys/class/thermal/thermal_zone2/temp",
        "/sys/class/thermal/thermal_zone3/temp",
        "/sys/class/thermal/thermal_zone4/temp",
        "/sys/class/thermal/thermal_zone5/temp",
        "/sys/devices/virtual/thermal/thermal_zone0/temp",
        "/sys/devices/virtual/thermal/thermal_zone1/temp",
        "/sys/devices/platform/msm_thermal/thermal_zone/temp",
        "/sys/class/hwmon/hwmon0/temp1_input",
        "/sys/class/hwmon/hwmon1/temp1_input",
        "/sys/class/hwmon/hwmon0/temp2_input",
        "/sys/class/power_supply/battery/temp"
    )

    override fun parseParams(jsonString: String) = Unit

    override suspend fun executeInternal(params: Unit?): String = withContext(Dispatchers.IO) {
        val status = StatusBarInfo(
            cpuTemp = getAverageCpuTemp(),
            internetSpeed = getAverageInternetSpeed(),
            volume = getVolume(),
            gsmSignal = getGsmSignal(),
            gsmNetworkType = getGsmNetworkType(),
            wifiSignalLevel = getWifiSignalLevel(),
            bluetooth = isBluetoothEnabled(),
            wifi = isWifiEnabled(),
            gps = isGpsEnabled(),
            usbConnected = isUsbConnected()
        )

        json.encodeToString(
            ApiResponse.serializer(StatusBarInfo.serializer()),
            ApiResponse(success = true, data = status)
        )
    }

    // ==================== ТЕМПЕРАТУРА CPU ====================

    /** Средняя температура CPU по последним значениям. */
    private fun getAverageCpuTemp(): String? {
        val currentTemp = getRawCpuTemp()

        if (currentTemp != null) {
            tempHistory.add(currentTemp)
            while (tempHistory.size > tempHistorySize) {
                tempHistory.removeAt(0)
            }
        }

        if (tempHistory.size < 2) {
            return currentTemp?.let { formatCpuTemp(it) }
        }

        val average = tempHistory.average()
        return formatCpuTemp(average.toFloat())
    }

    /** Читает температуру из термальных зон, выбирает лучший источник. */
    private fun getRawCpuTemp(): Float? {
        return try {
            var bestTemp: Float? = null
            var bestScore = 0

            for (path in thermalPaths) {
                val file = File(path)
                if (file.exists() && file.canRead()) {
                    try {
                        val value = RandomAccessFile(file, "r").readLine()?.trim()?.toFloatOrNull()
                        if (value != null && value > 0) {
                            val celsius = when {
                                value > 1000 -> value / 1000f
                                value > 100 -> value
                                else -> value
                            }

                            if (celsius in ApiConfig.MIN_CPU_TEMP_CELSIUS.toFloat()..ApiConfig.MAX_CPU_TEMP_CELSIUS.toFloat()) {
                                val score = when {
                                    path.contains("cpu") || path.contains("thermal_zone") -> 3
                                    path.contains("hwmon") -> 2
                                    path.contains("battery") -> 1
                                    else -> 0
                                }

                                if (score > bestScore) {
                                    bestTemp = celsius
                                    bestScore = score
                                }
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            }

            bestTemp
        } catch (_: Exception) {
            null
        }
    }

    private fun formatCpuTemp(temp: Float): String {
        return String.format(Locale.US, "%.0f°C", temp)
    }

    // ==================== СКОРОСТЬ ИНТЕРНЕТА ====================

    /** Средняя скорость интернета по последним значениям. */
    private fun getAverageInternetSpeed(): String? {
        val currentSpeed = getRawInternetSpeed()

        if (currentSpeed != null) {
            speedHistory.add(currentSpeed)
            while (speedHistory.size > speedHistorySize) {
                speedHistory.removeAt(0)
            }
        }

        if (speedHistory.size < 2) {
            return currentSpeed?.let { formatSpeed(it) }
        }

        val averageSpeed = speedHistory.average()
        return formatSpeed(averageSpeed)
    }

    /** Скорость по разнице TrafficStats между вызовами. */
    private fun getRawInternetSpeed(): Double? {
        return try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_NETWORK_STATE) != PackageManager.PERMISSION_GRANTED) {
                return null
            }

            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = connectivityManager.activeNetwork
            val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return null

            val isWifi = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            val isMobile = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)

            if (!isWifi && !isMobile) {
                return null
            }

            val currentRxBytes = TrafficStats.getTotalRxBytes()
            val currentTxBytes = TrafficStats.getTotalTxBytes()
            val currentTime = System.currentTimeMillis()

            if (lastTimestamp == 0L) {
                lastRxBytes = currentRxBytes
                lastTxBytes = currentTxBytes
                lastTimestamp = currentTime
                return null
            }

            val timeDelta = (currentTime - lastTimestamp) / 1000.0
            if (timeDelta <= 0) return null

            val rxSpeed = (currentRxBytes - lastRxBytes) / timeDelta
            val txSpeed = (currentTxBytes - lastTxBytes) / timeDelta

            lastRxBytes = currentRxBytes
            lastTxBytes = currentTxBytes
            lastTimestamp = currentTime

            val totalSpeed = rxSpeed + txSpeed
            if (totalSpeed <= 0) return null

            totalSpeed
        } catch (_: Exception) {
            null
        }
    }

    private fun formatSpeed(bytesPerSecond: Double): String {
        return when {
            bytesPerSecond >= 1024 * 1024 -> {
                val mbps = bytesPerSecond / (1024 * 1024)
                String.format(Locale.US, "%.1f MB/s", mbps)
            }
            bytesPerSecond >= 1024 -> {
                val kbps = bytesPerSecond / 1024
                String.format(Locale.US, "%.0f KB/s", kbps)
            }
            else -> {
                String.format(Locale.US, "%.0f B/s", bytesPerSecond)
            }
        }
    }

    // ==================== ГРОМКОСТЬ ====================

    private fun getVolume(): String? {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

            if (maxVolume > 0) {
                val percent = (currentVolume * 100) / maxVolume
                percent.toString()
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    // ==================== GSM СИГНАЛ ====================

    @Suppress("DEPRECATION")
    private fun getGsmSignal(): Int? {
        return try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                return null
            }

            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            telephonyManager.signalStrength?.level
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun getGsmNetworkType(): String? {
        return try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                return null
            }

            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val networkType = telephonyManager.dataNetworkType

            when (networkType) {
                TelephonyManager.NETWORK_TYPE_GPRS,
                TelephonyManager.NETWORK_TYPE_EDGE,
                TelephonyManager.NETWORK_TYPE_CDMA,
                TelephonyManager.NETWORK_TYPE_1xRTT,
                TelephonyManager.NETWORK_TYPE_IDEN -> "2G"

                TelephonyManager.NETWORK_TYPE_UMTS,
                TelephonyManager.NETWORK_TYPE_HSDPA,
                TelephonyManager.NETWORK_TYPE_HSUPA,
                TelephonyManager.NETWORK_TYPE_HSPA,
                TelephonyManager.NETWORK_TYPE_EVDO_0,
                TelephonyManager.NETWORK_TYPE_EVDO_A,
                TelephonyManager.NETWORK_TYPE_EVDO_B,
                TelephonyManager.NETWORK_TYPE_EHRPD -> "3G"

                TelephonyManager.NETWORK_TYPE_LTE -> "4G"

                TelephonyManager.NETWORK_TYPE_NR -> "5G"

                TelephonyManager.NETWORK_TYPE_HSPAP -> "H+"

                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    // ==================== Wi-Fi ====================

    @Suppress("DEPRECATION")
    private fun getWifiSignalLevel(): Int? {
        return try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_WIFI_STATE) != PackageManager.PERMISSION_GRANTED) {
                return null
            }

            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return null

            if (!wifiManager.isWifiEnabled) {
                return null
            }

            val wifiInfo = wifiManager.connectionInfo ?: return null
            val rssi = wifiInfo.rssi

            when {
                rssi > -50 -> 4
                rssi > -60 -> 3
                rssi > -70 -> 2
                rssi > -80 -> 1
                else -> 0
            }
        } catch (_: Exception) {
            null
        }
    }

    // ==================== Bluetooth ====================

    @Suppress("DEPRECATION")
    private fun isBluetoothEnabled(): Boolean {
        return try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            adapter?.isEnabled == true
        } catch (_: Exception) {
            false
        }
    }

    private fun isWifiEnabled(): Boolean {
        return try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_NETWORK_STATE) != PackageManager.PERMISSION_GRANTED) {
                return false
            }

            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = connectivityManager.activeNetwork
            val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } catch (_: Exception) {
            false
        }
    }

    private fun isGpsEnabled(): Boolean {
        return try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (_: Exception) {
            false
        }
    }

    // ==================== USB ====================

    private fun isUsbConnected(): Boolean {
        return try {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
            usbManager.deviceList.isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }
}