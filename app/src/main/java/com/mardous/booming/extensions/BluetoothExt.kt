/*
 * Copyright (c) 2025 Christians Martínez Alvarado
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.mardous.booming.extensions

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.media.AudioManager
import androidx.core.content.getSystemService

fun Context.isBluetoothA2dpConnected(): Boolean {
    return if (hasS()) {
        isBluetoothProfileInState(BluetoothProfile.A2DP, BluetoothAdapter.STATE_CONNECTED)
    } else isBluetoothA2dpOn()
}

fun Context.isBluetoothA2dpDisconnected(): Boolean {
    return if (hasS()) {
        isBluetoothProfileInState(BluetoothProfile.A2DP, BluetoothAdapter.STATE_DISCONNECTED)
    } else !isBluetoothA2dpOn()
}

private fun Context.isBluetoothProfileInState(profile: Int, state: Int): Boolean {
    val bluetoothAdapter = getSystemService<BluetoothManager>()?.adapter ?: return false
    if (checkSelfPermission(BLUETOOTH_CONNECT) == PERMISSION_GRANTED) {
        return bluetoothAdapter.getProfileConnectionState(profile) == state
    }
    return false
}

@Suppress("DEPRECATION")
private fun Context.isBluetoothA2dpOn() = getSystemService<AudioManager>()?.isBluetoothA2dpOn == true