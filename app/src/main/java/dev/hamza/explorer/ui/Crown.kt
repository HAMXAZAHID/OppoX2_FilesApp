package dev.hamza.explorer.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
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
import kotlin.math.abs
import kotlin.math.sign

/** How far one crown click scrolls (the "Normal" step the user picked in Home Files). */
private val CrownStep: Dp = 44.dp

/**
 * Scrolls a fixed distance per crown click, whatever size the system says the click is.
 * The OPPO crown reports very large scroll amounts, so the stock handling flew to the end of
 * the list. Handles both rotary-encoder events and mouse-wheel-style scroll events, and eats
 * them before the list's own handler sees them.
 */
@Composable
fun Modifier.crownScroll(state: ScrollableState): Modifier {
    val stepPx = with(LocalDensity.current) { CrownStep.toPx() }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val ticker = remember { CrownTicker() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun onTick(direction: Float) {
        if (direction == 0f || !ticker.accept()) return
        ticker.pending += direction * stepPx
        if (ticker.job?.isActive == true) return
        // One animation runs for as long as clicks keep coming, easing toward the target every
        // frame. Restarting a short tween per click is what made it look like 10 fps.
        ticker.job = scope.launch {
            // A touch drag takes over the list and cancels this; drop what was left.
            try { state.scroll {
                while (abs(ticker.pending) > 0.5f) {
                    withFrameNanos { }
                    val p = ticker.pending
                    val d = if (abs(p) < 2f) p else p * 0.28f
                    val used = scrollBy(d)
                    ticker.pending -= d
                    if (abs(used) < 0.01f) { ticker.pending = 0f; break }
                }
            } } finally { if (ticker.job?.isCancelled == true) ticker.pending = 0f }
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
    var pending = 0f
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
