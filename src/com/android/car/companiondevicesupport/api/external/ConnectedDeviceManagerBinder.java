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

package com.android.car.companiondevicesupport.api.external;

import static com.android.car.connecteddevice.util.SafeLog.loge;

import android.os.ParcelUuid;
import android.os.RemoteException;

import com.android.car.connecteddevice.ConnectedDeviceManager;
import com.android.car.connecteddevice.ConnectedDeviceManager.ConnectionCallback;
import com.android.car.connecteddevice.ConnectedDeviceManager.DeviceAssociationCallback;
import com.android.car.connecteddevice.ConnectedDeviceManager.DeviceCallback;
import com.android.car.connecteddevice.model.ConnectedDevice;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** Binder for exposing ConnectedDeviceManager to features. */
public class ConnectedDeviceManagerBinder extends IConnectedDeviceManager.Stub {

    private static final String TAG = "ConnectedDeviceManagerBinder";

    private final ConnectedDeviceManager mConnectedDeviceManager;

    // aidl callback -> connection callback
    // Need to maintain a mapping in order to support unregistering callbacks.
    private final ConcurrentHashMap<IConnectionCallback, ConnectionCallback> mConnectionCallbacks =
            new ConcurrentHashMap<>();

    private final ConcurrentHashMap<IDeviceAssociationCallback, DeviceAssociationCallback>
            mAssociationCallbacks = new ConcurrentHashMap<>();

    // aidl callback -> device callback
    // Need to maintain a mapping in order to support unregistering callbacks.
    private final ConcurrentHashMap<IDeviceCallback, DeviceCallback> mDeviceCallbacks =
            new ConcurrentHashMap<>();

    private final Executor mCallbackExecutor = Executors.newSingleThreadExecutor();

    public ConnectedDeviceManagerBinder(ConnectedDeviceManager connectedDeviceManager) {
        mConnectedDeviceManager = connectedDeviceManager;
    }

    @Override
    public List<CompanionDevice> getActiveUserConnectedDevices() {
        List<ConnectedDevice> connectedDevices =
                mConnectedDeviceManager.getActiveUserConnectedDevices();
        List<CompanionDevice> convertedDevices = new ArrayList<>();
        for (ConnectedDevice device : connectedDevices) {
            convertedDevices.add(new CompanionDevice(device));
        }
        return convertedDevices;
    }

    @Override
    public void registerActiveUserConnectionCallback(IConnectionCallback callback) {
        ConnectionCallback connectionCallback = new ConnectionCallback() {
            @Override
            public void onDeviceConnected(ConnectedDevice device) {
                try {
                    callback.onDeviceConnected(new CompanionDevice(device));
                } catch (RemoteException exception) {
                    loge(TAG, "onDeviceConnected failed.", exception);
                }
            }

            @Override
            public void onDeviceDisconnected(ConnectedDevice device) {
                try {
                    callback.onDeviceDisconnected(new CompanionDevice(device));
                } catch (RemoteException exception) {
                    loge(TAG, "onDeviceDisconnected failed.", exception);
                }
            }
        };
        mConnectedDeviceManager.registerActiveUserConnectionCallback(connectionCallback,
                mCallbackExecutor);
        mConnectionCallbacks.put(callback, connectionCallback);
    }

    @Override
    public void unregisterConnectionCallback(IConnectionCallback callback) {
        ConnectionCallback connectionCallback = mConnectionCallbacks.get(callback);
        if (connectionCallback == null) {
            return;
        }
        mConnectedDeviceManager.unregisterConnectionCallback(connectionCallback);
        mConnectionCallbacks.remove(callback);
    }

    @Override
    public void registerDeviceCallback(CompanionDevice companionDevice, ParcelUuid recipientId,
            IDeviceCallback callback) {
        DeviceCallback deviceCallback = new DeviceCallback() {
            @Override
            public void onSecureChannelEstablished(ConnectedDevice device) {
                try {
                    callback.onSecureChannelEstablished(new CompanionDevice(device));
                } catch (RemoteException exception) {
                    loge(TAG, "onSecureChannelEstablished failed.", exception);
                }
            }

            @Override
            public void onMessageReceived(ConnectedDevice device, byte[] message) {
                try {
                    callback.onMessageReceived(new CompanionDevice(device), message);
                } catch (RemoteException exception) {
                    loge(TAG, "onMessageReceived failed.", exception);
                }
            }

            @Override
            public void onDeviceError(ConnectedDevice device, int error) {
                try {
                    callback.onDeviceError(new CompanionDevice(device), error);
                } catch (RemoteException exception) {
                    loge(TAG, "onDeviceError failed.", exception);
                }
            }
        };
        mConnectedDeviceManager.registerDeviceCallback(companionDevice.toConnectedDevice(),
                recipientId.getUuid(), deviceCallback, mCallbackExecutor);
        mDeviceCallbacks.put(callback, deviceCallback);
    }

    @Override
    public void unregisterDeviceCallback(CompanionDevice companionDevice, ParcelUuid recipientId,
            IDeviceCallback callback) {
        DeviceCallback deviceCallback = mDeviceCallbacks.get(callback);
        if (deviceCallback == null) {
            return;
        }
        mConnectedDeviceManager.unregisterDeviceCallback(companionDevice.toConnectedDevice(),
                recipientId.getUuid(), deviceCallback);
        mDeviceCallbacks.remove(callback);
    }

    @Override
    public boolean sendMessageSecurely(CompanionDevice companionDevice, ParcelUuid recipientId,
            byte[] message) {
        try {
            mConnectedDeviceManager.sendMessageSecurely(companionDevice.toConnectedDevice(),
                    recipientId.getUuid(), message);
            return true;
        } catch (IllegalStateException exception) {
            loge(TAG, "Attempted to send message prior to secure channel established.", exception);
        }
        return false;
    }

    @Override
    public void sendMessageUnsecurely(CompanionDevice companionDevice, ParcelUuid recipientId,
            byte[] message) {
        mConnectedDeviceManager.sendMessageUnsecurely(companionDevice.toConnectedDevice(),
                recipientId.getUuid(), message);
    }

    @Override
    public void registerDeviceAssociationCallback(IDeviceAssociationCallback callback) {
        DeviceAssociationCallback associationCallback = new DeviceAssociationCallback() {
            @Override
            public void onAssociatedDeviceAdded(String deviceId) {
                try {
                    callback.onAssociatedDeviceAdded(deviceId);
                } catch (RemoteException exception) {
                    loge(TAG, "onAssociatedDeviceAdded failed.", exception);
                }
            }

            @Override
            public void onAssociatedDeviceRemoved(String deviceId) {
                try {
                    callback.onAssociatedDeviceRemoved(deviceId);
                } catch (RemoteException exception) {
                    loge(TAG, "onAssociatedDeviceRemoved failed.", exception);
                }
            }

            @Override
            public void onAssociatedDeviceUpdated(
                    com.android.car.connecteddevice.model.AssociatedDevice device) {
                try {
                    callback.onAssociatedDeviceUpdated(new AssociatedDevice(device));
                } catch (RemoteException exception) {
                    loge(TAG, "onAssociatedDeviceUpdated failed.", exception);
                }
            }
        };

        mConnectedDeviceManager.registerDeviceAssociationCallback(associationCallback,
                mCallbackExecutor);
        mAssociationCallbacks.put(callback, associationCallback);
    }

    @Override
    public void unregisterDeviceAssociationCallback(IDeviceAssociationCallback callback) {
        DeviceAssociationCallback associationCallback = mAssociationCallbacks.get(callback);
        if (associationCallback == null) {
            return;
        }
        mConnectedDeviceManager.unregisterDeviceAssociationCallback(associationCallback);
        mAssociationCallbacks.remove(callback);
    }
}
