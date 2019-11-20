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

package com.android.car.companiondevicesupport.api.internal;

import static com.android.car.connecteddevice.util.SafeLog.logd;
import static com.android.car.connecteddevice.util.SafeLog.loge;

import android.os.RemoteException;

import com.android.car.connecteddevice.ConnectedDeviceManager;
import com.android.car.connecteddevice.AssociationCallback;

import java.util.ArrayList;
import java.util.List;

/** Binder for exposing ConnectedDeviceManager to internal features. */
public class InternalBinder extends IAssociatedDeviceManager.Stub {

    private static final String TAG = "InternalBinder";

    private final ConnectedDeviceManager mConnectedDeviceManager;
    private AssociationCallback mAssociationCallback;
    private IAssociationCallback mIAssociationCallback;

    public InternalBinder(ConnectedDeviceManager connectedDeviceManager) {
        mConnectedDeviceManager = connectedDeviceManager;
    }

    @Override
    public void startAssociation(IAssociationCallback callback) {
        AssociationCallback associationCallback = new AssociationCallback() {
            @Override
            public void onAssociationStartFailure() {
                try {
                    callback.onAssociationStartFailure();
                } catch (RemoteException exception) {
                    loge(TAG, "onAssociationStartFailure failed.", exception);
                }
            }

            @Override
            public void onAssociationError(int error) {
                try {
                    callback.onAssociationError(error);
                } catch (RemoteException exception) {
                    loge(TAG, "onAssociationError failed. Error: " + error + ".", exception);
                }
            }

            @Override
            public void onVerificationCodeAvailable(String code) {
                logd(TAG, "Showing pairing code: " + code);
                try {
                    callback.onVerificationCodeAvailable(code);
                } catch (RemoteException exception) {
                    loge(TAG, "onVerificationCodeAvailable failed. Code: " + code + ".", exception);
                }
            }

            @Override
            public void onAssociationCompleted() {
                try {
                    callback.onAssociationCompleted();
                } catch (RemoteException exception) {
                    loge(TAG, "onAssociationCompleted failed.", exception);
                }
            }
        };
        mAssociationCallback = associationCallback;
        mIAssociationCallback = callback;
        mConnectedDeviceManager.startAssociation(associationCallback);
    }

    @Override
    public void stopAssociation(IAssociationCallback callback) {
        if (callback != mIAssociationCallback) {
            loge(TAG, "Unexpected IAssociationCallback.");
            return;
        }
        if (mAssociationCallback == null) {
            loge(TAG, "Association callback is null.");
            return;
        }
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

}
