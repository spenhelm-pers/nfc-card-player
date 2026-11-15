package com.spencer.tagtunes

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.IOException

/**
 * Reads tag UIDs from a PN532 board plugged in over USB (CH340/CP2102/FTDI serial chip,
 * board set to HSU/UART mode). For phones and tablets without built-in NFC.
 *
 * UIDs come out in the same format as the phone's own NFC, so tags pair once and work on both.
 * A tag resting on the reader fires [onTag] once; it fires again only after it's lifted and replaced.
 */
class Pn532UsbReader(
    private val context: Context,
    private val onTag: (String) -> Unit,
    private val onStateChanged: () -> Unit,
    private val onError: (String) -> Unit,
) {
    private val usb = context.getSystemService(UsbManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    @Volatile var connected = false
        private set
    @Volatile private var running = false
    private var port: UsbSerialPort? = null
    private var receiverRegistered = false

    private val permissionAction = context.packageName + ".USB_PERMISSION"

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action != permissionAction) return
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) open()
            else onError("USB reader permission denied. Unplug and replug it to ask again.")
        }
    }

    /** True if a supported USB serial device is plugged in at all. */
    fun isPluggedIn(): Boolean = UsbSerialProber.getDefaultProber().findAllDrivers(usb).isNotEmpty()

    fun start() {
        if (running) return
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                context, permissionReceiver, IntentFilter(permissionAction),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(usb).firstOrNull() ?: return
        if (usb.hasPermission(driver.device)) {
            open()
        } else {
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(
                context, 0, Intent(permissionAction).setPackage(context.packageName), flags
            )
            usb.requestPermission(driver.device, pi)
        }
    }

    fun stop() {
        running = false
        try { port?.close() } catch (_: Exception) {}
        port = null
        if (receiverRegistered) {
            context.unregisterReceiver(permissionReceiver)
            receiverRegistered = false
        }
        setConnected(false)
    }

    private fun open() {
        if (running) return
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(usb).firstOrNull() ?: return
        val connection = usb.openDevice(driver.device) ?: run {
            onError("Couldn't open the USB reader"); return
        }
        val p = driver.ports[0]
        try {
            p.open(connection)
            p.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        } catch (e: IOException) {
            try { p.close() } catch (_: Exception) {}
            onError("Couldn't open the USB reader: ${e.message}")
            return
        }
        port = p
        running = true
        Thread({ pollLoop(p) }, "pn532").start()
    }

    private fun pollLoop(p: UsbSerialPort) {
        try {
            if (!initialize(p)) {
                main.post { onError("USB reader isn't answering. Check its switches are set to HSU (UART).") }
                return
            }
            setConnected(true)
            var lastUid: String? = null
            var misses = 0
            while (running) {
                val uid = pollOnce(p)
                if (uid != null) {
                    misses = 0
                    if (uid != lastUid) {
                        lastUid = uid
                        main.post { onTag(uid) }
                    }
                } else if (++misses >= 2) {
                    lastUid = null   // tag lifted; placing it again counts as a new tap
                }
                Thread.sleep(250)
            }
        } catch (_: InterruptedException) {
        } catch (e: IOException) {
            if (running) main.post { onError("USB reader disconnected") }
        } finally {
            running = false
            try { p.close() } catch (_: Exception) {}
            setConnected(false)
        }
    }

    private fun setConnected(value: Boolean) {
        if (connected == value) return
        connected = value
        main.post { onStateChanged() }
    }

    // ---- PN532 protocol ----

    private fun initialize(p: UsbSerialPort): Boolean {
        // Wake the chip from power-down: a long run of 0x55 then zeros.
        p.write(byteArrayOf(0x55, 0x55, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), 500)
        Thread.sleep(50)
        drain(p)
        // SAMConfiguration: normal mode, no secure element.
        transceive(p, byteArrayOf(0x14, 0x01, 0x14, 0x01)) ?: return false
        // RFConfiguration item 5: give up quickly when no tag is present instead of waiting forever.
        transceive(p, byteArrayOf(0x32, 0x05, 0xFF.toByte(), 0x01, 0x02)) ?: return false
        return true
    }

    /** InListPassiveTarget for one ISO14443-A tag (NTAG, MIFARE). Returns its UID or null. */
    private fun pollOnce(p: UsbSerialPort): String? {
        val r = transceive(p, byteArrayOf(0x4A, 0x01, 0x00)) ?: return null
        // r = [NbTg, Tg, SENS_RES(2), SEL_RES, NFCIDLength, NFCID...]
        if (r.isEmpty() || r[0].toInt() == 0 || r.size < 6) return null
        val len = r[5].toInt() and 0xFF
        if (r.size < 6 + len || len == 0) return null
        return r.copyOfRange(6, 6 + len).joinToString("") { "%02X".format(it) }
    }

    /**
     * Send a command (without the 0xD4 prefix) and return the response data
     * (without 0xD5 and the response code), or null on timeout/garbage.
     */
    private fun transceive(p: UsbSerialPort, command: ByteArray, timeoutMs: Long = 1000): ByteArray? {
        val body = byteArrayOf(0xD4.toByte()) + command
        val len = body.size
        val frame = ByteArray(len + 7)
        frame[0] = 0x00; frame[1] = 0x00; frame[2] = 0xFF.toByte()
        frame[3] = len.toByte()
        frame[4] = (0x100 - len).toByte()
        body.copyInto(frame, 5)
        frame[5 + len] = (0x100 - (body.sumOf { it.toInt() and 0xFF } and 0xFF)).toByte()
        frame[6 + len] = 0x00
        p.write(frame, 500)

        val deadline = System.currentTimeMillis() + timeoutMs
        val buf = ArrayList<Byte>()
        val chunk = ByteArray(64)
        while (System.currentTimeMillis() < deadline) {
            val n = p.read(chunk, 100)
            for (i in 0 until n) buf.add(chunk[i])
            val parsed = parseResponse(buf, command[0])
            if (parsed != null) return parsed
        }
        return null
    }

    /** Find the response frame in [buf], skipping the ACK frame (00 00 FF 00 FF 00). */
    private fun parseResponse(buf: List<Byte>, cmd: Byte): ByteArray? {
        var i = 0
        while (i + 4 < buf.size) {
            if (buf[i] == 0x00.toByte() && buf[i + 1] == 0xFF.toByte()) {
                val len = buf[i + 2].toInt() and 0xFF
                val lcs = buf[i + 3].toInt() and 0xFF
                if (len == 0 && lcs == 0xFF) { i += 4; continue }          // ACK
                if ((len + lcs) and 0xFF != 0) { i++; continue }           // not a real header
                val start = i + 4
                if (buf.size < start + len + 1) return null                // wait for more bytes
                val data = buf.subList(start, start + len)
                if (data.size >= 2 && data[0] == 0xD5.toByte() && data[1] == (cmd + 1).toByte()) {
                    return data.subList(2, data.size).toByteArray()
                }
                i = start + len
                continue
            }
            i++
        }
        return null
    }

    private fun drain(p: UsbSerialPort) {
        val tmp = ByteArray(64)
        try { while (p.read(tmp, 50) > 0) { } } catch (_: IOException) {}
    }
}
