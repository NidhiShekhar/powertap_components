/*
 * uart.h
 *
 *  Created on: 10-Jan-2026
 *      Author: Yoganathan V
 */

/* Define to prevent recursive inclusion -----------------------------*/
#ifndef INC_UART_H_
#define INC_UART_H_


/* Includes ----------------------------------------------------------*/
#include <stdlib.h>
#include <string.h>
#include "stdio.h"

#include <HardwareSerial.h>
#include <Preferences.h>


/* Define ------------------------------------------------------------*/
#define HW_VER          "1.1.0"
#define FW_VER          "3.1.0"


// #define DEBUG_PRINT(level, fmt, ...) \
//   do { \
//     if (level <= gDebugLevel) { \
//       Serial.printf("[L%d] " fmt "\n", level, ##__VA_ARGS__); \
//     } \
//   } while (0)

// #define DEBUG_PRINT(level, fmt, ...) \
//   if (level <= gDebugLevel) Serial.printf("[L%u] " fmt "\n", level, ##__VA_ARGS__)

#define DEBUG_PRINT(level, fmt, ...) {}

//#define D_PRINT(fmt, ...) Serial.printf("[L3] " fmt "\n", ##__VA_ARGS__)
#define D_PRINT(fmt, ...) Serial.printf("", "")
/* Macro -------------------------------------------------------------*/
#define RX_BUFFER_SIZE				160
#define CMD_QUEUE_DEPTH       10

#define CONFIG_NAMESPACE      "PowerTap"
#define FACTORIALS_KEY        "Factorials"


/* Typedef -----------------------------------------------------------*/

enum DebugLevel
{
  DEBUG_NONE  = 0,
  DEBUG_MIN   = 1,
  DEBUG_FULL  = 2

};

typedef struct 
{
  int year;
  int month;
  int day;
  int hour;
  int minute;
  int second;
  int millisecond;

} DecodedTime;

typedef enum
{
  CMD_HEARTBEAT   = 0x01,
  CMD_RELAY       = 0x02,
  CMD_DISPLAY     = 0x03,
  CMD_CALIB       = 0x04,
  CMD_RESET       = 0x05,
	CMD_ESP			    = 0x06,
  CMD_EM          = 0x07,
  CMD_FACTORIAL   = 0x08,

} MCU_CommandId;

typedef enum
{
  RESP_OK      = 0x00,
  RESP_INVALID = 0x01,
  RESP_BUSY    = 0x02,
  RESP_ERROR   = 0x03,
  RESP_TIMEOUT = 0x04

} RespStatus;

typedef struct
{
	uint8_t rxBuffer[RX_BUFFER_SIZE];
	uint8_t rxCount;
	uint8_t rxFlag;

}UART_STRUCT;

typedef enum
{
    Com_Send,
    Com_Waiting

}ComStatus;

typedef struct
{
  uint8_t  cmd;
  uint8_t  length;
  uint8_t *data;

} CmdNode;

typedef struct
{
  CmdNode  nodes[CMD_QUEUE_DEPTH];
  uint8_t head;
  uint8_t tail;
  uint8_t count;

} CmdQueue;

typedef struct
{
  uint8_t  cmd;
  uint8_t  status;
  uint8_t *payload;
  uint8_t  payloadLen;

} UartResponse;

typedef struct
{
  int32_t       powerActive;
  int32_t       powerReactive;
  int32_t       powerApparent;
  int32_t       energyActive;
  int32_t       energyReactive;
  int32_t       energyApparent;
  uint32_t      rmsvoltage;
  uint32_t      rmscurrent;

} metroData_t;

typedef struct
{
  uint32_t powerFact;
  uint32_t voltageFact;
  uint32_t currentFact;

} metroFactorials_t;

/* Variables ---------------------------------------------------------*/
extern int                  gMeteringInterval;
extern int                  gDebugLevel;
extern metroData_t          gMetroData;
extern metroFactorials_t    gMetroFact;


/* Function prototypes -----------------------------------------------*/
bool MCU_Cmd_Enqueue(uint8_t cmd, const uint8_t *payload, uint8_t length);
bool writeDataToNVS(const void *data, const char *key, size_t size);
size_t readDataFromNVS(void *data, const char *key, size_t maxSize);
void UART_Communication_Handling(void);
void UART1_RxCpltCallback(uint8_t rxbyte);



#endif /* INC_UART_H_ */