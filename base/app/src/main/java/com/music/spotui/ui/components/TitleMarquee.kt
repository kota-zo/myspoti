package com.music.spotui.ui.components

import androidx.compose.foundation.basicMarquee
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.music.spotui.ui.look.rememberReduceMotion

/**
 * Scrolls a one-line title that is wider than its room instead of cutting it with "…": a pause
 * at the start, then a gentle crawl. For the main titles of headers, bars and players, never
 * for the lines of a list.
 *
 * The line keeps the size it had with the ellipsis (the marquee measures the text unbounded but
 * reports the width it was given, and clips inside), so put it AFTER any `clickable` or padding
 * the title already had: the touch target does not grow, shrink or push its neighbours.
 * With "Remove animations" nothing scrolls and the ellipsis stays.
 *
 * @param iterations how many passes before it rests at the start; the players that stay up
 * for the whole song pass [Int.MAX_VALUE], a mini player or a bar scrolls once or twice per title.
 */
@Composable
fun Modifier.titleMarquee(enabled: Boolean = true, iterations: Int = 2): Modifier =
    if (!enabled || rememberReduceMotion()) this
    else this.basicMarquee(iterations = iterations, repeatDelayMillis = 2_500, initialDelayMillis = 1_800, velocity = 28.dp)
