// *** api/methods/GetStatusBar.kt *** //
package by.quty.launch.api.methods

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

class GetStatusBar(
    private val context: Context
) : BaseApiMethod<Unit>() {

    // Для расчёта скорости интернета
    private var lastRxBytes = 0L
    private var lastTxBytes = 0L
    private var lastTimestamp = 0L

    // Хранилище для усреднения значений
    private val speedHistory = mutableListOf<Double>()
    private val tempHistory = mutableListOf<Float>()

    // Количество значений для усреднения (из конфига)
    private val speedHistorySize = ApiConfig.SPEED_HISTORY_SIZE
    private val tempHistorySize = ApiConfig.TEMP_HISTORY_SIZE

    // Пути к файлам температуры (системные, оставляем локально)
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

    /**
     * Получение температуры CPU с усреднением (среднее арифметическое)
     */
    private fun getAverageCpuTemp(): String? {
        val currentTemp = getRawCpuTemp()

        if (currentTemp != null) {
            tempHistory.add(currentTemp)
            // Оставляем только последние tempHistorySize значений
            while (tempHistory.size > tempHistorySize) {
                tempHistory.removeAt(0)
            }
        }

        // Если в истории меньше 2 значений, возвращаем текущее
        if (tempHistory.size < 2) {
            return currentTemp?.let { formatCpuTemp(it) }
        }

        // Вычисляем среднее арифметическое температур
        val average = tempHistory.average()
        return formatCpuTemp(average.toFloat())
    }

    /**
     * Получение сырых данных температуры CPU
     */
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
                            // Определяем температуру в градусах Цельсия
                            val celsius = when {
                                // Значение больше 1000 (миллиградусы) → делим на 1000
                                value > 1000 -> value / 1000f
                                // Значение от 100 до 1000 (обычно градусы)
                                value > 100 -> value
                                // Значение от 0 до 100 (уже градусы)
                                else -> value
                            }

                            // Проверяем, что температура в разумных пределах (из конфига)
                            if (celsius in ApiConfig.MIN_CPU_TEMP_CELSIUS.toFloat()..ApiConfig.MAX_CPU_TEMP_CELSIUS.toFloat()) {
                                // Оцениваем качество источника
                                val score = when {
                                    path.contains("cpu") || path.contains("thermal_zone") -> 3
                                    path.contains("hwmon") -> 2
                                    path.contains("battery") -> 1
                                    else -> 0
                                }

                                // Выбираем источник с наивысшим качеством
                                if (score > bestScore) {
                                    bestTemp = celsius
                                    bestScore = score
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // Пропускаем файл, который не можем прочитать
                    }
                }
            }

            // Форматируем результат
            bestTemp
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Форматирует температуру в строку с символом градуса
     */
    private fun formatCpuTemp(temp: Float): String {
        return String.format(Locale.US, "%.0f°C", temp)
    }

    // ==================== СКОРОСТЬ ИНТЕРНЕТА ====================

    /**
     * Получение скорости интернета с усреднением (среднее арифметическое)
     */
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

    /**
     * Получение сырых данных скорости интернета
     */
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

    /**
     * Форматирует скорость в читаемый вид (B/s, KB/s, MB/s)
     */
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

    /**
     * Получение текущей громкости
     */
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

    /**
     * Получение уровня GSM сигнала
     */
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

    /**
     * Получение типа сети (2G, 3G, 4G, 5G, H+)
     */
    @Suppress("DEPRECATION")
    private fun getGsmNetworkType(): String? {
        return try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                return null
            }

            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val networkType = telephonyManager.dataNetworkType

            when (networkType) {
                // 2G
                TelephonyManager.NETWORK_TYPE_GPRS,
                TelephonyManager.NETWORK_TYPE_EDGE,
                TelephonyManager.NETWORK_TYPE_CDMA,
                TelephonyManager.NETWORK_TYPE_1xRTT,
                TelephonyManager.NETWORK_TYPE_IDEN -> "2G"

                // 3G
                TelephonyManager.NETWORK_TYPE_UMTS,
                TelephonyManager.NETWORK_TYPE_HSDPA,
                TelephonyManager.NETWORK_TYPE_HSUPA,
                TelephonyManager.NETWORK_TYPE_HSPA,
                TelephonyManager.NETWORK_TYPE_EVDO_0,
                TelephonyManager.NETWORK_TYPE_EVDO_A,
                TelephonyManager.NETWORK_TYPE_EVDO_B,
                TelephonyManager.NETWORK_TYPE_EHRPD -> "3G"

                // 4G
                TelephonyManager.NETWORK_TYPE_LTE -> "4G"

                // 5G
                TelephonyManager.NETWORK_TYPE_NR -> "5G"

                // HSPA+ (часто отображается как H+)
                TelephonyManager.NETWORK_TYPE_HSPAP -> "H+"

                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    // ==================== Wi-Fi ====================

    /**
     * Получение уровня сигнала Wi-Fi (0-4)
     */
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

    /**
     * Проверка включен ли Bluetooth
     */
    @Suppress("DEPRECATION")
    private fun isBluetoothEnabled(): Boolean {
        return try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            adapter?.isEnabled == true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Проверка включен ли Wi-Fi
     */
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

    /**
     * Проверка включен ли GPS
     */
    private fun isGpsEnabled(): Boolean {
        return try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (_: Exception) {
            false
        }
    }

    // ==================== USB ====================

    /**
     * Проверка подключения USB устройств
     */
    private fun isUsbConnected(): Boolean {
        return try {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
            val deviceList = usbManager.deviceList
            deviceList.isNotEmpty()  // true если есть подключённые USB устройства
        } catch (_: Exception) {
            false
        }
    }
}