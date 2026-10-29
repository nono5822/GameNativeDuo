package app.gamenative.duo.ui

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gamenative.duo.DualScreenHost
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import timber.log.Timber

/**
 * GameNative Duo's section in GameNative's in-game menu (the one Back opens): the layout editor, the PC editor, the
 * bottom screen's background and widgets, and screen alignment, for the running game. Hooked into upstream's
 * QuickMenu with one line per place (tab id, rail button, title, content); everything else lives here.
 */
object DuoQuickMenu {
    /** True while a game runs with the screen split, so the section has something to act on. */
    fun isAvailable(): Boolean = DualScreenHost.current?.screens != null

    val icon: ImageVector get() = Icons.Default.ViewAgenda

    /** The logo blue (see duo/branding). */
    val accent = Color(0xFF5B8DF6)
}

private val Card = Color(0xFF151A33)
private val CardLine = Color(0x33FFFFFF)
private val Muted = Color(0xFFAEB8DA)
private val Accent = DuoQuickMenu.accent
private val Gradient = listOf(Color(0xFF49D0EE), Color(0xFF5B8DF6), Color(0xFF827EFB), Color(0xFFD74EE9))

@Composable
fun DuoQuickMenuSection(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val session = DualScreenHost.current
    val screens = session?.screens
    val controller = session?.layout
    val context = LocalContext.current
    var layout by remember { mutableStateOf(controller?.layout) }
    var pc by remember { mutableStateOf(screens?.pcEditor) }
    val firstFocus = remember { FocusRequester() }

    DisposableEffect(controller) {
        val listener: (UiLayout) -> Unit = { layout = it }
        controller?.listeners?.add(listener)
        onDispose { controller?.listeners?.remove(listener) }
    }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    val picker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri == null || controller == null) return@rememberLauncherForActivityResult
        Thread({
            val bytes = try {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (t: Throwable) {
                Timber.tag(DualScreenHost.TAG).w(t, "Could not read the chosen image")
                null
            }
            if (bytes != null) {
                Handler(Looper.getMainLooper()).post { controller.setBackgroundImage(bytes, 1240, 1080) }
            }
        }, "GNDuo-pick").start()
    }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DuoMark(Modifier.size(40.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text("GameNative Duo", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color.White)
                val engine = controller?.engine.orEmpty()
                Text(
                    when {
                        screens == null -> "Turn on the screen split for this game to use these options"
                        controller?.modSeen == true -> "Dual-screen mod connected" + if (engine.isNotEmpty()) " ($engine)" else ""
                        else -> "No dual-screen mod running in this game"
                    },
                    color = Muted,
                    fontSize = 13.sp,
                )
            }
        }

        if (screens == null || controller == null || layout == null) return@Column
        val bg = layout!!.background

        Section("Interface layout") {
            Button(
                onClick = {
                    if (screens.openEditor()) onDismiss()
                },
                enabled = controller.modSeen,
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                modifier = Modifier.fillMaxWidth().focusRequester(firstFocus),
            ) { Text("Edit on the bottom screen") }
            Text("Move, resize, hide or send any element to the top screen.", color = Muted, fontSize = 13.sp)
            val info = pc
            if (info == null) {
                OutlinedButton(
                    onClick = { pc = screens.startPcEditor(showCard = false) },
                    enabled = controller.modSeen,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Edit on a PC", color = Color.White) }
            } else {
                PcInfo(info.first, info.second)
                OutlinedButton(
                    onClick = {
                        screens.stopPcEditor()
                        pc = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Stop the PC editor", color = Color.White) }
            }
        }

        Section("Bottom screen background") {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UiBackgroundMode.entries.forEach { mode ->
                    FilterChip(
                        selected = bg.mode == mode,
                        onClick = {
                            if (mode == UiBackgroundMode.IMAGE && !bg.image) {
                                picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
                            } else {
                                controller.setBackground(bg.copy(mode = mode), save = true)
                            }
                        },
                        enabled = controller.supports(mode),
                        label = { Text(mode.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Accent,
                            selectedLabelColor = Color.White,
                            labelColor = Color.White,
                        ),
                    )
                }
            }
            when (bg.mode) {
                UiBackgroundMode.BLUR -> LabeledSlider("Blur", bg.blur) { v, done -> controller.setBackground(bg.copy(blur = v), done) }
                UiBackgroundMode.COLOR -> ColorRow(bg.color) { controller.setBackground(bg.copy(color = it), save = true) }
                UiBackgroundMode.IMAGE -> OutlinedButton(
                    onClick = { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Choose another image", color = Color.White) }
                UiBackgroundMode.GAME -> {}
            }
            if (bg.mode != UiBackgroundMode.COLOR) {
                LabeledSlider("Dim", bg.dim / 0.9f) { v, done -> controller.setBackground(bg.copy(dim = v * 0.9f), done) }
            }
            if (!controller.supports(bg.mode)) {
                Text("This game's mod cannot draw this background: the bottom screen keeps its default.", color = Muted, fontSize = 13.sp)
            }
        }

        Section("Widgets") {
            Text("Live readouts on the bottom screen. Drag them in the editor to place them.", color = Muted, fontSize = 13.sp)
            UiWidgetType.entries.forEach { type ->
                val on = layout!!.widgets.any { it.type == type }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(type.label, color = Color.White, modifier = Modifier.weight(1f))
                    Switch(
                        checked = on,
                        onCheckedChange = { checked ->
                            val list = layout!!.widgets
                            controller.setWidgets(
                                if (checked) {
                                    list + UiWidget(type, 0.03f, (0.04f + 0.09f * list.size).coerceAtMost(0.9f))
                                } else {
                                    list.filter { it.type != type }
                                },
                                save = true,
                            )
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = Color.White),
                    )
                }
            }
        }

        Section("Screens") {
            OutlinedButton(
                onClick = { if (screens.openCalibration()) onDismiss() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Line up the screens", color = Color.White) }
            Text("Hinge gap, pixel size and offset, adjusted live on the bottom screen.", color = Muted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Card, RoundedCornerShape(16.dp))
            .border(1.dp, CardLine, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title.uppercase(), color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        content()
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, onChange: (Float, Boolean) -> Unit) {
    var v by remember(value) { mutableStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, modifier = Modifier.width(56.dp))
        Slider(
            value = v,
            onValueChange = {
                v = it
                onChange(it, false)
            },
            onValueChangeFinished = { onChange(v, true) },
            colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent),
            modifier = Modifier.weight(1f),
        )
    }
}

private val Swatches = listOf(0xFF101322, 0xFF000000, 0xFF1C202C, 0xFF12284E, 0xFF1C4078, 0xFF2E225A, 0xFF541E5C, 0xFF183428)

@Composable
private fun ColorRow(selected: Int, onPick: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Swatches.forEach { argb ->
            val color = argb.toInt()
            OutlinedButton(
                onClick = { onPick(color) },
                shape = CircleShape,
                modifier = Modifier.size(40.dp),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(color)),
                border = BorderStroke(if (color == selected) 3.dp else 1.dp, if (color == selected) Accent else CardLine),
                contentPadding = PaddingValues(0.dp),
            ) {}
        }
    }
}

@Composable
private fun PcInfo(url: String?, code: String) {
    if (url == null) {
        Text("Connect the device to Wi-Fi to use the PC editor.", color = Muted, fontSize = 13.sp)
        return
    }
    val qr = remember(url, code) { qrBitmap("$url?k=$code", 360) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        qr?.let { Image(it.asImageBitmap(), contentDescription = "QR code for the PC editor", modifier = Modifier.size(96.dp)) }
        Spacer(Modifier.width(14.dp))
        Column {
            Text("Open in a browser on the same Wi-Fi", color = Muted, fontSize = 13.sp)
            Text(url, color = Accent, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text("Code  $code", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

private fun qrBitmap(text: String, size: Int): Bitmap? = try {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
        val black = android.graphics.Color.BLACK
        val white = android.graphics.Color.WHITE
        for (y in 0 until size) for (x in 0 until size) setPixel(x, y, if (m[x, y]) black else white)
    }
} catch (t: Throwable) {
    null
}

/** The GameNative Duo mark: the two screens in the logo gradient. */
@Composable
private fun DuoMark(modifier: Modifier) {
    Canvas(modifier) {
        val s = size.minDimension / 108f
        drawRoundRect(Color(0xFF151A33), cornerRadius = CornerRadius(24 * s))
        val brush = Brush.linearGradient(Gradient, start = Offset.Zero, end = Offset(108 * s, 108 * s))
        drawRoundRect(brush, topLeft = Offset(12 * s, 8 * s), size = Size(84 * s, 44 * s), cornerRadius = CornerRadius(8 * s))
        drawRoundRect(brush, topLeft = Offset(28 * s, 56 * s), size = Size(52 * s, 44 * s), cornerRadius = CornerRadius(8 * s))
    }
}
