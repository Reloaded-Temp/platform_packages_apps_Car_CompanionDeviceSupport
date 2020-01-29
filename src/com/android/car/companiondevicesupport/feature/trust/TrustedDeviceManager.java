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
import android.os.RemoteException;
import android.os.UserHandle;

import androidx.room.Room;

import com.android.car.companiondevicesupport.api.external.CompanionDevice;
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
import com.android.car.connecteddevice.util.ThreadSafeCallbacks;
import com.android.car.protobuf.InvalidProtocolBufferException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;


/** Manager for the feature of unlocking the head unit with a user's trusted device. */
public class TrustedDeviceManager extends ITrustedDeviceManager.Stub {

    private static final String TAG = "TrustedDeviceManager";

    /** Length of token generated on a trusted device. */
    private static final int ESCROW_TOKEN_LENGTH = 8;

    private final ThreadSafeCallbacks<ITrustedDeviceCallback> mTrustedDeviceCallbacks =
            new ThreadSafeCallbacks<>();

    private final ThreadSafeCallbacks<IOnValidateCredentialsRequestListener> mEnrollmentCallbacks =
            new ThreadSafeCallbacks<>();

    private final Context mContext;

    private final TrustedDeviceFeature mTrustedDeviceFeature;

    private final Executor mExecutor = Executors.newSingleThreadExecutor();

    private TrustedDeviceDao mDatabase;

    private ITrustedDeviceAgentDelegate mTrustAgentDelegate;

    private CompanionDevice mPendingDevice;

    private byte[] mPendingToken;

    private PhoneCredentials mPendingCredentials;

    private boolean mIsWaitingForCredentials;


    TrustedDeviceManager(@NonNull Context context) {
        mContext = context;
        mTrustedDeviceFeature = new TrustedDeviceFeature(context);
        mTrustedDeviceFeature.setCallback(mFeatureCallback);
        mTrustedDeviceFeature.start();
        mDatabase = Room.databaseBuilder(context, TrustedDeviceDatabase.class,
                TrustedDeviceDatabase.DATABASE_NAME).build().trustedDeviceDao();
        logd(TAG, "TrustedDeviceManager created successfully.");
    }

    void cleanup() {
        mPendingToken = null;
        mPendingDevice = null;
        mPendingCredentials = null;
        mIsWaitingForCredentials = false;
        mTrustedDeviceCallbacks.clear();
        mEnrollmentCallbacks.clear();
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

    private void unlockUser(@NonNull PhoneCredentials credentials) {
        logd(TAG, "Unlocking with credentials.");
        try {
            mTrustAgentDelegate.unlockUserWithToken(credentials.getEscrowToken().toByteArray(),
                    ByteUtils.bytesToLong(credentials.getHandle().toByteArray()),
                    ActivityManager.getCurrentUser());
        } catch (RemoteException e) {
            loge(TAG, "Error while unlocking user through delegate.", e);
        }
    }

    @Override
    public void onEscrowTokenAdded(int userId, long handle) {
        logd(TAG, "Escrow token has been successfully added.");
        mPendingToken = null;
        mIsWaitingForCredentials = true;
        mEnrollmentCallbacks.invoke(callback -> {
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
        entity.id = mPendingDevice.getDeviceId();
        entity.userId = userId;
        entity.handle = handle;
        mDatabase.addOrReplaceTrustedDevice(entity);
        mPendingDevice = null;
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
        }
    }

    @Override
    public void registerTrustedDeviceCallback(ITrustedDeviceCallback callback)  {
        mTrustedDeviceCallbacks.add(callback, mExecutor);
    }

    @Override
    public void unregisterTrustedDeviceCallback(ITrustedDeviceCallback callback) {
        mTrustedDeviceCallbacks.remove(callback);
    }

    @Override
    public void addOnValidateCredentialsRequestListener(
            IOnValidateCredentialsRequestListener listener) {
        mEnrollmentCallbacks.add(listener, mExecutor);

        // A token has been added and is waiting on user credential validation.
        if (mIsWaitingForCredentials) {
            mIsWaitingForCredentials = false;
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
            IOnValidateCredentialsRequestListener trustEnrollmentCallback)  {
        mEnrollmentCallbacks.remove(trustEnrollmentCallback);
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
            unlockUser(mPendingCredentials);
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

        if (mTrustAgentDelegate == null) {
            logd(TAG, "No trust agent delegate set yet. Credentials will be delivered once "
                    + "set.");
            mPendingCredentials = credentials;
            return;
        }

        unlockUser(credentials);
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
}
