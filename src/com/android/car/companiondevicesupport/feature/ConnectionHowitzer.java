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

package com.android.car.companiondevicesupport.feature;

import static com.android.car.connecteddevice.util.SafeLog.logd;

import android.content.Context;

import com.android.car.companiondevicesupport.R;
import com.android.car.connecteddevice.ConnectedDeviceManager;
import com.android.car.connecteddevice.ConnectedDeviceManager.ConnectionCallback;
import com.android.car.connecteddevice.ConnectedDeviceManager.DeviceCallback;
import com.android.car.connecteddevice.model.ConnectedDevice;
import com.android.car.connecteddevice.util.ByteUtils;

import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** Reference feature used to validate the performance of the connected device connection. */
public class ConnectionHowitzer {

    private static final String TAG = "ConnectionHowitzer";

    private static final int LARGE_MESSAGE_SIZE = 10000;

    private final UUID mFeatureId;

    private final ConnectedDeviceManager mConnectedDeviceManager;

    private final Executor mExecutor = Executors.newSingleThreadExecutor();

    private final ConnectionCallback mConnectionCallback = new ConnectionCallback() {
        @Override
        public void onDeviceConnected(ConnectedDevice device) {
            logd(TAG, "Device " + device.getDeviceId() + " connected.");
            mConnectedDeviceManager.registerDeviceCallback(device, mFeatureId,
                    mDeviceCallback, mExecutor);
            // TODO (b/143789390) Add remote trigger instead of doing this on every
            // connection.
            sendMessage(device, LARGE_MESSAGE_SIZE);
        }

        @Override
        public void onDeviceDisconnected(ConnectedDevice device) {
            logd(TAG, "Device " + device.getDeviceId() + " disconnected.");
            mConnectedDeviceManager.unregisterDeviceCallback(device, mFeatureId,
                    mDeviceCallback);
        }
    };

    private final DeviceCallback mDeviceCallback = new DeviceCallback() {
        @Override
        public void onSecureChannelEstablished(ConnectedDevice device) {
            logd(TAG, "Secure channel established.");
        }

        @Override
        public void onMessageReceived(ConnectedDevice device, byte[] message) {
            logd(TAG, "Received a new message of " + message.length + " bytes.");
        }

        @Override
        public void onDeviceError(ConnectedDevice device,
                @ConnectedDeviceManager.DeviceError int error) {
            logd(TAG, "A device error occurred " + error + ".");
        }
    };

    public ConnectionHowitzer(Context context, ConnectedDeviceManager connectedDeviceManager) {
        mConnectedDeviceManager = connectedDeviceManager;
        mFeatureId = UUID.fromString(context.getString(R.string.connection_howitzer_feature_id));
        mConnectedDeviceManager.registerActiveUserConnectionCallback(mConnectionCallback,
                mExecutor);
    }

    /** Clean up callbacks established by this feature. */
    public void cleanup() {
        mConnectedDeviceManager.unregisterConnectionCallback(mConnectionCallback);
        for (ConnectedDevice device : mConnectedDeviceManager.getActiveUserConnectedDevices()) {
            mConnectedDeviceManager.unregisterDeviceCallback(device, mFeatureId, mDeviceCallback);
        }
    }

    private void sendMessage(ConnectedDevice device, int messageSize) {
        byte[] message = ByteUtils.randomBytes(messageSize);
        mConnectedDeviceManager.sendMessageUnsecurely(device, mFeatureId, message);
    }
}
