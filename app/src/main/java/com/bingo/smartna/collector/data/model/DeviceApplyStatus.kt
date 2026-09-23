package com.bingo.smartna.collector.data.model

enum class DeviceApplyStatus {
    NONE,
    COMPLETED;

    companion object {
        fun fromName(name: String?): DeviceApplyStatus =
            entries.find { it.name == name } ?: NONE
    }
}

enum class DeviceShipmentStatus {
    PENDING,
    RECEIVED;

    companion object {
        fun fromName(name: String?): DeviceShipmentStatus =
            entries.find { it.name == name } ?: PENDING
    }
}
