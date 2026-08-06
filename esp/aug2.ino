
/* Includes ------------------------------------------------------------------*/
#include <WiFiManager.h>  
#include <HardwareSerial.h>
#include "mqtt.h"
#include "uart.h"
#include "utilities.h"
#include <WiFi.h>
#include <PubSubClient.h>
//#include <HTTPClient.h>

#define BT_PRINT(fmt, ...) Serial.printf("BT " fmt "\n", ##__VA_ARGS__)

/* Typedef -------------------------------------------------------------------*/

WiFiClient        espClient         = {0};
PubSubClient      mqttClient(espClient);
SemaphoreHandle_t xRadioBufMutex    = NULL;

/* Define --------------------------------------------------------------------*/
#define SERVICE_UUID            "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
#define CHARACTERISTIC_UUID     "beb5483e-36e1-4688-b7f5-ea07361b26a8"
#define MAX_CON_RETRY_COUNT     10
#define USE_BLE

// BLE-only test: ESP <-> BLE <-> Android <-> Firebase (phone network).
// Comment out / set to 0 to restore normal WiFi+MQTT on the ESP.
#define BLE_ONLY_TEST  1

/* Variables -----------------------------------------------------------------*/
const int       buttonPin           = 4;
const int       BLE_LED_Pin         = 45;
const int       WIFI_LED_Pin        = 48;
const int       INT_PIN             = 8;

const char *mqtt_broker       = "65.0.141.225";
const char *public_topic      = "pwt_fm01/packet";
const char *mqtt_username     = "mqttpwt_fw01";
const char *mqtt_password     = "Mqtt##fw01";
const int   mqtt_port           = 1883;
static char sub_ack_topic[80] = {0};
static char sub_cmd_Topic[80] = {0};

String mstrMQTTClientId = "esp32_mqtt_client_";
      
String mstrDeviceTag = "PowerTap_";
/* Extern --------------------------------------------------------------------*/

/* Function prototypes -------------------------------------------------------*/

#ifdef USE_BLE 
//#include <BLEDevice.h>
//#include <BLEUtils.h>
//#include <BLEServer.h>
//#include <BLE2902.h>

BLEAdvertising *pBTAdvertising = NULL;
BLECharacteristic *pCharacteristic  = NULL;

class OnBTStateChange : public BLEServerCallbacks
{
  void onConnect(BLEServer *pServer) {
    //D_PRINT("BT Connected");
    //Serial1.println("BT connected!");
    gFlags.BTConnected = true;
    digitalWrite(BLE_LED_Pin, HIGH);
    if(pBTAdvertising != NULL)pBTAdvertising->stop();
  }
  void onDisconnect(BLEServer *pServer) {
    //D_PRINT("BT Disonnected");
    gFlags.BTConnected = false;
    digitalWrite(BLE_LED_Pin, LOW);
   
    if(pBTAdvertising != NULL)pBTAdvertising->start();
  
  }
};

class OnBTDataReceive : public BLECharacteristicCallbacks
{
  void onWrite(BLECharacteristic *pCharacteristic) {
    String strBTBuff = pCharacteristic->getValue();
    size_t iBTBuffLen = strBTBuff.length();
    //BT_PRINT("Rx V: %s :", (const char *)strBTBuff);
    //BT_PRINT("%d\n",iBTBuffLen);
    
    if (iBTBuffLen > 0  && xRadioBufMutex != NULL) {
      if (xSemaphoreTake(xRadioBufMutex, (TickType_t)10) == pdTRUE) {
        if (gFlags.RadioRx) {
          //BT_PRINT("W:9");
          xSemaphoreGive(xRadioBufMutex);
          return;
        }

        if (iBTBuffLen >= MQTT_BUFFER_SIZE) iBTBuffLen = MQTT_BUFFER_SIZE - 1;

        memcpy(gRxBuf, strBTBuff.c_str(), iBTBuffLen);
        gRxBuf[iBTBuffLen] = '\0'; // Crucial: Null-terminate to prevent crashes in BT_PRINT and JSON parsing

        BT_PRINT("R:%s", (const char *)gRxBuf);
        gFlags.RadioRx = true;

        xSemaphoreGive(xRadioBufMutex);
      }
      //  else {
      //   BT_PRINT("W:10");
      // }



      // for (int i = 0; i < iBTBuffLen ; i++){
      //   gRxBuf[i] = strBTBuff[i]; 
      // }
      // gRxBuf[iBTBuffLen] = 0;
      
      // BT_PRINT("Rx B: %s\n", (const char *)gRxBuf);
      // gFlags.RadioRx = true;



    }
  }
};

void startBLE() {

  BLEDevice::init(mstrDeviceTag.c_str());
  BLEServer *pServer = BLEDevice::createServer();
  pServer->setCallbacks(new OnBTStateChange());
  
  BLEService *pService = pServer->createService(SERVICE_UUID);
    
   //  | BLECharacteristic::PROPERTY_NOTIFY

  pCharacteristic = pService->createCharacteristic(
                                              CHARACTERISTIC_UUID,
                                              BLECharacteristic::PROPERTY_READ |
                                              BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_NOTIFY);

  pCharacteristic->setCallbacks(new OnBTDataReceive());
  //pCharacteristic->addDescriptor(new BLE2902());
  
  pService->start();
  pBTAdvertising = pServer->getAdvertising();
  pBTAdvertising->addServiceUUID(SERVICE_UUID);
  pBTAdvertising->setScanResponse(true);
  pBTAdvertising->start();

}

void shutdownBLE() {
    //D_PRINT("Shutting down BLE to reclaim RAM...");
    
    // 1. Stop advertising first
    BLEDevice::getAdvertising()->stop();
    
    // 2. De-initialize the entire BLE stack and release memory
    // The 'true' argument releases the BT controller memory back to the heap
    BLEDevice::deinit(true); 
    
    delay(100); // Give the RTOS a moment to clean up
    //Serial.printf("Heap after BLE shutdown: %u bytes\n", ESP.getFreeHeap());
}

#endif 

void mqtt_callback(char *subscribe_topic, byte *payload, unsigned int length)
{
  // for (int i = 0; i < length; i++) {
  //   gRxBuf[i] = payload[i];
  // }
  // gFlags.RadioRx = true;


  if (xRadioBufMutex != NULL && xSemaphoreTake(xRadioBufMutex, (TickType_t)10) == pdTRUE) {
    if (gFlags.RadioRx) {
      xSemaphoreGive(xRadioBufMutex);
      return;
    }
    size_t len = (length < MQTT_BUFFER_SIZE) ? length : (MQTT_BUFFER_SIZE - 1);
    memcpy(gRxBuf, payload, len);
    gRxBuf[len] = '\0'; // Ensure null termination
    gFlags.RadioRx = true;
    xSemaphoreGive(xRadioBufMutex);
  }
}

void SendData(uint8_t *payload)
{
  if (gFlags.WiFiConnected) {
    D_PRINT("SendData via MQTT: %s\n", (const char *)payload);
    mqttClient.publish(public_topic, (const char *)payload);
  }else if (gFlags.BTConnected) {
    BT_PRINT("T:%s\n", (const char *)payload);
#ifdef USE_BLE    
    pCharacteristic->setValue((uint8_t*)payload, strlen((char *)payload));
    pCharacteristic->notify();
#endif
  }
}

bool CheckReceiverStatus(void)
{
  // if (gFlags.RadioRx) {
  //   gFlags.RadioRx = false;
  //   return true;
  // }
  // return false;

  return gFlags.RadioRx;
}

uint8_t iMQTTAttemptCount = 0;
void mqttReconnect() {
  // Loop until we're reconnected
  //while (!mqttClient.connected()) {
    D_PRINT("MQTT connecting...");
    // Attempt to connect
    //if (mqttClient.connect("ESP32_Client_ID")) {
    //delay(1000);
    if (mqttClient.connect(mstrMQTTClientId.c_str(), mqtt_username, mqtt_password) ){ 
      //D_PRINT("connected.");
      // Resubscribe to topics here if needed
      gFlags.ShouldStartPortal = false;
      iMQTTAttemptCount = 0;
      mqttClient.subscribe(sub_ack_topic);
      mqttClient.subscribe(sub_cmd_Topic);
      //Serial1.println("Linking Cloud...");
      //D_PRINT("Subscribed.");
      delay(500);

    } else {
      //D_PRINT("failed, rc=");
      //D_PRINT(mqttClient.state());
      iMQTTAttemptCount++;
      D_PRINT("Trying again...");
      if(iMQTTAttemptCount > 10) {
         enableWiFi_AP_Mode();
      }
      // Wait 5 seconds before retrying to avoid crashing the ESP
      delay(5000);
    }
  //}
}

void IRAM_ATTR PowerFailureInterrupt()
{
  // DEBUG_PRINT(DEBUG_MIN, "Power Failure Detected\n");
  gFlags.PowerDown = 1;
  gDeviceState.iEnergy += gMetroData.energyActive; // gMeetering.Energy;
  gDeviceState.iStopEnergy = gDeviceState.iEnergy;

  if(gDeviceState.isCharging == true){
    gDeviceState.iStopReason = STOP_REASON_POWERFAIL;
  }
  
  saveState();
  gStatus = Unavailable;
  gFlags.Relay = false;
  if(gDeviceState.isCharging == true){
    //String strReponse = RemoteStop_Response();  
    //DEBUG_PRINT(DEBUG_MIN,"strReponse %s", strReponse.c_str());
    //SendData((uint8_t*) strReponse.c_str());
  }
}

void IRAM_ATTR serialEvent1()
{
  if (Serial1.available()) {
    char inChar = Serial1.read();
    UART1_RxCpltCallback(inChar);
    //DEBUG_PRINT(DEBUG_MIN, "gMUBuffer :%s", gMcu.rxBuffer);
  }
}

WiFiManager wifiManager;

void enableWiFi_AP_Mode(){
#if BLE_ONLY_TEST
      // BLE-only test: never bring WiFi/AP back up
      gFlags.WiFiConnected = false;
      gFlags.ShouldStartPortal = false;
      return;
#else
      if (gFlags.ShouldStartPortal && !gFlags.WiFiConnected) return; // Already in this state

      D_PRINT("WiFi connection lost! Enabling AP Mode.");
      gFlags.ShouldStartPortal = true;
      gFlags.WiFiConnected = false;

      // Only switch modes if necessary
      if (WiFi.getMode() != WIFI_AP_STA) {
          WiFi.mode(WIFI_AP_STA);
      }

      WiFi.softAP(mstrDeviceTag.c_str());
      digitalWrite(WIFI_LED_Pin, LOW);

#ifdef USE_BLE      
      if(pBTAdvertising != NULL)pBTAdvertising->start();
#endif
#endif
}

void setup()
{
  uint8_t  gConRetryCnt = 0;
  delay(500); // To give LCD to internally start their module before sending any data

  /* UART Initialization */
  Serial.begin(115200);
  Serial1.setPins(18, 17, -1, -1);
  Serial1.begin(115200);
  Serial .printf((char *)"\r\n");
	Serial1.printf((char *)"\n\n");

  pinMode(WIFI_LED_Pin, OUTPUT);
  pinMode(BLE_LED_Pin, OUTPUT);
  pinMode(INT_PIN, INPUT_PULLUP);
  attachInterrupt(digitalPinToInterrupt(INT_PIN), PowerFailureInterrupt, RISING);
  xRadioBufMutex = xSemaphoreCreateMutex();
	DEBUG_PRINT(DEBUG_MIN, "--------------------------------------------------");
	DEBUG_PRINT(DEBUG_MIN, "                 Drivool PowerTap                 ");
	DEBUG_PRINT(DEBUG_MIN, "--------------------------------------------------");
	DEBUG_PRINT(DEBUG_MIN, "HW Ver: %s", HW_VER);
	DEBUG_PRINT(DEBUG_MIN, "FW Ver: %s\n", FW_VER);
  Serial1.printf("ESP Init:{\"HW\":\"%s\",\"FW\":\"%s\"}\n", HW_VER, FW_VER);
  
  for(int i=0; i<=5; i++)
  {
    digitalWrite(WIFI_LED_Pin, HIGH);
    digitalWrite(BLE_LED_Pin, HIGH);
    delay(500);
    digitalWrite(WIFI_LED_Pin, LOW);
    digitalWrite(BLE_LED_Pin, LOW);
    delay(500);
  }

  readDeviceMACAddress();

  mstrMQTTClientId += (char *)gDeviceId;

  loadState();

  mstrDeviceTag += (char *)gDeviceId;

#if BLE_ONLY_TEST
  // Force radio off (keeps saved credentials for when BLE_ONLY_TEST is turned off)
  WiFi.setAutoReconnect(false);
  WiFi.disconnect(true);
  WiFi.mode(WIFI_OFF);
  gFlags.WiFiConnected = false;
  gFlags.ShouldStartPortal = false;
  digitalWrite(WIFI_LED_Pin, LOW);
  D_PRINT("BLE_ONLY_TEST: WiFi OFF — use Android BLE bridge to Firebase");

#ifdef USE_BLE
  startBLE();
#endif

#else
  // Ensure we are in AP+STA mode so the config portal can be hosted
  // while the station is also active/searching.
  WiFi.mode(WIFI_AP_STA);
  WiFi.setAutoReconnect(true);

#ifdef USE_BLE
  // WiFi first, then BLE — required for reliable STA join
  // (BLE is started after autoConnect below)
#endif

  // WiFiManager Setup
  wifiManager.setConfigPortalTimeout(180); // 3 minutes timeout
  wifiManager.setConnectTimeout(30);       // 30 seconds to try connecting to station
  wifiManager.setConfigPortalBlocking(false);

  /* Enabling WiFi */
  if (!(wifiManager.autoConnect(mstrDeviceTag.c_str(), "drivool123"))) {
    D_PRINT("WiFi: AutoConnect failed, starting non-blocking portal");
  }
  else {
    D_PRINT("WiFi: Connected!");
    gFlags.WiFiConnected = true;
    digitalWrite(WIFI_LED_Pin, HIGH);
  }
  delay(250);

#ifdef USE_BLE
  startBLE();
#endif

  /*connecting to a mqtt broker */
  mqttClient.setServer(mqtt_broker, mqtt_port);
  mqttClient.setCallback(mqtt_callback);

  sprintf(sub_ack_topic, "pwt_fm01/%s/ack", gDeviceId);
  sprintf(sub_cmd_Topic, "pwt_fm01/%s/command", gDeviceId);
#endif

  //DEBUG_PRINT(DEBUG_MIN, " RadioCmd_Enqueue(BootNotification)");
  RadioCmd_Enqueue(BootNotification);
  //DEBUG_PRINT(DEBUG_MIN, " RadioCmd_Enqueue(StatusNotification)");
  RadioCmd_Enqueue(StatusNotification);

  DEBUG_PRINT(DEBUG_MIN, "readDataFromNVS");
  size_t size = readDataFromNVS(&gMetroFact, FACTORIALS_KEY, sizeof(gMetroFact));
  if (size) {
    D_PRINT("CALIB read : V=%ld I=%ld P=%ld",
            (long)gMetroFact.voltageFact, (long)gMetroFact.currentFact, (long)gMetroFact.powerFact);
    MCU_Cmd_Enqueue(CMD_FACTORIAL, (const uint8_t *)&gMetroFact, sizeof(gMetroFact));
  }else{
    D_PRINT("readDataFromNVS failed");
  }
}
unsigned long lastHeapCheck = 0;
unsigned long lastWiFiAttempt = 0;
const unsigned long WIFI_RETRY_INTERVAL = 30000; // 30 seconds
void loop()
{
#if !BLE_ONLY_TEST
  wifiManager.process();

  // Sync our internal flag with actual WiFi status
  if (WiFi.status() == WL_CONNECTED) {
    if (!gFlags.WiFiConnected) {
      gFlags.WiFiConnected = true;
      digitalWrite(WIFI_LED_Pin, HIGH);
      D_PRINT("WiFi: Connected (IP: %s)", WiFi.localIP().toString().c_str());
      #ifdef USE_BLE
        if(pBTAdvertising != NULL) pBTAdvertising->stop();
      #endif
    }
  } else {
    if (gFlags.WiFiConnected) {
      gFlags.WiFiConnected = false;
      digitalWrite(WIFI_LED_Pin, LOW);
      D_PRINT("WiFi: Disconnected");
      #ifdef USE_BLE
        if(pBTAdvertising != NULL) pBTAdvertising->start();
      #endif
    }
  }

  if (gFlags.WiFiConnected) {
    if (!mqttClient.connected()) {
      mqttReconnect();
    } else {
      Wireless_Communication_Handler();
    }
    mqttClient.loop();
  } else
#endif
  {
    // BLE path (only path when BLE_ONLY_TEST=1)
    if (gFlags.BTConnected) {
      Wireless_Communication_Handler();
    }
  }

  /* Fota Update */
  if (gFlags.FOTA) {
#if !BLE_ONLY_TEST
    performFotaUpdate();
#else
    gFlags.FOTA = false; // needs WiFi; skip in BLE-only test
#endif
  }

  if (gFlags.PowerDown == 1) {
    gStatus = Unavailable;
    gFlags.Relay = false;
    RadioCmd_Enqueue(StatusNotification);
    MCU_Cmd_Enqueue(CMD_EM, (const uint8_t *)"EM", 2);
    gFlags.PowerDown = 0;
  }

  static uint32_t lastmilli = 0;
  uint32_t now = millis();

  if ((now - lastmilli) >= 5000) {
#if BLE_ONLY_TEST
    // Always show meter/time on device LCD during BLE test (no WiFi portal nag)
    showMeterDetailsOnLCD();
    if (gFlags.Relay) {
      showChargingUpdateOnLCD();
    } else {
      showTimeOnLCD();
    }
#else
    if (!gFlags.Relay && !gFlags.WiFiConnected) {
      static bool blnDisplayDone = false;
      if (!blnDisplayDone) {
        showMeesageOnLCD(1, "Setup via WiFi:");
        showMeesageOnLCD(2, "PowerTap Hotspot");
        blnDisplayDone = true;
      }
    } else {
      showMeterDetailsOnLCD();
      if (gFlags.Relay) {
        showChargingUpdateOnLCD();
      } else {
        showTimeOnLCD();
      }
    }
#endif
    lastmilli = now;
  }

  UART_Communication_Handling();
}
