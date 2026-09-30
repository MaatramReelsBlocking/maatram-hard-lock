package com.maatram.hardlock

import android.app.admin.DeviceAdminReceiver

/**
 * Being an active Device Admin means Android refuses to uninstall the app until
 * it is deactivated first — and the deactivate screen lives in Settings, which
 * is guarded while a lock runs. No special powers are used beyond that.
 */
class AdminReceiver : DeviceAdminReceiver()
