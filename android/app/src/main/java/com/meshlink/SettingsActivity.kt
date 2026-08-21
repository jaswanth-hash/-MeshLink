package com.meshlink

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.meshlink.network.MeshNetworkManager

class SettingsActivity : AppCompatActivity() {

    private lateinit var app: MeshLinkApp
    private lateinit var network: MeshNetworkManager

    private lateinit var themeRadioGroup: RadioGroup
    private lateinit var displayNameInput: EditText
    private lateinit var autoStartSwitch: SwitchCompat
    private lateinit var nearbyNotificationsSwitch: SwitchCompat
    private lateinit var sendSoundSwitch: SwitchCompat
    private lateinit var enterToSendSwitch: SwitchCompat
    private lateinit var versionText: TextView

    private var updatingUi = false

    private val nearbyPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.values.all { it }
        app.preferences.setAutoStartDiscovery(granted)
        setSwitchChecked(autoStartSwitch, granted)
        if (granted) {
            if (!network.isDiscovering()) network.start()
        } else {
            Toast.makeText(this, R.string.auto_start_permissions_denied, Toast.LENGTH_SHORT).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        app.preferences.setShowNearbyDeviceNotifications(granted)
        setSwitchChecked(nearbyNotificationsSwitch, granted)
        if (!granted) {
            Toast.makeText(this, R.string.notifications_permission_denied, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        app = MeshLinkApp.get()
        network = app.meshSession.ensureNetwork(app)

        themeRadioGroup = findViewById(R.id.themeRadioGroup)
        displayNameInput = findViewById(R.id.settingsDisplayNameInput)
        autoStartSwitch = findViewById(R.id.autoStartSwitch)
        nearbyNotificationsSwitch = findViewById(R.id.nearbyNotificationsSwitch)
        sendSoundSwitch = findViewById(R.id.sendSoundSwitch)
        enterToSendSwitch = findViewById(R.id.enterToSendSwitch)
        versionText = findViewById(R.id.appVersionText)

        findViewById<Button>(R.id.settingsBackButton).setOnClickListener { finish() }
        findViewById<Button>(R.id.settingsSaveNameButton).setOnClickListener { saveDisplayName() }
        findViewById<Button>(R.id.resetDiscoveryButton).setOnClickListener { resetDiscovery() }
        findViewById<Button>(R.id.clearHistoryButton).setOnClickListener { confirmClearHistory() }
        findViewById<Button>(R.id.clearAllDataButton).setOnClickListener { confirmClearAllData() }

        setupPreferenceListeners()
        refreshSettingsUi()
    }

    private fun setupPreferenceListeners() {
        themeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            if (updatingUi) return@setOnCheckedChangeListener
            val theme = when (checkedId) {
                R.id.themeLightRadio -> MeshTheme.LIGHT
                R.id.themeDarkRadio -> MeshTheme.DARK
                else -> MeshTheme.SYSTEM
            }
            app.preferences.setTheme(theme)
            ThemeController.apply(theme)
        }

        autoStartSwitch.setOnCheckedChangeListener { _, checked ->
            if (updatingUi) return@setOnCheckedChangeListener
            app.preferences.setAutoStartDiscovery(checked)
            if (checked) startDiscoveryWhenAllowed()
        }

        nearbyNotificationsSwitch.setOnCheckedChangeListener { _, checked ->
            if (updatingUi) return@setOnCheckedChangeListener
            if (!checked) {
                app.preferences.setShowNearbyDeviceNotifications(false)
                return@setOnCheckedChangeListener
            }
            if (NearbyDeviceNotifier.canPostNotifications(this)) {
                app.preferences.setShowNearbyDeviceNotifications(true)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        sendSoundSwitch.setOnCheckedChangeListener { _, checked ->
            if (!updatingUi) app.preferences.setPlaySendSound(checked)
        }

        enterToSendSwitch.setOnCheckedChangeListener { _, checked ->
            if (!updatingUi) app.preferences.setEnterToSend(checked)
        }
    }

    private fun refreshSettingsUi() {
        updatingUi = true
        themeRadioGroup.check(
            when (app.preferences.getTheme()) {
                MeshTheme.LIGHT -> R.id.themeLightRadio
                MeshTheme.DARK -> R.id.themeDarkRadio
                MeshTheme.SYSTEM -> R.id.themeSystemRadio
            }
        )
        displayNameInput.setText(app.preferences.getDisplayName() ?: network.getDisplayName())
        autoStartSwitch.isChecked = app.preferences.shouldAutoStartDiscovery()
        nearbyNotificationsSwitch.isChecked =
            app.preferences.shouldShowNearbyDeviceNotifications()
        sendSoundSwitch.isChecked = app.preferences.shouldPlaySendSound()
        enterToSendSwitch.isChecked = app.preferences.shouldEnterSendMessage()
        versionText.text = getString(R.string.settings_version, appVersionName())
        updatingUi = false
    }

    private fun saveDisplayName() {
        val name = displayNameInput.text?.toString().orEmpty().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, R.string.display_name_hint, Toast.LENGTH_SHORT).show()
            return
        }
        app.preferences.setDisplayName(name)
        network.setDisplayName(name)
        Toast.makeText(this, R.string.display_name_saved, Toast.LENGTH_SHORT).show()
    }

    private fun startDiscoveryWhenAllowed() {
        val missing = PermissionHelper.missingPermissions(this)
        if (missing.isEmpty()) {
            if (!network.isDiscovering()) network.start()
            return
        }
        nearbyPermissionLauncher.launch(missing)
    }

    private fun resetDiscovery() {
        network.stop()
        if (PermissionHelper.hasAllPermissions(this) && network.start()) {
            Toast.makeText(this, R.string.discovery_reset_searching, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.discovery_reset_permissions, Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmClearHistory() {
        confirm(
            titleRes = R.string.confirm_clear_history_title,
            messageRes = R.string.confirm_clear_history_body
        ) {
            app.messageStore.clearAllMessages()
            Toast.makeText(this, R.string.message_history_cleared, Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmClearAllData() {
        confirm(
            titleRes = R.string.confirm_clear_all_title,
            messageRes = R.string.confirm_clear_all_body
        ) {
            app.messageStore.clearAllMessages()
            app.preferences.clearAll()
            network.stop()
            network.resetDisplayName()
            ThemeController.applySaved(app.preferences)
            refreshSettingsUi()
            Toast.makeText(this, R.string.local_data_cleared, Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirm(titleRes: Int, messageRes: Int, onConfirm: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setMessage(messageRes)
            .setPositiveButton(R.string.clear_action) { _, _ -> onConfirm() }
            .setNegativeButton(R.string.cancel_action, null)
            .show()
    }

    private fun setSwitchChecked(switch: SwitchCompat, checked: Boolean) {
        updatingUi = true
        switch.isChecked = checked
        updatingUi = false
    }

    @Suppress("DEPRECATION")
    private fun appVersionName(): String {
        return runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        }.getOrDefault("1.0")
    }
}
