/*
 * uart.cpp
 *
 *  Created on: 10-Jan-2026
 *      Author: Yoganathan V
 */

/* Includes ------------------------------------------------------------------*/
#include "uart.h"
#include "mqtt.h"
#include "utilities.h"
#include "stdio.h"
#include <HardwareSerial.h>

/* Typedef -------------------------------------------------------------------*/
metroFactorials_t     gMetroFact  = {0};
metroData_t           gMetroData  = {0};
static UART_STRUCT    gMcu        = {0};
static CmdQueue       gCmdQueue   = {0};

/* Define --------------------------------------------------------------------*/

/* Macro ---------------------------------------------------------------------*/

/* Variables -----------------------------------------------------------------*/
int          gMeteringInterval    = 8000;
int          gDebugLevel          = 1;

const char *Command[] = { "NA", "HEARTBEAT", "RELAY", "DISPLAY", "CALIB", "RESET", "ESP", "EM", "FACTORIALS", "UNKNOWN" };
const char *response[] = { "OK", "INVALID", "BUSY", "ERROR", "UNKNOWN" };

/* Function prototypes -------------------------------------------------------*/

/* Function Implementation ----------------------------------------------------*/



bool MCU_Cmd_Enqueue(uint8_t cmd, const uint8_t *payload, uint8_t length)
{
  if(cmd == CMD_DISPLAY){
      LcdDisCommand *pLCDCmd = (LcdDisCommand* )payload;
      //D_PRINT("LCD Message: %d , %s", pLCDCmd->row, pLCDCmd->text );
  }
  if (gCmdQueue.count >= CMD_QUEUE_DEPTH) {
    Serial.print("Q!");
    return false;
  }

  if (length > RX_BUFFER_SIZE || length <= 0)
    return false;

  uint8_t *buf = NULL;
  if (length > 0) {
    buf = (uint8_t *)malloc(length);
    if (!buf) {
      Serial.print("M!");
      return false;
    }
    memcpy(buf, payload, length);
  }

  CmdNode *n = &gCmdQueue.nodes[gCmdQueue.tail];
  n->cmd = cmd;
  n->length = length;
  n->data = buf;

  gCmdQueue.tail = (gCmdQueue.tail + 1) % CMD_QUEUE_DEPTH;
  gCmdQueue.count++;
  return true;
}

static CmdNode *MCU_Cmd_GetNode(void)
{
  if (gCmdQueue.count == 0)
    return NULL;

  return &gCmdQueue.nodes[gCmdQueue.head];
}

static bool MCU_Cmd_Dequeue(void)
{
  if (gCmdQueue.count == 0)
    return false;

  CmdNode *n = &gCmdQueue.nodes[gCmdQueue.head];
  if (n->data) {
    free(n->data);
    n->data = NULL;
  }

  n->length = 0;
  n->cmd = 0;
  gCmdQueue.head = (gCmdQueue.head + 1) % CMD_QUEUE_DEPTH;
  gCmdQueue.count--;
  return true;
}

static uint8_t Calc_Checksum(uint8_t *buf, uint16_t len)
{
  uint8_t xorVal = 0x00;
  for (uint16_t i = 0; i < len; i++) {
    xorVal ^= buf[i];
  }
  return (xorVal ^ 0x5A);
}

static void SendFrame(const CmdNode *node)
{
  if (!node)
    return;

  uint8_t frame[1 + 1 + RX_BUFFER_SIZE + 1 + 1];
  uint16_t idx = 0;

  frame[idx++] = node->cmd;
  frame[idx++] = node->length;
  if (node->length && node->data) {
    memcpy(&frame[idx], node->data, node->length);
    idx += node->length;
  }

  frame[idx++] = Calc_Checksum(frame, idx);
  frame[idx++] = '\n';
  Serial1.write(frame, idx);
  DEBUG_PRINT(DEBUG_FULL, "MCU Cmd: %s %02X %s", Command[node->cmd], idx, frame);
}

static bool DecodeUartResponse(uint8_t *buf, uint16_t bufLen, UartResponse *out)
{
  if ((!buf) || (!out) || (bufLen < 5))
    return false;

  if (buf[bufLen - 1] != '\n')
    return false;

  uint8_t len = buf[1];
  if (bufLen != (uint16_t)(2 + len + 2))
    return false;

  uint8_t rxCrc = buf[2 + len];
  uint8_t calc  = Calc_Checksum(buf, 2 + len);
  if (rxCrc != calc)
    return false;

  out->cmd    = buf[0];
  out->status = (RespStatus)buf[2];
  out->payloadLen = len - 1;
  out->payload    = (out->payloadLen > 0) ? &buf[3] : NULL;

  return true;
}

static RespStatus UART_Response_Handle(uint32_t timeout)
{
  static uint32_t expireTime = 0;
	static bool waiting = false;
  RespStatus status;

	if(gMcu.rxFlag)
  {
    UartResponse resp = {0};    
    if (DecodeUartResponse(gMcu.rxBuffer, gMcu.rxCount, &resp))
    {
      
      if(resp.cmd == CMD_EM)
      {
        if((resp.status == RESP_OK) && (resp.payloadLen == sizeof(metroData_t)))
        {
          memcpy(&gMetroData, resp.payload, sizeof(metroData_t));
          //DEBUG_PRINT(DEBUG_MIN, "MCU Response -> CMD: %s, STATUS: %s", Command[resp.cmd], response[resp.status]);
          //D_PRINT( "P Active   : %ld",  (long)gMetroData.powerActive);
          DEBUG_PRINT(DEBUG_FULL, "P Reactive : %ld",  (long)gMetroData.powerReactive);
          DEBUG_PRINT(DEBUG_FULL, "P Apparent : %ld",  (long)gMetroData.powerApparent);

          DEBUG_PRINT(DEBUG_FULL, "E Active   : %ld",  (long)gMetroData.energyActive);
          DEBUG_PRINT(DEBUG_FULL, "E Reactive : %ld",  (long)gMetroData.energyReactive);
          DEBUG_PRINT(DEBUG_FULL, "E Apparent : %ld",  (long)gMetroData.energyApparent);

          DEBUG_PRINT(DEBUG_FULL, "RMS Voltage: %lu", (unsigned long)gMetroData.rmsvoltage);
          //D_PRINT( "Current: %lu", (unsigned long)gMetroData.rmscurrent);
          handleAutoStopScenario();
          status = RESP_OK;
        }
        else {
          DEBUG_PRINT(DEBUG_MIN, "CMD_EM Response STATUS: %s len %d %d", response[resp.status], resp.payloadLen, sizeof(metroData_t));
          status = RESP_ERROR;
        }
      }
      else if (resp.cmd == CMD_CALIB)
      {
        if ((resp.status == RESP_OK) && (resp.payloadLen == sizeof(metroFactorials_t)))
        {
          /* Store in RAM */
          memcpy(&gMetroFact, resp.payload, sizeof(metroFactorials_t));
          writeDataToNVS(&gMetroFact, FACTORIALS_KEY, sizeof(gMetroFact));
          D_PRINT("CALIB write: V=%ld I=%ld P=%ld",
            (long)gMetroFact.voltageFact, (long)gMetroFact.currentFact, (long)gMetroFact.powerFact);
          status = RESP_OK;
        }
        else {
          DEBUG_PRINT(DEBUG_MIN, "CMD_CALIB Response STATUS: %s len %d %d", response[resp.status], resp.payloadLen, sizeof(metroFactorials_t));
          status = RESP_ERROR;
        }
      }
      else {
        //DEBUG_PRINT(DEBUG_MIN, "MCU Response -> CMD: %s [%d],  STATUS: %s", Command[resp.cmd], resp.cmd, response[resp.status]);
        status = RESP_OK;
        if(resp.cmd == CMD_RELAY){

            if(gFlags.Relay == true){
                DEBUG_PRINT(DEBUG_MIN, "Relay On");
                gDeviceState.isCharging = true;
                gFlags.Charging = true;
                String strReponse = composeRemoteStartResponse();  
                SendData((uint8_t*) strReponse.c_str());
            }else{
                DEBUG_PRINT(DEBUG_MIN, "Relay Off");
                gDeviceState.isCharging = false;
                gFlags.Charging = false;
                gDeviceState.iStopEnergy = gMetroData.energyActive + gDeviceState.iEnergy;
                String strReponse = composeRemoteStopResponse();  
                SendData((uint8_t*) strReponse.c_str());
            }
        }
      }
    }
    else {
      DEBUG_PRINT(DEBUG_MIN, "Invalid UART frame %s", gMcu.rxBuffer);
      status = RESP_ERROR;
    }
    memset(gMcu.rxBuffer, 0, gMcu.rxCount);
    gMcu.rxFlag = false;
    gMcu.rxCount = 0;
    waiting = false;
    return status;
  }

  if (!waiting) {
    expireTime = millis() + timeout;
    waiting = true;
  } else {
    if (millis() >= expireTime) {
      waiting = false;
      return RESP_TIMEOUT;
    }
  }
  return RESP_BUSY;
}

void UART_Communication_Handling(void)
{
	static ComStatus ComState = Com_Send;
	RespStatus res = RESP_BUSY;
	static uint8_t retryCnt = 10;
  CmdNode *node = NULL;
	if(ComState == Com_Send) {
    node = MCU_Cmd_GetNode();
    if(node) {
      SendFrame(node);
      ComState = Com_Waiting;
    }
	} else {
		res = UART_Response_Handle(1000);
		if(res == RESP_TIMEOUT) {
			retryCnt--;
			ComState = Com_Send;
			if(retryCnt == 0) {
        MCU_Cmd_Dequeue();
        retryCnt = 10;
			  ComState = Com_Send;
			}
		} else if((res == RESP_OK) || (res == RESP_ERROR)) {
			MCU_Cmd_Dequeue();
			retryCnt = 10;
			ComState = Com_Send;
		}
	}
  /* Query EM Values every 5 seconds */
  static uint32_t lastmilli=0;
  uint32_t now = millis();
  if ((now - lastmilli) >= gMeteringInterval) {
    MCU_Cmd_Enqueue(CMD_EM, (const uint8_t *)"EM", 2);
    lastmilli = now;
  }
}

void UART1_RxCpltCallback(uint8_t rxbyte)
{
  gMcu.rxBuffer[gMcu.rxCount++] = rxbyte;
  if (gMcu.rxCount >= RX_BUFFER_SIZE)
    gMcu.rxCount = 0;

  if(rxbyte == '\n')
    gMcu.rxFlag = true;
}
