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

package com.android.car.companiondevicesupport.activity;

import static com.android.car.companiondevicesupport.service.CompanionDeviceSupportService.ACTION_BIND_ASSOCIATION;
import static com.android.car.connecteddevice.util.SafeLog.logd;
import static com.android.car.connecteddevice.util.SafeLog.loge;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;

import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.android.car.companiondevicesupport.api.external.AssociatedDevice;
import com.android.car.companiondevicesupport.api.external.CompanionDevice;
import com.android.car.companiondevicesupport.api.external.IDeviceAssociationCallback;
import com.android.car.companiondevicesupport.api.external.IConnectionCallback;
import com.android.car.companiondevicesupport.api.internal.association.IAssociatedDeviceManager;
import com.android.car.companiondevicesupport.api.internal.association.IAssociationCallback;
import com.android.car.companiondevicesupport.service.CompanionDeviceSupportService;

import java.util.ArrayList;
import java.util.List;

/**
 * Implementation {@link ViewModel} for sharing associated devices data between
 * {@link AssociatedDeviceDetailFragment} and {@link AssociationActivity}
 */
public class AssociatedDeviceViewModel extends AndroidViewModel {

    private static final String TAG = "AssociatedDeviceViewModel";

    private IAssociatedDeviceManager mAssociatedDeviceManager;
    private boolean mIsInAssociation = false;
    private List<AssociatedDevice> mAssociatedDevices = new ArrayList<>();
    private List<CompanionDevice> mConnectedDevices = new ArrayList<>();

    private final MutableLiveData<AssociatedDeviceDetails> mDeviceDetails =
            new MutableLiveData<>(null);
    private final MutableLiveData<String> mAdvertisedCarName = new MutableLiveData<>(null);
    private final MutableLiveData<String> mPairingCode = new MutableLiveData<>(null);
    private final MutableLiveData<AssociatedDevice> mDeviceToRemove = new MutableLiveData<>(null);
    private final MutableLiveData<Boolean> mIsFinished = new MutableLiveData<>(false);

    public AssociatedDeviceViewModel(@NonNull Application application) {
        super(application);
        Intent intent = new Intent(getApplication(), CompanionDeviceSupportService.class);
        intent.setAction(ACTION_BIND_ASSOCIATION);
        getApplication().bindServiceAsUser(intent, mConnection, Context.BIND_AUTO_CREATE,
                UserHandle.SYSTEM);
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        try {
            unregisterCallbacks();
        } catch (RemoteException e) {
            loge(TAG, "Error clearing registered callbacks. ", e);
        }
        getApplication().unbindService(mConnection);
        mAssociatedDeviceManager = null;
    }

    /** confirms that the pairing code matches. */
    public void acceptVerification() {
        mPairingCode.postValue(null);
        try {
            mAssociatedDeviceManager.acceptVerification();
        } catch (RemoteException e) {
            loge(TAG, "Error while accepting verification.", e);
        }
    }

    /** Stops association. */
    public void stopAssociation() {
        if (!mIsInAssociation) {
            return;
        }
        try {
            mAssociatedDeviceManager.stopAssociation();
        } catch (RemoteException e) {
            loge(TAG, "Error while stopping association process.", e);
        }
        mIsInAssociation = false;
    }

    /** Select the current associated device as the device to remove. */
    public void selectCurrentDeviceToRemove() {
        mDeviceToRemove.postValue(getAssociatedDevice());
    }

    /** Remove the current associated device.*/
    public void removeCurrentDevice() {
        AssociatedDevice device = getAssociatedDevice();
        if (device == null) {
            return;
        }
        try {
            mAssociatedDeviceManager.removeAssociatedDevice(device.getDeviceId());
        } catch (RemoteException e) {
            loge(TAG, "Failed to remove associated device: " + device, e);
        }
    }

    /** Toggle connection on the current associated device. */
    public void toggleConnectionStatusForCurrentDevice() {
        AssociatedDevice device = getAssociatedDevice();
        if (device == null) {
            return;
        }
        try {
            if (device.isConnectionEnabled()) {
                mAssociatedDeviceManager.disableAssociatedDeviceConnection(device.getDeviceId());
            } else {
                mAssociatedDeviceManager.enableAssociatedDeviceConnection(device.getDeviceId());
            }
        } catch (RemoteException e) {
            loge(TAG, "Failed to toggle connection status for device: " + device + ".", e);
        }
    }

    /** Get the associated device details. */
    public MutableLiveData<AssociatedDeviceDetails> getDeviceDetails() {
        return mDeviceDetails;
    }

    /** Get the associated device to remove. The associated device could be null. */
    public LiveData<AssociatedDevice> getDeviceToRemove() {
        return mDeviceToRemove;
    }

    /** Get the name that is being advertised by the car. */
    public MutableLiveData<String> getAdvertisedCarName() {
        return mAdvertisedCarName;
    }

    /** Get the generated pairing code. */
    public MutableLiveData<String> getPairingCode() {
        return mPairingCode;
    }

    /** Value is {@code true} if IHU is not in association and has no associated device. */
    public MutableLiveData<Boolean> isFinished() {
        return mIsFinished;
    }

    private void updateDeviceDetails() {
        AssociatedDevice device = getAssociatedDevice();
        if (device == null) {
            return;
        }
        mDeviceDetails.postValue(new AssociatedDeviceDetails(getAssociatedDevice(), isConnected()));
    }

    @Nullable
    private AssociatedDevice getAssociatedDevice() {
        if (mAssociatedDevices.isEmpty()) {
            return null;
        }
        return mAssociatedDevices.get(0);
    }

    private boolean isConnected() {
        if (mAssociatedDevices.isEmpty() || mConnectedDevices.isEmpty()) {
            return false;
        }
        String associatedDeviceId = mAssociatedDevices.get(0).getDeviceId();
        String connectedDeviceId = mConnectedDevices.get(0).getDeviceId();
        return associatedDeviceId.equals(connectedDeviceId);
    }

    private void setAssociatedDevices(@NonNull List<AssociatedDevice> associatedDevices) {
        mAssociatedDevices = associatedDevices;
        updateDeviceDetails();
    }

    private void setConnectedDevices(@NonNull List<CompanionDevice> connectedDevices) {
        mConnectedDevices = connectedDevices;
        updateDeviceDetails();
    }

    private void addOrUpdateAssociatedDevice(@NonNull AssociatedDevice device) {
        mAssociatedDevices.removeIf(d -> d.getDeviceId().equals(device.getDeviceId()));
        mAssociatedDevices.add(device);
        updateDeviceDetails();
    }

    private void removeAssociatedDevice(String deviceId) {
        if (mAssociatedDevices.removeIf(d -> d.getDeviceId().equals(deviceId))) {
            mDeviceDetails.postValue(null);
            mIsFinished.postValue(true);
        }
    }

    private void registerCallbacks() throws RemoteException {
        mAssociatedDeviceManager.setAssociationCallback(mAssociationCallback);
        mAssociatedDeviceManager.setDeviceAssociationCallback(mDeviceAssociationCallback);
        mAssociatedDeviceManager.setConnectionCallback(mConnectionCallback);
    }

    private void unregisterCallbacks() throws RemoteException {
        mAssociatedDeviceManager.clearDeviceAssociationCallback();
        mAssociatedDeviceManager.clearAssociationCallback();
        mAssociatedDeviceManager.clearConnectionCallback();
    }

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mAssociatedDeviceManager = IAssociatedDeviceManager.Stub.asInterface(service);
            try {
                registerCallbacks();
                setConnectedDevices(mAssociatedDeviceManager.getActiveUserConnectedDevices());
                setAssociatedDevices(mAssociatedDeviceManager.getActiveUserAssociatedDevices());
                if (mAssociatedDevices.isEmpty() && !mIsInAssociation) {
                    mAssociatedDeviceManager.startAssociation();
                }
            } catch (RemoteException e) {
                loge(TAG, "Initial set failed onServiceConnected", e);
            }
            logd(TAG, "Service connected:" + name.getClassName());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mAssociatedDeviceManager = null;
            logd(TAG, "Service disconnected: " + name.getClassName());
            mIsFinished.postValue(true);
        }
    };

    private final IAssociationCallback mAssociationCallback = new IAssociationCallback.Stub() {
        @Override
        public void onAssociationStartSuccess(String deviceName) {
            mIsInAssociation = true;
            mAdvertisedCarName.postValue(deviceName);
        }

        @Override
        public void onAssociationStartFailure() {
            mIsInAssociation = false;
            mIsFinished.postValue(true);
        }

        @Override
        public void onAssociationError(int error) throws RemoteException {
            mIsInAssociation = false;
            mIsFinished.postValue(true);
        }

        @Override
        public void onVerificationCodeAvailable(String code) throws RemoteException {
            mAdvertisedCarName.postValue(null);
            mPairingCode.postValue(code);
        }

        @Override
        public void onAssociationCompleted() {
            mIsInAssociation = false;
        }
    };

    private final IDeviceAssociationCallback mDeviceAssociationCallback =
            new IDeviceAssociationCallback.Stub() {
                @Override
                public void onAssociatedDeviceAdded(AssociatedDevice device) {
                    addOrUpdateAssociatedDevice(device);
                }

                @Override
                public void onAssociatedDeviceRemoved(AssociatedDevice device) {
                    removeAssociatedDevice(device.getDeviceId());
                }

                @Override
                public void onAssociatedDeviceUpdated(AssociatedDevice device) {
                    addOrUpdateAssociatedDevice(device);
                }
            };

    private final IConnectionCallback mConnectionCallback = new IConnectionCallback.Stub() {
        @Override
        public void onDeviceConnected(CompanionDevice companionDevice) {
            mConnectedDevices.add(companionDevice);
            updateDeviceDetails();
        }

        @Override
        public void onDeviceDisconnected(CompanionDevice companionDevice) {
            mConnectedDevices.removeIf(d -> d.getDeviceId().equals(companionDevice.getDeviceId()));
            updateDeviceDetails();
        }
    };
}
