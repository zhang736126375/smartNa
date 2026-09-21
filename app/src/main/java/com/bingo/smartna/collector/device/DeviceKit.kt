package com.bingo.smartna.collector.device

import com.bingo.smartna.R

enum class DeviceKit(
    val id: String,
    val titleRes: Int,
    val hasEgo: Boolean,
    val scanPartRes: Int
) {
    EGO("ego", R.string.device_kit_ego, true, R.string.device_scan_part_ego),
    GRIPPER("gripper", R.string.device_kit_gripper, false, R.string.device_scan_part_gripper),
    EGO_GRIPPER("ego_gripper", R.string.device_kit_ego_gripper, true, R.string.device_scan_part_ego),
    EGO_WRIST("ego_wrist", R.string.device_kit_ego_wrist, true, R.string.device_scan_part_ego);

    companion object {
        fun fromId(id: String?): DeviceKit? = entries.find { it.id == id }
    }
}
