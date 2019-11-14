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

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;

import com.android.car.companiondevicesupport.api.external.ExternalBinder;
import com.android.car.companiondevicesupport.feature.ConnectionHowitzer;
import com.android.car.connecteddevice.ConnectedDeviceManager;
import com.android.internal.annotations.GuardedBy;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Early start service that holds a {@link ConnectedDeviceManager} reference to support companion
 * device features.
 */
public class CompanionDeviceSupportService extends Service {

    private static final String TAG = "CompanionDeviceSupportService";

    private final BroadcastReceiver mBleBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (BluetoothAdapter.ACTION_BLE_STATE_CHANGED.equals(intent.getAction())) {
                onBluetoothStateChanged(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1));
            }
        }
    };

    private final Lock mLock = new ReentrantLock();
    @GuardedBy("mLock")
    private volatile boolean mIsEveryFeatureInitialized = false;

    private ConnectedDeviceManager mConnectedDeviceManager;

    private ExternalBinder mExternalBinder;

    private ConnectionHowitzer mConnectionHowitzer;

    @Override
    public void onCreate() {
        super.onCreate();
        logd(TAG, "Service created.");
        mConnectedDeviceManager = new ConnectedDeviceManager(this);
        mExternalBinder = new ExternalBinder(mConnectedDeviceManager);
        registerReceiver(mBleBroadcastReceiver,
                new IntentFilter(BluetoothAdapter.ACTION_BLE_STATE_CHANGED));
        if (BluetoothAdapter.getDefaultAdapter().isLeEnabled()) {
            initializeFeatures();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mExternalBinder;
    }

    @Override
    public void onDestroy() {
        logd(TAG, "Service destroyed.");
        unregisterReceiver(mBleBroadcastReceiver);
        cleanup();
        super.onDestroy();
    }

    private void onBluetoothStateChanged(int state) {
        logd(TAG, "onBluetoothStateChanged: " + BluetoothAdapter.nameForState(state));
        switch (state) {
            case BluetoothAdapter.STATE_BLE_ON:
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
        mLock.lock();
        try {
            logd(TAG, "Cleaning up features.");
            if (!mIsEveryFeatureInitialized) {
                logd(TAG, "Features are already cleaned up. No need to clean up again.");
                return;
            }
            mConnectedDeviceManager.cleanup();
            mConnectionHowitzer.cleanup();
            mIsEveryFeatureInitialized = false;
        } finally {
            mLock.unlock();
        }
    }

    private void initializeFeatures() {
        mLock.lock();
        try {
            logd(TAG, "Initializing features.");
            if (mIsEveryFeatureInitialized) {
                logd(TAG, "Features are already initialized. No need to initialize again.");
                return;
            }
            mConnectedDeviceManager.start();
            mConnectionHowitzer = new ConnectionHowitzer(this, mConnectedDeviceManager);
            mIsEveryFeatureInitialized = true;
        } finally {
            mLock.unlock();
        }

    }
}
