package com.bingo.smartna.collector.data

import android.content.Context
import com.bingo.smartna.collector.data.model.DeviceApplyStatus
import com.bingo.smartna.collector.data.model.DeviceShipmentStatus
import com.bingo.smartna.collector.data.model.UserRole

/** 登录态、角色、提权申请、已连接设备。 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val phone: String?
        get() = sp.getString(KEY_PHONE, null)

    val account: String?
        get() = sp.getString(KEY_ACCOUNT, null)

    val displayName: String
        get() = account?.takeIf { it.isNotBlank() } ?: phone.orEmpty()

    val role: UserRole
        get() = UserRole.fromIdOrNull(sp.getString(KEY_ROLE, null)) ?: UserRole.CROWD

    val isLoggedIn: Boolean
        get() = !phone.isNullOrBlank() || !account.isNullOrBlank()

    val staffUpgradePending: Boolean
        get() = sp.getBoolean(KEY_UPGRADE_STAFF, false)

    val leadUpgradePending: Boolean
        get() = sp.getBoolean(KEY_UPGRADE_LEAD, false)

    val staffUpgradeArea: String
        get() = sp.getString(KEY_UPGRADE_STAFF_AREA, "").orEmpty()

    val leadUpgradeArea: String
        get() = sp.getString(KEY_UPGRADE_LEAD_AREA, "").orEmpty()

    fun savePhoneLogin(phone: String) {
        sp.edit()
            .putString(KEY_PHONE, phone)
            .remove(KEY_ACCOUNT)
            .putString(KEY_ROLE, UserRole.CROWD.name)
            .apply()
    }

    fun saveAccountLogin(account: String, role: UserRole) {
        sp.edit()
            .remove(KEY_PHONE)
            .putString(KEY_ACCOUNT, account)
            .putString(KEY_ROLE, role.name)
            .apply()
    }

    fun upgradeTo(role: UserRole, area: String) {
        sp.edit()
            .putString(KEY_ROLE, role.name)
            .putString(KEY_ACCOUNT, area)
            .remove(KEY_UPGRADE_STAFF)
            .remove(KEY_UPGRADE_LEAD)
            .remove(KEY_UPGRADE_STAFF_AREA)
            .remove(KEY_UPGRADE_LEAD_AREA)
            .apply()
    }

    fun applyStaffUpgrade(area: String) {
        sp.edit()
            .putBoolean(KEY_UPGRADE_STAFF, true)
            .putString(KEY_UPGRADE_STAFF_AREA, area)
            .apply()
    }

    fun applyLeadUpgrade(area: String) {
        sp.edit()
            .putBoolean(KEY_UPGRADE_LEAD, true)
            .putString(KEY_UPGRADE_LEAD_AREA, area)
            .apply()
    }

    fun clearLogin() {
        sp.edit()
            .remove(KEY_PHONE)
            .remove(KEY_ACCOUNT)
            .remove(KEY_ROLE)
            .remove(KEY_UPGRADE_STAFF)
            .remove(KEY_UPGRADE_LEAD)
            .remove(KEY_UPGRADE_STAFF_AREA)
            .remove(KEY_UPGRADE_LEAD_AREA)
            .apply()
    }

    val connectedKitId: String?
        get() = sp.getString(KEY_CONNECTED_KIT, null)

    val connectedQr: String?
        get() = sp.getString(KEY_CONNECTED_QR, null)

    val hasConnectedDevice: Boolean
        get() = !connectedKitId.isNullOrBlank()

    fun saveConnectedDevice(kitId: String, qr: String) {
        sp.edit()
            .putString(KEY_CONNECTED_KIT, kitId)
            .putString(KEY_CONNECTED_QR, qr)
            .apply()
        if (!isApplyCompleted) {
            saveApplyCompleted(kitId)
        }
    }

    fun clearConnectedDevice() {
        sp.edit()
            .remove(KEY_CONNECTED_KIT)
            .remove(KEY_CONNECTED_QR)
            .apply()
    }

    val applyStatus: DeviceApplyStatus
        get() = DeviceApplyStatus.fromName(sp.getString(KEY_APPLY_STATUS, null))

    val isApplyCompleted: Boolean
        get() = applyStatus == DeviceApplyStatus.COMPLETED

    val applyKitId: String?
        get() = sp.getString(KEY_APPLY_KIT, null)

    val shipmentStatus: DeviceShipmentStatus
        get() = DeviceShipmentStatus.fromName(sp.getString(KEY_SHIPMENT_STATUS, null))

    fun saveApplyCompleted(kitId: String) {
        sp.edit()
            .putString(KEY_APPLY_STATUS, DeviceApplyStatus.COMPLETED.name)
            .putString(KEY_APPLY_KIT, kitId)
            .putString(KEY_SHIPMENT_STATUS, DeviceShipmentStatus.PENDING.name)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "smartna_collector_prefs"
        const val KEY_PHONE = "phone"
        const val KEY_ACCOUNT = "account"
        const val KEY_ROLE = "user_role"
        const val KEY_UPGRADE_STAFF = "upgrade_staff_pending"
        const val KEY_UPGRADE_LEAD = "upgrade_lead_pending"
        const val KEY_UPGRADE_STAFF_AREA = "upgrade_staff_area"
        const val KEY_UPGRADE_LEAD_AREA = "upgrade_lead_area"
        const val KEY_CONNECTED_KIT = "connected_kit"
        const val KEY_CONNECTED_QR = "connected_qr"
        const val KEY_APPLY_STATUS = "device_apply_status"
        const val KEY_APPLY_KIT = "device_apply_kit"
        const val KEY_SHIPMENT_STATUS = "device_shipment_status"
    }
}
