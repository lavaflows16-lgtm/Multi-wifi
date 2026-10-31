package app.multiwifi

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private val ssidFields = mutableListOf<EditText>()
    private val passFields = mutableListOf<EditText>()
    private lateinit var cellular: CheckBox
    private lateinit var toggle: Button
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())

    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "MultiWifi"
            textSize = 24f
        })
        root.addView(TextView(this).apply {
            text = "Add the extra WiFi networks to join. Android will ask you to approve each one. " +
                "Your current WiFi is used automatically."
        })

        repeat(2) { i ->
            val ssid = EditText(this).apply { hint = "Extra WiFi ${i + 1} name (SSID)"; setSingleLine() }
            val pass = EditText(this).apply {
                hint = "Password (blank if open)"
                setSingleLine()
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            ssidFields += ssid; passFields += pass
            root.addView(ssid); root.addView(pass)
        }

        cellular = CheckBox(this).apply { text = "Also use mobile data as a link" }
        root.addView(cellular)

        toggle = Button(this).apply { setOnClickListener { onToggle() } }
        root.addView(toggle)

        status = TextView(this).apply { setPadding(0, pad, 0, 0) }
        root.addView(status)

        setContentView(ScrollView(this).apply {
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun onToggle() {
        if (BondState.running) {
            startService(Intent(this, BondService::class.java).setAction(BondService.ACTION_STOP))
        } else {
            val intent = Intent(this, BondService::class.java)
                .putStringArrayListExtra(BondService.EXTRA_SSIDS, ArrayList(ssidFields.map { it.text.toString() }))
                .putStringArrayListExtra(BondService.EXTRA_PASSWORDS, ArrayList(passFields.map { it.text.toString() }))
                .putExtra(BondService.EXTRA_CELLULAR, cellular.isChecked)
            startForegroundService(intent)
        }
        handler.postDelayed({ render() }, 300)
    }

    private fun render() {
        toggle.text = if (BondState.running) "Stop" else "Start"
        status.text = buildString {
            if (BondState.running) {
                appendLine("Running. Point apps at SOCKS5 127.0.0.1:1080")
                appendLine()
                if (BondState.lines.isEmpty()) appendLine("Waiting for networks…")
                BondState.lines.forEach { appendLine(it) }
            } else {
                append("Stopped.")
            }
            BondState.error?.let { appendLine(); append(it) }
        }
    }
}
