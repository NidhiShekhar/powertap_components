
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
      D_PRINT("WiFi connection lost!");
      //Serial1.println("WiFi connected!");
      gFlags.ShouldStartPortal = true;
      gFlags.WiFiConnected = false;
      WiFi.disconnect(); // Ensure state is cleared
      //WiFi.mode(WIFI_OFF);
      WiFi.mode(WIFI_AP_STA);
      WiFi.softAP(mstrDeviceTag.c_str());
      digitalWrite(WIFI_LED_Pin, LOW);
      
      //Line 1: Setup via WiFi:  (16 chars)
      //Line 2: PowerTap Hotspot (16 chars)

#ifdef USE_BLE      
      if(pBTAdvertising != NULL)pBTAdvertising->start();
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

  //WiFi.mode(WIFI_STA);
  WiFi.mode(WIFI_AP_STA);
  WiFi.setAutoReconnect(true);

  // Register an event listener for when the station disconnects
  WiFi.onEvent([](WiFiEvent_t event, WiFiEventInfo_t info){
    if(gFlags.WiFiConnected){
      enableWiFi_AP_Mode();
    }
  }, WiFiEvent_t::ARDUINO_EVENT_WIFI_STA_DISCONNECTED);


  // 2. CONNECTED EVENT
  WiFi.onEvent([](WiFiEvent_t event, WiFiEventInfo_t info){
        D_PRINT("WiFi Restored! System Online.");
        //Serial1.println("WiFi connected!");
        gFlags.WiFiConnected = true;
        digitalWrite(WIFI_LED_Pin, HIGH);
#ifdef USE_BLE        
        if(pBTAdvertising != NULL)pBTAdvertising->stop();
#endif        
        
  }, WiFiEvent_t::ARDUINO_EVENT_WIFI_STA_GOT_IP);

  readDeviceMACAddress();

  mstrMQTTClientId += (char *)gDeviceId;

  loadState();

  mstrDeviceTag += (char *)gDeviceId;
  /* BLE intialization */
 
    #ifdef USE_BLE
        startBLE();
    #endif 

    String ssid = wifiManager.getWiFiSSID();
    String pass = wifiManager.getWiFiPass();
        
    //D_PRINT("Saved SSID: " + ssid);
    //D_PRINT("Saved Pass: " + pass);
    
  
    if(ssid != ""){ 
      wifiManager.setConfigPortalTimeout(30);
    }

    //Serial1.println("Searching WiFi..");
    /* Enabling WiFi Access Point */
    if (!(wifiManager.autoConnect(mstrDeviceTag.c_str(), "drivool123"))) {
      D_PRINT("WiFi Failed to connect");
      //Serial1.println("WiFi Failed to connect");
    }
    else {
      /* if you get here you have connected to the WiFi */
      D_PRINT("WiFi connected!");
      //Serial1.println("WiFi connected!");
    }
    delay(250);

    /*connecting to a mqtt broker */
    mqttClient.setServer(mqtt_broker, mqtt_port);
    mqttClient.setCallback(mqtt_callback);
    

    //DEBUG_PRINT(DEBUG_MIN, "Client connected!");
    //Serial1.println("Client connected!");
    //delay(500);
    sprintf(sub_ack_topic, "pwt_fm01/%s/ack", gDeviceId);    
    sprintf(sub_cmd_Topic, "pwt_fm01/%s/command", gDeviceId);

    //mqttClient.subscribe((const char*)&sub_ack_topic[0]);
    //mqttClient.subscribe((const char*)&sub_cmd_Topic[0]);
    //DEBUG_PRINT(DEBUG_MIN, "mqtt subscribed topic \n1: %s\n2: %s\n\n", &sub_ack_topic[0], &sub_cmd_Topic[0]);
   


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

  if (millis() - lastHeapCheck > 5000) {
    Serial.printf("H:%u\n", ESP.getFreeHeap());
    lastHeapCheck = millis();
  }

  wifiManager.process();
  if (gFlags.WiFiConnected) {
    if (!mqttClient.connected()) {
      mqttReconnect();
    }else{
      Wireless_Communication_Handler();
    }
    
    mqttClient.loop();
  } 
  else {
    
     if(gFlags.BTConnected){
          Wireless_Communication_Handler();
     }else{ 
      //D_PRINT("WiFi Reconencting...");
      unsigned long currentMillis = millis();

      // if (gFlags.ShouldStartPortal) {
      //         D_PRINT("Starting Config Portal safely...");
      //         wifiManager.startConfigPortal(mstrDeviceTag.c_str(), "drivool123");
              
      //         gFlags.ShouldStartPortal = false; 
      //         // Note: WiFiManager will set WiFi back to connected if the user saves successfully
      //         return; // Exit loop early to let the system refresh
      // }
      
      if (currentMillis - lastWiFiAttempt >= WIFI_RETRY_INTERVAL) {
          lastWiFiAttempt = currentMillis;
          D_PRINT("Retrying WiFi Connection...");
          
          // Calling begin() without arguments uses the last known SSID/Pass
          WiFi.begin(); 
      }
      
    }

  }

  /* Fota Update */
  if(gFlags.FOTA) {
    performFotaUpdate();
  }

  if(gFlags.PowerDown == 1) {
    //DEBUG_PRINT(DEBUG_MIN, "Power Failure Detected\n");
    gStatus = Unavailable;
    gFlags.Relay = false;
    RadioCmd_Enqueue(StatusNotification);
    MCU_Cmd_Enqueue(CMD_EM, (const uint8_t *)"EM", 2);
    gFlags.PowerDown = 0;
  }

  static uint32_t lastmilli=0;
  uint32_t now = millis();
  
  if ((now - lastmilli) >= 10000) { // Every 5 sec
      //D_PRINT("showMeterDetailsOnLCD");
      if(!gFlags.Relay && gFlags.ShouldStartPortal){
        static bool blnDisplayDone = false;
        if(!blnDisplayDone){
          showMeesageOnLCD(1,"Setup via WiFi:" );
          showMeesageOnLCD(2,"PowerTap Hotspot" );
          blnDisplayDone = true;
        }else{
          D_PRINT("Starting Config Portal...");
          if(wifiManager.startConfigPortal(mstrDeviceTag.c_str(), "drivool123")){
            gFlags.ShouldStartPortal = false; // On Sucessfull
          }else{
            gFlags.ShouldStartPortal = true;  // On Timeout
          }
          blnDisplayDone = false;
          return;
        }
      }else{
          showMeterDetailsOnLCD(); // First line
          if(gFlags.Relay){ 
            showChargingUpdateOnLCD(); // Second line
          }else{
            showTimeOnLCD(); // Second line
          }
      }
    lastmilli = now;
  }

  UART_Communication_Handling();

}
