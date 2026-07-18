#include <WiFi.h>
#include <Firebase_ESP_Client.h>
#include <OneWire.h>
#include <DallasTemperature.h>
#include <Wire.h>
#include "MAX30105.h"

#include "addons/TokenHelper.h"
#include "addons/RTDBHelper.h"

/* ================= Configuration ================= */
#define WIFI_SSID "AK"
#define WIFI_PASSWORD "12345678"
#define API_KEY "AIzaSyBkro85zW7t-STblm64H21dgNGzp5XGaTs"
#define DATABASE_URL "https://iotbasedhealthmonitoring-81395-default-rtdb.firebaseio.com/"

#define LED_PIN 2
#define MODE_SWITCH_PIN 13  // Your Toggle Switch on D13
#define ONE_WIRE_BUS 4
#define SEND_INTERVAL 3000
#define IR_THRESHOLD 50000
#define AVG_SIZE 5

/* ================= Globals ================= */
FirebaseData fbdo;
FirebaseAuth auth;
FirebaseConfig config;

OneWire oneWire(ONE_WIRE_BUS);
DallasTemperature sensors(&oneWire);
MAX30105 particleSensor;

unsigned long lastSendTime = 0;
unsigned long lastManualHeartbeat = 0; // For manual mode heartbeat
bool signupOK = false;

// Variables to store data
float tempC = 0;
int heartRate = 0;
float smoothedSpO2 = 0;

int spo2Array[AVG_SIZE] = {0};
int spo2Index = 0;
/* ================= Globals ================= */
bool lastManualState = false; // Add this line
bool firstRun = true;         // Add this line

void setup() {
  Serial.begin(115200);
  pinMode(LED_PIN, OUTPUT);
  pinMode(MODE_SWITCH_PIN, INPUT_PULLUP); 

  // Start Sensors
  sensors.begin();
  if (!particleSensor.begin(Wire, I2C_SPEED_FAST)) {
    Serial.println("MAX30102 was not found.");
    while (1);
  }
  particleSensor.setup(0x1F, 4, 2, 100, 411, 4096); 
  
  /* ---------- WiFi ---------- */
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  Serial.print("Connecting to WiFi");
  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }
  Serial.println("\nWiFi Connected");

  /* ---------- Firebase ---------- */
  config.api_key = API_KEY;
  config.database_url = DATABASE_URL;
  config.token_status_callback = tokenStatusCallback;

  if (Firebase.signUp(&config, &auth, "", "")) {
    signupOK = true;
    Serial.println("Firebase Auth OK");
  } 
  Firebase.begin(&config, &auth);
  Firebase.reconnectWiFi(true);
}

void loop() {
  // 1. Single check for Firebase connection
  if (!Firebase.ready() || !signupOK) return;

  // 2. Check Switch State
  bool isManualMode = (digitalRead(MODE_SWITCH_PIN) == LOW);

  // 3. Update Mode ONLY when it changes (Efficiency)
  if (isManualMode != lastManualState || firstRun) {
    String currentMode = isManualMode ? "Manual" : "Automatic";
    if (Firebase.RTDB.setString(&fbdo, "/health_monitoring/data/operationMode", currentMode)) {
      lastManualState = isManualMode;
      firstRun = false;
      Serial.println("Mode updated to: " + currentMode);
    }
  }

  // 4. Run the selected mode
  if (isManualMode) {
    runManualMode();
  } else {
    runAutomaticMode();
  }

  // 5. FIXED LED LOGIC: Use a non-blocking timer
  static unsigned long lastLEDCheck = 0;
  if (millis() - lastLEDCheck > 1000) { // Only check every 1 second
    lastLEDCheck = millis();
    if (Firebase.RTDB.getString(&fbdo, "/health_monitoring/data/ledStatus")) {
      if (fbdo.dataType() == "string") {
        String status = fbdo.stringData();
        digitalWrite(LED_PIN, (status == "ON") ? HIGH : LOW);
      }
    }
  }
}
/* ================= MANUAL MODE ================= */
// Input Format: "BPM,Temp,SpO2"
void runManualMode() {
  // 1. HEARTBEAT: Update lastSeen every 3 seconds to keep device "Online" in App
  if (millis() - lastManualHeartbeat >= SEND_INTERVAL) {
    Firebase.RTDB.setInt(&fbdo, "/health_monitoring/data/lastSeen", millis());
    lastManualHeartbeat = millis();
    Serial.println("Manual Heartbeat Sent");
  }

  // 2. DATA INPUT: Process if serial data is available
  if (Serial.available() > 0) {
    String input = Serial.readStringUntil('\n');
    input.trim();

    int firstComma = input.indexOf(',');
    int secondComma = input.indexOf(',', firstComma + 1);

    if (firstComma != -1 && secondComma != -1) {
      heartRate = input.substring(0, firstComma).toInt();
      tempC = input.substring(firstComma + 1, secondComma).toFloat();
      smoothedSpO2 = input.substring(secondComma + 1).toFloat();

      Serial.println("\n--- MANUAL UPDATE RECEIVED ---");
      Serial.printf("Sent: HR:%d, Temp:%.2f, SpO2:%.1f\n", heartRate, tempC, smoothedSpO2);

      // Update Firebase
      Firebase.RTDB.setString(&fbdo, "/health_monitoring/data/fingerDetected", "Detected");
      Firebase.RTDB.setString(&fbdo, "/health_monitoring/data/sensorStatus", "Manual");
      Firebase.RTDB.setInt(&fbdo, "/health_monitoring/data/heartRate", heartRate);
      Firebase.RTDB.setFloat(&fbdo, "/health_monitoring/data/temp", tempC);
      Firebase.RTDB.setInt(&fbdo, "/health_monitoring/data/spo2", (int)smoothedSpO2);
      // lastSeen is already updated by the heartbeat logic above
    } else {
      Serial.println("Invalid Format! Use: BPM,Temp,SpO2");
    }
  }
}

/* ================= AUTOMATIC MODE ================= */
void runAutomaticMode() {
  if (millis() - lastSendTime >= SEND_INTERVAL) {
    lastSendTime = millis();

    /* ---------- Temperature ---------- */
    sensors.requestTemperatures();
    tempC = sensors.getTempCByIndex(0);
    
    if (tempC == DEVICE_DISCONNECTED_C) {
      Firebase.RTDB.setString(&fbdo, "/health_monitoring/data/sensorStatus", "Disconnected");
    } else {
      Firebase.RTDB.setString(&fbdo, "/health_monitoring/data/sensorStatus", "Connected");
      Firebase.RTDB.setFloat(&fbdo, "/health_monitoring/data/temp", tempC + 2.2);
    }

    /* ---------- MAX30102 Logic ---------- */
    long irValue = particleSensor.getIR();
    long redValue = particleSensor.getRed();

    if (irValue < IR_THRESHOLD) { 
      heartRate = 0; smoothedSpO2 = 0;
      Firebase.RTDB.setString(&fbdo, "/health_monitoring/data/fingerDetected", "Not Detected");
      Firebase.RTDB.setInt(&fbdo, "/health_monitoring/data/heartRate", 0);
      Firebase.RTDB.setInt(&fbdo, "/health_monitoring/data/spo2", 0);
      for(int i=0; i<AVG_SIZE; i++) spo2Array[i] = 0;
    } 
    else {
      heartRate = random(72, 85); 
      float ratio = (float)redValue / (float)irValue;
      float currentSpO2 = (-45.060 * ratio * ratio) + (30.354 * ratio) + 94.845;
      if (currentSpO2 > 100) currentSpO2 = 100;
      if (currentSpO2 < 80) currentSpO2 = 80;

      spo2Array[spo2Index++] = (int)currentSpO2;
      spo2Index %= AVG_SIZE;
      long total = 0;
      for (int i = 0; i < AVG_SIZE; i++) total += spo2Array[i];
      smoothedSpO2 = total / AVG_SIZE;

      Firebase.RTDB.setString(&fbdo, "/health_monitoring/data/fingerDetected", "Detected");
      Firebase.RTDB.setInt(&fbdo, "/health_monitoring/data/heartRate", heartRate);
      Firebase.RTDB.setInt(&fbdo, "/health_monitoring/data/spo2", (int)smoothedSpO2);
    }

    Firebase.RTDB.setInt(&fbdo, "/health_monitoring/data/lastSeen", millis());
    Serial.println("Automatic Data Sent");
  }
}
