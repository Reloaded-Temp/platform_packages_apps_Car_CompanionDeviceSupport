/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.car.companiondevicesupport.service;

import static com.android.car.connecteddevice.util.SafeLog.logd;
import static com.android.car.connecteddevice.util.SafeLog.loge;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.IBinder;

import com.android.car.companiondevicesupport.R;
import com.android.car.companiondevicesupport.api.external.ConnectedDeviceManagerBinder;
import com.android.car.companiondevicesupport.api.internal.association.AssociationBinder;
import com.android.car.companiondevicesupport.api.internal.association.IAssociatedDeviceManager;
import com.android.car.companiondevicesupport.feature.LocalFeature;
import com.android.car.companiondevicesupport.feature.howitzer.ConnectionHowitzer;
import com.android.car.connecteddevice.ConnectedDeviceManager;
import com.android.car.connecteddevice.connection.CarBluetoothManager;
import com.android.car.connecteddevice.connection.ble.BlePeripheralManager;
import com.android.car.connecteddevice.connection.ble.CarBlePeripheralManager;
import com.android.car.connecteddevice.connection.spp.CarSppManager;
import com.android.car.connecteddevice.connection.spp.SppManager;
import com.android.car.connecteddevice.storage.ConnectedDeviceStorage;
import com.android.car.connecteddevice.util.EventLog;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Early start service that holds a {@link ConnectedDeviceManager} reference to support companion
 * device features.
 */
public class CompanionDeviceSupportService extends Service {

    private static final String TAG = "CompanionDeviceSupportService";

    // The mac address randomly rotates every 7-15 minutes. To be safe, we will rotate our
    // reconnect advertisement every 6 minutes to avoid crossing a rotation.
    private static final Duration MAX_ADVERTISEMENT_DURATION = Duration.ofMinutes(6);

    /**
     * When a client calls {@link Context#bindService(Intent, ServiceConnection, int)} to get the
     * {@link IAssociatedDeviceManager}, this action is required in the param {@link Intent}.
     */
    public static final String ACTION_BIND_ASSOCIATION =
            "com.android.car.companiondevicesupport.BIND_ASSOCIATION";

    /**
     * When a client calls {@link Context#bindService(Intent, ServiceConnection, int)} to get the
     * IConnectedDeviceManager, this action is required in the param {@link Intent}.
     */
    public static final String ACTION_BIND_CONNECTED_DEVICE_MANAGER =
            "com.android.car.companiondevicesupport.BIND_CONNECTED_DEVICE_MANAGER";

    private final BroadcastReceiver mBleBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (BluetoothAdapter.ACTION_BLE_STATE_CHANGED.equals(intent.getAction())) {
                onBluetoothStateChanged(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1));
            }
        }
    };

    private final List<LocalFeature> mLocalFeatures = new ArrayList<>();

    private final AtomicBoolean mIsEveryFeatureInitialized = new AtomicBoolean(false);

    private ConnectedDeviceManager mConnectedDeviceManager;

    private ConnectedDeviceManagerBinder mConnectedDeviceManagerBinder;

    private AssociationBinder mAssociationBinder;

    @Override
    public void onCreate() {
        super.onCreate();
        logd(TAG, "Service created.");
        EventLog.onServiceStarted();
        boolean isSppSupported = getResources().getBoolean(R.bool.enable_spp_support);
        boolean isSecureRfcommChannel =
                getResources().getBoolean(R.bool.enable_secure_rfcomm_channel);
        UUID mSppServiceUuid = isSecureRfcommChannel ? UUID.fromString(
                getString(R.string.car_spp_service_uuid_secure)) :
                UUID.fromString(getString(R.string.car_spp_service_uuid_insecure));
        UUID associationUuid = UUID.fromString(getString(R.string.car_association_service_uuid));
        UUID reconnectUuid = UUID.fromString(getString(R.string.car_reconnect_service_uuid));
        UUID reconnectDataUuid = UUID.fromString(getString(R.string.car_reconnect_data_uuid));
        UUID writeUuid = UUID.fromString(getString(R.string.car_secure_write_uuid));
        UUID readUuid = UUID.fromString(getString(R.string.car_secure_read_uuid));
        int defaultMtuSize = getResources().getInteger(R.integer.car_default_mtu_size);
        int maxSppPacketSize = getResources().getInteger(R.integer.car_max_spp_packet_bytes);
        ConnectedDeviceStorage storage = new ConnectedDeviceStorage(this);
        CarBluetoothManager carBluetoothManager;
        if (isSppSupported) {
            carBluetoothManager = new CarSppManager( new SppManager(isSecureRfcommChannel), storage,
                    mSppServiceUuid, maxSppPacketSize);
        } else {
            carBluetoothManager = new CarBlePeripheralManager(new BlePeripheralManager(this),
                    storage, associationUuid, reconnectUuid, reconnectDataUuid, writeUuid, readUuid,
                    MAX_ADVERTISEMENT_DURATION, defaultMtuSize);
        }
        mConnectedDeviceManager = new ConnectedDeviceManager(carBluetoothManager, storage);
        mLocalFeatures.add(new ConnectionHowitzer(this, mConnectedDeviceManager));
        mConnectedDeviceManagerBinder =
                new ConnectedDeviceManagerBinder(mConnectedDeviceManager);
        mAssociationBinder = new AssociationBinder(mConnectedDeviceManager);
        registerReceiver(mBleBroadcastReceiver,
                new IntentFilter(BluetoothAdapter.ACTION_BLE_STATE_CHANGED));
        if (BluetoothAdapter.getDefaultAdapter().isLeEnabled()) {
            initializeFeatures();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        logd(TAG, "Service bound.");
        if (intent == null || intent.getAction() == null) {
            return null;
        }
        String action = intent.getAction();
        switch (action) {
            case ACTION_BIND_ASSOCIATION:
                return mAssociationBinder;
            case ACTION_BIND_CONNECTED_DEVICE_MANAGER:
                return mConnectedDeviceManagerBinder;
            default:
                loge(TAG, "Unexpected action found while binding: " + action);
                return null;
        }
    }

    @Override
    public void onDestroy() {
        logd(TAG, "Service destroyed.");
        unregisterReceiver(mBleBroadcastReceiver);
        cleanup();
        super.onDestroy();
    }

    private void onBluetoothStateChanged(int state) {
        logd(TAG, "onBluetoothStateChanged: " + state);
        switch (state) {
            case BluetoothAdapter.STATE_ON:
                EventLog.onBleOn();
                initializeFeatures();
                break;
            case BluetoothAdapter.STATE_OFF:
                cleanup();
                break;
            default:
                // Ignore.
        }
    }

    private void cleanup() {
        logd(TAG, "Cleaning up features.");
        if (!mIsEveryFeatureInitialized.get()) {
            logd(TAG, "Features are already cleaned up. No need to clean up again.");
            return;
        }
        mConnectedDeviceManager.reset();
        mIsEveryFeatureInitialized.set(false);
    }

    private void initializeFeatures() {
        // Room cannot be accessed on main thread.
        Executors.defaultThreadFactory().newThread(() -> {
            logd(TAG, "Initializing features.");
            if (mIsEveryFeatureInitialized.get()) {
                logd(TAG, "Features are already initialized. No need to initialize again.");
                return;
            }
            mConnectedDeviceManager.start();
            for (LocalFeature feature : mLocalFeatures) {
                feature.start();
            }
            mIsEveryFeatureInitialized.set(true);
        }).start();
    }

    /** Returns the service's instance of {@link ConnectedDeviceManager}. */
    protected ConnectedDeviceManager getConnectedDeviceManager() {
        return mConnectedDeviceManager;
    }
}
