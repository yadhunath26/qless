#include <SPI.h>
#include <MFRC522.h>
#include <Preferences.h>
#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

/* =================================================
   QLESS SMART CART v2.0
   - Add new items by adding ONE line to items[]
   - Sends ITEM/TOTAL/MODE to Android app via BLE
   - OLED shows live cart + total
   - Preferences saves cart across reboots
   ================================================= */

/* ──────────── OLED ──────────── */
#define SCREEN_WIDTH  128
#define SCREEN_HEIGHT 64
Adafruit_SSD1306 display(SCREEN_WIDTH, SCREEN_HEIGHT, &Wire, -1);

/* ──────────── RFID ──────────── */
#define SS_PIN  5
#define RST_PIN 22
MFRC522 rfid(SS_PIN, RST_PIN);

/* ──────────── BLE ──────────── */
BLECharacteristic* pCharacteristic;
bool deviceConnected = false;
Preferences prefs;
#define SERVICE_UUID        "12345678-1234-1234-1234-1234567890ab"
#define CHARACTERISTIC_UUID "abcd1234-5678-90ab-cdef-1234567890ab"

/* ──────────── MODE CARD ──────────── */
// Scan any spare RFID tag and paste its UID here
// Tapping this card toggles ADD ↔ REMOVE mode
#define MODE_CARD_UID "09055B06"

/* =================================================
   ✅ TO ADD A NEW ITEM — just add ONE line here:
   { "Name", "RFID_UID", price }

   How to find RFID UID:
   - Open Serial Monitor at 115200
   - Scan the tag
   - Copy the UID printed as "Unknown tag: XXXXXXXX"
   ================================================= */
struct CartItem {
  String name;
  String uid;
  int    price;
  int    qty;
};

CartItem items[] = {
  { "Bread",  "2BFCF105", 45,  0 },
  { "Eggs",   "F753F105", 60,  0 },
  { "Milk",   "95273BCA", 40,  0 },
  { "Butter", "FA1156D0", 55,  0 },
  { "Jam",    "B51B2207", 90,  0 },
  // ── Add more items below this line ──
  // { "Rice",   "AABBCCDD", 120, 0 },
  // { "Oil",    "11223344", 95,  0 },
};
const int ITEM_COUNT = sizeof(items) / sizeof(items[0]);

bool addMode = true;

/* ──────────── HELPERS ──────────── */

String readUID() {
  String uid = "";
  for (byte i = 0; i < rfid.uid.size; i++) {
    if (rfid.uid.uidByte[i] < 0x10) uid += "0";
    uid += String(rfid.uid.uidByte[i], HEX);
  }
  uid.toUpperCase();
  return uid;
}

void sendBLE(String msg) {
  if (deviceConnected) {
    pCharacteristic->setValue(msg.c_str());
    pCharacteristic->notify();
    delay(20);
  }
}

/* ──────────── CALCULATE TOTAL ──────────── */
int calcTotal() {
  int t = 0;
  for (int i = 0; i < ITEM_COUNT; i++) {
    t += items[i].price * items[i].qty;
  }
  return t;
}

/* ──────────── OLED ──────────── */
// Scrolls through items if more than 3
void updateOLED() {
  display.clearDisplay();
  display.setTextSize(1);
  display.setTextColor(SSD1306_WHITE);

  // Header
  display.setCursor(0, 0);
  display.print("QLess  Mode:");
  display.println(addMode ? "ADD" : "REMOVE");

  // Divider
  display.drawLine(0, 10, 127, 10, SSD1306_WHITE);

  // Show up to 3 items with qty > 0, then total
  int row = 0;
  int yPos = 14;
  for (int i = 0; i < ITEM_COUNT && row < 3; i++) {
    if (items[i].qty > 0) {
      display.setCursor(0, yPos);
      // Truncate name to 8 chars to fit screen
      String name = items[i].name.substring(0, min((int)items[i].name.length(), 8));
      display.print(name);
      display.print(": ");
      display.print(items[i].qty);
      display.print(" Rs");
      display.println(items[i].price * items[i].qty);
      yPos += 12;
      row++;
    }
  }

  if (row == 0) {
    display.setCursor(0, 20);
    display.println("  Cart is empty");
    display.setCursor(0, 32);
    display.println("  Scan an item!");
  }

  // Total at bottom
  display.drawLine(0, 53, 127, 53, SSD1306_WHITE);
  display.setCursor(0, 56);
  display.print("TOTAL: Rs ");
  display.println(calcTotal());

  display.display();
}

/* ──────────── BLE SEND FULL CART ──────────── */
void sendFullCartBLE() {
  // Send mode
  sendBLE("MODE," + String(addMode ? "ADD" : "REMOVE"));

  // Send each item (qty 0 items sent too so app can remove them)
  for (int i = 0; i < ITEM_COUNT; i++) {
    String msg = "ITEM," + items[i].name
               + "," + String(items[i].qty)
               + "," + String(items[i].price);
    sendBLE(msg);
  }

  // Send total
  sendBLE("TOTAL," + String(calcTotal()));
}

/* ──────────── CLEAR CART (called on CLEAR command) ──────────── */
void clearCart() {
  for (int i = 0; i < ITEM_COUNT; i++) {
    items[i].qty = 0;
  }
  addMode = true;
  saveCart();
  updateOLED();
  sendFullCartBLE();
  Serial.println("Cart cleared!");
}

/* ──────────── PREFERENCES (flash memory) ──────────── */
void saveCart() {
  prefs.begin("cart", false);
  for (int i = 0; i < ITEM_COUNT; i++) {
    prefs.putInt(items[i].name.c_str(), items[i].qty);
  }
  prefs.putBool("mode", addMode);
  prefs.end();
}

void loadCart() {
  prefs.begin("cart", true);
  for (int i = 0; i < ITEM_COUNT; i++) {
    items[i].qty = prefs.getInt(items[i].name.c_str(), 0);
  }
  addMode = prefs.getBool("mode", true);
  prefs.end();
}


/* ──────────── BLE CALLBACKS ──────────── */
class MyServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer* pServer) {
    deviceConnected = true;
    Serial.println("Android connected");
    sendFullCartBLE();  // Send current cart state on connect
  }
  void onDisconnect(BLEServer* pServer) {
    deviceConnected = false;
    Serial.println("Android disconnected");
    BLEDevice::startAdvertising();
  }
};

// Listen for CLEAR command from app
class MyCharacteristicCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* pChar) {
    String val = String(pChar->getValue().c_str());
    val.trim();
    if (val == "CLEAR") {
      clearCart();
    }
  }
};

/* ──────────── SETUP ──────────── */
void setup() {
  Serial.begin(115200);
  delay(1000);
  Serial.println("\n--- QLESS SMART CART v2.0 ---");

  SPI.begin();
  rfid.PCD_Init();
  Serial.println("RFID OK");

  if (!display.begin(SSD1306_SWITCHCAPVCC, 0x3C)) {
    Serial.println("OLED failed! Check wiring.");
    for(;;);
  }
  display.clearDisplay();
  display.setTextSize(1);
  display.setTextColor(SSD1306_WHITE);
  display.setCursor(20, 24);
  display.println("QLess Starting...");
  display.display();
  Serial.println("OLED OK");

  loadCart();

  BLEDevice::init("QLess-Cart");

  Serial.print("BLE MAC: ");
  Serial.println(BLEDevice::getAddress().toString().c_str());
  Serial.println("Use this MAC in your QR code: SMARTCART,<MAC>");

  BLEServer* pServer = BLEDevice::createServer();
  pServer->setCallbacks(new MyServerCallbacks());

  BLEService* pService = pServer->createService(SERVICE_UUID);

  pCharacteristic = pService->createCharacteristic(
    CHARACTERISTIC_UUID,
    BLECharacteristic::PROPERTY_NOTIFY |
    BLECharacteristic::PROPERTY_WRITE   // needed to receive CLEAR command
  );
  pCharacteristic->addDescriptor(new BLE2902());
  pCharacteristic->setCallbacks(new MyCharacteristicCallbacks());

  pService->start();

  BLEAdvertising* advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(SERVICE_UUID);
  advertising->setScanResponse(true);
  BLEDevice::startAdvertising();

  updateOLED();
  Serial.printf("Ready. %d items configured. Scan a tag!\n", ITEM_COUNT);
}

/* ──────────── LOOP ──────────── */
void loop() {

  if (!rfid.PICC_IsNewCardPresent() || !rfid.PICC_ReadCardSerial()) {
    delay(50);
    return;
  }

  String uid = readUID();
  Serial.println("\nScanned UID: " + uid);

  // ── Mode card toggle ──
  if (uid == MODE_CARD_UID) {
    addMode = !addMode;
    Serial.println("Mode: " + String(addMode ? "ADD" : "REMOVE"));
    saveCart();
    updateOLED();
    sendFullCartBLE();

  } else {
    // ── Check items array ──
    bool found = false;
    for (int i = 0; i < ITEM_COUNT; i++) {
      if (uid == items[i].uid) {
        found = true;

        if (addMode) {
          items[i].qty++;
          Serial.printf("ADD: %s x%d = Rs%d\n",
            items[i].name.c_str(), items[i].qty,
            items[i].price * items[i].qty);
        } else if (items[i].qty > 0) {
          items[i].qty--;
          Serial.printf("REMOVE: %s x%d\n",
            items[i].name.c_str(), items[i].qty);
        } else {
          Serial.println("Already 0 — cannot remove");
        }

        Serial.printf("TOTAL: Rs%d\n", calcTotal());
        saveCart();
        updateOLED();
        sendFullCartBLE();
        break;
      }
    }

    if (!found) {
      // Print UID so you can copy it for new items
      Serial.println("Unknown tag: " + uid);
      Serial.println("  → Copy this UID and add it to items[] array");

      display.clearDisplay();
      display.setCursor(0, 0);
      display.println("Unknown Tag:");
      display.setCursor(0, 16);
      display.setTextSize(1);
      display.println(uid);
      display.setCursor(0, 40);
      display.println("Add UID to code");
      display.display();
      delay(2000);
      updateOLED();
    }
  }

  rfid.PICC_HaltA();
  rfid.PCD_StopCrypto1();
  delay(800);
}
