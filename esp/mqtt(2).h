/*
 * mqtt.h
 *
 *  Created on: 10-Jan-2026
 *      Author: Yoganathan V
 */

/* Define to prevent recursive inclusion -----------------------------*/
#ifndef INC_MQTT_H_
#define INC_MQTT_H_

/* Includes ----------------------------------------------------------*/
#include <stdlib.h>
#include <string.h>
#include "stdio.h"
#include "uart.h"
#include <stdbool.h>

#include <HardwareSerial.h>
#include <Preferences.h>

#include <BLEDevice.h>
#include <BLEUtils.h>
#include <BLEServer.h>

#include <WiFi.h>
#include <WiFiManager.h>  
#include <PubSubClient.h>
#include <HTTPClient.h>
#include <Update.h>

/* Define ------------------------------------------------------------*/
#define COMMAND_REQUEST       2
#define COMMAND_RESPONSE      3
#define COMMAND_ERROR         4

#define QUEUE_SIZE            5
#define MAX_RETRY_COUNT       10
#define DATA_SIZE             160
#define MQTT_BUFFER_SIZE      512
#define RETRY_DELAY_MS        5000
#define MILLI_SECONDS         1000

#define LCD_ROWS              2
#define LCD_COLS              16

/* Macro -------------------------------------------------------------*/

/* Typedef -----------------------------------------------------------*/
typedef void (*FUN_PTR)(uint8_t *);

typedef struct
{
  FUN_PTR request;
  FUN_PTR response;
} Commands;

typedef enum
{
  BootNotification = 0,
  HeartBeat,
  StatusNotification,
  MeterValues,
  DataTransfer,
  RemoteStart,
  RemoteStop,
  CMD_MAX

} CommandInfo;

typedef struct
{
  CommandInfo   cmd;
  uint64_t      messageId;

} CommandEntry;

typedef struct
{
  CommandEntry buf[QUEUE_SIZE];
  uint8_t head;
  uint8_t tail;
  uint8_t count;

} CommandQueue;

typedef struct
{
  uint8_t row;          // 0 or 1 for 2x16 LCD
  uint8_t column;       // 0–15
  char    text[17];     // Max 16 chars + NULL

} LcdDisCommand;

typedef enum
{
  TX_IDLE,
  TX_WAITING_RESPONSE

} TxState;

typedef struct
{
  unsigned char BTConnected    : 1;  // BLE connection status
  unsigned char WiFiConnected  : 1;  // WiFi connection status
  
  unsigned char ShouldStartPortal  : 1;  // Time to start Web Portal
  
  unsigned char WiFi           : 1;  // BLE / WiFi state
  unsigned char RadioRx        : 1;  // MQTT / BLE RX Status
  unsigned char MCURx          : 1;  // MCU RX Status
  unsigned char Relay          : 1;  // RELAY Status
  unsigned char Charging       : 1;
  unsigned char FOTA           : 1;  // FOTA Status
  unsigned char PowerDown      : 1;  // Main Power status
  unsigned char resv           : 1;
  
} POWER_TAP_FLAGS;

typedef enum
{
  Available = 0,
  Charging,
  Faulted,
  Finishing,
  Reserved,
  Unavailable

} Charger_Status;

/* Variables ---------------------------------------------------------*/
extern POWER_TAP_FLAGS gFlags;
extern Charger_Status gStatus;
extern uint8_t gDeviceId[13];
extern uint8_t gRxBuf[MQTT_BUFFER_SIZE];

extern uint32_t giStartEnergy;
extern uint32_t giRelayOnTime;

extern DecodedTime gHeartBeatTime;
extern uint32_t gHeartBeatReceivedSystemMillis;

/* Function prototypes -----------------------------------------------*/
void Wireless_Communication_Handler(void);
bool RadioCmd_Enqueue(CommandInfo cmd);
bool RadioCmd_Dequeue(void);
void readDeviceMACAddress(void);
void performFotaUpdate(void);

/* External APIs provided by application -----------------------------*/
extern void SendData(uint8_t *buf);
extern bool CheckReceiverStatus(void);
extern String composeRemoteStopResponse();
extern String composeRemoteStartResponse();
extern void handleAutoStopScenario(void);


#endif /* INC_MQTT_H_ */