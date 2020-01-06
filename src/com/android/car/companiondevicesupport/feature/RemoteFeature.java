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

import static com.android.car.connecteddevice.util.SafeLog.loge;

import android.annotation.CallSuper;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.os.UserHandle;

import com.android.car.companiondevicesupport.api.external.CompanionDevice;
import com.android.car.companiondevicesupport.api.external.IConnectedDeviceManager;
import com.android.car.companiondevicesupport.api.external.IConnectionCallback;
import com.android.car.companiondevicesupport.api.external.IDeviceCallback;
import com.android.car.companiondevicesupport.service.CompanionDeviceSupportService;

import java.util.List;

/**
 * Base class for a feature that must bind to {@link CompanionDeviceSupportService}. Callbacks
 * are registered automatically and events are forwarded to internal methods. Override these to
 * add custom logic for callback triggers.
 */
public abstract class RemoteFeature {

    private static final String TAG = "RemoteFeature";

    private final Context mContext;

    private final ParcelUuid mFeatureId;

    private IConnectedDeviceManager mConnectedDeviceManager;

    public RemoteFeature(@NonNull Context context, @NonNull ParcelUuid featureId) {
        mContext = context;
        mFeatureId = featureId;
    }

    /** Start setup process and begin binding to {@link CompanionDeviceSupportService}. */
    @CallSuper
    public void start() {
        Intent intent = new Intent(mContext, CompanionDeviceSupportService.class);
        intent.setAction(CompanionDeviceSupportService.ACTION_BIND_CONNECTED_DEVICE_MANAGER);
        mContext.bindServiceAsUser(intent, mServiceConnection, Context.BIND_AUTO_CREATE,
                UserHandle.SYSTEM);
    }

    /** Called when the hosting service is being destroyed. Cleans up internal feature logic. */
    @CallSuper
    public void stop() {
        try {
            mConnectedDeviceManager.unregisterConnectionCallback(mConnectionCallback);
            for (CompanionDevice device : mConnectedDeviceManager.getActiveUserConnectedDevices()) {
                mConnectedDeviceManager.unregisterDeviceCallback(device, mFeatureId,
                        mDeviceCallback);
            }
        } catch (RemoteException e) {
            loge(TAG, "Error while stopping remote feature.", e);
        }
    }

    /** Return the {@link Context} registered with the feature. */
    @NonNull
    public Context getContext() {
        return  mContext;
    }

    /**
     *  Return the {@link IConnectedDeviceManager} bound with the feature. Returns {@code null} if
     *  binding has not completed yet.
     */
    @Nullable
    public IConnectedDeviceManager getConnectedDeviceManager() {
        return mConnectedDeviceManager;
    }

    /** Return the {@link ParcelUuid} feature id registered for the feature. */
    @NonNull
    public ParcelUuid getFeatureId() {
        return mFeatureId;
    }

    // These can be overridden to perform custom actions.

    /** Called when a new {@link CompanionDevice} is connected. */
    protected void onDeviceConnected(@NonNull CompanionDevice device) { }

    /** Called when a {@link CompanionDevice} disconnects. */
    protected void onDeviceDisconnected(@NonNull CompanionDevice device) { }

    /** Called when a secure channel has been established with a {@link CompanionDevice}. */
    protected void onSecureChannelEstablished(@NonNull CompanionDevice device) { }

    /** Called when a new {@link byte[]} message is received for this feature. */
    protected void onMessageReceived(@NonNull CompanionDevice device, @NonNull byte[] message) { }

    /** Called when an error has occurred with the connection. */
    protected void onDeviceError(@NonNull CompanionDevice device, int error) { }

    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mConnectedDeviceManager = IConnectedDeviceManager.Stub.asInterface(service);
            try {
                mConnectedDeviceManager.registerActiveUserConnectionCallback(mConnectionCallback);
                List<CompanionDevice> activeUserConnectedDevices =
                        mConnectedDeviceManager.getActiveUserConnectedDevices();
                if (activeUserConnectedDevices.isEmpty()) {
                    mConnectedDeviceManager.connectToActiveUserDevice();
                    return;
                }
                for (CompanionDevice device : activeUserConnectedDevices) {
                    mConnectedDeviceManager.registerDeviceCallback(device, mFeatureId,
                            mDeviceCallback);
                }
            } catch (RemoteException e) {
                loge(TAG, "Error while inspecting connected devices.", e);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            stop();
        }
    };

    private final IConnectionCallback mConnectionCallback = new IConnectionCallback.Stub() {
        @Override
        public void onDeviceConnected(CompanionDevice companionDevice) throws RemoteException {
            mConnectedDeviceManager.registerDeviceCallback(companionDevice, mFeatureId,
                    mDeviceCallback);
            RemoteFeature.this.onDeviceConnected(companionDevice);
        }

        @Override
        public void onDeviceDisconnected(CompanionDevice companionDevice) throws RemoteException {
            mConnectedDeviceManager.unregisterDeviceCallback(companionDevice, mFeatureId,
                    mDeviceCallback);
            RemoteFeature.this.onDeviceDisconnected(companionDevice);
        }
    };

    private final IDeviceCallback mDeviceCallback = new IDeviceCallback.Stub() {
        @Override
        public void onSecureChannelEstablished(CompanionDevice companionDevice)
                throws RemoteException {
            RemoteFeature.this.onSecureChannelEstablished(companionDevice);
        }

        @Override
        public void onMessageReceived(CompanionDevice companionDevice, byte[] message)
                throws RemoteException {
            RemoteFeature.this.onMessageReceived(companionDevice, message);
        }

        @Override
        public void onDeviceError(CompanionDevice companionDevice, int error)
                throws RemoteException {
            RemoteFeature.this.onDeviceError(companionDevice, error);
        }
    };
}