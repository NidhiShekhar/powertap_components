package com.drivool.iot.powertap.contract

import java.util.UUID

/** Mirrors shared/contract/powertap-contract.yaml */
object PtContract {
    // BLE — phone <-> ESP
    val SERVICE_UUID: UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
    val DATA_CHAR_UUID: UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    const val DEVICE_NAME = "PowerTapBLE"
    const val MTU = 512

    // MQTT — phone <-> cloud (pwt_fm01)
    const val TOPIC_PACKET = "pwt_fm01/packet"
    const val TOPIC_COMMAND = "pwt_fm01/command"
    fun topicCommand(deviceId: String) = "pwt_fm01/$deviceId/command"
    fun topicAck(deviceId: String) = "pwt_fm01/$deviceId/ack"
    const val MQTT_QOS = 1
}
