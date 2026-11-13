package com.example.ui.screens

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.core.view.WindowCompat
import com.example.R
import com.example.core.AlloraAuthSessionState
import com.example.core.AuthSessionViewModel
import com.example.core.FirebaseAuthGateway
import com.example.data.ai.AlloraAiService
import com.example.data.local.AlloraDatabase
import com.example.data.local.AppearanceMode
import com.example.data.local.AppearancePreferencesStore
import com.example.data.model.ApprovalStatus
import com.example.data.remote.AlloraFirestoreRepository
import com.example.data.repository.AlloraRepository
import com.example.data.repository.GeospatialRepository
import com.example.data.repository.WeatherRepository
import com.example.location.TravelLocationService
import com.example.location.TravelLocationTracker
import com.example.ui.components.AlloraAtmosphericBackground
import com.example.ui.components.AlloraGlassNavigationRail
import com.example.ui.components.AlloraTab
import com.example.ui.components.AtmosphereMood
import com.example.ui.components.FloatingNavigation
import com.example.ui.components.GlassLiveBackdropScope
import com.example.ui.components.GlassLoader
import com.example.ui.theme.AlloraTheme
import com.example.ui.viewmodel.AlloraViewModel
import com.example.work.WeatherSyncWorker
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.testTag

private data class AlloraBackendDeps(
    val localRepo: AlloraRepository,
    val database: AlloraDatabase,
    val firestoreRepo: AlloraFirestoreRepository,
    val weatherRepo: WeatherRepository,
    val geospatialRepo: GeospatialRepository,
    val aiService: AlloraAiService
)

@Composable
fun AlloraAppRoot() {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val auth = remember { Firebase.auth }
    val appearanceStore = remember(appContext) { AppearancePreferencesStore.getInstance(appContext) }
    val locationTracker = remember(appContext) { TravelLocationTracker.getInstance(appContext) }

    val sessionViewModel: AuthSessionViewModel = viewModel(
        key = "allora_auth_session",
        factory = viewModelFactory {
            initializer {
                val credentialManager = CredentialManager.create(appContext)
                AuthSessionViewModel(
                    authGateway = FirebaseAuthGateway(auth, credentialManager),
                    appearanceStore = appearanceStore
                )
            }
        }
    )

    val sessionState by sessionViewModel.sessionState.collectAsStateWithLifecycle()
    var pendingOnboardingDraft by remember { mutableStateOf<OnboardingPreferencesDraft?>(null) }

    val activeUid = (sessionState as? AlloraAuthSessionState.Authenticated)?.user?.uid
    val appearanceMode by appearanceStore
        .appearanceModeForUserFlow(activeUid)
        .collectAsStateWithLifecycle(initialValue = AppearanceMode.LIGHT)
    val resolvedDarkTheme = appearanceMode.resolveDarkTheme(isSystemInDarkTheme())
    var lastAppliedLightBars by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(resolvedDarkTheme) {
        val wantLightBars = !resolvedDarkTheme
        if (lastAppliedLightBars == wantLightBars) return@LaunchedEffect
        val activity = context as? ComponentActivity ?: return@LaunchedEffect
        val window = activity.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = wantLightBars
        controller.isAppearanceLightNavigationBars = wantLightBars
        lastAppliedLightBars = wantLightBars
    }

    LaunchedEffect(sessionViewModel) {
        sessionViewModel.resolveStartupSession(context)
    }

    AlloraTheme(darkTheme = resolvedDarkTheme) {
        when (val currentSession = sessionState) {
            is AlloraAuthSessionState.Checking -> {
                AlloraAtmosphericBackground(mood = AtmosphereMood.HOME) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("session_restore_splash"),
                        contentAlignment = Alignment.Center
                    ) {
                        GlassLoader(statusMessage = "Restoring your ALLORA session...")
                    }
                }
            }

            is AlloraAuthSessionState.Unauthenticated -> {
                UnauthenticatedFlowScreen(
                    initialShowOnboarding = !currentSession.skipOnboardingToSignIn,
                    reauthNotice = currentSession.reauthReason,
                    onAuthenticated = { draft ->
                        pendingOnboardingDraft = draft.takeIf { it.wasCustomized }
                        sessionViewModel.clearReauthNotice()
                    }
                )
            }

            is AlloraAuthSessionState.Authenticated -> {
                val user = currentSession.user
                val uid = user.uid
                val backendDeps by produceState<AlloraBackendDeps?>(initialValue = null, uid) {
                    value = withContext(Dispatchers.IO) {
                        val databaseId = appContext.getString(R.string.firestore_database_id)
                        val db = FirebaseFirestore.getInstance(databaseId)
                        val localRepo = AlloraRepository()
                        val firestoreRepo = AlloraFirestoreRepository(db, auth)
                        val weatherRepo = WeatherRepository.createDefault()
                        val geospatialRepo = GeospatialRepository.createDefault()
                        val database = AlloraDatabase.getInstance(appContext)
                        AlloraBackendDeps(
                            localRepo = localRepo,
                            database = database,
                            firestoreRepo = firestoreRepo,
                            weatherRepo = weatherRepo,
                            geospatialRepo = geospatialRepo,
                            aiService = AlloraAiService(
                                weatherRepo = weatherRepo,
                                geospatialRepo = geospatialRepo,
                                firestoreRepo = firestoreRepo
                            )
                        )
                    }
                }

                val deps = backendDeps
                if (deps == null) {
                    AlloraAtmosphericBackground(mood = AtmosphereMood.HOME) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("workspace_loading_splash"),
                            contentAlignment = Alignment.Center
                        ) {
                            GlassLoader(statusMessage = "Preparing your personal ALLORA space...")
                        }
                    }
                } else {
                    key(uid) {
                        val customizedDraft = remember(uid) {
                            val captured = pendingOnboardingDraft
                            pendingOnboardingDraft = null
                            captured?.takeIf { it.wasCustomized }
                        }

                        val viewModel: AlloraViewModel = viewModel(
                            key = uid,
                            factory = viewModelFactory {
                                initializer {
                                    AlloraViewModel(
                                        localRepo = deps.localRepo,
                                        database = deps.database,
                                        firestoreRepo = deps.firestoreRepo,
                                        weatherRepo = deps.weatherRepo,
                                        geospatialRepo = deps.geospatialRepo,
                                        aiService = deps.aiService,
                                        currentUserId = uid,
                                        initialDisplayName = user.displayName,
                                        initialEmail = user.email,
                                        appearanceStore = appearanceStore,
                                        initialOnboardingTravelStyle = customizedDraft?.travelStyle,
                                        initialOnboardingBudgetTier = customizedDraft?.budgetTier,
                                        initialOnboardingHomeAirport = customizedDraft?.homeAirport,
                                        locationTracker = locationTracker,
                                        onStopTrackingService = {
                                            TravelLocationService.stopTracking(appContext, clearLastFix = true)
                                        },
                                        onAuthInvalidated = { reason ->
                                            sessionViewModel.handleSessionInvalidated(reason)
                                        },
                                        onVerifySessionRequested = {
                                            sessionViewModel.verifyActiveSessionNow()
                                        }
                                    )
                                }
                            }
                        )

                        val activeWeatherCity by viewModel.activeWeatherCity.collectAsState()
                        LaunchedEffect(activeWeatherCity) {
                            delay(500L)
                            withContext(Dispatchers.IO) {
                                runCatching { WeatherSyncWorker.schedulePeriodic(appContext, activeWeatherCity) }
                            }
                        }

                        AlloraAuthenticatedShell(
                            viewModel = viewModel,
                            onSignOutRequested = {
                                sessionViewModel.signOut {
                                    viewModel.prepareForSignOut()
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AlloraAuthenticatedShell(
    viewModel: AlloraViewModel,
    onSignOutRequested: () -> Unit
) {
    val reducedMotion by viewModel.reducedMotion.collectAsState()
    val orbState by viewModel.orbState.collectAsState()
    val approvals by viewModel.approvalRequests.collectAsState()
    val pendingApprovalsCount = approvals.count { it.status == ApprovalStatus.PENDING }

    var selectedTab by remember { mutableStateOf(AlloraTab.HOME) }
    var subDestination by remember { mutableStateOf(SubDestination.NONE) }

    BackHandler(enabled = subDestination != SubDestination.NONE) {
        subDestination = SubDestination.NONE
    }

    val mood = when {
        subDestination == SubDestination.AI_ASSISTANT -> AtmosphereMood.ASSISTANT
        subDestination == SubDestination.FLIGHTS -> AtmosphereMood.FLIGHTS
        subDestination == SubDestination.HOTELS -> AtmosphereMood.HOTELS
        subDestination == SubDestination.DINING -> AtmosphereMood.DINING
        subDestination == SubDestination.ACTIVITIES || subDestination == SubDestination.CARS || subDestination == SubDestination.CAMERA -> AtmosphereMood.DISCOVER
        subDestination == SubDestination.MAP -> AtmosphereMood.MAP
        subDestination == SubDestination.APPROVALS -> AtmosphereMood.APPROVALS
        selectedTab == AlloraTab.DISCOVER -> AtmosphereMood.DISCOVER
        selectedTab == AlloraTab.TRIPS -> AtmosphereMood.TRIPS
        selectedTab == AlloraTab.BOOKINGS -> AtmosphereMood.APPROVALS
        else -> AtmosphereMood.HOME
    }

    val hideBottomCapsule = subDestination == SubDestination.AI_ASSISTANT ||
        subDestination == SubDestination.MAP ||
        subDestination == SubDestination.CAMERA ||
        subDestination == SubDestination.TRIP_WIZARD

    AlloraAtmosphericBackground(mood = mood) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
            ) {
                val isExpandedWidth = maxWidth >= 720.dp

                Row(modifier = Modifier.fillMaxSize()) {
                    if (isExpandedWidth) {
                        AlloraGlassNavigationRail(
                            selectedTab = selectedTab,
                            pendingApprovalsCount = pendingApprovalsCount,
                            orbState = orbState,
                            onSelectTab = { tab ->
                                selectedTab = tab
                                subDestination = SubDestination.NONE
                            },
                            onOrbClick = { subDestination = SubDestination.AI_ASSISTANT }
                        )
                    }

                    GlassLiveBackdropScope(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize(),
                        overlayContent = {
                            if (!isExpandedWidth && !hideBottomCapsule) {
                                FloatingNavigation(
                                    selectedTab = selectedTab,
                                    pendingApprovalsCount = pendingApprovalsCount,
                                    orbState = orbState,
                                    reducedMotion = reducedMotion,
                                    onSelectTab = { tab ->
                                        selectedTab = tab
                                        subDestination = SubDestination.NONE
                                    },
                                    onOrbClick = { subDestination = SubDestination.AI_ASSISTANT },
                                    modifier = Modifier.align(Alignment.BottomCenter)
                                )
                            }
                        }
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .widthIn(max = if (subDestination == SubDestination.MAP) 1600.dp else 860.dp)
                                .align(Alignment.TopCenter)
                        ) {
                            when (subDestination) {
                                SubDestination.AI_ASSISTANT -> AiAssistantScreen(
                                    viewModel = viewModel,
                                    onBack = { subDestination = SubDestination.NONE },
                                    onNavigateSub = { subDestination = it }
                                )

                                SubDestination.NOTIFICATIONS -> NotificationsCenterScreen(
                                    viewModel = viewModel,
                                    onBack = { subDestination = SubDestination.NONE },
                                    onNavigateSub = { subDestination = it }
                                )

                                SubDestination.SAVED_ITEMS -> SavedItemsScreen(
                                    viewModel = viewModel,
                                    onBack = { subDestination = SubDestination.NONE }
                                )

                                SubDestination.MAP -> FullScreenMapScreen(
                                    viewModel = viewModel,
                                    onBack = { subDestination = SubDestination.NONE },
                                    onNavigateSub = { subDestination = it }
                                )

                                SubDestination.FLIGHTS -> FlightSearchScreen(
                                    viewModel = viewModel,
                                    onBack = { subDestination = SubDestination.NONE },
                                    onHoldSubmitted = { subDestination = SubDestination.APPROVALS }
                                )

                                SubDestination.HOTELS -> HotelSearchScreen(
                                    viewModel = viewModel,
                                    onBack = { subDestination = SubDestination.NONE },
                                    onHoldSubmitted = { subDestination = SubDestination.APPROVALS }
                                )

                                SubDestination.DINING -> RestaurantDiningScreen(
                                    viewModel = viewModel,
                                    onBack = { subDestination = SubDestination.NONE },
                                    onHoldSubmitted = { subDestination = SubDestination.APPROVALS }
                                )

                                SubDestination.ACTIVITIES -> ActivitiesExcursionsScreen(
                                    viewModel = viewModel,
                                    onBack = { subDestination = SubDestination.NONE },
                                    onHoldSubmitted = { subDestination = SubDestination.APPROVALS },
                                    onNavigateSub = { subDestination = it }
                                )

                                SubDestination.CARS -> CarRentalSearchScreen(
                                    viewModel = viewModel,
                                    onBack = { subDestination = SubDestination.NONE },
                                    onHoldSubmitted = { subDestination = SubDestination.APPROVALS }
                                )

                                SubDestination.TRIP_WIZARD -> TravelPlanningWizardScreen(
                                    viewModel = viewModel,
                                    onBack = { subDestination = SubDestination.NONE },
                                    onFinishToItinerary = { subDestination = SubDestination.ITINERARY }
                                )

                                SubDestination.ITINERARY -> ItineraryTimelineScreen(
                                    viewModel = viewModel,
                                    onBack = { subDestination = SubDestination.NONE },
                                    onNavigateSub = { subDestination = it }
                                )

                                SubDestination.BUDGET -> TravelBudgetScreen(
                                    viewModel = viewModel,
                                    onBack = { subDestination = SubDestination.NONE }
                                )

                                SubDestination.APPROVALS -> ApprovalCenterScreen(
                                    viewModel = viewModel,
                                    onBack = { subDestination = SubDestination.NONE }
                                )

                                SubDestination.AUDIT_HUB -> Phase0AuditHubScreen(
                                    viewModel = viewModel,
                                    onBack = { subDestination = SubDestination.NONE }
                                )

                                SubDestination.CAMERA -> TravelCameraScreen(
                                    viewModel = viewModel,
                                    onBack = { subDestination = SubDestination.NONE }
                                )

                                SubDestination.NONE -> {
                                    when (selectedTab) {
                                        AlloraTab.HOME -> HomeScreen(
                                            viewModel = viewModel,
                                            onNavigateSub = { subDestination = it },
                                            onOpenAssistantWithPrompt = { prompt ->
                                                viewModel.sendAiPrompt(prompt)
                                                subDestination = SubDestination.AI_ASSISTANT
                                            }
                                        )

                                        AlloraTab.DISCOVER -> DiscoverScreen(
                                            viewModel = viewModel,
                                            onNavigateSub = { subDestination = it }
                                        )

                                        AlloraTab.TRIPS -> TripsHubScreen(
                                            viewModel = viewModel,
                                            onNavigateSub = { subDestination = it }
                                        )

                                        AlloraTab.BOOKINGS -> BookingsHubScreen(
                                            viewModel = viewModel,
                                            onNavigateSub = { subDestination = it }
                                        )

                                        AlloraTab.PROFILE -> ProfileSettingsScreen(
                                            viewModel = viewModel,
                                            onNavigateSub = { subDestination = it },
                                            onSignOutRequested = onSignOutRequested
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
    }
}
