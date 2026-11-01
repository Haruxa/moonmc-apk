package dev.moonmc.game

import android.app.Activity
import android.app.Dialog
import android.view.WindowManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as C
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/**
 * Игровой слой MoonMC: маленькая «капля» с шестернёй сверху и панель настроек.
 *
 * Окно прозрачное и лежит поверх нативной поверхности (та же схема, что у экрана
 * загрузки: обычные view под SurfaceView не видны, окно-диалог — видно).
 * Настройки пишутся в `moonmc/perf.txt` в папке игры и читаются движком при старте.
 */
object Overlay {
    private const val FILE = "moonmc/perf.txt"
    private var dialog: Dialog? = null

    fun attach(activity: Activity) {
        activity.runOnUiThread {
            if (dialog?.isShowing == true) return@runOnUiThread
            val view = ComposeView(activity).apply {
                setContent { MaterialTheme(colorScheme = darkColorScheme()) { Panel() } }
            }
            val d = Dialog(activity, android.R.style.Theme_Material_NoActionBar_Fullscreen)
            d.setContentView(view)
            d.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                )
                setDimAmount(0f)
                addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            }
            dialog = d
            d.show()
        }
    }

    private fun file(activity: Activity): File {
        val home = File("/sdcard/MoonMC/horizon")
        return File(home, FILE)
    }

    private fun read(activity: Activity): Map<String, String> =
        runCatching {
            file(activity).readLines()
                .mapNotNull { line ->
                    val i = line.indexOf('=')
                    if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
                }
                .toMap()
        }.getOrDefault(emptyMap())

    private fun write(activity: Activity, values: Map<String, String>) {
        runCatching {
            val f = file(activity)
            f.parentFile?.mkdirs()
            f.writeText(values.entries.joinToString("\n") { "${it.key}=${it.value}" } + "\n")
        }
    }

    @Composable
    private fun Panel() {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val activity = ctx as? Activity ?: return
        var opened by remember { mutableStateOf(false) }
        val saved = remember { read(activity) }
        var dynamicResolution by remember { mutableStateOf(saved["dynamic_resolution"] ?: "on") }
        var hdr by remember { mutableStateOf(saved["hdr"] ?: "off") }
        var fpsCap by remember { mutableStateOf(saved["fps_cap"] ?: "60") }

        fun store() = write(
            activity,
            mapOf(
                "dynamic_resolution" to dynamicResolution,
                "hdr" to hdr,
                "fps_cap" to fpsCap,
            ),
        )

        Box(Modifier.fillMaxSize()) {
            // капля с шестернёй
            Surface(
                modifier = Modifier.padding(12.dp).size(44.dp),
                shape = RoundedCornerShape(16.dp),
                color = C(0xE6101418),
                onClick = { opened = !opened },
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Settings, contentDescription = null, tint = C(0xFF9BE8BE))
                }
            }

            if (opened) {
                Surface(
                    modifier = Modifier.padding(top = 64.dp, start = 12.dp).width(300.dp),
                    shape = RoundedCornerShape(24.dp),
                    color = C(0xF0111418),
                    tonalElevation = 6.dp,
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Производительность", color = C(0xFF9BE8BE), fontSize = 16.sp)
                        Toggle("Динамическое разрешение", dynamicResolution == "on") {
                            dynamicResolution = if (it) "on" else "off"; store()
                        }
                        Toggle("HDR-вывод", hdr == "on") { hdr = if (it) "on" else "off"; store() }
                        Text("Лимит FPS", color = C(0xFFB9C8CE), fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("30", "60", "90", "0").forEach { value ->
                                FilterChip(
                                    selected = fpsCap == value,
                                    onClick = { fpsCap = value; store() },
                                    label = { Text(if (value == "0") "∞" else value, fontSize = 12.sp) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, color = C(0xFFE8F1EC), fontSize = 13.sp)
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}
