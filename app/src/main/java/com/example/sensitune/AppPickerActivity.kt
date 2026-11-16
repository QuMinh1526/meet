package com.example.sensitune

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.example.sensitune.ui.kit.LabeledSlider
import com.example.sensitune.ui.kit.SectionCard
import com.example.sensitune.ui.kit.ToggleRow

/**
 * Picks target apps for "chỉ trong app đã chọn" and optional per-app X/Y factors.
 */
class AppPickerActivity : Activity() {

    private data class AppRow(
        val pkg: String,
        val label: String,
        var checked: Boolean,
        var override: Pair<Float, Float>?
    )

    private lateinit var prefs: Prefs
    private lateinit var adapter: AppAdapter
    private lateinit var listView: ListView
    private lateinit var etSearch: EditText
    private val allRows = ArrayList<AppRow>()
    private val visibleRows = ArrayList<AppRow>()
    private var loading = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_picker)
        setTitle(R.string.picker_title)

        prefs = Prefs(this)
        adapter = AppAdapter()
        listView = findViewById(R.id.listApps)
        listView.adapter = adapter
        etSearch = findViewById(R.id.etSearch)
        etSearch.hint = getString(R.string.picker_search_hint)

        findViewById<Button>(R.id.btnSaveApps).setOnClickListener { saveAndFinish() }
        findViewById<Button>(R.id.btnCancelApps).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tvPickerHint).text = getString(R.string.picker_hint)

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applyFilter(s?.toString() ?: "")
            }

            override fun afterTextChanged(s: Editable?) {}
        })

        loadApps()
    }

    private fun loadApps() {
        Thread {
            val pm = packageManager
            val selected = prefs.selectedPackages()
            val overrides = prefs.allOverrides()
            val result = ArrayList<AppRow>()
            try {
                val apps = pm.getInstalledApplications(0)
                for (info in apps) {
                    if (info.packageName == packageName) continue
                    if (pm.getLaunchIntentForPackage(info.packageName) == null) continue
                    val label = info.loadLabel(pm).toString()
                    result.add(
                        AppRow(
                            pkg = info.packageName,
                            label = label,
                            checked = selected.contains(info.packageName),
                            override = overrides[info.packageName]
                        )
                    )
                }
                result.sortBy { it.label.lowercase() }
            } catch (t: Throwable) {
                LogBuffer.error("AppPicker", "load apps failed", t)
            }
            runOnUiThread {
                loading = false
                allRows.clear()
                allRows.addAll(result)
                LogBuffer.info("AppPicker", "loaded ${allRows.size} launchable apps")
                applyFilter(etSearch.text.toString())
            }
        }.start()
    }

    private fun applyFilter(query: String) {
        val q = query.trim().lowercase()
        visibleRows.clear()
        for (row in allRows) {
            if (q.isEmpty() || row.label.lowercase().contains(q) ||
                row.pkg.lowercase().contains(q)
            ) {
                visibleRows.add(row)
            }
        }
        adapter.notifyDataSetChanged()
    }

    private fun saveAndFinish() {
        val selected = allRows.filter { it.checked }.map { it.pkg }
        prefs.setSelectedPackages(selected)
        for (row in allRows) {
            prefs.setPerAppFactor(row.pkg, if (row.checked) row.override else null)
        }
        LogBuffer.info(
            "AppPicker",
            "saved ${selected.size} target apps: ${selected.joinToString(",")}"
        )
        Toast.makeText(
            this,
            getString(R.string.picker_saved, selected.size),
            Toast.LENGTH_SHORT
        ).show()
        finish()
    }

    private fun editFactor(row: AppRow) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 16, 40, 0)
        }
        val card = SectionCard(this, getString(R.string.picker_factor_hint))
        val start = row.override ?: Pair(1.0f, 1.0f)
        val sliderX = LabeledSlider(
            this, getString(R.string.factor_x_horizontal), 0.5f, 4f, 0.05f, start.first.coerceIn(0.5f, 4f)
        )
        val sliderY = LabeledSlider(
            this, getString(R.string.factor_y_vertical), 0.5f, 4f, 0.05f, start.second.coerceIn(0.5f, 4f)
        )
        card.addRow(sliderX)
        card.addRow(sliderY)
        val modeRow = ToggleRow(
            this, getString(R.string.picker_drag_mode),
            prefs.modeFor(row.pkg) == 1
        )
        modeRow.onCheckedChange = { checked ->
            prefs.setModeFor(row.pkg, if (checked) 1 else 0)
            LogBuffer.info("AppPicker", "mode for ${row.pkg} -> ${if (checked) "DRAG" else "SENSITIVITY"}")
        }
        card.addRow(modeRow)
        container.addView(card)

        val dialog = AlertDialog.Builder(this)
            .setTitle(row.label)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val cx = sliderX.currentValue().coerceIn(0.5f, 4f)
                val cy = sliderY.currentValue().coerceIn(0.5f, 4f)
                row.override = Pair(cx, cy)
                toast(getString(R.string.picker_override_set, cx, cy))
                adapter.notifyDataSetChanged()
            }
            .setNeutralButton(R.string.picker_clear_override) { _, _ ->
                row.override = null
                adapter.notifyDataSetChanged()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnDismissListener {
            sliderX.release()
            sliderY.release()
            modeRow.release()
            card.release()
        }
        dialog.show()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount(): Int = visibleRows.size
        override fun getItem(position: Int): Any = visibleRows[position]
        override fun getItemId(position: Int): Long = visibleRows[position].pkg.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(this@AppPickerActivity)
                .inflate(R.layout.item_app, parent, false)
            val row = visibleRows[position]
            val cb = view.findViewById<CheckBox>(R.id.cbApp)
            val label = view.findViewById<TextView>(R.id.tvAppLabel)
            val btn = view.findViewById<Button>(R.id.btnAppFactor)
            val overrideText = row.override?.let {
                String.format(java.util.Locale.US, "X %.2f / Y %.2f", it.first, it.second)
            } ?: getString(R.string.picker_use_global)
            label.text = "${row.label}\n${row.pkg}\n$overrideText"
            label.textSize = 12f
            cb.setOnCheckedChangeListener(null)
            cb.isChecked = row.checked
            cb.setOnCheckedChangeListener { _, checked ->
                row.checked = checked
                LogBuffer.debug("AppPicker", "toggle ${row.pkg} -> $checked")
            }
            btn.setOnClickListener { editFactor(row) }
            view.setOnClickListener {
                row.checked = !row.checked
                cb.isChecked = row.checked
            }
            return view
        }
    }

}
