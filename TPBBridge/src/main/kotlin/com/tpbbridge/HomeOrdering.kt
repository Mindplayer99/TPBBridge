/*
 * TPBBridge - Home catalogue ordering support.
 *
 * This file is intentionally isolated from stream/debrid handling. It only
 * controls the order of HomePageList rows and the small ordering dialog.
 *
 * GPL-3.0-or-later.
 */
package com.tpbbridge

import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import java.util.Locale

internal const val PREF_HOME_SOURCES = "home_sources_v8"
internal const val PREF_HOME_ORDER = "home_order_v8"

internal fun homeSourceKey(name: String): String =
    name.trim().lowercase(Locale.ROOT)

internal fun saveHomeNameList(names: List<String>): String {
    val arr = JSONArray()
    names
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinctBy(::homeSourceKey)
        .forEach { arr.put(it) }
    return arr.toString()
}

internal fun loadHomeNameList(json: String): List<String> {
    if (json.isBlank()) return emptyList()
    return try {
        val arr = JSONArray(json)
        buildList {
            for (i in 0 until arr.length()) {
                val value = arr.optString(i).trim()
                if (value.isNotBlank()) add(value)
            }
        }.distinctBy(::homeSourceKey)
    } catch (_: Throwable) {
        emptyList()
    }
}

/**
 * Keeps the user's complete saved order, including temporarily unavailable
 * sources, and appends genuinely new sources at the bottom.
 */
internal fun reconcileHomeOrder(
    savedOrder: List<String>,
    discoveredSources: List<String>
): List<String> {
    val out = savedOrder
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinctBy(::homeSourceKey)
        .toMutableList()
    val known = out.mapTo(mutableSetOf(), ::homeSourceKey)

    discoveredSources
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinctBy(::homeSourceKey)
        .forEach { source ->
            if (known.add(homeSourceKey(source))) out += source
        }

    return out
}

/** Current visible sources, rendered in the user's saved order. */
internal fun visibleHomeOrder(
    fullOrder: List<String>,
    discoveredSources: List<String>
): List<String> {
    val discovered = discoveredSources
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinctBy(::homeSourceKey)
    val byKey = discovered.associateBy(::homeSourceKey)
    return reconcileHomeOrder(fullOrder, discovered)
        .mapNotNull { byKey[homeSourceKey(it)] }
}

/**
 * Applies a reordered visible subset while leaving temporarily missing sources
 * in their saved slots. This prevents a transient source outage from destroying
 * its remembered position.
 */
internal fun mergeVisibleHomeOrder(
    fullOrder: List<String>,
    visibleOrder: List<String>,
    discoveredSources: List<String>
): List<String> {
    val discovered = discoveredSources
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinctBy(::homeSourceKey)
    val visibleKeys = discovered.mapTo(mutableSetOf(), ::homeSourceKey)
    val reordered = visibleOrder
        .filter { homeSourceKey(it) in visibleKeys }
        .distinctBy(::homeSourceKey)

    val out = reconcileHomeOrder(fullOrder, discovered).toMutableList()
    var next = 0
    for (i in out.indices) {
        if (homeSourceKey(out[i]) in visibleKeys && next < reordered.size) {
            out[i] = reordered[next++]
        }
    }

    while (next < reordered.size) {
        val value = reordered[next++]
        if (out.none { homeSourceKey(it) == homeSourceKey(value) }) out += value
    }
    return out.distinctBy(::homeSourceKey)
}

internal fun orderHomeRows(rows: Collection<HomeRow>, homeOrder: List<String>): List<HomeRow> {
    if (rows.size < 2 || homeOrder.isEmpty()) return rows.toList()
    val rank = homeOrder
        .distinctBy(::homeSourceKey)
        .mapIndexed { index, name -> homeSourceKey(name) to index }
        .toMap()

    return rows.withIndex()
        .sortedWith(
            compareBy<IndexedValue<HomeRow>> { rank[homeSourceKey(it.value.name)] ?: Int.MAX_VALUE }
                .thenBy { it.index }
        )
        .map { it.value }
}

internal fun showHomeCatalogueOrderDialog(
    activity: Activity,
    discoveredSources: List<String>,
    initialFullOrder: List<String>,
    onDone: (List<String>) -> Unit
) {
    fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()

    val defaults = discoveredSources
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinctBy(::homeSourceKey)

    if (defaults.isEmpty()) {
        AlertDialog.Builder(activity)
            .setTitle("Home catalogue order")
            .setMessage("No Home catalogues are available yet. Save + refresh once after enabling Recent in TPB.")
            .setPositiveButton("OK", null)
            .show()
        return
    }

    var working = visibleHomeOrder(initialFullOrder, defaults).toMutableList()
    val container = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(4), dp(12), dp(8))
    }

    fun render() {
        container.removeAllViews()
        container.addView(TextView(activity).apply {
            text = "Top = first row on Home"
            textSize = 13f
            alpha = 0.72f
            setPadding(0, 0, 0, dp(8))
        })

        working.forEachIndexed { index, source ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(2), 0, dp(2))
            }

            row.addView(
                TextView(activity).apply {
                    text = "${index + 1}. $source"
                    textSize = 16f
                    setPadding(dp(2), 0, dp(6), 0)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )

            val up = Button(activity).apply {
                text = "↑"
                textSize = 18f
                isEnabled = index > 0
                minimumWidth = 0
                minWidth = 0
                setPadding(dp(12), 0, dp(12), 0)
                setOnClickListener {
                    if (index > 0) {
                        val item = working.removeAt(index)
                        working.add(index - 1, item)
                        render()
                    }
                }
            }
            val down = Button(activity).apply {
                text = "↓"
                textSize = 18f
                isEnabled = index < working.lastIndex
                minimumWidth = 0
                minWidth = 0
                setPadding(dp(12), 0, dp(12), 0)
                setOnClickListener {
                    if (index < working.lastIndex) {
                        val item = working.removeAt(index)
                        working.add(index + 1, item)
                        render()
                    }
                }
            }
            row.addView(up)
            row.addView(down)
            container.addView(row)
        }

        container.addView(Button(activity).apply {
            text = "Reset to default"
            setOnClickListener {
                working = defaults.toMutableList()
                render()
            }
        })
    }

    render()
    val scroll = ScrollView(activity).apply { addView(container) }
    AlertDialog.Builder(activity)
        .setTitle("Home catalogue order")
        .setView(scroll)
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Done") { _, _ ->
            onDone(mergeVisibleHomeOrder(initialFullOrder, working, defaults))
        }
        .show()
}
