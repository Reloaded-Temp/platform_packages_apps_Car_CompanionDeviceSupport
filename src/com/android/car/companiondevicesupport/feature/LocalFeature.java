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

import static com.android.car.connecteddevice.ConnectedDeviceManager.ConnectionCallback;
import static com.android.car.connecteddevice.ConnectedDeviceManager.DeviceCallback;
import static com.android.car.connecteddevice.ConnectedDeviceManager.DeviceError;

import android.annotation.CallSuper;
import android.annotation.NonNull;
import android.content.Context;

import com.android.car.connecteddevice.ConnectedDeviceManager;
import com.android.car.connecteddevice.model.ConnectedDevice;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** Base class for features local to the service. */
public abstract class LocalFeature {

    private static final String TAG = "LocalFeature";

    private final Context mContext;

    private final ConnectedDeviceManager mConnectedDeviceManager;

    private final UUID mFeatureId;

    private final Executor mExecutor = Executors.newSingleThreadExecutor();

    public LocalFeature(@NonNull Context context,
            @NonNull ConnectedDeviceManager connectedDeviceManager, @NonNull UUID featureId) {
        mContext = context;
        mConnectedDeviceManager = connectedDeviceManager;
        mFeatureId = featureId;
    }

    /** Start setup process and subscribe callbacks with {@link ConnectedDeviceManager}. */
    @CallSuper
    public void start() {
        mConnectedDeviceManager.registerActiveUserConnectionCallback(mConnectionCallback,
                mExecutor);

        List<ConnectedDevice> activeUsersDevices =
                mConnectedDeviceManager.getActiveUserConnectedDevices();
        for (ConnectedDevice device : activeUsersDevices) {
            mConnectedDeviceManager.registerDeviceCallback(device, mFeatureId,
                    mDeviceCallback, mExecutor);
        }
    }

    /** Stop execution and clean up callbacks established by this feature. */
    @CallSuper
    public void stop() {
        mConnectedDeviceManager.unregisterConnectionCallback(mConnectionCallback);
        for (ConnectedDevice device : mConnectedDeviceManager.getActiveUserConnectedDevices()) {
            mConnectedDeviceManager.unregisterDeviceCallback(device, mFeatureId, mDeviceCallback);
        }
    }

    /** Return the {@link Context} registered with the feature. */
    @NonNull
    public Context getContext() {
        return  mContext;
    }

    /** Return the {@link ConnectedDeviceManager} registered with the feature. */
    @NonNull
    public ConnectedDeviceManager getConnectedDeviceManager() {
        return mConnectedDeviceManager;
    }

    /** Return the {@link UUID} feature id registered for the feature. */
    @NonNull
    public UUID getFeatureId() {
        return mFeatureId;
    }

    // These can be overridden to perform custom actions.

    /** Called when a new {@link ConnectedDevice} is connected. */
    protected void onDeviceConnected(@NonNull ConnectedDevice device) { }

    /** Called when a {@link ConnectedDevice} disconnects. */
    protected void onDeviceDisconnected(@NonNull ConnectedDevice device) { }

    /** Called when a secure channel has been established with a {@link ConnectedDevice}. */
    protected void onSecureChannelEstablished(@NonNull ConnectedDevice device) { }

    /** Called when a new {@link byte[]} message is received for this feature. */
    protected void onMessageReceived(@NonNull ConnectedDevice device, @NonNull byte[] message) { }

    /** Called when an error has occurred with the connection. */
    protected void onDeviceError(@NonNull ConnectedDevice device, @DeviceError int error) { }

    private final ConnectionCallback mConnectionCallback = new ConnectionCallback() {
        @Override
        public void onDeviceConnected(ConnectedDevice device) {
            mConnectedDeviceManager.registerDeviceCallback(device, mFeatureId,
                    mDeviceCallback, mExecutor);
            LocalFeature.this.onDeviceConnected(device);
        }

        @Override
        public void onDeviceDisconnected(ConnectedDevice device) {
            mConnectedDeviceManager.unregisterDeviceCallback(device, mFeatureId,
                    mDeviceCallback);
            LocalFeature.this.onDeviceDisconnected(device);
        }
    };

    private final DeviceCallback mDeviceCallback = new DeviceCallback() {
        @Override
        public void onSecureChannelEstablished(ConnectedDevice device) {
            LocalFeature.this.onSecureChannelEstablished(device);
        }

        @Override
        public void onMessageReceived(ConnectedDevice device, byte[] message) {
            LocalFeature.this.onMessageReceived(device, message);
        }

        @Override
        public void onDeviceError(ConnectedDevice device, @DeviceError int error) {
            LocalFeature.this.onDeviceError(device, error);
        }
    };
}
