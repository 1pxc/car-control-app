package com.example.carcontrol

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.UUID

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB")
        val CHAR_UUID: UUID    = UUID.fromString("0000FFF2-0000-1000-8000-00805F9B34FB")

        private const val SEND_INTERVAL_MS = 100L
    }

    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    var state = State.DISCONNECTED
        private set

    var currentOpcode: Byte = 0x00

    var onStateChange: ((State) -> Unit)? = null
    var onDeviceFound: ((BluetoothDevice) -> Unit)? = null

    private val bluetoothAdapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val sendRunnable = object : Runnable {
        override fun run() {
            sendCurrentOpcode()
            mainHandler.postDelayed(this, SEND_INTERVAL_MS)
        }
    }

    // -------- 扫描 --------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            result.device?.let { onDeviceFound?.invoke(it) }
        }
    }

    fun startScan() {
        bluetoothAdapter?.bluetoothLeScanner?.startScan(scanCallback)
    }

    fun stopScan() {
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    // -------- 连接 --------

    fun connect(device: BluetoothDevice) {
        setState(State.CONNECTING)
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        stopSending()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        writeChar = null
        setState(State.DISCONNECTED)
    }

    // -------- GATT 回调 --------

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices()
            } else {
                mainHandler.post { disconnect() }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                mainHandler.post { disconnect() }
                return
            }
            writeChar = g.getService(SERVICE_UUID)
                ?.getCharacteristic(CHAR_UUID)
                ?.also { it.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE }

            mainHandler.post {
                if (writeChar != null) {
                    setState(State.CONNECTED)
                    startSending()
                } else {
                    disconnect()
                }
            }
        }
    }

    // -------- 持续发送 --------

    private fun startSending() {
        mainHandler.removeCallbacks(sendRunnable)
        mainHandler.post(sendRunnable)
    }

    private fun stopSending() {
        mainHandler.removeCallbacks(sendRunnable)
    }

    @Suppress("DEPRECATION")
    private fun sendCurrentOpcode() {
        val char = writeChar ?: return
        char.value = byteArrayOf(currentOpcode)
        gatt?.writeCharacteristic(char)
    }

    // -------- 工具 --------

    private fun setState(s: State) {
        state = s
        onStateChange?.invoke(s)
    }
}
