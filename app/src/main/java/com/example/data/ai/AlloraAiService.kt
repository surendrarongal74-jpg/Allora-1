package com.example.data.ai

import android.util.Log
import com.example.data.model.ItinerarySlot
import com.example.data.model.ToolExecutionTrace
import com.example.data.remote.AlloraFirestoreRepository
import com.example.data.remote.ItinerarySlotDoc
import com.example.data.remote.TripPlanDoc
import com.example.data.repository.GeospatialRepository
import com.example.data.repository.WeatherRepository
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

data class GeneratedTripItinerary(
    val title: String,
    val summary: String,
    val estimatedTotalUsd: Double,
    val slots: List<ItinerarySlot>
)

data class AiAssistantResponse(
    val text: String,
    val toolTraces: List<ToolExecutionTrace>,
    val followUpChips: List<String>,
    val createdTripId: String? = null
)

class AlloraAiService(
    private val weatherRepo: WeatherRepository,
    private val geospatialRepo: GeospatialRepository,
    private val firestoreRepo: AlloraFirestoreRepository? = null
) {

    private val functionTools: List<Tool> by lazy {
        listOf(
            Tool.functionDeclarations(
                listOf(
                    FunctionDeclaration(
                        name = TOOL_GET_WEATHER,
                        description = "Fetches live current weather, feels-like temperature, humidity, wind, precipitation, and 7-day forecast for a destination city via Open-Meteo.",
                        parameters = mapOf(
                            "destination" to Schema.string("City or destination name, e.g. 'Tokyo' or 'Paris, France'"),
                            "days" to Schema.integer("Optional number of forecast days (1-7)")
                        ),
                        optionalParameters = listOf("days")
                    ),
                    FunctionDeclaration(
                        name = TOOL_SEARCH_DESTINATIONS,
                        description = "Searches real geographic destinations, cities, and landmarks using OpenStreetMap Nominatim.",
                        parameters = mapOf(
                            "query" to Schema.string("Place or city search query"),
                            "limit" to Schema.integer("Maximum results (1-10)")
                        ),
                        optionalParameters = listOf("limit")
                    ),
                    FunctionDeclaration(
                        name = TOOL_SEARCH_NEARBY_PLACES,
                        description = "Finds real nearby attractions, restaurants, cafes, or hotels from OpenStreetMap Overpass around a destination or coordinates.",
                        parameters = mapOf(
                            "destination" to Schema.string("City or neighborhood name when coordinates are not provided"),
                            "latitude" to Schema.double("Center latitude in decimal degrees"),
                            "longitude" to Schema.double("Center longitude in decimal degrees"),
                            "category" to Schema.enumeration(
                                values = listOf("All", "Attraction", "Restaurant", "Hotel"),
                                description = "POI category filter"
                            ),
                            "radius_meters" to Schema.integer("Search radius in meters (500-10000)")
                        ),
                        optionalParameters = listOf("destination", "latitude", "longitude", "category", "radius_meters")
                    ),
                    FunctionDeclaration(
                        name = TOOL_GET_ROUTE,
                        description = "Computes real distance, travel duration, and major road names between two places via OSRM OpenStreetMap routing.",
                        parameters = mapOf(
                            "origin" to Schema.string("Origin place name or 'lat,lng'"),
                            "destination" to Schema.string("Destination place name or 'lat,lng'"),
                            "profile" to Schema.enumeration(
                                values = listOf("driving", "walking", "cycling"),
                                description = "Travel mode"
                            )
                        ),
                        optionalParameters = listOf("profile")
                    ),
                    FunctionDeclaration(
                        name = TOOL_CALCULATE_BUDGET,
                        description = "Calculates an explicit structured trip budget estimate breakdown across flights, lodging, dining, activities, and contingency.",
                        parameters = mapOf(
                            "destination" to Schema.string("Destination city"),
                            "days" to Schema.integer("Trip length in days"),
                            "travellers" to Schema.integer("Number of travellers"),
                            "flight_usd" to Schema.double("Optional per-person flight estimate in USD"),
                            "hotel_night_usd" to Schema.double("Optional nightly accommodation rate in USD"),
                            "daily_food_activity_usd" to Schema.double("Optional daily dining & sightseeing spend per person in USD")
                        ),
                        optionalParameters = listOf("flight_usd", "hotel_night_usd", "daily_food_activity_usd")
                    ),
                    FunctionDeclaration(
                        name = TOOL_CREATE_ITINERARY,
                        description = "Generates a structured multi-stop trip itinerary for a destination and optionally saves it to the user's cloud Trips.",
                        parameters = mapOf(
                            "destination" to Schema.string("Destination city and country"),
                            "start_date" to Schema.string("Trip start date"),
                            "end_date" to Schema.string("Trip end date"),
                            "travellers" to Schema.integer("Number of travellers"),
                            "style" to Schema.string("Travel style e.g. Luxury & Culture, Culinary, Adventure"),
                            "budget_usd" to Schema.double("Target budget in USD"),
                            "save_to_trips" to Schema.boolean("Whether to save the generated itinerary into Cloud Firestore Trips")
                        ),
                        optionalParameters = listOf("style", "budget_usd", "save_to_trips")
                    )
                )
            )
        )
    }

    private val assistantModel by lazy {
        Firebase.ai.generativeModel(
            modelName = DEFAULT_MODEL,
            tools = functionTools,
            systemInstruction = content {
                text(
                    """
                    You are ALLORA, an intelligent personal travel & concierge agent.
                    Use the declared tools whenever the user asks for weather, destinations, nearby restaurants/attractions/hotels, routes/distances, trip budgets, or creating an itinerary.
                    Never invent or guess live weather figures, distances, coordinates, or provider confirmation codes (PNRs).
                    If a tool fails or returns an error, report the failure accurately and suggest next steps without fabricating sample data.
                    Remember that ALLORA currently stages booking holds for user approval and requires external partner credentials for live flight/hotel/dining ticketing — never claim a booking is live-confirmed.
                    When essential details (dates, origin, budget, or traveller count) are missing for planning, briefly ask clarifying questions or state clear assumptions.
                    """.trimIndent()
                )
            }
        )
    }

    private val itinerarySchema = Schema.obj(
        properties = mapOf(
            "tripTitle" to Schema.string(description = "Concise title for the trip"),
            "summary" to Schema.string(description = "Brief 2-sentence overview of the tailored trip"),
            "estimatedTotalUsd" to Schema.double(description = "Estimated total budget in USD"),
            "days" to Schema.array(
                items = Schema.obj(
                    properties = mapOf(
                        "dayNumber" to Schema.integer(description = "1-based trip day index"),
                        "timeRange" to Schema.string(description = "Time window e.g. 09:00 AM - 11:30 AM"),
                        "title" to Schema.string(description = "Activity or transit title"),
                        "location" to Schema.string(description = "Neighborhood or venue name"),
                        "category" to Schema.enumeration(
                            values = listOf("Flight", "CheckIn", "Dining", "Sights", "Transit"),
                            description = "Slot category"
                        ),
                        "durationMinutes" to Schema.integer(description = "Duration in minutes"),
                        "estimatedCostUsd" to Schema.double(description = "Estimated slot cost in USD"),
                        "notes" to Schema.string(description = "Practical logistics tip")
                    )
                ),
                description = "Chronological itinerary stops (4 to 8 stops)"
            )
        )
    )

    private val itineraryModel by lazy {
        Firebase.ai.generativeModel(
            modelName = DEFAULT_MODEL,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = itinerarySchema
            }
        )
    }

    suspend fun sendAssistantWithTools(
        prompt: String,
        historyPairs: List<Pair<Boolean, String>>,
        onToolProgress: (String) -> Unit = {}
    ): Result<AiAssistantResponse> {
        val history: List<Content> = historyPairs.takeLast(10).map { (isUser, msg) ->
            content(if (isUser) "user" else "model") { text(msg) }
        }
        val chat = assistantModel.startChat(history = history)
        val traces = mutableListOf<ToolExecutionTrace>()
        var lastCreatedTripId: String? = null

        return try {
            var response = withTimeout(60_000L) { chat.sendMessage(prompt) }
            var round = 0

            while (round < MAX_TOOL_ROUNDS) {
                val calls = response.functionCalls
                if (calls.isEmpty()) break
                round++

                val responseParts = mutableListOf<FunctionResponsePart>()
                for (call in calls) {
                    val toolLabel = humanReadableToolLabel(call.name)
                    onToolProgress("Running $toolLabel...")
                    val execResult = executeToolCall(call.name, call.args)
                    traces.add(execResult.trace)
                    if (execResult.createdTripId != null) {
                        lastCreatedTripId = execResult.createdTripId
                    }
                    responseParts.add(FunctionResponsePart(call.name, execResult.json))
                }

                onToolProgress("Synthesizing tool results...")
                val functionContent = content("function") {
                    for (part in responseParts) {
                        part(part)
                    }
                }
                response = withTimeout(60_000L) { chat.sendMessage(functionContent) }
            }

            val finalText = response.text?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("ALLORA AI returned an empty response.")

            Result.success(
                AiAssistantResponse(
                    text = finalText,
                    toolTraces = traces,
                    followUpChips = suggestFollowUpChips(traces),
                    createdTripId = lastCreatedTripId
                )
            )
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Gemini orchestration timed out", e)
            Result.failure(IllegalStateException("AI request timed out after 60 seconds. Please retry.", e))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Gemini orchestration failed", e)
            Result.failure(e)
        }
    }

    internal data class ToolExecOutput(
        val json: JsonObject,
        val trace: ToolExecutionTrace,
        val createdTripId: String? = null
    )

    internal suspend fun executeToolCall(
        name: String,
        args: Map<String, JsonElement>
    ): ToolExecOutput {
        return when (name) {
            TOOL_GET_WEATHER -> handleGetWeather(args)
            TOOL_SEARCH_DESTINATIONS -> handleSearchDestinations(args)
            TOOL_SEARCH_NEARBY_PLACES -> handleSearchNearbyPlaces(args)
            TOOL_GET_ROUTE -> handleGetRoute(args)
            TOOL_CALCULATE_BUDGET -> handleCalculateBudget(args)
            TOOL_CREATE_ITINERARY -> handleCreateItinerary(args)
            else -> ToolExecOutput(
                json = buildJsonObject {
                    put("status", "error")
                    put("error", "Unknown tool '$name'.")
                },
                trace = ToolExecutionTrace(name, "Unsupported tool requested", false)
            )
        }
    }

    private suspend fun handleGetWeather(args: Map<String, JsonElement>): ToolExecOutput {
        val destination = args.str("destination").orEmpty()
        val daysLimit = (args.int("days") ?: 5).coerceIn(1, 7)
        if (destination.isBlank()) {
            return errorToolOutput(TOOL_GET_WEATHER, "Missing required 'destination' argument.")
        }
        val res = weatherRepo.getWeatherForCity(destination)
        return res.fold(
            onSuccess = { report ->
                val json = buildJsonObject {
                    put("status", "ok")
                    put("source", "Open-Meteo")
                    put("city", report.city)
                    put("country", report.country)
                    put("timezone", report.timezone)
                    put("last_updated", report.lastUpdatedDisplay)
                    put("temp_celsius", report.tempCelsius)
                    put("feels_like_celsius", report.feelsLikeCelsius)
                    put("condition", report.condition)
                    put("humidity_percent", report.humidityPercent)
                    put("wind_kmh", report.windKmh)
                    put("wind_direction", "${report.windCardinal} (${report.windDirectionDegrees}°)")
                    put("precipitation_mm", report.precipitationMm)
                    put("uv_index", report.uvIndex)
                    putJsonArray("daily_forecast") {
                        report.daily.take(daysLimit).forEach { day ->
                            addJsonObject {
                                put("date", day.dateIso)
                                put("condition", day.condition)
                                put("temp_max_c", day.tempMaxCelsius)
                                put("temp_min_c", day.tempMinCelsius)
                                put("precip_prob_max_percent", day.precipitationProbMaxPercent)
                                day.sunriseIso?.let { put("sunrise", it) }
                                day.sunsetIso?.let { put("sunset", it) }
                            }
                        }
                    }
                }
                ToolExecOutput(
                    json = json,
                    trace = ToolExecutionTrace(
                        toolName = TOOL_GET_WEATHER,
                        summary = "${report.city}: ${"%.1f".format(Locale.US, report.tempCelsius)}°C, ${report.condition}",
                        succeeded = true
                    )
                )
            },
            onFailure = { err ->
                errorToolOutput(TOOL_GET_WEATHER, err.localizedMessage ?: "Failed to fetch live weather for $destination.")
            }
        )
    }

    private suspend fun handleSearchDestinations(args: Map<String, JsonElement>): ToolExecOutput {
        val query = args.str("query").orEmpty()
        val limit = (args.int("limit") ?: 5).coerceIn(1, 10)
        if (query.isBlank()) {
            return errorToolOutput(TOOL_SEARCH_DESTINATIONS, "Missing required 'query' argument.")
        }
        val res = geospatialRepo.searchDestinations(query, limit)
        return res.fold(
            onSuccess = { results ->
                val json = buildJsonObject {
                    put("status", "ok")
                    put("source", "OpenStreetMap Nominatim")
                    put("count", results.size)
                    putJsonArray("destinations") {
                        results.forEach { d ->
                            addJsonObject {
                                put("name", d.name)
                                put("display_name", d.displayName)
                                put("country", d.country)
                                put("type", d.placeType)
                                put("latitude", d.latitude)
                                put("longitude", d.longitude)
                            }
                        }
                    }
                }
                ToolExecOutput(
                    json = json,
                    trace = ToolExecutionTrace(
                        toolName = TOOL_SEARCH_DESTINATIONS,
                        summary = "${results.size} match(es) for \"$query\"",
                        succeeded = true
                    )
                )
            },
            onFailure = { err ->
                errorToolOutput(TOOL_SEARCH_DESTINATIONS, err.localizedMessage ?: "Destination search failed.")
            }
        )
    }

    private suspend fun handleSearchNearbyPlaces(args: Map<String, JsonElement>): ToolExecOutput {
        val destination = args.str("destination")
        var lat = args.double("latitude")
        var lng = args.double("longitude")
        val category = args.str("category") ?: "All"
        val radius = (args.int("radius_meters") ?: 2500).coerceIn(300, 10000)

        if (lat == null || lng == null) {
            if (destination.isNullOrBlank()) {
                return errorToolOutput(TOOL_SEARCH_NEARBY_PLACES, "Provide either destination or latitude/longitude.")
            }
            val geo = geospatialRepo.searchDestinations(destination, limit = 1).getOrElse { err ->
                return errorToolOutput(TOOL_SEARCH_NEARBY_PLACES, err.localizedMessage ?: "Could not geocode '$destination'.")
            }
            val first = geo.firstOrNull()
                ?: return errorToolOutput(TOOL_SEARCH_NEARBY_PLACES, "No coordinates found for '$destination'.")
            lat = first.latitude
            lng = first.longitude
        }

        val res = geospatialRepo.searchNearbyPlaces(lat, lng, category, radius)
        return res.fold(
            onSuccess = { places ->
                val json = buildJsonObject {
                    put("status", "ok")
                    put("source", "OpenStreetMap Overpass")
                    put("center_lat", lat)
                    put("center_lng", lng)
                    put("category", category)
                    put("count", places.size)
                    putJsonArray("places") {
                        places.take(12).forEach { poi ->
                            addJsonObject {
                                put("name", poi.name)
                                put("category", poi.category)
                                put("tag", poi.tag)
                                put("address", poi.address)
                                put("latitude", poi.lat)
                                put("longitude", poi.lng)
                                poi.distanceKm?.let { put("distance_km", ((it * 100).toInt() / 100.0)) }
                            }
                        }
                    }
                }
                ToolExecOutput(
                    json = json,
                    trace = ToolExecutionTrace(
                        toolName = TOOL_SEARCH_NEARBY_PLACES,
                        summary = "${places.size} $category place(s) found",
                        succeeded = true
                    )
                )
            },
            onFailure = { err ->
                errorToolOutput(TOOL_SEARCH_NEARBY_PLACES, err.localizedMessage ?: "Nearby places query failed.")
            }
        )
    }

    private suspend fun handleGetRoute(args: Map<String, JsonElement>): ToolExecOutput {
        val origin = args.str("origin").orEmpty()
        val destination = args.str("destination").orEmpty()
        val profile = args.str("profile") ?: "driving"
        if (origin.isBlank() || destination.isBlank()) {
            return errorToolOutput(TOOL_GET_ROUTE, "Both 'origin' and 'destination' are required.")
        }

        val originCoord = resolveCoordinate(origin).getOrElse { err ->
            return errorToolOutput(TOOL_GET_ROUTE, "Origin lookup failed: ${err.localizedMessage}")
        }
        val destCoord = resolveCoordinate(destination).getOrElse { err ->
            return errorToolOutput(TOOL_GET_ROUTE, "Destination lookup failed: ${err.localizedMessage}")
        }

        val res = geospatialRepo.calculateRoute(
            originLat = originCoord.first,
            originLng = originCoord.second,
            destLat = destCoord.first,
            destLng = destCoord.second,
            profile = profile
        )
        return res.fold(
            onSuccess = { route ->
                val distanceKm = route.distanceMeters / 1000.0
                val durationMin = route.durationSeconds / 60.0
                val json = buildJsonObject {
                    put("status", "ok")
                    put("source", route.providerSource)
                    put("profile", route.profile)
                    put("distance_km", ((distanceKm * 10).toInt() / 10.0))
                    put("duration_minutes", durationMin.toInt())
                    putJsonArray("via_streets") {
                        route.viaStreets.forEach { add(JsonPrimitive(it)) }
                    }
                }
                ToolExecOutput(
                    json = json,
                    trace = ToolExecutionTrace(
                        toolName = TOOL_GET_ROUTE,
                        summary = "${"%.1f".format(Locale.US, distanceKm)} km • ${durationMin.toInt()} min (${route.profile})",
                        succeeded = true
                    )
                )
            },
            onFailure = { err ->
                errorToolOutput(TOOL_GET_ROUTE, err.localizedMessage ?: "Route calculation failed.")
            }
        )
    }

    private fun handleCalculateBudget(args: Map<String, JsonElement>): ToolExecOutput {
        val destination = args.str("destination")?.takeIf { it.isNotBlank() } ?: "Trip"
        val days = (args.int("days") ?: 4).coerceIn(1, 60)
        val travellers = (args.int("travellers") ?: 1).coerceIn(1, 20)
        val flightPerPerson = (args.double("flight_usd") ?: 750.0).coerceAtLeast(0.0)
        val hotelNight = (args.double("hotel_night_usd") ?: 280.0).coerceAtLeast(0.0)
        val dailyFoodActivity = (args.double("daily_food_activity_usd") ?: 140.0).coerceAtLeast(0.0)

        val nights = (days - 1).coerceAtLeast(1)
        val rooms = ((travellers + 1) / 2).coerceAtLeast(1)
        val flightsTotal = flightPerPerson * travellers
        val lodgingTotal = hotelNight * nights * rooms
        val foodAndActivitiesTotal = dailyFoodActivity * days * travellers
        val localTransitTotal = 45.0 * days
        val contingency = (flightsTotal + lodgingTotal + foodAndActivitiesTotal + localTransitTotal) * 0.10
        val grandTotal = flightsTotal + lodgingTotal + foodAndActivitiesTotal + localTransitTotal + contingency

        val json = buildJsonObject {
            put("status", "ok")
            put("note", "Budget breakdown computed deterministically (estimates only, not live provider quotes).")
            put("destination", destination)
            put("days", days)
            put("travellers", travellers)
            putJsonObject("breakdown_usd") {
                put("flights", flightsTotal)
                put("lodging", lodgingTotal)
                put("dining_and_activities", foodAndActivitiesTotal)
                put("local_transit", localTransitTotal)
                put("contingency_10pct", contingency)
                put("total_estimated_usd", grandTotal)
                put("per_person_usd", grandTotal / travellers)
            }
        }
        return ToolExecOutput(
            json = json,
            trace = ToolExecutionTrace(
                toolName = TOOL_CALCULATE_BUDGET,
                summary = "$destination ($days d, $travellers pax): ~$${grandTotal.toInt()} USD",
                succeeded = true
            )
        )
    }

    private suspend fun handleCreateItinerary(args: Map<String, JsonElement>): ToolExecOutput {
        val destination = args.str("destination").orEmpty()
        if (destination.isBlank()) {
            return errorToolOutput(TOOL_CREATE_ITINERARY, "Destination is required to create an itinerary.")
        }
        val startDate = args.str("start_date") ?: "Upcoming"
        val endDate = args.str("end_date") ?: "+4 days"
        val travellers = (args.int("travellers") ?: 2).coerceIn(1, 20)
        val style = args.str("style") ?: "Balanced Luxury"
        val budgetUsd = (args.double("budget_usd") ?: 3500.0).coerceAtLeast(200.0)
        val saveToTrips = args.bool("save_to_trips") ?: false

        val genRes = generateStructuredItinerary(
            destination = destination,
            startDate = startDate,
            endDate = endDate,
            travellers = travellers,
            style = style,
            budgetUsd = budgetUsd,
            transport = "Local Transit & Air",
            lodgingType = "Boutique Hotel",
            activities = listOf("Landmarks", "Dining", "Neighborhood Walks")
        )
        return genRes.fold(
            onSuccess = { generated ->
                var savedTripId: String? = null
                var saveStatus = "not_requested"
                if (saveToTrips && firestoreRepo != null) {
                    val createRes = firestoreRepo.createTrip(
                        TripPlanDoc(
                            title = generated.title,
                            destination = destination,
                            startDate = startDate,
                            endDate = endDate,
                            travellersCount = travellers.toLong(),
                            budgetUsd = generated.estimatedTotalUsd,
                            style = style,
                            status = "PLANNED"
                        )
                    )
                    createRes.onSuccess { tripId ->
                        savedTripId = tripId
                        val docs = generated.slots.map { slot ->
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
                                calSyncStatus = "AI Assistant Saved"
                            )
                        }
                        firestoreRepo.saveAllItinerarySlots(tripId, docs)
                        saveStatus = "saved_to_firestore"
                    }.onFailure {
                        saveStatus = "save_failed: ${it.localizedMessage}"
                    }
                }

                val json = buildJsonObject {
                    put("status", "ok")
                    put("title", generated.title)
                    put("summary", generated.summary)
                    put("estimated_total_usd", generated.estimatedTotalUsd)
                    put("cloud_save_status", saveStatus)
                    savedTripId?.let { put("trip_id", it) }
                    putJsonArray("stops") {
                        generated.slots.forEach { slot ->
                            addJsonObject {
                                put("day", slot.dayNumber)
                                put("time_range", slot.timeRange)
                                put("title", slot.title)
                                put("location", slot.location)
                                put("category", slot.type)
                                put("estimated_cost_usd", slot.estimatedCostUsd)
                            }
                        }
                    }
                }
                ToolExecOutput(
                    json = json,
                    trace = ToolExecutionTrace(
                        toolName = TOOL_CREATE_ITINERARY,
                        summary = "${generated.title} (${generated.slots.size} stops)",
                        succeeded = true
                    ),
                    createdTripId = savedTripId
                )
            },
            onFailure = { err ->
                errorToolOutput(TOOL_CREATE_ITINERARY, err.localizedMessage ?: "Failed to generate itinerary.")
            }
        )
    }

    private suspend fun resolveCoordinate(input: String): Result<Pair<Double, Double>> {
        val parts = input.split(",").map { it.trim() }
        if (parts.size == 2) {
            val lat = parts[0].toDoubleOrNull()
            val lng = parts[1].toDoubleOrNull()
            if (lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0) {
                return Result.success(lat to lng)
            }
        }
        val matches = geospatialRepo.searchDestinations(input, limit = 1)
            .getOrElse { return Result.failure(it) }
        val first = matches.firstOrNull()
            ?: return Result.failure(IllegalStateException("Could not find coordinates for \"$input\"."))
        return Result.success(first.latitude to first.longitude)
    }

    private fun errorToolOutput(toolName: String, message: String): ToolExecOutput {
        return ToolExecOutput(
            json = buildJsonObject {
                put("status", "error")
                put("error", message)
            },
            trace = ToolExecutionTrace(toolName = toolName, summary = message.take(64), succeeded = false)
        )
    }

    suspend fun generateStructuredItinerary(
        destination: String,
        startDate: String,
        endDate: String,
        travellers: Int,
        style: String,
        budgetUsd: Double,
        transport: String,
        lodgingType: String,
        activities: List<String>
    ): Result<GeneratedTripItinerary> = try {
        val destCoord = geospatialRepo.searchDestinations(destination, limit = 1)
            .getOrNull()?.firstOrNull()

        val prompt = """
            Design a refined $style itinerary for $travellers traveller(s) visiting $destination
            from $startDate to $endDate with a target budget of $${budgetUsd.toInt()} USD.
            Preferred transport: $transport. Preferred lodging: $lodgingType.
            Focus interests: ${activities.joinToString(", ").ifBlank { "Culture, Dining, Landmarks" }}.
            Return 5 to 6 crisp chronological itinerary stops across days 1 to 3 with realistic estimated costs and practical logistics notes.
        """.trimIndent()

        val response = withTimeout(60_000L) {
            itineraryModel.generateContent(prompt)
        }
        val rawJson = response.text ?: throw IllegalStateException("Gemini returned empty JSON.")
        val json = JSONObject(rawJson)
        val title = json.optString("tripTitle", "$destination Curated Escape")
        val summary = json.optString("summary", "Tailored itinerary generated via ALLORA AI.")
        val total = json.optDouble("estimatedTotalUsd", budgetUsd)
        val daysArr = json.getJSONArray("days")
        val slots = (0 until daysArr.length()).map { idx ->
            val obj = daysArr.getJSONObject(idx)
            ItinerarySlot(
                id = "slot_${UUID.randomUUID().toString().replace("-", "").take(10)}",
                timeRange = obj.optString("timeRange", "09:00 AM - 11:00 AM"),
                title = obj.optString("title", "Curated Experience"),
                location = obj.optString("location", destination),
                type = obj.optString("category", "Sights"),
                durationMinutes = obj.optInt("durationMinutes", 90).coerceIn(15, 720),
                routeSequence = idx + 1,
                calSyncStatus = "AI Generated • Cloud Ready",
                dayNumber = obj.optInt("dayNumber", 1).coerceIn(1, 30),
                latitude = destCoord?.latitude,
                longitude = destCoord?.longitude,
                notes = obj.optString("notes", ""),
                estimatedCostUsd = obj.optDouble("estimatedCostUsd", 0.0).coerceAtLeast(0.0)
            )
        }
        Result.success(GeneratedTripItinerary(title, summary, total, slots))
    } catch (e: TimeoutCancellationException) {
        Log.e(TAG, "Itinerary generation timed out", e)
        Result.failure(IllegalStateException("Itinerary generation timed out after 60 seconds.", e))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "Itinerary generation failed", e)
        Result.failure(e)
    }

    private fun humanReadableToolLabel(toolName: String): String = when (toolName) {
        TOOL_GET_WEATHER -> "Live Weather (Open-Meteo)"
        TOOL_SEARCH_DESTINATIONS -> "Destination Search (OSM)"
        TOOL_SEARCH_NEARBY_PLACES -> "Nearby Places (Overpass)"
        TOOL_GET_ROUTE -> "Route Engine (OSRM)"
        TOOL_CALCULATE_BUDGET -> "Budget Calculator"
        TOOL_CREATE_ITINERARY -> "Itinerary Planner"
        else -> toolName
    }

    private fun suggestFollowUpChips(traces: List<ToolExecutionTrace>): List<String> {
        val names = traces.map { it.toolName }.toSet()
        return buildList {
            if (TOOL_GET_WEATHER !in names) add("Check forecast")
            if (TOOL_SEARCH_NEARBY_PLACES !in names) add("Find nearby dining & sights")
            if (TOOL_CALCULATE_BUDGET !in names) add("Estimate trip budget")
            if (TOOL_CREATE_ITINERARY !in names) add("Build 4-day itinerary")
        }.take(3)
    }

    private fun Map<String, JsonElement>.str(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun Map<String, JsonElement>.int(key: String): Int? =
        this[key]?.jsonPrimitive?.intOrNull ?: this[key]?.jsonPrimitive?.doubleOrNull?.toInt()

    private fun Map<String, JsonElement>.double(key: String): Double? =
        this[key]?.jsonPrimitive?.doubleOrNull

    private fun Map<String, JsonElement>.bool(key: String): Boolean? =
        this[key]?.jsonPrimitive?.booleanOrNull

    companion object {
        private const val TAG = "AlloraAiService"
        private const val DEFAULT_MODEL = "gemini-3.8-flash"
        private const val MAX_TOOL_ROUNDS = 5

        const val TOOL_GET_WEATHER = "get_weather"
        const val TOOL_SEARCH_DESTINATIONS = "search_destinations"
        const val TOOL_SEARCH_NEARBY_PLACES = "search_nearby_places"
        const val TOOL_GET_ROUTE = "get_route"
        const val TOOL_CALCULATE_BUDGET = "calculate_trip_budget"
        const val TOOL_CREATE_ITINERARY = "create_itinerary"
    }
}
