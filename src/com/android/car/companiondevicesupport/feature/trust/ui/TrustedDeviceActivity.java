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

import static com.android.car.connecteddevice.util.SafeLog.logd;
import static com.android.car.connecteddevice.util.SafeLog.loge;
import static com.android.car.connecteddevice.util.SafeLog.logw;

import android.annotation.Nullable;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;

import androidx.fragment.app.FragmentActivity;

import com.android.car.companiondevicesupport.R;
import com.android.car.companiondevicesupport.api.internal.trust.IOnValidateCredentialsRequestListener;
import com.android.car.companiondevicesupport.api.internal.trust.ITrustedDeviceManager;
import com.android.car.companiondevicesupport.feature.trust.TrustedDeviceConstants;
import com.android.car.companiondevicesupport.feature.trust.TrustedDeviceManagerService;


/** Activity for enrolling and viewing trusted devices. */
public class TrustedDeviceActivity extends FragmentActivity {

    private static final String TAG = "TrustedDeviceActivity";

    private static final int ACTIVATE_TOKEN_REQUEST_CODE = 1;

    private static final int CREATE_LOCK_REQUEST_CODE = 2;

    private static final String ACTION_LOCK_SETTINGS = "android.car.settings.SCREEN_LOCK_ACTIVITY";

    private KeyguardManager mKeyguardManager;

    private ITrustedDeviceManager mTrustedDeviceManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.trust_activity);

        Intent intent = new Intent(this, TrustedDeviceManagerService.class);
        bindServiceAsUser(intent, mServiceConnection, Context.BIND_AUTO_CREATE, UserHandle.SYSTEM);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        switch (requestCode) {
            case ACTIVATE_TOKEN_REQUEST_CODE:
                if (resultCode != RESULT_OK) {
                    loge(TAG, "Lock screen was unsuccessful. Returned result code: " + resultCode
                            + ".");
                    return;
                }
                logd(TAG, "Credentials accepted. Waiting for TrustAgent to activate token.");
                break;
            case CREATE_LOCK_REQUEST_CODE:
                KeyguardManager keyguardManager = getKeyguardManager();
                if (keyguardManager == null) {
                    return;
                }
                if (!keyguardManager.isDeviceSecure()) {
                    loge(TAG, "Set up new lock unsuccessful. Returned result code: " + resultCode
                            + ".");
                    return;
                }
                break;
            default:
                logw(TAG, "Unrecognized activity result. Request code: " + requestCode
                        + ". Ignoring.");
                break;
        }
    }

    @Override
    protected void onDestroy() {
        try {
            mTrustedDeviceManager.removeOnValidateCredentialsRequestListener(
                    mOnValidateCredentialsListener);
        } catch (RemoteException e) {
            loge(TAG, "Error while disconnecting from service.", e);
        }
        unbindService(mServiceConnection);
        super.onDestroy();
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

    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mTrustedDeviceManager = ITrustedDeviceManager.Stub.asInterface(service);
            try {
                mTrustedDeviceManager.addOnValidateCredentialsRequestListener(
                        mOnValidateCredentialsListener);
            } catch (RemoteException e) {
                loge(TAG, "Error while connecting to service.");
            }

            logd(TAG, "Successfully connected to TrustedDeviceManager.");

            Intent incomingIntent = getIntent();
            if (incomingIntent == null || !incomingIntent.getBooleanExtra(
                    TrustedDeviceConstants.INTENT_EXTRA_ENROLL_NEW_TOKEN, false)) {
                return;
            }

            KeyguardManager keyguardManager = getKeyguardManager();
            if (keyguardManager == null) {
                return;
            }
            if (!keyguardManager.isDeviceSecure()) {
                logd(TAG, "User has not set a lock screen. Redirecting to set up.");
                Intent intent = new Intent(ACTION_LOCK_SETTINGS);
                startActivityForResult(intent, CREATE_LOCK_REQUEST_CODE);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
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
}
