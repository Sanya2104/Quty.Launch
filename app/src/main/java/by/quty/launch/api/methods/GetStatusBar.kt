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
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import by.quty.launch.api.base.BaseApiMethod
import by.quty.launch.api.base.ApiResponse
import by.quty.launch.api.model.StatusBarInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GetStatusBar(
    private val context: Context
) : BaseApiMethod<Unit>() {

    override fun parseParams(jsonString: String) = Unit

    override suspend fun executeInternal(params: Unit?): String = withContext(Dispatchers.IO) {
        val status = StatusBarInfo(
            cpuTemp = getCpuTemp(),
            internetSpeed = getInternetSpeed(),
            volume = getVolume(),
            gsmSignal = getGsmSignal(),
            bluetooth = isBluetoothEnabled(),
            wifi = isWifiEnabled(),
            gps = isGpsEnabled()
        )

        json.encodeToString(
            ApiResponse.serializer(StatusBarInfo.serializer()),
            ApiResponse(success = true, data = status)
        )
    }

    private fun getCpuTemp(): String? = null
    private fun getInternetSpeed(): String? = null

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