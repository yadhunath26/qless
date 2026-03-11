package com.example.smartcartbilling

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
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

class PaymentActivity : AppCompatActivity(), PaymentResultListener {

    // ── Views ──
    private lateinit var txtPaymentTotal: TextView
    private lateinit var txtPaymentName: TextView
    private lateinit var txtPaymentEmail: TextView
    private lateinit var paymentItemsContainer: LinearLayout
    private lateinit var btnPay: com.google.android.material.button.MaterialButton
    private lateinit var btnBackToCart: com.google.android.material.button.MaterialButton

    // ── Firebase ──
    private val auth = FirebaseAuth.getInstance()
    private val db   = FirebaseDatabase
        .getInstance("https://qless-be82a-default-rtdb.firebaseio.com/")

    // ── Data passed from MainActivity ──
    private var totalAmount   = 0
    private var itemsJson     = "[]"
    private var customerName  = ""
    private var customerEmail = ""

    // ── Razorpay Key ──
    private val RAZORPAY_KEY = "rzp_test_SPfRkUYRxObJ6J"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_payment)

        // Pre-warm Razorpay (speeds up opening)
        Checkout.preload(applicationContext)

        bindViews()
        loadDataFromIntent()
        populateUI()

        btnPay.setOnClickListener        { startRazorpayPayment() }
        btnBackToCart.setOnClickListener { finish() }
    }

    // ─────────────────────────────────────────
    // Bind Views
    // ─────────────────────────────────────────

    private fun bindViews() {
        txtPaymentTotal       = findViewById(R.id.txtPaymentTotal)
        txtPaymentName        = findViewById(R.id.txtPaymentName)
        txtPaymentEmail       = findViewById(R.id.txtPaymentEmail)
        paymentItemsContainer = findViewById(R.id.paymentItemsContainer)
        btnPay                = findViewById(R.id.btnPay)
        btnBackToCart         = findViewById(R.id.btnBackToCart)
    }

    // ─────────────────────────────────────────
    // Load Intent Data
    // ─────────────────────────────────────────

    private fun loadDataFromIntent() {
        totalAmount   = intent.getIntExtra("total", 0)
        itemsJson     = intent.getStringExtra("itemsJson") ?: "[]"
        customerName  = auth.currentUser?.displayName ?: "Customer"
        customerEmail = auth.currentUser?.email ?: ""
    }

    // ─────────────────────────────────────────
    // Populate UI
    // ─────────────────────────────────────────

    private fun populateUI() {
        txtPaymentTotal.text = "Rs $totalAmount"
        txtPaymentName.text  = customerName
        txtPaymentEmail.text = customerEmail

        try {
            val arr = JSONArray(itemsJson)
            for (i in 0 until arr.length()) {
                val obj   = arr.getJSONObject(i)
                val name  = obj.getString("name")
                val qty   = obj.getInt("qty")
                val price = obj.getInt("price")

                val row = LayoutInflater.from(this)
                    .inflate(R.layout.bill_row_item, paymentItemsContainer, false)
                row.findViewById<TextView>(R.id.billRowName).text  = name
                row.findViewById<TextView>(R.id.billRowQty).text   = "x$qty"
                row.findViewById<TextView>(R.id.billRowPrice).text = "Rs $price"
                paymentItemsContainer.addView(row)
            }
        } catch (e: Exception) {
            Log.e("QLess", "Error parsing items JSON", e)
        }
    }

    // ─────────────────────────────────────────
    // Start Razorpay
    // ─────────────────────────────────────────

    private fun startRazorpayPayment() {
        btnPay.isEnabled = false
        btnPay.text      = "Opening payment…"

        val checkout = Checkout()
        checkout.setKeyID(RAZORPAY_KEY)
        checkout.setImage(R.mipmap.ic_launcher)

        try {
            val options = JSONObject().apply {
                put("name",        "QLess Smart Cart")
                put("description", "Cart Payment — ${itemsJson.let {
                    JSONArray(it).length()
                }} items")
                put("currency",    "INR")
                put("amount",      totalAmount * 100)   // paise
                put("prefill", JSONObject().apply {
                    put("name",    customerName)
                    put("email",   customerEmail)
                    put("contact", "9999999999")
                })
                put("theme", JSONObject().apply {
                    put("color", "#2563EB")
                })
            }

            checkout.open(this, options)

        } catch (e: Exception) {
            Log.e("QLess", "Razorpay open error: ${e.message}")
            Toast.makeText(this, "Could not open payment: ${e.message}", Toast.LENGTH_LONG).show()
            btnPay.isEnabled = true
            btnPay.text      = "Pay with Razorpay"
        }
    }

    // ─────────────────────────────────────────
    // Razorpay Result Callbacks
    // ─────────────────────────────────────────

    override fun onPaymentSuccess(razorpayPaymentId: String?) {
        Log.i("QLess", "✅ Payment success: $razorpayPaymentId")
        Toast.makeText(this, "Payment successful! 🎉", Toast.LENGTH_SHORT).show()
        saveTransactionToFirebase(paymentId = razorpayPaymentId ?: "unknown", status = "SUCCESS")
    }

    override fun onPaymentError(errorCode: Int, errorDescription: String?) {
        Log.e("QLess", "❌ Payment error $errorCode: $errorDescription")
        Toast.makeText(this, "Payment failed: $errorDescription", Toast.LENGTH_LONG).show()
        saveTransactionToFirebase(paymentId = "FAILED_$errorCode", status = "FAILED")
        btnPay.isEnabled = true
        btnPay.text      = "Pay with Razorpay"
    }

    // ─────────────────────────────────────────
    // Save Full Transaction to Firebase
    // ─────────────────────────────────────────

    private fun saveTransactionToFirebase(paymentId: String, status: String) {
        val uid = auth.currentUser?.uid ?: "anonymous"

        // Build items list
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
        } catch (e: Exception) {
            Log.e("QLess", "Items parse error", e)
        }

        val transaction = mapOf(
            "paymentId"     to paymentId,
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

        // Save to /transactions (global — admin can see all)
        db.getReference("transactions").push().setValue(transaction)
            .addOnSuccessListener { Log.i("QLess", "/transactions saved ✓") }

        // Save to /users/$uid/transactions (per-user history)
        db.getReference("users/$uid/transactions").push().setValue(transaction)
            .addOnCompleteListener {
                Log.i("QLess", "/users/$uid/transactions saved ✓")

                // After saving, go back to MainActivity to show bill
                if (status == "SUCCESS") {
                    navigateToBill()
                }
            }
    }

    private fun navigateToBill() {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("showBill",  true)
            putExtra("total",     totalAmount)
            putExtra("itemsJson", itemsJson)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(intent)
        finish()
    }
}