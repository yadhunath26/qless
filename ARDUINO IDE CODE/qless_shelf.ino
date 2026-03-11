#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <HTTPClient.h>
#include <SPI.h>
#include <MFRC522.h>
#include <Preferences.h>

/* =================================================
   QLESS SMART SHELF v4.0
   - Shelf ESP32 writes to /shelfEvents + /inventory ONLY
   - /cartEvents is written ONLY by the cart ESP32
   - Theft detection is handled by the Android app
     by comparing shelfEvents vs cartEvents
   ================================================= */

/* ================= WIFI ================= */
const char* WIFI_SSID = "FTTH-3F1B";
const char* WIFI_PASS = "12345678";

/* =============== FIREBASE =============== */
const char* FIREBASE_HOST = "https://qless-be82a-default-rtdb.firebaseio.com";

/* =============== RFID PINS (XIAO ESP32-C3) =============== */
#define SS_PIN    20
#define RST_PIN   6
#define SCK_PIN   8
#define MISO_PIN  9
#define MOSI_PIN  10

#define SHELF_NUMBER "Shelf 1"

MFRC522 rfid(SS_PIN, RST_PIN);
Preferences prefs;
WiFiClientSecure client;

/* =============== ITEM STRUCT =============== */
struct Item {
  String name;
  String uid;
  int    stock;
  int    price;
  bool   picked;
};

/* =============== INVENTORY ===============
   ⚠️ Update UIDs to match your actual RFID tags */
Item items[] = {
  { "Bread",  "2BFCF105", 10, 45,  false },
  { "Eggs",   "F753F105", 12, 80,  false },
  { "Milk",   "95273BCA", 8,  40,  false },
  { "Butter", "FA1156D0", 5,  55,  false },
  { "Jam",    "B51B2207", 6,  90,  false }
};
const int ITEM_COUNT = sizeof(items) / sizeof(items[0]);

/* =============== READ UID =============== */
String readUID() {
  String uid = "";
  for (byte i = 0; i < rfid.uid.size; i++) {
    if (rfid.uid.uidByte[i] < 0x10) uid += "0";
    uid += String(rfid.uid.uidByte[i], HEX);
  }
  uid.toUpperCase();
  return uid;
}

/* =============== FIREBASE HELPERS =============== */
void firebasePUT(const String& path, const String& json) {
  if (WiFi.status() != WL_CONNECTED) return;
  HTTPClient https;
  https.begin(client, String(FIREBASE_HOST) + path + ".json");
  https.addHeader("Content-Type", "application/json");
  int code = https.PUT(json);
  Serial.printf("[PUT] %s -> HTTP %d\n", path.c_str(), code);
  https.end();
}

void firebasePOST(const String& path, const String& json) {
  if (WiFi.status() != WL_CONNECTED) return;
  HTTPClient https;
  https.begin(client, String(FIREBASE_HOST) + path + ".json");
  https.addHeader("Content-Type", "application/json");
  int code = https.POST(json);
  Serial.printf("[POST] %s -> HTTP %d\n", path.c_str(), code);
  https.end();
}

/* =============== DATA PERSISTENCE =============== */
void saveInventory() {
  prefs.begin("shelf", false);
  for (int i = 0; i < ITEM_COUNT; i++) {
    prefs.putInt(items[i].name.c_str(), items[i].stock);
    prefs.putBool(("p_" + items[i].name).c_str(), items[i].picked);
  }
  prefs.end();
}

void loadInventory() {
  prefs.begin("shelf", true);
  for (int i = 0; i < ITEM_COUNT; i++) {
    items[i].stock  = prefs.getInt(items[i].name.c_str(), items[i].stock);
    items[i].picked = prefs.getBool(("p_" + items[i].name).c_str(), false);
  }
  prefs.end();
}

void pushFullInventoryToFirebase() {
  Serial.println("Syncing inventory to Firebase...");
  for (int i = 0; i < ITEM_COUNT; i++) {
    String json = "{\"stock\":"  + String(items[i].stock)
                + ",\"price\":" + String(items[i].price)
                + ",\"name\":\"" + items[i].name + "\"}";
    firebasePUT("/inventory/" + items[i].name, json);
  }
  Serial.println("Inventory synced.");
}

/* =============== SETUP =============== */
void setup() {
  Serial.begin(115200);
  delay(1000);
  Serial.println("\n--- QLESS SMART SHELF v4.0 ---");

  SPI.begin(SCK_PIN, MISO_PIN, MOSI_PIN, SS_PIN);
  rfid.PCD_Init();

  byte v = rfid.PCD_ReadRegister(rfid.VersionReg);
  Serial.printf("MFRC522 Version: 0x%X\n", v);
  if (v == 0x00 || v == 0xFF) {
    Serial.println("RFID not responding! Check wiring.");
  } else {
    Serial.println("RFID OK.");
  }

  client.setInsecure();

  Serial.print("Connecting to WiFi");
  WiFi.begin(WIFI_SSID, WIFI_PASS);
  int attempts = 0;
  while (WiFi.status() != WL_CONNECTED && attempts < 30) {
    delay(500);
    Serial.print(".");
    attempts++;
  }

  if (WiFi.status() == WL_CONNECTED) {
    Serial.println("\nWiFi connected: " + WiFi.localIP().toString());
  } else {
    Serial.println("\nWiFi failed. Running offline.");
  }

  loadInventory();
  pushFullInventoryToFirebase();
  Serial.println("Ready. Scan an RFID tag...");
}

/* =============== LOOP =============== */
void loop() {

  // ── WiFi watchdog ──
  if (WiFi.status() != WL_CONNECTED) {
    WiFi.reconnect();
    delay(2000);
    return;
  }

  // ── Wait for RFID tag ──
  if (!rfid.PICC_IsNewCardPresent() || !rfid.PICC_ReadCardSerial()) {
    delay(50);
    return;
  }

  String uid = readUID();
  Serial.println("\nScanned UID: " + uid);

  bool found = false;

  for (int i = 0; i < ITEM_COUNT; i++) {
    if (uid == items[i].uid) {
      found = true;
      String action = "";

      if (!items[i].picked) {
        // ── Item picked from shelf ──
        if (items[i].stock > 0) {
          items[i].stock--;
          items[i].picked = true;
          action = "PICKUP";
          Serial.printf("PICKUP: %s | Stock left: %d\n",
                        items[i].name.c_str(), items[i].stock);
        } else {
          Serial.println("OUT OF STOCK: " + items[i].name);
          break;
        }
      } else {
        // ── Item returned to shelf ──
        items[i].stock++;
        items[i].picked = false;
        action = "PUTBACK";
        Serial.printf("PUTBACK: %s | Stock: %d\n",
                      items[i].name.c_str(), items[i].stock);
      }

      saveInventory();

      // ── Update /inventory ──
      String invJson = "{\"stock\":"   + String(items[i].stock)
                     + ",\"price\":"  + String(items[i].price)
                     + ",\"name\":\"" + items[i].name + "\"}";
      firebasePUT("/inventory/" + items[i].name, invJson);

      // ── Write to /shelfEvents ONLY ──
      // ✅ cartEvents is NOT written here — that is the cart ESP32's job only
      String shelfJson = "{\"item\":\""  + items[i].name + "\""
                       + ",\"action\":\"" + action + "\""
                       + ",\"shelf\":\""  + SHELF_NUMBER + "\""
                       + ",\"stock\":"   + String(items[i].stock)
                       + ",\"price\":"   + String(items[i].price)
                       + ",\"time\":{\".sv\":\"timestamp\"}}";
      firebasePOST("/shelfEvents", shelfJson);

      Serial.println("Firebase updated for: " + items[i].name);
      break;
    }
  }

  if (!found) {
    Serial.println("Unknown UID: " + uid);
  }

  rfid.PICC_HaltA();
  rfid.PCD_StopCrypto1();
  rfid.PCD_Init();  // reset reader to prevent stuck state
  delay(500);
}
