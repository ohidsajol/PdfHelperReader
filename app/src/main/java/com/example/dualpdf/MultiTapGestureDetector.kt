package com.example.dualpdf

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.changedToUp
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

private sealed class LongPressResult {
    data class Released(val finalUpChange: PointerInputChange) : LongPressResult()
    object Canceled : LongPressResult()
    object Success : LongPressResult()
}

/**
 * Waits for a long press or pointer release/cancellation.
 * Compatible with Compose's standard long press detection.
 */
private suspend fun AwaitPointerEventScope.waitForLongPress(
    pass: PointerEventPass = PointerEventPass.Main
): LongPressResult {
    var result: LongPressResult = LongPressResult.Canceled
    try {
        withTimeout(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val event = awaitPointerEvent(pass)
                if (event.changes.all { it.changedToUp() }) {
                    result = LongPressResult.Released(event.changes[0])
                    break
                }

                if (
                    event.changes.any {
                        it.isConsumed ||
                        it.position.x < 0f || it.position.x > size.width ||
                        it.position.y < 0f || it.position.y > size.height
                    }
                ) {
                    result = LongPressResult.Canceled
                    break
                }

                val consumeCheck = awaitPointerEvent(PointerEventPass.Final)
                if (consumeCheck.changes.any { it.isConsumed }) {
                    result = LongPressResult.Canceled
                    break
                }
            }
        }
    } catch (_: PointerEventTimeoutCancellationException) {
        return LongPressResult.Success
    }
    return result
}

/**
 * Consumes all pointer events until nothing is pressed.
 */
private suspend fun AwaitPointerEventScope.consumeUntilUp() {
    do {
        val event = awaitPointerEvent()
        event.changes.forEach { it.consume() }
    } while (event.changes.any { it.pressed })
}

/**
 * Detects tap, double-tap, triple-tap, and long press gestures.
 *
 * Distinguishes single tap, double tap, and triple tap cleanly:
 * - When [onTripleTap] is provided, double tap waits a snappy 200ms window before
 *   invoking [onDoubleTap], so that double-tap feels fast and responsive, while ensuring
 *   that a 3rd tap triggers [onTripleTap] WITHOUT triggering double-tap actions first
 *   (e.g. sidebar buttons never flicker/appear before exiting Zen mode).
 * - If [onTripleTap] triggers, neither [onTap] nor [onDoubleTap] is invoked.
 * - [onLongPress] triggers accurately via [waitForLongPress] when held for [longPressTimeoutMillis].
 */
suspend fun PointerInputScope.detectMultiTapGestures(
    tripleTapTimeoutMillis: Long = 200L,
    onDoubleTap: ((Offset) -> Unit)? = null,
    onTripleTap: ((Offset) -> Unit)? = null,
    onLongPress: ((Offset) -> Unit)? = null,
    onLongPressEnd: (() -> Unit)? = null,
    onTap: ((Offset) -> Unit)? = null
) {
    awaitEachGesture {
        val down = awaitFirstDown()
        down.consume()

        val upOrCancel: PointerInputChange? = if (onLongPress == null) {
            waitForUpOrCancellation()
        } else {
            when (val longPressResult = waitForLongPress()) {
                LongPressResult.Success -> {
                    onLongPress.invoke(down.position)
                    consumeUntilUp()
                    onLongPressEnd?.invoke()
                    return@awaitEachGesture
                }
                is LongPressResult.Released -> longPressResult.finalUpChange
                is LongPressResult.Canceled -> null
            }
        }

        val up1 = upOrCancel ?: return@awaitEachGesture
        up1.consume()

        if (onDoubleTap == null && onTripleTap == null) {
            onTap?.invoke(up1.position)
            return@awaitEachGesture
        }

        val secondDown = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
            val minUptime = up1.uptimeMillis + viewConfiguration.doubleTapMinTimeMillis
            var change: PointerInputChange
            do {
                change = awaitFirstDown()
            } while (change.uptimeMillis < minUptime)
            change
        }

        if (secondDown == null) {
            onTap?.invoke(up1.position)
            return@awaitEachGesture
        }
        secondDown.consume()

        val secondUp = waitForUpOrCancellation()
        if (secondUp == null) {
            onTap?.invoke(up1.position)
            return@awaitEachGesture
        }
        secondUp.consume()

        if (onTripleTap == null) {
            onDoubleTap?.invoke(secondUp.position)
            return@awaitEachGesture
        }

        // Wait a snappy 200ms window for the third tap so double-tap is fast,
        // while ensuring a 3rd tap triggers onTripleTap exclusively without onDoubleTap firing first.
        val thirdDown = withTimeoutOrNull(tripleTapTimeoutMillis) {
            val minUptime = secondUp.uptimeMillis + viewConfiguration.doubleTapMinTimeMillis
            var change: PointerInputChange
            do {
                change = awaitFirstDown()
            } while (change.uptimeMillis < minUptime)
            change
        }

        if (thirdDown == null) {
            onDoubleTap?.invoke(secondUp.position)
            return@awaitEachGesture
        }
        thirdDown.consume()

        val thirdUp = waitForUpOrCancellation()
        if (thirdUp == null) {
            onDoubleTap?.invoke(secondUp.position)
            return@awaitEachGesture
        }
        thirdUp.consume()

        onTripleTap.invoke(thirdUp.position)
    }
}
