package com.example.balancee_pulsetracker

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
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

        pulseTextView = findViewById(R.id.pulseTextView)
        dbHelper = DatabaseHelper(this)

        // Fetch and display persisted data immediately when the app opens
        val lastCount = dbHelper.getLastCount()
        pulseTextView.text = String.format("%04d", lastCount)

        setupUsb()
    }

    private fun setupUsb() {
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
        if (availableDrivers.isEmpty()) return

        val driver = availableDrivers[0]
        val connection = manager.openDevice(driver.device)
        if (connection == null) {
            val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val permissionIntent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), flags)
            manager.requestPermission(driver.device, permissionIntent)
            return
        }

        usbSerialPort = driver.ports[0]
        try {
            usbSerialPort?.open(connection)
            usbSerialPort?.setParameters(9600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

            ioManager = SerialInputOutputManager(usbSerialPort, this)
            Executors.newSingleThreadExecutor().submit(ioManager)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // This triggers continuously as the Arduino streams data
    override fun onNewData(data: ByteArray?) {
        data?.let {
            val incoming = String(it, Charsets.UTF_8)
            buffer += incoming

            // Wait until we have a full line ending in a newline character
            if (buffer.contains("\n")) {
                val lines = buffer.split("\n")
                // Process all complete lines
                for (i in 0 until lines.size - 1) {
                    val line = lines[i].trim()
                    if (line.startsWith("PULSE:")) {
                        val countStr = line.removePrefix("PULSE:")
                        countStr.toIntOrNull()?.let { count ->
                            // UI and Database updates must happen on the main thread
                            runOnUiThread {
                                pulseTextView.text = String.format("%04d", count)
                                dbHelper.saveCount(count)
                            }
                        }
                    }
                }
                // Keep the incomplete part of the stream in the buffer
                buffer = lines.last()
            }
        }
    }

    override fun onRunError(e: Exception?) {}

    override fun onDestroy() {
        super.onDestroy()
        ioManager?.stop()
        usbSerialPort?.close()
    }
}