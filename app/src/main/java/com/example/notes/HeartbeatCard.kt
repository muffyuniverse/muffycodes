package com.example.notes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The account's four numbers, as a card above the project list.
 *
 * **This is the statistics screen, redesigned as a thing you glance at.** A table you had to go and
 * open answers "what happened between 09:00 and 10:00 on Tuesday", which an agent now answers
 * better from the same database. The question a person actually asks is "is it behaving", asked
 * constantly, and a card where they already are is the only shape that can answer it.
 *
 * **Two windows in one cell, because they are different questions.** The graph is the last two days,
 * so a person can see what normal is; the number is the last hour, so they can see what is happening
 * now; the badge is the difference, so they can see whether now is unusual. A cell with only the
 * number would be a number with no context, and a cell with only the graph would have no present.
 */
@Composable
fun HeartbeatCard(measures: List<Heartbeat.Measure>, modifier: Modifier = Modifier) {
    // **Nothing is drawn before anything has happened.** Four cells of dashes would look like a
    // failure rather than an empty account, and the project list is what a new person needs to see.
    if (measures.isEmpty()) return
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Two rows of two. A `LazyVerticalGrid` would be lazy for four fixed cells, which buys
            // nothing and costs a scroll state.
            for (row in measures.chunked(2)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    for (measure in row) {
                        Cell(measure, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/**
 * The width the value column is given, the same in all four cells.
 *
 * Wide enough for `9.99M` and `>+99.99%` at `titleMedium`, and narrow enough that the graph still
 * gets most of the cell. A value that outgrows it is elided rather than pushing the graph to nothing.
 */
private val VALUE_WIDTH = 64.dp

/**
 * One cell: title and badge on top, graph and value beneath.
 *
 * The layout the operator asked for by position - *"value to the right of the graph, relative change
 * as a badge in its top right corner"* - and the order matters to how it reads: the eye lands on the
 * title, checks the badge for whether to care, and then reads the graph and the number as one thing.
 */
@Composable
private fun Cell(measure: Heartbeat.Measure, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(vertical = 6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                measure.id.title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // **One line, cut rather than wrapped.** "output share" beside a badge is already
                // tight at this width, and a title that wrapped would push the badge onto a row of
                // its own - so the two would stop reading as a pair.
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                Heartbeat.badge(measure.badge),
                style = MaterialTheme.typography.labelMedium,
                color = badgeColour(measure),
                maxLines = 1,
                // The badge must not wrap or clip: it is the one number in the cell that is read
                // first, and `-98.96%` broken across two lines reads as two numbers.
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Bars(
                points = measure.points,
                modifier = Modifier.weight(1f).height(32.dp),
                colour = MaterialTheme.colorScheme.primary,
            )
            Text(
                Heartbeat.format(measure.id, measure.recent),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.End,
                maxLines = 1,
                // **A fixed width, so the four numbers line up down the card.** With `wrapContent`
                // each sits wherever its own digits end, which turns four readings into four ragged
                // edges - the one thing a grid of numbers must not have.
                modifier = Modifier
                    .width(VALUE_WIDTH)
                    .padding(start = 8.dp)
                    .semantics {
                        contentDescription =
                            "${measure.id.title}: ${Heartbeat.format(measure.id, measure.recent)}"
                    },
            )
        }
    }
}

/**
 * Green above, red below - **except spend, which is the brand's caramel and passes no judgement.**
 *
 * Deciding whether more spending is good is not the app's call: a busy hour costs more and also gets
 * more done, and which of those matters depends on what the person is doing. So spend's badge states
 * the comparison and stops. The other three keep green and red, because a cache hit rising and a
 * share of output rising are plainly better and calling them bad would be wrong rather than modest.
 *
 * **The dash stays muted** in every cell, because an absent comparison is not a value at all.
 */
@Composable
private fun badgeColour(measure: Heartbeat.Measure): Color = when {
    measure.badge == null -> MaterialTheme.colorScheme.onSurfaceVariant
    measure.id.neutral -> AppTheme.CARAMEL.swatch
    measure.better == true -> Color(0xFF2E7D32)
    else -> MaterialTheme.colorScheme.error
}

/**
 * Forty-eight windows as forty-eight bars.
 *
 * **A window with no requests is a gap, not a bar at the floor.** The two are different facts - "it
 * spent nothing" against "nothing was recorded" - and drawing the second as the first would show a
 * flat line through a phone that was switched off, which reads as a free two days.
 *
 * **Scaled to this cell's own maximum.** Spend in dollars and tokens in thousands share no axis, so
 * a shared scale would flatten three of the four cells into invisible stubs.
 */
@Composable
private fun Bars(points: List<Double?>, colour: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        if (points.isEmpty()) return@Canvas
        // **The maximum over the windows that have one.** A null is not a zero, so it must not drag
        // the scale down - otherwise one quiet day would make every real bar look small.
        val top = points.filterNotNull().maxOrNull() ?: return@Canvas
        if (top <= 0.0) return@Canvas

        // **The gap is a fraction of the slot, floored at no gap at all.** Forty-eight windows in a
        // cell this wide gives about one and a half dp each; asking for a 30% gap there would spend
        // a third of every bar on nothing and the shape would blur into a smear. So the gap scales
        // down with the slot and disappears rather than eating the bar.
        val slot = size.width / points.size
        val gap = if (slot >= 4f) slot * 0.25f else 0f
        val barWidth = (slot - gap).coerceAtLeast(1f)
        points.forEachIndexed { i, value ->
            if (value == null) return@forEachIndexed
            // A value of zero draws nothing rather than a hairline, because a hairline at the floor
            // is indistinguishable from the gap that means "not recorded".
            val h = (value / top).toFloat() * size.height
            if (h <= 0f) return@forEachIndexed
            drawRect(
                color = colour,
                topLeft = Offset(i * slot, size.height - h),
                size = Size(barWidth, h),
            )
        }
    }
}
