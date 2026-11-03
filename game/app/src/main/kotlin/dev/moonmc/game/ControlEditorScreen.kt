/*
 * MoonMC — игровой слой (GPL-3.0).
 *
 * Редактор раскладки управления: холст и перетаскивание — из LayerController
 * (Zalith Launcher 2, GPL-3.0), панели свойств, слоёв и стилей — наши, Material 3.
 */

package dev.moonmc.game

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.movtery.layer_controller.ControlEditorLayer
import com.movtery.layer_controller.data.ButtonPosition
import com.movtery.layer_controller.data.ButtonSize
import com.movtery.layer_controller.data.VisibilityType
import com.movtery.layer_controller.event.ClickEvent
import com.movtery.layer_controller.layout.createNewLayer
import com.movtery.layer_controller.observable.ObservableButtonStyle
import com.movtery.layer_controller.observable.ObservableControlLayer
import com.movtery.layer_controller.observable.ObservableControlLayout
import com.movtery.layer_controller.observable.ObservableJoystickData
import com.movtery.layer_controller.observable.ObservableNormalData
import com.movtery.layer_controller.observable.ObservableStyleConfig
import com.movtery.layer_controller.observable.ObservableTextData
import com.movtery.layer_controller.observable.ObservableWidget
import com.movtery.layer_controller.utils.snap.SnapMode

/** Полноэкранный редактор раскладки: холст сверху, панель снизу. */
@Composable
fun ControlEditorScreen(
    layout: ObservableControlLayout,
    name: String,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    val layers by layout.layers.collectAsState()
    var selected by remember { mutableStateOf<ObservableWidget?>(null) }
    var focus by remember { mutableStateOf<ObservableControlLayer?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    var sheet by remember { mutableStateOf(Sheet.Normal) }
    var keysFor by remember { mutableStateOf<ObservableWidget?>(null) }
    var confirmDelete by remember { mutableStateOf<ObservableWidget?>(null) }

    fun activeLayer(): ObservableControlLayer =
        focus ?: layers.firstOrNull() ?: layout.addLayer(createNewLayer("Слой 1"))

    fun remove(widget: ObservableWidget) {
        val layer = layers.firstOrNull { l ->
            l.normalButtons.value.any { it.uuid == (widget as? ObservableNormalData)?.uuid } ||
                l.textBoxes.value.any { it.uuid == (widget as? ObservableTextData)?.uuid } ||
                l.joystickButtons.value.any { it.uuid == (widget as? ObservableJoystickData)?.uuid }
        }
        when (widget) {
            is ObservableNormalData -> layer?.removeNormalButton(widget.uuid)
            is ObservableTextData -> layer?.removeTextBox(widget.uuid)
            is ObservableJoystickData -> layer?.removeJoystickButton(widget.uuid)
        }
        if (selected === widget) selected = null
    }

    Box(Modifier.fillMaxSize()) {
        // Затемнение: за холстом видна игра, но кнопки читаются.
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x66000000))
        )
        ControlEditorLayer(
            observedLayout = layout,
            selectedWidget = selected,
            onButtonTap = { widget, layer ->
                selected = widget
                focus = layer
            },
            onBackgroundClick = { selected = null },
            floatingButtons = {
                AssistChipRow(
                    onButton = {
                        val made = activeLayer().addButton()
                        selected = made
                        tab = 0
                    },
                    onJoystick = {
                        val layer = activeLayer()
                        layer.addJoystickButton(Controls.newJoystick())
                        selected = layer.joystickButtons.value.lastOrNull()
                        tab = 0
                    },
                    onText = {
                        val layer = activeLayer()
                        layer.addTextBox(Controls.newText("Текст"))
                        selected = layer.textBoxes.value.lastOrNull()
                        tab = 0
                    },
                )
            },
            enableSnap = true,
            snapInAllLayers = true,
            snapMode = SnapMode.Local,
            focusedLayer = null,
            isDark = isSystemInDarkTheme(),
        )

        // Верхняя панель: выход, имя профиля, сохранение.
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(8.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Filled.ArrowBack, "Отменить", tint = MaterialTheme.colorScheme.onSurface)
                }
                Text(name, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.weight(1f))
                Button(onClick = onSave) {
                    Icon(Icons.Filled.Done, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Сохранить", fontSize = 14.sp)
                }
            }
        }

        // Нижняя панель: свойства / слой / стили.
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(if (sheet == Sheet.Normal) 0.34f else 0.62f),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        when (tab) {
                            0 -> "Свойства"
                            1 -> "Слой"
                            else -> "Стили"
                        },
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 15.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = {
                        sheet = if (sheet == Sheet.Normal) Sheet.Big else Sheet.Normal
                    }) {
                        Icon(
                            if (sheet == Sheet.Normal) Icons.Filled.KeyboardArrowUp
                            else Icons.Filled.KeyboardArrowDown,
                            "Размер панели",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
                    listOf("Свойства", "Слой", "Стили").forEachIndexed { index, label ->
                        Tab(
                            selected = tab == index,
                            onClick = { tab = index },
                            text = { Text(label, fontSize = 12.sp) },
                        )
                    }
                }
                when (tab) {
                    0 -> PropertiesTab(
                        selected = selected,
                        layer = focus,
                        onPickKeys = { keysFor = it },
                        onDelete = { confirmDelete = it },
                        modifier = Modifier.weight(1f),
                    )
                    1 -> LayersTab(
                        layout = layout,
                        selected = focus,
                        onFocus = { focus = it },
                        onDeleteLayer = { layout.removeLayer(it) },
                        modifier = Modifier.weight(1f),
                    )
                    else -> StylesTab(
                        layout = layout,
                        selected = selected,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    keysFor?.let { widget ->
        KeyPickDialog(
            onDismiss = { keysFor = null },
            onPick = { event ->
                when (widget) {
                    is ObservableNormalData -> widget.clickEvents = widget.clickEvents + event
                    else -> Unit
                }
                keysFor = null
            },
        )
    }

    confirmDelete?.let { widget ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Удалить элемент?") },
            text = { Text("Он исчезнет из текущего профиля.") },
            confirmButton = {
                TextButton(onClick = {
                    remove(widget)
                    confirmDelete = null
                }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Отмена") }
            },
        )
    }
}

private enum class Sheet { Normal, Big }

/** Палитра добавления: новые кнопка, стик и текст. */
@Composable
private fun AssistChipRow(
    onButton: () -> Unit,
    onJoystick: () -> Unit,
    onText: () -> Unit,
) {
    AssistChip(onClick = onButton, label = { Text("+ Кнопка", fontSize = 12.sp) })
    AssistChip(onClick = onJoystick, label = { Text("+ Стик", fontSize = 12.sp) })
    AssistChip(onClick = onText, label = { Text("+ Текст", fontSize = 12.sp) })
}

/** Новая кнопка сразу с клавишей «пробел» — её видно на холсте. */
private fun ObservableControlLayer.addButton(): ObservableNormalData {
    val made = Controls.newButton().copy(_clickEvents = listOf(ClickEvent(ClickEvent.Type.Key, "GLFW_KEY_SPACE")))
    addNormalButton(made)
    return normalButtons.value.last()
}

/* ------------------------------- свойства ------------------------------- */

@Composable
private fun PropertiesTab(
    selected: ObservableWidget?,
    layer: ObservableControlLayer?,
    onPickKeys: (ObservableWidget) -> Unit,
    onDelete: (ObservableWidget) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selected == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Выберите кнопку, стик или текст на экране",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (selected) {
            is ObservableNormalData -> NormalProperties(selected, onPickKeys)
            is ObservableJoystickData -> JoystickProperties(selected)
            is ObservableTextData -> TextProperties(selected)
        }
        OutlinedButton(onClick = { onDelete(selected) }) {
            Icon(Icons.Filled.Delete, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Удалить элемент")
        }
        Text(
            "Слой: ${layer?.name ?: "—"}",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NormalProperties(data: ObservableNormalData, onPickKeys: (ObservableWidget) -> Unit) {
    TextRow("Текст") {
        OutlinedTextField(
            value = data.text.default,
            onValueChange = { data.text.default = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Text("Действия", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (data.clickEvents.isEmpty()) {
        Text("Не назначено", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    data.clickEvents.forEach { event ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                eventLabel(event),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = {
                data.clickEvents = data.clickEvents - event
            }) {
                Icon(Icons.Filled.Close, "Убрать", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    OutlinedButton(onClick = { onPickKeys(data) }) { Text("Задать клавишу") }
    SizeRows(data.buttonSize, minDp = 20f) { data.buttonSize = it }
    VisibilityRows(data.visibilityType) { data.visibilityType = it }
    SwitchRow("Переключатель", data.isToggleable) { data.isToggleable = it }
    SwitchRow("Волна при нажатии", data.isSwipple) { data.isSwipple = it }
    SwitchRow("Пропускать нажатия", data.isPenetrable) { data.isPenetrable = it }
}

@Composable
private fun JoystickProperties(data: ObservableJoystickData) {
    Text("Режим", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = data.triggerMode == com.movtery.layer_controller.data.JoystickTriggerMode.DRAG,
            onClick = { data.triggerMode = com.movtery.layer_controller.data.JoystickTriggerMode.DRAG },
            label = { Text("С протяжкой", fontSize = 12.sp) },
        )
        FilterChip(
            selected = data.triggerMode == com.movtery.layer_controller.data.JoystickTriggerMode.TOUCH,
            onClick = { data.triggerMode = com.movtery.layer_controller.data.JoystickTriggerMode.TOUCH },
            label = { Text("С касания", fontSize = 12.sp) },
        )
    }
    SliderRow(
        "Мёртвая зона",
        data.deadZoneRatio,
        0.02f..0.5f,
    ) { data.deadZoneRatio = it }
    SliderRow(
        "Порог фиксации",
        data.lockThreshold,
        0f..1f,
    ) { data.lockThreshold = it }
    SwitchRow("Фиксация вперёд", data.canLock) { data.canLock = it }
    VisibilityRows(data.visibilityType) { data.visibilityType = it }
    Text(
        "Размер: " + if (data.sizeType == ButtonSize.Type.Dp) {
            data.sizeDp.toInt().toString() + " dp"
        } else {
            (data.sizePercentage / 100f).toString() + "%"
        },
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SliderRow(
        "Размер",
        if (data.sizeType == ButtonSize.Type.Dp) data.sizeDp else data.sizePercentage / 100f,
        if (data.sizeType == ButtonSize.Type.Dp) 40f..240f else 20f..60f,
    ) { value ->
        if (data.sizeType == ButtonSize.Type.Dp) {
            data.sizeDp = value
            data.sizePercentage = (value * 90f).toInt().coerceIn(2000, 10000)
        } else {
            data.sizePercentage = (value * 100f).toInt().coerceIn(2000, 10000)
            data.sizeDp = value * 6f
        }
    }
}

@Composable
private fun TextProperties(data: ObservableTextData) {
    TextRow("Текст") {
        OutlinedTextField(
            value = data.text.default,
            onValueChange = { data.text.default = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    SizeRows(data.buttonSize, minDp = 20f) { data.buttonSize = it }
    VisibilityRows(data.visibilityType) { data.visibilityType = it }
    SwitchRow("Жирный", data.textBold) { data.textBold = it }
    SwitchRow("Курсив", data.textItalic) { data.textItalic = it }
    SwitchRow("Подчёркнутый", data.textUnderline) { data.textUnderline = it }
}

/* --------------------------------- слой --------------------------------- */

@Composable
private fun LayersTab(
    layout: ObservableControlLayout,
    selected: ObservableControlLayer?,
    onFocus: (ObservableControlLayer) -> Unit,
    onDeleteLayer: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val layers by layout.layers.collectAsState()
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            OutlinedButton(onClick = {
                val made = layout.addLayer(createNewLayer("Слой " + (layers.size + 1)))
                onFocus(made)
            }) {
                Icon(Icons.Filled.Add, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Слой", fontSize = 12.sp)
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(layers, key = { it.uuid }) { layer ->
                val index = layers.indexOf(layer)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { onFocus(layer) }) {
                        Icon(
                            Icons.Filled.Check,
                            "Сделать активным",
                            tint = if (selected === layer) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    Text(
                        layer.name,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {
                        layer.hide = !layer.hide
                        layer.editorHide = layer.hide
                    }) {
                        Icon(
                            Icons.Filled.Close,
                            if (layer.hide) "Показать" else "Скрыть",
                            tint = if (layer.hide) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    IconButton(onClick = {
                        if (index > 0) layout.reorder(index, index - 1)
                    }) { Icon(Icons.Filled.KeyboardArrowUp, "Выше") }
                    IconButton(onClick = {
                        if (index < layers.lastIndex) layout.reorder(index, index + 1)
                    }) { Icon(Icons.Filled.KeyboardArrowDown, "Ниже") }
                    IconButton(onClick = { onDeleteLayer(layer.uuid) }) {
                        Icon(Icons.Filled.Delete, "Удалить", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

/* -------------------------------- стили --------------------------------- */

@Composable
private fun StylesTab(
    layout: ObservableControlLayout,
    selected: ObservableWidget?,
    modifier: Modifier = Modifier,
) {
    val styles by layout.styles.collectAsState()
    val joystickStyles by layout.joystickStyles.collectAsState()
    var editing by remember { mutableStateOf<ObservableButtonStyle?>(null) }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Стили кнопок", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        styles.forEach { style ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(20.dp)
                        .background(style.lightStyle.backgroundColor, RoundedCornerShape(6.dp))
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    style.name,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    when (selected) {
                        is ObservableNormalData -> selected.buttonStyle = style.uuid
                        is ObservableTextData -> selected.buttonStyle = style.uuid
                        else -> Unit
                    }
                }) { Icon(Icons.Filled.Done, "Назначить") }
                IconButton(onClick = { layout.cloneStyle(style) }) {
                    Icon(Icons.Filled.Add, "Копия")
                }
                IconButton(onClick = { layout.removeStyle(style.uuid) }) {
                    Icon(Icons.Filled.Delete, "Удалить")
                }
                IconButton(onClick = { editing = style }) {
                    Icon(Icons.Filled.KeyboardArrowDown, "Цвета")
                }
            }
            if (editing === style) {
                ColorRows(
                    config = if (style.commonStyle) style.lightStyle else style.darkStyle,
                    onCommon = { style.commonStyle = it },
                )
            }
        }
        HorizontalDivider()
        Text("Стили стика", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        joystickStyles.forEach { style ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    style.name,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    (selected as? ObservableJoystickData)?.joystickStyleId = style.uuid
                }) { Icon(Icons.Filled.Done, "Назначить") }
                IconButton(onClick = { layout.cloneJoystickStyle(style) }) {
                    Icon(Icons.Filled.Add, "Копия")
                }
                IconButton(onClick = { layout.removeJoystickStyle(style.uuid) }) {
                    Icon(Icons.Filled.Delete, "Удалить")
                }
            }
        }
    }
}

/** Цвета стиля: hex-поля и прозрачность. */
@Composable
private fun ColorRows(config: ObservableStyleConfig, onCommon: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HexRow("Фон", config.backgroundColor) { config.backgroundColor = it }
        HexRow("Текст", config.contentColor) { config.contentColor = it }
        HexRow("Рамка", config.borderColor) { config.borderColor = it }
        SliderRow("Прозрачность", config.alpha, 0f..1f) { config.alpha = it }
    }
}

@Composable
private fun HexRow(label: String, color: Color, onChange: (Color) -> Unit) {
    var text by remember { mutableStateOf(String.format("#%08X", color.toArgb())) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(78.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { value ->
                text = value
                runCatching { android.graphics.Color.parseColor(value) }
                    .onSuccess { onChange(Color(it)) }
            },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.size(20.dp).background(color, RoundedCornerShape(6.dp)))
    }
}

private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255f).toInt(),
    (red * 255f).toInt(),
    (green * 255f).toInt(),
    (blue * 255f).toInt(),
)

/* ------------------------------ маленькие ------------------------------- */

@Composable
private fun TextRow(label: String, content: @Composable () -> Unit) {
    Column {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Text(
            label + ": " + String.format("%.2f", value),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
        )
    }
}

@Composable
private fun VisibilityRows(current: VisibilityType, onChange: (VisibilityType) -> Unit) {
    Text("Показывать", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            VisibilityType.ALWAYS to "Всегда",
            VisibilityType.IN_GAME to "В игре",
            VisibilityType.IN_MENU to "В меню",
        ).forEach { (type, label) ->
            FilterChip(
                selected = current == type,
                onClick = { onChange(type) },
                label = { Text(label, fontSize = 12.sp) },
            )
        }
    }
}

/** Размер виджета: dp или проценты от высоты экрана. */
@Composable
private fun SizeRows(size: ButtonSize, minDp: Float, onChange: (ButtonSize) -> Unit) {
    val dpMode = size.type == ButtonSize.Type.Dp
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = dpMode,
            onClick = {
                onChange(size.copy(type = ButtonSize.Type.Dp))
            },
            label = { Text("dp", fontSize = 12.sp) },
        )
        FilterChip(
            selected = !dpMode,
            onClick = {
                onChange(size.copy(type = ButtonSize.Type.Percentage))
            },
            label = { Text("%", fontSize = 12.sp) },
        )
    }
    if (dpMode) {
        SliderRow("Ширина, dp", size.widthDp, minDp..240f) {
            onChange(size.copy(widthDp = it, widthPercentage = (it * 90f).toInt().coerceIn(100, 10000)))
        }
        SliderRow("Высота, dp", size.heightDp, minDp..240f) {
            onChange(size.copy(heightDp = it, heightPercentage = (it * 90f).toInt().coerceIn(100, 10000)))
        }
    } else {
        SliderRow("Ширина, %", size.widthPercentage / 100f, 1f..80f) {
            onChange(size.copy(widthPercentage = (it * 100f).toInt().coerceIn(100, 10000)))
        }
        SliderRow("Высота, %", size.heightPercentage / 100f, 1f..80f) {
            onChange(size.copy(heightPercentage = (it * 100f).toInt().coerceIn(100, 10000)))
        }
    }
}

/* ------------------------------ выбор клавиш ---------------------------- */

private data class KeyChoice(val name: String, val label: String, val mouse: Boolean = false)

private val keyChoices: List<KeyChoice> = buildList {
    for (letter in 'A'..'Z') add(KeyChoice("GLFW_KEY_" + letter, letter.toString()))
    for (digit in '0'..'9') add(KeyChoice("GLFW_KEY_" + digit, digit.toString()))
    for (fn in 1..12) add(KeyChoice("GLFW_KEY_F" + fn, "F" + fn))
    listOf(
        "SPACE" to "Space",
        "LEFT_SHIFT" to "Shift",
        "LEFT_CONTROL" to "Ctrl",
        "LEFT_ALT" to "Alt",
        "TAB" to "Tab",
        "ESCAPE" to "Esc",
        "ENTER" to "Enter",
        "BACKSPACE" to "⌫",
        "UP" to "↑",
        "DOWN" to "↓",
        "LEFT" to "←",
        "RIGHT" to "→",
        "INSERT" to "Ins",
        "DELETE" to "Del",
        "HOME" to "Home",
        "END" to "End",
        "PAGE_UP" to "PgUp",
        "PAGE_DOWN" to "PgDn",
    ).forEach { (name, label) -> add(KeyChoice("GLFW_KEY_" + name, label)) }
    add(KeyChoice("GLFW_MOUSE_BUTTON_LEFT", "ЛКМ", mouse = true))
    add(KeyChoice("GLFW_MOUSE_BUTTON_RIGHT", "ПКМ", mouse = true))
    add(KeyChoice("GLFW_MOUSE_BUTTON_MIDDLE", "СКМ", mouse = true))
}

private fun eventLabel(event: ClickEvent): String {
    if (event.type == ClickEvent.Type.LauncherEvent) {
        return when {
            event.key.endsWith("BUTTON_LEFT") -> "ЛКМ"
            event.key.endsWith("BUTTON_RIGHT") -> "ПКМ"
            event.key.endsWith("BUTTON_MIDDLE") -> "СКМ"
            event.key.contains("switch_ime") -> "Клавиатура"
            else -> event.key
        }
    }
    val label = keyChoices.firstOrNull { it.name == event.key }?.label
    if (label != null) return label
    return event.key.removePrefix("GLFW_KEY_")
}

@Composable
private fun KeyPickDialog(onDismiss: () -> Unit, onPick: (ClickEvent) -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = keyChoices.filter {
        query.isBlank() || it.label.contains(query, ignoreCase = true) ||
            it.name.contains(query, ignoreCase = true)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Клавиша") },
        text = {
            Column(Modifier.fillMaxHeight(0.9f)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("Поиск") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LazyVerticalGrid(columns = GridCells.Adaptive(64.dp), modifier = Modifier.weight(1f)) {
                    items(shown) { choice ->
                        Surface(
                            onClick = {
                                val type = if (choice.mouse) ClickEvent.Type.LauncherEvent else ClickEvent.Type.Key
                                onPick(ClickEvent(type, choice.name))
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.padding(3.dp).height(40.dp),
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(choice.label, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/** Заглушка не нужна: текст виджета создаётся через createTranslatable. */
