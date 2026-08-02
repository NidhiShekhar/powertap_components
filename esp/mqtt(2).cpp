#include <string.h>
/*
 * mqtt.cpp
 *
 *  Created on: 10-Jan-2026
 *      Author: Yoganathan V
 */

/* Includes ------------------------------------------------------------------*/
#include "mqtt.h"
#include "utilities.h"
#include <HardwareSerial.h>
#include <ArduinoJson.h>

StaticJsonDocument<300>           mJSONDocManager;

/* Typedef -------------------------------------------------------------------*/
char *gcmd[] = {
  "\"BootNotification\"",
  "\"Heartbeat\"",
  "\"StatusNotification\"",
  "\"MeterValues\"",
  "\"DataTransfer\"",
  "\"UpdateFirmware\"",
  "\"UpdateFirmware\"",
  "\"GetConfiguration\"",
  "\"SetConfiguration\"",
};

char *PowerTapStatus[] = {
  "\"Available\"",
  "\"Charging\"",
  "\"Faulted\"",
  "\"Finishing\"",
  "\"Reserved\"",
  "\"Unavailable\"",
};


static CommandQueue     gQueue;
static CommandEntry     *gActiveCmd    = NULL;
static TxState          gTxState       = TX_IDLE;
POWER_TAP_FLAGS         gFlags = {0};
Charger_Status          gStatus = Available;


/* Define --------------------------------------------------------------------*/

/* Macro ---------------------------------------------------------------------*/

/* Variables -----------------------------------------------------------------*/
static uint8_t      gRetryCount           = 0;
static uint32_t     gCmdStartTime         = 0;
static uint64_t     gMessageCounter       = 0x100000;
//static uint8_t      gTransactionId        = 0;
static int          gHeartBeatInterval    = 10;
static int          gMeterValueInterval   = 20;
static uint8_t      gFotaURL[DATA_SIZE]   = {0};
static uint8_t      gDataBuf[DATA_SIZE]   = {0};

uint8_t   gDeviceId[13]               = { 0 };
uint8_t   gRxBuf[MQTT_BUFFER_SIZE]    = {0};

DecodedTime gHeartBeatTime = {0};
uint32_t gHeartBeatReceivedSystemMillis = 0;


uint32_t giStartEnergy =0;


/* Function prototypes -------------------------------------------------------*/
void BootNotification_Request(uint8_t *ptr);
void HeartBeat_Request(uint8_t *ptr);
void Heartbeat_Response(uint8_t *ptr);
void StatusNotification_Request(uint8_t *ptr);
void MeterValues_Request(uint8_t *ptr);
void DataTransfer_Request(uint8_t *Ptr);
void BootNotification_Response(uint8_t *ptr);
void DataTransfer_Response(uint8_t *ptr);
void RemoteStart_Request(uint8_t *ptr);

void RemoteStop_Request(uint8_t *ptr);


void Dummy_Function(uint8_t *ptr);
void startPowerTap();
void stopPowerTap(StopReason reason);

String formatISO8601(uint64_t elapsedMillis);

Commands gMqttCmd[] = {
  { BootNotification_Request,     BootNotification_Response },
  { HeartBeat_Request,            Heartbeat_Response },
  { StatusNotification_Request,   Dummy_Function },
  { MeterValues_Request,          Dummy_Function },
  { DataTransfer_Request,         DataTransfer_Response },
  { Dummy_Function,               Dummy_Function }
};


/* Function Implementation ----------------------------------------------------*/

void handleAutoStopScenario(void){

  if(gFlags.Charging == true){
      switch(gDeviceState.iChargingMode){

        case 0:{ // Stop when Full charge (i.e load is close to zero)
            DEBUG_PRINT(DEBUG_MIN,"gMetroData.powerActive : %ld", gMetroData.powerActive);
            if((gMetroData.powerActive < 100) && (millis() - gDeviceState.iStartTime > 10000) ){ // 0.1 Wh
                stopPowerTap(STOP_REASON_NO_LOAD);
            }

        }break;

        case 1:{ // Time based

            if((gMetroData.powerActive < 100) && (millis() - gDeviceState.iStartTime > 10000) ){ // 0.1 Wh
                stopPowerTap(STOP_REASON_NO_LOAD);
            }

            DEBUG_PRINT(DEBUG_MIN,"gDeviceState.iStartTime : %ld, Elapsed Time: %ld, iTargetValue =%ld", 
            gDeviceState.iStartTime, millis() - gDeviceState.iStartTime, gDeviceState.iTargetValue );
            if(millis() - gDeviceState.iStartTime  >=  gDeviceState.iTargetValue){ // milli sec
                stopPowerTap(STOP_REASON_TIMEOUT);
            }

        }break;

        case 2:{ // Wh bashed 
            if((gMetroData.powerActive < 100) && (millis() - gDeviceState.iStartTime > 10000) ){ // 0.1 Wh
                stopPowerTap(STOP_REASON_NO_LOAD);
            }
            
            DEBUG_PRINT(DEBUG_MIN,"gDeviceState.iTargetValue : %ld, gMetroData.energyActive: %ld",gDeviceState.iTargetValue , gMetroData.energyActive );
            if(gMetroData.energyActive  >=  gDeviceState.iTargetValue){ // milli sec
                stopPowerTap(STOP_REASON_COMPLETED);
            }

        }break;

        default:{
          
            DEBUG_PRINT(DEBUG_MIN,"gDeviceState.iChargingMode : %d",gDeviceState.iChargingMode );
        }

      }
  }

}

void readDeviceMACAddress(void)
{
  uint8_t baseMac[6];
  esp_err_t ret = esp_wifi_get_mac(WIFI_IF_STA, baseMac);
  if (ret == ESP_OK) {
    sprintf((char *)gDeviceId, "%02x%02x%02x%02x%02x%02x",
                  baseMac[0], baseMac[1], baseMac[2],
                  baseMac[3], baseMac[4], baseMac[5]);
  } else {
    strcpy((char *)gDeviceId, "123242526272");
  }
  DEBUG_PRINT(DEBUG_MIN, "DeviceId: %02x%02x%02x%02x%02x%02x",
                  baseMac[0], baseMac[1], baseMac[2],
                  baseMac[3], baseMac[4], baseMac[5]);
}


static int getJsonIntValue(const char *json, const char *key)
{
  if (!json || !key) return 0;

  char pattern[64];
  snprintf(pattern, sizeof(pattern), "\"%s\":", key);

  char *start = strstr(json, pattern);
  if (start) {
    start += strlen(pattern);
    while (*start == ' ' || *start == '\t') {
      start++;
    }
    if (*start == '"') start++;
    return atoi(start);
  }
  return 0;
}


static size_t getJsonStringField(const char *json, const char *key, char *out, size_t outSize)
{
  if (!json || !key || !out || outSize == 0) {
    if (out && outSize > 0) out[0] = '\0';
    return 0;
  }

  char pattern[64];
  snprintf(pattern, sizeof(pattern), "\"%s\"", key);
  char *start = strstr(json, pattern);
  if (!start) {
    *out = '\0';
    return 0;
  }

  start += strlen(pattern);
  while (*start && (*start == ' ' || *start == '\t' || *start == ':')) {
    start++;
  }

  if (*start != '"') {
    *out = '\0';
    return 0;
  }

  start++;
  char *end = strchr(start, '"');
  if (!end) {
    *out = '\0';
    return 0;
  }

  size_t len = (size_t)(end - start);
  if (len >= outSize) len = outSize - 1;
  strncpy(out, start, len);
  out[len] = '\0';
  return len;
}


static bool DecodeCurrentTime(const char *frame, DecodedTime *outTime)
{
  if (!frame) return false;

  char buf[64] = { 0 };
  getJsonStringField(frame, "currentTime", buf, sizeof(buf));

  if (buf[0] == '\0') {
    return false;
  }

  if (outTime) {
    int y, m, d, h, min, s, ms = 0;
    int parsed = sscanf(buf, "%d-%d-%dT%d:%d:%d.%dZ",
                        &y, &m, &d, &h, &min, &s, &ms);

    if (parsed >= 6) {
      outTime->year = y;
      outTime->month = m;
      outTime->day = d;
      outTime->hour = h;
      outTime->minute = min;
      outTime->second = s;
      outTime->millisecond = (parsed == 7) ? ms : 0;
    } else {
      return false;
    }
  }

  return true;
}

static bool ParseCommandInt(const char *input, const char *command, const char *key, int *outValue)
{
    if (!input || !command || !outValue)
        return false;

    const char *p = input;

    /* Skip leading spaces */
    while (isspace((unsigned char)*p)) p++;

    /* Match command */
    size_t cmdLen = strlen(command);
    if (strncmp(p, command, cmdLen) != 0)
        return false;
    p += cmdLen;

    /* Skip separators (space, comma, tab) */
    while (*p == ' ' || *p == ',' || *p == '\t') p++;

    /* Match key if provided */
    if (key && *key) {
        size_t keyLen = strlen(key);
        if (strncmp(p, key, keyLen) != 0)
            return false;
        p += keyLen;

        /* Skip separators again */
        while (*p == ' ' || *p == ',' || *p == '\t') p++;
    }

    /* Parse integer */
    if (*p == '\0')  // nothing left
        return false;

    char *endptr = NULL;
    long val = strtol(p, &endptr, 10);

    if (endptr == p)                    // no digits
        return false;
    if (*endptr != '\0' && !isspace((unsigned char)*endptr))
        return false;

    *outValue = (int)val;
    return true;
}

static bool ParseSetStringCommand(const char *input, const char *key, char *outStr, size_t outSize)
{
    if (!input || !key || !outStr || outSize == 0)
        return false;

    const char *p = input;

    /* Skip leading spaces */
    while (*p == ' ' || *p == '\t') p++;

    /* Expect SET */
    if (strncmp(p, "SET", 3) != 0)
        return false;

    p += 3;

    /* Skip separators */
    while (*p == ' ' || *p == ',' || *p == '\t') p++;

    /* Match key */
    size_t keyLen = strlen(key);
    if (strncmp(p, key, keyLen) != 0)
        return false;

    p += keyLen;

    /* Skip separators */
    while (*p == ' ' || *p == ',' || *p == '\t') p++;

    /* Remaining must be string */
    if (*p == '\0')
        return false;

    /* Copy safely */
    size_t len = strlen(p);
    if (len >= outSize)
        len = outSize - 1;

    memcpy(outStr, p, len);
    outStr[len] = '\0';

    return true;
}

bool DecodeLcdDisCommand(const char *input, LcdDisCommand *cmd)
{
    if (!input || !cmd)
        return false;

    memset(cmd, 0, sizeof(LcdDisCommand));

    /* Must start with DIS */
    while (*input == ' ' || *input == '\t') input++;
    if (strncmp(input, "DIS", 3) != 0)
        return false;

    input += 3;

    /* Skip separators */
    while (*input == ' ' || *input == ',' || *input == '\t') input++;

    char *end;

    /* Parse row */
    long r = strtol(input, &end, 10);
    if (input == end)
        return false;
    input = end;

    while (*input == ' ' || *input == ',' || *input == '\t') input++;

    /* Parse column */
    long c = strtol(input, &end, 10);
    if (input == end)
        return false;
    input = end;

    while (*input == ' ' || *input == ',' || *input == '\t') input++;

    /* Text must exist */
    if (*input == '\0')
        return false;

    size_t len = strlen(input);
    if (len > 16)
        return false;

    /* Validation */
    if (r < 0 || r >= LCD_ROWS)
        return false;

    if (c < 0 || c >= LCD_COLS)
        return false;

    if (len > (LCD_COLS - c))
        return false;

    cmd->row    = (uint8_t)r;
    cmd->column = (uint8_t)c;
    strncpy(cmd->text, input, sizeof(cmd->text) - 1);

    return true;
}

void BootNotification_Request(uint8_t *ptr)
{
  uint32_t len = sprintf((char *)ptr, "%s%llu", "[2,\"", gMessageCounter);
  len += sprintf((char *)(ptr + len), "%s", "\",\"BootNotification\",");
  len += sprintf((char *)(ptr + len), "%s", "{\"reason\":\"PowerOn\",");
  len += sprintf((char *)(ptr + len), "%s%s", "\"HW_VER\":\"", HW_VER);
  len += sprintf((char *)(ptr + len), "%s%s", "\",\"FW_VER\":\"", FW_VER);
  len += sprintf((char *)(ptr + len), "%s", "\"},\"");
  len += sprintf((char *)(ptr + len), "%s", gDeviceId);
  len += sprintf((char *)(ptr + len), "%s", "\"]");
}

void HeartBeat_Request(uint8_t *ptr)
{
  uint32_t len = sprintf((char *)ptr, "%s%llu", "[2,\"", gMessageCounter);
  len += sprintf((char *)(ptr + len), "%s", "\",\"Heartbeat\",{");
  len += sprintf((char *)(ptr + len), "%s%d,", "\"v\":", gMetroData.rmsvoltage);
  len += sprintf((char *)(ptr + len), "%s%d,", "\"c\":", gMetroData.rmscurrent);
  len += sprintf((char *)(ptr + len), "%s%d,", "\"p\":", gMetroData.powerActive);
  len += sprintf((char *)(ptr + len), "%s%d,", "\"e\":", gMetroData.energyActive + gDeviceState.iEnergy);
  len += sprintf((char *)(ptr + len), "%s%d", "\"f\":", 50);
  len += sprintf((char *)(ptr + len), "%s", "},\"");
  len += sprintf((char *)(ptr + len), "%s", gDeviceId);
  len += sprintf((char *)(ptr + len), "%s", "\"]");
}

void StatusNotification_Request(uint8_t *ptr)
{
  uint32_t len = sprintf((char *)ptr, "%s%llu", "[2,\"", gMessageCounter);
  len += sprintf((char *)(ptr + len), "%s", "\",\"StatusNotification\",");
  len += sprintf((char *)(ptr + len), "%s", "{\"status\":");
  len += sprintf((char *)(ptr + len), "%s", PowerTapStatus[gStatus]);
  len += sprintf((char *)(ptr + len), "%s", ",\"errorCode\":\"");
  len += sprintf((char *)(ptr + len), "%s", "NO ERROR");
  len += sprintf((char *)(ptr + len), "%s", "\"},\"");
  len += sprintf((char *)(ptr + len), "%s", gDeviceId);
  len += sprintf((char *)(ptr + len), "%s", "\"]");
}

void MeterValues_Request(uint8_t *ptr)
{
  // //gTransactionId++;
  // uint32_t len = sprintf((char *)ptr, "%s%llu", "[2,\"", gMessageCounter);
  // len += sprintf((char *)(ptr + len), "%s", "\",\"MeterValues\",{");
  // len += sprintf((char *)(ptr + len), "%s", "\"connectorId\":\"");
  // len += sprintf((char *)(ptr + len), "%s", "1");
  // len += sprintf((char *)(ptr + len), "%s", "\",\"transactionId\":");
  // len += sprintf((char *)(ptr + len), "%s", gDeviceState.strTID);
  // len += sprintf((char *)(ptr + len), "%s", ",\"meterValue\":{");
  // len += sprintf((char *)(ptr + len), "%s%d,", "\"v\":", gMetroData.rmsvoltage);
  // len += sprintf((char *)(ptr + len), "%s%d,", "\"c\":", gMetroData.rmscurrent);
  // len += sprintf((char *)(ptr + len), "%s%d,", "\"p\":", gMetroData.powerActive);
  // len += sprintf((char *)(ptr + len), "%s%d,", "\"e\":", gMetroData.energyActive + gDeviceState.iEnergy );
  // len += sprintf((char *)(ptr + len), "%s%d", "\"f\":", 50);
  // len += sprintf((char *)(ptr + len), "%s", "}},\"");
  // len += sprintf((char *)(ptr + len), "%s", gDeviceId);
  // len += sprintf((char *)(ptr + len), "%s", "\"]");


  // 2. Create the Root Array [ ... ]
  JsonArray root = mJSONDocManager.to<JsonArray>();
  
  root.add(2);                                  // Index 0: Message Type
  root.add(String(gMessageCounter));            // Index 1: Unique ID
  root.add("MeterValues");                      // Index 2: Action

  // 3. Create the MeterValues Object { ... }
  JsonObject payload = root.createNestedObject();
  payload["connectorId"] = "1";
  payload["transactionId"] = gDeviceState.strTID;

  // 4. Create the nested meterValue Object { "v":..., "c":... }
  JsonObject meterValue = payload.createNestedObject("meterValue");
  meterValue["v"] = gMetroData.rmsvoltage;
  meterValue["c"] = gMetroData.rmscurrent;
  meterValue["p"] = gMetroData.powerActive;
  meterValue["e"] = gMetroData.energyActive + gDeviceState.iEnergy;
  meterValue["f"] = 50;

  // 5. Add Device ID at the end of the array
  root.add(gDeviceId);

  // 6. Serialize directly to your buffer (ptr)
  // serializeJson returns the number of bytes written (length)
  size_t len = serializeJson(mJSONDocManager, (char*)ptr, 512);

 // DEBUG_PRINT(DEBUG_MIN, "Serialized Packet (%d bytes): %s", len, (char*)ptr);

}

void DataTransfer_Request(uint8_t *ptr)
{
  char buf[DATA_SIZE];
  bool response = 0;
  getJsonStringField((const char *)ptr, "data", buf, sizeof(buf));
  memset(gDataBuf, 0, DATA_SIZE);

  if (strstr((char *)buf, "GET")) {
    if ((strstr((char *)buf, "RCONF")) || (strstr((char *)buf, "INFO"))) {
      sprintf((char*)gDataBuf, "{\"HWVER\":\"%s\",\"HWVER\":\"%s\"}", HW_VER, FW_VER);
    }
    else if (strstr((char *)buf, "T1")) {
      sprintf((char*)gDataBuf, "{\"HeartBeatInterval\":%u}", gHeartBeatInterval);
    }
    else if (strstr((char *)buf, "T2")) {
      sprintf((char*)gDataBuf, "{\"MeterValueInterval\":%u}", gMeterValueInterval);
    }
    else if (strstr((char *)buf, "T3")) {
      sprintf((char*)gDataBuf, "{\"gMeteringInterval\":%u}", gMeteringInterval);
    }
    else if (strstr((char *)buf, "DBG")) {
      sprintf((char*)gDataBuf, "{\"DBG\":\"%u\"}", gDebugLevel);
    }
    else if (strstr((char *)buf, "FOTA-URL")) {
      sprintf((char*)gDataBuf, "{\"FOTA-URL\":\"%s\"}", gFotaURL);
    }

  }else if (strstr((char *)buf, "SET")) {
    if (strstr((char *)buf, "T1")) {
      if(ParseCommandInt(buf, "SET", "T1", &gHeartBeatInterval))
        response = 1;
    }
    else if (strstr((char *)buf, "T2")) {
      if(ParseCommandInt(buf, "SET", "T2", &gMeterValueInterval))
        response = 1;
    }
    else if (strstr((char *)buf, "T3")) {
      if(ParseCommandInt(buf, "SET", "T3", &gMeteringInterval))
        response = 1;
    }
    else if (strstr((char *)buf, "DBG")) {
      if(ParseCommandInt(buf, "SET", "DBG", &gDebugLevel))
        response = 1;
    }
    else if (strstr((char *)buf, "FOTA-URL")) {
      if(ParseSetStringCommand(buf, "FOTA-URL", (char *)gFotaURL, DATA_SIZE))
        response = 1;
    }
  }
  else if (strstr((char *)buf, "FOTA-START")) {
    if (strnlen((const char *)gFotaURL, DATA_SIZE))
      response = 1;
    else
      strncpy((char*)gDataBuf, "\"Configure URL before Triggerring FOTA\"", DATA_SIZE);
  }
  // else if (strstr((char *)buf, "DIS")) {
  //   LcdDisCommand lcdCmd;
  //   if (DecodeLcdDisCommand(buf, &lcdCmd)) {
  //     MCU_Cmd_Enqueue(CMD_DISPLAY, (const uint8_t*)&lcdCmd, sizeof(lcdCmd));
  //     response = 1;
  //   }
  // }
  // else if (strstr((char *)buf, "REL")) {
  //   int value = 0;
  //   if (ParseCommandInt(buf, "REL", NULL, &value))
  //     response = 1;
  //   if(value == 1) {
  //     startPowerTap();
  //   }
  //   else if(value == 0) {
  //     stopPowerTap(STOP_REASON_REMOTE);
  //   }
  //   else
  //     response = 0;
  // }
  else if (strstr((char *)buf, "CLB")) {
    int value = 0;
    char Calibbuf[32] = {0};
    if (ParseCommandInt(buf, "CLB", "V", &value)) {
      response = 1;
      sprintf(Calibbuf,"V %d", value);
      MCU_Cmd_Enqueue(CMD_CALIB, (const uint8_t *)Calibbuf, strlen(Calibbuf));
      D_PRINT("Sending CLB Param %s", Calibbuf);
    }
    else if (ParseCommandInt(buf, "CLB", "I", &value)) {
      response = 1;
      sprintf(Calibbuf,"I %d", value);
      MCU_Cmd_Enqueue(CMD_CALIB, (const uint8_t *)Calibbuf, strlen(Calibbuf));
    }
    DEBUG_PRINT(DEBUG_FULL, "CALIB Cmd: %d %s ", strlen(Calibbuf), Calibbuf);
  }
  else if (strstr((char *)buf, "REBOOT")) {
    response = 1;
    MCU_Cmd_Enqueue(CMD_RESET, (const uint8_t *)"RST", 3);
  }

  if(!strnlen((const char *)gDataBuf, DATA_SIZE))
    strncpy((char*)gDataBuf, ((response) ? "\"OK\"": "\"Invalid Command\""), DATA_SIZE);

  DEBUG_PRINT(DEBUG_FULL, "Cmd: %s", buf);
  DEBUG_PRINT(DEBUG_FULL, "Res: %s", gDataBuf);
}

void BootNotification_Response(uint8_t *ptr)
{
  if (strstr((char *)ptr, "Accepted")) {
    int interval = getJsonIntValue((const char *)ptr, "interval");
    if (interval > 0) {
      gHeartBeatInterval = (uint8_t)interval;
      DEBUG_PRINT(DEBUG_FULL, "BootNotification Accepted, interval=%d", gHeartBeatInterval);
    } else {
      DEBUG_PRINT(DEBUG_FULL, "BootNotification Accepted, but no interval found");
    }
  } else {
    DEBUG_PRINT(DEBUG_FULL, "BootNotification not accepted");
  }
}

void Heartbeat_Response(uint8_t *ptr)
{
  char buf[64];
  size_t len = getJsonStringField((const char *)ptr, "currentTime", buf, sizeof(buf));
  MCU_Cmd_Enqueue(CMD_HEARTBEAT, (const uint8_t*)&buf[0], len);


  if (DecodeCurrentTime((const char*)ptr, &gHeartBeatTime)) {
    gHeartBeatReceivedSystemMillis = millis();
    DEBUG_PRINT(DEBUG_FULL, "Parsed Heartbeat currentTime => %04d-%02d-%02d %02d:%02d:%02d.%03d",
                    gHeartBeatTime.year, gHeartBeatTime.month, gHeartBeatTime.day, gHeartBeatTime.hour, gHeartBeatTime.minute, gHeartBeatTime.second, gHeartBeatTime.millisecond);
  } else {
    DEBUG_PRINT(DEBUG_FULL, "Parsed Heartbeat currentTime Failed");
  }
}

void DataTransfer_Response(uint8_t *ptr)
{
  uint32_t len = sprintf((char *)ptr, "%s%llu", "[3,\"", gMessageCounter);
  len += sprintf((char *)(ptr + len), "%s", "\",\"DataTransfer\",{\"response\":");
  len += sprintf((char *)(ptr + len), "%s", gDataBuf);
  len += sprintf((char *)(ptr + len), "%s", "},\"");
  len += sprintf((char *)(ptr + len), "%s", gDeviceId);
  len += sprintf((char *)(ptr + len), "%s", "\"]");
}


void RemoteStart_Request(uint8_t *ptr)
{
  DEBUG_PRINT(DEBUG_MIN, "RemoteStart_Request %s", ptr);
}

void RemoteStop_Request(uint8_t *ptr)
{
  DEBUG_PRINT(DEBUG_MIN, "RemoteStop_Request %s", ptr);
}


String formatISO8601(uint64_t elapsedMillis) {
  // 1. Fill a standard tm struct with your heartbeat time
  struct tm t = {0};
  t.tm_year = gHeartBeatTime.year - 1900; // tm_year is years since 1900
  t.tm_mon  = gHeartBeatTime.month - 1;   // tm_mon is 0-11
  t.tm_mday = gHeartBeatTime.day;
  t.tm_hour = gHeartBeatTime.hour;
  t.tm_min  = gHeartBeatTime.minute;
  t.tm_sec  = gHeartBeatTime.second;

  // 2. Convert heartbeat to epoch seconds and add elapsed seconds
  time_t baseSeconds = mktime(&t);
  uint32_t extraSeconds = (uint32_t)(elapsedMillis / 1000);
  uint32_t remainingMillis = (uint32_t)(elapsedMillis % 1000);
  
  time_t finalSeconds = baseSeconds + extraSeconds;

  // 3. Handle Millisecond carry-over (optional)
  // If your gHeartBeatTime.millisecond + remainingMillis > 1000, 
  // mktime doesn't see that, but for standard ISO8601, we usually just focus on seconds.
  
  // 4. Convert back to broken-down time
  struct tm *finalTime = gmtime(&finalSeconds);

  // 5. Format into the buffer
  char isoBuffer[30];
  snprintf(isoBuffer, sizeof(isoBuffer), 
           "%04d-%02d-%02dT%02d:%02d:%02dZ",
           finalTime->tm_year + 1900,
           finalTime->tm_mon + 1,
           finalTime->tm_mday,
           finalTime->tm_hour,
           finalTime->tm_min,
           finalTime->tm_sec);

  DEBUG_PRINT(DEBUG_MIN, "New ISO Time: %s", isoBuffer);
  return String(isoBuffer);
}

String composeRemoteStartResponse()
{
  JsonArray arrPacket = mJSONDocManager.to<JsonArray>();

  // 2. Add simple values to the array
  arrPacket.add(2);                        // Index 0
  arrPacket.add(gMessageCounter);          // Index 1
  arrPacket.add("StartTransaction");            // Index 2

  // 3. Create and add a nested object at Index 3
  JsonObject payload = arrPacket.createNestedObject();
    payload["transactionId"] =  gDeviceState.strTID;
    payload["meterStart"] = gDeviceState.iStartEnergy;    
    payload["timestamp"] = formatISO8601(millis() - gHeartBeatReceivedSystemMillis);              

  arrPacket.add(gDeviceId);   // Index 4

  String output;
  serializeJson(mJSONDocManager, output);

  return output;
}

String composeRemoteStopResponse()
{
  JsonArray arrPacket = mJSONDocManager.to<JsonArray>();

  // 2. Add simple values to the array
  arrPacket.add(2);                        // Index 0
  arrPacket.add(gMessageCounter);          // Index 1
  arrPacket.add("StopTransaction");            // Index 2

  // 3. Create and add a nested object at Index 3
  JsonObject payload = arrPacket.createNestedObject();
    payload["transactionId"] =  (gDeviceState.strTID == 0) ?  "" : gDeviceState.strTID ;
    payload["meterStop"] = gMetroData.energyActive + gDeviceState.iEnergy;    
    payload["reason"] =  gDeviceState.iStopReason;
    payload["timestamp"] = formatISO8601(millis() - gHeartBeatReceivedSystemMillis);              

  arrPacket.add(gDeviceId);   // Index 4

  String output;
  serializeJson(mJSONDocManager, output);

  return output;
}


void Dummy_Function(uint8_t *ptr)
{
}

static CommandEntry* RadioCmd_Peek(void)
{
  if (gQueue.count == 0)
    return NULL;

  return &gQueue.buf[gQueue.head];
}

bool RadioCmd_Enqueue(CommandInfo cmd)
{
  if (gQueue.count >= QUEUE_SIZE)
    return false;

  gQueue.buf[gQueue.tail].cmd = cmd;
  gQueue.buf[gQueue.tail].messageId = 0;
  gQueue.tail = (gQueue.tail + 1) % QUEUE_SIZE;
  gQueue.count++;

  return true;
}

bool RadioCmd_Dequeue(void)
{
  if (gQueue.count == 0)
    return false;

  gQueue.head = (gQueue.head + 1) % QUEUE_SIZE;
  gQueue.count--;

  return true;
}

static void Tx_Start(void)
{
  if ((gTxState != TX_IDLE) || (gQueue.count == 0))
    return;

  gActiveCmd = RadioCmd_Peek();
  gRetryCount = 0;

  gActiveCmd->messageId = ++gMessageCounter;
  gCmdStartTime = millis();
  gTxState = TX_WAITING_RESPONSE;

  if (gMqttCmd[gActiveCmd->cmd].request) {
    uint8_t txBuf[MQTT_BUFFER_SIZE] = {0};
    gMqttCmd[gActiveCmd->cmd].request(txBuf);
    SendData(txBuf);

  } else {
    DEBUG_PRINT(DEBUG_MIN, "No request handler for Cmd=%d", gActiveCmd->cmd);
    RadioCmd_Dequeue();
  }
}

static void Transmit_TaskHandler(void)
{
  if (gTxState == TX_IDLE) {
    Tx_Start();
    return;
  }

  if ((millis() - gCmdStartTime) >= RETRY_DELAY_MS)
  {
    gRetryCount++;
    if (gRetryCount >= MAX_RETRY_COUNT) {
      DEBUG_PRINT(DEBUG_MIN, "/--------------------------------------------------------------/");
      DEBUG_PRINT(DEBUG_MIN, "System reboot: maximum retry limit reached.");
      DEBUG_PRINT(DEBUG_MIN, "/--------------------------------------------------------------/");
      DEBUG_PRINT(DEBUG_MIN, "\n\n\n");
      delay(2000);
      ESP.restart();
    } else {
      uint8_t txBuf[MQTT_BUFFER_SIZE] = {0};
      gCmdStartTime = millis();
      gMqttCmd[gActiveCmd->cmd].request(txBuf);
      SendData(txBuf);
      DEBUG_PRINT(DEBUG_MIN, "TX Message => %s", (char *)txBuf);
      DEBUG_PRINT(DEBUG_MIN, "Retry Count => %d", (char *)gRetryCount);

    }
  }
}

static uint64_t GetMessageId(const char *json)
{
  const char *p = strchr(json, '"');
  if (!p) 
    return 0;

  return strtoull(p + 1, NULL, 10);
}

static uint8_t GetFrameType(const char *json)
{
  const char *p = strchr(json, '[');
  if (!p)
    return 0;

  return (uint8_t)(p[1] - '0');
}


void startPowerTap(){

      gFlags.Relay = true;
      gMeteringInterval = 3000; /* get Meetering value every 3 seconds when relay is ON */
      MCU_Cmd_Enqueue(CMD_RELAY, (const uint8_t *)"1", 1);
      giStartEnergy =  gMetroData.energyActive;
      gDeviceState.iStartTime = millis();
      gDeviceState.iStartEnergy = gMetroData.energyActive + gDeviceState.iEnergy;      
}


void stopPowerTap(StopReason reason){
      gFlags.Relay = false;
      gMeteringInterval = 10000; /* get Meetering value every 10 seconds when relay is OFF */
      MCU_Cmd_Enqueue(CMD_RELAY, (const uint8_t *)"0", 1);
      gDeviceState.iStopReason = reason ;   
}

static void Receive_TaskHandler(void)
{

    deserializeJson(mJSONDocManager, (char *)gRxBuf);
    JsonArray arrPacket = mJSONDocManager.as<JsonArray>();

    //DEBUG_PRINT(DEBUG_MIN, "Parsed JSON array size %d", arrPacket.size());
    uint8_t frameType = arrPacket[0];

    uint64_t msgId = 0L;

    if (sscanf(arrPacket[1], "%lld", &msgId) == 1) { // Check if one item was successfully read
         // DEBUG_PRINT(DEBUG_MIN, "msgId %lld, frameType: %d", msgId, frameType ); 
    }


  DEBUG_PRINT(DEBUG_MIN, "msgId %lld, frameType: %d ", msgId, frameType); 

  switch (frameType)
  {
    case COMMAND_RESPONSE:
      if (msgId != gActiveCmd->messageId) {
        DEBUG_PRINT(DEBUG_MIN, "RX msgId mismatch RX=%llu EXP=%llu",
                          msgId, gActiveCmd->messageId);
        return;
      }

      if (gMqttCmd[gActiveCmd->cmd].response) {
        gMqttCmd[gActiveCmd->cmd].response(gRxBuf);
      }
      RadioCmd_Dequeue();
      gActiveCmd = NULL;
      gTxState = TX_IDLE;
      gRetryCount = 0;
    break;

    case COMMAND_ERROR:
      DEBUG_PRINT(DEBUG_MIN, "Server ERROR => %s", gRxBuf);
      if ((gActiveCmd) && msgId == gActiveCmd->messageId) {
        RadioCmd_Dequeue();
        gActiveCmd = NULL;
        gTxState = TX_IDLE;
        gRetryCount = 0;
      }
    break;

    case COMMAND_REQUEST:

    const char* action    = arrPacket[2];          // "RemoteStart" , "DataTransfer", "RemoteStop" etc

    if(strcmp(action, "RemoteStart" ) == 0){

      // 5. Access the nested object at index 3
      JsonObject payload    = arrPacket[3];
      const char* tid       = payload["tid"]|"";              // "T1771664898282"
      const char* mode      = payload["mode"]|"full";
      const bool isARC = payload["arc"] | true;  // Auto Resume Charging

      DEBUG_PRINT(DEBUG_MIN, "tid %s, mode: %s, ARC = %d", tid, mode, isARC ); 

      if(strlen(tid) == 0){
        // Missing Transaction id
        return;
      } 
      //TBD Put code to make Relay on
      gDeviceState.isARC = true;
      strcpy(gDeviceState.strTID,tid);
      if(strcmp(mode, "full" ) == 0){
        gDeviceState.iChargingMode = 0;
        DEBUG_PRINT(DEBUG_MIN, "tid %s, mode: %s", tid, mode ); 
      }else if(strcmp(mode, "time" ) == 0){
        DEBUG_PRINT(DEBUG_MIN, "tid %s, mode: %s, time:%d", tid, mode, payload["time"] ); 
        gDeviceState.iChargingMode = 1;
        gDeviceState.iTargetValue = payload["time"];
        gDeviceState.iTargetValue *= 1000; // Converting seconds to milli seconds
      }else if(strcmp(mode, "energy" ) == 0){
        DEBUG_PRINT(DEBUG_MIN, "tid %s, mode: %s, energy:%d", tid, mode, payload["units"] ); 
        gDeviceState.iTargetValue = payload["energy"];
        gDeviceState.iChargingMode = 2;
      }

      startPowerTap();
      
    }
    else if(strcmp(action, "RemoteStop" ) == 0){
         
      JsonObject payload    = arrPacket[3];
      const char* tid       = payload["tid"];  
               
      stopPowerTap(STOP_REASON_REMOTE);

    }
    else if(strcmp(action, "DataTransfer" ) == 0){

      if (gMqttCmd[DataTransfer].request) {
        gMqttCmd[DataTransfer].request(gRxBuf);
      }

      uint8_t txBuf[MQTT_BUFFER_SIZE] = {0};
      if (gMqttCmd[DataTransfer].response) {
        gMqttCmd[DataTransfer].response(txBuf);
      }
      SendData(txBuf); // Sending Data to MQTT Server

    }
  

    break;
  }
}

void Wireless_Communication_Handler(void)
{
  if (CheckReceiverStatus() == true)
  {
    Receive_TaskHandler();
  }

  Transmit_TaskHandler();

  static uint32_t lastmilli;
  uint32_t now = millis();

  if(gFlags.Relay == true) {
    static uint32_t lastmetervalues;
    if ((now - lastmetervalues) >= (gMeterValueInterval * MILLI_SECONDS)) {
      RadioCmd_Enqueue(MeterValues);
      lastmetervalues = now;
    }
  }else{ // Hearbeat is sent when charging is not happening
    if ((now - lastmilli) >= (gHeartBeatInterval * MILLI_SECONDS)) {
      RadioCmd_Enqueue(HeartBeat);
      lastmilli = now;
    }
  }
}

void performFotaUpdate(void)
{
  WiFiClient otaclient;
  HTTPClient http;

  DEBUG_PRINT(DEBUG_MIN, "Checking for firmware updates...");
  Serial1.println("FOTA Starting");
  
  if (http.begin(otaclient, (const char *)gFotaURL)) {
    int httpCode = http.GET();
    if (httpCode == HTTP_CODE_OK) {
      int contentLength = http.getSize();
      DEBUG_PRINT(DEBUG_MIN, "Got HTTP status code %d, content length %d", httpCode, contentLength);
      bool updateSuccess = false;
      if (contentLength > 0) {
        if (Update.begin(contentLength)) {
          DEBUG_PRINT(DEBUG_MIN, "Starting OTA update...");
          Serial1.println("FOTA Downloading");
          size_t written = Update.writeStream(otaclient);
          if (written == contentLength) {
            DEBUG_PRINT(DEBUG_MIN, "OTA write complete.");
          } else {
            DEBUG_PRINT(DEBUG_MIN, "OTA write failed. Wrote %d of %d bytes.", written, contentLength);
          }

          if (Update.end()) {
            DEBUG_PRINT(DEBUG_MIN, "OTA update finished. Rebooting...");
            if (Update.isFinished()) {
              Serial1.println("FOTA Success");
              DEBUG_PRINT(DEBUG_MIN, "/--------------------------------------------------------------/");
              DEBUG_PRINT(DEBUG_MIN, "System reboot: FOTA Update successfully finished");
              DEBUG_PRINT(DEBUG_MIN, "/--------------------------------------------------------------/");
              DEBUG_PRINT(DEBUG_MIN, "\n\n\n");
              delay(2000);
              ESP.restart();
            } else {
              DEBUG_PRINT(DEBUG_MIN, "Update not finished? Something went wrong!");
              Serial1.println("FOTA Download Error");
            }
          } else {
            DEBUG_PRINT(DEBUG_MIN, "Update failed. Error: %d", Update.getError());
          }
        } else {
          DEBUG_PRINT(DEBUG_MIN, "Not enough space to begin OTA update.");
        }
      } else {
        DEBUG_PRINT(DEBUG_MIN, "No new firmware available.");
      }
    } else {
      DEBUG_PRINT(DEBUG_MIN, "HTTP GET failed. Error: %s", http.errorToString(httpCode).c_str());
    }
  } else {
    DEBUG_PRINT(DEBUG_MIN, "HTTP connection failed.");
  }
}
