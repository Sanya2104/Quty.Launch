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
    private val tempHistory = mutableListOf<String>()

    // Количество значений для усреднения
    private val historySize = 3

    override fun parseParams(jsonString: String) = Unit

    override suspend fun executeInternal(params: Unit?): String = withContext(Dispatchers.IO) {
        val status = StatusBarInfo(
            cpuTemp = getAverageCpuTemp(),
            internetSpeed = getAverageInternetSpeed(),
            volume = getVolume(),
            gsmSignal = getGsmSignal(),
            wifiSignalLevel = getWifiSignalLevel(),
            bluetooth = isBluetoothEnabled(),
            wifi = isWifiEnabled(),
            gps = isGpsEnabled()
        )

        json.encodeToString(
            ApiResponse.serializer(StatusBarInfo.serializer()),
            ApiResponse(success = true, data = status)
        )
    }

    // ==================== ТЕМПЕРАТУРА CPU ====================

    /**
     * Получение температуры CPU с усреднением
     */
    private fun getAverageCpuTemp(): String? {
        val currentTemp = getRawCpuTemp()

        if (currentTemp != null) {
            tempHistory.add(currentTemp)
            // Оставляем только последние historySize значений
            while (tempHistory.size > historySize) {
                tempHistory.removeAt(0)
            }
        }

        // Если в истории меньше 2 значений, возвращаем текущее
        if (tempHistory.size < 2) {
            return currentTemp
        }

        // Усредняем значения (находим наиболее часто встречающееся)
        return getMostFrequentTemp(tempHistory) ?: currentTemp
    }

    /**
     * Получение сырых данных температуры CPU
     */
    private fun getRawCpuTemp(): String? {
        return try {
            // Пути к файлам температуры (наиболее распространённые)
            val thermalPaths = listOf(
                // CPU temperature paths
                "/sys/class/thermal/thermal_zone0/temp",
                "/sys/class/thermal/thermal_zone1/temp",
                "/sys/class/thermal/thermal_zone2/temp",
                "/sys/class/thermal/thermal_zone3/temp",
                "/sys/class/thermal/thermal_zone4/temp",
                "/sys/class/thermal/thermal_zone5/temp",
                "/sys/devices/virtual/thermal/thermal_zone0/temp",
                "/sys/devices/virtual/thermal/thermal_zone1/temp",
                "/sys/devices/platform/msm_thermal/thermal_zone/temp",
                // Alternative paths
                "/sys/class/hwmon/hwmon0/temp1_input",
                "/sys/class/hwmon/hwmon1/temp1_input",
                "/sys/class/hwmon/hwmon0/temp2_input",
                "/sys/class/power_supply/battery/temp"  // fallback - температура батареи
            )

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

                            // Проверяем, что температура в разумных пределах (0-120°C)
                            if (celsius in 0f..120f) {
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
            bestTemp?.let {
                String.format(Locale.US, "%.0f°C", it)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Находит наиболее часто встречающуюся температуру в истории
     */
    private fun getMostFrequentTemp(history: MutableList<String>): String? {
        if (history.isEmpty()) return null

        val frequency = mutableMapOf<String, Int>()
        for (temp in history) {
            frequency[temp] = frequency.getOrDefault(temp, 0) + 1
        }

        return frequency.maxByOrNull { it.value }?.key
    }

    // ==================== СКОРОСТЬ ИНТЕРНЕТА ====================

    /**
     * Получение скорости интернета с усреднением
     */
    private fun getAverageInternetSpeed(): String? {
        val currentSpeed = getRawInternetSpeed()

        if (currentSpeed != null) {
            speedHistory.add(currentSpeed)
            while (speedHistory.size > historySize) {
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
}