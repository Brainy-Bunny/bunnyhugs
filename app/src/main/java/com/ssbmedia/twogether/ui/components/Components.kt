package com.ssbmedia.twogether.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.ssbmedia.twogether.util.DateFormats
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier.padding(bottom = 8.dp)
    )
}

/**
 * [onClick] is optional so every existing call site (which just displays a number) keeps working
 * unchanged - passing it opts a card into the Stats screen's drill-down navigation (Feature 1), and
 * automatically shows a small chevron as tap affordance (mirroring [QuickLinkChip]'s onClick-driven
 * Card ripple elsewhere in the app) so a tappable stat never looks identical to a purely-informational
 * one.
 */
@Composable
fun StatCard(
    emoji: String,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    onClick: (() -> Unit)? = null,
    // MAJOR fix (independent audit, live-reproducible): defaults to 2 everywhere (unchanged behavior for
    // every existing call site), but "Together since" started passing a genuinely longer narrative date
    // string ("20th September 2026" - see DateFormats.formatDateLong's own doc) that, on a 360dp-class
    // phone at default font scale, can't fit its own long month name across 2 lines in a half-width
    // card and got silently ellipsized mid-word ("20th September…", the year cut off entirely). Exposed
    // as a parameter rather than just bumping the shared default to 3 everywhere, since every OTHER
    // call site's value genuinely only ever needs 1-2 lines and a wider default would just reserve
    // occasionally-unused space again - the exact "ugly gaps" complaint this whole card already went
    // through several rounds of fixing for.
    valueMaxLines: Int = 2
) {
    // Deliberately NOT using Card's own onClick/enabled overload here - Material3 applies a dimmed
    // disabled-content alpha to that overload whenever enabled=false, which would visually fade every
    // plain, non-clickable StatCard (still the majority of call sites) for no reason. A plain
    // Modifier.clickable added only when [onClick] is non-null gives the same ripple/tap affordance
    // without touching any card's appearance when it isn't tappable.
    val clickModifier = if (onClick != null) {
        modifier.clickable(onClick = onClick)
    } else modifier
    // BUG fix (user-reported "so much space/gap"): minLines = 2 below used to unconditionally reserve
    // 2 lines of height for BOTH the value and label text in every single StatCard everywhere, even
    // when every card in that row only ever needed 1 line - e.g. Stats' plain "0.6h"/"Hours together"
    // tiles, which never wrap, still paid for a permanently empty second line each. That was working
    // around a real problem (two StatCards in the same Row ending up different heights when one of
    // them DOES wrap) with a blunt fix that cost every OTHER row genuinely wasted space it never
    // needed. Real fix: each StatCard now fills whatever height its own Row allocates
    // (Modifier.fillMaxHeight() below, paired with that Row needing
    // Modifier.height(IntrinsicSize.Max) at its own call site - see StatsScreen.kt/HomeScreen.kt) -
    // this equalizes cards ONLY within their own specific row, matching whatever that row's own
    // tallest actual content happens to be, rather than a global worst-case reservation everywhere.
    Card(
        modifier = clickModifier.fillMaxHeight(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box {
            // BUG fix (user-reported): same root cause as BadgesScreen's/HomeScreen's own equivalent
            // fixes (see BadgesScreen.kt's take-3 doc) - without fillMaxHeight/verticalArrangement, this
            // Column just wraps its own content and sits top-anchored inside whatever taller height a
            // row-mate's wrapped label/value forces onto this equal-height Card (fillMaxHeight above),
            // leaving dead space below this card's own (shorter) content instead of centering it.
            Column(modifier = Modifier.padding(16.dp).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                Text(text = emoji, style = MaterialTheme.typography.headlineMedium)
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 4.dp))
                // Item 7 (deferred UX fix, 4-model advisory audit): this value slot sits inside a
                // third-width card and some call sites can produce a genuinely long string (e.g. "Down 12
                // days" vs "Same" - see StatsScreen's "This month vs last" tile) - maxLines/ellipsis is a
                // defensive fix applied here, once, for every StatCard rather than special-cased on one
                // call site, so any future long value string is protected the same way without anyone
                // having to remember to add it again. maxLines = 2 (not 1) so it wraps instead of
                // truncating short-but-not-one-line values ("Down 12 days") - see the fillMaxHeight doc
                // above for why this no longer also needs minLines = 2 to keep a row's cards aligned.
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = valueMaxLines,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (onClick != null) {
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(16.dp)
                )
            }
        }
    }
}

@Composable
fun PulsingHeart(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary, size: androidx.compose.ui.unit.Dp = 72.dp) {
    val transition = rememberInfiniteTransition(label = "heart-pulse")
    val scale by transition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(animation = tween(700), repeatMode = RepeatMode.Reverse),
        label = "heart-scale"
    )
    Icon(
        imageVector = Icons.Filled.Favorite,
        contentDescription = "Together",
        tint = color,
        modifier = modifier
            .size(size)
            .graphicsLayer { scaleX = scale; scaleY = scale }
    )
}

@Composable
fun EmptyState(emoji: String, title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = emoji, style = MaterialTheme.typography.displayMedium)
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 12.dp)
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/**
 * UX-FIX-PLAN.md Phase 3 item 18: a read-only, tap-to-open-native-picker date field - replaces the raw
 * ISO `yyyy-MM-dd` free-text fields this app used to have in GalleryImportFlow/CalendarScreen's
 * AddManualSessionDialog (a picker can't produce a typo/unparseable value the way free text could).
 * Shared by both call sites so they can't visually or behaviorally drift apart.
 *
 * Renders as a read-only [OutlinedTextField] (so it keeps the same label/appearance every other text
 * field in this app has) with an invisible clickable [Box] drawn ON TOP of it - a `readOnly` text field
 * still accepts focus/cursor taps rather than reliably surfacing a click the way a plain `enabled = false`
 * field would (which would also visually greyed it out, which isn't wanted here); the overlay is the
 * standard Compose pattern for "looks like a text field, behaves like a button".
 *
 * [minDate]/[maxDate] are enforced structurally via the picker's own [SelectableDates] (an out-of-range
 * day simply can't be tapped) rather than left to the caller's own post-hoc validation - the caller may
 * still keep its own error text as a backstop (per the plan's own instruction to keep existing validation
 * even though a picker can't produce an invalid date), but the picker itself can no longer produce an
 * out-of-range value in the first place.
 *
 * Deliberately converts via [ZoneOffset.UTC] on both ends, never the device's local zone: Material3's
 * DatePicker always represents its selection as UTC-midnight epoch millis internally regardless of the
 * device's own timezone (a well-documented API quirk) - converting through the local zone instead would
 * silently shift the displayed/selected date by one day for any negative-UTC-offset user.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerField(
    label: String,
    date: LocalDate,
    onDateChange: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    minDate: LocalDate? = null,
    maxDate: LocalDate? = null,
    enabled: Boolean = true
) {
    var showPicker by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedTextField(
            value = DateFormats.formatDateLong(date),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth()
        )
        if (enabled) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(onClick = { showPicker = true })
            )
        }
    }
    if (showPicker) {
        val selectableDates = remember(minDate, maxDate) {
            object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val d = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
                    if (minDate != null && d.isBefore(minDate)) return false
                    if (maxDate != null && d.isAfter(maxDate)) return false
                    return true
                }
            }
        }
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = selectableDates
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        onDateChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    showPicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("Cancel") } }
        ) {
            DatePicker(state = state)
        }
    }
}

/**
 * UX-FIX-PLAN.md Phase 3 item 18: a read-only, tap-to-open-native-picker time field - replaces the raw
 * 24-hour `H:MM` free-text time fields this app used to have. Same "read-only text field + invisible
 * clickable overlay" shape as [DatePickerField] - see its own doc for why.
 *
 * Material3 (the version this project is pinned to via its compose-bom) ships [DatePicker] with a ready-
 * made [DatePickerDialog] wrapper but no equivalent `TimePickerDialog` wrapper for [TimePicker] - so this
 * builds its own minimal dialog chrome (a plain [Dialog] + [Surface] + Cancel/OK row) around the bare
 * [TimePicker] composable rather than depending on an API this BOM doesn't provide.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerField(
    label: String,
    time: LocalTime,
    onTimeChange: (LocalTime) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    var showPicker by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedTextField(
            value = DateFormats.formatTime(time),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth()
        )
        if (enabled) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(onClick = { showPicker = true })
            )
        }
    }
    if (showPicker) {
        val state = rememberTimePickerState(initialHour = time.hour, initialMinute = time.minute, is24Hour = false)
        Dialog(onDismissRequest = { showPicker = false }) {
            Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    TimePicker(state = state)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showPicker = false }) { Text("Cancel") }
                        TextButton(onClick = {
                            onTimeChange(LocalTime.of(state.hour, state.minute))
                            showPicker = false
                        }) { Text("OK") }
                    }
                }
            }
        }
    }
}

@Composable
fun QuickLinkChip(emoji: String, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = emoji, style = MaterialTheme.typography.titleMedium)
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}
