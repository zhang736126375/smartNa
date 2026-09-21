package com.bingo.smartna.collector.data.model

/** 三角色：众包 / 数采员 / 小组长。 */
enum class UserRole {
    CROWD,
    STAFF,
    LEAD;

    companion object {
        fun fromId(id: String?): UserRole = entries.find { it.name == id } ?: CROWD

        fun fromIdOrNull(id: String?): UserRole? = entries.find { it.name == id }
    }
}
