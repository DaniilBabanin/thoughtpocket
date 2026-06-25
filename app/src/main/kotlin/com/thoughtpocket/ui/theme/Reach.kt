package com.thoughtpocket.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.StateFlow

/** "Reach" glass design system: blur-free translucent surfaces over an accent-glow backdrop. */
object ReachShapes {
    val card = RoundedCornerShape(20.dp)
    val field = RoundedCornerShape(14.dp)
    val pill = RoundedCornerShape(percent = 50)
    val bar = RoundedCornerShape(30.dp)
}

/**
 * Translucent fill + a top-lit 1px hairline + rounded corners — glass without an expensive blur.
 * Uses [drawWithCache] so the outline + gradient are built once per size (not per frame/recomposition),
 * and [clip] makes each surface its own layer so scrolling translates it instead of re-recording it.
 */
@Composable
fun Modifier.glass(shape: Shape = ReachShapes.card): Modifier {
    val dark = isSystemInDarkTheme()
    val fill = MaterialTheme.colorScheme.surface.copy(alpha = if (dark) 0.50f else 0.84f)
    val topC = if (dark) Color.White.copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.10f)
    val botC = if (dark) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.04f)
    return this
        .clip(shape)
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val border = Brush.verticalGradient(listOf(topC, botC), startY = 0f, endY = size.height)
            val stroke = Stroke(width = 1.dp.toPx())
            onDrawBehind {
                drawOutline(outline, color = fill)
                drawOutline(outline, brush = border, style = stroke)
            }
        }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = ReachShapes.card,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.glass(shape).padding(padding), content = content)
}

/**
 * Accent-glow backdrop: base color + two cached radial gradients painted once behind everything.
 * Translucent glass surfaces layered on top let the glow show through. No blur, no per-item cost.
 */
@Composable
fun ReachBackground(
    modifier: Modifier = Modifier,
    glow: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val bg = MaterialTheme.colorScheme.background
    val dark = isSystemInDarkTheme()
    val a1 = if (dark) 0.52f else 0.28f
    val a2 = if (dark) 0.30f else 0.16f
    Box(modifier.fillMaxSize().background(bg)) {
        // Glow lives in its OWN graphicsLayer so it's rasterized once and merely composited each frame —
        // not re-recorded/re-executed when the (translucent) content above it scrolls. This was the big jank.
        if (glow) {
            Box(
                Modifier
                    .matchParentSize()
                    // Offscreen = rasterize the radial gradients ONCE into a texture, then just sample it
                    // each frame. Without this the GPU re-runs the full-screen gradient fill every frame.
                    .graphicsLayer { clip = false; compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithCache {
                        val c1 = Offset(size.width * 0.12f, 0f)
                        val c2 = Offset(size.width * 0.95f, size.height * 0.08f)
                        // Big screens: each glow's radius reaches the OPPOSITE corner, so it fades smoothly
                        // across the whole screen at ANY size/aspect (portrait, landscape, foldable) with no
                        // hard edge or vertical squish. Phone (Compact) keeps the exact original look.
                        val big = size.width >= 600.dp.toPx()
                        val r1 = if (big) (Offset(size.width, size.height) - c1).getDistance() else size.width * 1.20f
                        val r2 = if (big) (Offset(0f, size.height) - c2).getDistance() else size.width * 1.25f
                        val b1 = Brush.radialGradient(listOf(primary.copy(alpha = a1), Color.Transparent), center = c1, radius = r1)
                        val b2 = Brush.radialGradient(listOf(primary.copy(alpha = a2), Color.Transparent), center = c2, radius = r2)
                        onDrawBehind {
                            scale(1f, if (big) 1f else 0.62f, pivot = c1) { drawRect(b1) }
                            scale(1f, if (big) 1f else 0.68f, pivot = c2) { drawRect(b2) }
                        }
                    }
            )
        }
        content()
    }
}

private const val HOLD_MS = 350L

/**
 * Docked record orb. Gesture mirrors the prototype: a quick **tap** latches start/stop;
 * **hold + release** is push-to-talk (records only while held). [onStart]/[onStop] drive
 * [com.thoughtpocket.service.RecordingService].
 *
 * While recording the orb turns red and shows the classic round record dot. The pulse runs
 * continuously (so there's always "I'm recording" motion); [level] (0..1 smoothed mic loudness)
 * just pushes the expanding ripples FURTHER out + brighter and makes the orb breathe — live feedback
 * that it's hearing you. [level] is collected here, so only the orb recomposes when it changes.
 */
@Composable
fun RecordOrb(
    recording: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    level: StateFlow<Float>,
    modifier: Modifier = Modifier,
    diameter: Dp = 58.dp,
) {
    val primary = MaterialTheme.colorScheme.primary
    // Recording = classic record red; idle = the teal accent.
    val accent = if (recording) Color(0xFFFF4D4D) else primary
    // Read live state inside the gesture without restarting pointerInput mid-press.
    val rec = rememberUpdatedState(recording)
    val start = rememberUpdatedState(onStart)
    val stop = rememberUpdatedState(onStop)
    var pressed by remember { mutableStateOf(false) }
    val pressScale by animateFloatAsState(if (pressed) 0.9f else 1f, label = "orbPress")

    val animate = recording && !LocalReduceMotion.current
    // Collected only here → updates recompose just this tiny orb, never the screens above it.
    val amp = level.collectAsState().value.coerceIn(0f, 1f)
    // Orb breathes with what it hears — immediate "I'm listening" feedback (also scales its glow).
    val breath by animateFloatAsState(if (animate) 1f + 0.16f * amp else 1f, tween(90), label = "breath")

    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        // NOTE: requiredSize (not size) — these must be ALLOWED to exceed the orb's fixed-size Box,
        // otherwise the constraints clamp them to the orb and the ring is invisible.
        // Static halo hugging the orb (design's box-shadow `0 0 0 6px accent 20%`) — makes it pop.
        Box(Modifier.requiredSize(diameter + 12.dp).clip(CircleShape).background(accent.copy(alpha = if (recording) 0.22f else 0.16f)))
        // Recording pulse: expanding "sonar" rings (design @keyframes ring). Runs continuously even in
        // silence; [amp] just extends the reach + brightens it. Three rings at 1/3-cycle offsets = a
        // never-empty outward ripple. They must travel well past the orb's glow to read as motion.
        if (animate) {
            val t = rememberInfiniteTransition(label = "pulse")
            val p by t.animateFloat(
                0f, 1f,
                infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart),
                label = "p",
            )
            repeat(3) { i ->
                Box(
                    Modifier
                        .requiredSize(diameter)
                        .graphicsLayer {
                            val lp = (p + i / 3f) % 1f
                            val e = lp * (2f - lp)                 // ease-out
                            // Always expands (~1.8×); sound pushes it out to ~3.4× and brightens it.
                            val s = 1f + (0.8f + amp * 1.6f) * e
                            scaleX = s; scaleY = s
                            alpha = (0.55f + amp * 0.30f) * (1f - e)
                        }
                        .border(3.dp, accent, CircleShape)
                )
            }
        }
        Box(
            Modifier
                .size(diameter)
                .graphicsLayer { val s = pressScale * breath; scaleX = s; scaleY = s }
                .shadow(16.dp, CircleShape, spotColor = accent, ambientColor = accent)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(lerp(accent, Color.White, 0.5f), accent)))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        pressed = true
                        try {
                            when {
                                rec.value -> { // latched → a tap stops
                                    if (waitForUpOrCancellation() != null) stop.value()
                                }
                                else -> {
                                    start.value()
                                    val up = waitForUpOrCancellation()
                                    // Held past the threshold → push-to-talk (release stops); quick tap latches.
                                    if (up != null && up.uptimeMillis - down.uptimeMillis >= HOLD_MS) stop.value()
                                }
                            }
                        } finally {
                            pressed = false
                        }
                    }
                }
                .semantics { contentDescription = if (recording) "Stop recording" else "Record" },
            contentAlignment = Alignment.Center,
        ) {
            if (recording) {
                // Classic round record dot.
                Box(Modifier.size(diameter * 0.34f).clip(CircleShape).background(Color.White))
            } else {
                Icon(Icons.Filled.Mic, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(diameter * 0.4f))
            }
        }
    }
}

enum class ReachTab(val icon: ImageVector, val label: String) {
    Home(Icons.Filled.Home, "Home"),
    Tasks(Icons.Filled.Checklist, "Action items"),
    Ask(Icons.Filled.AutoAwesome, "Ask"),
    Settings(Icons.Filled.Settings, "Settings"),
}

/** Thumb-first bottom bar: Home · Tasks · [orb] · Ask · Settings, orb docked center and raised. */
@Composable
fun ReachBottomBar(
    selected: ReachTab,
    onSelect: (ReachTab) -> Unit,
    recording: Boolean,
    onStartRecord: () -> Unit,
    onStopRecord: () -> Unit,
    level: StateFlow<Float>,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(66.dp)
                .glass(ReachShapes.bar)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            NavItem(ReachTab.Home, selected, onSelect)
            NavItem(ReachTab.Tasks, selected, onSelect)
            Spacer(Modifier.width(58.dp)) // reserve room for the docked orb
            NavItem(ReachTab.Ask, selected, onSelect)
            NavItem(ReachTab.Settings, selected, onSelect)
        }
        RecordOrb(
            recording = recording,
            onStart = onStartRecord,
            onStop = onStopRecord,
            level = level,
            modifier = Modifier.align(Alignment.Center).offset(y = (-14).dp),
        )
    }
}

@Composable
private fun RowScope.NavItem(tab: ReachTab, selected: ReachTab, onSelect: (ReachTab) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val on = tab == selected
    // Pill "pops" in on selection (scale .6→1 + fade), like the design's `.nit.on::before`.
    val pop by animateFloatAsState(
        if (on) 1f else 0f,
        animationSpec = if (LocalReduceMotion.current) snap() else tween(220),
        label = "navPop",
    )
    val tint = lerp(cs.onSurface.copy(alpha = 0.55f), cs.primary, pop)
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable { onSelect(tab) },
        contentAlignment = Alignment.Center,
    ) {
        if (pop > 0f) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer { val s = 0.6f + 0.4f * pop; scaleX = s; scaleY = s; alpha = pop }
                    .clip(CircleShape)
                    .background(cs.primary.copy(alpha = 0.16f))
            )
        }
        Icon(tab.icon, tab.label, tint = tint, modifier = Modifier.size(24.dp))
    }
}

/** Live equalizer for the "listening" card — only animates while shown (i.e. while recording). */
@Composable
fun WaveBars(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    bars: Int = 7,
) {
    if (LocalReduceMotion.current) {
        Row(modifier.height(28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(bars) { i -> Box(Modifier.width(3.dp).height((10 + (i % 3) * 7).dp).clip(RoundedCornerShape(2.dp)).background(color)) }
        }
        return
    }
    val t = rememberInfiniteTransition(label = "wave")
    Row(
        modifier.height(28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        repeat(bars) { i ->
            // design @keyframes wave: height 6→26px, 1s ease-in-out, each bar delayed 0.1s.
            // Animate scaleY in the draw phase (no relayout/recompose per frame) for a fixed-height bar.
            val h = t.animateFloat(
                6f, 26f,
                infiniteRepeatable(
                    tween(500, easing = FastOutSlowInEasing),
                    RepeatMode.Reverse,
                    initialStartOffset = StartOffset(i * 100),
                ),
                label = "bar$i",
            )
            Box(
                Modifier.width(3.dp).height(26.dp)
                    .graphicsLayer { scaleY = h.value / 26f }
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
            )
        }
    }
}

/** Lightweight radio (design's `.radio`) — far cheaper than Material RadioButton during scroll. */
@Composable
fun ReachRadio(selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ring = when {
        !enabled -> cs.onSurface.copy(alpha = 0.18f)
        selected -> cs.primary
        else -> cs.onSurface.copy(alpha = 0.40f)
    }
    Box(
        Modifier.size(40.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(20.dp).border(2.dp, ring, CircleShape), contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(cs.primary))
        }
    }
}

/** Lightweight switch (design's `.sw2`) — far cheaper than Material Switch during scroll. */
@Composable
fun ReachSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val track = if (checked) cs.primary else cs.onSurface.copy(alpha = 0.22f)
    Box(
        Modifier
            .size(width = 44.dp, height = 26.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(track)
            .clickable { onChange(!checked) }
            .padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(20.dp).clip(CircleShape).background(Color.White))
    }
}

/** Rounded-square accent checkbox matching the design's `.check` (fills accent + tick when on). */
@Composable
fun ReachCheck(checked: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(7.dp)
    Box(
        modifier
            .size(24.dp)
            .clip(shape)
            .background(if (checked) cs.primary else cs.onSurface.copy(alpha = 0.06f))
            .border(1.dp, if (checked) Color.Transparent else cs.onSurface.copy(alpha = 0.25f), shape)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(Icons.Filled.Check, null, tint = cs.onPrimary, modifier = Modifier.size(16.dp))
    }
}

/** Uppercase, letter-spaced muted section label (the prototype's `.sec` / `.sectitle`). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 1.5.sp,
        modifier = modifier,
    )
}

/**
 * Glass pill chip. Selected = filled accent; [dashed] = dashed accent outline (AI suggestions);
 * otherwise a glass pill ([accentText] tints the text).
 */
@Composable
fun ReachChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accentText: Boolean = false,
    dashed: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val base = when {
        selected -> modifier.clip(ReachShapes.pill).background(cs.primary)
        dashed -> modifier.clip(ReachShapes.pill).dashedOutline(cs.primary.copy(alpha = 0.6f))
        else -> modifier.glass(ReachShapes.pill)
    }
    val content = when {
        selected -> cs.onPrimary
        accentText || dashed -> cs.primary
        else -> cs.onSurface
    }
    Box(
        base.clickable { onClick() }.padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Dashed rounded (pill) outline, drawn cheaply behind content. */
private fun Modifier.dashedOutline(color: Color): Modifier = this.drawWithCache {
    val stroke = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 6f)))
    val radius = CornerRadius(size.height / 2f)
    onDrawBehind { drawRoundRect(color = color, cornerRadius = radius, style = stroke) }
}

/** Sentence-case, semibold section title (the design's `.sectitle`: Scope / Interact / card headers). */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, modifier = modifier)
}

/**
 * Staggered fade+rise reveal driven by a single screen-level [progress] (0→1, see [rememberReveal]).
 * [progress] is read in the DRAW phase (a lambda), so animating it redraws — never recomposes — the item,
 * and after it settles to 1 (post-entry) scrolling pays nothing. Stagger is capped so long lists never stall.
 * No-op under reduce-motion.
 */
@Composable
fun Modifier.revealItem(index: Int, progress: () -> Float): Modifier {
    if (LocalReduceMotion.current) return this
    return this.graphicsLayer {
        val start = index.coerceAtMost(8) * 0.06f
        val local = ((progress() - start) / 0.40f).coerceIn(0f, 1f)
        alpha = local
        translationY = (1f - local) * 24.dp.toPx()
    }
}

/**
 * One-shot 0→1 reveal clock for a screen/list. Animates once on entry (re-runs on re-entry), snaps to 1
 * under reduce-motion. Feed `{ value }` to [Modifier.revealItem].
 */
@Composable
fun rememberReveal(durationMillis: Int = 650): androidx.compose.animation.core.Animatable<Float, *> {
    val reduce = LocalReduceMotion.current
    val anim = remember { androidx.compose.animation.core.Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(reduce) {
        if (reduce) anim.snapTo(1f) else { anim.snapTo(0f); anim.animateTo(1f, tween(durationMillis)) }
    }
    return anim
}

/** Shimmering skeleton lines (design's reformat `.sk`) — bounded to the busy state; static under reduce-motion. */
@Composable
fun ShimmerLines(modifier: Modifier = Modifier, lines: Int = 5) {
    val cs = MaterialTheme.colorScheme
    val base = cs.onSurface.copy(alpha = 0.08f)
    val hi = cs.onSurface.copy(alpha = 0.16f)
    val widths = listOf(0.55f, 0.9f, 0.75f, 0.85f, 0.6f, 0.8f)
    val reduce = LocalReduceMotion.current
    val sweep = if (reduce) null else rememberInfiniteTransition(label = "shimmer")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "sweep")
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(lines) { i ->
            Box(
                Modifier.fillMaxWidth(widths[i % widths.size]).height(14.dp).clip(RoundedCornerShape(7.dp))
                    .drawBehind {
                        drawRect(base)
                        sweep?.let {
                            val x = (it.value * 1.6f - 0.3f) * size.width
                            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, hi, Color.Transparent), startX = x, endX = x + size.width * 0.5f))
                        }
                    }
            )
        }
    }
}

/**
 * Glass text field. With [label] it renders the prototype's `.field` (tiny uppercase label above the
 * value); without it, an inline icon+placeholder style (`.search` / `.cmd`). Replaces OutlinedTextField.
 */
@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    flat: Boolean = false,
    readOnly: Boolean = false,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        (if (flat) modifier else modifier.glass(ReachShapes.field))
            .padding(horizontal = if (flat) 0.dp else 14.dp, vertical = if (label != null) 10.dp else 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            if (label != null) {
                Text(
                    label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                    letterSpacing = 1.sp,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                readOnly = readOnly,
                singleLine = singleLine,
                minLines = minLines,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
                cursorBrush = SolidColor(cs.primary),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) {
                            Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
                        }
                        inner()
                    }
                },
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            trailing()
        }
    }
}
