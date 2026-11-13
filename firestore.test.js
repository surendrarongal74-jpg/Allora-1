const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";
const VALID_SHA256 = "a1b2c3d4e5f60718293a4b5c6d7e8f90123456789abcdef0123456789abcdef0";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function pastTimestamp(offsetMs = 2000) {
  return new Date(Date.now() - offsetMs);
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user cannot read or write any user profile, trips, bookings, or AI messages", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("trips").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("bookings").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("saved_items").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("ai_messages").get());
});

test("Authenticated owner can create, update, and read their own profile", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const createdAt = pastTimestamp(5000);
  const profileRef = aliceDb.collection("users").doc(ALICE_UID);

  await assertSucceeds(
    profileRef.set({
      userId: ALICE_UID,
      displayName: "Alice Traveler",
      preferredCurrency: "USD",
      temperatureUnit: "METRIC",
      preferredCabinClass: "BUSINESS",
      homeAirportIata: "SFO",
      biometricThresholdUsd: 500,
      darkGlassTheme: true,
      createdAt,
      updatedAt: createdAt,
    })
  );
  await assertSucceeds(profileRef.get());
  await assertSucceeds(
    profileRef.update({
      displayName: "Alice Updated",
      updatedAt: pastTimestamp(1000),
    })
  );
});

test("Authenticated owner can create trips, manage itinerary slots, and reject orphan slots", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const createdAt = pastTimestamp(5000);

  // Writing slot without parent trip must fail
  const orphanSlotRef = aliceDb
    .collection("users").doc(ALICE_UID)
    .collection("trips").doc("missing_trip")
    .collection("itinerary_slots").doc("slot_orphan");
  await assertFails(
    orphanSlotRef.set({
      userId: ALICE_UID,
      tripId: "missing_trip",
      dayNumber: 1,
      timeRange: "09:00 AM",
      title: "Meiji Jingu",
      location: "Shibuya",
      category: "Sights",
      durationMinutes: 90,
      routeSequence: 1,
      notes: "",
      estimatedCostUsd: 0,
      calSyncStatus: "Cloud Synced",
      createdAt,
      updatedAt: createdAt,
    })
  );

  // Create trip first
  const tripRef = aliceDb.collection("users").doc(ALICE_UID).collection("trips").doc("trip_1");
  await assertSucceeds(
    tripRef.set({
      userId: ALICE_UID,
      title: "Tokyo Serenity",
      destination: "Tokyo, Japan",
      startDate: "2026-12-10",
      endDate: "2026-12-16",
      travellersCount: 2,
      budgetUsd: 4200,
      style: "Luxury & Wellness",
      status: "PLANNED",
      createdAt,
      updatedAt: createdAt,
    })
  );

  const slotRef = tripRef.collection("itinerary_slots").doc("slot_1");
  await assertSucceeds(
    slotRef.set({
      userId: ALICE_UID,
      tripId: "trip_1",
      dayNumber: 1,
      timeRange: "09:00 AM",
      title: "Meiji Jingu Shrine",
      location: "Shibuya, Tokyo",
      latitude: 35.6764,
      longitude: 139.6993,
      category: "Sights",
      durationMinutes: 90,
      routeSequence: 1,
      notes: "Arrive early",
      estimatedCostUsd: 0,
      calSyncStatus: "Cloud Synced",
      createdAt,
      updatedAt: createdAt,
    })
  );
  await assertSucceeds(
    slotRef.update({
      notes: "Confirmed early morning walk",
      updatedAt: pastTimestamp(1000),
    })
  );
  await assertSucceeds(slotRef.delete());

  // Cross-user cannot read or create slots under Alice's trip
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("trips").doc("trip_1").collection("itinerary_slots").get()
  );
});

test("Client cannot create CONFIRMED booking or fabricate providerPnr, and terminal CANCELLED lock applies", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const ts = pastTimestamp(5000);
  const bookingsCol = aliceDb.collection("users").doc(ALICE_UID).collection("bookings");

  // Reject client-side CONFIRMED creation
  await assertFails(
    bookingsCol.doc("bk_fake_confirm").set({
      userId: ALICE_UID,
      referenceCode: "ALR-999-FLT",
      bookingType: "FLIGHT",
      title: "SFO to HND",
      providerSource: "Amadeus Sample",
      providerPnr: null,
      originOrLocation: "SFO",
      totalAmountUsd: 920,
      status: "CONFIRMED",
      sha256AuditHash: VALID_SHA256,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Reject client supplying non-null providerPnr
  await assertFails(
    bookingsCol.doc("bk_fake_pnr").set({
      userId: ALICE_UID,
      referenceCode: "ALR-998-FLT",
      bookingType: "FLIGHT",
      title: "SFO to HND",
      providerSource: "Amadeus Sample",
      providerPnr: "FAKEPNR9",
      originOrLocation: "SFO",
      totalAmountUsd: 920,
      status: "HELD",
      sha256AuditHash: VALID_SHA256,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Allow HELD creation, transition HELD -> USER_APPROVED -> CANCELLED, deny transition to CONFIRMED
  const holdRef = bookingsCol.doc("bk_hold_1");
  await assertSucceeds(
    holdRef.set({
      userId: ALICE_UID,
      referenceCode: "ALR-123-CAR",
      bookingType: "CAR_RENTAL",
      title: "Porsche Taycan",
      providerSource: "Sample Catalog",
      providerPnr: null,
      originOrLocation: "Tokyo Haneda",
      totalAmountUsd: 645,
      status: "HELD",
      sha256AuditHash: VALID_SHA256,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertFails(holdRef.update({ status: "CONFIRMED", updatedAt: pastTimestamp(1500) }));
  await assertSucceeds(holdRef.update({ status: "USER_APPROVED", updatedAt: pastTimestamp(1400) }));
  await assertSucceeds(holdRef.update({ status: "CANCELLED", updatedAt: pastTimestamp(1200) }));

  // Cancelled booking is locked & cannot be deleted
  await assertFails(holdRef.update({ status: "HELD", updatedAt: pastTimestamp(1000) }));
  await assertFails(holdRef.delete());
});

test("AI messages allow owner create/read/delete and forbid update or cross-user access", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const ts = pastTimestamp(3000);
  const msgRef = aliceDb.collection("users").doc(ALICE_UID).collection("ai_messages").doc("msg_1");

  await assertSucceeds(
    msgRef.set({
      userId: ALICE_UID,
      isUser: true,
      text: "Check Tokyo weather",
      toolTraceSummary: null,
      isError: false,
      createdAt: ts,
    })
  );
  await assertSucceeds(msgRef.get());
  await assertFails(msgRef.update({ text: "Tampered text" }));
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("ai_messages").doc("msg_1").get());
  await assertSucceeds(msgRef.delete());
});

test("Cross-user access to trips and bookings is rejected", async () => {
  const createdAt = pastTimestamp(5000);
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("users").doc(BOB_UID).collection("bookings").doc("bk_1").set({
      userId: BOB_UID,
      referenceCode: "ALR-999-FLT",
      bookingType: "FLIGHT",
      title: "SFO to NRT",
      providerSource: "Amadeus GDS",
      originOrLocation: "SFO",
      totalAmountUsd: 920,
      status: "REQUESTED",
      sha256AuditHash: VALID_SHA256,
      createdAt,
      updatedAt: createdAt,
    });
  });

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertFails(aliceDb.collection("users").doc(BOB_UID).collection("bookings").get());
  await assertFails(aliceDb.collection("users").doc(BOB_UID).collection("bookings").doc("bk_1").get());
});

test("Shadow update with ghost field on UserProfile is rejected", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const ts = pastTimestamp(5000);
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "Alice",
      preferredCurrency: "USD",
      temperatureUnit: "METRIC",
      preferredCabinClass: "ECONOMY",
      homeAirportIata: "SFO",
      biometricThresholdUsd: 250,
      darkGlassTheme: true,
      isAdmin: true,
      createdAt: ts,
      updatedAt: ts,
    })
  );
});
