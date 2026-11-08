package eu.neilalexander.yggdrasil

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doOnTextChanged
import androidx.localbroadcastmanager.content.LocalBroadcastManager

/** The clearnet mode, the exits and the key to give their owners. Changes apply at once. */
class ClearnetActivity : AppCompatActivity() {
    private lateinit var publicKey: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_clearnet)

        val privateKey = ConfigurationProxy(applicationContext).getJSON().optString("PrivateKey")
        publicKey = privateKey.substring(privateKey.length / 2)
        findViewById<TextView>(R.id.clearnetPublicKey).text = publicKey
        findViewById<TableRow>(R.id.clearnetPublicKeyPanel).setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("public key", publicKey))
            Toast.makeText(applicationContext, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
        }

        findViewById<TableRow>(R.id.clearnetModePanel).setOnClickListener {
            val modes = listOf(CLEARNET_WAN, CLEARNET_EXIT, CLEARNET_WAN_EXIT, CLEARNET_OFF)
            val builder = AlertDialog.Builder(ContextThemeWrapper(this, R.style.YggdrasilDialogs))
            builder.setTitle(getString(R.string.clearnet_mode))
            builder.setSingleChoiceItems(
                modes.map { modeName(this, it) }.toTypedArray(),
                modes.indexOf(Clearnet.mode(this)),
            ) { dialog, which ->
                Clearnet.setMode(this, modes[which])
                dialog.dismiss()
                update()
                reconnect(this)
            }
            builder.setNegativeButton(getString(R.string.cancel)) { dialog, _ -> dialog.cancel() }
            builder.show()
        }

        findViewById<TableRow>(R.id.addClearnetExitPanel).setOnClickListener {
            val input = EditText(this).apply { setSingleLine() }
            val builder = AlertDialog.Builder(ContextThemeWrapper(this, R.style.YggdrasilDialogs))
            builder.setTitle(getString(R.string.clearnet_add_exit))
            builder.setMessage(getString(R.string.clearnet_add_exit_help))
            builder.setView(input)
            builder.setPositiveButton(getString(R.string.peers_add)) { dialog, _ ->
                val key = input.text.toString().trim().lowercase()
                val exits = Clearnet.exits(this)
                if (key !in exits) Clearnet.setExits(this, exits + key)
                dialog.dismiss()
                update()
                reconnect(this)
            }
            builder.setNegativeButton(getString(R.string.cancel)) { dialog, _ -> dialog.cancel() }
            val dialog = builder.create()
            dialog.show()
            val addButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            addButton.isEnabled = false
            input.doOnTextChanged { text, _, _, _ ->
                val key = text.toString().trim().lowercase()
                val error = when {
                    key.isEmpty() -> null
                    !Clearnet.isPublicKey(key) -> getString(R.string.clearnet_exit_invalid)
                    key == publicKey -> getString(R.string.clearnet_exit_own)
                    else -> null
                }
                input.error = error
                addButton.isEnabled = error == null && key.isNotEmpty()
            }
        }
    }

    private fun update() {
        findViewById<TextView>(R.id.clearnetModeValue).text = modeName(this, Clearnet.mode(this))
        val layout = findViewById<TableLayout>(R.id.clearnetExitsLayout)
        layout.removeAllViewsInLayout()
        val exits = Clearnet.exits(this)
        layout.visibility = if (exits.isEmpty()) View.GONE else View.VISIBLE
        val inflater = LayoutInflater.from(this)
        for (exit in exits) {
            val view = inflater.inflate(R.layout.peers_configured, null)
            view.findViewById<TextView>(R.id.addressValue).text = exit
            view.findViewById<ImageButton>(R.id.deletePeerButton).setOnClickListener {
                val builder = AlertDialog.Builder(ContextThemeWrapper(this, R.style.YggdrasilDialogs))
                builder.setTitle(getString(R.string.public_peers_remove_title, exit.take(16) + "…"))
                builder.setPositiveButton(getString(R.string.peers_remove)) { dialog, _ ->
                    Clearnet.setExits(this, Clearnet.exits(this) - exit)
                    dialog.dismiss()
                    update()
                    reconnect(this)
                }
                builder.setNegativeButton(getString(R.string.cancel)) { dialog, _ -> dialog.cancel() }
                builder.show()
            }
            layout.addView(view)
        }
    }

    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(this).registerReceiver(receiver, IntentFilter(PacketTunnelProvider.STATE_INTENT))
        (application as GlobalApplication).subscribe()
        update()
    }

    override fun onPause() {
        super.onPause()
        (application as GlobalApplication).unsubscribe()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            intent.getStringExtra("clearnet")?.let {
                findViewById<TextView>(R.id.clearnetStatus).text = it
            }
        }
    }

    companion object {
        fun modeName(context: Context, mode: String): String = context.getString(
            when (mode) {
                CLEARNET_EXIT -> R.string.clearnet_mode_exit
                CLEARNET_WAN_EXIT -> R.string.clearnet_mode_wan_exit
                CLEARNET_OFF -> R.string.clearnet_mode_off
                else -> R.string.clearnet_mode_wan
            }
        )
    }
}
