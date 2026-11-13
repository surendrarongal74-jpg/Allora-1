package com.example

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AlloraDatabase
import com.example.data.local.entity.TravelPhotoRecord
import com.example.data.model.GeoCoordinate
import com.example.data.model.PointOfInterest
import com.example.location.LocationTrackingState
import com.example.location.TravelLocationTracker
import com.example.ui.screens.FullScreenMapContent
import com.example.ui.screens.TravelCameraContent
import com.example.ui.theme.AlloraTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h892dp")
class TravelLocationCameraAndMapTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `travel location tracker updates state and clears coordinates on sign out`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tracker = TravelLocationTracker.getInstance(context)

        val tokyo = GeoCoordinate(35.6762, 139.6503)
        tracker.publishSyntheticFixForTest(tokyo, accuracyMeters = 6.5f, isTracking = true)

        val active = tracker.state.value
        assertTrue(active.isTracking)
        assertEquals(tokyo, active.currentLocation)
        assertEquals(6.5f, active.accuracyMeters)

        tracker.stopUpdates(clearLastFix = true)
        val cleared = tracker.state.value
        assertFalse(cleared.isTracking)
        assertNull(cleared.currentLocation)
        assertNull(cleared.accuracyMeters)
    }

    @Test
    fun `travel photo dao enforces strict per user isolation`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AlloraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.travelPhotoDao()
            val alicePhoto = TravelPhotoRecord(
                id = "p_alice_1",
                userId = "uid_alice",
                filePath = "/tmp/alice_1.jpg",
                caption = "Tokyo International Forum Atrium",
                category = "Architecture",
                destinationName = "Tokyo, Japan",
                latitude = 35.6769,
                longitude = 139.7635
            )
            val bobPhoto = TravelPhotoRecord(
                id = "p_bob_1",
                userId = "uid_bob",
                filePath = "/tmp/bob_1.jpg",
                caption = "Louvre Pyramid Dusk",
                category = "Landmark",
                destinationName = "Paris, France",
                latitude = 48.8610,
                longitude = 2.3359
            )
            dao.insertPhoto(alicePhoto)
            dao.insertPhoto(bobPhoto)

            val aliceList = dao.observePhotosForUser("uid_alice").first()
            val bobList = dao.observePhotosForUser("uid_bob").first()

            assertEquals(1, aliceList.size)
            assertEquals("Tokyo International Forum Atrium", aliceList.first().caption)
            assertEquals(1, bobList.size)
            assertEquals("Louvre Pyramid Dusk", bobList.first().caption)

            dao.deletePhotoForUser("uid_bob", "p_alice_1")
            assertEquals(1, dao.observePhotosForUser("uid_alice").first().size)

            dao.deletePhotoForUser("uid_alice", "p_alice_1")
            assertTrue(dao.observePhotosForUser("uid_alice").first().isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun `database migration 3 to 4 creates travel_photos table cleanly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = context.getDatabasePath("migration_test_3_4.db")
        dbFile.delete()
        try {
            val helper = FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration.builder(context)
                    .name(dbFile.name)
                    .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL("CREATE TABLE IF NOT EXISTS user_preferences (user_id TEXT PRIMARY KEY NOT NULL)")
                        }
                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    })
                    .build()
            )
            val v3Db = helper.writableDatabase
            AlloraDatabase.MIGRATION_3_4.migrate(v3Db)
            v3Db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='travel_photos'").use { cursor ->
                assertTrue(cursor.moveToFirst())
            }
            v3Db.close()
        } finally {
            dbFile.delete()
        }
    }

    @Test
    fun `camera screen shows rationale card when CAMERA permission is denied`() {
        composeRule.setContent {
            AlloraTheme(darkTheme = false) {
                TravelCameraContent(
                    currentUserId = "uid_alice",
                    photos = emptyList(),
                    destinationLabel = "Kyoto, Japan",
                    latitude = null,
                    longitude = null,
                    onSavePhoto = { _, _, _ -> },
                    onDeletePhoto = {},
                    onBack = {}
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("camera_permission_card").assertIsDisplayed()
        composeRule.onNodeWithTag("camera_grant_permission_btn").assertIsDisplayed()
    }

    @Test
    fun `camera screen captures architectural photo and toggles grid when CAMERA permission granted`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        shadowOf(app).grantPermissions(android.Manifest.permission.CAMERA)

        var savedCaption = ""
        var savedCategory = ""
        var savedPath = ""

        composeRule.setContent {
            AlloraTheme(darkTheme = false) {
                TravelCameraContent(
                    currentUserId = "uid_alice",
                    photos = emptyList(),
                    destinationLabel = "Tokyo, Japan",
                    latitude = 35.6762,
                    longitude = 139.6503,
                    onSavePhoto = { path, caption, category ->
                        savedPath = path
                        savedCaption = caption
                        savedCategory = category
                    },
                    onDeletePhoto = {},
                    onBack = {}
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("camera_preview_surface").assertIsDisplayed()
        composeRule.onNodeWithTag("camera_architecture_grid").assertIsDisplayed()

        composeRule.onNodeWithText("Landmark").performClick()
        composeRule.onNodeWithTag("camera_shutter_button").performClick()
        composeRule.waitForIdle()

        assertTrue(savedPath.isNotBlank())
        assertEquals("Landmark", savedCategory)
        assertEquals("", savedCaption)
        File(savedPath).delete()
    }

    @Test
    fun `google map screen toggles live tracking, switches layers, and opens field camera`() {
        var trackingActive by mutableStateOf(false)
        var openedCamera = false
        var addedPoiTitle = ""

        val samplePois = listOf(
            PointOfInterest(
                id = "poi-1",
                name = "Tokyo National Museum",
                category = "Attraction",
                rating = 4.9,
                reviewCount = 420,
                address = "Ueno Park, Tokyo",
                lat = 35.7188,
                lng = 139.7765,
                tag = "Museum",
                distanceKm = 2.4,
                providerSource = "OpenStreetMap Overpass"
            )
        )

        composeRule.setContent {
            AlloraTheme(darkTheme = false) {
                FullScreenMapContent(
                    pois = samplePois,
                    searchResults = emptyList(),
                    mapCenter = GeoCoordinate(35.6762, 139.6503),
                    userLocation = GeoCoordinate(35.6762, 139.6503),
                    activeRoute = null,
                    mapLoading = false,
                    mapError = null,
                    locationPermissionDenied = false,
                    trackingState = LocationTrackingState(
                        isTracking = trackingActive,
                        accuracyMeters = if (trackingActive) 7.0f else null
                    ),
                    onBack = {},
                    onSearchDestinations = {},
                    onSelectDestination = { _, _ -> },
                    onRefreshPois = {},
                    onComputeRoute = { _, _ -> },
                    onClearRoute = {},
                    onClearError = {},
                    onRequestMyLocation = {},
                    onToggleTracking = { trackingActive = !trackingActive },
                    onAddStop = { addedPoiTitle = it.name },
                    onOpenCamera = { openedCamera = true }
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("map_preview_surface").assertIsDisplayed()
        composeRule.onNodeWithText("Track Live").performClick()
        composeRule.waitForIdle()
        assertTrue(trackingActive)
        composeRule.onNodeWithText("Live GPS On ±7m").assertIsDisplayed()

        composeRule.onNodeWithText("Map").performClick()
        composeRule.onNodeWithText("Terrain").assertIsDisplayed()

        composeRule.onNodeWithTag("map_camera_chip").performClick()
        assertTrue(openedCamera)

        composeRule.onNodeWithText("Add Stop").performClick()
        assertEquals("Tokyo National Museum", addedPoiTitle)
    }
}
