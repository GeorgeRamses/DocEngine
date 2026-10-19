package nsf.georgeramsis.docengine

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.*
import androidx.compose.runtime.*

import androidx.compose.ui.Alignment
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.Modifier
import java.net.URI
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import docengine.composeapp.generated.resources.Res
import docengine.composeapp.generated.resources.docengine
import docengine.composeapp.generated.resources.moon
import docengine.composeapp.generated.resources.sun
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.painterResource
import java.io.File
import javax.swing.JFileChooser
import javax.swing.UIManager

// ─────────────────────────────────────────────────────────────────────────────
// DESIGN TOKENS
// ─────────────────────────────────────────────────────────────────────────────

private data class UiPalette(
    val bgDeep: Color, val bgBase: Color, val bgElevated: Color, val bgSubtle: Color,
    val textPrimary: Color, val textSecondary: Color, val textMuted: Color, val borderDefault: Color
)

private val DarkPalette = UiPalette(
    bgDeep = Color(0xFF0D1117),
    bgBase = Color(0xFF161B22),
    bgElevated = Color(0xFF21262D),
    bgSubtle = Color(0xFF30363D),
    textPrimary = Color(0xFFE6EDF3),
    textSecondary = Color(0xFF8B949E),
    textMuted = Color(0xFF484F58),
    borderDefault = Color(0xFF30363D)
)

private val LightPalette = UiPalette(
    bgDeep = Color(0xFFF7F8FA),
    bgBase = Color(0xFFFFFFFF),
    bgElevated = Color(0xFFF1F3F6),
    bgSubtle = Color(0xFFE8ECF2),
    textPrimary = Color(0xFF111827),
    textSecondary = Color(0xFF4B5563),
    textMuted = Color(0xFF9CA3AF),
    borderDefault = Color(0xFFE2E8F0)
)

private val LocalPalette = staticCompositionLocalOf { DarkPalette }

private val AccentBlue = Color(0xFF58A6FF)
private val AccentGreen = Color(0xFF3FB950)
private val AccentRed = Color(0xFFF85149)
private val AccentYellow = Color(0xFFD29922)
private val AccentOrange = Color(0xFFF0883E)
private val AccentPurple = Color(0xFFA5B4FC)

private val BgDeep: Color @Composable get() = LocalPalette.current.bgDeep
private val BgBase: Color @Composable get() = LocalPalette.current.bgBase
private val BgElevated: Color @Composable get() = LocalPalette.current.bgElevated
private val BgSubtle: Color @Composable get() = LocalPalette.current.bgSubtle
private val TextPrimary: Color @Composable get() = LocalPalette.current.textPrimary
private val TextSecondary: Color @Composable get() = LocalPalette.current.textSecondary
private val TextMuted: Color @Composable get() = LocalPalette.current.textMuted
private val BorderDefault: Color @Composable get() = LocalPalette.current.borderDefault

// ─────────────────────────────────────────────────────────────────────────────
// THEME
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun AppTheme(isDark: Boolean, content: @Composable () -> Unit) {
    val palette = if (isDark) DarkPalette else LightPalette
    val colors = if (isDark) darkColors(
        primary = AccentBlue, primaryVariant = Color(0xFF1F6FEB), secondary = AccentGreen,
        background = palette.bgDeep, surface = palette.bgBase,
        onPrimary = Color.White, onSecondary = Color.White,
        onBackground = palette.textPrimary, onSurface = palette.textPrimary, error = AccentRed
    ) else lightColors(
        primary = Color(0xFF2563EB), primaryVariant = Color(0xFF1D4ED8), secondary = Color(0xFF059669),
        background = palette.bgDeep, surface = palette.bgBase,
        onPrimary = Color.White, onSecondary = Color.White,
        onBackground = palette.textPrimary, onSurface = palette.textPrimary, error = Color(0xFFDC2626)
    )

    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(
            colors = colors,
            typography = Typography(
                h5 = TextStyle(
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.3).sp,
                    color = palette.textPrimary
                ),
                h6 = TextStyle(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.2).sp,
                    color = palette.textPrimary
                ),
                subtitle1 = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = palette.textPrimary),
                subtitle2 = TextStyle(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp,
                    color = palette.textSecondary
                ),
                body1 = TextStyle(fontSize = 13.sp, color = palette.textPrimary),
                body2 = TextStyle(fontSize = 12.sp, color = palette.textSecondary),
                caption = TextStyle(fontSize = 11.sp, color = palette.textSecondary),
                button = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp),
            ),
            shapes = Shapes(
                small = RoundedCornerShape(6.dp),
                medium = RoundedCornerShape(8.dp),
                large = RoundedCornerShape(10.dp)
            ),
            content = content
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// REUSABLE PRIMITIVES
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SectionLabel(text: String, badge: String? = null, badgeColor: Color = AccentBlue) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
        Text(
            text.uppercase(),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
            color = TextSecondary
        )
        if (badge != null) {
            Spacer(Modifier.width(8.dp))
            Surface(color = badgeColor.copy(alpha = 0.15f), shape = RoundedCornerShape(4.dp)) {
                Text(
                    badge, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, color = badgeColor,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun ConfigDivider() {
    Spacer(Modifier.height(18.dp))
    Divider(color = BorderDefault, thickness = 1.dp)
    Spacer(Modifier.height(18.dp))
}

@Composable
private fun OptionRow(
    label: String, description: String, selected: Boolean, enabled: Boolean,
    badge: String? = null, badgeColor: Color = AccentGreen, onClick: () -> Unit
) {
    val borderColor = if (selected) AccentBlue else BorderDefault
    val bgColor = if (selected) AccentBlue.copy(alpha = 0.07f) else Color.Transparent
    Surface(
        color = bgColor, shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected, onClick = null, enabled = enabled,
                colors = RadioButtonDefaults.colors(selectedColor = AccentBlue, unselectedColor = TextMuted),
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        label, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        color = if (selected) TextPrimary else TextSecondary
                    )
                    if (badge != null) {
                        Spacer(Modifier.width(6.dp))
                        Surface(color = badgeColor.copy(alpha = 0.15f), shape = RoundedCornerShape(3.dp)) {
                            Text(
                                badge, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = badgeColor,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                if (description.isNotBlank()) {
                    Text(description, fontSize = 11.sp, color = TextMuted, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

@Composable
private fun ThemedTextField(
    value: String, onValueChange: (String) -> Unit,
    label: String, enabled: Boolean, modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange,
        label = { Text(label, fontSize = 11.sp) },
        modifier = modifier, singleLine = true, enabled = enabled,
        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, color = TextPrimary),
        colors = TextFieldDefaults.outlinedTextFieldColors(
            textColor = TextPrimary, focusedBorderColor = AccentBlue, unfocusedBorderColor = BorderDefault,
            disabledBorderColor = BgSubtle, backgroundColor = BgBase,
            focusedLabelColor = AccentBlue, unfocusedLabelColor = TextSecondary,
            disabledLabelColor = TextMuted, cursorColor = AccentBlue
        )
    )
}

@Composable
private fun ThemedCheckbox(
    label: String, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 6.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked, onCheckedChange = null, enabled = enabled,
            colors = CheckboxDefaults.colors(
                checkedColor = AccentBlue,
                uncheckedColor = TextMuted,
                checkmarkColor = Color.White
            ),
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(label, fontSize = 12.sp, color = if (enabled) TextPrimary else TextSecondary)
    }
}

@OptIn(ExperimentalResourceApi::class)
@Composable
private fun ThemeSwitchButton(isDark: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val icon = if (isDark) Res.drawable.sun else Res.drawable.moon
    val label = if (isDark) "Light" else "Dark"
    val bg = if (isDark) BgElevated else BgSubtle
    val border = if (isDark) AccentBlue.copy(alpha = 0.45f) else BorderDefault
    Surface(
        color = bg, border = BorderStroke(1.dp, border), shape = RoundedCornerShape(10.dp),
        modifier = Modifier.height(32.dp).clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onToggle)
    ) {
        Row(modifier = Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(icon), contentDescription = "Toggle theme",
                modifier = Modifier.size(16.dp),
                colorFilter = ColorFilter.tint(if (isDark) AccentYellow else AccentBlue)
            )
            Spacer(Modifier.width(6.dp))
            Text("Switch to $label", fontSize = 11.sp, color = TextSecondary)
        }
    }
}

@Composable
private fun FolderPickerCard(
    label: String, path: File?, enabled: Boolean, buttonLabel: String, onPick: () -> Unit
) {
    val hasPath = path != null
    Surface(
        color = BgBase, shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, if (hasPath) AccentGreen.copy(alpha = 0.45f) else BorderDefault),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = if (hasPath) AccentGreen.copy(alpha = 0.15f) else BgSubtle,
                    shape = RoundedCornerShape(3.dp)
                ) {
                    Text(
                        if (hasPath) "✓" else "–", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        color = if (hasPath) AccentGreen else TextMuted,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextSecondary)
            }
            if (hasPath) {
                Spacer(Modifier.height(6.dp))
                Text(
                    path!!.absolutePath,
                    fontSize = 11.sp,
                    color = TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onPick, modifier = Modifier.fillMaxWidth().height(30.dp), enabled = enabled,
                shape = RoundedCornerShape(5.dp),
                border = BorderStroke(
                    1.dp,
                    if (enabled) AccentBlue.copy(alpha = 0.45f) else TextMuted.copy(alpha = 0.2f)
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentBlue)
            ) { Text(buttonLabel, fontSize = 11.sp, fontWeight = FontWeight.Medium) }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// INFO BOX HELPER
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun InfoBox(title: String, body: String, accentColor: Color) {
    Surface(
        color = accentColor.copy(alpha = 0.05f), shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.20f)), modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = accentColor)
            Spacer(Modifier.height(6.dp))
            Text(body, fontSize = 11.sp, color = TextSecondary, lineHeight = 17.sp)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// LOG COLORING
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun logColor(msg: String): Color = when {
    msg.contains("✅") || msg.startsWith(">>> Process Completed") ||
            msg.contains("PAGE MERGE COMPLETE") || msg.contains("PROCESS COMPLETE") ||
            msg.contains("AUTO-ROTATE COMPLETE") || msg.contains("FIND & REPLACE COMPLETE") ||
            msg.contains("DELETE BY KEYWORD COMPLETE") -> AccentGreen

    msg.contains("❌") || msg.contains("ERROR") || msg.contains("CRITICAL") || msg.contains("FATAL") -> AccentRed
    msg.contains("⚠️") || msg.contains("Warning") || msg.contains("⚠") -> AccentYellow
    msg.contains("🔹") -> AccentBlue
    msg.contains("🔸") -> AccentOrange
    msg.contains(">>>") || msg.contains("╔") || msg.contains("║") || msg.contains("╚") || msg.contains("┌") -> AccentPurple
    msg.contains("⏱️") || msg.contains("📄") -> TextSecondary
    msg.contains("📂") || msg.contains("🔄") || msg.contains("🔍") -> AccentBlue
    msg.trim().isEmpty() -> Color.Transparent
    msg == "Ready." -> TextMuted
    else -> TextSecondary
}

// ─────────────────────────────────────────────────────────────────────────────
// MAIN APP
// ─────────────────────────────────────────────────────────────────────────────

@Composable
@Preview
@OptIn(ExperimentalResourceApi::class)
fun App() {
    val viewModel = remember { PdfViewModel() }
    val scope = rememberCoroutineScope()

    val isScanning by viewModel.isScanning.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val stats by viewModel.completionStats.collectAsState()

    // ── Config state ──────────────────────────────────────────────────────────
    var mode by remember { mutableStateOf(ScanMode.RENAME_ONLY) }
    var delimiter by remember { mutableStateOf("") }
    var pattern by remember { mutableStateOf("000000") }
    var dropUnassigned by remember { mutableStateOf(false) }
    var autoRotate by remember { mutableStateOf(true) }
    var debugMode by remember { mutableStateOf(false) }
    var exportUndetected by remember { mutableStateOf(false) }
    var extractAndDelete by remember { mutableStateOf(false) }
    var keywordExtractSeparately by remember { mutableStateOf(false) }
    var scanArea by remember { mutableStateOf(ScanArea.FULL_PAGE) }
    var scanQuality by remember { mutableStateOf(ScanQuality.NORMAL) }
    var mergeOperation by remember { mutableStateOf(MergeOperation.REPLACE_PAGE) }
    var targetPageNumber by remember { mutableStateOf("1") }
    var folderBPath by remember { mutableStateOf<File?>(null) }
    var lastDirectory by remember { mutableStateOf<File?>(null) }

    // ── Auto-Rotate state ──────────────────────────────────────────────────────
    var enableCheckpoint by remember { mutableStateOf(false) }
    var diagMode by remember { mutableStateOf(false) }

    // ── Text Replace state ────────────────────────────────────────────────────
    var searchText by remember { mutableStateOf("") }
    var replaceText by remember { mutableStateOf("") }
    var detectionAccuracy by remember { mutableStateOf(DetectionAccuracy.BALANCED) }
    var maxCoverageLines by remember { mutableStateOf("2") }
    // ── Add Footer state ──────────────────────────────────────────────────────
    var footerLeftText by remember { mutableStateOf("") }
    var footerCenterText by remember { mutableStateOf("") }
    var footerRightText by remember { mutableStateOf("Page {page} / {total}") }
    var footerHeight by remember { mutableStateOf("20") }
    var footerFontSize by remember { mutableStateOf("9") }
    var footerPages by remember { mutableStateOf("") }
    // ── Handwriting Extract state ─────────────────────────────────────────────
    var handwritingConfidence by remember { mutableStateOf("25") }
    var handwritingSaveReport by remember { mutableStateOf(true) }
    var handwritingTextOnly by remember { mutableStateOf(false) }
    var isHover by remember { mutableStateOf(false) }

    // ── Theme — loaded from and saved to AppPreferences ───────────────────────
    var isDarkTheme by remember { mutableStateOf(AppPreferences.isDarkTheme) }

    val runScan: (List<File>) -> Unit = { files ->
        val inputFiles = files.flatMap { file ->
            // Always use absolute paths so parentFile is never null on any PC.
            val absFile = file.absoluteFile
            when {
                absFile.isDirectory -> absFile.walkTopDown()
                    .filter {
                        it.isFile && it.extension.equals("pdf", ignoreCase = true) &&
                            !it.toPath().any { part -> part.toString().equals("Extracted by size", ignoreCase = true) }
                    }
                    .toList()

                absFile.extension.equals("pdf", ignoreCase = true) -> listOf(absFile)
                else -> emptyList()
            }
        }
        if (inputFiles.isNotEmpty()) {
            viewModel.startScanning(
                inputFiles,
                AppConfig(
                    mode = mode,
                    delimiter = delimiter,
                    pattern = pattern.takeIf { it.isNotBlank() } ?: "000000",
                    unassignedAction = if (dropUnassigned) UnassignedAction.DROP else UnassignedAction.APPEND_TO_PREVIOUS,
                    autoRotate = autoRotate,
                    debugMode = debugMode,
                    exportUndetected = exportUndetected,
                    extractAndDelete = extractAndDelete,
                    mergeOperation = mergeOperation,
                    targetPageNumber = targetPageNumber.toIntOrNull() ?: 1,
                    sourceFolderB = folderBPath,
                    scanArea = scanArea,
                    scanQuality = scanQuality,
                    enableCheckpoint = enableCheckpoint,
                    diagMode = diagMode,
                    searchText = searchText,
                    replaceText = replaceText,
                    detectionAccuracy = detectionAccuracy,
                    maxCoverageLines = maxCoverageLines.toIntOrNull() ?: 2,
                    footerLeftText = footerLeftText,
                    footerCenterText = footerCenterText,
                    footerRightText = footerRightText,
                    footerHeight = footerHeight.toFloatOrNull() ?: 20f,
                    footerFontSize = footerFontSize.toFloatOrNull() ?: 9f,
                    footerPages = footerPages,
                    handwritingMinConfidence = (handwritingConfidence.toIntOrNull() ?: 25) / 100.0,
                    handwritingSaveReport = handwritingSaveReport,
                    handwritingTextOnly = handwritingTextOnly,
                    keywordExtractSeparately = keywordExtractSeparately
                )
            )
        }
    }

    val needsFolderB = mode == ScanMode.PAGE_MERGE && folderBPath == null
    val needsSearch = mode == ScanMode.TEXT_REPLACE && searchText.isBlank()
    val isBlocked = needsFolderB || needsSearch


    @OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
    val dragAndDropTarget = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                isHover = false
                if (isScanning || isBlocked) return false
                val dragData = event.dragData()
                if (dragData is DragData.FilesList) {
                    val files = dragData.readFiles().map { File(URI(it).path) }
                    val hasPdf = files.any {
                        it.isDirectory || it.extension.equals("pdf", ignoreCase = true)
                    }
                    if (!hasPdf) return false
                    lastDirectory = files.firstOrNull()?.parentFile
                    runScan(files)
                    return true
                }
                return false
            }

            override fun onExited(event: DragAndDropEvent) {
                isHover = false
                super.onExited(event)
            }

            override fun onEntered(event: DragAndDropEvent) {
                isHover = true
                super.onEntered(event)
            }
        }
    }

    @OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
    AppTheme(isDarkTheme) {
        Box(
            Modifier
                .fillMaxSize()
                .background(BgDeep)
        ) {

            Row(Modifier.fillMaxSize()) {

                // ═══════════════════════════════════════════════════════════
                // LEFT PANEL — Configuration
                // ═══════════════════════════════════════════════════════════
                Column(
                    modifier = Modifier.width(390.dp).fillMaxHeight()
                        .background(BgBase)
                        .border(BorderStroke(1.dp, BorderDefault), RectangleShape)
                ) {
                    // ── Header ────────────────────────────────────────────────
                    Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = AccentBlue.copy(alpha = 0.12f), shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.size(38.dp)
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("⚙", fontSize = 19.sp)
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("DocEngine", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                Text(
                                    "OCR  ·  Split  ·  Rename  ·  Merge  ·  Rotate  ·  Replace",
                                    fontSize = 11.sp, color = TextMuted, letterSpacing = 0.3.sp
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            ThemeSwitchButton(isDarkTheme, !isScanning) {
                                isDarkTheme = !isDarkTheme
                                AppPreferences.isDarkTheme = isDarkTheme   // ← persist immediately
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Divider(color = BorderDefault)
                    }

                    // ── Scrollable Config ─────────────────────────────────────
                    Column(
                        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp)
                    ) {
                        // ── Operation Mode ──────────────────────────────────────
                        SectionLabel("Operation Mode")
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            OptionRow(
                                "Rename File Only",
                                "Scan page 1 → rename by matched pattern",
                                mode == ScanMode.RENAME_ONLY,
                                !isScanning
                            ) { mode = ScanMode.RENAME_ONLY }
                            OptionRow(
                                "Split by Number",
                                "Group and split pages by matched pattern",
                                mode == ScanMode.SPLIT_AND_ORGANIZE,
                                !isScanning
                            ) { mode = ScanMode.SPLIT_AND_ORGANIZE }
                            OptionRow(
                                "Extract by Keyword",
                                "Split pages by text keyword match",
                                mode == ScanMode.KEYWORD_GROUP,
                                !isScanning
                            ) { mode = ScanMode.KEYWORD_GROUP }
                            OptionRow(
                                "Delete Pages by Keyword",
                                "Remove every page that contains a word or phrase",
                                mode == ScanMode.DELETE_BY_KEYWORD,
                                !isScanning,
                                badgeColor = AccentRed
                            ) { mode = ScanMode.DELETE_BY_KEYWORD }
                            OptionRow(
                                "Page Merge / Replace",
                                "Replace or insert pages between PDFs",
                                mode == ScanMode.PAGE_MERGE,
                                !isScanning,
                                badge = "NEW",
                                badgeColor = AccentGreen
                            ) { mode = ScanMode.PAGE_MERGE }
                            OptionRow(
                                "Auto-Rotate Pages",
                                "3-stage OCR engine corrects skewed/rotated pages in-place",
                                mode == ScanMode.AUTO_ROTATE,
                                !isScanning,
                                badge = "NEW",
                                badgeColor = AccentPurple
                            ) { mode = ScanMode.AUTO_ROTATE }
                            OptionRow(
                                "Find & Replace Text",
                                "Fuzzy OCR-aware text search with visual replacement",
                                mode == ScanMode.TEXT_REPLACE,
                                !isScanning,
                                badge = "NEW",
                                badgeColor = AccentOrange
                            ) { mode = ScanMode.TEXT_REPLACE }
                            OptionRow(
                                "Add Footer",
                                "Expand each page and stamp a 3-zone footer band",
                                mode == ScanMode.ADD_FOOTER,
                                !isScanning,
                                badge = "NEW",
                                badgeColor = AccentBlue
                            ) { mode = ScanMode.ADD_FOOTER }
                            OptionRow(
                                "Handwriting Extract",
                                "Detect & extract handwritten annotations from scanned PDFs",
                                mode == ScanMode.HANDWRITING_EXTRACT,
                                !isScanning,
                                badge = "NEW",
                                badgeColor = AccentPurple
                            ) { mode = ScanMode.HANDWRITING_EXTRACT }
                            OptionRow(
                                "Extract by Page Size",
                                "Save A4 and A3 pages into separate folders",
                                mode == ScanMode.EXTRACT_BY_PAGE_SIZE,
                                !isScanning,
                                badge = "NEW",
                                badgeColor = AccentGreen
                            ) { mode = ScanMode.EXTRACT_BY_PAGE_SIZE }
                        }

                        ConfigDivider()

                        // ── Mode-Specific Config ────────────────────────────────
                        when (mode) {
                            ScanMode.PAGE_MERGE -> PageMergeConfig(
                                mergeOperation = mergeOperation, targetPageNumber = targetPageNumber,
                                folderBPath = folderBPath, isScanning = isScanning,
                                onMergeOpChange = { mergeOperation = it },
                                onTargetPageChange = { targetPageNumber = it },
                                onFolderBPick = {
                                    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
                                    val chooser = JFileChooser().apply {
                                        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                                        dialogTitle = "Select Source Folder B"
                                        lastDirectory?.let { currentDirectory = it }
                                    }
                                    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION)
                                        folderBPath = chooser.selectedFile
                                }
                            )

                            ScanMode.AUTO_ROTATE -> AutoRotateConfig(
                                enableCheckpoint = enableCheckpoint, diagMode = diagMode,
                                isScanning = isScanning,
                                onEnableCheckpointChange = { enableCheckpoint = it },
                                onDiagModeChange = { diagMode = it }
                            )

                            ScanMode.TEXT_REPLACE -> TextReplaceConfig(
                                searchText = searchText, replaceText = replaceText,
                                detectionAccuracy = detectionAccuracy, maxCoverageLines = maxCoverageLines,
                                isScanning = isScanning,
                                onSearchTextChange = { searchText = it },
                                onReplaceTextChange = { replaceText = it },
                                onAccuracyChange = { detectionAccuracy = it },
                                onMaxLinesChange = { if (it.all(Char::isDigit)) maxCoverageLines = it }
                            )

                            ScanMode.ADD_FOOTER -> FooterConfig(
                                leftText = footerLeftText,
                                centerText = footerCenterText,
                                rightText = footerRightText,
                                height = footerHeight,
                                fontSize = footerFontSize,
                                pages = footerPages,
                                isScanning = isScanning,
                                onLeftChange = { footerLeftText = it },
                                onCenterChange = { footerCenterText = it },
                                onRightChange = { footerRightText = it },
                                onHeightChange = { if (it.all(Char::isDigit)) footerHeight = it },
                                onFontSizeChange = { if (it.all(Char::isDigit)) footerFontSize = it },
                                onPagesChange = { footerPages = it }
                            )

                            ScanMode.HANDWRITING_EXTRACT -> HandwritingConfig(
                                minConfidence = handwritingConfidence,
                                saveReport = handwritingSaveReport,
                                textOnly = handwritingTextOnly,
                                isScanning = isScanning,
                                onMinConfidenceChange = {
                                    if (it.all(Char::isDigit) && (it.toIntOrNull() ?: 0) <= 100)
                                        handwritingConfidence = it
                                },
                                onSaveReportChange = { handwritingSaveReport = it },
                                onTextOnlyChange = { handwritingTextOnly = it }
                            )

                            ScanMode.EXTRACT_BY_PAGE_SIZE -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Extract A4 and A3 pages", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                Text(
                                    "Each source PDF is processed separately. Matching pages are saved under " +
                                        "Extracted by size/A4 or Extracted by size/A3, keeping the source filename.",
                                    fontSize = 11.sp, color = TextMuted
                                )
                            }

                            else -> OcrConfig(
                                mode = mode, delimiter = delimiter, pattern = pattern,
                                autoRotate = autoRotate, debugMode = debugMode,
                                exportUndetected = exportUndetected, extractAndDelete = extractAndDelete,
                                keywordExtractSeparately = keywordExtractSeparately,
                                scanArea = scanArea, scanQuality = scanQuality,
                                dropUnassigned = dropUnassigned, isScanning = isScanning,
                                onDelimiterChange = { delimiter = it },
                                onPatternChange = { pattern = it },
                                onAutoRotateChange = { autoRotate = it },
                                onDebugChange = { debugMode = it },
                                onExportUndetectedChange = { exportUndetected = it },
                                onExtractAndDeleteChange = { extractAndDelete = it },
                                onKeywordExtractSeparatelyChange = { keywordExtractSeparately = it },
                                onScanAreaChange = { scanArea = it },
                                onScanQualityChange = { scanQuality = it },
                                onDropUnassignedChange = { dropUnassigned = it }
                            )
                        }
                    }

                    // ── Action Area (pinned to bottom) ────────────────────────
                    Surface(
                        color = BgBase, border = BorderStroke(1.dp, BorderDefault),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            if (isScanning) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "Processing…",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = AccentBlue
                                    )
                                    Text(
                                        "${(progress * 100).toInt()}%",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary
                                    )
                                }
                                LinearProgressIndicator(
                                    progress = progress,
                                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                    color = AccentBlue, backgroundColor = BgSubtle
                                )
                                Spacer(Modifier.height(12.dp))
                                Button(
                                    onClick = { viewModel.cancelScanning() },
                                    modifier = Modifier.fillMaxWidth().height(40.dp),
                                    shape = RoundedCornerShape(6.dp),
                                    elevation = ButtonDefaults.elevation(0.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        backgroundColor = AccentRed.copy(alpha = 0.12f), contentColor = AccentRed
                                    ),
                                    border = BorderStroke(1.dp, AccentRed.copy(alpha = 0.35f))
                                ) { Text("Cancel Operation", fontWeight = FontWeight.SemiBold) }
                            } else {
                                // Stats strip
                                AnimatedVisibility(visible = stats != null) {
                                    stats?.let { s ->
                                        Surface(
                                            color = BgElevated, shape = RoundedCornerShape(6.dp),
                                            border = BorderStroke(1.dp, BorderDefault),
                                            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(10.dp),
                                                horizontalArrangement = Arrangement.SpaceEvenly
                                            ) {
                                                StatChip("Total", s.total.toString(), TextSecondary)
                                                StatChip("OK", s.success.toString(), AccentGreen)
                                                StatChip("Skipped", s.skipped.toString(), AccentYellow)
                                                StatChip("Time", "${s.elapsedSec}s", AccentBlue)
                                            }
                                        }
                                    }
                                }

                                Button(
                                    onClick = {
                                        if (isBlocked) return@Button
                                        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
                                        val chooser = JFileChooser().apply {
                                            fileSelectionMode = if (mode == ScanMode.PAGE_MERGE)
                                                JFileChooser.DIRECTORIES_ONLY
                                            else
                                                JFileChooser.FILES_AND_DIRECTORIES
                                            dialogTitle = if (mode == ScanMode.PAGE_MERGE)
                                                "Select Target Folder A"
                                            else
                                                "Select PDF File or Folder"
                                            lastDirectory?.let { currentDirectory = it }
                                        }
                                        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                                            val file = chooser.selectedFile
                                            lastDirectory = file.parentFile
                                            runScan(listOf(file))
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().height(40.dp),
                                    shape = RoundedCornerShape(6.dp),
                                    enabled = !isBlocked,
                                    elevation = ButtonDefaults.elevation(0.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        backgroundColor = AccentBlue, contentColor = Color.White,
                                        disabledBackgroundColor = AccentBlue.copy(alpha = 0.25f),
                                        disabledContentColor = TextMuted
                                    )
                                ) {
                                    Text(
                                        when (mode) {
                                            ScanMode.PAGE_MERGE -> "Select Target Folder A →"
                                            ScanMode.AUTO_ROTATE -> "Select File / Folder →"
                                            ScanMode.TEXT_REPLACE -> "Select File / Folder →"
                                            else -> "Select File / Folder →"
                                        },
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }

                                if (needsFolderB) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        "⚠  Please select Source Folder B first", fontSize = 11.sp,
                                        color = AccentYellow, textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                if (needsSearch) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        "⚠  Please enter search text first", fontSize = 11.sp,
                                        color = AccentYellow, textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                }

                // ═══════════════════════════════════════════════════════════
                // RIGHT PANEL — Execution Log (drop zone)
                // ═══════════════════════════════════════════════════════════
                Box(
                    modifier = Modifier.weight(1f).fillMaxHeight()
                        .dragAndDropTarget(
                            shouldStartDragAndDrop = { !isScanning && !isBlocked },
                            target = dragAndDropTarget
                        )
                ) {
                    Column(modifier = Modifier.fillMaxSize().background(BgDeep)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().background(BgBase)
                                .border(BorderStroke(1.dp, BorderDefault), RectangleShape)
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    color = if (isScanning) AccentGreen.copy(alpha = 0.18f) else BgSubtle,
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        if (isScanning) "● LIVE" else "○ IDLE",
                                        fontSize = 9.sp, fontWeight = FontWeight.ExtraBold,
                                        color = if (isScanning) AccentGreen else TextMuted,
                                        letterSpacing = 0.8.sp,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    "Execution Log",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "${viewModel.logs.size} lines",
                                    fontSize = 11.sp,
                                    color = TextMuted,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                LogToolButton("Export") {
                                    scope.launch {
                                        val result = viewModel.exportLogs(lastDirectory)
                                        viewModel.logs.add(result)
                                    }
                                }
                                LogToolButton("Clear") { viewModel.clearLogs() }
                            }
                        }

                        val listState = rememberLazyListState()
                        LaunchedEffect(viewModel.logs.size) {
                            if (viewModel.logs.isNotEmpty())
                                listState.animateScrollToItem(viewModel.logs.size - 1)
                        }

                        val logBgAlpha = if (isDarkTheme) 0.30f else 0.32f
                        val logFadeTop = if (isDarkTheme) 0.68f else 0.58f
                        val logFadeEnd = if (isDarkTheme) 0.88f else 0.80f

                        Box(modifier = Modifier.weight(1f)) {
                            Image(
                                painter = painterResource(Res.drawable.docengine),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.matchParentSize().alpha(logBgAlpha)
                            )
                            Box(
                                modifier = Modifier.matchParentSize().background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            BgBase.copy(alpha = logFadeTop),
                                            BgBase.copy(alpha = logFadeEnd)
                                        )
                                    )
                                )
                            )
                            SelectionContainer(modifier = Modifier.fillMaxSize()) {
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp),
                                    verticalArrangement = Arrangement.spacedBy(1.dp)
                                ) {
                                    items(viewModel.logs) { msg ->
                                        if (msg.isBlank()) Spacer(Modifier.height(4.dp))
                                        else Text(
                                            msg, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                                            lineHeight = 18.sp, color = logColor(msg)
                                        )
                                    }
                                }
                            }
                        }

                        Surface(
                            color = BgBase,
                            border = BorderStroke(1.dp, BorderDefault),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 7.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "DocEngine  v${BuildConfig.VERSION_FULL}  ·  KMP Desktop",
                                    fontSize = 10.sp,
                                    color = TextMuted,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    when {
                                        isScanning -> "● Processing  ${(progress * 100).toInt()}%"
                                        stats != null -> "✓ Last run: ${stats!!.success}/${stats!!.total} succeeded"
                                        else -> "Ready"
                                    },
                                    fontSize = 10.sp,
                                    color = when {
                                        isScanning -> AccentBlue; stats != null -> AccentGreen; else -> TextMuted
                                    },
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    } // closes Column
                    DropOverlay(visible = isHover)
                } // closes Box
            }
        }
    }
}


// ─────────────────────────────────────────────────────────────────────────────
// SMALL SHARED COMPOSABLES
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun StatChip(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = color)
        Text(label, fontSize = 10.sp, color = TextMuted, letterSpacing = 0.5.sp)
    }
}
@Composable
private fun DropOverlay(visible: Boolean) {
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AccentBlue.copy(alpha = 0.10f))
                .border(2.dp, AccentBlue)
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                color = BgBase,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, AccentBlue.copy(alpha = 0.4f)),
                modifier = Modifier.padding(24.dp)
            ) {
                Column(
                    modifier = Modifier.padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("📄", fontSize = 34.sp)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Drop PDF files or folders",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("Only .pdf files will be processed", fontSize = 11.sp, color = TextMuted)
                }
            }
        }
    }
}

@Composable
private fun LogToolButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, modifier = Modifier.height(26.dp), shape = RoundedCornerShape(5.dp),
        border = BorderStroke(1.dp, BorderDefault),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
    ) { Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium) }
}

// ─────────────────────────────────────────────────────────────────────────────
// OCR CONFIG PANEL
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun OcrConfig(
    mode: ScanMode, delimiter: String, pattern: String,
    autoRotate: Boolean, debugMode: Boolean, exportUndetected: Boolean,
    extractAndDelete: Boolean, keywordExtractSeparately: Boolean,
    dropUnassigned: Boolean, isScanning: Boolean,
    onDelimiterChange: (String) -> Unit, onPatternChange: (String) -> Unit,
    onAutoRotateChange: (Boolean) -> Unit, onDebugChange: (Boolean) -> Unit,
    onExportUndetectedChange: (Boolean) -> Unit, onExtractAndDeleteChange: (Boolean) -> Unit,
    onKeywordExtractSeparatelyChange: (Boolean) -> Unit,
    scanArea: ScanArea, onScanAreaChange: (ScanArea) -> Unit,
    scanQuality: ScanQuality, onScanQualityChange: (ScanQuality) -> Unit,
    onDropUnassignedChange: (Boolean) -> Unit
) {
    val isDeleteMode = mode == ScanMode.DELETE_BY_KEYWORD
    val isKeywordMode = mode == ScanMode.KEYWORD_GROUP
    val isSplitMode = mode == ScanMode.SPLIT_AND_ORGANIZE || isKeywordMode

    SectionLabel(if (isDeleteMode) "Keyword to Delete" else "OCR Pattern")
    ThemedTextField(
        value = delimiter, onValueChange = onDelimiterChange,
        label = when (mode) {
            ScanMode.DELETE_BY_KEYWORD -> "Word or phrase to match"
            ScanMode.KEYWORD_GROUP -> "Keyword to find"
            else -> "Delimiter (e.g. Prefix, Invoice, ID)"
        },
        enabled = !isScanning, modifier = Modifier.fillMaxWidth()
    )

    if (!isKeywordMode && !isDeleteMode) {
        Spacer(Modifier.height(10.dp))
        var showPatternHelp by remember { mutableStateOf(false) }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ThemedTextField(
                value = pattern, onValueChange = onPatternChange,
                label = "Pattern (e.g. 000000)", enabled = !isScanning,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { showPatternHelp = true }) {
                Text("?", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = AccentBlue)
            }
        }

        if (showPatternHelp) {
            AlertDialog(
                onDismissRequest = { showPatternHelp = false },
                title = { Text("Pattern Guide", fontWeight = FontWeight.Bold, color = TextPrimary) },
                text = {
                    Column {
                        Text("Use patterns to define how the file number looks:", color = TextSecondary)
                        Spacer(Modifier.height(8.dp))
                        Text("• 0 : Matches any single digit (0-9)", color = TextSecondary)
                        Text("• ? : Matches any single letter or digit", color = TextSecondary)
                        Text("• Other characters (like -, /) match exactly.", color = TextSecondary)
                        Spacer(Modifier.height(8.dp))
                        Text("Example: 00-000 matches 12-345", color = TextSecondary)
                        Text("Example: ???000 matches ABC123", color = TextSecondary)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showPatternHelp = false }) { Text("OK", color = AccentBlue) }
                },
                backgroundColor = BgElevated
            )
        }
    }

    if (isDeleteMode) {
        Spacer(Modifier.height(14.dp))
        InfoBox(
            "How it works",
            "Every page is OCR-scanned. Pages containing the keyword (case-insensitive) are permanently " +
                    "removed. The cleaned file overwrites the original.",
            AccentRed
        )
    }

    ConfigDivider()
    SectionLabel("Options")
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        ThemedCheckbox("Auto-Rotate Retry (OSD)", autoRotate, !isScanning, onAutoRotateChange)
        if (!isDeleteMode) ThemedCheckbox("Debug Mode (save OCR images)", debugMode, !isScanning, onDebugChange)
        if (isSplitMode && !isKeywordMode)
            ThemedCheckbox(
                "Export Undetected Pages to PDF",
                exportUndetected,
                !isScanning,
                onExportUndetectedChange
            )
        if (isKeywordMode)
            ThemedCheckbox(
                "Extract & Delete from original",
                extractAndDelete,
                !isScanning,
                onExtractAndDeleteChange
            )
    }

    ConfigDivider()
    SectionLabel("Scan Performance")
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Column(modifier = Modifier.padding(bottom = 8.dp)) {
            Text("Scan Area", fontSize = 12.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
            Text("Crop page before scanning to increase speed", fontSize = 10.sp, color = TextMuted)
        }
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            ScanArea.values().forEach { area ->
                OptionRow(
                    label = area.name.replace("_", " "),
                    description = "",
                    selected = scanArea == area,
                    enabled = !isScanning,
                    onClick = { onScanAreaChange(area) }
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(6.dp))

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Column(modifier = Modifier.padding(bottom = 8.dp)) {
            Text("Quality Preset", fontSize = 12.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
            Text("Fast mode skips OpenCV denoising", fontSize = 10.sp, color = TextMuted)
        }
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            ScanQuality.values().forEach { sq ->
                OptionRow(
                    label = sq.name,
                    description = "",
                    selected = scanQuality == sq,
                    enabled = !isScanning,
                    onClick = { onScanQualityChange(sq) }
                )
            }
        }
    }

    if (isKeywordMode) {
        ConfigDivider()
        SectionLabel("Extract Output Format")
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            OptionRow(
                label = "All matched pages in one file",
                description = "Combine all keyword-matched pages into a single PDF",
                selected = !keywordExtractSeparately, enabled = !isScanning
            ) { onKeywordExtractSeparatelyChange(false) }
            OptionRow(
                label = "Each page as a separate file",
                description = "Save each matched page individually (page 1, page 2, …)",
                selected = keywordExtractSeparately, enabled = !isScanning
            ) { onKeywordExtractSeparatelyChange(true) }
        }
    }

    if (isSplitMode) {
        ConfigDivider()
        SectionLabel("Unmatched Page Handling")
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            OptionRow(
                label = if (isKeywordMode) "Append Subsequent Pages" else "Append to Previous Group",
                description = "Attach unmatched pages to the last known group",
                selected = !dropUnassigned, enabled = !isScanning
            ) { onDropUnassignedChange(false) }
            OptionRow(
                label = if (isKeywordMode) "Extract Matched Page Only" else "Drop Unassigned Pages",
                description = "Silently discard pages with no pattern match",
                selected = dropUnassigned, enabled = !isScanning
            ) { onDropUnassignedChange(true) }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// AUTO-ROTATE CONFIG PANEL
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun AutoRotateConfig(
    enableCheckpoint: Boolean, diagMode: Boolean, isScanning: Boolean,
    onEnableCheckpointChange: (Boolean) -> Unit, onDiagModeChange: (Boolean) -> Unit
) {
    SectionLabel("Detection Engine")
    InfoBox(
        "3-Stage OCR Rotation Detection",
        "① OSD fast-path — resolves high-confidence cases immediately\n" +
                "② Parallel 4-angle scan — scores 0°/90°/180°/270° simultaneously " +
                "using dict hits, numeric tokens, and word confidence\n" +
                "③ OSD fallback — used when the heuristic is inconclusive\n\n" +
                "Pages are corrected in-place; no new files are created.",
        AccentPurple
    )

    ConfigDivider()
    SectionLabel("Options")
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        ThemedCheckbox(
            "Enable Checkpoint / Resume",
            enableCheckpoint, !isScanning, onEnableCheckpointChange
        )
        ThemedCheckbox(
            "Write Diagnostic Logs (.rotation-diag.log)",
            diagMode, !isScanning, onDiagModeChange
        )
    }

    Spacer(Modifier.height(14.dp))
    InfoBox(
        "Checkpoint",
        "When enabled, processed files are recorded in a hidden .rotation_checkpoint file " +
                "next to the PDFs. If the batch is interrupted you can re-run and already-processed " +
                "files will be skipped automatically.",
        AccentBlue
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// TEXT REPLACE CONFIG PANEL
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TextReplaceConfig(
    searchText: String, replaceText: String,
    detectionAccuracy: DetectionAccuracy, maxCoverageLines: String,
    isScanning: Boolean,
    onSearchTextChange: (String) -> Unit, onReplaceTextChange: (String) -> Unit,
    onAccuracyChange: (DetectionAccuracy) -> Unit, onMaxLinesChange: (String) -> Unit
) {
    SectionLabel("Search & Replace")
    ThemedTextField(
        value = searchText, onValueChange = onSearchTextChange,
        label = "Text to find (OCR-aware)", enabled = !isScanning, modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(10.dp))
    ThemedTextField(
        value = replaceText, onValueChange = onReplaceTextChange,
        label = "Replacement text", enabled = !isScanning, modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(10.dp))
    ThemedTextField(
        value = maxCoverageLines, onValueChange = onMaxLinesChange,
        label = "Max coverage lines (default 2)", enabled = !isScanning, modifier = Modifier.fillMaxWidth()
    )

    ConfigDivider()
    SectionLabel("Detection Accuracy")
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        OptionRow(
            label = "Strict",
            description = "Exact text match only — fastest, zero false positives",
            selected = detectionAccuracy == DetectionAccuracy.STRICT, enabled = !isScanning
        ) { onAccuracyChange(DetectionAccuracy.STRICT) }
        OptionRow(
            label = "Balanced",
            description = "Tolerates minor OCR noise — recommended for most documents",
            selected = detectionAccuracy == DetectionAccuracy.BALANCED, enabled = !isScanning,
            badge = "DEFAULT", badgeColor = AccentGreen
        ) { onAccuracyChange(DetectionAccuracy.BALANCED) }
        OptionRow(
            label = "Flexible",
            description = "Handles moderate OCR errors and character substitutions",
            selected = detectionAccuracy == DetectionAccuracy.FLEXIBLE, enabled = !isScanning
        ) { onAccuracyChange(DetectionAccuracy.FLEXIBLE) }
        OptionRow(
            label = "Aggressive",
            description = "Finds text even with heavy OCR corruption — may produce false positives",
            selected = detectionAccuracy == DetectionAccuracy.AGGRESSIVE, enabled = !isScanning,
            badgeColor = AccentOrange
        ) { onAccuracyChange(DetectionAccuracy.AGGRESSIVE) }
    }

    Spacer(Modifier.height(14.dp))
    InfoBox(
        "How it works",
        "Each page is rendered at 200 DPI and OCR-scanned. Matched text is visually replaced: " +
                "a white rectangle covers the original, then the replacement text is drawn at the " +
                "original font size. Originals are backed up to _originals/ and unmatched files go to _unmatched/.",
        AccentOrange
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// PAGE MERGE CONFIG PANEL
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PageMergeConfig(
    mergeOperation: MergeOperation, targetPageNumber: String,
    folderBPath: File?, isScanning: Boolean,
    onMergeOpChange: (MergeOperation) -> Unit,
    onTargetPageChange: (String) -> Unit,
    onFolderBPick: () -> Unit
) {
    val needsPageNumber = mergeOperation != MergeOperation.APPEND_END &&
            mergeOperation != MergeOperation.REPLACE_LAST_PAGE

    SectionLabel("Merge Operation")
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        OptionRow(
            "Replace Page",
            "Remove target page, insert source page(s) at that position",
            mergeOperation == MergeOperation.REPLACE_PAGE,
            !isScanning
        ) { onMergeOpChange(MergeOperation.REPLACE_PAGE) }
        OptionRow(
            "Replace Last Page",
            "Replace the last page with source page(s)",
            mergeOperation == MergeOperation.REPLACE_LAST_PAGE,
            !isScanning
        ) { onMergeOpChange(MergeOperation.REPLACE_LAST_PAGE) }
        OptionRow(
            "Insert Before Page",
            "Insert source pages before the target page number",
            mergeOperation == MergeOperation.INSERT_BEFORE,
            !isScanning
        ) { onMergeOpChange(MergeOperation.INSERT_BEFORE) }
        OptionRow(
            "Insert After Page",
            "Insert source pages after the target page number",
            mergeOperation == MergeOperation.INSERT_AFTER,
            !isScanning
        ) { onMergeOpChange(MergeOperation.INSERT_AFTER) }
        OptionRow(
            "Append to End",
            "Append all source pages at the end of the document",
            mergeOperation == MergeOperation.APPEND_END,
            !isScanning
        ) { onMergeOpChange(MergeOperation.APPEND_END) }
    }

    AnimatedVisibility(visible = needsPageNumber) {
        Column {
            ConfigDivider()
            SectionLabel("Target Page Number")
            ThemedTextField(
                value = targetPageNumber,
                onValueChange = { if (it.all(Char::isDigit)) onTargetPageChange(it) },
                label = "Page Number (1-based)", enabled = !isScanning, modifier = Modifier.fillMaxWidth()
            )
        }
    }

    ConfigDivider()
    SectionLabel("Source Folder B")
    FolderPickerCard(
        label = "Source PDFs", path = folderBPath, enabled = !isScanning,
        buttonLabel = "Browse Folder B…", onPick = onFolderBPick
    )
    Spacer(Modifier.height(14.dp))
    InfoBox(
        "How it works",
        "Folder A  →  Target PDFs (to modify)\n" +
                "Folder B  →  Source PDFs (pages to use)\n" +
                "Files matched by filename (without extension)\n" +
                "Output overwrites the target file in Folder A",
        AccentBlue
    )

}

@Composable
fun FooterConfig(
    leftText: String,
    centerText: String,
    rightText: String,
    height: String,
    fontSize: String,
    pages: String,
    isScanning: Boolean,
    onLeftChange: (String) -> Unit,
    onCenterChange: (String) -> Unit,
    onRightChange: (String) -> Unit,
    onHeightChange: (String) -> Unit,
    onFontSizeChange: (String) -> Unit,
    onPagesChange: (String) -> Unit
) {
    SectionLabel("Footer Text  —  3 Zones")

    InfoBox(
        "Supported placeholders",
        "{filename}  →  file name without extension\n" +
                "{page}      →  current page number\n" +
                "{total}     →  total pages in document\n" +
                "{date}      →  today's date  (dd MMM yyyy)",
        AccentBlue
    )

    Spacer(Modifier.height(12.dp))

    ThemedTextField(
        value = leftText,
        onValueChange = onLeftChange,
        label = "Left zone",
        enabled = !isScanning,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
    ThemedTextField(
        value = centerText,
        onValueChange = onCenterChange,
        label = "Center zone",
        enabled = !isScanning,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
    ThemedTextField(
        value = rightText,
        onValueChange = onRightChange,
        label = "Right zone",
        enabled = !isScanning,
        modifier = Modifier.fillMaxWidth()
    )

    ConfigDivider()
    SectionLabel("Dimensions")

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ThemedTextField(
            value = height,
            onValueChange = onHeightChange,
            label = "Footer height (pt)",
            enabled = !isScanning,
            modifier = Modifier.weight(1f)
        )
        ThemedTextField(
            value = fontSize,
            onValueChange = onFontSizeChange,
            label = "Font size (pt)",
            enabled = !isScanning,
            modifier = Modifier.weight(1f)
        )
    }

    ConfigDivider()
    SectionLabel("Page Range")

    ThemedTextField(
        value = pages,
        onValueChange = onPagesChange,
        label = "Pages (e.g. 5-10, 12, 15, 20-25) - Blank for all",
        enabled = !isScanning,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
    InfoBox(
        "Range notes",
        "Leave blank to apply the footer to all pages. " +
                "Use a mix of ranges and specific pages separated by commas. " +
                "Values are 1-based and will be clamped to the document size.",
        AccentBlue
    )

    ConfigDivider()
    SectionLabel("Output")

    InfoBox(
        "Output folder",
        "Processed PDFs are saved to a 'processed' folder beside the original file " +
                "with the same filename. The original file is never modified.",
        AccentBlue
    )

    Spacer(Modifier.height(14.dp))

    InfoBox(
        "How it works",
        "Each page's MediaBox and CropBox are expanded downward into negative Y space. " +
                "The existing content coordinates are never touched — no shifting, no clipping. " +
                "The footer is drawn into the new strip below y = 0. " +
                "Rotation (90°/180°/270°) is handled automatically. No rasterisation — vector quality at any zoom.",
        AccentBlue
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// HANDWRITING EXTRACT CONFIG PANEL
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun HandwritingConfig(
    minConfidence: String,
    saveReport: Boolean,
    textOnly: Boolean,
    isScanning: Boolean,
    onMinConfidenceChange: (String) -> Unit,
    onSaveReportChange: (Boolean) -> Unit,
    onTextOnlyChange: (Boolean) -> Unit
) {
    SectionLabel("Detection Engine")
    InfoBox(
        "How handwriting is detected",
        "Each page is rendered at 150 DPI and scored using stroke-width Coefficient of " +
                "Variation (CV) — the hallmark of handwriting vs. printed text.\n\n" +
                "Pages above the threshold are re-rendered at 300 DPI and re-OCR'd using " +
                "Sauvola adaptive thresholding + contrast normalisation, both tuned to " +
                "preserve faint ink and pencil marks that global Otsu binarisation destroys.",
        AccentPurple
    )

    ConfigDivider()
    SectionLabel("Confidence Threshold")

    ThemedTextField(
        value = minConfidence,
        onValueChange = onMinConfidenceChange,
        label = "Min confidence % (0–100, default 25)",
        enabled = !isScanning,
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(Modifier.height(10.dp))
    InfoBox(
        "Threshold guide",
        "25% (default) — catches most handwriting, including light pencil marks\n" +
                "40%           — reduces false positives on heavily stamped documents\n" +
                "60%           — only clearly hand-written pages",
        AccentBlue
    )

    ConfigDivider()
    SectionLabel("Output")

    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        ThemedCheckbox(
            label = "Save extraction report (.txt)",
            checked = saveReport,
            enabled = !isScanning,
            onCheckedChange = onSaveReportChange
        )
        ThemedCheckbox(
            label = "Export handwriting text only (.txt)",
            checked = textOnly,
            enabled = !isScanning,
            onCheckedChange = onTextOnlyChange
        )
    }

    Spacer(Modifier.height(10.dp))
    InfoBox(
        "Report format",
        "Full report — includes headers, page numbers, confidence scores, and extracted text.\n\n" +
                "Text only — a clean plain-text file containing only the extracted handwritten " +
                "text with no metadata. Ideal for pasting into other tools.",
        AccentGreen
    )

    ConfigDivider()
    SectionLabel("Active  —  OpenCV Pipeline")
    InfoBox(
        "Powered by OpenCV & JavaCV",
        "• CLAHE adaptive contrast — recovers faded ink & uneven lighting\n" +
                "• Fast Non-Local Means Denoising — removes scanner noise\n" +
                "• Gaussian Adaptive Thresholding — local SIMD binarization\n" +
                "• Run-length Coefficient of Variation — stroke analysis",
        AccentGreen
    )
}

