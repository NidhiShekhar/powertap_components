/*
 * utilities.h
 *
 *  Created on: 20-Jan-2026
 *      Author: Satya Prakash
 */

/* Define to prevent recursive inclusion -----------------------------*/
#ifndef INC_UTILITIES_H_
#define INC_UTILITIES_H_


/* Includes ----------------------------------------------------------*/
#include <stdlib.h>
#include <string.h>
#include "stdio.h"

#define METER_STATE_KEY "MeterState"

#define TID_MAX_LEN 32

typedef enum
{
  STOP_REASON_REMOTE      = 0x01,
  STOP_REASON_NO_LOAD     = 0x02,
  STOP_REASON_TIMEOUT     = 0x03,
  STOP_REASON_COMPLETED   = 0x04,
  STOP_REASON_POWERFAIL   = 0x05,
	STOP_REASON_MAX			    = 0x09,
}StopReason;

typedef struct {
  uint32_t   iStartEnergy; // Meter reading just before start (in Wh)
  uint32_t   iStartTime;
  uint32_t   iStopEnergy; // Meter reading just after stop (in Wh)
  uint32_t   iStopTime;
  StopReason iStopReason;
  bool       isCharging;
  bool       isARC; // Auto Resume Charging
  bool       isServerAckReceived; // is Server Ack received for stop packet
  int8_t     iChargingMode;
  int32_t    iTargetValue; // Time in millisec or Wh
  int32_t    iEnergy;  // Meter reading in Wh
  char       strTID[TID_MAX_LEN];   // Transaction ID 
} DeviceState;

extern DeviceState gDeviceState;
extern Preferences prefs;

extern bool writeDataToNVS(const void *data, const char *key, size_t size);
extern size_t readDataFromNVS(void *data, const char *key, size_t maxSize);

extern void showMeesageOnLCD(uint8_t iLine, char* message );
extern void showTimeOnLCD();
extern void showChargingUpdateOnLCD();
extern void showMeterDetailsOnLCD();
extern void loadState();
extern void saveState();

#endif /* INC_UTILITIES_H_ */