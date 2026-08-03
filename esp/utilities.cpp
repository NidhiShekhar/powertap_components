/*
 * utilities.cpp
 *
 *  Created on: 20-Jan-2026
 *      Author: Satya Prakash
 */

/* Includes ------------------------------------------------------------------*/
#include "mqtt.h"
#include "uart.h"
#include "utilities.h"
#include "stdio.h"

DeviceState gDeviceState = {0};

static const char *months[] = {
        "JAN", "FEB", "MAR", "APR", "MAY", "JUN",
        "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"
};
    
static  LcdDisCommand cmdFirstLineLCD = {
        .row = 0,
        .column = 0,
        .text = ""
};

static  LcdDisCommand cmdSecondLineLCD = {
        .row = 1,
        .column = 0,
        .text = ""
};


Preferences prefs;
bool writeDataToNVS(const void *data, const char *key, size_t size)
{
  if (!prefs.begin(CONFIG_NAMESPACE, false))
    return false;

  size_t written = prefs.putBytes(key, data, size);
  prefs.end();

  return (written == size);
}

size_t readDataFromNVS(void *data, const char *key, size_t maxSize)
{
  if (!prefs.begin(CONFIG_NAMESPACE, true))
    return 0;

  size_t storedSize = prefs.getBytesLength(key);

  if (storedSize > 0 && storedSize <= maxSize)
    prefs.getBytes(key, data, storedSize);

  prefs.end();
  return storedSize;  // return actual stored data size
}
// -------------------- Save to NVS --------------------
void saveState() {
  prefs.begin("app", false);
  prefs.putBytes(METER_STATE_KEY, &gDeviceState, sizeof(DeviceState));
  prefs.end();      
  DEBUG_PRINT(DEBUG_MIN,"State saved to flash %d", gDeviceState.iEnergy);
}

// -------------------- Load from NVS --------------------
void loadState() {
  prefs.begin("app", true);
  size_t len = prefs.getBytesLength(METER_STATE_KEY);

  if (len == sizeof(DeviceState)) {
    prefs.getBytes(METER_STATE_KEY, &gDeviceState, sizeof(DeviceState));

    DEBUG_PRINT(DEBUG_MIN,"-----Saved State loaded from flash-----");
    DEBUG_PRINT(DEBUG_MIN,"iEnergy: %d", gDeviceState.iEnergy);
    DEBUG_PRINT(DEBUG_MIN,"isCharging: %d", gDeviceState.isCharging);
    DEBUG_PRINT(DEBUG_MIN,"iStartEnergy: %d", gDeviceState.iStartEnergy);
    DEBUG_PRINT(DEBUG_MIN,"iStopEnergy: %d", gDeviceState.iStopEnergy);
    DEBUG_PRINT(DEBUG_MIN,"iStopReason: %d", gDeviceState.iStopReason);
    DEBUG_PRINT(DEBUG_MIN,"isARC: %d", gDeviceState.isARC);
    DEBUG_PRINT(DEBUG_MIN,"iTargetValue: %d", gDeviceState.iTargetValue);
    DEBUG_PRINT(DEBUG_MIN,"iChargingMode: %d", gDeviceState.iChargingMode);

    if(gDeviceState.strTID != 0 && gDeviceState.strTID[0] != 0){
      DEBUG_PRINT(DEBUG_MIN,"strTID: %s", gDeviceState.strTID);
    }
 

  } else {
    // Defaults
    gDeviceState.isCharging = false;
    gDeviceState.iStartEnergy = 0;
    strcpy(gDeviceState.strTID,"");
    Serial.println("No saved state, using defaults");
  }
  prefs.end();
}

DecodedTime get_time_with_ist(const DecodedTime *input_dt, long ms_to_add) {
    // 1. Create a copy to work with
    DecodedTime result = *input_dt;

    // 2. Handle Milliseconds carry-over
    long total_ms = (long)result.millisecond + ms_to_add;
    int seconds_to_add = total_ms / 1000;
    result.millisecond = total_ms % 1000;

    // Handle negative remainder logic (if ms_to_add was negative)
    if (result.millisecond < 0) {
        result.millisecond += 1000;
        seconds_to_add--;
    }

    // 3. Prepare C standard time structure for calendar normalization
    struct tm time_data = {0};
    time_data.tm_year = result.year - 1900;
    time_data.tm_mon  = result.month - 1;
    time_data.tm_mday = result.day;
    time_data.tm_hour = result.hour;
    time_data.tm_min  = result.minute;
    time_data.tm_sec  = result.second + seconds_to_add;

    // 4. Apply IST Offset (+5 hours, +30 minutes)
    time_data.tm_hour += 5;
    time_data.tm_min  += 30;

    // 5. Normalize (Handles rolling over days, months, and leap years)
    mktime(&time_data);

    // 6. Update the result struct with normalized values
    result.year   = time_data.tm_year + 1900;
    result.month  = time_data.tm_mon + 1;
    result.day    = time_data.tm_mday;
    result.hour   = time_data.tm_hour;
    result.minute = time_data.tm_min;
    result.second = time_data.tm_sec;

    return result;
}

void convertIsoToFormattedIST(uint32_t iEllapsedSystemMillis, char *out_str, size_t max_len)
{
    DecodedTime dt = get_time_with_ist(&gHeartBeatTime,iEllapsedSystemMillis);

    // 1. Determine AM or PM
    const char *period = (dt.hour >= 12) ? "PM" : "AM";

    // 2. Convert 24h to 12h format
    int hour12 = dt.hour % 12;
    if (hour12 == 0) hour12 = 12; // Handle Midnight/Noon

    // 3. Safety check for month index (1-12)
    int month_idx = dt.month - 1;
    if (month_idx < 0 || month_idx > 11) month_idx = 0; 

    // 4. Print to the buffer
    // %02d: 2 digits for day, zero-padded
    // %d:   Hour without leading zero
    // %02d: Minutes with leading zero
    snprintf(out_str, max_len, "  %02d%s %d:%02d%s", 
             dt.day, months[month_idx], hour12, dt.minute, period);

}

void getVoltageEnergyString(char *dest, double voltage, double energy) {
    char leftPart[17];
    char rightPart[17];
    
    // 1. Format the pieces
    // Result example: "230V"
    int leftLen = sprintf(leftPart, "%.0fV", voltage/1000.0);
    
    // Result example: "1.2KWh"
    int rightLen = (energy > 1000000) ? sprintf(rightPart, "%.1fKWh", energy / 1000000.0) : sprintf(rightPart, "%.1fWh", energy/1000.0) ;


    // 2. Calculate necessary padding
    // Total LCD width is 16. 
    int spacesNeeded = 16 - leftLen - rightLen;

    // 3. Safety Check: If data is too long, we can't add spaces
    if (spacesNeeded < 0) spacesNeeded = 0;

    // 4. Build the final string: Left + Spaces + Right
    // Start with the left part
    strcpy(dest, leftPart);
    
    // Add the specific number of spaces
    for (int i = 0; i < spacesNeeded; i++) {
        strcat(dest, " ");
    }
    
    // Append the right part
    strcat(dest, rightPart);
}

void showMeterDetailsOnLCD()
{

    char strDisplay[32] = {0};

    if(gMetroData.rmsvoltage == 0) return;
    
    char temp[32] = {0};
    //DEBUG_PRINT(DEBUG_MIN, "gMetroData.powerActive: %ld", gMetroData.powerActive );
    if(gFlags.Relay){
        if(gMetroData.powerActive > 1000000){

          sprintf(temp, "%.0fV %.1fA %.1fKW",
                        gMetroData.rmsvoltage / 1000.0,
                        gMetroData.rmscurrent / 1000.0,
                        gMetroData.powerActive / 1000000.0);
        }
        else {

          sprintf(temp, "%.0fV %.1fA %.1fW",
                        gMetroData.rmsvoltage / 1000.0,
                        gMetroData.rmscurrent / 1000.0,
                        gMetroData.powerActive / 1000.0);          
        }

        sprintf(cmdFirstLineLCD.text, "%-16.16s", temp);  

    }else{
        int32_t iTotalEnergy = gMetroData.energyActive + gDeviceState.iEnergy;
        
        iTotalEnergy = (iTotalEnergy > 0)?iTotalEnergy:0;
     

        //DEBUG_PRINT(DEBUG_MIN, "iTotalEnergy: %ld", iTotalEnergy );
        
        getVoltageEnergyString(cmdFirstLineLCD.text, gMetroData.rmsvoltage, iTotalEnergy);

    }

      //DEBUG_PRINT(DEBUG_MIN, "Display Line 1: %s", cmdFirstLineLCD.text );
      MCU_Cmd_Enqueue(CMD_DISPLAY, (uint8_t* )&cmdFirstLineLCD, sizeof(cmdFirstLineLCD));
  
}

void showTimeOnLCD()
{
    if(gHeartBeatTime.year == 0 ) return;
    char temp[32] = {0};
 
    uint32_t iEllapsedMillisAfterLastHeartBeat = millis() - gHeartBeatReceivedSystemMillis;

    convertIsoToFormattedIST(iEllapsedMillisAfterLastHeartBeat,temp,16);

    sprintf(cmdSecondLineLCD.text, "%-16.16s", temp);  
    //DEBUG_PRINT(DEBUG_MIN, "showTimeOnLCD Line 2: %s", cmdSecondLineLCD.text );
    MCU_Cmd_Enqueue(CMD_DISPLAY, (uint8_t* )&cmdSecondLineLCD, sizeof(cmdSecondLineLCD));

}

void showMeesageOnLCD(uint8_t iLine, char* message )
{
    if(iLine == 1){
      sprintf(cmdFirstLineLCD.text, "%-16.16s", message);  
      MCU_Cmd_Enqueue(CMD_DISPLAY, (uint8_t* )&cmdFirstLineLCD, sizeof(cmdFirstLineLCD));
    }else if(iLine == 2){
      sprintf(cmdSecondLineLCD.text, "%-16.16s", message);  
      MCU_Cmd_Enqueue(CMD_DISPLAY, (uint8_t* )&cmdSecondLineLCD, sizeof(cmdSecondLineLCD));
    }

}


void showChargingUpdateOnLCD()
{
    char temp[32] = {0};
    //char strDisplay[32] = {0};

    uint32_t iEnergyConsumed = (gMetroData.energyActive > 0)?gMetroData.energyActive:0 - giStartEnergy; 

    uint32_t totalSeconds = (millis() - gDeviceState.iStartTime )/ 1000;

    unsigned int hours   = totalSeconds / 3600;
    unsigned int minutes = (totalSeconds % 3600) / 60;
    unsigned int seconds = totalSeconds % 60;

    if(iEnergyConsumed > 1000000){
      sprintf(temp, "%.1fKWh %02u:%02u:%02u",
              iEnergyConsumed / 1000000.0, hours, minutes, seconds);
    }else{
      sprintf(temp, "%.1fWh %02u:%02u:%02u",
              iEnergyConsumed / 1000.0, hours, minutes, seconds);
    }

    sprintf(cmdSecondLineLCD.text, "%-16.16s", temp);  
    //DEBUG_PRINT(DEBUG_MIN, "showChargingUpdateOnLCD Line 2: %s", cmdSecondLineLCD.text );
    MCU_Cmd_Enqueue(CMD_DISPLAY, (uint8_t* )&cmdSecondLineLCD, sizeof(cmdSecondLineLCD));
}

