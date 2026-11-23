package dev.hamza.explorer.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onPreRotaryScrollEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.sign

/** How far one crown click scrolls. Changed in Settings; same steps as Home Files. */
object Crown {
    val speeds = listOf("Slow" to 24.dp, "Normal" to 44.dp, "Fast" to 80.dp)
    var index by mutableIntStateOf(1)
    val step: Dp get() = speeds[index].second
    val label: String get() = speeds[index].first
}

/**
 * Scrolls a fixed distance per crown click, whatever size the system says the click is.
 * The OPPO crown reports very large scroll amounts, so the stock handling flew to the end of
 * the list. Handles both rotary-encoder events and mouse-wheel-style scroll events, and eats
 * them before the list's own handler sees them.
 */
@Composable
fun Modifier.crownScroll(state: ScrollableState): Modifier {
    val stepPx = with(LocalDensity.current) { Crown.step.toPx() }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val ticker = remember { CrownTicker() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun onTick(direction: Float) {
        if (direction == 0f || !ticker.accept()) return
        ticker.target += direction * stepPx
        ticker.job?.cancel()
        ticker.job = scope.launch {
            val distance = ticker.target
            ticker.target = 0f
            state.animateScrollBy(distance, tween(durationMillis = 90))
        }
    }

    return this
        .pointerInput(stepPx) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Scroll) {
                        val dy = event.changes.sumOf { it.scrollDelta.y.toDouble() }.toFloat()
                        event.changes.forEach { it.consume() }
                        onTick(dy.sign)
                    }
                }
            }
        }
        .onPreRotaryScrollEvent { e ->
            onTick(e.verticalScrollPixels.sign)
            true
        }
        .focusRequester(focus)
        .focusable()
}

private class CrownTicker {
    var target = 0f
    var job: Job? = null
    private var last = 0L

    /** Ignores bursts: the crown can send several events for one small turn. */
    fun accept(): Boolean {
        val now = System.currentTimeMillis()
        if (now - last < 35) return false
        last = now
        return true
    }
}

/**
 * Calls [onTick] with +1 or -1 per crown click (bursts throttled), for screens that aren't lists,
 * such as zooming an image or changing the volume. Handles rotary and wheel-style events.
 */
@Composable
fun Modifier.crownTicks(onTick: (Float) -> Unit): Modifier {
    val focus = remember { FocusRequester() }
    val ticker = remember { CrownTicker() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    fun tick(d: Float) { if (d != 0f && ticker.accept()) onTick(d) }
    return this
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Scroll) {
                        val dy = event.changes.sumOf { it.scrollDelta.y.toDouble() }.toFloat()
                        event.changes.forEach { it.consume() }
                        tick(dy.sign)
                    }
                }
            }
        }
        .onPreRotaryScrollEvent { e -> tick(e.verticalScrollPixels.sign); true }
        .focusRequester(focus)
        .focusable()
}
