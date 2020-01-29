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

package com.android.car.companiondevicesupport.api.internal.association;

import static com.android.car.connecteddevice.util.SafeLog.logd;
import static com.android.car.connecteddevice.util.SafeLog.loge;

import android.os.RemoteException;

import com.android.car.companiondevicesupport.api.external.AssociatedDevice;
import com.android.car.companiondevicesupport.api.external.IDeviceAssociationCallback;
import com.android.car.connecteddevice.ConnectedDeviceManager;
import com.android.car.connecteddevice.ConnectedDeviceManager.DeviceAssociationCallback;
import com.android.car.connecteddevice.AssociationCallback;
import com.android.car.connecteddevice.util.RemoteCallbackBinder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** Binder for exposing connected device association actions to internal features. */
public class AssociationBinder extends IAssociatedDeviceManager.Stub {

    private static final String TAG = "AssociationBinder";

    private final ConnectedDeviceManager mConnectedDeviceManager;
    /**
     * {@link #mRemoteAssociationCallbackBinder} and {@link #mIAssociationCallback} can only be
     * modified together through {@link #registerAssociationCallback(IAssociationCallback)} or
     * {@link #unregisterAssociationCallback()} from the association thread.
     */
    private RemoteCallbackBinder mRemoteAssociationCallbackBinder;
    private IAssociationCallback mIAssociationCallback;
    /**
     * {@link #mRemoteDeviceAssociationCallbackBinder} and {@link #mDeviceAssociationCallback} can
     * only be modified together through
     * {@link #registerDeviceAssociationCallback(IDeviceAssociationCallback)} or
     * {@link #unregisterDeviceAssociationCallback()} from the association thread.
     */
    private RemoteCallbackBinder mRemoteDeviceAssociationCallbackBinder;
    private DeviceAssociationCallback mDeviceAssociationCallback;

    private final Executor mCallbackExecutor = Executors.newSingleThreadExecutor();

    public AssociationBinder(ConnectedDeviceManager connectedDeviceManager) {
        mConnectedDeviceManager = connectedDeviceManager;
    }

    @Override
    public void registerAssociationCallback(IAssociationCallback callback) {
        logd(TAG, "registerAssociationCallback called");
        mRemoteAssociationCallbackBinder = new RemoteCallbackBinder(callback.asBinder(),
                iBinder -> stopAssociation());
        mIAssociationCallback = callback;
        if (mIAssociationCallback == null) {
            logd(TAG, "mIAssociationCallback is null");
        }
    }

    @Override
    public void unregisterAssociationCallback() {
        mIAssociationCallback = null;
        if (mRemoteAssociationCallbackBinder == null) {
            return;
        }
        mRemoteAssociationCallbackBinder.cleanUp();
        mRemoteAssociationCallbackBinder = null;
    }

    @Override
    public void startAssociation() {
        mConnectedDeviceManager.startAssociation(mAssociationCallback);
    }

    @Override
    public void stopAssociation() {
        mConnectedDeviceManager.stopAssociation(mAssociationCallback);
    }

    @Override
    public List<AssociatedDevice> getActiveUserAssociatedDevices() {
        List<com.android.car.connecteddevice.model.AssociatedDevice> associatedDevices =
                mConnectedDeviceManager.getActiveUserAssociatedDevices();
        List<AssociatedDevice> convertedDevices = new ArrayList<>();
        for (com.android.car.connecteddevice.model.AssociatedDevice device : associatedDevices) {
            convertedDevices.add(new AssociatedDevice(device));
        }
        return convertedDevices;
    }

    @Override
    public void acceptVerification() {
        mConnectedDeviceManager.notifyOutOfBandAccepted();
    }

    @Override
    public void removeAssociatedDevice(String deviceId) {
        mConnectedDeviceManager.removeActiveUserAssociatedDevice(deviceId);
    }

    @Override
    public void registerDeviceAssociationCallback(IDeviceAssociationCallback callback) {
        mDeviceAssociationCallback = new DeviceAssociationCallback() {
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
        mRemoteDeviceAssociationCallbackBinder = new RemoteCallbackBinder(callback.asBinder(),
                iBinder -> unregisterDeviceAssociationCallback());
        mConnectedDeviceManager.registerDeviceAssociationCallback(mDeviceAssociationCallback,
                mCallbackExecutor);
    }

    @Override
    public void unregisterDeviceAssociationCallback() {
        if (mDeviceAssociationCallback == null) {
            return;
        }
        mConnectedDeviceManager.unregisterDeviceAssociationCallback(mDeviceAssociationCallback);
        mDeviceAssociationCallback = null;
        mRemoteDeviceAssociationCallbackBinder.cleanUp();
        mRemoteDeviceAssociationCallbackBinder = null;
    }

    private AssociationCallback mAssociationCallback = new AssociationCallback() {

        @Override
        public void onAssociationStartSuccess(String deviceName) {
            if (mIAssociationCallback == null) {
                loge(TAG, "No IAssociationCallback has been registered, ignoring " +
                        "onAssociationStartSuccess.");
                return;
            }
            try {
                mIAssociationCallback.onAssociationStartSuccess(deviceName);
            } catch (RemoteException exception) {
                loge(TAG, "onAssociationStartSuccess failed.", exception);
            }
        }
        @Override
        public void onAssociationStartFailure() {
            if (mIAssociationCallback == null) {
                loge(TAG, "No IAssociationCallback has been registered, ignoring " +
                        "onAssociationStartFailure.");
                return;
            }
            try {
                mIAssociationCallback.onAssociationStartFailure();
            } catch (RemoteException exception) {
                loge(TAG, "onAssociationStartFailure failed.", exception);
            }
        }

        @Override
        public void onAssociationError(int error) {
            if (mIAssociationCallback == null) {
                loge(TAG, "No IAssociationCallback has been registered, ignoring " +
                        "onAssociationError: " + error + ".");
                return;
            }
            try {
                mIAssociationCallback.onAssociationError(error);
            } catch (RemoteException exception) {
                loge(TAG, "onAssociationError failed. Error: " + error + "", exception);
            }
        }

        @Override
        public void onVerificationCodeAvailable(String code) {
            if (mIAssociationCallback == null) {
                loge(TAG, "No IAssociationCallback has been registered, ignoring " +
                        "onVerificationCodeAvailable, code: " + code);
                return;
            }
            try {
                mIAssociationCallback.onVerificationCodeAvailable(code);
            } catch (RemoteException exception) {
                loge(TAG, "onVerificationCodeAvailable failed. Code: " + code + "", exception);
            }
        }

        @Override
        public void onAssociationCompleted(String deviceId) {
            if (mIAssociationCallback == null) {
                loge(TAG, "No IAssociationCallback has been registered, ignoring " +
                        "onAssociationCompleted, deviceId: " + deviceId);
                return;
            }
            try {
                mIAssociationCallback.onAssociationCompleted();
            } catch (RemoteException exception) {
                loge(TAG, "onAssociationCompleted failed.", exception);
            }
        }
    };
}
