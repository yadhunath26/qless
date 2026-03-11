package com.example.smartcartbilling

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import java.text.SimpleDateFormat
import java.util.*

class AdminActivity : AppCompatActivity() {

    // ── Views ──
    private lateinit var txtAdminEmail: TextView
    private lateinit var txtAlertCount: TextView
    private lateinit var txtItemsOnShelf: TextView
    private lateinit var alertsContainer: LinearLayout
    private lateinit var cardNoAlerts: CardView
    private lateinit var inventoryContainer: LinearLayout
    private lateinit var eventsContainer: LinearLayout
    private lateinit var btnClearAlerts: com.google.android.material.button.MaterialButton
    private lateinit var btnAdminSignOut: com.google.android.material.button.MaterialButton

    // ── Firebase ──
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseDatabase
        .getInstance("https://qless-be82a-default-rtdb.firebaseio.com/")

    // ── Listeners (stored so we can remove on destroy) ──
    private var alertsListener: ValueEventListener? = null
    private var inventoryListener: ValueEventListener? = null
    private var eventsListener: ValueEventListener? = null

    // ── Admin email — must match LoginActivity ──
    private val ADMIN_EMAIL = "ckayyappan1@gmail.com"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin)

        // ── Guard: only admin can access this screen ──
        val currentEmail = auth.currentUser?.email ?: ""
        if (currentEmail != ADMIN_EMAIL) {
            Toast.makeText(this, "Access denied", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        bindViews()

        txtAdminEmail.text = currentEmail

        btnAdminSignOut.setOnClickListener { signOut() }
        btnClearAlerts.setOnClickListener  { clearAllAlerts() }

        listenToAlerts()
        listenToInventory()
        listenToShelfEvents()
    }

    // ─────────────────────────────────────────
    // View Binding
    // ─────────────────────────────────────────

    private fun bindViews() {
        txtAdminEmail    = findViewById(R.id.txtAdminEmail)
        txtAlertCount    = findViewById(R.id.txtAlertCount)
        txtItemsOnShelf  = findViewById(R.id.txtItemsOnShelf)
        alertsContainer  = findViewById(R.id.alertsContainer)
        cardNoAlerts     = findViewById(R.id.cardNoAlerts)
        inventoryContainer = findViewById(R.id.inventoryContainer)
        eventsContainer  = findViewById(R.id.eventsContainer)
        btnClearAlerts   = findViewById(R.id.btnClearAlerts)
        btnAdminSignOut  = findViewById(R.id.btnAdminSignOut)
    }

    // ─────────────────────────────────────────
    // Listen to Theft Alerts
    // ─────────────────────────────────────────

    private fun listenToAlerts() {
        val ref = db.getReference("theftAlerts")
        alertsListener = ref.addValueEventListener(object : ValueEventListener {

            override fun onDataChange(snapshot: DataSnapshot) {
                alertsContainer.removeAllViews()
                var count = 0

                for (child in snapshot.children) {
                    val item      = child.child("item").getValue(String::class.java) ?: "Unknown"
                    val shelf     = child.child("shelf").getValue(String::class.java) ?: "Shelf 1"
                    val timestamp = child.child("time").getValue(Long::class.java) ?: 0L
                    val resolved  = child.child("resolved").getValue(Boolean::class.java) ?: false

                    if (resolved) continue  // skip resolved alerts
                    count++

                    val card = LayoutInflater.from(this@AdminActivity)
                        .inflate(R.layout.alert_card, alertsContainer, false)

                    card.findViewById<TextView>(R.id.txtAlertItem).text  = item
                    card.findViewById<TextView>(R.id.txtAlertShelf).text = "$shelf · Not scanned in cart within 15s"
                    card.findViewById<TextView>(R.id.txtAlertTime).text  = formatTime(timestamp)

                    // Tap to mark as resolved
                    val alertKey = child.key
                    card.setOnClickListener {
                        if (alertKey != null) {
                            ref.child(alertKey).child("resolved").setValue(true)
                            Toast.makeText(this@AdminActivity, "Alert resolved", Toast.LENGTH_SHORT).show()
                        }
                    }

                    alertsContainer.addView(card)
                }

                txtAlertCount.text = count.toString()

                if (count == 0) {
                    cardNoAlerts.visibility    = View.VISIBLE
                    alertsContainer.visibility = View.GONE
                } else {
                    cardNoAlerts.visibility    = View.GONE
                    alertsContainer.visibility = View.VISIBLE
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    // ─────────────────────────────────────────
    // Listen to Live Inventory
    // ─────────────────────────────────────────

    private fun listenToInventory() {
        val ref = db.getReference("inventory")
        inventoryListener = ref.addValueEventListener(object : ValueEventListener {

            override fun onDataChange(snapshot: DataSnapshot) {
                inventoryContainer.removeAllViews()
                var totalItems = 0

                for (child in snapshot.children) {
                    val name  = child.child("name").getValue(String::class.java)  ?: child.key ?: "?"
                    val stock = child.child("stock").getValue(Long::class.java)?.toInt() ?: 0
                    val price = child.child("price").getValue(Long::class.java)?.toInt() ?: 0
                    totalItems++

                    val row = LayoutInflater.from(this@AdminActivity)
                        .inflate(R.layout.inventory_row, inventoryContainer, false)

                    row.findViewById<TextView>(R.id.txtInvName).text  = name
                    row.findViewById<TextView>(R.id.txtInvPrice).text = "Rs $price each"

                    val stockView = row.findViewById<TextView>(R.id.txtInvStock)
                    stockView.text = "$stock left"

                    // Turn red if stock is low (≤ 2)
                    if (stock <= 2) {
                        stockView.setTextColor(Color.parseColor("#EF4444"))
                        stockView.background = getDrawable(R.drawable.chip_red_bg)
                    }

                    inventoryContainer.addView(row)
                }

                txtItemsOnShelf.text = totalItems.toString()
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    // ─────────────────────────────────────────
    // Listen to Recent Shelf Events (last 10)
    // ─────────────────────────────────────────

    private fun listenToShelfEvents() {
        val ref = db.getReference("shelfEvents")
            .orderByChild("time")
            .limitToLast(10)

        eventsListener = ref.addValueEventListener(object : ValueEventListener {

            override fun onDataChange(snapshot: DataSnapshot) {
                eventsContainer.removeAllViews()

                // Reverse so newest is on top
                val events = snapshot.children.toList().reversed()

                for (child in events) {
                    val item      = child.child("item").getValue(String::class.java)   ?: "?"
                    val action    = child.child("action").getValue(String::class.java) ?: "?"
                    val timestamp = child.child("time").getValue(Long::class.java)     ?: 0L

                    val row = LayoutInflater.from(this@AdminActivity)
                        .inflate(R.layout.shelf_event_row, eventsContainer, false)

                    row.findViewById<TextView>(R.id.txtEventItem).text   = item
                    row.findViewById<TextView>(R.id.txtEventAction).text = "$action · Shelf 1"
                    row.findViewById<TextView>(R.id.txtEventTime).text   = formatTime(timestamp)

                    // Green dot for PUTBACK, red for PICKUP
                    val dot = row.findViewById<View>(R.id.eventDot)
                    dot.background = if (action == "PICKUP")
                        getDrawable(R.drawable.dot_red)
                    else
                        getDrawable(R.drawable.dot_green)

                    eventsContainer.addView(row)
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    // ─────────────────────────────────────────
    // Clear All Alerts
    // ─────────────────────────────────────────

    private fun clearAllAlerts() {
        db.getReference("theftAlerts").removeValue()
        Toast.makeText(this, "All alerts cleared", Toast.LENGTH_SHORT).show()
    }

    // ─────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────

    private fun formatTime(timestamp: Long): String {
        if (timestamp == 0L) return "--"
        return SimpleDateFormat("dd MMM, hh:mm:ss a", Locale.getDefault()).format(Date(timestamp))
    }

    private fun signOut() {
        auth.signOut()
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build()
        GoogleSignIn.getClient(this, gso).signOut().addOnCompleteListener {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    // ─────────────────────────────────────────
    // Cleanup listeners on destroy
    // ─────────────────────────────────────────

    override fun onDestroy() {
        super.onDestroy()
        alertsListener?.let    { db.getReference("theftAlerts").removeEventListener(it) }
        inventoryListener?.let { db.getReference("inventory").removeEventListener(it) }
        eventsListener?.let    { db.getReference("shelfEvents").removeEventListener(it) }
    }
}