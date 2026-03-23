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
import androidx.core.net.toUri

class GetStatusBar(
    private val context: Context
) : BaseApiMethod<Unit>() {

    // Для расчёта скорости интернета
    private var lastRxBytes = 0L
    private var lastTxBytes = 0L
    private var lastTimestamp = 0L

    override fun parseParams(jsonString: String) = Unit

    override suspend fun executeInternal(params: Unit?): String = withContext(Dispatchers.IO) {
        val status = StatusBarInfo(
            cpuTemp = getCpuTemp(),
            internetSpeed = getInternetSpeed(),
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

    /**
     * Получение температуры CPU через чтение системных файлов
     */
    private fun getCpuTemp(): String? {
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
     * Получение скорости интернета
     */
    private fun getInternetSpeed(): String? {
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

            formatSpeed(totalSpeed)
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

    /**
     * Получение текущей громкости (универсальный вариант для всех устройств)
     */
    private fun getVolume(): String? {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

            // 1. Сначала пробуем специфичные для FYT методы (UIS7862S)
            android.util.Log.d("GetStatusBar", "Trying FYT methods first...")
            val fytVolume = getFytVolume()
            if (fytVolume != null) {
                android.util.Log.d("GetStatusBar", "✅ FYT volume: $fytVolume%")
                return fytVolume.toString()
            }

            // 2. Пробуем стандартные стримы
            val streamsToTry = listOf(
                AudioManager.STREAM_MUSIC to "MUSIC",
                AudioManager.STREAM_SYSTEM to "SYSTEM",
                AudioManager.STREAM_VOICE_CALL to "VOICE_CALL",
                AudioManager.STREAM_RING to "RING",
                AudioManager.STREAM_ALARM to "ALARM",
                AudioManager.STREAM_NOTIFICATION to "NOTIFICATION"
            )

            var bestPercent: Int? = null
            var bestStreamName = ""

            for ((stream, name) in streamsToTry) {
                try {
                    val maxVolume = audioManager.getStreamMaxVolume(stream)
                    val currentVolume = audioManager.getStreamVolume(stream)

                    if (maxVolume > 0) {
                        val percent = (currentVolume * 100) / maxVolume
                        if (bestPercent == null && currentVolume > 0) {
                            bestPercent = percent
                            bestStreamName = name
                        }
                        android.util.Log.d("GetStatusBar", "$name: $currentVolume/$maxVolume = $percent%")
                    }
                } catch (_: Exception) { }
            }

            if (bestPercent != null) {
                android.util.Log.d("GetStatusBar", "✅ Using stream: $bestStreamName -> $bestPercent%")
                return bestPercent.toString()
            }

            // 3. Пробуем через ContentResolver
            val contentVolume = getContentVolume()
            if (contentVolume != null) {
                android.util.Log.d("GetStatusBar", "✅ Content volume: $contentVolume%")
                return contentVolume.toString()
            }

            // 4. Пробуем через AudioManager.getParameter
            val paramVolume = getParameterVolume(audioManager)
            if (paramVolume != null) {
                android.util.Log.d("GetStatusBar", "✅ Parameter volume: $paramVolume%")
                return paramVolume.toString()
            }

            android.util.Log.d("GetStatusBar", "❌ No volume method found")
            null
        } catch (e: Exception) {
            android.util.Log.e("GetStatusBar", "Error getting volume: ${e.message}")
            null
        }
    }

    /**
     * Специфичные методы для FYT магнитол (UIS7862S / YL850/YL860)
     */
    private fun getFytVolume(): Int? {
        // Способ 1: FytManager (основной класс для FYT)
        try {
            val fytManagerClass = Class.forName("com.fyt.FytManager")
            val instance = fytManagerClass.getMethod("getInstance").invoke(null)
            val getVolumeMethod = fytManagerClass.getMethod("getVolume")
            val volume = getVolumeMethod.invoke(instance) as Int
            if (volume in 0..100) {
                android.util.Log.d("GetStatusBar", "✅ FytManager.getVolume: $volume")
                return volume
            }
        } catch (e: Exception) {
            android.util.Log.d("GetStatusBar", "FytManager not available: ${e.message}")
        }

        // Способ 2: через системное свойство persist.sys.volume
        try {
            val process = Runtime.getRuntime().exec("getprop persist.sys.volume")
            val volume = process.inputStream.bufferedReader().readLine()?.toIntOrNull()
            if (volume != null && volume in 0..100) {
                android.util.Log.d("GetStatusBar", "✅ persist.sys.volume: $volume")
                return volume
            }
        } catch (e: Exception) {
            android.util.Log.d("GetStatusBar", "persist.sys.volume failed: ${e.message}")
        }

        // Способ 3: через системное свойство sys.volume
        try {
            val process = Runtime.getRuntime().exec("getprop sys.volume")
            val volume = process.inputStream.bufferedReader().readLine()?.toIntOrNull()
            if (volume != null && volume in 0..100) {
                android.util.Log.d("GetStatusBar", "✅ sys.volume: $volume")
                return volume
            }
        } catch (e: Exception) {
            android.util.Log.d("GetStatusBar", "sys.volume failed: ${e.message}")
        }

        // Способ 4: через FytSystemProperties
        try {
            val fytPropertiesClass = Class.forName("com.fyt.FytSystemProperties")
            val getIntMethod = fytPropertiesClass.getMethod("getInt", String::class.java, Int::class.java)
            val volume = getIntMethod.invoke(null, "persist.sys.volume", 0) as Int
            if (volume in 0..100) {
                android.util.Log.d("GetStatusBar", "✅ FytSystemProperties: $volume")
                return volume
            }
        } catch (e: Exception) {
            android.util.Log.d("GetStatusBar", "FytSystemProperties not available: ${e.message}")
        }

        return null
    }

    /**
     * Получение громкости через ContentResolver (на некоторых FYT)
     */
    private fun getContentVolume(): Int? {
        return try {
            val cursor = context.contentResolver.query(
                "content://settings/secure".toUri(),
                arrayOf("value"),
                "name = ?",
                arrayOf("volume_music"),
                null
            )

            cursor?.use {
                if (it.moveToFirst()) {
                    val volume = it.getInt(0)
                    if (volume >= 0) {
                        // На FYT обычно maxVolume = 30 или 40
                        val maxVolume = 40
                        val percent = (volume * 100) / maxVolume
                        return percent.coerceIn(0, 100)
                    }
                }
            }
            null
        } catch (e: Exception) {
            android.util.Log.d("GetStatusBar", "ContentResolver failed: ${e.message}")
            null
        }
    }

    /**
     * Получение громкости через AudioManager.getParameter (метод для автомобильных ГУ)
     */
    private fun getParameterVolume(audioManager: AudioManager): Int? {
        return try {
            val params = listOf("android.intent.extra.NOTIFICATION_VOLUME", "volume", "master_volume")

            for (param in params) {
                try {
                    val method = audioManager.javaClass.getMethod("getParameter", String::class.java)
                    val result = method.invoke(audioManager, param) as? String
                    if (result != null) {
                        val volume = result.toIntOrNull()
                        if (volume != null && volume in 0..100) {
                            return volume
                        }
                    }
                } catch (_: Exception) { }
            }
            null
        } catch (e: Exception) {
            android.util.Log.d("GetStatusBar", "getParameter failed: ${e.message}")
            null
        }
    }

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