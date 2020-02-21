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

package com.android.car.companiondevicesupport.feature.trust.ui;

import static com.android.car.companiondevicesupport.activity.AssociationActivity.ACTION_ASSOCIATION_SETTING;
import static com.android.car.companiondevicesupport.activity.AssociationActivity.ASSOCIATED_DEVICE_DATA_NAME_EXTRA;
import static com.android.car.connecteddevice.util.SafeLog.logd;
import static com.android.car.connecteddevice.util.SafeLog.loge;
import static com.android.car.connecteddevice.util.SafeLog.logw;

import android.annotation.Nullable;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;

import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.ViewModelProviders;

import com.android.car.companiondevicesupport.R;
import com.android.car.companiondevicesupport.api.external.AssociatedDevice;
import com.android.car.companiondevicesupport.api.external.CompanionDevice;
import com.android.car.companiondevicesupport.api.external.IDeviceAssociationCallback;
import com.android.car.companiondevicesupport.api.internal.trust.IOnValidateCredentialsRequestListener;
import com.android.car.companiondevicesupport.api.internal.trust.ITrustedDeviceCallback;
import com.android.car.companiondevicesupport.api.internal.trust.ITrustedDeviceManager;
import com.android.car.companiondevicesupport.api.internal.trust.TrustedDevice;
import com.android.car.companiondevicesupport.feature.trust.TrustedDeviceConstants;
import com.android.car.companiondevicesupport.feature.trust.TrustedDeviceManagerService;
import com.android.car.ui.toolbar.Toolbar;

import java.util.List;


/** Activity for enrolling and viewing trusted devices. */
public class TrustedDeviceActivity extends FragmentActivity {

    private static final String TAG = "TrustedDeviceActivity";

    private static final int ACTIVATE_TOKEN_REQUEST_CODE = 1;

    private static final int CREATE_LOCK_REQUEST_CODE = 2;

    private static final int RETRIEVE_ASSOCIATED_DEVICE_REQUEST_CODE = 3;

    private static final String ACTION_LOCK_SETTINGS = "android.car.settings.SCREEN_LOCK_ACTIVITY";

    private static final String DEVICE_DETAIL_FRAGMENT_TAG = "TrustedDeviceDetailFragmentTag";

    private static final String DEVICE_NOT_CONNECTED_DIALOG_TAG =
            "DeviceNotConnectedDialogFragmentTag";

        private static final String CREATE_PROFILE_LOCK_DIALOG_TAG =
            "CreateProfileLockdialogFragmentTag";

    private KeyguardManager mKeyguardManager;

    private ITrustedDeviceManager mTrustedDeviceManager;

    private Toolbar mToolbar;

    private TrustedDeviceViewModel mModel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.base_activity);
        observeViewModel();
        resumePreviousState(savedInstanceState);
        mToolbar = findViewById(R.id.toolbar);
        mToolbar.setTitle(R.string.trusted_device_feature_title);
        mToolbar.showProgressBar();

        Intent intent = new Intent(this, TrustedDeviceManagerService.class);
        bindServiceAsUser(intent, mServiceConnection, Context.BIND_AUTO_CREATE, UserHandle.SYSTEM);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        switch (requestCode) {
            case ACTIVATE_TOKEN_REQUEST_CODE:
                if (resultCode != RESULT_OK) {
                    loge(TAG, "Lock screen was unsuccessful. Returned result code: " +
                            resultCode + ".");
                    return;
                }
                logd(TAG, "Credentials accepted. Waiting for TrustAgent to activate " +
                        "token.");
                break;
            case CREATE_LOCK_REQUEST_CODE:
                if (!isDeviceSecure()) {
                    loge(TAG, "Set up new lock unsuccessful. Returned result code: "
                            + resultCode + ".");

                    return;
                }
                break;
            case RETRIEVE_ASSOCIATED_DEVICE_REQUEST_CODE:
                AssociatedDevice device = data.getParcelableExtra(ASSOCIATED_DEVICE_DATA_NAME_EXTRA);
                if (device == null) {
                    loge(TAG, "No valid associated device.");
                    return;
                }
                showTrustedDeviceDetailFragment(device);
                break;
            default:
                logw(TAG, "Unrecognized activity result. Request code: " + requestCode
                        + ". Ignoring.");
                break;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent == null || !intent.getBooleanExtra(
                TrustedDeviceConstants.INTENT_EXTRA_ENROLL_NEW_TOKEN, false)) {
            return;
        }
        maybePromptToCreatePassword();
    }

    @Override
    protected void onDestroy() {
        try {
            mTrustedDeviceManager.removeOnValidateCredentialsRequestListener(
                    mOnValidateCredentialsListener);
            mTrustedDeviceManager.unregisterTrustedDeviceCallback(mTrustedDeviceCallback);
            mTrustedDeviceManager.unregisterAssociatedDeviceCallback(mDeviceAssociationCallback);
        } catch (RemoteException e) {
            loge(TAG, "Error while disconnecting from service.", e);
        }
        unbindService(mServiceConnection);
        super.onDestroy();
    }

    private void resumePreviousState(Bundle saveInstanceState) {
        if (saveInstanceState == null) {
            return;
        }
        CreateProfileLockDialogFragment createProfileLockDialogFragment =
                (CreateProfileLockDialogFragment) getSupportFragmentManager()
                .findFragmentByTag(CREATE_PROFILE_LOCK_DIALOG_TAG);
        if (createProfileLockDialogFragment != null) {
            createProfileLockDialogFragment.setOnConfirmListener((d, w) -> createScreenLock());
        }
    }

    private void observeViewModel() {
        mModel = ViewModelProviders.of(this).get(TrustedDeviceViewModel.class);
        mModel.getDeviceToDisable().observe(this, trustedDevice -> {
            if (trustedDevice == null) {
                return;
            }
            mModel.setDeviceToDisable(null);
            if (mTrustedDeviceManager == null) {
                loge(TAG, "Failed to remove trusted device. service not connected.");
                return;
            }
            try {
                logd(TAG, "calling removeTrustedDevice");
                mTrustedDeviceManager.removeTrustedDevice(trustedDevice);
            } catch (RemoteException e) {
                loge(TAG, "Failed to remove trusted device.", e);
            }
        });
        mModel.getDeviceToEnable().observe(this, associatedDevice -> {
            if (associatedDevice == null) {
                return;
            }
            mModel.setDeviceToEnable(null);
            attemptInitiatingEnrollment(associatedDevice);
        });
    }

    private void attemptInitiatingEnrollment(AssociatedDevice device) {
        if (!isCompanionDeviceConnected(device.getDeviceId())) {
            DeviceNotConnectedDialogFragment fragment = new DeviceNotConnectedDialogFragment();
            fragment.show(getSupportFragmentManager(), DEVICE_NOT_CONNECTED_DIALOG_TAG);
            return;
        }
        initiateEnrollment(device);
    }

    private boolean isCompanionDeviceConnected(String deviceId) {
        if (mTrustedDeviceManager == null) {
            loge(TAG, "Failed to check connection status for device: " + deviceId +
                    "Service not connected.");
            return false;
        }
        List<CompanionDevice> devices = null;
        try {
            devices = mTrustedDeviceManager.getActiveUserConnectedDevices();
        } catch (RemoteException e) {
            loge(TAG, "Failed to check connection status for device: " + deviceId, e);
            return false;
        }
        if (devices == null || devices.isEmpty()) {
            return false;
        }
        for (CompanionDevice device: devices) {
            if (device.getDeviceId().equals(deviceId)) {
                return true;
            }
        }
        return false;
    }

    private void initiateEnrollment(AssociatedDevice device) {
        //TODO(148569416): Send message to phone to request escrow token
    }

    private void validateCredentials() {
        logd(TAG, "Validating credentials to activate token.");
        KeyguardManager keyguardManager = getKeyguardManager();
        if (keyguardManager == null) {
            return;
        }
        @SuppressWarnings("deprecation") // Car does not support Biometric lock as of now.
        Intent confirmIntent = keyguardManager.createConfirmDeviceCredentialIntent(
                "PLACEHOLDER PROMPT TITLE", "PLACEHOLDER PROMPT MESSAGE");
        if (confirmIntent == null) {
            loge(TAG, "User either has no lock screen, or a token is already registered.");
            return;
        }

        logd(TAG, "Prompting user to validate credentials.");
        startActivityForResult(confirmIntent, ACTIVATE_TOKEN_REQUEST_CODE);
    }

    private void maybePromptToCreatePassword() {
        if (isDeviceSecure()) {
            return;
        }

        CreateProfileLockDialogFragment fragment = CreateProfileLockDialogFragment.newInstance(
                (d, w) -> createScreenLock());
        fragment.show(getSupportFragmentManager(), CREATE_PROFILE_LOCK_DIALOG_TAG);
    }

    private void createScreenLock() {
        if (isDeviceSecure()) {
            return;
        }
        logd(TAG, "User has not set a lock screen. Redirecting to set up.");
        Intent intent = new Intent(ACTION_LOCK_SETTINGS);
        startActivityForResult(intent, CREATE_LOCK_REQUEST_CODE);
    }

    private boolean isDeviceSecure() {
        KeyguardManager keyguardManager = getKeyguardManager();
        if (keyguardManager == null) {
            return false;
        }
        return keyguardManager.isDeviceSecure();
    }

    private void retrieveAssociatedDevice() {
        Intent intent = new Intent(ACTION_ASSOCIATION_SETTING);
        startActivityForResult(intent, RETRIEVE_ASSOCIATED_DEVICE_REQUEST_CODE);
    }

    private void showTrustedDeviceDetailFragment(AssociatedDevice device) {
        mToolbar.hideProgressBar();
        TrustedDeviceDetailFragment fragment = TrustedDeviceDetailFragment.newInstance(device);
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, fragment, DEVICE_DETAIL_FRAGMENT_TAG)
                .commit();
    }

    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mTrustedDeviceManager = ITrustedDeviceManager.Stub.asInterface(service);
            try {
                mTrustedDeviceManager.addOnValidateCredentialsRequestListener(
                        mOnValidateCredentialsListener);
                mTrustedDeviceManager.registerTrustedDeviceCallback(mTrustedDeviceCallback);
                mTrustedDeviceManager.registerAssociatedDeviceCallback(mDeviceAssociationCallback);
                mModel.setTrustedDevices(mTrustedDeviceManager.getTrustedDevicesForActiveUser());
            } catch (RemoteException e) {
                loge(TAG, "Error while connecting to service.");
            }

            logd(TAG, "Successfully connected to TrustedDeviceManager.");

            retrieveAssociatedDevice();

            Intent incomingIntent = getIntent();
            if (incomingIntent == null || !incomingIntent.getBooleanExtra(
                    TrustedDeviceConstants.INTENT_EXTRA_ENROLL_NEW_TOKEN, false)) {
                return;
            }
            maybePromptToCreatePassword();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
        }
    };

    private final ITrustedDeviceCallback mTrustedDeviceCallback =
            new ITrustedDeviceCallback.Stub() {
        @Override
        public void onTrustedDeviceAdded(TrustedDevice device) {
            logd(TAG, "onTrustedDeviceAdded");
            mModel.setEnabledDevice(device);
        }

        @Override
        public void onTrustedDeviceRemoved(TrustedDevice device) {
            logd(TAG, "onTrustedDeviceRemoved");
            mModel.setDisabledDevice(device);
        }
    };

    private final IDeviceAssociationCallback mDeviceAssociationCallback =
            new IDeviceAssociationCallback.Stub() {
        @Override
        public void onAssociatedDeviceAdded(AssociatedDevice device) { }

        @Override
        public void onAssociatedDeviceRemoved(AssociatedDevice device) {
            if (mTrustedDeviceManager == null) {
                loge(TAG, "Failed to remove trusted device on associated device removed.");
                return;
            }

            try {
                List<TrustedDevice> trustedDevices = mTrustedDeviceManager
                        .getTrustedDevicesForActiveUser();
                if (trustedDevices == null || trustedDevices.isEmpty()) {
                    return;
                }
                TrustedDevice deviceToRemove = null;
                for (TrustedDevice trustedDevice : trustedDevices) {
                    if (trustedDevice.getDeviceId().equals(device.getDeviceId())) {
                        deviceToRemove = trustedDevice;
                        break;
                    }
                }
                if (deviceToRemove != null) {
                    mTrustedDeviceManager.removeTrustedDevice(deviceToRemove);
                    finish();
                }
            } catch (RemoteException e) {
                loge(TAG, "Error while responding to associated device removed event.", e);
            }

        }

        @Override
        public void onAssociatedDeviceUpdated(AssociatedDevice device) {
            if (device != null) {
                mModel.setAssociatedDevice(device);
            }
        }
    };

    @Nullable
    private KeyguardManager getKeyguardManager() {
        if (mKeyguardManager == null) {
            mKeyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        }
        if (mKeyguardManager == null) {
            loge(TAG, "Unable to get KeyguardManager.");
        }
        return mKeyguardManager;
    }

    private IOnValidateCredentialsRequestListener mOnValidateCredentialsListener =
            new IOnValidateCredentialsRequestListener.Stub() {

        @Override
        public void onValidateCredentialsRequest() {
            validateCredentials();
        }
    };

    /** Dialog Fragment to notify that the device is not actively connected. */
    public static class DeviceNotConnectedDialogFragment extends DialogFragment {
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            return new AlertDialog.Builder(getActivity())
                    .setTitle(getString(R.string.device_not_connected_dialog_title))
                    .setMessage(getString(R.string.device_not_connected_dialog_message))
                    .setNegativeButton(getString(R.string.ok), null)
                    .setCancelable(true)
                    .create();
        }
    }

    /** Dialog Fragment to notify that a profile lock is needed to continue enrollment. */
    public static class CreateProfileLockDialogFragment extends DialogFragment {
        private DialogInterface.OnClickListener mOnConfirmListener;

        static CreateProfileLockDialogFragment newInstance(
                DialogInterface.OnClickListener listener) {
            CreateProfileLockDialogFragment fragment = new CreateProfileLockDialogFragment();
            fragment.setOnConfirmListener(listener);
            return fragment;
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            return new AlertDialog.Builder(getActivity())
                    .setTitle(getString(R.string.create_profile_lock_dialog_title))
                    .setMessage(getString(R.string.create_profile_lock_dialog_message))
                    .setNegativeButton(getString(R.string.cancel), null)
                    .setPositiveButton(getString(R.string.continue_button), mOnConfirmListener)
                    .setCancelable(true)
                    .create();
        }

        void setOnConfirmListener(DialogInterface.OnClickListener onConfirmListener) {
            mOnConfirmListener = onConfirmListener;
        }
    }
}
