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

package com.android.car.companiondevicesupport.feature.trust;

import static com.android.car.connecteddevice.util.SafeLog.logd;
import static com.android.car.connecteddevice.util.SafeLog.loge;
import static com.android.car.connecteddevice.util.SafeLog.logw;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;

import androidx.room.Room;

import com.android.car.companiondevicesupport.api.external.AssociatedDevice;
import com.android.car.companiondevicesupport.api.external.CompanionDevice;
import com.android.car.companiondevicesupport.api.external.IConnectedDeviceManager;
import com.android.car.companiondevicesupport.api.external.IDeviceAssociationCallback;
import com.android.car.companiondevicesupport.api.internal.trust.IOnValidateCredentialsRequestListener;
import com.android.car.companiondevicesupport.api.internal.trust.ITrustedDeviceAgentDelegate;
import com.android.car.companiondevicesupport.api.internal.trust.ITrustedDeviceCallback;
import com.android.car.companiondevicesupport.api.internal.trust.ITrustedDeviceManager;
import com.android.car.companiondevicesupport.api.internal.trust.TrustedDevice;
import com.android.car.companiondevicesupport.feature.trust.storage.TrustedDeviceDao;
import com.android.car.companiondevicesupport.feature.trust.storage.TrustedDeviceDatabase;
import com.android.car.companiondevicesupport.feature.trust.storage.TrustedDeviceEntity;
import com.android.car.companiondevicesupport.feature.trust.ui.TrustedDeviceActivity;
import com.android.car.companiondevicesupport.protos.PhoneAuthProto.PhoneCredentials;
import com.android.car.connecteddevice.util.ByteUtils;
import com.android.car.connecteddevice.util.RemoteCallbackBinder;
import com.android.car.protobuf.InvalidProtocolBufferException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;


/** Manager for the feature of unlocking the head unit with a user's trusted device. */
public class TrustedDeviceManager extends ITrustedDeviceManager.Stub {

    private static final String TAG = "TrustedDeviceManager";

    /** Length of token generated on a trusted device. */
    private static final int ESCROW_TOKEN_LENGTH = 8;

    private static final byte[] ACKNOWLEDGEMENT_MESSAGE = "ACK".getBytes();

    private final Map<IBinder, ITrustedDeviceCallback> mTrustedDeviceCallbacks =
            new ConcurrentHashMap<>();

    private final Map<IBinder, IOnValidateCredentialsRequestListener> mEnrollmentCallbacks
            = new ConcurrentHashMap<>();

    private final Map<IBinder, IDeviceAssociationCallback> mAssociatedDeviceCallbacks =
            new ConcurrentHashMap<>();

    private final Set<RemoteCallbackBinder> mCallbackBinders = new CopyOnWriteArraySet<>();

    private final Context mContext;

    private final TrustedDeviceFeature mTrustedDeviceFeature;

    private final Executor mExecutor = Executors.newSingleThreadExecutor();

    private final AtomicBoolean mIsWaitingForCredentials = new AtomicBoolean(false);

    private TrustedDeviceDao mDatabase;

    private ITrustedDeviceAgentDelegate mTrustAgentDelegate;

    private CompanionDevice mPendingDevice;

    private byte[] mPendingToken;

    private PendingCredentials mPendingCredentials;


    TrustedDeviceManager(@NonNull Context context) {
        mContext = context;
        mTrustedDeviceFeature = new TrustedDeviceFeature(context);
        mTrustedDeviceFeature.setCallback(mFeatureCallback);
        mTrustedDeviceFeature.setAssociatedDeviceCallback(mAssociatedDeviceCallback);
        mTrustedDeviceFeature.start();
        mDatabase = Room.databaseBuilder(context, TrustedDeviceDatabase.class,
                TrustedDeviceDatabase.DATABASE_NAME).build().trustedDeviceDao();
        logd(TAG, "TrustedDeviceManager created successfully.");
    }

    void cleanup() {
        mPendingToken = null;
        mPendingDevice = null;
        mPendingCredentials = null;
        mIsWaitingForCredentials.set(false);
        mTrustedDeviceCallbacks.clear();
        mEnrollmentCallbacks.clear();
        mAssociatedDeviceCallbacks.clear();
        mTrustedDeviceFeature.stop();
    }

    private void startEnrollment(@NonNull CompanionDevice device, @NonNull byte[] token) {
        logd(TAG, "Starting trusted device enrollment process.");
        mPendingDevice = device;
        Intent intent = new Intent(mContext, TrustedDeviceActivity.class);
        intent.putExtra(TrustedDeviceConstants.INTENT_EXTRA_ENROLL_NEW_TOKEN, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivityAsUser(intent, UserHandle.of(ActivityManager.getCurrentUser()));

        mPendingToken = token;
        if (mTrustAgentDelegate == null) {
            logd(TAG, "No trust agent delegate has been set yet. No further enrollment action "
                    + "can be taken at this time.");
            return;
        }

        try {
            mTrustAgentDelegate.addEscrowToken(token, ActivityManager.getCurrentUser());
        } catch (RemoteException e) {
            loge(TAG, "Error while adding token through delegate.", e);
        }
    }

    private void unlockUser(@NonNull String deviceId, @NonNull PhoneCredentials credentials) {
        logd(TAG, "Unlocking with credentials.");
        try {
            mTrustAgentDelegate.unlockUserWithToken(credentials.getEscrowToken().toByteArray(),
                    ByteUtils.bytesToLong(credentials.getHandle().toByteArray()),
                    ActivityManager.getCurrentUser());
            mTrustedDeviceFeature.sendMessageSecurely(deviceId, ACKNOWLEDGEMENT_MESSAGE);
        } catch (RemoteException e) {
            loge(TAG, "Error while unlocking user through delegate.", e);
        }
    }

    @Override
    public void onEscrowTokenAdded(int userId, long handle) {
        logd(TAG, "Escrow token has been successfully added.");
        mPendingToken = null;

        if (mEnrollmentCallbacks.size() == 0) {
            mIsWaitingForCredentials.set(true);
            return;
        }

        mIsWaitingForCredentials.set(false);
        notifyEnrollmentCallbacks(callback -> {
            try {
                callback.onValidateCredentialsRequest();
            } catch (RemoteException e) {
                loge(TAG, "Error while requesting credential validation.", e);
            }
        });
    }

    @Override
    public void onEscrowTokenActivated(int userId, long handle) {
        if (mPendingDevice == null) {
            loge(TAG, "Unable to complete device enrollment. Pending device was null.");
            return;
        }
        logd(TAG, "Enrollment completed successfully! Sending handle to connected device and "
                + "persisting trusted device record.");
        mTrustedDeviceFeature.sendMessageSecurely(mPendingDevice, ByteUtils.longToBytes(handle));
        TrustedDeviceEntity entity = new TrustedDeviceEntity();
        String deviceId = mPendingDevice.getDeviceId();
        entity.id = deviceId;
        entity.userId = userId;
        entity.handle = handle;
        mDatabase.addOrReplaceTrustedDevice(entity);
        mPendingDevice = null;
        TrustedDevice trustedDevice = new TrustedDevice(deviceId, userId, handle);
        notifyTrustedDeviceCallbacks(callback -> {
            try {
                callback.onTrustedDeviceAdded(trustedDevice);
            } catch (RemoteException e) {
                loge(TAG, "Failed to notify that enrollment completed successfully.", e);
            }
        });
    }

    @Override
    public List<TrustedDevice> getTrustedDevicesForActiveUser() {
        List<TrustedDeviceEntity> foundEntities =
                mDatabase.getTrustedDevicesForUser(ActivityManager.getCurrentUser());

        List<TrustedDevice> trustedDevices = new ArrayList<>();
        if (foundEntities == null) {
            return trustedDevices;
        }

        for (TrustedDeviceEntity entity : foundEntities) {
            trustedDevices.add(entity.toTrustedDevice());
        }

        return trustedDevices;
    }

    @Override
    public void removeTrustedDevice(TrustedDevice trustedDevice) {
        if (mTrustAgentDelegate == null) {
            loge(TAG, "No TrustAgent delegate has been set. Unable to remove trusted device.");
            return;
        }

        try {
            mTrustAgentDelegate.removeEscrowToken(trustedDevice.getHandle(),
                    trustedDevice.getUserId());
            mDatabase.removeTrustedDevice(new TrustedDeviceEntity(trustedDevice));
        } catch (RemoteException e) {
            loge(TAG, "Error while removing token through delegate.", e);
            return;
        }
        notifyTrustedDeviceCallbacks(callback -> {
            try {
                callback.onTrustedDeviceRemoved(trustedDevice);
            } catch (RemoteException e) {
                loge(TAG, "Failed to notify that a trusted device has been removed.", e);
            }
        });
    }

    @Override
    public List<CompanionDevice> getActiveUserConnectedDevices() {
        List<CompanionDevice> devices = new ArrayList<>();
        IConnectedDeviceManager manager = mTrustedDeviceFeature.getConnectedDeviceManager();
        if (manager == null) {
            loge(TAG, "Unable to get connected devices. Service not connected. ");
            return devices;
        }
        try {
            devices = manager.getActiveUserConnectedDevices();
        } catch (RemoteException e) {
            loge(TAG, "Failed to get connected devices. ", e);
        }
        return devices;
    }

    @Override
    public void registerTrustedDeviceCallback(ITrustedDeviceCallback callback)  {
        mTrustedDeviceCallbacks.put(callback.asBinder(), callback);
        RemoteCallbackBinder remoteBinder = new RemoteCallbackBinder(callback.asBinder(), iBinder ->
                unregisterTrustedDeviceCallback(callback));
        mCallbackBinders.add(remoteBinder);
    }

    @Override
    public void unregisterTrustedDeviceCallback(ITrustedDeviceCallback callback) {
        IBinder binder = callback.asBinder();
        mTrustedDeviceCallbacks.remove(binder);
        removeRemoteBinder(binder);
    }

    @Override
    public void registerAssociatedDeviceCallback(IDeviceAssociationCallback callback) {
        mAssociatedDeviceCallbacks.put(callback.asBinder(), callback);
        RemoteCallbackBinder remoteBinder = new RemoteCallbackBinder(callback.asBinder(), iBinder ->
                unregisterAssociatedDeviceCallback(callback));
        mCallbackBinders.add(remoteBinder);
    }

    @Override
    public void unregisterAssociatedDeviceCallback(IDeviceAssociationCallback callback) {
        IBinder binder = callback.asBinder();
        mAssociatedDeviceCallbacks.remove(binder);
        removeRemoteBinder(binder);
    }

    @Override
    public void addOnValidateCredentialsRequestListener(
            IOnValidateCredentialsRequestListener listener) {
        mEnrollmentCallbacks.put(listener.asBinder(), listener);
        RemoteCallbackBinder remoteBinder = new RemoteCallbackBinder(listener.asBinder(),
                iBinder -> removeOnValidateCredentialsRequestListener(listener));
        mCallbackBinders.add(remoteBinder);
        // A token has been added and is waiting on user credential validation.
        if (mIsWaitingForCredentials.getAndSet(false)) {
            mExecutor.execute(() -> {
                try {
                    listener.onValidateCredentialsRequest();
                } catch (RemoteException e) {
                    loge(TAG, "Error while notifying enrollment listener.", e);
                }
            });
        }
    }

    @Override
    public void removeOnValidateCredentialsRequestListener(
            IOnValidateCredentialsRequestListener listener)  {
        IBinder binder = listener.asBinder();
        mEnrollmentCallbacks.remove(binder);
        removeRemoteBinder(binder);
    }

    @Override
    public void setTrustedDeviceAgentDelegate(ITrustedDeviceAgentDelegate trustAgentDelegate) {

        mTrustAgentDelegate = trustAgentDelegate;

        if (trustAgentDelegate == null) {
            return;
        }

        // Add pending token if present.
        if (mPendingToken != null) {
            try {
                trustAgentDelegate.addEscrowToken(mPendingToken, ActivityManager.getCurrentUser());
            } catch (RemoteException e) {
                loge(TAG, "Error while adding token through delegate.", e);
            }
            return;
        }

        // Unlock with pending credentials if present.
        if (mPendingCredentials != null) {
            unlockUser(mPendingCredentials.mDeviceId, mPendingCredentials.mPhoneCredentials);
            mPendingCredentials = null;
        }
    }

    private boolean areCredentialsValid(@Nullable PhoneCredentials credentials) {
        return credentials != null && credentials.getEscrowToken() != null
                && credentials.getHandle() != null;
    }

    private void onMessageFromUntrustedDevice(@NonNull CompanionDevice device,
            @NonNull byte[] message) {
        logd(TAG, "Received a new message from untrusted device " + device.getDeviceId() + ".");
        PhoneCredentials credentials = null;
        try {
            credentials = PhoneCredentials.parseFrom(message);
        } catch (InvalidProtocolBufferException e) {
            // Intentional if enrolling a new device. Error logged below if in wrong state.
        }

        // Start enrollment if escrow token was sent instead of credentials.
        if (areCredentialsValid(credentials)) {
            logw(TAG, "Received credentials from an untrusted device.");
            // TODO(b/145618412) Notify device that it is no longer trusted.
            return;
        }
        if (message.length != ESCROW_TOKEN_LENGTH) {
            logw(TAG, "Received invalid escrow token of length " + message.length + ". Ignoring.");
            return;
        }

        startEnrollment(device, message);
    }

    private void onMessageFromTrustedDevice(@NonNull CompanionDevice device,
            @NonNull TrustedDeviceEntity entity, @NonNull byte[] message) {
        logd(TAG, "Received a new message from trusted device " + device.getDeviceId() + ".");
        PhoneCredentials credentials = null;
        try {
            credentials = PhoneCredentials.parseFrom(message);
        } catch (InvalidProtocolBufferException e) {
            // Intentional if enrolling a new device. Error logged below if in wrong state.
        }

        if (!areCredentialsValid(credentials)) {
            loge(TAG, "Unable to parse credentials from device. Aborting unlock.");
            if (message.length == ESCROW_TOKEN_LENGTH) {
                startEnrollment(device, message);
            }
            return;
        }

        if (entity.userId != ActivityManager.getCurrentUser()) {
            logw(TAG, "Received credentials from background user " + entity.userId
                    + ". Ignoring.");
            return;
        }

        TrustedDeviceEventLog.onCredentialsReceived();

        if (mTrustAgentDelegate == null) {
            logd(TAG, "No trust agent delegate set yet. Credentials will be delivered once "
                    + "set.");
            mPendingCredentials = new PendingCredentials(device.getDeviceId(), credentials);
            return;
        }

        unlockUser(device.getDeviceId(), credentials);
    }

    private void notifyTrustedDeviceCallbacks(Consumer<ITrustedDeviceCallback> notification) {
        mTrustedDeviceCallbacks.forEach((iBinder, callback) -> mExecutor.execute(() ->
                notification.accept(callback)));
    }

    private void notifyEnrollmentCallbacks(
            Consumer<IOnValidateCredentialsRequestListener> notification ) {
        mEnrollmentCallbacks.forEach((iBinder, callback) -> mExecutor.execute(() ->
                notification.accept(callback)));
    }

    private void notifyAssociatedDeviceCallbacks(
            Consumer<IDeviceAssociationCallback> notification ) {
        mAssociatedDeviceCallbacks.forEach((iBinder, callback) -> mExecutor.execute(() ->
                notification.accept(callback)));
    }

    private void removeRemoteBinder(IBinder binder) {
        RemoteCallbackBinder remoteBinderToRemove = null;
        for (RemoteCallbackBinder remoteBinder : mCallbackBinders) {
            if (remoteBinder.getCallbackBinder().equals(binder)) {
                remoteBinderToRemove = remoteBinder;
                break;
            }
        }
        if (remoteBinderToRemove != null) {
            remoteBinderToRemove.cleanUp();
            mCallbackBinders.remove(remoteBinderToRemove);
        }
    }

    private final TrustedDeviceFeature.Callback mFeatureCallback =
            new TrustedDeviceFeature.Callback() {
        @Override
        public void onMessageReceived(CompanionDevice device, byte[] message) {
            TrustedDeviceEntity trustedDevice = mDatabase.getTrustedDevice(device.getDeviceId());
            if (trustedDevice == null) {
                onMessageFromUntrustedDevice(device, message);
                return;
            }

            onMessageFromTrustedDevice(device, trustedDevice, message);
        }

        @Override
        public void onDeviceError(CompanionDevice device, int error) {
        }
    };

    private final TrustedDeviceFeature.AssociatedDeviceCallback mAssociatedDeviceCallback =
            new TrustedDeviceFeature.AssociatedDeviceCallback() {
        @Override
        public void onAssociatedDeviceAdded(AssociatedDevice device) {
            notifyAssociatedDeviceCallbacks(callback -> {
                try {
                    callback.onAssociatedDeviceAdded(device);
                } catch (RemoteException e) {
                    loge(TAG, "Failed to notify that an associated device has been added.", e);
                }
            });
        }

        @Override
        public void onAssociatedDeviceRemoved(AssociatedDevice device) {
            List<TrustedDevice> devices = getTrustedDevicesForActiveUser();
            if (devices == null || devices.isEmpty()) {
                return;
            }
            TrustedDevice deviceToRemove = null;
            for (TrustedDevice trustedDevice : devices) {
                if (trustedDevice.getDeviceId().equals(device.getDeviceId())) {
                    deviceToRemove = trustedDevice;
                    break;
                }
            }
            if (deviceToRemove != null) {
                removeTrustedDevice(deviceToRemove);
            }
            notifyAssociatedDeviceCallbacks(callback -> {
                try {
                    callback.onAssociatedDeviceRemoved(device);
                } catch (RemoteException e) {
                    loge(TAG, "Failed to notify that an associated device has been " +
                            "removed.", e);
                }
            });
        }

        @Override
        public void onAssociatedDeviceUpdated(AssociatedDevice device) {
            notifyAssociatedDeviceCallbacks(callback -> {
                try {
                    callback.onAssociatedDeviceUpdated(device);
                } catch (RemoteException e) {
                    loge(TAG, "Failed to notify that an associated device has been " +
                            "updated.", e);
                }
            });
        }
    };

    private static class PendingCredentials {
        final String mDeviceId;
        final PhoneCredentials mPhoneCredentials;

        PendingCredentials(@NonNull String deviceId, @NonNull PhoneCredentials credentials) {
            mDeviceId = deviceId;
            mPhoneCredentials = credentials;
        }
    }
}
