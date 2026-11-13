package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CredentialManager
import com.example.core.signOutFromAllora
import com.example.data.local.AppearanceMode
import com.example.ui.components.GlassButton
import com.example.ui.components.GlassCard
import com.example.ui.components.GlassChip
import com.example.ui.components.GlassInput
import com.example.ui.components.GlassModal
import com.example.ui.components.GlassNotification
import com.example.ui.components.GlassStateView
import com.example.ui.components.GlassSurface
import com.example.ui.components.GlassSurfaceRole
import com.example.ui.components.GlassSwitch
import com.example.ui.components.GlassTabBar
import com.example.ui.components.StatusGlassPill
import com.example.ui.theme.GlassMaterial
import com.example.ui.theme.LocalAlloraGlassColors
import com.example.ui.theme.TabularNumberStyle
import com.example.ui.viewmodel.AlloraViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileSettingsScreen(
    viewModel: AlloraViewModel,
    onNavigateSub: (SubDestination) -> Unit,
    modifier: Modifier = Modifier,
    onSignOutRequested: (() -> Unit)? = null
) {
    val glass = LocalAlloraGlassColors.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    val profile by viewModel.userProfile.collectAsState()
    val email by viewModel.userEmail.collectAsState()
    val appearanceMode by viewModel.appearanceMode.collectAsState()
    val darkGlass by viewModel.darkGlassTheme.collectAsState()
    val reducedMotion by viewModel.reducedMotion.collectAsState()

    var displayName by remember(profile.displayName) { mutableStateOf(profile.displayName) }
    var homeAirport by remember(profile.homeAirportIata) { mutableStateOf(profile.homeAirportIata) }
    var cabin by remember(profile.preferredCabinClass) { mutableStateOf(profile.preferredCabinClass) }
    var tempUnit by remember(profile.temperatureUnit) { mutableStateOf(profile.temperatureUnit) }
    var threshold by remember(profile.biometricThresholdUsd) { mutableStateOf(profile.biometricThresholdUsd.toFloat()) }
    var showSavedFeedback by remember { mutableStateOf(false) }
    var showClearCacheConfirm by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 144.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            GlassCard(material = GlassMaterial.FROSTED, modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlassSurface(
                        modifier = Modifier.size(56.dp),
                        role = GlassSurfaceRole.CHIP,
                        material = GlassMaterial.CLEAR,
                        shape = CircleShape,
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = profile.displayName.take(1).uppercase(),
                            style = MaterialTheme.typography.headlineMedium.copy(color = glass.textPrimary, fontWeight = FontWeight.Bold)
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(profile.displayName, style = MaterialTheme.typography.titleLarge.copy(color = glass.textPrimary))
                        Text(email.ifBlank { "Google Account Connected" }, style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary))
                        Spacer(modifier = Modifier.height(6.dp))
                        StatusGlassPill(label = "Home: ${profile.homeAirportIata} • ${profile.preferredCabinClass}", color = glass.textSecondary)
                    }
                }
            }
        }

        item {
            AppearanceSelectorSection(
                selectedMode = appearanceMode,
                onSelectMode = { viewModel.setAppearanceMode(it) },
                reducedMotion = reducedMotion,
                onReducedMotionChange = { viewModel.setReducedMotion(it) }
            )
        }

        item {
            GlassCard(material = GlassMaterial.FROSTED, modifier = Modifier.fillMaxWidth()) {
                Text("Travel & Shield Preferences", style = MaterialTheme.typography.titleMedium.copy(color = glass.textPrimary))
                Spacer(modifier = Modifier.height(12.dp))
                GlassInput(value = displayName, onValueChange = { displayName = it }, label = "Display Name", placeholder = "Traveler")
                Spacer(modifier = Modifier.height(10.dp))
                GlassInput(value = homeAirport, onValueChange = { homeAirport = it.uppercase().take(4) }, label = "Home Airport IATA", placeholder = "SFO")
                Spacer(modifier = Modifier.height(14.dp))

                Text("Preferred Cabin Class", style = MaterialTheme.typography.labelLarge.copy(color = glass.textPrimary))
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("ECONOMY", "PREMIUM_ECONOMY", "BUSINESS", "FIRST").forEach { item ->
                        GlassChip(label = item, selected = cabin == item, onClick = { cabin = item })
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                Text("Weather Scale", style = MaterialTheme.typography.labelLarge.copy(color = glass.textPrimary))
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassChip(label = "Celsius (°C)", selected = tempUnit == "METRIC", onClick = { tempUnit = "METRIC" })
                    GlassChip(label = "Fahrenheit (°F)", selected = tempUnit == "IMPERIAL", onClick = { tempUnit = "IMPERIAL" })
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = glass.hairlineDivider, thickness = 0.7.dp)
                Spacer(modifier = Modifier.height(12.dp))
                Text("Shield Approval Threshold: $${threshold.toInt()} USD", style = TabularNumberStyle.copy(color = glass.textPrimary, fontSize = 15.sp))
                Slider(
                    value = threshold,
                    onValueChange = { threshold = it },
                    valueRange = 50f..5000f,
                    colors = SliderDefaults.colors(
                        thumbColor = glass.textPrimary,
                        activeTrackColor = glass.textPrimary,
                        inactiveTrackColor = glass.glassBorder
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))
                GlassButton(
                    text = if (showSavedFeedback) "Saved to Room & Cloud ✓" else "Save Preferences",
                    onClick = {
                        viewModel.updateUserProfile(
                            profile.copy(
                                displayName = displayName.ifBlank { "Traveler" },
                                homeAirportIata = homeAirport.ifBlank { "SFO" },
                                preferredCabinClass = cabin,
                                temperatureUnit = tempUnit,
                                biometricThresholdUsd = threshold.toDouble(),
                                darkGlassTheme = darkGlass
                            )
                        )
                        showSavedFeedback = true
                    },
                    isPrimary = true,
                    material = GlassMaterial.PEARL,
                    leadingIcon = Icons.Default.Check,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        item {
            GlassCard(material = GlassMaterial.CLEAR, modifier = Modifier.fillMaxWidth()) {
                Text("Connected Services & Vault", style = MaterialTheme.typography.titleMedium.copy(color = glass.textPrimary))
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    GlassChip(label = "Saved Items", selected = false, leadingIcon = Icons.Default.Bookmark, onClick = { onNavigateSub(SubDestination.SAVED_ITEMS) })
                    GlassChip(label = "Notifications", selected = false, leadingIcon = Icons.Default.Notifications, onClick = { onNavigateSub(SubDestination.NOTIFICATIONS) })
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    GlassChip(label = "Phase 0 Audit", selected = false, leadingIcon = Icons.Default.Security, onClick = { onNavigateSub(SubDestination.AUDIT_HUB) })
                    GlassChip(label = "Clear Cache", selected = false, leadingIcon = Icons.Default.DeleteOutline, onClick = { showClearCacheConfirm = true })
                }
            }
        }

        item {
            GlassButton(
                text = "Sign Out of ALLORA",
                onClick = {
                    if (onSignOutRequested != null) {
                        onSignOutRequested()
                    } else {
                        coroutineScope.launch {
                            viewModel.prepareForSignOut()
                            signOutFromAllora(credentialManager = credentialManager, scope = coroutineScope)
                        }
                    }
                },
                isPrimary = false,
                material = GlassMaterial.CLEAR,
                leadingIcon = Icons.AutoMirrored.Filled.Logout,
                accentColor = glass.statusError,
                testTag = "profile_sign_out_button",
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    if (showClearCacheConfirm) {
        GlassModal(onDismissRequest = { showClearCacheConfirm = false }) {
            Text("Refresh Offline Room Cache?", style = MaterialTheme.typography.titleLarge.copy(color = glass.textPrimary))
            Spacer(modifier = Modifier.height(8.dp))
            Text("Clears local offline booking records while keeping your cloud Firestore vault intact.", style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary))
            Spacer(modifier = Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                GlassButton(text = "Cancel", onClick = { showClearCacheConfirm = false }, isPrimary = false, material = GlassMaterial.CLEAR, modifier = Modifier.weight(1f))
                GlassButton(
                    text = "Confirm",
                    onClick = {
                        viewModel.clearLocalCacheAndResync()
                        showClearCacheConfirm = false
                    },
                    isPrimary = true,
                    material = GlassMaterial.PEARL,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun NotificationsCenterScreen(
    viewModel: AlloraViewModel,
    onBack: () -> Unit,
    onNavigateSub: (SubDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalAlloraGlassColors.current
    val notifications by viewModel.notifications.collectAsState()
    BackHandler(onBack = onBack)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 144.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ScreenHeaderBar(title = "Notifications", subtitle = "Approvals, AI itineraries & weather alerts", onBack = onBack)
        }
        item {
            GlassChip(label = "Mark All Read", selected = true, onClick = { viewModel.markAllNotificationsRead() })
        }
        if (notifications.isEmpty()) {
            item {
                GlassStateView(title = "All caught up", message = "No active itinerary or Shield alerts right now.")
            }
        } else {
            items(notifications, key = { it.id }) { item ->
                val accent = when (item.category) {
                    "Approval" -> glass.statusWarning
                    "Confirmed" -> glass.statusSuccess
                    "Security" -> glass.statusError
                    else -> glass.textSecondary
                }
                GlassNotification(
                    title = item.title,
                    message = item.message,
                    timestamp = item.timestamp,
                    accentColor = accent,
                    icon = if (item.category == "Approval") Icons.Default.Shield else Icons.Default.Notifications,
                    onClick = {
                        if (item.category == "Approval") onNavigateSub(SubDestination.APPROVALS)
                    }
                )
            }
        }
    }
}

@Composable
fun SavedItemsScreen(
    viewModel: AlloraViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalAlloraGlassColors.current
    val savedItems by viewModel.savedItems.collectAsState()
    var tab by remember { mutableIntStateOf(0) }

    BackHandler(onBack = onBack)

    val filtered = remember(savedItems, tab) {
        when (tab) {
            1 -> savedItems.filter { it.itemType == "DESTINATION" }
            2 -> savedItems.filter { it.itemType == "HOTEL" }
            3 -> savedItems.filter { it.itemType == "FLIGHT" }
            else -> savedItems
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 144.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ScreenHeaderBar(title = "Saved Collection", subtitle = "Synced with your Firestore vault", onBack = onBack)
        }
        item {
            GlassTabBar(
                tabs = listOf("All", "Places", "Hotels", "Flights"),
                selectedIndex = tab,
                onSelectTab = { tab = it }
            )
        }
        if (filtered.isEmpty()) {
            item {
                GlassStateView(
                    title = "No saved items yet",
                    message = "Tap the bookmark icon on any destination, hotel, or flight card to store it here."
                )
            }
        } else {
            items(filtered, key = { it.id }) { doc ->
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            StatusGlassPill(label = "${doc.itemType} • ${doc.category}", color = glass.textSecondary)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(doc.title, style = MaterialTheme.typography.titleMedium.copy(color = glass.textPrimary))
                            Text("${doc.subtitle} • ${doc.priceLabel}", style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary))
                        }
                        GlassChip(
                            label = "Remove",
                            selected = false,
                            onClick = {
                                viewModel.toggleSavedItem(
                                    itemType = doc.itemType,
                                    title = doc.title,
                                    subtitle = doc.subtitle,
                                    category = doc.category,
                                    priceLabel = doc.priceLabel,
                                    rating = doc.rating
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AppearanceSelectorSection(
    selectedMode: AppearanceMode,
    onSelectMode: (AppearanceMode) -> Unit,
    reducedMotion: Boolean,
    onReducedMotionChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalAlloraGlassColors.current
    GlassCard(
        material = GlassMaterial.CLEAR,
        modifier = modifier
            .fillMaxWidth()
            .testTag("appearance_selector_card")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Appearance",
                    style = MaterialTheme.typography.titleMedium.copy(color = glass.textPrimary)
                )
                Text(
                    text = "${selectedMode.title} • ${selectedMode.subtitle}",
                    style = MaterialTheme.typography.labelSmall.copy(color = glass.textSecondary)
                )
            }
            StatusGlassPill(label = selectedMode.title, color = glass.textSecondary)
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AppearanceMode.entries.forEach { mode ->
                val isSelected = selectedMode == mode
                val icon = when (mode) {
                    AppearanceMode.LIGHT -> Icons.Default.LightMode
                    AppearanceMode.DARK -> Icons.Default.DarkMode
                    AppearanceMode.SYSTEM -> Icons.Default.BrightnessAuto
                }
                val shortTitle = when (mode) {
                    AppearanceMode.LIGHT -> "Light"
                    AppearanceMode.DARK -> "Dark"
                    AppearanceMode.SYSTEM -> "System"
                }
                val shortSub = when (mode) {
                    AppearanceMode.LIGHT -> "Crystal"
                    AppearanceMode.DARK -> "Obsidian"
                    AppearanceMode.SYSTEM -> "Default"
                }
                val tag = when (mode) {
                    AppearanceMode.LIGHT -> "appearance_mode_light"
                    AppearanceMode.DARK -> "appearance_mode_dark"
                    AppearanceMode.SYSTEM -> "appearance_mode_system"
                }
                val interactionSource = remember { MutableInteractionSource() }
                val tileShape = RoundedCornerShape(16.dp)
                GlassSurface(
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize(),
                    role = GlassSurfaceRole.CHIP,
                    material = if (isSelected) GlassMaterial.LUMINOUS else GlassMaterial.CLEAR,
                    shape = tileShape,
                    borderWidth = if (isSelected) 1.15.dp else 0.75.dp,
                    accentBorder = if (isSelected) glass.accentPrimary.copy(alpha = 0.7f) else null,
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = interactionSource,
                                indication = ripple(color = glass.accentPrimary),
                                role = Role.RadioButton,
                                onClick = { onSelectMode(mode) }
                            )
                            .semantics { selected = isSelected }
                            .testTag(tag)
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = mode.title,
                            tint = if (isSelected) glass.textPrimary else glass.textSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = shortTitle,
                            style = MaterialTheme.typography.labelLarge.copy(
                                color = if (isSelected) glass.textPrimary else glass.textSecondary,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = shortSub,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = if (isSelected) glass.textSecondary else glass.textMuted
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))
        HorizontalDivider(color = glass.hairlineDivider, thickness = 0.7.dp)
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Reduced Orb Motion", style = MaterialTheme.typography.titleSmall.copy(color = glass.textPrimary))
                Text("Minimizes floating & pulse animations", style = MaterialTheme.typography.labelSmall.copy(color = glass.textSecondary))
            }
            GlassSwitch(checked = reducedMotion, onCheckedChange = onReducedMotionChange)
        }
    }
}
