package com.example.smartcartbilling

import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.razorpay.Checkout
import com.razorpay.PaymentResultListener
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class PaymentActivity : AppCompatActivity(), PaymentResultListener {

    // ── Payment screen views ──
    private lateinit var txtPayAmount: TextView
    private lateinit var txtPayTotal: TextView
    private lateinit var txtPayCustomer: TextView
    private lateinit var txtPayEmail: TextView
    private lateinit var payItemsContainer: LinearLayout
    private lateinit var btnPay: com.google.android.material.button.MaterialButton
    private lateinit var btnBackToCart: com.google.android.material.button.MaterialButton

    // ── Bill screen views ──
    private lateinit var screenPayment: View
    private lateinit var screenBill: View
    private lateinit var txtBillTotal: TextView
    private lateinit var txtBillTotalHeader: TextView
    private lateinit var txtBillSubtotal: TextView
    private lateinit var txtBillTimestamp: TextView
    private lateinit var txtBillPaymentId: TextView
    private lateinit var billItemsContainer: LinearLayout
    private lateinit var txtItemsBill: TextView
    private lateinit var btnDownloadBill: com.google.android.material.button.MaterialButton
    private lateinit var btnNewSession: com.google.android.material.button.MaterialButton

    // ── Firebase ──
    private val auth = FirebaseAuth.getInstance()
    private val db   = FirebaseDatabase
        .getInstance("https://qless-be82a-default-rtdb.firebaseio.com/")

    // ── Data ──
    private var totalAmount   = 0
    private var itemsJson     = "[]"
    private var customerName  = ""
    private var customerEmail = ""
    private var lastPaymentId = ""

    // ── Carbon footprint kg CO2 per unit ──
    private val carbonFootprint = mapOf(
        "Bread"  to 0.91,
        "Eggs"   to 0.45,
        "Milk"   to 1.35,
        "Butter" to 2.79,
        "Jam"    to 0.50
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_payment_bill)

        Checkout.preload(applicationContext)

        totalAmount   = intent.getIntExtra("total", 0)
        itemsJson     = intent.getStringExtra("itemsJson") ?: "[]"
        customerName  = auth.currentUser?.displayName ?: "Customer"
        customerEmail = auth.currentUser?.email       ?: ""

        bindViews()
        populatePaymentScreen()

        btnPay.setOnClickListener        { startRazorpayPayment() }
        btnBackToCart.setOnClickListener { finish() }
        btnDownloadBill.setOnClickListener { generatePdfBill() }
        btnNewSession.setOnClickListener {
            // Go back to MainActivity fresh
            val intent = Intent(this, MainActivity::class.java)
            intent.putExtra("reset", true)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
            finish()
        }

        showScreen(false) // start on payment screen
    }

    // ─────────────────────────────────────────
    // Bind Views
    // ─────────────────────────────────────────

    private fun bindViews() {
        screenPayment    = findViewById(R.id.screenPayment)
        screenBill       = findViewById(R.id.screenBill)

        // Payment screen
        txtPayAmount     = findViewById(R.id.txtPayAmount)
        txtPayTotal      = findViewById(R.id.txtPayTotal)
        txtPayCustomer   = findViewById(R.id.txtPayCustomer)
        txtPayEmail      = findViewById(R.id.txtPayEmail)
        payItemsContainer= findViewById(R.id.payItemsContainer)
        btnPay           = findViewById(R.id.btnPay)
        btnBackToCart    = findViewById(R.id.btnBackToCart)

        // Bill screen
        txtBillTotalHeader = findViewById(R.id.txtBillTotalHeader)
        txtBillTotal       = findViewById(R.id.txtBillTotal)
        txtBillSubtotal    = findViewById(R.id.txtBillSubtotal)
        txtBillTimestamp   = findViewById(R.id.txtBillTimestamp)
        txtBillPaymentId   = findViewById(R.id.txtBillPaymentId)
        billItemsContainer = findViewById(R.id.billItemsContainer)
        txtItemsBill       = findViewById(R.id.txtItemsBill)
        btnDownloadBill    = findViewById(R.id.btnDownloadBill)
        btnNewSession      = findViewById(R.id.btnNewSession)
    }

    // ─────────────────────────────────────────
    // Show/hide screens inside this activity
    // showBill=true → bill screen, false → payment screen
    // ─────────────────────────────────────────

    private fun showScreen(showBill: Boolean) {
        screenPayment.visibility = if (showBill) View.GONE else View.VISIBLE
        screenBill.visibility    = if (showBill) View.VISIBLE else View.GONE
    }

    // ─────────────────────────────────────────
    // Populate Payment Screen
    // ─────────────────────────────────────────

    private fun populatePaymentScreen() {
        txtPayAmount.text   = "Rs $totalAmount"
        txtPayTotal.text    = "Rs $totalAmount"
        txtPayCustomer.text = customerName
        txtPayEmail.text    = customerEmail

        try {
            val arr = JSONArray(itemsJson)
            for (i in 0 until arr.length()) {
                val obj   = arr.getJSONObject(i)
                val name  = obj.getString("name")
                val qty   = obj.getInt("qty")
                val price = obj.getInt("price")
                val row = LayoutInflater.from(this)
                    .inflate(R.layout.bill_row_item, payItemsContainer, false)
                row.findViewById<TextView>(R.id.billRowName).text  = name
                row.findViewById<TextView>(R.id.billRowQty).text   = "x$qty"
                row.findViewById<TextView>(R.id.billRowPrice).text = "Rs $price"
                payItemsContainer.addView(row)
            }
        } catch (e: Exception) {
            Log.e("QLess", "Payment items parse error", e)
        }
    }

    // ─────────────────────────────────────────
    // Populate Bill Screen — called after payment success
    // Uses itemsJson prices (from Firebase) NOT BLE total
    // This fixes the wrong total bug
    // ─────────────────────────────────────────

    private fun populateBillScreen() {
        val time = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date())
        txtBillTimestamp.text  = time
        txtBillPaymentId.text  = if (lastPaymentId.isNotEmpty()) "ID: $lastPaymentId" else "ID: --"

        billItemsContainer.removeAllViews()
        val sb = StringBuilder("Items:\n")
        var totalCarbon    = 0.0
        var recalcTotal    = 0  // ← recalculate total from item prices (fixes wrong total bug)

        try {
            val arr = JSONArray(itemsJson)
            for (i in 0 until arr.length()) {
                val obj   = arr.getJSONObject(i)
                val name  = obj.getString("name")
                val qty   = obj.getInt("qty")
                val price = obj.getInt("price")  // price already = unit price × qty from MainActivity
                recalcTotal += price

                val row = LayoutInflater.from(this)
                    .inflate(R.layout.bill_row_item, billItemsContainer, false)
                row.findViewById<TextView>(R.id.billRowName).text  = name
                row.findViewById<TextView>(R.id.billRowQty).text   = "x$qty"
                row.findViewById<TextView>(R.id.billRowPrice).text = "Rs $price"
                billItemsContainer.addView(row)
                sb.append("$name x$qty = Rs $price\n")

                totalCarbon += (carbonFootprint[name] ?: 0.5) * qty
            }
        } catch (e: Exception) {
            Log.e("QLess", "Bill items parse error", e)
        }

        // Carbon footprint row
        val carbonRow = LayoutInflater.from(this)
            .inflate(R.layout.bill_row_item, billItemsContainer, false)
        carbonRow.findViewById<TextView>(R.id.billRowName).text  = "🌱 Carbon Footprint"
        carbonRow.findViewById<TextView>(R.id.billRowQty).text   = ""
        carbonRow.findViewById<TextView>(R.id.billRowPrice).text = String.format("%.2f kg CO₂", totalCarbon)
        billItemsContainer.addView(carbonRow)

        // Use recalculated total from item prices — not BLE total
        // This ensures bill total always matches item prices shown
        val displayTotal = if (recalcTotal > 0) recalcTotal else totalAmount
        txtBillTotalHeader.text = "Rs $displayTotal"
        txtBillTotal.text       = "Rs $displayTotal"
        txtBillSubtotal.text    = "Rs $displayTotal"
        txtItemsBill.text       = sb.toString()
        totalAmount             = displayTotal   // update for PDF
    }

    // ─────────────────────────────────────────
    // Razorpay Payment
    // ─────────────────────────────────────────

    private fun startRazorpayPayment() {
        btnPay.isEnabled = false
        btnPay.text      = "Opening payment…"

        val checkout = Checkout()
        checkout.setKeyID("rzp_test_SPfRkUYRxObJ6J")
        checkout.setImage(R.mipmap.ic_launcher)

        try {
            val options = JSONObject().apply {
                put("name",        "QLess Smart Cart")
                put("description", "Cart Payment")
                put("currency",    "INR")
                put("amount",      totalAmount * 100)   // paise
                put("prefill", JSONObject().apply {
                    put("name",    customerName)
                    put("email",   customerEmail)
                    put("contact", "9999999999")
                })
                put("theme", JSONObject().apply { put("color", "#2563EB") })
            }
            checkout.open(this, options)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open payment: ${e.message}", Toast.LENGTH_LONG).show()
            btnPay.isEnabled = true
            btnPay.text      = "Pay with Razorpay"
        }
    }

    // ─────────────────────────────────────────
    // Razorpay Callbacks
    // ─────────────────────────────────────────

    override fun onPaymentSuccess(razorpayPaymentId: String?) {
        lastPaymentId = razorpayPaymentId ?: "unknown"
        Log.i("QLess", "Payment success: $lastPaymentId")
        Toast.makeText(this, "Payment successful! 🎉", Toast.LENGTH_SHORT).show()
        saveTransactionToFirebase("SUCCESS")
        populateBillScreen()
        showScreen(true)   // ← show bill screen inside same activity, no race condition
    }

    override fun onPaymentError(errorCode: Int, errorDescription: String?) {
        Log.e("QLess", "Payment error $errorCode: $errorDescription")
        Toast.makeText(this, "Payment failed: $errorDescription", Toast.LENGTH_LONG).show()
        saveTransactionToFirebase("FAILED")
        btnPay.isEnabled = true
        btnPay.text      = "Pay with Razorpay"
    }

    // ─────────────────────────────────────────
    // Save Transaction to Firebase
    // ─────────────────────────────────────────

    private fun saveTransactionToFirebase(status: String) {
        val uid       = auth.currentUser?.uid ?: "anonymous"
        val itemsList = mutableListOf<Map<String, Any>>()
        try {
            val arr = JSONArray(itemsJson)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                itemsList.add(mapOf(
                    "name"  to obj.getString("name"),
                    "qty"   to obj.getInt("qty"),
                    "price" to obj.getInt("price")
                ))
            }
        } catch (_: Exception) {}

        val transaction = mapOf(
            "paymentId"     to lastPaymentId,
            "status"        to status,
            "amount"        to totalAmount,
            "currency"      to "INR",
            "customerName"  to customerName,
            "customerEmail" to customerEmail,
            "customerUid"   to uid,
            "items"         to itemsList,
            "gateway"       to "Razorpay",
            "timestamp"     to ServerValue.TIMESTAMP
        )

        db.getReference("transactions").push().setValue(transaction)
        db.getReference("users/$uid/transactions").push().setValue(transaction)
    }

    // ─────────────────────────────────────────
    // PDF Bill Download
    // ─────────────────────────────────────────

    private fun generatePdfBill() {
        val pdf      = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
        val page     = pdf.startPage(pageInfo)
        val canvas   = page.canvas
        val paint    = Paint()

        var y = 50f
        paint.textSize = 22f; paint.isFakeBoldText = true
        canvas.drawText("QLess — Payment Receipt", 140f, y, paint)

        y += 10f; paint.textSize = 10f; paint.isFakeBoldText = false; paint.color = Color.GRAY
        canvas.drawLine(40f, y + 10f, 555f, y + 10f, paint)
        paint.color = Color.BLACK

        y += 30f; paint.textSize = 12f
        canvas.drawText("Customer : $customerName", 40f, y, paint)
        y += 20f
        canvas.drawText("Email    : $customerEmail", 40f, y, paint)
        y += 20f
        canvas.drawText("Date     : ${SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date())}", 40f, y, paint)
        y += 20f
        canvas.drawText("Payment  : $lastPaymentId", 40f, y, paint)

        y += 20f; paint.color = Color.GRAY
        canvas.drawLine(40f, y, 555f, y, paint)
        paint.color = Color.BLACK

        y += 20f; paint.textSize = 11f; paint.isFakeBoldText = true
        canvas.drawText("ITEM", 40f, y, paint)
        canvas.drawText("QTY", 340f, y, paint)
        canvas.drawText("PRICE", 460f, y, paint)

        y += 6f; paint.color = Color.GRAY
        canvas.drawLine(40f, y, 555f, y, paint)
        paint.color = Color.BLACK; paint.isFakeBoldText = false

        y += 16f
        val billText = txtItemsBill.text.toString()
        for (line in billText.split("\n")) {
            if (line.isBlank() || line == "Items:") continue
            canvas.drawText(line, 40f, y, paint); y += 20f
        }

        y += 6f; paint.color = Color.GRAY
        canvas.drawLine(40f, y, 555f, y, paint)
        paint.color = Color.BLACK

        y += 20f; paint.textSize = 15f; paint.isFakeBoldText = true
        canvas.drawText("Total Paid : Rs $totalAmount", 40f, y, paint)

        // Carbon
        var totalCarbon = 0.0
        try {
            val arr = JSONArray(itemsJson)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                totalCarbon += (carbonFootprint[obj.getString("name")] ?: 0.5) * obj.getInt("qty")
            }
        } catch (_: Exception) {}

        y += 20f; paint.textSize = 11f; paint.isFakeBoldText = false; paint.color = Color.parseColor("#166534")
        canvas.drawText("🌱 Carbon Footprint: ${String.format("%.2f", totalCarbon)} kg CO₂", 40f, y, paint)

        y += 30f; paint.color = Color.GRAY; paint.textSize = 9f
        canvas.drawText("Thank you for shopping with QLess · Powered by Razorpay", 130f, y, paint)

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
            Toast.makeText(this, "✅ Bill saved to Downloads!", Toast.LENGTH_LONG).show()
        }
        pdf.close()
    }

    // ─────────────────────────────────────────
    // Back button — on bill screen go to welcome
    // ─────────────────────────────────────────

    override fun onBackPressed() {
        if (screenBill.visibility == View.VISIBLE) {
            // On bill screen — go to MainActivity fresh
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
            finish()
        } else {
            super.onBackPressed()
        }
    }
}