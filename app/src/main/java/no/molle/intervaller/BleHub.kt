package no.molle.intervaller

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

/**
 * All Bluetooth i appen:
 *  - Sender farten som en fotpod (Running Speed and Cadence, 0x1814), slik at Zwift kan bruke den.
 *  - Kobler til pulsmåler (Heart Rate, 0x180D).
 * Nettsiden får beskjeder via [js]. Alt kjøres på hovedtråden.
 */
@SuppressLint("MissingPermission")
object BleHub {

    private fun uuid16(x: Int): UUID =
        UUID.fromString(String.format(Locale.ROOT, "0000%04x-0000-1000-8000-00805f9b34fb", x))

    private val RSC_SERVICE = uuid16(0x1814)
    private val RSC_MEASUREMENT = uuid16(0x2A53)
    private val RSC_FEATURE = uuid16(0x2A54)
    private val SENSOR_LOCATION = uuid16(0x2A5D)
    private val HR_SERVICE = uuid16(0x180D)
    private val HR_MEASUREMENT = uuid16(0x2A37)
    private val CCCD = uuid16(0x2902)

    private lateinit var ctx: Context
    private val main = Handler(Looper.getMainLooper())
    var js: ((String) -> Unit)? = null

    fun init(c: Context) {
        ctx = c.applicationContext
    }

    private fun adapter(): BluetoothAdapter? = ctx.getSystemService(BluetoothManager::class.java)?.adapter

    private fun call(fn: String, vararg args: Any) {
        val a = args.joinToString(",") { if (it is String) JSONObject.quote(it) else it.toString() }
        js?.invoke("window.$fn && window.$fn($a)")
    }

    /** Feilmelding fra tillatelse- eller Bluetooth-sjekken. */
    fun message(msg: String) = main.post { call("onNativeMessage", msg) }

    // ---------------- tilstand fra økta ----------------

    @Volatile var speedKmh = 0.0
    @Volatile var distM = 0.0
    @Volatile var running = false
    @Volatile var sessionActive = false
    @Volatile var label = ""
    @Volatile var remain = ""

    fun setState(json: String) {
        try {
            val o = JSONObject(json)
            speedKmh = o.optDouble("speed", 0.0).let { if (it.isNaN()) 0.0 else it }
            distM = o.optDouble("dist", 0.0).let { if (it.isNaN()) 0.0 else it }
            running = o.optBoolean("running", false)
            sessionActive = o.optBoolean("active", false)
            label = o.optString("label", "")
            remain = o.optString("remain", "")
        } catch (_: Exception) {
        }
        main.post { TreadmillService.refresh() }
    }

    fun pushStatus() = main.post {
        zwiftStatus("")
        hrStatus("")
    }

    // ---------------- fotpod for Zwift ----------------

    private var server: BluetoothGattServer? = null
    private var measurement: BluetoothGattCharacteristic? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advCallback: AdvertiseCallback? = null
    private val clients = mutableSetOf<BluetoothDevice>()
    private val subscribers = mutableSetOf<BluetoothDevice>()
    var rscOn = false
        private set

    private val ticker = object : Runnable {
        override fun run() {
            if (!rscOn) return
            notifySubscribers()
            main.postDelayed(this, 1000)
        }
    }

    fun rscSpeedText(): String = String.format(Locale("nb", "NO"), "%.1f", if (running) speedKmh else 0.0)

    private fun rscValue(): ByteArray {
        val kmh = if (running) speedKmh else 0.0
        val ms = kmh / 3.6
        val speed = (ms * 256).roundToInt().coerceIn(0, 65535)
        // Zwift viser kadens. Vi har ingen skritteller, så den anslås ut fra farten.
        val cadence = if (kmh > 1.0) (148 + kmh * 2.2).roundToInt().coerceIn(140, 200) else 0
        val runningFlag = if (kmh >= 7.5) 0x04 else 0x00
        val flags = 0x02 or runningFlag // totaldistanse er med
        val dist = (distM * 10).toLong().coerceIn(0L, 0xFFFFFFFFL)
        return byteArrayOf(
            flags.toByte(),
            (speed and 0xFF).toByte(), (speed shr 8 and 0xFF).toByte(),
            cadence.toByte(),
            (dist and 0xFF).toByte(), (dist shr 8 and 0xFF).toByte(),
            (dist shr 16 and 0xFF).toByte(), (dist shr 24 and 0xFF).toByte()
        )
    }

    @Suppress("DEPRECATION")
    private fun notifySubscribers() {
        val srv = server ?: return
        val ch = measurement ?: return
        val value = rscValue()
        for (d in subscribers.toList()) {
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    srv.notifyCharacteristicChanged(d, ch, false, value)
                } else {
                    ch.value = value
                    srv.notifyCharacteristicChanged(d, ch, false)
                }
            } catch (_: Exception) {
            }
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            main.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    clients.add(device)
                } else {
                    clients.remove(device)
                    subscribers.remove(device)
                }
                zwiftStatus("")
            }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            val value = when (characteristic.uuid) {
                RSC_FEATURE -> byteArrayOf(0x06, 0x00) // totaldistanse + gå/løp-status
                SENSOR_LOCATION -> byteArrayOf(0x02) // i skoen
                else -> rscValue()
            }
            val off = offset.coerceIn(0, value.size)
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value.copyOfRange(off, value.size))
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
            val value = if (subscribers.contains(device)) byteArrayOf(0x01, 0x00) else byteArrayOf(0x00, 0x00)
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            if (descriptor.uuid == CCCD) {
                val on = value != null && value.isNotEmpty() && (value[0].toInt() and 0x01) != 0
                main.post {
                    if (on) subscribers.add(device) else subscribers.remove(device)
                    zwiftStatus("")
                }
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }
    }

    fun startRsc() {
        if (rscOn) { zwiftStatus(""); return }
        val mgr = ctx.getSystemService(BluetoothManager::class.java)
        val adv = mgr?.adapter?.bluetoothLeAdvertiser
        if (mgr == null || adv == null) {
            zwiftStatus("Telefonen kan ikke sende som Bluetooth-sensor.")
            return
        }
        val srv = mgr.openGattServer(ctx, serverCallback)
        if (srv == null) {
            zwiftStatus("Klarte ikke å starte Bluetooth-tjenesten. Prøv å slå Bluetooth av og på.")
            return
        }
        val service = BluetoothGattService(RSC_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val meas = BluetoothGattCharacteristic(RSC_MEASUREMENT, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0)
        meas.addDescriptor(BluetoothGattDescriptor(CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        service.addCharacteristic(meas)
        service.addCharacteristic(BluetoothGattCharacteristic(RSC_FEATURE, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
        service.addCharacteristic(BluetoothGattCharacteristic(SENSOR_LOCATION, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
        srv.addService(service)

        server = srv
        measurement = meas
        advertiser = adv
        rscOn = true
        startAdvertising(withName = true)
        main.removeCallbacks(ticker)
        main.post(ticker)
        zwiftStatus("")
        TreadmillService.ensure(ctx)
    }

    private fun startAdvertising(withName: Boolean) {
        val adv = advertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(RSC_SERVICE))
            .setIncludeDeviceName(false)
            .build()
        val scanResponse = AdvertiseData.Builder().setIncludeDeviceName(withName).build()
        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                main.post { zwiftStatus("") }
            }

            override fun onStartFailure(errorCode: Int) {
                main.post {
                    if (errorCode == ADVERTISE_FAILED_DATA_TOO_LARGE && withName) {
                        // Telefonnavnet er for langt til å sendes med. Prøv uten.
                        startAdvertising(withName = false)
                    } else if (errorCode != ADVERTISE_FAILED_ALREADY_STARTED) {
                        stopRsc()
                        zwiftStatus("Klarte ikke å sende til Zwift (feilkode $errorCode).")
                    }
                }
            }
        }
        advCallback = cb
        try {
            adv.startAdvertising(settings, data, scanResponse, cb)
        } catch (e: Exception) {
            stopRsc()
            zwiftStatus("Klarte ikke å sende til Zwift.")
        }
    }

    fun stopRsc() {
        main.removeCallbacks(ticker)
        try { advCallback?.let { advertiser?.stopAdvertising(it) } } catch (_: Exception) {}
        try {
            for (d in clients.toList()) server?.cancelConnection(d)
            server?.close()
        } catch (_: Exception) {
        }
        server = null
        measurement = null
        advCallback = null
        clients.clear()
        subscribers.clear()
        rscOn = false
        zwiftStatus("")
        TreadmillService.ensure(ctx)
    }

    val zwiftConnected: Boolean get() = subscribers.isNotEmpty()

    private fun zwiftStatus(msg: String) {
        val name = try { adapter()?.name ?: "" } catch (_: Exception) { "" }
        call("onNativeZwift", rscOn, subscribers.size, name, msg)
        TreadmillService.refresh()
    }

    // ---------------- pulsmåler ----------------

    private val found = LinkedHashMap<String, String>()
    private var scanning = false
    private var hrGatt: BluetoothGatt? = null
    private var hrAddress: String? = null
    private var hrWanted = false
    private var hrTries = 0
    var hrConnected = false
        private set
    var hrName = ""
        private set
    @Volatile var hrBpm = 0
        private set

    val hrActive: Boolean get() = hrWanted

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val addr = result.device.address
            val name = result.scanRecord?.deviceName
                ?: (try { result.device.name } catch (_: Exception) { null })
                ?: "Pulsmåler (${addr.takeLast(5)})"
            main.post {
                if (!found.containsKey(addr) || found[addr] != name) {
                    found[addr] = name
                    sendList()
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            main.post {
                scanning = false
                hrStatus("Søket etter pulsmåler feilet (feilkode $errorCode). Prøv igjen.")
            }
        }
    }

    private fun sendList() {
        val arr = JSONArray()
        for ((id, name) in found) arr.put(JSONObject().put("id", id).put("name", name))
        js?.invoke("window.onNativeHrList && window.onNativeHrList($arr)")
    }

    private val stopScanRunnable = Runnable {
        stopScan()
        if (found.isEmpty()) hrStatus("Fant ingen pulsmåler. Fukt beltet og ta det på, eller slå på «Kringkast puls» på klokka, og prøv igjen.")
    }

    fun scanHr() {
        val scanner = adapter()?.bluetoothLeScanner
        if (scanner == null) {
            hrStatus("Fant ikke Bluetooth på telefonen.")
            return
        }
        stopScan()
        found.clear()
        sendList()
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(HR_SERVICE)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(filters, settings, scanCallback)
            scanning = true
            hrStatus("Leter etter pulsmålere …")
            main.postDelayed(stopScanRunnable, 12000)
        } catch (e: Exception) {
            hrStatus("Klarte ikke å lete etter pulsmåler.")
        }
    }

    private fun stopScan() {
        main.removeCallbacks(stopScanRunnable)
        if (!scanning) return
        scanning = false
        try { adapter()?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
    }

    fun connectHr(address: String) {
        stopScan()
        val dev = try { adapter()?.getRemoteDevice(address) } catch (_: Exception) { null }
        if (dev == null) {
            hrStatus("Fant ikke pulsmåleren. Prøv å søke på nytt.")
            return
        }
        closeHrGatt()
        hrWanted = true
        hrTries = 0
        hrAddress = address
        hrName = found[address] ?: hrName
        hrStatus("Kobler til $hrName …")
        hrGatt = dev.connectGatt(ctx, false, hrCallback, BluetoothDevice.TRANSPORT_LE)
        TreadmillService.ensure(ctx)
    }

    fun disconnectHr() {
        hrWanted = false
        stopScan()
        closeHrGatt()
        hrConnected = false
        hrBpm = 0
        hrStatus("")
        TreadmillService.ensure(ctx)
    }

    private fun closeHrGatt() {
        try {
            hrGatt?.disconnect()
            hrGatt?.close()
        } catch (_: Exception) {
        }
        hrGatt = null
    }

    private fun reconnectHr() {
        val addr = hrAddress ?: return
        if (!hrWanted) return
        if (hrTries++ > 30) {
            hrWanted = false
            hrStatus("Mistet kontakten med pulsmåleren. Koble til på nytt.")
            TreadmillService.ensure(ctx)
            return
        }
        val dev = (try { adapter()?.getRemoteDevice(addr) } catch (_: Exception) { null }) ?: return
        closeHrGatt()
        hrGatt = dev.connectGatt(ctx, true, hrCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun parseHr(v: ByteArray) {
        if (v.size < 2) return
        val flags = v[0].toInt()
        val bpm = if (flags and 0x01 != 0 && v.size >= 3)
            (v[1].toInt() and 0xFF) or ((v[2].toInt() and 0xFF) shl 8)
        else
            v[1].toInt() and 0xFF
        hrBpm = bpm
        main.post {
            call("onNativeHr", bpm)
            TreadmillService.refresh()
        }
    }

    private val hrCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    val was = hrConnected
                    hrConnected = false
                    hrBpm = 0
                    call("onNativeHr", 0)
                    if (hrWanted) {
                        hrStatus(if (was) "Mistet kontakten, prøver igjen …" else "Kobler til $hrName …")
                        main.postDelayed({ reconnectHr() }, 2000)
                    } else {
                        hrStatus("")
                    }
                }
            }
        }

        @Suppress("DEPRECATION")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            main.post {
                val ch = gatt.getService(HR_SERVICE)?.getCharacteristic(HR_MEASUREMENT)
                if (ch == null) {
                    hrStatus("Enheten sender ikke puls.")
                    hrWanted = false
                    closeHrGatt()
                    return@post
                }
                gatt.setCharacteristicNotification(ch, true)
                val d = ch.getDescriptor(CCCD)
                if (d != null) {
                    if (Build.VERSION.SDK_INT >= 33) {
                        gatt.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(d)
                    }
                }
                hrConnected = true
                hrTries = 0
                hrStatus("")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == HR_MEASUREMENT) parseHr(value)
        }

        @Deprecated("Brukes bare på Android 12 og eldre")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33 && characteristic.uuid == HR_MEASUREMENT) {
                characteristic.value?.let { parseHr(it) }
            }
        }
    }

    private fun hrStatus(msg: String) {
        call("onNativeHrStatus", hrConnected, hrName, msg)
        TreadmillService.refresh()
    }

    /** Om bakgrunnstjenesten trengs: når vi sender til Zwift eller holder kontakt med pulsmåler. */
    val needsService: Boolean get() = rscOn || hrWanted

    fun startServiceIntent(c: Context) = Intent(c, TreadmillService::class.java)
}
