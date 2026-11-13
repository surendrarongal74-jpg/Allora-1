package com.example.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.SecurityUtils
import com.example.data.ai.AlloraAiService
import com.example.data.local.AlloraDatabase
import com.example.data.local.AppearanceMode
import com.example.data.local.AppearancePreferencesStore
import com.example.data.local.entity.BookingRecord
import com.example.data.local.entity.TravelPhotoRecord
import com.example.data.local.entity.UserPreference
import com.example.location.LocationTrackingState
import com.example.location.TravelLocationTracker
import com.example.data.model.ApprovalRequest
import com.example.data.model.ApprovalStatus
import com.example.data.model.DestinationSearchResult
import com.example.data.model.FlightOffer
import com.example.data.model.GeoCoordinate
import com.example.data.model.ItinerarySlot
import com.example.data.model.LedgerTransaction
import com.example.data.model.LodgingOffer
import com.example.data.model.PointOfInterest
import com.example.data.model.RepoAuditItem
import com.example.data.model.RouteResult
import com.example.data.model.ToolExecutionTrace
import com.example.data.model.WeatherLoadState
import com.example.data.model.WeatherReport
import com.example.data.remote.AiMessageDoc
import com.example.data.remote.AlloraFirestoreRepository
import com.example.data.remote.BookingDoc
import com.example.data.remote.ItinerarySlotDoc
import com.example.data.remote.SavedItemDoc
import com.example.data.remote.TripPlanDoc
import com.example.data.remote.UserProfileDoc
import com.example.data.repository.AlloraRepository
import com.example.data.repository.GeospatialRepository
import com.example.data.repository.WeatherRepository
import com.example.ui.components.OrbState
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class DestinationSpotlight(
    val id: String,
    val city: String,
    val country: String,
    val tagline: String,
    val highlights: String,
    val rating: Double,
    val paletteSeed: Int,
    val category: String,
    val estimatedFlightUsd: Double,
    val estimatedHotelNightUsd: Double
)

data class CarRentalOffer(
    val id: String,
    val modelName: String,
    val vehicleClass: String,
    val seats: Int,
    val transmission: String,
    val dailyRateUsd: Double,
    val insurancePerDayUsd: Double,
    val pickupHub: String,
    val providerSource: String,
    val policySummary: String
)

data class AlloraNotificationItem(
    val id: String,
    val title: String,
    val message: String,
    val timestamp: String,
    val category: String,
    val isUnread: Boolean = true
)

data class AiChatMessage(
    val id: String,
    val isUser: Boolean,
    val text: String,
    val timestamp: String,
    val isError: Boolean = false,
    val isStreaming: Boolean = false,
    val followUpChips: List<String> = emptyList(),
    val toolTraces: List<ToolExecutionTrace> = emptyList()
)

data class TripWizardState(
    val currentStep: Int = 1,
    val destination: String = "Tokyo, Japan",
    val startDate: String = "Dec 12, 2026",
    val endDate: String = "Dec 17, 2026",
    val travellersCount: Int = 2,
    val style: String = "Luxury & Culture",
    val budgetUsd: Double = 4200.0,
    val transport: String = "Business Flight + Rail",
    val lodgingType: String = "Boutique Luxury Hotel",
    val activities: List<String> = listOf("Fine Dining", "Temples & Art", "Skyline Views"),
    val generatedTitle: String = "",
    val generatedSummary: String = "",
    val isGenerating: Boolean = false,
    val generationError: String? = null
)

data class ItineraryMeta(
    val estimatedCostUsd: Double = 0.0,
    val note: String = ""
)

data class BudgetCategoryBreakdown(
    val label: String,
    val estimatedUsd: Double,
    val actualUsd: Double
)

class AlloraViewModel(
    private val localRepo: AlloraRepository,
    private val database: AlloraDatabase,
    private val firestoreRepo: AlloraFirestoreRepository,
    private val weatherRepo: WeatherRepository,
    private val geospatialRepo: GeospatialRepository,
    private val aiService: AlloraAiService,
    val currentUserId: String,
    initialDisplayName: String = "Traveler",
    initialEmail: String = "",
    private val appearanceStore: AppearancePreferencesStore? = null,
    private val initialOnboardingTravelStyle: String? = null,
    private val initialOnboardingBudgetTier: String? = null,
    private val initialOnboardingHomeAirport: String? = null,
    private val locationTracker: TravelLocationTracker? = null,
    private val onStopTrackingService: (() -> Unit)? = null,
    private val onAuthInvalidated: ((String) -> Unit)? = null,
    private val onVerifySessionRequested: (() -> Unit)? = null
) : ViewModel() {

    val auditItems: StateFlow<List<RepoAuditItem>> = localRepo.auditItems
    val flightOffers: StateFlow<List<FlightOffer>> = localRepo.flightOffers
    val lodgingOffers: StateFlow<List<LodgingOffer>> = localRepo.lodgingOffers
    val approvalRequests: StateFlow<List<ApprovalRequest>> = localRepo.approvalRequests
    val ledgerTransactions: StateFlow<List<LedgerTransaction>> = localRepo.ledgerTransactions

    // Weather state backed purely by Open-Meteo API responses (zero fake defaults)
    private val _weatherStates = MutableStateFlow<Map<String, WeatherLoadState>>(emptyMap())
    val weatherStates: StateFlow<Map<String, WeatherLoadState>> = _weatherStates.asStateFlow()

    val weatherReports: StateFlow<Map<String, WeatherReport>> = _weatherStates
        .map { map ->
            map.mapNotNull { (city, state) ->
                (state as? WeatherLoadState.Success)?.let { city to it.report }
            }.toMap()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyMap())

    private val _activeWeatherCity = MutableStateFlow("Tokyo")
    val activeWeatherCity: StateFlow<String> = _activeWeatherCity.asStateFlow()

    // Geospatial & Map state backed by OpenStreetMap Nominatim, Overpass & OSRM
    private val _destinationSearchResults = MutableStateFlow<List<DestinationSearchResult>>(emptyList())
    val destinationSearchResults: StateFlow<List<DestinationSearchResult>> = _destinationSearchResults.asStateFlow()

    private val _poiList = MutableStateFlow<List<PointOfInterest>>(emptyList())
    val poiList: StateFlow<List<PointOfInterest>> = _poiList.asStateFlow()

    private val _mapCenter = MutableStateFlow(GeoCoordinate(latitude = 35.6762, longitude = 139.6503))
    val mapCenter: StateFlow<GeoCoordinate> = _mapCenter.asStateFlow()

    private val _userLocation = MutableStateFlow<GeoCoordinate?>(null)
    val userLocation: StateFlow<GeoCoordinate?> = _userLocation.asStateFlow()

    private val _activeRoute = MutableStateFlow<RouteResult?>(null)
    val activeRoute: StateFlow<RouteResult?> = _activeRoute.asStateFlow()

    private val _mapLoading = MutableStateFlow(false)
    val mapLoading: StateFlow<Boolean> = _mapLoading.asStateFlow()

    private val _mapError = MutableStateFlow<String?>(null)
    val mapError: StateFlow<String?> = _mapError.asStateFlow()

    private val _locationPermissionDenied = MutableStateFlow(false)
    val locationPermissionDenied: StateFlow<Boolean> = _locationPermissionDenied.asStateFlow()

    val locationTrackingState: StateFlow<LocationTrackingState> = locationTracker?.state
        ?: MutableStateFlow(LocationTrackingState()).asStateFlow()

    val travelPhotos: StateFlow<List<TravelPhotoRecord>> = database.travelPhotoDao()
        .observePhotosForUser(currentUserId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    // Trip-scoped Itinerary Slots persisted in Firestore
    private val _selectedTripId = MutableStateFlow<String?>(null)
    val selectedTripId: StateFlow<String?> = _selectedTripId.asStateFlow()

    private val _itinerarySlots = MutableStateFlow<List<ItinerarySlot>>(emptyList())
    val itinerarySlots: StateFlow<List<ItinerarySlot>> = _itinerarySlots.asStateFlow()

    private val _itineraryMeta = MutableStateFlow<Map<String, ItineraryMeta>>(emptyMap())
    val itineraryMeta: StateFlow<Map<String, ItineraryMeta>> = _itineraryMeta.asStateFlow()

    // User-scoped Room cached bookings (strictly filtered by currentUserId)
    val cachedBookings: StateFlow<List<BookingRecord>> = database.bookingRecordDao()
        .observeBookingsForUser(currentUserId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    private val _remoteBookings = MutableStateFlow<List<BookingDoc>>(emptyList())
    val remoteBookings: StateFlow<List<BookingDoc>> = _remoteBookings.asStateFlow()

    private val _remoteTrips = MutableStateFlow<List<TripPlanDoc>>(emptyList())
    val remoteTrips: StateFlow<List<TripPlanDoc>> = _remoteTrips.asStateFlow()

    private val _savedItems = MutableStateFlow<List<SavedItemDoc>>(emptyList())
    val savedItems: StateFlow<List<SavedItemDoc>> = _savedItems.asStateFlow()

    private val _userProfile = MutableStateFlow(
        UserProfileDoc(
            userId = currentUserId,
            displayName = initialDisplayName.ifBlank { "Traveler" }
        )
    )
    val userProfile: StateFlow<UserProfileDoc> = _userProfile.asStateFlow()

    private val _userEmail = MutableStateFlow(initialEmail)
    val userEmail: StateFlow<String> = _userEmail.asStateFlow()

    private val _appearanceMode = MutableStateFlow(AppearanceMode.LIGHT)
    val appearanceMode: StateFlow<AppearanceMode> = _appearanceMode.asStateFlow()

    private val _darkGlassTheme = MutableStateFlow(false)
    val darkGlassTheme: StateFlow<Boolean> = _darkGlassTheme.asStateFlow()

    private val _reducedMotion = MutableStateFlow(false)
    val reducedMotion: StateFlow<Boolean> = _reducedMotion.asStateFlow()

    private val defaultStarterSearches = listOf(
        "Tokyo 4-day weather & sights",
        "Find dining near Ginza",
        "Paris weekend itinerary"
    )
    private val _recentSearches = MutableStateFlow(defaultStarterSearches)
    val recentSearches: StateFlow<List<String>> = _recentSearches.asStateFlow()

    private val _orbState = MutableStateFlow(OrbState.IDLE)
    val orbState: StateFlow<OrbState> = _orbState.asStateFlow()

    private val _aiStatusLabel = MutableStateFlow("Ready • Gemini 3.8 Flash + Tools")
    val aiStatusLabel: StateFlow<String> = _aiStatusLabel.asStateFlow()

    private val defaultWelcomeMessage = AiChatMessage(
        id = "msg-welcome",
        isUser = false,
        text = "Welcome to ALLORA. I can fetch live Open-Meteo weather, search real OpenStreetMap destinations & nearby dining, calculate OSRM routes, estimate budgets, and plan itineraries.",
        timestamp = "Now",
        followUpChips = listOf(
            "Check Tokyo weather",
            "Find restaurants in Tokyo",
            "Plan 4 days in Paris"
        )
    )

    private val _chatMessages = MutableStateFlow(listOf(defaultWelcomeMessage))
    val chatMessages: StateFlow<List<AiChatMessage>> = _chatMessages.asStateFlow()

    private val _wizardState = MutableStateFlow(TripWizardState())
    val wizardState: StateFlow<TripWizardState> = _wizardState.asStateFlow()

    private val _notifications = MutableStateFlow(
        listOf(
            AlloraNotificationItem(
                id = "notif-1",
                title = "Approval Shield Active",
                message = "Review staged holds in Shield before authorizing. Live ticketing requires external provider credentials.",
                timestamp = "Just now",
                category = "Approval"
            )
        )
    )
    val notifications: StateFlow<List<AlloraNotificationItem>> = _notifications.asStateFlow()

    private val _cancelledRefs = MutableStateFlow<Set<String>>(emptySet())
    val cancelledRefs: StateFlow<Set<String>> = _cancelledRefs.asStateFlow()

    private val _cloudSyncError = MutableStateFlow<String?>(null)
    val cloudSyncError: StateFlow<String?> = _cloudSyncError.asStateFlow()

    val destinations: List<DestinationSpotlight> = listOf(
        DestinationSpotlight(
            id = "dest-tokyo",
            city = "Tokyo",
            country = "Japan",
            tagline = "Serene temples, precision transit & sky-high culinary craft",
            highlights = "Otemachi • Ginza • Shibuya • Meiji Jingu",
            rating = 4.9,
            paletteSeed = 0,
            category = "Culture",
            estimatedFlightUsd = 720.0,
            estimatedHotelNightUsd = 620.0
        ),
        DestinationSpotlight(
            id = "dest-paris",
            city = "Paris",
            country = "France",
            tagline = "Architectural elegance, ateliers & Seine twilight salons",
            highlights = "Avenue Montaigne • Louvre • Le Marais",
            rating = 4.8,
            paletteSeed = 1,
            category = "Luxury",
            estimatedFlightUsd = 610.0,
            estimatedHotelNightUsd = 780.0
        ),
        DestinationSpotlight(
            id = "dest-singapore",
            city = "Singapore",
            country = "Singapore",
            tagline = "Biophilic skyline gardens, Marina waterfront & effortless hub",
            highlights = "Marina Bay • Gardens by the Bay • Sentosa",
            rating = 4.8,
            paletteSeed = 2,
            category = "Nature",
            estimatedFlightUsd = 840.0,
            estimatedHotelNightUsd = 450.0
        ),
        DestinationSpotlight(
            id = "dest-london",
            city = "London",
            country = "United Kingdom",
            tagline = "Historic townhouses, West End theatre & Mayfair galleries",
            highlights = "Mayfair • South Bank • Kensington",
            rating = 4.7,
            paletteSeed = 3,
            category = "City",
            estimatedFlightUsd = 580.0,
            estimatedHotelNightUsd = 510.0
        )
    )

    val carRentals: List<CarRentalOffer> = listOf(
        CarRentalOffer(
            id = "car-01",
            modelName = "Porsche Taycan Cross Turismo",
            vehicleClass = "Electric Grand Tourer",
            seats = 4,
            transmission = "Automatic • EV",
            dailyRateUsd = 215.0,
            insurancePerDayUsd = 35.0,
            pickupHub = "Tokyo Haneda VIP Terminal",
            providerSource = "Sample Catalog • Partner Credentials Required",
            policySummary = "Unlimited mileage • Sample hold only (fleet partner not connected)"
        ),
        CarRentalOffer(
            id = "car-02",
            modelName = "BMW i5 eDrive40 Touring",
            vehicleClass = "Executive Sedan",
            seats = 5,
            transmission = "Automatic • EV",
            dailyRateUsd = 148.0,
            insurancePerDayUsd = 26.0,
            pickupHub = "Tokyo Station / Marunouchi",
            providerSource = "Sample Catalog • Amadeus Car Credentials Required",
            policySummary = "Full-to-full energy policy • Collision waiver optional • Staged hold"
        ),
        CarRentalOffer(
            id = "car-03",
            modelName = "Range Rover Velar Autobiography",
            vehicleClass = "Luxury SUV",
            seats = 5,
            transmission = "Automatic • Hybrid",
            dailyRateUsd = 185.0,
            insurancePerDayUsd = 32.0,
            pickupHub = "Paris CDG Terminal 2E",
            providerSource = "Sample Catalog • Partner Credentials Required",
            policySummary = "Cross-border EU transit allowed • Staged rental hold"
        )
    )

    private var activeAiJob: Job? = null
    private var itineraryObserverJob: Job? = null
    private val firestoreObserverJobs = mutableListOf<Job>()
    private var hasLoadedServerProfile = false

    private var lastAutoPoiRefreshCoord: GeoCoordinate? = null

    init {
        observeAppearancePreferences()
        loadInitialRoomPreferences()
        startFirestoreObservers()
        observeLocationTracker()
        viewModelScope.launch {
            delay(250L)
            loadWeatherForFeaturedCities()
            refreshNearbyPois(35.6762, 139.6503, "All")
        }
    }

    private fun observeLocationTracker() {
        val tracker = locationTracker ?: return
        viewModelScope.launch {
            tracker.state.collect { locState ->
                val coord = locState.currentLocation ?: return@collect
                _userLocation.value = coord
                _locationPermissionDenied.value = false
                if (locState.isTracking) {
                    val prev = lastAutoPoiRefreshCoord
                    val movedKm = if (prev == null) {
                        Double.MAX_VALUE
                    } else {
                        GeospatialRepository.haversineDistanceKm(
                            prev.latitude,
                            prev.longitude,
                            coord.latitude,
                            coord.longitude
                        )
                    }
                    if (movedKm >= 0.4) {
                        lastAutoPoiRefreshCoord = coord
                        _mapCenter.value = coord
                        refreshNearbyPois(coord.latitude, coord.longitude, "All")
                    }
                }
            }
        }
    }

    private fun observeAppearancePreferences() {
        val store = appearanceStore ?: return
        viewModelScope.launch {
            store.appearanceModeForUserFlow(currentUserId).collect { mode ->
                _appearanceMode.value = mode
                if (mode != AppearanceMode.SYSTEM) {
                    _darkGlassTheme.value = (mode == AppearanceMode.DARK)
                }
            }
        }
    }

    private fun loadInitialRoomPreferences() {
        viewModelScope.launch {
            database.userPreferenceDao().observePreferences(currentUserId).collect { pref ->
                if (pref != null) {
                    _userProfile.update { current ->
                        current.copy(
                            userId = currentUserId,
                            displayName = pref.displayName.ifBlank { current.displayName },
                            preferredCurrency = pref.preferredCurrency,
                            temperatureUnit = pref.temperatureUnit,
                            preferredCabinClass = pref.preferredCabinClass,
                            homeAirportIata = pref.homeAirportIata,
                            travelStyle = pref.travelStyle,
                            budgetTier = pref.budgetTier,
                            biometricThresholdUsd = pref.biometricThresholdUsd,
                            darkGlassTheme = pref.darkGlassTheme
                        )
                    }
                    if (_userEmail.value.isBlank() && pref.email.isNotBlank()) {
                        _userEmail.value = pref.email
                    }
                    val restoredSearches = deserializeRecentSearches(pref.recentSearchesSerialized)
                    if (restoredSearches.isNotEmpty()) {
                        _recentSearches.value = restoredSearches
                    }
                }
            }
        }
    }

    private fun startFirestoreObservers() {
        stopFirestoreObservers()
        firestoreObserverJobs += viewModelScope.launch {
            firestoreRepo.observeUserProfileSnapshot(currentUserId)
                .catch { error -> handleStreamException("profile", error) }
                .collect { snapshot ->
                    val remote = snapshot.profile
                    if (remote != null) {
                        hasLoadedServerProfile = true
                        _userProfile.value = remote
                        withContext(Dispatchers.IO) {
                            val existingLocal = database.userPreferenceDao().getPreferences(currentUserId)
                            database.userPreferenceDao().upsertPreferences(
                                UserPreference(
                                    userId = currentUserId,
                                    displayName = remote.displayName,
                                    email = _userEmail.value.ifBlank { existingLocal?.email.orEmpty() },
                                    preferredCurrency = remote.preferredCurrency,
                                    temperatureUnit = remote.temperatureUnit,
                                    preferredCabinClass = remote.preferredCabinClass,
                                    homeAirportIata = remote.homeAirportIata,
                                    travelStyle = remote.travelStyle,
                                    budgetTier = remote.budgetTier,
                                    biometricThresholdUsd = remote.biometricThresholdUsd,
                                    darkGlassTheme = remote.darkGlassTheme,
                                    recentSearchesSerialized = existingLocal?.recentSearchesSerialized.orEmpty()
                                )
                            )
                        }
                        if (appearanceStore != null &&
                            !appearanceStore.hasUserAppearancePreference(currentUserId) &&
                            remote.darkGlassTheme
                        ) {
                            appearanceStore.setAppearanceModeForUser(currentUserId, AppearanceMode.DARK)
                        }
                    } else if (!snapshot.isFromCache && !hasLoadedServerProfile) {
                        hasLoadedServerProfile = true
                        val seedProfile = _userProfile.value.copy(
                            userId = currentUserId,
                            travelStyle = initialOnboardingTravelStyle?.takeIf { it.isNotBlank() }
                                ?: _userProfile.value.travelStyle,
                            budgetTier = initialOnboardingBudgetTier?.takeIf { it.isNotBlank() }
                                ?: _userProfile.value.budgetTier,
                            homeAirportIata = initialOnboardingHomeAirport?.takeIf { it.isNotBlank() }
                                ?: _userProfile.value.homeAirportIata
                        )
                        _userProfile.value = seedProfile
                        firestoreRepo.upsertUserProfile(seedProfile)
                    }
                }
        }

        firestoreObserverJobs += viewModelScope.launch {
            firestoreRepo.observeTrips(currentUserId)
                .catch { error -> handleStreamException("trips", error) }
                .collect { trips ->
                    _remoteTrips.value = trips
                    val currentSelected = _selectedTripId.value
                    if ((currentSelected == null || trips.none { it.id == currentSelected }) && trips.isNotEmpty()) {
                        selectTrip(trips.first().id)
                    }
                }
        }

        firestoreObserverJobs += viewModelScope.launch {
            firestoreRepo.observeBookings(currentUserId)
                .catch { error -> handleStreamException("bookings", error) }
                .collect { docs ->
                    _remoteBookings.value = docs
                    if (docs.isNotEmpty()) {
                        withContext(Dispatchers.IO) {
                            val records = docs.map { doc ->
                                val effectiveStatus = if (doc.referenceCode in _cancelledRefs.value) {
                                    "CANCELLED"
                                } else {
                                    doc.status
                                }
                                val createdMs = doc.createdAt?.toDate()?.time ?: System.currentTimeMillis()
                                BookingRecord(
                                    id = doc.id,
                                    userId = currentUserId,
                                    referenceCode = doc.referenceCode,
                                    bookingType = doc.bookingType,
                                    title = doc.title,
                                    providerSource = doc.providerSource,
                                    providerPnr = doc.providerPnr,
                                    originOrLocation = doc.originOrLocation,
                                    destination = doc.destination,
                                    startTimestamp = createdMs,
                                    endTimestamp = null,
                                    totalAmountUsd = doc.totalAmountUsd,
                                    status = effectiveStatus,
                                    sha256AuditHash = doc.sha256AuditHash,
                                    isSynced = true,
                                    lastSyncedAt = System.currentTimeMillis(),
                                    createdAt = createdMs
                                )
                            }
                            database.bookingRecordDao().upsertBookings(records)
                        }
                    }
                }
        }

        firestoreObserverJobs += viewModelScope.launch {
            firestoreRepo.observeSavedItems(currentUserId)
                .catch { error -> handleStreamException("saved items", error) }
                .collect { items -> _savedItems.value = items }
        }

        firestoreObserverJobs += viewModelScope.launch {
            firestoreRepo.observeAiMessages(currentUserId)
                .catch { error -> handleStreamException("ai messages", error) }
                .collect { docs ->
                    if (docs.isNotEmpty() && activeAiJob?.isActive != true) {
                        _chatMessages.value = docs.map { doc ->
                            val parsedTraces = doc.toolTraceSummary
                                ?.split(" | ")
                                ?.filter { it.isNotBlank() }
                                ?.map { entry ->
                                    ToolExecutionTrace(
                                        toolName = entry.substringBefore(":").trim(),
                                        summary = entry.substringAfter(":", "").trim(),
                                        succeeded = !entry.contains("[failed]")
                                    )
                                }
                                ?: emptyList()
                            AiChatMessage(
                                id = doc.id,
                                isUser = doc.isUser,
                                text = doc.text,
                                timestamp = "Synced",
                                isError = doc.isError,
                                toolTraces = parsedTraces
                            )
                        }
                    }
                }
        }
    }

    private fun stopFirestoreObservers() {
        firestoreObserverJobs.forEach { it.cancel() }
        firestoreObserverJobs.clear()
        itineraryObserverJob?.cancel()
        itineraryObserverJob = null
    }

    private fun handleStreamException(scopeLabel: String, error: Throwable) {
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        val root = firestoreError ?: error
        Log.w(TAG, "Error observing $scopeLabel", root)
        if (firestoreError?.code == FirebaseFirestoreException.Code.UNAUTHENTICATED) {
            onAuthInvalidated?.invoke("Your ALLORA session expired. Please sign in again.")
            return
        }
        if (firestoreError?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
            onVerifySessionRequested?.invoke()
        }
        _cloudSyncError.value = root.localizedMessage ?: "Cloud sync unavailable ($scopeLabel)"
    }

    fun dismissCloudSyncError() {
        _cloudSyncError.value = null
    }

    // ---- PART 2: Real Weather Methods ----

    fun loadWeatherForFeaturedCities(forceRefresh: Boolean = false) {
        destinations.forEach { dest ->
            loadWeatherForCity(dest.city, forceRefresh = forceRefresh)
        }
    }

    fun loadWeatherForCity(city: String, forceRefresh: Boolean = false) {
        val clean = city.trim().substringBefore(",").trim()
        if (clean.isBlank()) return
        _activeWeatherCity.value = clean
        _weatherStates.update { it + (clean to WeatherLoadState.Loading) }
        viewModelScope.launch {
            val result = weatherRepo.getWeatherForCity(clean, forceRefresh = forceRefresh)
            result.fold(
                onSuccess = { report ->
                    _weatherStates.update {
                        it + (clean to WeatherLoadState.Success(report)) + (report.city to WeatherLoadState.Success(report))
                    }
                },
                onFailure = { err ->
                    val message = err.localizedMessage ?: "Failed to load weather for $clean."
                    _weatherStates.update { it + (clean to WeatherLoadState.Error(message)) }
                }
            )
        }
    }

    // ---- PART 3 & 4: Real Geospatial & Map Methods ----

    fun searchMapDestinations(query: String) {
        val clean = query.trim()
        if (clean.isBlank()) return
        _mapLoading.value = true
        _mapError.value = null
        viewModelScope.launch {
            geospatialRepo.searchDestinations(clean, limit = 8).fold(
                onSuccess = { results ->
                    _destinationSearchResults.value = results
                    val first = results.firstOrNull()
                    if (first != null) {
                        _mapCenter.value = GeoCoordinate(first.latitude, first.longitude)
                        _activeRoute.value = null
                        refreshNearbyPois(first.latitude, first.longitude, "All")
                        loadWeatherForCity(first.name)
                    } else {
                        _mapError.value = "No matching geographic locations found for \"$clean\"."
                    }
                    _mapLoading.value = false
                },
                onFailure = { err ->
                    _mapError.value = err.localizedMessage ?: "Destination search failed."
                    _mapLoading.value = false
                }
            )
        }
    }

    fun selectDestinationOnMap(dest: DestinationSearchResult, category: String = "All") {
        _mapCenter.value = GeoCoordinate(dest.latitude, dest.longitude)
        _activeRoute.value = null
        refreshNearbyPois(dest.latitude, dest.longitude, category)
        loadWeatherForCity(dest.name)
    }

    fun refreshNearbyPois(
        latitude: Double = _mapCenter.value.latitude,
        longitude: Double = _mapCenter.value.longitude,
        category: String = "All"
    ) {
        _mapLoading.value = true
        _mapError.value = null
        viewModelScope.launch {
            geospatialRepo.searchNearbyPlaces(latitude, longitude, category).fold(
                onSuccess = { places ->
                    _poiList.value = places
                    _mapLoading.value = false
                },
                onFailure = { err ->
                    _mapError.value = err.localizedMessage ?: "Nearby places lookup failed."
                    _mapLoading.value = false
                }
            )
        }
    }

    fun computeRouteToPoi(poi: PointOfInterest, profile: String = "driving") {
        val origin = _userLocation.value ?: _mapCenter.value
        _mapLoading.value = true
        _mapError.value = null
        viewModelScope.launch {
            geospatialRepo.calculateRoute(
                originLat = origin.latitude,
                originLng = origin.longitude,
                destLat = poi.lat,
                destLng = poi.lng,
                profile = profile
            ).fold(
                onSuccess = { route ->
                    _activeRoute.value = route
                    _mapLoading.value = false
                },
                onFailure = { err ->
                    _mapError.value = err.localizedMessage ?: "Route calculation unavailable."
                    _mapLoading.value = false
                }
            )
        }
    }

    fun clearActiveRoute() {
        _activeRoute.value = null
    }

    fun updateUserLocation(latitude: Double, longitude: Double, category: String = "All") {
        val coord = GeoCoordinate(latitude, longitude)
        _userLocation.value = coord
        _mapCenter.value = coord
        _locationPermissionDenied.value = false
        lastAutoPoiRefreshCoord = coord
        refreshNearbyPois(latitude, longitude, category)
    }

    fun requestFreshLocationFix(category: String = "All") {
        val tracker = locationTracker ?: return
        tracker.fetchSingleFix { coord, _ ->
            if (coord != null) {
                updateUserLocation(coord.latitude, coord.longitude, category)
            } else {
                refreshNearbyPois(category = category)
            }
        }
    }

    fun onLocationPermissionDenied() {
        _locationPermissionDenied.value = true
        _mapError.value = "Location permission was denied. You can still search any destination manually."
    }

    fun clearMapError() {
        _mapError.value = null
    }

    // ---- Travel & Architectural Photography ----

    fun saveCapturedPhoto(
        filePath: String,
        caption: String,
        category: String,
        destinationName: String,
        latitude: Double? = _userLocation.value?.latitude,
        longitude: Double? = _userLocation.value?.longitude
    ) {
        val id = "photo_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val record = TravelPhotoRecord(
            id = id,
            userId = currentUserId,
            filePath = filePath,
            caption = caption.trim().ifBlank { "$category capture" },
            category = category,
            destinationName = destinationName.trim().ifBlank { _wizardState.value.destination },
            latitude = latitude,
            longitude = longitude,
            capturedAt = System.currentTimeMillis()
        )
        viewModelScope.launch(Dispatchers.IO) {
            database.travelPhotoDao().insertPhoto(record)
        }
        addNotification(
            title = "Architectural Photo Saved",
            message = "${record.caption} (${record.category}) added to your personal field log.",
            category = "Journal"
        )
    }

    fun deleteCapturedPhoto(photo: TravelPhotoRecord) {
        viewModelScope.launch(Dispatchers.IO) {
            database.travelPhotoDao().deletePhotoForUser(currentUserId, photo.id)
            runCatching { java.io.File(photo.filePath).delete() }
        }
    }

    // ---- PART 5: Real AI Tool Orchestration & Persistent Messages ----

    fun sendAiPrompt(prompt: String) {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty()) return

        val updatedSearches = (listOf(trimmed) + _recentSearches.value).distinct().take(5)
        _recentSearches.value = updatedSearches
        viewModelScope.launch(Dispatchers.IO) {
            val currentPref = database.userPreferenceDao().getPreferences(currentUserId)
                ?: UserPreference(
                    userId = currentUserId,
                    displayName = _userProfile.value.displayName,
                    email = _userEmail.value
                )
            database.userPreferenceDao().upsertPreferences(
                currentPref.copy(
                    recentSearchesSerialized = serializeRecentSearches(updatedSearches),
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
        val userMsg = AiChatMessage(
            id = "msg-u-${UUID.randomUUID().toString().take(8)}",
            isUser = true,
            text = trimmed,
            timestamp = "Now"
        )
        val assistantMsgId = "msg-a-${UUID.randomUUID().toString().take(8)}"
        val placeholderMsg = AiChatMessage(
            id = assistantMsgId,
            isUser = false,
            text = "",
            timestamp = "Thinking",
            isStreaming = true
        )

        val historyPairs = _chatMessages.value
            .filter { !it.isError && it.text.isNotBlank() && it.id != "msg-welcome" }
            .map { it.isUser to it.text }

        _chatMessages.update { it + userMsg + placeholderMsg }
        _orbState.value = OrbState.PROCESSING
        _aiStatusLabel.value = "Orchestrating tools..."

        activeAiJob?.cancel()
        activeAiJob = viewModelScope.launch {
            firestoreRepo.saveAiMessage(
                AiMessageDoc(id = userMsg.id, userId = currentUserId, isUser = true, text = trimmed)
            )

            val result = aiService.sendAssistantWithTools(
                prompt = trimmed,
                historyPairs = historyPairs,
                onToolProgress = { label ->
                    _aiStatusLabel.value = label
                }
            )

            result.fold(
                onSuccess = { reply ->
                    _orbState.value = OrbState.IDLE
                    _aiStatusLabel.value = "Ready • Gemini 3.8 Flash + Tools"
                    _chatMessages.update { list ->
                        list.map { msg ->
                            if (msg.id == assistantMsgId) {
                                msg.copy(
                                    text = reply.text,
                                    timestamp = "Now",
                                    isStreaming = false,
                                    followUpChips = reply.followUpChips,
                                    toolTraces = reply.toolTraces
                                )
                            } else msg
                        }
                    }
                    val traceSummary = reply.toolTraces.joinToString(" | ") { trace ->
                        val suffix = if (trace.succeeded) "" else " [failed]"
                        "${trace.toolName}: ${trace.summary}$suffix"
                    }.takeIf { it.isNotBlank() }

                    firestoreRepo.saveAiMessage(
                        AiMessageDoc(
                            id = assistantMsgId,
                            userId = currentUserId,
                            isUser = false,
                            text = reply.text,
                            toolTraceSummary = traceSummary,
                            isError = false
                        )
                    )
                    reply.createdTripId?.let { selectTrip(it) }
                },
                onFailure = { e ->
                    if (e is CancellationException) throw e
                    _orbState.value = OrbState.ERROR
                    _aiStatusLabel.value = "AI service error"
                    val friendlyError = e.localizedMessage ?: "Failed to connect to Gemini AI Logic."
                    val errText = "Unable to complete AI response: $friendlyError"
                    _chatMessages.update { list ->
                        list.map { msg ->
                            if (msg.id == assistantMsgId) {
                                msg.copy(
                                    text = errText,
                                    timestamp = "Error",
                                    isError = true,
                                    isStreaming = false
                                )
                            } else msg
                        }
                    }
                    firestoreRepo.saveAiMessage(
                        AiMessageDoc(
                            id = assistantMsgId,
                            userId = currentUserId,
                            isUser = false,
                            text = errText,
                            isError = true
                        )
                    )
                }
            )
        }
    }

    fun clearAiConversation() {
        activeAiJob?.cancel()
        _orbState.value = OrbState.IDLE
        _aiStatusLabel.value = "Ready • Gemini 3.8 Flash + Tools"
        _chatMessages.value = listOf(defaultWelcomeMessage)
        viewModelScope.launch {
            firestoreRepo.clearAiMessages()
        }
    }

    // ---- PART 6: Trip-Scoped Persistent Itineraries ----

    fun selectTrip(tripId: String) {
        if (_selectedTripId.value == tripId && itineraryObserverJob?.isActive == true) return
        _selectedTripId.value = tripId
        itineraryObserverJob?.cancel()
        itineraryObserverJob = viewModelScope.launch {
            firestoreRepo.observeItinerarySlots(currentUserId, tripId)
                .catch { error -> handleStreamException("itinerary slots", error) }
                .collect { docs ->
                    _itinerarySlots.value = docs.map { doc ->
                        ItinerarySlot(
                            id = doc.id,
                            timeRange = doc.timeRange,
                            title = doc.title,
                            location = doc.location,
                            type = doc.category,
                            durationMinutes = doc.durationMinutes.toInt(),
                            routeSequence = doc.routeSequence.toInt(),
                            calSyncStatus = doc.calSyncStatus,
                            dayNumber = doc.dayNumber.toInt(),
                            latitude = doc.latitude,
                            longitude = doc.longitude,
                            notes = doc.notes,
                            estimatedCostUsd = doc.estimatedCostUsd
                        )
                    }
                    _itineraryMeta.value = docs.associate { doc ->
                        doc.id to ItineraryMeta(estimatedCostUsd = doc.estimatedCostUsd, note = doc.notes)
                    }
                }
        }
    }

    fun updateWizard(transform: (TripWizardState) -> TripWizardState) {
        _wizardState.update(transform)
    }

    fun generateWizardItinerary() {
        val state = _wizardState.value
        if (state.isGenerating) return

        _wizardState.update { it.copy(isGenerating = true, generationError = null) }
        _orbState.value = OrbState.PROCESSING
        _aiStatusLabel.value = "Synthesizing ${state.destination} itinerary..."

        viewModelScope.launch {
            val result = aiService.generateStructuredItinerary(
                destination = state.destination,
                startDate = state.startDate,
                endDate = state.endDate,
                travellers = state.travellersCount,
                style = state.style,
                budgetUsd = state.budgetUsd,
                transport = state.transport,
                lodgingType = state.lodgingType,
                activities = state.activities
            )
            result.onSuccess { generated ->
                val tripRes = firestoreRepo.createTrip(
                    TripPlanDoc(
                        title = generated.title,
                        destination = state.destination,
                        startDate = state.startDate,
                        endDate = state.endDate,
                        travellersCount = state.travellersCount.toLong(),
                        budgetUsd = state.budgetUsd,
                        style = state.style,
                        status = "PLANNED"
                    )
                )
                tripRes.onSuccess { tripId ->
                    val slotDocs = generated.slots.map { slot ->
                        ItinerarySlotDoc(
                            id = slot.id,
                            tripId = tripId,
                            dayNumber = slot.dayNumber.toLong(),
                            timeRange = slot.timeRange,
                            title = slot.title,
                            location = slot.location,
                            latitude = slot.latitude,
                            longitude = slot.longitude,
                            category = slot.type,
                            durationMinutes = slot.durationMinutes.toLong(),
                            routeSequence = slot.routeSequence.toLong(),
                            notes = slot.notes,
                            estimatedCostUsd = slot.estimatedCostUsd,
                            calSyncStatus = "Cloud Synced"
                        )
                    }
                    firestoreRepo.saveAllItinerarySlots(tripId, slotDocs)
                    selectTrip(tripId)
                }.onFailure { err ->
                    _cloudSyncError.value = err.localizedMessage ?: "Failed to save trip to Firestore."
                }

                _itinerarySlots.value = generated.slots
                _itineraryMeta.value = generated.slots.associate { slot ->
                    slot.id to ItineraryMeta(slot.estimatedCostUsd, slot.notes)
                }
                _wizardState.update {
                    it.copy(
                        isGenerating = false,
                        generatedTitle = generated.title,
                        generatedSummary = generated.summary,
                        currentStep = 10
                    )
                }
                _orbState.value = OrbState.IDLE
                _aiStatusLabel.value = "Ready • Gemini 3.8 Flash + Tools"

                addNotification(
                    title = "AI Itinerary Saved",
                    message = "${generated.title} (${generated.slots.size} stops) persisted to Trips.",
                    category = "Itinerary"
                )
            }.onFailure { err ->
                _orbState.value = OrbState.ERROR
                _aiStatusLabel.value = "Itinerary generation failed"
                _wizardState.update {
                    it.copy(
                        isGenerating = false,
                        generationError = err.localizedMessage ?: "Failed to generate itinerary via Gemini."
                    )
                }
            }
        }
    }

    private suspend fun ensureActiveTripId(): String? {
        _selectedTripId.value?.let { return it }
        val wiz = _wizardState.value
        val createRes = firestoreRepo.createTrip(
            TripPlanDoc(
                title = wiz.generatedTitle.ifBlank { "${wiz.destination} Itinerary" },
                destination = wiz.destination,
                startDate = wiz.startDate,
                endDate = wiz.endDate,
                travellersCount = wiz.travellersCount.toLong(),
                budgetUsd = wiz.budgetUsd,
                style = wiz.style,
                status = "PLANNED"
            )
        )
        return createRes.getOrNull()?.also { selectTrip(it) }
    }

    fun moveItinerarySlotUp(slotId: String) {
        val current = _itinerarySlots.value
        val idx = current.indexOfFirst { it.id == slotId }
        if (idx <= 0) return
        val mutable = current.toMutableList()
        val item = mutable.removeAt(idx)
        mutable.add(idx - 1, item)
        val reordered = mutable.mapIndexed { newIdx, slot -> slot.copy(routeSequence = newIdx + 1) }
        _itinerarySlots.value = reordered

        val tripId = _selectedTripId.value ?: return
        viewModelScope.launch {
            val docs = reordered.map { slot ->
                val meta = _itineraryMeta.value[slot.id]
                ItinerarySlotDoc(
                    id = slot.id,
                    tripId = tripId,
                    dayNumber = slot.dayNumber.toLong(),
                    timeRange = slot.timeRange,
                    title = slot.title,
                    location = slot.location,
                    latitude = slot.latitude,
                    longitude = slot.longitude,
                    category = slot.type,
                    durationMinutes = slot.durationMinutes.toLong(),
                    routeSequence = slot.routeSequence.toLong(),
                    notes = meta?.note ?: slot.notes,
                    estimatedCostUsd = meta?.estimatedCostUsd ?: slot.estimatedCostUsd,
                    calSyncStatus = slot.calSyncStatus
                )
            }
            firestoreRepo.saveAllItinerarySlots(tripId, docs)
        }
    }

    fun updateItinerarySlotNoteAndCost(
        slotId: String,
        note: String,
        costUsd: Double,
        updatedTitle: String? = null,
        updatedTimeRange: String? = null,
        updatedDayNumber: Int? = null
    ) {
        val cleanCost = costUsd.coerceAtLeast(0.0)
        _itineraryMeta.update { map ->
            map + (slotId to ItineraryMeta(estimatedCostUsd = cleanCost, note = note))
        }
        var updatedSlot: ItinerarySlot? = null
        _itinerarySlots.update { list ->
            list.map { slot ->
                if (slot.id == slotId) {
                    slot.copy(
                        title = updatedTitle?.takeIf { it.isNotBlank() } ?: slot.title,
                        timeRange = updatedTimeRange?.takeIf { it.isNotBlank() } ?: slot.timeRange,
                        dayNumber = updatedDayNumber?.coerceIn(1, 30) ?: slot.dayNumber,
                        notes = note,
                        estimatedCostUsd = cleanCost
                    ).also { updatedSlot = it }
                } else slot
            }
        }
        val slotToSave = updatedSlot ?: return
        val tripId = _selectedTripId.value ?: return
        viewModelScope.launch {
            firestoreRepo.upsertItinerarySlot(
                tripId = tripId,
                slot = ItinerarySlotDoc(
                    id = slotToSave.id,
                    tripId = tripId,
                    dayNumber = slotToSave.dayNumber.toLong(),
                    timeRange = slotToSave.timeRange,
                    title = slotToSave.title,
                    location = slotToSave.location,
                    latitude = slotToSave.latitude,
                    longitude = slotToSave.longitude,
                    category = slotToSave.type,
                    durationMinutes = slotToSave.durationMinutes.toLong(),
                    routeSequence = slotToSave.routeSequence.toLong(),
                    notes = note,
                    estimatedCostUsd = cleanCost,
                    calSyncStatus = slotToSave.calSyncStatus
                )
            )
        }
    }

    fun deleteItinerarySlot(slotId: String) {
        _itinerarySlots.update { list ->
            list.filterNot { it.id == slotId }
                .mapIndexed { idx, slot -> slot.copy(routeSequence = idx + 1) }
        }
        _itineraryMeta.update { it - slotId }
        val tripId = _selectedTripId.value ?: return
        viewModelScope.launch {
            firestoreRepo.deleteItinerarySlot(tripId, slotId)
        }
    }

    fun addItinerarySlot(
        title: String,
        location: String,
        timeRange: String,
        category: String,
        costUsd: Double,
        dayNumber: Int = 1,
        latitude: Double? = null,
        longitude: Double? = null
    ) {
        val id = "slot_${UUID.randomUUID().toString().replace("-", "").take(10)}"
        val nextSeq = _itinerarySlots.value.size + 1
        val cleanCost = costUsd.coerceAtLeast(0.0)
        val newSlot = ItinerarySlot(
            id = id,
            timeRange = timeRange.ifBlank { "Flexible Window" },
            title = title.ifBlank { "Custom Activity" },
            location = location.ifBlank { _wizardState.value.destination },
            type = category,
            durationMinutes = 90,
            routeSequence = nextSeq,
            calSyncStatus = "Cloud Synced",
            dayNumber = dayNumber.coerceIn(1, 30),
            latitude = latitude,
            longitude = longitude,
            notes = "",
            estimatedCostUsd = cleanCost
        )
        _itinerarySlots.update { it + newSlot }
        _itineraryMeta.update { it + (id to ItineraryMeta(cleanCost, "")) }

        viewModelScope.launch {
            val tripId = ensureActiveTripId() ?: return@launch
            firestoreRepo.upsertItinerarySlot(
                tripId = tripId,
                slot = ItinerarySlotDoc(
                    id = id,
                    tripId = tripId,
                    dayNumber = newSlot.dayNumber.toLong(),
                    timeRange = newSlot.timeRange,
                    title = newSlot.title,
                    location = newSlot.location,
                    latitude = latitude,
                    longitude = longitude,
                    category = newSlot.type,
                    durationMinutes = 90L,
                    routeSequence = nextSeq.toLong(),
                    notes = "",
                    estimatedCostUsd = cleanCost,
                    calSyncStatus = "Cloud Synced"
                )
            )
        }
    }

    // ---- PART 1: Bookings, Holds, Approval & Cancellation Persistence ----

    fun requestFlightHold(offer: FlightOffer) {
        localRepo.requestBookingHold(offer)
        addNotification(
            title = "Flight Hold Queued",
            message = "${offer.airline} ${offer.flightNumber} ($${offer.priceUsd.toInt()}) awaiting Shield review.",
            category = "Approval"
        )
    }

    fun requestLodgingHold(offer: LodgingOffer) {
        localRepo.requestLodgingHold(offer)
        addNotification(
            title = "Hotel Hold Queued",
            message = "${offer.name} ($${(offer.pricePerNightUsd * 2).toInt()} for 2 nights) awaiting Shield review.",
            category = "Approval"
        )
    }

    fun requestDiningHold(
        restaurantName: String,
        guests: Int,
        timeSlot: String,
        depositUsd: Double,
        address: String
    ) {
        localRepo.requestCustomHold(
            title = "Dining Hold Request ($restaurantName)",
            category = "Dining Reservation",
            merchantOrProvider = "$restaurantName (Partner API Required)",
            amountUsd = depositUsd,
            details = "$guests guests • $timeSlot • $address",
            providerPolicy = "Staged hold only — restaurant partner integration required for live table lock.",
            expiresInMinutes = 45
        )
        addNotification(
            title = "Dining Hold Queued",
            message = "$restaurantName ($guests guests at $timeSlot) staged in Shield Approval.",
            category = "Approval"
        )
    }

    fun requestActivityHold(
        experienceTitle: String,
        guests: Int,
        sessionWindow: String,
        pricePerGuestUsd: Double,
        location: String
    ) {
        val total = pricePerGuestUsd * guests.coerceAtLeast(1)
        localRepo.requestCustomHold(
            title = "Experience Hold ($experienceTitle)",
            category = "Excursion & Activity",
            merchantOrProvider = "$experienceTitle (Partner API Required)",
            amountUsd = total,
            details = "$guests guest(s) • $sessionWindow • $location",
            providerPolicy = "Staged hold only — excursion partner confirmation required before execution.",
            expiresInMinutes = 90
        )
        addNotification(
            title = "Activity Hold Queued",
            message = "$experienceTitle ($${total.toInt()}) staged in Approval Shield.",
            category = "Approval"
        )
    }

    fun requestCarRentalHold(offer: CarRentalOffer, days: Int, includeInsurance: Boolean) {
        val total = (offer.dailyRateUsd + if (includeInsurance) offer.insurancePerDayUsd else 0.0) * days
        val nowMs = System.currentTimeMillis()
        val ref = "ALR-${UUID.randomUUID().toString().take(4).uppercase()}-CAR"
        val docId = "car_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val title = "${offer.modelName} ($days days)"
        val digest = SecurityUtils.sha256Hex(
            SecurityUtils.buildBookingAuditPayload(
                userId = currentUserId,
                referenceCode = ref,
                bookingType = "CAR_RENTAL",
                title = title,
                amountUsd = total,
                createdAtMs = nowMs
            )
        )

        addNotification(
            title = "Vehicle Hold Saved",
            message = "$title ($${total.toInt()}) saved as a HELD request.",
            category = "Approval"
        )

        viewModelScope.launch {
            database.bookingRecordDao().upsertBooking(
                BookingRecord(
                    id = docId,
                    userId = currentUserId,
                    referenceCode = ref,
                    bookingType = "CAR_RENTAL",
                    title = title,
                    providerSource = offer.providerSource,
                    providerPnr = null,
                    originOrLocation = offer.pickupHub,
                    destination = null,
                    startTimestamp = nowMs,
                    endTimestamp = nowMs + days * 86_400_000L,
                    totalAmountUsd = total,
                    status = "HELD",
                    sha256AuditHash = digest
                )
            )
            firestoreRepo.createBooking(
                BookingDoc(
                    id = docId,
                    userId = currentUserId,
                    referenceCode = ref,
                    bookingType = "CAR_RENTAL",
                    title = title,
                    providerSource = offer.providerSource,
                    providerPnr = null,
                    originOrLocation = offer.pickupHub,
                    totalAmountUsd = total,
                    status = "HELD",
                    sha256AuditHash = digest
                )
            ).onFailure { err ->
                _cloudSyncError.value = err.localizedMessage ?: "Failed to sync vehicle hold to Firestore."
            }
        }
    }

    fun approveRequest(requestId: String) {
        val req = localRepo.approvalRequests.value.firstOrNull { it.id == requestId } ?: return
        val newTx = localRepo.approveRequest(requestId, userId = currentUserId) ?: return
        val docId = "bk_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val safeStatus = if (req.isBookableWithProvider) "USER_APPROVED" else "NOT_BOOKABLE"
        val nowMs = System.currentTimeMillis()

        viewModelScope.launch {
            val bookingType = when {
                req.category.contains("Flight", ignoreCase = true) -> "FLIGHT"
                req.category.contains("Accommodation", ignoreCase = true) || req.category.contains("Hotel", ignoreCase = true) -> "HOTEL"
                req.category.contains("Dining", ignoreCase = true) -> "DINING"
                else -> "ACTIVITY"
            }
            database.bookingRecordDao().upsertBooking(
                BookingRecord(
                    id = docId,
                    userId = currentUserId,
                    referenceCode = newTx.referenceCode,
                    bookingType = bookingType,
                    title = req.title,
                    providerSource = req.merchantOrProvider,
                    providerPnr = null,
                    originOrLocation = req.details.take(60),
                    destination = null,
                    startTimestamp = nowMs,
                    endTimestamp = null,
                    totalAmountUsd = req.amountUsd,
                    status = safeStatus,
                    sha256AuditHash = newTx.sha256Hash
                )
            )
            firestoreRepo.createBooking(
                BookingDoc(
                    id = docId,
                    userId = currentUserId,
                    referenceCode = newTx.referenceCode,
                    bookingType = bookingType,
                    title = req.title,
                    providerSource = req.merchantOrProvider,
                    providerPnr = null,
                    originOrLocation = req.details.take(60),
                    totalAmountUsd = req.amountUsd,
                    status = safeStatus,
                    sha256AuditHash = newTx.sha256Hash
                )
            ).onFailure { err ->
                _cloudSyncError.value = err.localizedMessage ?: "Failed to persist approved hold to Firestore."
            }
        }
        addNotification(
            title = if (req.isBookableWithProvider) "Hold Authorized • ${newTx.referenceCode}" else "Hold Staged • ${newTx.referenceCode}",
            message = if (req.isBookableWithProvider) {
                "${req.title} approved and queued for backend provider confirmation."
            } else {
                "${req.title} recorded as NOT_BOOKABLE (partner API credentials required for issuance)."
            },
            category = "Booking"
        )
    }

    fun rejectRequest(requestId: String) {
        val req = localRepo.approvalRequests.value.firstOrNull { it.id == requestId }
        localRepo.rejectRequest(requestId)
        if (req != null) {
            addNotification(
                title = "Hold Declined",
                message = "${req.title} was declined. Zero charge executed.",
                category = "Security"
            )
        }
    }

    fun cancelConfirmedBooking(referenceCode: String, remoteBookingId: String?) {
        _cancelledRefs.update { it + referenceCode }
        localRepo.markLedgerCancelled(referenceCode)

        val resolvedRemoteId = remoteBookingId
            ?: _remoteBookings.value.firstOrNull { it.referenceCode == referenceCode }?.id

        viewModelScope.launch {
            database.bookingRecordDao().updateStatusByReference(
                userId = currentUserId,
                referenceCode = referenceCode,
                status = "CANCELLED"
            )
            if (!resolvedRemoteId.isNullOrBlank()) {
                firestoreRepo.updateBookingStatus(resolvedRemoteId, "CANCELLED")
                    .onFailure { err ->
                        _cloudSyncError.value = err.localizedMessage ?: "Failed to persist cancellation to Firestore."
                    }
            }
        }
        addNotification(
            title = "Cancellation Recorded",
            message = "Reference $referenceCode marked as CANCELLED in local vault and cloud.",
            category = "Booking"
        )
    }

    fun isItemSaved(title: String, itemType: String): Boolean {
        return _savedItems.value.any { it.title == title && it.itemType == itemType }
    }

    fun toggleSavedItem(
        itemType: String,
        title: String,
        subtitle: String,
        category: String,
        priceLabel: String,
        rating: Double
    ) {
        val existing = _savedItems.value.firstOrNull { it.title == title && it.itemType == itemType }
        viewModelScope.launch {
            if (existing != null) {
                firestoreRepo.deleteSavedItem(existing.id)
            } else {
                firestoreRepo.saveItem(
                    SavedItemDoc(
                        itemType = itemType,
                        title = title,
                        subtitle = subtitle,
                        category = category,
                        priceLabel = priceLabel,
                        rating = rating
                    )
                )
            }
        }
    }

    fun setAppearanceMode(mode: AppearanceMode) {
        _appearanceMode.value = mode
        if (mode != AppearanceMode.SYSTEM) {
            val dark = (mode == AppearanceMode.DARK)
            _darkGlassTheme.value = dark
            if (hasLoadedServerProfile) {
                updateUserProfile(_userProfile.value.copy(darkGlassTheme = dark))
            }
        }
        viewModelScope.launch {
            appearanceStore?.setAppearanceModeForUser(currentUserId, mode)
        }
    }

    fun setDarkGlassTheme(enabled: Boolean) {
        val mode = if (enabled) AppearanceMode.DARK else AppearanceMode.LIGHT
        _darkGlassTheme.value = enabled
        setAppearanceMode(mode)
        updateUserProfile(_userProfile.value.copy(darkGlassTheme = enabled))
    }

    fun setReducedMotion(enabled: Boolean) {
        _reducedMotion.value = enabled
    }

    fun updateUserProfile(updated: UserProfileDoc) {
        _userProfile.value = updated
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val existingLocal = database.userPreferenceDao().getPreferences(currentUserId)
                database.userPreferenceDao().upsertPreferences(
                    UserPreference(
                        userId = currentUserId,
                        displayName = updated.displayName,
                        email = _userEmail.value.ifBlank { existingLocal?.email.orEmpty() },
                        preferredCurrency = updated.preferredCurrency,
                        temperatureUnit = updated.temperatureUnit,
                        preferredCabinClass = updated.preferredCabinClass,
                        homeAirportIata = updated.homeAirportIata,
                        travelStyle = updated.travelStyle,
                        budgetTier = updated.budgetTier,
                        biometricThresholdUsd = updated.biometricThresholdUsd,
                        darkGlassTheme = updated.darkGlassTheme,
                        recentSearchesSerialized = existingLocal?.recentSearchesSerialized.orEmpty()
                    )
                )
            }
            firestoreRepo.upsertUserProfile(updated)
        }
    }

    fun clearLocalCacheAndResync() {
        viewModelScope.launch {
            database.bookingRecordDao().deleteBookingsForUser(currentUserId)
            addNotification(
                title = "Local Cache Cleared",
                message = "Room offline booking cache cleared for your account.",
                category = "System"
            )
        }
    }

    suspend fun prepareForSignOut() {
        activeAiJob?.cancel()
        onStopTrackingService?.invoke()
        stopFirestoreObservers()
        withContext(Dispatchers.IO) {
            database.bookingRecordDao().deleteBookingsForUser(currentUserId)
            database.userPreferenceDao().deletePreferences(currentUserId)
        }
    }

    override fun onCleared() {
        super.onCleared()
        activeAiJob?.cancel()
        stopFirestoreObservers()
    }

    private fun serializeRecentSearches(searches: List<String>): String =
        searches.map { it.replace("\n", " ").trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(5)
            .joinToString("\n")

    private fun deserializeRecentSearches(raw: String): List<String> =
        raw.split("\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(5)

    fun markAllNotificationsRead() {
        _notifications.update { list -> list.map { it.copy(isUnread = false) } }
    }

    fun computeBudgetBreakdowns(): List<BudgetCategoryBreakdown> {
        val activeTx = ledgerTransactions.value.filter {
            it.referenceCode !in _cancelledRefs.value && !it.status.contains("CANCELLED", true)
        }
        val flightActual = activeTx.filter { it.category.contains("Flight", true) }.sumOf { it.amountUsd }
        val lodgingActual = activeTx.filter {
            it.category.contains("Lodg", true) || it.category.contains("Hotel", true) || it.category.contains("Accomm", true)
        }.sumOf { it.amountUsd }
        val diningActual = activeTx.filter { it.category.contains("Dining", true) }.sumOf { it.amountUsd }
        val itineraryExtra = _itineraryMeta.value.values.sumOf { it.estimatedCostUsd }

        val target = _wizardState.value.budgetUsd.coerceAtLeast(1000.0)
        return listOf(
            BudgetCategoryBreakdown("Flights", target * 0.35, flightActual),
            BudgetCategoryBreakdown("Lodging", target * 0.40, lodgingActual),
            BudgetCategoryBreakdown("Dining", target * 0.15, diningActual),
            BudgetCategoryBreakdown("Activities & Transit", target * 0.10, itineraryExtra)
        )
    }

    private fun addNotification(title: String, message: String, category: String) {
        val item = AlloraNotificationItem(
            id = "notif-${UUID.randomUUID().toString().take(6)}",
            title = title,
            message = message,
            timestamp = "Just now",
            category = category,
            isUnread = true
        )
        _notifications.update { list -> listOf(item) + list }
    }

    private companion object {
        const val TAG = "AlloraViewModel"
    }
}
