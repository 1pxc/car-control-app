package com.example.carcontrol

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var ble: BleManager

    // UI 控件
    private lateinit var tvStatus: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnForward: Button
    private lateinit var btnBackward: Button
    private lateinit var btnTurnLeft: Button
    private lateinit var btnTurnRight: Button
    private lateinit var btnStrafeLeft: Button
    private lateinit var btnStrafeRight: Button
    private lateinit var btnStop: Button
    private lateinit var seekSpeed: SeekBar
    private lateinit var tvSpeed: TextView
    private lateinit var tvOpcode: TextView

    // 操作码常量
    private val OP_STOP:         Byte = 0x00
    private val OP_FORWARD:      Byte = 0x04
    private val OP_BACKWARD:     Byte = 0x08
    private val OP_TURN_LEFT:    Byte = 0x0B
    private val OP_TURN_RIGHT:   Byte = 0x10
    private val OP_STRAFE_LEFT:  Byte = 0x14
    private val OP_STRAFE_RIGHT: Byte = 0x18

    private var baseOpcode: Byte = 0x00   // 当前方向码
    private var speed: Int = 0            // 速度档位 0-3

    // 扫描到的设备
    private val scannedDevices = mutableListOf<BluetoothDevice>()
    private val deviceLabels   = mutableListOf<String>()

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) startScanAndShowDialog()
        else Toast.makeText(this, "需要蓝牙权限", Toast.LENGTH_SHORT).show()
    }

    // -------- 生命周期 --------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ble = BleManager(this)
        bindViews()
        setupDirectionButtons()
        setupSpeedSlider()
        setupBleCallbacks()
    }

    override fun onDestroy() {
        super.onDestroy()
        ble.disconnect()
    }

    // -------- 绑定 View --------

    private fun bindViews() {
        tvStatus      = findViewById(R.id.tvStatus)
        btnConnect    = findViewById(R.id.btnConnect)
        btnForward    = findViewById(R.id.btnForward)
        btnBackward   = findViewById(R.id.btnBackward)
        btnTurnLeft   = findViewById(R.id.btnTurnLeft)
        btnTurnRight  = findViewById(R.id.btnTurnRight)
        btnStrafeLeft = findViewById(R.id.btnStrafeLeft)
        btnStrafeRight= findViewById(R.id.btnStrafeRight)
        btnStop       = findViewById(R.id.btnStop)
        seekSpeed     = findViewById(R.id.seekSpeed)
        tvSpeed       = findViewById(R.id.tvSpeed)
        tvOpcode      = findViewById(R.id.tvOpcode)

        btnConnect.setOnClickListener { onConnectClick() }
    }

    // -------- 方向按钮 --------

    private fun setupDirectionButtons() {
        val map = mapOf(
            btnForward     to OP_FORWARD,
            btnBackward    to OP_BACKWARD,
            btnTurnLeft    to OP_TURN_LEFT,
            btnTurnRight   to OP_TURN_RIGHT,
            btnStrafeLeft  to OP_STRAFE_LEFT,
            btnStrafeRight to OP_STRAFE_RIGHT,
            btnStop        to OP_STOP,
        )
        map.forEach { (btn, op) ->
            btn.setOnClickListener {
                setBaseOpcode(op)
                highlightActive(btn)
            }
        }
    }

    private fun setBaseOpcode(op: Byte) {
        baseOpcode = op
        updateOpcode()
    }

    private fun highlightActive(active: Button) {
        val allBtns = listOf(
            btnForward, btnBackward, btnTurnLeft, btnTurnRight,
            btnStrafeLeft, btnStrafeRight, btnStop
        )
        allBtns.forEach { it.alpha = if (it == active) 1.0f else 0.55f }
    }

    // -------- 速度滑块 --------

    private fun setupSpeedSlider() {
        seekSpeed.max = 3
        seekSpeed.progress = 0
        tvSpeed.text = "速度：1 档"

        seekSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                speed = progress
                tvSpeed.text = "速度：${progress + 1} 档"
                updateOpcode()
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })
    }

    // 最终发送码 = 方向基码 + 速度档（停止时速度固定为0）
    private fun updateOpcode() {
        val finalOp = if (baseOpcode == OP_STOP) OP_STOP
                      else (baseOpcode + speed).toByte()
        ble.currentOpcode = finalOp
        tvOpcode.text = "发送：0x%02X".format(finalOp.toInt() and 0xFF)
    }

    // -------- BLE 回调 --------

    private fun setupBleCallbacks() {
        ble.onStateChange = { state ->
            runOnUiThread {
                when (state) {
                    BleManager.State.DISCONNECTED -> {
                        tvStatus.text = "未连接"
                        tvStatus.setTextColor(getColor(R.color.state_disconnected))
                        btnConnect.text = "连接设备"
                        setControlsEnabled(false)
                    }
                    BleManager.State.CONNECTING -> {
                        tvStatus.text = "连接中…"
                        tvStatus.setTextColor(getColor(R.color.state_connecting))
                        btnConnect.text = "取消"
                        setControlsEnabled(false)
                    }
                    BleManager.State.CONNECTED -> {
                        tvStatus.text = "已连接"
                        tvStatus.setTextColor(getColor(R.color.state_connected))
                        btnConnect.text = "断开"
                        setControlsEnabled(true)
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        ble.onDeviceFound = { device ->
            val label = (if (device.name.isNullOrBlank()) "未知设备" else device.name) +
                        "\n${device.address}"
            runOnUiThread {
                if (scannedDevices.none { it.address == device.address }) {
                    scannedDevices.add(device)
                    deviceLabels.add(label)
                }
            }
        }
    }

    private fun setControlsEnabled(enabled: Boolean) {
        listOf(btnForward, btnBackward, btnTurnLeft, btnTurnRight,
               btnStrafeLeft, btnStrafeRight, btnStop, seekSpeed)
            .forEach { it.isEnabled = enabled }
        if (!enabled) {
            listOf(btnForward, btnBackward, btnTurnLeft, btnTurnRight,
                   btnStrafeLeft, btnStrafeRight, btnStop)
                .forEach { it.alpha = 0.4f }
        }
    }

    // -------- 连接/断开 --------

    private fun onConnectClick() {
        when (ble.state) {
            BleManager.State.CONNECTED, BleManager.State.CONNECTING -> ble.disconnect()
            BleManager.State.DISCONNECTED -> checkPermissionsAndScan()
        }
    }

    private fun checkPermissionsAndScan() {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startScanAndShowDialog()
        else permLauncher.launch(missing.toTypedArray())
    }

    @SuppressLint("MissingPermission")
    private fun startScanAndShowDialog() {
        scannedDevices.clear()
        deviceLabels.clear()
        ble.startScan()

        // 扫描 3 秒后停止并弹出设备列表
        Handler(Looper.getMainLooper()).postDelayed({
            ble.stopScan()
            showDeviceDialog()
        }, 3000)

        Toast.makeText(this, "扫描蓝牙设备中…", Toast.LENGTH_SHORT).show()
    }

    private fun showDeviceDialog() {
        if (scannedDevices.isEmpty()) {
            Toast.makeText(this, "未找到设备", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("选择设备")
            .setItems(deviceLabels.toTypedArray()) { _, idx ->
                ble.connect(scannedDevices[idx])
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
