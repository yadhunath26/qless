package com.example.smartcartbilling

import android.Manifest
import android.app.Activity
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
import androidx.core.view.WindowCompat
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

private const val SCREEN_WELCOME  = 0
private const val SCREEN_CART     = 1
private const val SCREEN_BILL     = 2
private const val REQUEST_PAYMENT = 1001

class MainActivity : AppCompatActivity() {

    // ── Views – Welcome ──
    private lateinit var screenWelcome: View
    private lateinit var previewView: PreviewView
    private lateinit var btnScanQr: com.google.android.material.button.MaterialButton
    private lateinit var txtScanHint: TextView
    private lateinit var txtModeWelcome: TextView

    // ── Views – Cart ──
    private lateinit var screenCart: View
    private lateinit var txtModeCart: TextView
    private lateinit var txtItemCount: TextView
    private lateinit var txtTotalCart: TextView
    private lateinit var txtBadge: TextView
    private lateinit var itemsContainer: LinearLayout
    private lateinit var emptyState: LinearLayout
    private lateinit var txtItemsHidden: TextView
    private lateinit var btnCheckout: com.google.android.material.button.MaterialButton

    // ── Views – Bill ──
    private lateinit var screenBill: View
    private lateinit var txtTotalBill: TextView
    private lateinit var txtSubtotal: TextView
    private lateinit var txtTotalBillHeader: TextView
    private lateinit var txtTimestamp: TextView
    private lateinit var billItemsContainer: LinearLayout
    private lateinit var txtItemsBill: TextView
    private lateinit var btnGenerateBill: com.google.android.material.button.MaterialButton
    private lateinit var btnNewSession: com.google.android.material.button.MaterialButton

    // ── Firebase + Auth ──
    private val auth     = FirebaseAuth.getInstance()
    private val db       = FirebaseDatabase
        .getInstance("https://qless-be82a-default-rtdb.firebaseio.com/")
    private val database = db.getReference("cartEvents")

    // ── App State ──
    private var bluetoothGatt: BluetoothGatt? = null
    private val cartItems   = mutableMapOf<String, Int>()   // name → qty
    private val cartPrices  = mutableMapOf<String, Int>()   // name → price per unit (from Firebase)
    private var scanned       = false
    private var currentTotal  = 0
    private var lastPaymentId = ""

    private val SERVICE_UUID = UUID.fromString("12345678-1234-1234-1234-1234567890ab")
    private val CHAR_UUID    = UUID.fromString("abcd1234-5678-90ab-cdef-1234567890ab")

    // ── Carbon Footprint — kg CO2 per unit (hardcoded) ──
    // Source: average estimates for common grocery items
    private val carbonFootprint = mapOf(
        "Bread"  to 0.91,   // kg CO2 per loaf
        "Eggs"   to 0.45,   // kg CO2 per egg (approx)
        "Milk"   to 1.35,   // kg CO2 per litre
        "Butter" to 2.79,   // kg CO2 per 250g pack
        "Jam"    to 0.50    // kg CO2 per jar
    )

    // ── Anti-Theft ──
    private val THEFT_TIMEOUT_MS  = 15_000L
    private val handler           = Handler(Looper.getMainLooper())
    private val theftRunnables    = mutableMapOf<String, Runnable>()
    private var shelfListener: ChildEventListener?     = null
    private var cartTheftListener: ChildEventListener? = null
    private var shelfListenerReady = false
    private var cartListenerReady  = false

    // ─────────────────────────────────────────
    // onCreate
    // ─────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        WindowCompat.setDecorFitsSystemWindows(window, true)

        if (auth.currentUser == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        bindViews()
        requestPermissionsIfNeeded()

        // Load prices from Firebase inventory upfront
        loadPricesFromFirebase()

        btnScanQr.setOnClickListener {
            if (previewView.visibility == View.GONE) {
                previewView.visibility = View.VISIBLE
                txtScanHint.visibility = View.VISIBLE
                btnScanQr.text         = "Scanning…"
                btnScanQr.isEnabled    = false
                scanned                = false
                startQrScanner()
            }
        }

        btnCheckout.setOnClickListener     { openPaymentScreen() }
        btnGenerateBill.setOnClickListener { generatePdfBill() }
        btnNewSession.setOnClickListener   { resetSession() }

        listenToShelfEvents()
        listenToCartEventsForTheft()

        setScreen(SCREEN_WELCOME)
    }

    // ─────────────────────────────────────────
    // Load Prices from Firebase /inventory
    // Runs once on startup — keeps prices in sync
    // so item cards always show correct Rs amount
    // ─────────────────────────────────────────

    private fun loadPricesFromFirebase() {
        db.getReference("inventory")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    for (child in snapshot.children) {
                        val name  = child.child("name").getValue(String::class.java)
                            ?: child.key ?: continue
                        val price = child.child("price").getValue(Long::class.java)
                            ?.toInt() ?: 0
                        cartPrices[name] = price
                    }
                    // Refresh UI in case items already in cart
                    if (cartItems.isNotEmpty()) refreshCartUI()
                    Log.i("QLess", "Prices loaded from Firebase: $cartPrices")
                }
                override fun onCancelled(error: DatabaseError) {
                    Log.e("QLess", "Failed to load prices: ${error.message}")
                }
            })
    }

    // ─────────────────────────────────────────
    // Payment Screen
    // ─────────────────────────────────────────

    private fun openPaymentScreen() {
        if (cartItems.isEmpty()) {
            Toast.makeText(this, "Cart is empty!", Toast.LENGTH_SHORT).show()
            return
        }
        val arr = JSONArray()
        for ((name, qty) in cartItems) {
            arr.put(JSONObject().apply {
                put("name",  name)
                put("qty",   qty)
                put("price", cartPrices[name]?.let { it * qty } ?: 0)
            })
        }
        val intent = Intent(this, PaymentActivity::class.java).apply {
            putExtra("total",         currentTotal)
            putExtra("customerName",  auth.currentUser?.displayName ?: "Customer")
            putExtra("customerEmail", auth.currentUser?.email       ?: "")
            putExtra("itemsJson",     arr.toString())
        }
        startActivityForResult(intent, REQUEST_PAYMENT)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PAYMENT && resultCode == Activity.RESULT_OK) {
            lastPaymentId = data?.getStringExtra("paymentId") ?: ""
            showBillScreen()
        }
    }

    // ─────────────────────────────────────────
    // View Binding
    // ─────────────────────────────────────────

    private fun bindViews() {
        screenWelcome  = findViewById(R.id.screenWelcome)
        previewView    = findViewById(R.id.previewView)
        btnScanQr      = findViewById(R.id.btnScanQr)
        txtScanHint    = findViewById(R.id.txtScanHint)
        txtModeWelcome = findViewById(R.id.txtMode)

        screenCart     = findViewById(R.id.screenCart)
        txtModeCart    = findViewById(R.id.txtModeCart)
        txtItemCount   = findViewById(R.id.txtItemCount)
        txtTotalCart   = findViewById(R.id.txtTotal)
        txtBadge       = findViewById(R.id.txtBadge)
        itemsContainer = findViewById(R.id.itemsContainer)
        emptyState     = findViewById(R.id.emptyState)
        txtItemsHidden = findViewById(R.id.txtItems)
        btnCheckout    = findViewById(R.id.btnCheckout)

        screenBill         = findViewById(R.id.screenBill)
        txtTotalBillHeader = findViewById(R.id.txtTotal2)
        txtTotalBill       = findViewById(R.id.txtTotalBill)
        txtSubtotal        = findViewById(R.id.txtSubtotal)
        txtTimestamp       = findViewById(R.id.txtTimestamp)
        billItemsContainer = findViewById(R.id.billItemsContainer)
        txtItemsBill       = findViewById(R.id.txtItemsBill)
        btnGenerateBill    = findViewById(R.id.btnGenerateBill)
        btnNewSession      = findViewById(R.id.btnNewSession)
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
        val time = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date())
        txtTimestamp.text       = time
        txtTotalBillHeader.text = "Rs $currentTotal"
        txtTotalBill.text       = "Rs $currentTotal"
        txtSubtotal.text        = "Rs $currentTotal"
        // Show Razorpay payment ID on success card
        findViewById<TextView>(R.id.txtPaymentId)?.text =
            if (lastPaymentId.isNotEmpty()) "ID: $lastPaymentId" else "ID: --"


        billItemsContainer.removeAllViews()
        val sb = StringBuilder("Items:\n")

        // Calculate total carbon footprint for the whole cart
        var totalCarbon = 0.0

        for ((name, qty) in cartItems) {
            val row = LayoutInflater.from(this)
                .inflate(R.layout.bill_row_item, billItemsContainer, false)
            row.findViewById<TextView>(R.id.billRowName).text  = name
            row.findViewById<TextView>(R.id.billRowQty).text   = "x$qty"
            val price = cartPrices[name]
            row.findViewById<TextView>(R.id.billRowPrice).text =
                if (price != null) "Rs ${price * qty}" else "--"
            billItemsContainer.addView(row)
            sb.append("$name x $qty\n")

            // Add carbon for this item
            val carbon = carbonFootprint[name] ?: 0.5  // default 0.5 if unknown
            totalCarbon += carbon * qty
        }

        // Add carbon footprint row to bill
        val carbonRow = LayoutInflater.from(this)
            .inflate(R.layout.bill_row_item, billItemsContainer, false)
        carbonRow.findViewById<TextView>(R.id.billRowName).text  = "🌱 Carbon Footprint"
        carbonRow.findViewById<TextView>(R.id.billRowQty).text   = ""
        carbonRow.findViewById<TextView>(R.id.billRowPrice).text =
            String.format("%.2f kg CO₂", totalCarbon)
        billItemsContainer.addView(carbonRow)

        txtItemsBill.text = sb.toString()
        saveCartSession()
        setScreen(SCREEN_BILL)
    }

    // ─────────────────────────────────────────
    // Save Session to Firebase
    // ─────────────────────────────────────────

    private fun saveCartSession() {
        val uid = auth.currentUser?.uid ?: return
        val itemsList = cartItems.map { (name, qty) ->
            mapOf("name" to name, "qty" to qty,
                "price" to (cartPrices[name]?.let { it * qty } ?: 0))
        }
        db.getReference("users/$uid/sessions").push().setValue(
            mapOf("total"     to currentTotal,
                "items"     to itemsList,
                "paymentId" to lastPaymentId,
                "timestamp" to ServerValue.TIMESTAMP)
        )
    }

    // ─────────────────────────────────────────
    // Reset Session
    // ─────────────────────────────────────────

    private fun resetSession() {
        cartItems.clear()
        currentTotal  = 0
        lastPaymentId = ""
        scanned       = false
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null

        previewView.visibility = View.GONE
        txtScanHint.visibility = View.GONE
        btnScanQr.text         = "Start Scanning"
        btnScanQr.isEnabled    = true

        setScreen(SCREEN_WELCOME)
    }

    // ─────────────────────────────────────────
    // Anti-Theft: Listen to Shelf Events
    // ─────────────────────────────────────────

    private fun listenToShelfEvents() {
        val ref = db.getReference("shelfEvents")
            .orderByChild("time")
            .limitToLast(1)

        shelfListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                if (!shelfListenerReady) {
                    shelfListenerReady = true
                    Log.i("QLess-Theft", "Shelf listener ready")
                    return
                }
                val item   = snapshot.child("item").getValue(String::class.java)   ?: return
                val action = snapshot.child("action").getValue(String::class.java) ?: return
                Log.i("QLess-Theft", "Shelf event: $item -> $action")
                when (action) {
                    "PICKUP"  -> startTheftTimer(item)
                    "PUTBACK" -> cancelTheftTimer(item)
                }
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Log.e("QLess-Theft", "shelfEvents error: ${error.message}")
            }
        }
        ref.addChildEventListener(shelfListener!!)
    }

    // ─────────────────────────────────────────
    // Anti-Theft: Listen to Cart Events
    // ─────────────────────────────────────────

    private fun listenToCartEventsForTheft() {
        val ref = db.getReference("cartEvents")
            .orderByChild("time")
            .limitToLast(1)

        cartTheftListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                if (!cartListenerReady) {
                    cartListenerReady = true
                    Log.i("QLess-Theft", "Cart listener ready")
                    return
                }
                val item   = snapshot.child("item").getValue(String::class.java)   ?: return
                val action = snapshot.child("action").getValue(String::class.java) ?: return
                Log.i("QLess-Theft", "Cart event: $item -> $action")
                if (action == "ADD") cancelTheftTimer(item)
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Log.e("QLess-Theft", "cartEvents error: ${error.message}")
            }
        }
        ref.addChildEventListener(cartTheftListener!!)
    }

    // ─────────────────────────────────────────
    // Anti-Theft: Timer Functions
    // ─────────────────────────────────────────

    private fun startTheftTimer(itemName: String) {
        cancelTheftTimer(itemName)
        Log.i("QLess-Theft", "Timer started for $itemName")
        val runnable = Runnable {
            Log.w("QLess-Theft", "THEFT ALERT: $itemName")
            writeTheftAlert(itemName)
        }
        theftRunnables[itemName] = runnable
        handler.postDelayed(runnable, THEFT_TIMEOUT_MS)
    }

    private fun cancelTheftTimer(itemName: String) {
        theftRunnables.remove(itemName)?.let {
            handler.removeCallbacks(it)
            Log.i("QLess-Theft", "Timer cancelled for $itemName")
        }
    }

    private fun writeTheftAlert(itemName: String) {
        db.getReference("theftAlerts").push().setValue(
            mapOf("item"     to itemName,
                "shelf"    to "Shelf 1",
                "resolved" to false,
                "time"     to ServerValue.TIMESTAMP)
        ).addOnSuccessListener {
            Log.i("QLess-Theft", "Alert written for $itemName")
        }
    }

    // ─────────────────────────────────────────
    // QR Scanner
    // ─────────────────────────────────────────

    private fun startQrScanner() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            cameraProvider.unbindAll()
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)
            val scanner  = BarcodeScanning.getClient()
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { imageProxy ->
                if (scanned) { imageProxy.close(); return@setAnalyzer }
                val mediaImage = imageProxy.image
                if (mediaImage == null) { imageProxy.close(); return@setAnalyzer }
                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                scanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        if (barcodes.isNotEmpty() && !scanned) {
                            scanned = true
                            barcodes[0].rawValue?.let { raw ->
                                cameraProvider.unbindAll()
                                previewView.visibility = View.GONE
                                handleQrData(raw)
                            }
                        }
                    }
                    .addOnCompleteListener { imageProxy.close() }
            }
            try {
                cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleQrData(qrText: String) {
        val parts = qrText.trim().split(",").map { it.trim() }
        if (parts.size != 2 || !parts[0].equals("SMARTCART", ignoreCase = true)) {
            Toast.makeText(this, "Invalid QR format", Toast.LENGTH_LONG).show()
            scanned = false; btnScanQr.text = "Start Scanning"; btnScanQr.isEnabled = true
            return
        }
        val macAddress = parts[1].uppercase(Locale.ROOT)
        if (!BluetoothAdapter.checkBluetoothAddress(macAddress)) {
            Toast.makeText(this, "Invalid MAC address", Toast.LENGTH_SHORT).show()
            scanned = false; return
        }
        setScreen(SCREEN_CART)
        txtModeCart.text = "Connecting…"
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (!adapter.isEnabled) {
            Toast.makeText(this, "Enable Bluetooth", Toast.LENGTH_SHORT).show(); return
        }
        val device = adapter.getRemoteDevice(macAddress)
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "BT permission missing", Toast.LENGTH_SHORT).show(); return
        }
        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        else device.connectGatt(this, false, gattCallback)
    }

    // ─────────────────────────────────────────
    // BLE Callback
    // ─────────────────────────────────────────

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (ActivityCompat.checkSelfPermission(
                    this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) return
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                runOnUiThread { txtModeCart.text = "BLE Connected" }
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                runOnUiThread { txtModeCart.text = "Disconnected" }
                bluetoothGatt?.close(); bluetoothGatt = null
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val characteristic =
                gatt.getService(SERVICE_UUID)?.getCharacteristic(CHAR_UUID) ?: return
            if (ActivityCompat.checkSelfPermission(
                    this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) return
            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(
                UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            super.onDescriptorWrite(gatt, descriptor, status)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            runOnUiThread { handleBleData(value.toString(Charsets.UTF_8)) }
        }
    }

    // ─────────────────────────────────────────
    // BLE Data Handler
    // Price is now always read from cartPrices (loaded from Firebase)
    // so Rs -- never appears regardless of what BLE sends
    // ─────────────────────────────────────────

    private fun sendCartEvent(item: String, action: String) {
        database.push().setValue(
            mapOf("item" to item, "action" to action, "time" to ServerValue.TIMESTAMP))
    }

    private fun handleBleData(data: String) {
        val parts = data.split(",")
        when (parts[0]) {
            "ITEM" -> {
                val name   = parts[1]
                val newQty = parts[2].toIntOrNull() ?: 0
                val oldQty = cartItems.getOrDefault(name, 0)

                if (newQty > oldQty) sendCartEvent(name, "ADD")
                else if (newQty < oldQty) sendCartEvent(name, "REMOVE")

                if (newQty == 0) cartItems.remove(name)
                else cartItems[name] = newQty

                // If BLE sends price (4th part), use it — otherwise Firebase price is used
                if (parts.size >= 4) {
                    val blePrice = parts[3].toIntOrNull()
                    if (blePrice != null && blePrice > 0) cartPrices[name] = blePrice
                }
            }
            "TOTAL" -> {
                currentTotal      = parts[1].toIntOrNull() ?: currentTotal
                txtTotalCart.text = "Rs ${parts[1]}"
            }
            "MODE" -> { txtModeCart.text = parts[1] }
        }
        refreshCartUI()
    }

    // ─────────────────────────────────────────
    // Cart UI — shows price + carbon per item
    // ─────────────────────────────────────────

    private fun refreshCartUI() {
        if (cartItems.isEmpty()) {
            emptyState.visibility     = View.VISIBLE
            itemsContainer.visibility = View.GONE
            txtBadge.text             = "0 items"
            txtItemCount.text         = "0 items"
        } else {
            emptyState.visibility     = View.GONE
            itemsContainer.visibility = View.VISIBLE
            val count = cartItems.values.sum()
            txtBadge.text     = "$count item${if (count != 1) "s" else ""}"
            txtItemCount.text = txtBadge.text
            itemsContainer.removeAllViews()
            val sb = StringBuilder("Items:\n")

            for ((name, qty) in cartItems) {
                val card = LayoutInflater.from(this)
                    .inflate(R.layout.item_card, itemsContainer, false)

                card.findViewById<TextView>(R.id.txtItemName).text = name
                card.findViewById<TextView>(R.id.txtItemQty).text  = qty.toString()

                // Price — from Firebase inventory (always correct)
                val price = cartPrices[name]
                card.findViewById<TextView>(R.id.txtItemPrice).text =
                    if (price != null && price > 0) "Rs ${price * qty}" else "Rs --"

                // Carbon footprint for this item
                val carbon = carbonFootprint[name] ?: 0.5
                val carbonTotal = carbon * qty
                val carbonView = card.findViewById<TextView?>(R.id.txtItemCarbon)
                carbonView?.text = String.format("🌱 %.2f kg CO₂", carbonTotal)

                itemsContainer.addView(card)
                sb.append("$name x $qty\n")
            }
            txtItemsHidden.text = sb.toString()
        }
    }

    // ─────────────────────────────────────────
    // PDF Bill
    // ─────────────────────────────────────────

    private fun generatePdfBill() {
        val pdf      = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
        val page     = pdf.startPage(pageInfo)
        val canvas   = page.canvas
        val paint    = Paint()

        var y = 40f
        paint.textSize = 20f; paint.isFakeBoldText = true
        canvas.drawText("QLess Bill", 220f, y, paint)

        y += 40f; paint.textSize = 12f; paint.isFakeBoldText = false
        val itemsText = if (screenBill.visibility == View.VISIBLE)
            txtItemsBill.text.toString() else txtItemsHidden.text.toString()
        for (line in itemsText.split("\n")) {
            canvas.drawText(line, 40f, y, paint); y += 20f
        }

        // Carbon summary in PDF
        var totalCarbon = 0.0
        for ((name, qty) in cartItems) {
            totalCarbon += (carbonFootprint[name] ?: 0.5) * qty
        }
        y += 10f; paint.textSize = 11f
        canvas.drawText("Carbon Footprint: ${String.format("%.2f", totalCarbon)} kg CO2", 40f, y, paint)

        y += 20f; paint.textSize = 16f; paint.isFakeBoldText = true
        canvas.drawText("Total: Rs $currentTotal", 40f, y, paint)

        if (lastPaymentId.isNotEmpty()) {
            y += 24f; paint.textSize = 10f; paint.isFakeBoldText = false
            canvas.drawText("Payment ID: $lastPaymentId", 40f, y, paint)
        }

        y += 30f; paint.textSize = 10f; paint.isFakeBoldText = false
        canvas.drawText("Generated: ${SimpleDateFormat("dd/MM/yyyy HH:mm:ss",
            Locale.getDefault()).format(Date())}", 40f, y, paint)
        y += 20f
        canvas.drawText("Customer: ${auth.currentUser?.displayName ?: ""}", 40f, y, paint)

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
    // Permissions
    // ─────────────────────────────────────────

    private fun requestPermissionsIfNeeded() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED)
            permissions.add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (permissions.isNotEmpty())
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 100)
    }

    // ─────────────────────────────────────────
    // Cleanup
    // ─────────────────────────────────────────

    override fun onDestroy() {
        super.onDestroy()
        for ((_, runnable) in theftRunnables) handler.removeCallbacks(runnable)
        theftRunnables.clear()
        shelfListener?.let     { db.getReference("shelfEvents").removeEventListener(it) }
        cartTheftListener?.let { db.getReference("cartEvents").removeEventListener(it) }
    }
}