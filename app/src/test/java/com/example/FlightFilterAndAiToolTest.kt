package com.example

import com.example.data.ai.AlloraAiService
import com.example.data.model.FlightOffer
import com.example.data.remote.api.GeospatialHttpClientFactory
import com.example.data.remote.api.NominatimApi
import com.example.data.remote.api.OpenMeteoForecastApi
import com.example.data.remote.api.OpenMeteoGeocodingApi
import com.example.data.remote.api.OsrmApi
import com.example.data.remote.api.OverpassApi
import com.example.data.repository.GeospatialRepository
import com.example.data.repository.WeatherRepository
import com.example.ui.screens.filterFlightOffers
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class FlightFilterAndAiToolTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun buildAiService(): AlloraAiService {
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val baseUrl = server.url("/").toString()
        val okHttp = GeospatialHttpClientFactory.buildOkHttpClient("ALLORA-Test/1.0")
        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttp)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

        val weatherRepo = WeatherRepository(
            geocodingApi = retrofit.create(OpenMeteoGeocodingApi::class.java),
            forecastApi = retrofit.create(OpenMeteoForecastApi::class.java),
            cacheTtlMs = 0L
        )
        val geoRepo = GeospatialRepository(
            nominatimApi = retrofit.create(NominatimApi::class.java),
            overpassApi = retrofit.create(OverpassApi::class.java),
            osrmApi = retrofit.create(OsrmApi::class.java),
            minNominatimIntervalMs = 0L,
            cacheTtlMs = 0L
        )
        return AlloraAiService(weatherRepo = weatherRepo, geospatialRepo = geoRepo, firestoreRepo = null)
    }

    @Test
    fun `filterFlightOffers filters accurately by swapped origin destination cabin and sort`() {
        val flights = listOf(
            FlightOffer("1", "ANA", "NH008", "SFO", "HND", "San Francisco", "Tokyo", "11:00", "15:20", "11h20m", 1180.0, "Business", 0, "Sample"),
            FlightOffer("2", "ANA", "NH007", "HND", "SFO", "Tokyo", "San Francisco", "17:00", "09:40", "9h40m", 950.0, "Business", 0, "Sample"),
            FlightOffer("3", "Singapore Airlines", "SQ001", "SFO", "SIN", "San Francisco", "Singapore", "23:00", "06:30", "16h30m", 790.0, "Economy", 0, "Sample")
        )

        val forward = filterFlightOffers(
            flights = flights,
            originFilter = "SFO",
            destinationFilter = "HND",
            query = "",
            cabinFilter = "Business",
            sortLowFirst = true
        )
        assertEquals(listOf("NH008"), forward.map { it.flightNumber })

        // Swap origin & destination
        val swapped = filterFlightOffers(
            flights = flights,
            originFilter = "Tokyo (HND)",
            destinationFilter = "San Francisco (SFO)",
            query = "",
            cabinFilter = "All",
            sortLowFirst = true
        )
        assertEquals(listOf("NH007"), swapped.map { it.flightNumber })
    }

    @Test
    fun `ai service tool execution handles real weather budget and error propagation`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"results":[{"name":"Paris","latitude":48.8566,"longitude":2.3522,"country":"France"}]}"""
            )
        )
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "latitude": 48.8566,
                  "longitude": 2.3522,
                  "timezone": "Europe/Paris",
                  "timezone_abbreviation": "CET",
                  "current": {
                    "temperature_2m": 12.0,
                    "apparent_temperature": 10.5,
                    "relative_humidity_2m": 71,
                    "precipitation": 0.0,
                    "weather_code": 3,
                    "wind_speed_10m": 11.0,
                    "wind_direction_10m": 180,
                    "uv_index": 2.0
                  }
                }
                """.trimIndent()
            )
        )

        val aiService = buildAiService()
        val weatherOut = aiService.executeToolCall(
            name = "get_weather",
            args = mapOf("destination" to JsonPrimitive("Paris"), "days" to JsonPrimitive(3))
        )
        assertTrue(weatherOut.trace.succeeded)
        assertEquals("ok", weatherOut.json["status"]?.jsonPrimitive?.content)
        assertEquals(12.0, weatherOut.json["temp_celsius"]?.jsonPrimitive?.doubleOrNull ?: 0.0, 0.01)

        // Budget calculation tool
        val budgetOut = aiService.executeToolCall(
            name = "calculate_trip_budget",
            args = mapOf(
                "destination" to JsonPrimitive("Paris"),
                "days" to JsonPrimitive(4),
                "travellers" to JsonPrimitive(2),
                "flight_usd" to JsonPrimitive(650.0),
                "hotel_night_usd" to JsonPrimitive(300.0),
                "daily_food_activity_usd" to JsonPrimitive(140.0)
            )
        )
        assertTrue(budgetOut.trace.succeeded)
        assertEquals("ok", budgetOut.json["status"]?.jsonPrimitive?.content)
        val breakdown = budgetOut.json["breakdown_usd"] as kotlinx.serialization.json.JsonObject
        val estimatedTotal = breakdown["total_estimated_usd"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        assertTrue(estimatedTotal > 3000.0)

        // Failed HTTP call reports failure cleanly instead of returning fabricated data
        server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))
        val failedWeather = aiService.executeToolCall(
            name = "get_weather",
            args = mapOf("destination" to JsonPrimitive("UnknownPlace"))
        )
        assertFalse(failedWeather.trace.succeeded)
        assertEquals("error", failedWeather.json["status"]?.jsonPrimitive?.content)
    }
}
