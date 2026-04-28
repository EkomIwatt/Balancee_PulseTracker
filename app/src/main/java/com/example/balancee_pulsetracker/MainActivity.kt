package com.example.balancee_pulsetracker

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.content.IntentFilter
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), SerialInputOutputManager.Listener {

    private lateinit var pulseTextView: TextView
    private lateinit var dbHelper: DatabaseHelper
    private var usbSerialPort: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private var buffer = ""

    private val ACTION_USB_PERMISSION = "com.example.USB_PERMISSION"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Make it full screen
        hideSystemUI()

        pulseTextView = findViewById(R.id.pulseTextView)
        dbHelper = DatabaseHelper(this)

        // Fetch and display persisted data immediately
        val lastCount = dbHelper.getLastCount()
        pulseTextView.text = String.format("%04d", lastCount)

        // Register receiver for USB permission
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // For Android 13 and above, we must specify the EXPORTED flag.
            // We use RECEIVER_NOT_EXPORTED because this broadcast is only for our app.
            registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            // For older versions, we still use the same receiver logic.
            registerReceiver(usbReceiver, filter)
        }
    }

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onResume() {
        super.onResume()
        setupUsb()
    }

    private fun setupUsb() {
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)

        if (availableDrivers.isEmpty()) return

        val driver = availableDrivers[0]
        val device = driver.device

        // 1. Check Permission FIRST
        if (!manager.hasPermission(device)) {
            val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE else 0
            val permissionIntent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), flags)
            manager.requestPermission(device, permissionIntent)
            return
        }

        // 2. Open Connection
        val connection = manager.openDevice(device) ?: return

        try {
            val port = driver.ports[0]
            port.open(connection)
            port.setParameters(9600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

            // 3. Handshake - Crucial for Arduino/Redmi stability
            port.dtr = true
            port.rts = true

            usbSerialPort = port

            // 4. Start Listener
            ioManager = SerialInputOutputManager(usbSerialPort, this)
            Executors.newSingleThreadExecutor().submit(ioManager)

        } catch (e: Exception) {
            e.printStackTrace()
            // Optional: Show toast error
        }
    }

    override fun onNewData(data: ByteArray?) {
        data?.let {
            val incoming = String(it, Charsets.UTF_8)
            buffer += incoming

            if (buffer.contains("\n")) {
                val lines = buffer.split("\n")
                for (i in 0 until lines.size - 1) {
                    val line = lines[i].trim()
                    if (line.startsWith("PULSE:")) {
                        val countStr = line.removePrefix("PULSE:").trim()
                        countStr.toIntOrNull()?.let { count ->
                            runOnUiThread {
                                pulseTextView.text = String.format("%04d", count)
                                dbHelper.saveCount(count)
                            }
                        }
                    }
                }
                buffer = lines.last()
            }
        }
    }

    override fun onRunError(e: Exception?) {
        // Stop manager if connection drops
        runOnUiThread { setupUsb() }
    }

    override fun onPause() {
        super.onPause()
        stopSerial()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
        stopSerial()
    }

    private val usbReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    val device: android.hardware.usb.UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.apply { setupUsb() } // Permission granted! Start the connection
                    }
                }
            }
        }
    }


    private fun stopSerial() {
        ioManager?.stop()
        ioManager = null
        try {
            usbSerialPort?.close()
        } catch (e: Exception) {}
        usbSerialPort = null
    }
}
