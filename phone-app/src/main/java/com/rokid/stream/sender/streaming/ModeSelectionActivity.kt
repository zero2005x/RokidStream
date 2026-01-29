package com.rokid.stream.sender.streaming

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.rokid.stream.sender.R
import com.rokid.stream.sender.ui.LanguageSelectionActivity
import com.rokid.stream.sender.ui.SettingsActivity
import com.rokid.stream.sender.ui.TransportMode
import com.rokid.stream.sender.util.LocaleManager

/**
 * ModeSelectionActivity - Entry point for choosing streaming mode
 * 
 * Users can select from:
 * - Phone to Glasses: Send phone camera to glasses display
 * - Glasses to Phone: Receive glasses camera on phone display
 * 
 * Also allows selection of transport mode:
 * - BLE L2CAP: Standard Bluetooth Low Energy
 * - Rokid BLE: Optimized for Rokid glasses
 * - WiFi TCP: High bandwidth WiFi streaming
 */
class ModeSelectionActivity : AppCompatActivity() {
    
    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private const val PREFS_NAME = "RokidStreamPrefs"
        private const val KEY_TRANSPORT_MODE = "transport_mode"
        
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA
        )
    }
    
    // Selected mode to launch after permission granted
    private var pendingMode: Class<*>? = null
    
    // Current transport mode
    private var currentTransportMode: TransportMode = TransportMode.BLE_L2CAP
    
    // UI elements
    private lateinit var transportModeIndicator: LinearLayout
    private lateinit var transportModeText: TextView
    private lateinit var transportModeIcon: ImageView
    
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.applyLocale(newBase))
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mode_selection)
        
        // Load saved transport mode
        loadTransportMode()
        
        // Initialize UI elements
        initializeUI()
        
        setupClickListeners()
    }
    
    private fun initializeUI() {
        transportModeIndicator = findViewById(R.id.transport_mode_indicator)
        transportModeText = findViewById(R.id.text_transport_mode)
        transportModeIcon = findViewById(R.id.icon_transport_mode)
        
        updateTransportModeUI()
    }
    
    private fun loadTransportMode() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val modeOrdinal = prefs.getInt(KEY_TRANSPORT_MODE, TransportMode.BLE_L2CAP.ordinal)
        currentTransportMode = TransportMode.entries.getOrElse(modeOrdinal) { TransportMode.BLE_L2CAP }
    }
    
    private fun saveTransportMode(mode: TransportMode) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_TRANSPORT_MODE, mode.ordinal).apply()
    }
    
    private fun updateTransportModeUI() {
        val (textRes, iconRes) = when (currentTransportMode) {
            TransportMode.BLE_L2CAP -> Pair(R.string.transport_ble_l2cap, android.R.drawable.stat_sys_data_bluetooth)
            TransportMode.ROKID_BLE -> Pair(R.string.transport_rokid_ble, android.R.drawable.stat_sys_data_bluetooth)
            TransportMode.WIFI_TCP -> Pair(R.string.transport_wifi, android.R.drawable.stat_notify_sync)
        }
        transportModeText.setText(textRes)
        transportModeIcon.setImageResource(iconRes)
    }
    
    private fun showTransportModeDialog() {
        val modes = arrayOf(
            getString(R.string.transport_ble_l2cap),
            getString(R.string.transport_rokid_ble),
            getString(R.string.transport_wifi)
        )
        
        val descriptions = arrayOf(
            getString(R.string.transport_ble_l2cap_desc),
            getString(R.string.transport_rokid_ble_desc),
            getString(R.string.transport_wifi_desc)
        )
        
        val items = modes.mapIndexed { index, mode -> 
            "$mode\n${descriptions[index]}" 
        }.toTypedArray()
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.transport_mode_title)
            .setSingleChoiceItems(modes, currentTransportMode.ordinal) { dialog, which ->
                currentTransportMode = TransportMode.entries[which]
                saveTransportMode(currentTransportMode)
                updateTransportModeUI()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
    
    private fun setupClickListeners() {
        // Phone to Glasses
        findViewById<androidx.cardview.widget.CardView>(R.id.card_phone_to_glasses)
            .setOnClickListener {
                launchModeWithPermissionCheck(PhoneToGlassesActivity::class.java)
            }
        
        // Glasses to Phone
        findViewById<androidx.cardview.widget.CardView>(R.id.card_glasses_to_phone)
            .setOnClickListener {
                launchModeWithPermissionCheck(GlassesToPhoneActivity::class.java)
            }
        
        // Transport mode indicator (clickable to change mode)
        transportModeIndicator.setOnClickListener {
            showTransportModeDialog()
        }
        
        // Settings button
        findViewById<LinearLayout>(R.id.btn_settings_container).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        
        // Transport mode button
        findViewById<LinearLayout>(R.id.btn_transport_container).setOnClickListener {
            showTransportModeDialog()
        }
        
        // Log manager button
        findViewById<LinearLayout>(R.id.btn_logs_container).setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            intent.putExtra("navigate_to", "logs")
            startActivity(intent)
        }
        
        // Language button
        findViewById<LinearLayout>(R.id.btn_language_container).setOnClickListener {
            startActivity(Intent(this, LanguageSelectionActivity::class.java))
        }
    }
    
    private fun launchModeWithPermissionCheck(activityClass: Class<*>) {
        if (checkPermissions()) {
            startActivity(Intent(this, activityClass))
        } else {
            pendingMode = activityClass
            requestPermissions()
        }
    }
    
    private fun checkPermissions(): Boolean {
        return REQUIRED_PERMISSIONS.all { permission ->
            ActivityCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CODE)
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                // All permissions granted, launch pending mode
                pendingMode?.let {
                    startActivity(Intent(this, it))
                }
            } else {
                Toast.makeText(this, R.string.error_permissions_required, Toast.LENGTH_LONG).show()
            }
            pendingMode = null
        }
    }
    
    /**
     * Get the current transport mode (can be accessed by streaming activities)
     */
    fun getCurrentTransportMode(): TransportMode = currentTransportMode
}
