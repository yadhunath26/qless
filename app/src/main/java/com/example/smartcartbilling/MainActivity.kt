package com.example.smartcartbilling

import androidx.core.view.WindowCompat
import android.Manifest
import android.bluetooth.*
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.*
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.text.SimpleDateFormat
import java.util.*

// ─────────────────────────────────────────────
// SCREEN CONSTANTS  (used by setScreen)
// ─────────────────────────────────────────────
private const val SCREEN_WELCOME  = 0
private const val SCREEN_CART     = 1
private const val SCREEN_BILL     = 2

class MainActivity : AppCompatActivity() {

    // ── Views – Welcome Screen ──
    private lateinit var screenWelcome: View
    private lateinit var previewView: PreviewView
    private lateinit var btnScanQr: com.google.android.material.button.MaterialButton
    private lateinit var txtScanHint: TextView
    private lateinit var txtModeWelcome: TextView

    // ── Views – Cart Screen ──
    private lateinit var screenCart: View
    private lateinit var txtModeCart: TextView
    private lateinit var txtItemCount: TextView
    private lateinit var txtTotalCart: TextView
    private lateinit var txtBadge: TextView
    private lateinit var itemsContainer: LinearLayout
    private lateinit var emptyState: LinearLayout
    private lateinit var txtItemsHidden: TextView   // hidden, keeps PDF logic working
    private lateinit var btnCheckout: com.google.android.material.button.MaterialButton

    // ── Views – Bill Screen ──
    private lateinit var screenBill: View
    private lateinit var txtTotalBill: TextView
    private lateinit var txtSubtotal: TextView
    private lateinit var txtTotalBillHeader: TextView
    private lateinit var txtTimestamp: TextView
    private lateinit var billItemsContainer: LinearLayout
    private lateinit var txtItemsBill: TextView     // hidden, used by PDF
    private lateinit var btnGenerateBill: com.google.android.material.button.MaterialButton
    private lateinit var btnNewSession: com.google.android.material.button.MaterialButton

    // ── App State ──
    private val database = FirebaseDatabase
        .getInstance("https://qless-be82a-default-rtdb.firebaseio.com/")
        .getReference("cartEvents")

    private var bluetoothGatt: BluetoothGatt? = null
    private val cartItems = mutableMapOf<String, Int>()   // name → qty
    private val cartPrices = mutableMapOf<String, Int>()  // name → unit price (if provided)
    private var scanned = false
    private var currentTotal = 0

    private val SERVICE_UUID = UUID.fromString("12345678-1234-1234-1234-1234567890ab")
    private val CHAR_UUID    = UUID.fromString("abcd1234-5678-90ab-cdef-1234567890ab")

    // ─────────────────────────────────────────
    // onCreate
    // ─────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)          // root layout holding all 3 screens
        WindowCompat.setDecorFitsSystemWindows(window, true)
        bindViews()
        requestPermissionsIfNeeded()

        // ── Welcome screen actions ──
        btnScanQr.setOnClickListener {
            if (previewView.visibility == View.GONE) {
                // First tap: reveal camera
                previewView.visibility = View.VISIBLE
                txtScanHint.visibility = View.VISIBLE
                btnScanQr.text = "Scanning…"
                btnScanQr.isEnabled = false
                scanned = false
                startQrScanner()
            }
        }

        // ── Cart screen actions ──
        btnCheckout.setOnClickListener {
            showBillScreen()
        }

        // ── Bill screen actions ──
        btnGenerateBill.setOnClickListener {
            generatePdfBill()
        }

        btnNewSession.setOnClickListener {
            resetSession()
        }

        setScreen(SCREEN_WELCOME)
    }

    // ─────────────────────────────────────────
    // View Binding
    // ─────────────────────────────────────────

    private fun bindViews() {
        screenWelcome   = findViewById(R.id.screenWelcome)
        previewView     = findViewById(R.id.previewView)
        btnScanQr       = findViewById(R.id.btnScanQr)
        txtScanHint     = findViewById(R.id.txtScanHint)
        txtModeWelcome  = findViewById(R.id.txtMode)

        screenCart      = findViewById(R.id.screenCart)
        txtModeCart     = findViewById(R.id.txtModeCart)
        txtItemCount    = findViewById(R.id.txtItemCount)
        txtTotalCart    = findViewById(R.id.txtTotal)
        txtBadge        = findViewById(R.id.txtBadge)
        itemsContainer  = findViewById(R.id.itemsContainer)
        emptyState      = findViewById(R.id.emptyState)
        txtItemsHidden  = findViewById(R.id.txtItems)
        btnCheckout     = findViewById(R.id.btnCheckout)

        screenBill           = findViewById(R.id.screenBill)
        txtTotalBillHeader   = findViewById(R.id.txtTotal2)
        txtTotalBill         = findViewById(R.id.txtTotalBill)
        txtSubtotal          = findViewById(R.id.txtSubtotal)
        txtTimestamp         = findViewById(R.id.txtTimestamp)
        billItemsContainer   = findViewById(R.id.billItemsContainer)
        txtItemsBill         = findViewById(R.id.txtItemsBill)
        btnGenerateBill      = findViewById(R.id.btnGenerateBill)
        btnNewSession        = findViewById(R.id.btnNewSession)
    }

    // ─────────────────────────────────────────
    // Screen Navigation
    // ─────────────────────────────────────────

    private fun setScreen(screen: Int) {
        screenWelcome.visibility = if (screen == SCREEN_WELCOME) View.VISIBLE else View.GONE
        screenCart.visibility    = if (screen == SCREEN_CART)    View.VISIBLE else View.GONE
        screenBill.visibility    = if (screen == SCREEN_BILL)    View.VISIBLE else View.GONE
    }

    private fun showBillScreen() {
        // Populate bill screen before switching
        val time = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date())
        txtTimestamp.text = time

        val total = currentTotal
        txtTotalBillHeader.text = "Rs $total"
        txtTotalBill.text       = "Rs $total"
        txtSubtotal.text        = "Rs $total"

        // Build bill rows
        billItemsContainer.removeAllViews()
        val sb = StringBuilder("Items:\n")

        for ((name, qty) in cartItems) {
            // Inflate a simple row view for the bill table
            val row = LayoutInflater.from(this)
                .inflate(R.layout.bill_row_item, billItemsContainer, false)

            row.findViewById<TextView>(R.id.billRowName).text = name
            row.findViewById<TextView>(R.id.billRowQty).text  = "x$qty"
            val price = cartPrices[name]
            row.findViewById<TextView>(R.id.billRowPrice).text =
                if (price != null) "Rs ${price * qty}" else "--"

            billItemsContainer.addView(row)
            sb.append("$name x $qty\n")
        }

        // Keep hidden text in sync for PDF
        txtItemsBill.text = sb.toString()

        setScreen(SCREEN_BILL)
    }

    private fun resetSession() {
        cartItems.clear()
        cartPrices.clear()
        currentTotal = 0
        scanned = false
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null

        // Reset welcome screen
        previewView.visibility = View.GONE
        txtScanHint.visibility = View.GONE
        btnScanQr.text = "Start Scanning"
        btnScanQr.isEnabled = true

        setScreen(SCREEN_WELCOME)
    }

    // ─────────────────────────────────────────
    // QR Scanner  (unchanged logic)
    // ─────────────────────────────────────────

    private fun startQrScanner() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            cameraProvider.unbindAll()

            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)

            val scanner = BarcodeScanning.getClient()
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { imageProxy ->
                if (scanned) { imageProxy.close(); return@setAnalyzer }

                val mediaImage = imageProxy.image
                if (mediaImage == null) { imageProxy.close(); return@setAnalyzer }

                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                scanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        if (barcodes.isNotEmpty() && !scanned) {
                            scanned = true
                            val raw = barcodes[0].rawValue
                            if (raw != null) {
                                cameraProvider.unbindAll()
                                previewView.visibility = View.GONE
                                handleQrData(raw)
                            }
                        }
                    }
                    .addOnCompleteListener { imageProxy.close() }
            }

            try {
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                Log.e("SmartCart", "Camera bind error", e)
                Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_LONG).show()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleQrData(qrText: String) {
        val cleanedQrText = qrText.trim()
        val parts = cleanedQrText.split(",").map { it.trim() }

        if (parts.size != 2 || !parts[0].equals("SMARTCART", ignoreCase = true)) {
            Toast.makeText(this, "Invalid QR. Format must be SMARTCART,<MAC>", Toast.LENGTH_LONG).show()
            scanned = false
            btnScanQr.text = "Start Scanning"
            btnScanQr.isEnabled = true
            return
        }

        val macAddress = parts[1].uppercase(Locale.ROOT)

        if (!BluetoothAdapter.checkBluetoothAddress(macAddress)) {
            Toast.makeText(this, "Invalid MAC address format", Toast.LENGTH_SHORT).show()
            scanned = false
            return
        }

        // Transition to Cart screen
        setScreen(SCREEN_CART)
        txtModeCart.text = "Connecting to $macAddress…"

        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (!adapter.isEnabled) {
            Toast.makeText(this, "Please enable Bluetooth", Toast.LENGTH_SHORT).show()
            return
        }

        val device = adapter.getRemoteDevice(macAddress)

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Bluetooth permission missing", Toast.LENGTH_SHORT).show()
            return
        }

        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(this, false, gattCallback)
        }
    }

    // ─────────────────────────────────────────
    // BLE Callback  (unchanged logic)
    // ─────────────────────────────────────────

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) return

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i("SmartCart", "GATT Connected.")
                runOnUiThread { txtModeCart.text = "BLE Connected" }
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.w("SmartCart", "GATT Disconnected. Status: $status")
                runOnUiThread {
                    txtModeCart.text = "Disconnected"
                    Toast.makeText(this@MainActivity, "Disconnected (Status: $status)", Toast.LENGTH_LONG).show()
                }
                bluetoothGatt?.close()
                bluetoothGatt = null
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return

            val service = gatt.getService(SERVICE_UUID) ?: return
            val characteristic = service.getCharacteristic(CHAR_UUID) ?: return

            if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) return

            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(
                UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
            )
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            super.onDescriptorWrite(gatt, descriptor, status)
            if (status == BluetoothGatt.GATT_SUCCESS) Log.i("SmartCart", "Notifications enabled.")
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            val data = value.toString(Charsets.UTF_8)
            runOnUiThread { handleBleData(data) }
        }
    }

    // ─────────────────────────────────────────
    // BLE Data Handler  (unchanged logic + card UI)
    // ─────────────────────────────────────────

    private fun sendCartEvent(item: String, action: String) {
        val event = mapOf("item" to item, "action" to action, "time" to ServerValue.TIMESTAMP)
        database.push().setValue(event)
    }

    private fun handleBleData(data: String) {
        val parts = data.split(",")

        when (parts[0]) {
            "ITEM" -> {
                val name   = parts[1]
                val newQty = parts[2].toInt()
                val oldQty = cartItems.getOrDefault(name, 0)

                if (newQty > oldQty) sendCartEvent(name, "ADD")
                else if (newQty < oldQty) sendCartEvent(name, "REMOVE")

                if (newQty == 0) cartItems.remove(name)
                else cartItems[name] = newQty

                // Store price if provided: ITEM,name,qty,price
                if (parts.size >= 4) {
                    val price = parts[3].toIntOrNull()
                    if (price != null) cartPrices[name] = price
                }
            }

            "TOTAL" -> {
                currentTotal = parts[1].toIntOrNull() ?: currentTotal
                txtTotalCart.text = "Rs ${parts[1]}"
            }

            "MODE" -> {
                txtModeCart.text = parts[1]
            }
        }

        refreshCartUI()
    }

    private fun refreshCartUI() {
        if (cartItems.isEmpty()) {
            emptyState.visibility    = View.VISIBLE
            itemsContainer.visibility = View.GONE
            txtBadge.text  = "0 items"
            txtItemCount.text = "0 items"
        } else {
            emptyState.visibility    = View.GONE
            itemsContainer.visibility = View.VISIBLE

            val count = cartItems.values.sum()
            txtBadge.text     = "$count item${if (count != 1) "s" else ""}"
            txtItemCount.text = "$count item${if (count != 1) "s" else ""}"

            // Rebuild item cards
            itemsContainer.removeAllViews()
            val sb = StringBuilder("Items:\n")

            for ((name, qty) in cartItems) {
                val card = LayoutInflater.from(this)
                    .inflate(R.layout.item_card, itemsContainer, false)

                card.findViewById<TextView>(R.id.txtItemName).text = name
                card.findViewById<TextView>(R.id.txtItemQty).text  = qty.toString()

                val price = cartPrices[name]
                card.findViewById<TextView>(R.id.txtItemPrice).text =
                    if (price != null) "Rs ${price * qty}" else "Rs --"

                itemsContainer.addView(card)
                sb.append("$name x $qty\n")
            }

            // Keep hidden text in sync (used by PDF)
            txtItemsHidden.text = sb.toString()
        }
    }

    // ─────────────────────────────────────────
    // PDF Generation  (unchanged logic)
    // ─────────────────────────────────────────

    private fun generatePdfBill() {
        val pdf      = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
        val page     = pdf.startPage(pageInfo)
        val canvas   = page.canvas
        val paint    = Paint()

        var y = 40f
        paint.textSize = 20f
        paint.isFakeBoldText = true
        canvas.drawText("QLess Bill", 200f, y, paint)

        y += 40f
        paint.textSize = 12f
        paint.isFakeBoldText = false

        // Use the hidden txtItems from whichever screen is active
        val itemsText = if (screenBill.visibility == View.VISIBLE)
            txtItemsBill.text.toString() else txtItemsHidden.text.toString()

        for (line in itemsText.split("\n")) {
            canvas.drawText(line, 40f, y, paint)
            y += 20f
        }

        y += 20f
        paint.textSize = 16f
        paint.isFakeBoldText = true
        canvas.drawText("Total: Rs $currentTotal", 40f, y, paint)

        y += 30f
        paint.textSize = 10f
        paint.isFakeBoldText = false
        val time = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())
        canvas.drawText("Generated on: $time", 40f, y, paint)

        pdf.finishPage(page)

        val fileName = "QLess_Bill_${System.currentTimeMillis()}.pdf"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }

        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        uri?.let {
            contentResolver.openOutputStream(it)?.use { os -> pdf.writeTo(os) }
            Toast.makeText(this, "Bill saved to Downloads ✓", Toast.LENGTH_LONG).show()
        }

        pdf.close()
    }

    // ─────────────────────────────────────────
    // Permissions  (unchanged)
    // ─────────────────────────────────────────

    private fun requestPermissionsIfNeeded() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.CAMERA)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (permissions.isNotEmpty())
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 100)
    }
}