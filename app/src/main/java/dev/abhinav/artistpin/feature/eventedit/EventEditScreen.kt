package dev.abhinav.artistpin.feature.eventedit

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.abhinav.artistpin.core.designsystem.ArtistAvatar
import dev.abhinav.artistpin.core.designsystem.PIN_MOTION_MILLIS
import dev.abhinav.artistpin.core.model.City
import dev.abhinav.artistpin.core.model.Venue
import dev.abhinav.artistpin.core.model.VenueSuggestion
import dev.abhinav.artistpin.data.ArtistSuggestion
import org.koin.androidx.compose.koinViewModel
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

@Stable
interface EventEditActions {
    fun onLinkQueryChange(value: String)
    fun onImportLink()

    fun onArtistQueryChange(value: String)
    fun onArtistPicked(suggestion: ArtistSuggestion)
    fun onAddTypedArtist()
    fun onToggleArtistRole(name: String)
    fun onRemoveArtist(name: String)

    fun onVenueQueryChange(value: String)
    fun onPlaceSuggestionPicked(suggestion: VenueSuggestion)
    fun onKnownVenuePicked(venue: Venue, city: City)
    fun onClearVenue()
    fun onToggleAddress()
    fun onCityChange(value: String)
    fun onRegionChange(value: String)
    fun onCountryChange(value: String)
    fun onShowLocationPicker()
    fun onDismissLocationPicker()
    fun onLocationPicked(latitude: Double, longitude: Double)

    fun onWhenChosen(choice: WhenChoice)
    fun onDismissDatePicker()
    fun onDateSelected(date: LocalDate)

    fun onToggleExtras()
    fun onTitleChange(value: String)

    fun onBackRequested()
    fun onDiscardDismissed()
    fun onDiscardConfirmed()
    fun onSave()
}

/**
 * Add a show: two search fields and a row of chips.
 *
 * The rule the whole screen is built on is that nothing gets typed that could be picked. Artists
 * and venues are searched and tapped — there is no Enter-to-commit anywhere — and picking a venue
 * fills in the city, state and country that used to be three more boxes.
 */
@Composable
fun EventEditScreen(
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    viewModel: EventEditViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is EventEditEffect.ShowMessage -> snackbarHostState.showSnackbar(effect.message)
                    is EventEditEffect.Saved -> onSaved(effect.eventId)
                    EventEditEffect.NavigateBack -> onBack()
                }
            }
        }
    }

    EventEditContent(
        uiState = uiState,
        actions = viewModel,
        snackbarHostState = snackbarHostState,
    )
}

@Composable
fun EventEditContent(
    uiState: EventEditUiState,
    actions: EventEditActions,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    var venueFocusRequest by remember { mutableStateOf(false) }

    // System back goes through the same guard as the header arrow, so neither route can drop a
    // half-filled form on the floor.
    BackHandler { actions.onBackRequested() }

    Box(modifier.fillMaxSize().background(Ink)) {
        Column(Modifier.fillMaxSize()) {
            Header(
                title = if (uiState.isEditing) "Edit show" else "Add a show",
                onBack = actions::onBackRequested,
            )

            // The keyboard pushes the form, not the action bar: the field you're typing in stays
            // visible, and the button stays where it belongs at the bottom of the screen.
            Column(
                Modifier
                    .weight(1f)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp)
                    .padding(bottom = 120.dp),
            ) {
                LinkImportRow(uiState, actions)

                SectionLabel("Who did you see?", top = 6.dp)
                ArtistSection(uiState, actions)

                SectionLabel("Where?")
                VenueSection(
                    uiState = uiState,
                    actions = actions,
                    // Change is a decision to type a different venue, so hand the field the
                    // keyboard rather than making the user tap it a second time.
                    focusRequest = venueFocusRequest,
                    onFocusHandled = { venueFocusRequest = false },
                    onChangeVenue = {
                        actions.onClearVenue()
                        venueFocusRequest = true
                    },
                )

                SectionLabel("When?")
                WhenSection(uiState, actions)

                ExtrasSection(uiState, actions)
            }
        }

        BottomBar(uiState = uiState, onSave = actions::onSave, modifier = Modifier.align(Alignment.BottomCenter))

        SnackbarHost(
            snackbarHostState,
            Modifier.align(Alignment.BottomCenter).padding(bottom = 90.dp),
        )
    }

    if (uiState.showDatePicker) {
        ShowDatePicker(
            initial = uiState.date,
            onDismiss = actions::onDismissDatePicker,
            onSelected = actions::onDateSelected,
        )
    }

    if (uiState.showLocationPicker) {
        val venue = uiState.venue
        VenueLocationPickerDialog(
            initialLatitude = venue?.latitude,
            initialLongitude = venue?.longitude,
            venueName = venue?.name.orEmpty(),
            onDismiss = actions::onDismissLocationPicker,
            onConfirm = actions::onLocationPicked,
        )
    }

    if (uiState.showDiscardPrompt) {
        AlertDialog(
            onDismissRequest = actions::onDiscardDismissed,
            title = { Text("Discard this show?") },
            text = { Text("What you've entered here won't be kept.") },
            confirmButton = { TextButton(onClick = actions::onDiscardConfirmed) { Text("Discard") } },
            dismissButton = { TextButton(onClick = actions::onDiscardDismissed) { Text("Keep editing") } },
        )
    }
}

@Composable
private fun Header(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back",
            tint = Color.White,
            modifier = Modifier.size(22.dp).clickable(onClick = onBack),
        )
        // No Save up here: one action for one outcome, and it lives at the bottom where the
        // form ends.
        Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)
    }
}

@Composable
private fun SectionLabel(text: String, top: Dp = 26.dp) {
    Text(
        text = text.uppercase(),
        color = OnInkTertiary,
        fontSize = 11.5.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.3.sp,
        modifier = Modifier.padding(top = top, bottom = 10.dp),
    )
}

/**
 * Paste a ticket link and the form fills itself in. Sits above everything because it is the
 * shortcut past the rest of the screen — and everything it fills stays editable.
 */
@Composable
private fun LinkImportRow(uiState: EventEditUiState, actions: EventEditActions) {
    Column(Modifier.padding(top = 6.dp)) {
        SearchField(
            value = uiState.linkQuery,
            onValueChange = actions::onLinkQueryChange,
            placeholder = "Paste a DICE link",
            leading = Icons.Default.Link,
            busy = uiState.isImportingLink,
            onSubmit = actions::onImportLink,
        )
        AnimatedVisibility(
            visible = uiState.linkQuery.isNotBlank(),
            enter = expandVertically(tween(RESULTS_MILLIS, easing = PinEasing)) + fadeIn(),
            exit = shrinkVertically(tween(RESULTS_MILLIS, easing = PinEasing)) + fadeOut(),
        ) {
            Box(
                Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(15.dp))
                    .background(AccentFill)
                    .border(1.dp, AccentBorder, RoundedCornerShape(15.dp))
                    .clickable(enabled = !uiState.isImportingLink, onClick = actions::onImportLink)
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (uiState.isImportingLink) "Reading the link…" else "Fill in from link",
                    color = Accent,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

// ---- Who did you see? -------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ArtistSection(uiState: EventEditUiState, actions: EventEditActions) {
    val suggestions = uiState.artistSuggestions

    SearchField(
        value = uiState.artistQuery,
        onValueChange = actions::onArtistQueryChange,
        placeholder = "Search artists",
        leading = Icons.Default.Search,
        busy = uiState.isSearchingArtists,
        // The keyboard's action takes the top result as a convenience — never the only way in.
        onSubmit = {
            suggestions.firstOrNull()?.let(actions::onArtistPicked) ?: actions.onAddTypedArtist()
        },
    )

    AnimatedVisibility(
        visible = suggestions.isNotEmpty() || uiState.canAddTypedArtist,
        enter = expandVertically(tween(RESULTS_MILLIS, easing = PinEasing)) + fadeIn(),
        exit = shrinkVertically(tween(RESULTS_MILLIS, easing = PinEasing)) + fadeOut(),
    ) {
        Column(
            Modifier
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(Surface)
                .border(1.dp, BorderIdle, RoundedCornerShape(15.dp)),
        ) {
            suggestions.forEach { suggestion ->
                ResultRow(
                    onClick = { actions.onArtistPicked(suggestion) },
                    leading = {
                        ArtistAvatar(
                            name = suggestion.name,
                            imageUrl = suggestion.imageUrl,
                            size = 36.dp,
                        )
                    },
                    title = suggestion.name,
                    subtitle = suggestion.meta(),
                    trailing = {
                        Icon(Icons.Default.Add, contentDescription = null, tint = Accent, modifier = Modifier.size(20.dp))
                    },
                )
            }
            if (uiState.canAddTypedArtist) {
                // Nothing matched, and an act nobody has heard of is still an act you saw.
                ResultRow(
                    onClick = actions::onAddTypedArtist,
                    leading = {
                        Box(
                            Modifier.size(36.dp).clip(CircleShape).background(Color(0x14FFFFFF)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = Accent, modifier = Modifier.size(18.dp))
                        }
                    },
                    title = "Add \"${uiState.artistQuery.trim()}\"",
                    subtitle = "Not in your library or on Spotify",
                    trailing = {},
                )
            }
        }
    }

    if (uiState.artists.isNotEmpty()) {
        FlowRow(
            modifier = Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            uiState.artists.forEach { entry ->
                ArtistChip(
                    entry = entry,
                    onToggleRole = { actions.onToggleArtistRole(entry.name) },
                    onRemove = { actions.onRemoveArtist(entry.name) },
                )
            }
        }
        Text(
            text = "Tap a name to switch headliner / support.",
            color = OnInkHint,
            fontSize = 11.5.sp,
            modifier = Modifier.padding(top = 9.dp),
        )
    }
}

/** `minimal techno · seen 2×` — genres place the artist, the count says you've been before. */
private fun ArtistSuggestion.meta(): String = buildString {
    genres.take(2).joinToString(" · ").takeIf { it.isNotBlank() }?.let(::append)
    if (isInLibrary) {
        if (isNotEmpty()) append(" · ")
        append("seen ").append(timesSeen).append("×")
    }
}

@Composable
private fun ArtistChip(entry: ArtistEntry, onToggleRole: () -> Unit, onRemove: () -> Unit) {
    val isHeadliner = entry.role == ArtistRole.HEADLINER
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (isHeadliner) AccentFill else Color(0x0DFFFFFF))
            .border(
                width = 1.dp,
                color = if (isHeadliner) AccentBorder else Color(0x1AFFFFFF),
                shape = CircleShape,
            )
            .padding(start = 7.dp, end = 9.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        ArtistAvatar(name = entry.name, imageUrl = entry.imageUrl, size = 26.dp)
        Row(
            modifier = Modifier.clickable(onClick = onToggleRole),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(entry.name, color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            Text(
                text = if (isHeadliner) "HEADLINER" else "SUPPORT",
                color = if (isHeadliner) Accent else OnInkTertiary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.7.sp,
            )
        }
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(Color(0x1AFFFFFF))
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Remove ${entry.name}",
                tint = Color(0xB3FFFFFF),
                modifier = Modifier.size(11.dp),
            )
        }
    }
}

// ---- Where? ------------------------------------------------------------------------------

@Composable
private fun VenueSection(
    uiState: EventEditUiState,
    actions: EventEditActions,
    focusRequest: Boolean,
    onFocusHandled: () -> Unit,
    onChangeVenue: () -> Unit,
) {
    val venue = uiState.venue
    if (venue == null) {
        val requester = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(focusRequest) {
            if (!focusRequest) return@LaunchedEffect
            runCatching { requester.requestFocus() }
            keyboard?.show()
            onFocusHandled()
        }

        SearchField(
            value = uiState.venueQuery,
            onValueChange = actions::onVenueQueryChange,
            placeholder = if (uiState.venueSearchEnabled) "Search venues" else "Venue search unavailable",
            leading = Icons.Default.Place,
            busy = uiState.isSearchingVenues,
            enabled = uiState.venueSearchEnabled || uiState.knownVenues.isNotEmpty(),
            focusRequester = requester,
        )

        val known = uiState.knownVenueSuggestions
        AnimatedVisibility(
            visible = known.isNotEmpty() || uiState.venueResults.isNotEmpty(),
            enter = expandVertically(tween(RESULTS_MILLIS, easing = PinEasing)) + fadeIn(),
            exit = shrinkVertically(tween(RESULTS_MILLIS, easing = PinEasing)) + fadeOut(),
        ) {
            Column(
                Modifier
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(Surface)
                    .border(1.dp, BorderIdle, RoundedCornerShape(15.dp)),
            ) {
                known.forEach { (knownVenue, city) ->
                    ResultRow(
                        onClick = { actions.onKnownVenuePicked(knownVenue, city) },
                        leading = { MapThumbnail(size = 36.dp, corner = 10.dp) },
                        title = knownVenue.name,
                        subtitle = "${city.name} · ${city.country} · been here before",
                        trailing = {},
                    )
                }
                uiState.venueResults.forEach { suggestion ->
                    ResultRow(
                        onClick = { actions.onPlaceSuggestionPicked(suggestion) },
                        leading = { MapThumbnail(size = 36.dp, corner = 10.dp) },
                        title = suggestion.name,
                        subtitle = suggestion.address,
                        trailing = {},
                    )
                }
            }
        }
        return
    }

    // The card is the payoff for the search, so it carries a little weight: a tinted wash toward
    // the pin, an accent hairline, and a thumbnail that looks like a place rather than a swatch.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.horizontalGradient(listOf(Color(0xFF1B2029), Surface, Surface)),
            )
            .border(1.dp, AccentHairline, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        // The old "Pin set — tap to adjust" button lived on its own; it's the thumbnail now, and
        // the corner glyph is what says so.
        MapThumbnail(
            size = 56.dp,
            corner = 14.dp,
            showAdjustBadge = true,
            modifier = Modifier.clickable(onClick = actions::onShowLocationPicker),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = venue.name,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = venue.where,
                color = OnInkSecondary,
                fontSize = 12.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            Row(
                modifier = Modifier.padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Box(Modifier.size(5.dp).clip(CircleShape).background(Accent))
                Text(
                    text = "Pin set · tap the map to adjust",
                    color = OnInkHint,
                    fontSize = 11.sp,
                )
            }
        }
        Text(
            text = "Change",
            color = Accent,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(CircleShape)
                .border(1.dp, AccentBorder, CircleShape)
                .clickable(onClick = onChangeVenue)
                .padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }

    // A line of grey text gives no sign it does anything; the caret says it opens, and turning
    // over says it's open.
    val addressCaret by animateFloatAsState(
        targetValue = if (uiState.addressExpanded) 180f else 0f,
        animationSpec = tween(PIN_MOTION_MILLIS, easing = PinEasing),
        label = "addressCaret",
    )
    Row(
        modifier = Modifier
            .padding(top = 9.dp)
            .clickable(onClick = actions::onToggleAddress),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = if (uiState.addressExpanded) {
                "Hide city, state and country"
            } else {
                "City, state and country filled in — edit"
            },
            color = OnInkQuiet,
            fontSize = 12.sp,
        )
        Icon(
            Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = OnInkQuiet,
            modifier = Modifier.size(15.dp).rotate(addressCaret),
        )
    }

    AnimatedVisibility(
        visible = uiState.addressExpanded,
        enter = expandVertically(tween(PIN_MOTION_MILLIS, easing = PinEasing)) + fadeIn(),
        exit = shrinkVertically(tween(PIN_MOTION_MILLIS, easing = PinEasing)) + fadeOut(),
    ) {
        Column(
            Modifier.padding(top = 10.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                AddressTile(
                    label = "City",
                    value = venue.city,
                    onValueChange = actions::onCityChange,
                    modifier = Modifier.weight(1f),
                )
                AddressTile(
                    label = "State",
                    value = venue.region,
                    onValueChange = actions::onRegionChange,
                    modifier = Modifier.width(110.dp),
                )
            }
            AddressTile(
                label = "Country",
                value = venue.country,
                onValueChange = actions::onCountryChange,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun AddressTile(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Tile)
            .border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(12.dp))
            .padding(horizontal = 13.dp, vertical = 10.dp),
    ) {
        Text(
            text = label.uppercase(),
            color = OnInkQuiet,
            fontSize = 10.5.sp,
            letterSpacing = 0.4.sp,
        )
        PlainField(
            value = value,
            onValueChange = onValueChange,
            placeholder = "—",
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

/**
 * Stands in for the design's map tile. A live map here would mean one map surface per venue row
 * on a screen that is otherwise a form, so the place is drawn instead: two roads crossing, a
 * block of buildings, and the pin sitting on its own glow.
 */
@Composable
private fun MapThumbnail(
    size: Dp,
    corner: Dp,
    modifier: Modifier = Modifier,
    showAdjustBadge: Boolean = false,
) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(Color(0xFF161A20)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = this.size.width
            val h = this.size.height
            // Roads: one wide diagonal, two thin cross streets. Enough to read as a map at 36dp.
            drawLine(
                color = Color(0x1FFFFFFF),
                start = Offset(-w * 0.1f, h * 0.72f),
                end = Offset(w * 1.1f, h * 0.34f),
                strokeWidth = h * 0.09f,
            )
            drawLine(
                color = Color(0x14FFFFFF),
                start = Offset(w * 0.26f, -h * 0.1f),
                end = Offset(w * 0.42f, h * 1.1f),
                strokeWidth = h * 0.05f,
            )
            drawLine(
                color = Color(0x14FFFFFF),
                start = Offset(w * 0.62f, -h * 0.1f),
                end = Offset(w * 0.78f, h * 1.1f),
                strokeWidth = h * 0.05f,
            )
            // A couple of blocks, so the roads have something to run between.
            drawRoundRect(
                color = Color(0x12FFFFFF),
                topLeft = Offset(w * 0.06f, h * 0.12f),
                size = Size(w * 0.16f, h * 0.24f),
                cornerRadius = CornerRadius(w * 0.03f),
            )
            drawRoundRect(
                color = Color(0x0FFFFFFF),
                topLeft = Offset(w * 0.8f, h * 0.62f),
                size = Size(w * 0.22f, h * 0.26f),
                cornerRadius = CornerRadius(w * 0.03f),
            )
            // The pin's own light, so it reads as sitting on the map rather than printed over it.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x559FC4F0), Color.Transparent),
                    center = Offset(w / 2f, h / 2f),
                    radius = w * 0.42f,
                ),
                radius = w * 0.42f,
                center = Offset(w / 2f, h / 2f),
            )
        }

        Box(
            Modifier
                .size(size / 4)
                .clip(CircleShape)
                .background(Color.White)
                .padding(size / 22)
                .clip(CircleShape)
                .background(Accent),
        )

        if (showAdjustBadge) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(3.dp)
                    .size(16.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color(0xCC0D0E10)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.OpenInFull,
                    contentDescription = "Adjust the pin",
                    tint = Color(0xCCFFFFFF),
                    modifier = Modifier.size(9.dp),
                )
            }
        }
    }
}

// ---- When? -------------------------------------------------------------------------------

@Composable
private fun WhenSection(uiState: EventEditUiState, actions: EventEditActions) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DateChip(
            label = "Tonight",
            selected = uiState.whenChoice == WhenChoice.TONIGHT,
            onClick = { actions.onWhenChosen(WhenChoice.TONIGHT) },
            modifier = Modifier.weight(1f),
        )
        DateChip(
            label = "Last night",
            selected = uiState.whenChoice == WhenChoice.LAST_NIGHT,
            onClick = { actions.onWhenChosen(WhenChoice.LAST_NIGHT) },
            modifier = Modifier.weight(1f),
        )
        DateChip(
            label = if (uiState.whenChoice == WhenChoice.CUSTOM) {
                uiState.date.format(CHIP_DATE)
            } else {
                "Pick"
            },
            selected = uiState.whenChoice == WhenChoice.CUSTOM,
            icon = Icons.Default.CalendarToday,
            onClick = { actions.onWhenChosen(WhenChoice.CUSTOM) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun DateChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(13.dp))
            .background(if (selected) AccentFill else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (selected) AccentBorder else Color(0x21FFFFFF),
                shape = RoundedCornerShape(13.dp),
            )
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (selected) Accent else Color(0x9EFFFFFF)
        icon?.let { Icon(it, contentDescription = null, tint = tint, modifier = Modifier.size(13.dp)) }
        Text(label, color = tint, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShowDatePicker(
    initial: LocalDate,
    onDismiss: () -> Unit,
    onSelected: (LocalDate) -> Unit,
) {
    val pickerState = rememberDatePickerState(initialSelectedDateMillis = initial.toPickerMillis())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        onSelected(pickedDate(millis))
                    } ?: onDismiss()
                },
            ) { Text("Use this date") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state = pickerState)
    }
}

// ---- Festival name -------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExtrasSection(uiState: EventEditUiState, actions: EventEditActions) {
    val bringIntoView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val caret by animateFloatAsState(
        targetValue = if (uiState.extrasExpanded) 180f else 0f,
        animationSpec = tween(PIN_MOTION_MILLIS, easing = PinEasing),
        label = "extrasCaret",
    )
    Row(
        modifier = Modifier
            .padding(top = 26.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .border(1.dp, Color(0x24FFFFFF), RoundedCornerShape(15.dp))
            .clickable(onClick = actions::onToggleExtras)
            .padding(horizontal = 15.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "Festival or tour name",
                color = Color(0xCCFFFFFF),
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = uiState.title.ifBlank { "Optional — otherwise named after the headliner" },
                color = OnInkHint,
                fontSize = 11.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Icon(
            imageVector = Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = OnInkSecondary,
            modifier = Modifier.size(20.dp).rotate(caret),
        )
    }

    AnimatedVisibility(
        visible = uiState.extrasExpanded,
        enter = expandVertically(tween(PIN_MOTION_MILLIS, easing = PinEasing)) + fadeIn(),
        exit = shrinkVertically(tween(PIN_MOTION_MILLIS, easing = PinEasing)) + fadeOut(),
    ) {
        Row(
            // The requester sits outside the trailing padding on purpose: bringing *that* into
            // view scrolls the field clear of the keyboard instead of flush against its edge,
            // which left the bottom of the box tucked underneath.
            modifier = Modifier
                .padding(top = 9.dp)
                .bringIntoViewRequester(bringIntoView)
                .padding(bottom = KEYBOARD_CLEARANCE)
                .fillMaxWidth()
                .clip(RoundedCornerShape(15.dp))
                .background(Surface)
                .border(1.dp, BorderIdle, RoundedCornerShape(15.dp))
                .padding(horizontal = 15.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            PlainField(
                value = uiState.title,
                onValueChange = actions::onTitleChange,
                placeholder = "e.g. Resistance MMW",
                fontSize = 15.sp,
                onFocusChanged = { focused ->
                    if (focused) scope.launch { bringIntoView.bringIntoView() }
                },
                modifier = Modifier.weight(1f),
            )
            if (uiState.title.isNotEmpty()) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Clear festival name",
                    tint = OnInkTertiary,
                    modifier = Modifier.size(16.dp).clickable { actions.onTitleChange("") },
                )
            }
        }
    }
}

// ---- Shared pieces ---------------------------------------------------------------------------

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    leading: androidx.compose.ui.graphics.vector.ImageVector,
    busy: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onSubmit: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
) {
    val focused = value.isNotEmpty()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .background(Surface)
            .border(
                width = 1.dp,
                color = if (focused) AccentBorderStrong else BorderIdle,
                shape = RoundedCornerShape(15.dp),
            )
            .padding(horizontal = 15.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Icon(leading, contentDescription = null, tint = OnInkTertiary, modifier = Modifier.size(18.dp))
        PlainField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            fontSize = 15.5.sp,
            enabled = enabled,
            onSubmit = onSubmit,
            focusRequester = focusRequester,
            modifier = Modifier.weight(1f),
        )
        if (busy) {
            CircularProgressIndicator(Modifier.size(16.dp), color = Accent, strokeWidth = 2.dp)
        } else if (value.isNotEmpty()) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Clear",
                tint = OnInkTertiary,
                modifier = Modifier.size(16.dp).clickable { onValueChange("") },
            )
        }
    }
}

/**
 * A bare text field. Material's OutlinedTextField brings its own container, label and colours,
 * all of which would have to be fought off to land on the design's flat surfaces.
 */
@Composable
private fun PlainField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onSubmit: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    onFocusChanged: ((Boolean) -> Unit)? = null,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    Box(modifier) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = fontSize),
            cursorBrush = SolidColor(Accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            // Done means done: whatever the field wanted to do with the text happens, and then
            // the keyboard gets out of the way rather than sitting there over the form.
            keyboardActions = KeyboardActions(
                onDone = {
                    onSubmit?.invoke()
                    keyboard?.hide()
                    focusManager.clearFocus()
                },
            ),
            modifier = Modifier
                .fillMaxWidth()
                .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                .then(
                    onFocusChanged?.let { callback ->
                        Modifier.onFocusChanged { callback(it.isFocused) }
                    } ?: Modifier,
                ),
        )
        if (value.isEmpty()) {
            Text(placeholder, color = OnInkHint, fontSize = fontSize)
        }
    }
}

@Composable
private fun ResultRow(
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
    title: String,
    subtitle: String,
    trailing: @Composable () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            leading()
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        color = OnInkTertiary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
            }
            trailing()
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x0DFFFFFF)))
    }
}

@Composable
private fun BottomBar(uiState: EventEditUiState, onSave: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Ink, Ink)))
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 16.dp),
    ) {
        val enabled = uiState.isValid && !uiState.isSaving
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(if (enabled) Accent else Color(0x12FFFFFF))
                .clickable(enabled = enabled, onClick = onSave)
                .padding(vertical = 15.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (uiState.isSaving) {
                CircularProgressIndicator(Modifier.size(20.dp), color = OnAccent, strokeWidth = 2.dp)
            } else {
                Text(
                    text = uiState.actionLabel,
                    color = if (enabled) OnAccent else Color(0x59FFFFFF),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

private typealias Dp = androidx.compose.ui.unit.Dp

private val Ink = Color(0xFF0D0E10)
private val Surface = Color(0xFF181A1E)
private val Tile = Color(0xFF141619)
private val BorderIdle = Color(0x17FFFFFF)
private val Accent = Color(0xFF9FC4F0)
private val OnAccent = Color(0xFF07131F)
private val AccentFill = Color(0x1F9FC4F0)
private val AccentBorder = Color(0x669FC4F0)
private val AccentBorderStrong = Color(0x809FC4F0)
private val AccentHairline = Color(0x3D9FC4F0)
private val OnInkSecondary = Color(0x8CFFFFFF)
private val OnInkTertiary = Color(0x73FFFFFF)
private val OnInkQuiet = Color(0x66FFFFFF)
private val OnInkHint = Color(0x59FFFFFF)

private val PinEasing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)
private const val RESULTS_MILLIS = 150

/** How much daylight to leave between the field you're typing in and the top of the keyboard. */
private val KEYBOARD_CLEARANCE = 24.dp
/**
 * Material's date picker works entirely in UTC — it hands back midnight UTC for the day tapped —
 * so the day being shown has to be converted in UTC too. Feeding it local midnight instead put
 * the picker on the previous day for anyone east of Greenwich.
 */
internal fun LocalDate.toPickerMillis(): Long =
    atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

internal fun pickedDate(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

private val CHIP_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
