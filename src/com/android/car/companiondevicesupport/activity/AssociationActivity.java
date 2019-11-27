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

import static com.android.car.companiondevicesupport.service.CompanionDeviceSupportService.ACTION_BIND_INTERNAL;
import static com.android.car.connecteddevice.util.SafeLog.logd;
import static com.android.car.connecteddevice.util.SafeLog.loge;

import android.annotation.NonNull;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.widget.Button;

import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;

import com.android.car.companiondevicesupport.R;
import com.android.car.companiondevicesupport.service.CompanionDeviceSupportService;
import com.android.car.companiondevicesupport.api.internal.IAssociatedDeviceManager;
import com.android.car.companiondevicesupport.api.internal.IAssociationCallback;

/** Activity class for association */
public class AssociationActivity extends FragmentActivity {
    private static final String TAG = "CompanionAssociationActivity";
    // Arbitrary delay time to show car selecting dialog as there is a delay for the bluetooth
    // adapter to change its name.
    private static final long SHOW_DIALOG_DELAY_MS = 200L;
    private static final String SELECT_CAR_DIALOG_TAG = "SelectCarDialog";
    private static final String PAIRING_CODE_DIALOG_TAG = "PairingCodeDialog";
    private static final String ASSOCIATED_DIALOG_TAG = "AssociatedDialog";

    private Button mButton;
    private IAssociatedDeviceManager mAssociatedDeviceManager;

    private Handler mHandler = new Handler(Looper.getMainLooper());

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mAssociatedDeviceManager = IAssociatedDeviceManager.Stub.asInterface(service);
            mButton.setEnabled(true);
            logd(TAG, "Service connected:" + name.getClassName());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mAssociatedDeviceManager = null;
            logd(TAG, "Service disconnected: " + name.getClassName());
            mButton.setEnabled(false);
        }
    };

    private final IAssociationCallback mAssociationCallback = new IAssociationCallback.Stub() {
        @Override
        public void onAssociationStartSuccess(String deviceName) {
            runOnUiThread(() -> showAssociationDialog(deviceName));
        }
        @Override
        public void onAssociationStartFailure() {
            dismissSelectCarDialogFragment();
            loge(TAG, "Failed to start association.");
        }

        @Override
        public void onAssociationError(int error) throws RemoteException {
            dismissSelectCarDialogFragment();
            loge(TAG, "Encountered an error during association: " + error);
        }

        @Override
        public void onVerificationCodeAvailable(String code) throws RemoteException {
            runOnUiThread(() -> {
                // Need to run this part of code in UI thread to show the dialog as the callback is
                // triggered in a separate thread.
                dismissSelectCarDialogFragment();
                logd(TAG, "Showing pairing code: " + code);
                Bundle bundle = new Bundle();
                bundle.putString(PairingCodeDialogFragment.PAIRING_CODE_KEY, code);
                PairingCodeDialogFragment pairingCodeDialogFragment =
                        new PairingCodeDialogFragment();
                pairingCodeDialogFragment.setArguments(bundle);
                pairingCodeDialogFragment.setOnAcceptListener((d, which) -> acceptVerification());
                pairingCodeDialogFragment.setOnRejectListener((d, which) -> stopAssociation());
                pairingCodeDialogFragment.show(getSupportFragmentManager(),
                        PAIRING_CODE_DIALOG_TAG);
            });
        }

        @Override
        public void onAssociationCompleted() {
            runOnUiThread(() -> {
                AssociatedDialogFragment fragment = new AssociatedDialogFragment();
                fragment.show(getSupportFragmentManager(), ASSOCIATED_DIALOG_TAG);
            });
        }

    };

    @Override
    public void onCreate(Bundle saveInstanceState) {
        super.onCreate(saveInstanceState);
        setContentView(R.layout.settings_activity);
        mButton = findViewById(R.id.add_device_button);
        mButton.setOnClickListener(v -> {
            try {
                mAssociatedDeviceManager.startAssociation(mAssociationCallback);
            } catch (RemoteException e) {
                loge(TAG, "Failed to start association.", e);
            }
        });
        if (saveInstanceState != null) {
            registerDialogFragmentListeners();
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        Intent intent = new Intent(this, CompanionDeviceSupportService.class);
        intent.setAction(ACTION_BIND_INTERNAL);
        bindServiceAsUser(intent, mConnection, Context.BIND_AUTO_CREATE, UserHandle.SYSTEM);
    }

    @Override
    public void onStop() {
        super.onStop();
        unbindService(mConnection);
    }

    private void showAssociationDialog(String deviceName) {
        SelectCarDialogFragment fragment = SelectCarDialogFragment.newInstance(deviceName);
        fragment.setOnCancelListener((d, which) -> stopAssociation());
        fragment.show(getSupportFragmentManager(), SELECT_CAR_DIALOG_TAG);
    }

    private void dismissSelectCarDialogFragment() {
        SelectCarDialogFragment selectCarDialogFragment =
                (SelectCarDialogFragment) getSupportFragmentManager()
                        .findFragmentByTag(SELECT_CAR_DIALOG_TAG);
        if (selectCarDialogFragment == null) {
            loge(TAG, "Failed to retrieve select car dialog.");
            stopAssociation();
            return;
        }
        selectCarDialogFragment.dismiss();
    }

    private void registerDialogFragmentListeners() {
        SelectCarDialogFragment selectCarDialogFragment =
                (SelectCarDialogFragment) getSupportFragmentManager()
                        .findFragmentByTag(SELECT_CAR_DIALOG_TAG);
        if (selectCarDialogFragment != null) {
            selectCarDialogFragment.setOnCancelListener((d, which) -> stopAssociation());
        }

        PairingCodeDialogFragment pairingCodeDialogFragment =
                (PairingCodeDialogFragment) getSupportFragmentManager()
                .findFragmentByTag(PAIRING_CODE_DIALOG_TAG);
        if (pairingCodeDialogFragment != null) {
            pairingCodeDialogFragment.setOnAcceptListener((d, which) -> acceptVerification());
            pairingCodeDialogFragment.setOnRejectListener((d, which) -> stopAssociation());
        }
    }

    private void acceptVerification() {
        try {
            if (mAssociatedDeviceManager == null) {
                loge(TAG, "Failed to accept verification. Service not connected.");
                return;
            }
            mAssociatedDeviceManager.acceptVerification();
        } catch (RemoteException e) {
            loge(TAG, "AcceptVerification", e);
        }
    }

    private void stopAssociation() {
        try {
            if (mAssociatedDeviceManager == null) {
                loge(TAG, "Failed to stop association. Service not connected.");
                return;
            }
            mAssociatedDeviceManager.stopAssociation(mAssociationCallback);
        } catch (RemoteException e) {
            loge(TAG, "StopAssociation", e);
        }
    }

    /** Dialog fragment notifies the user to select the car. */
    public static class SelectCarDialogFragment extends DialogFragment {
        private static final String DEVICE_NAME_KEY = "deviceName";
        private DialogInterface.OnClickListener mOnCancelListener;

        static SelectCarDialogFragment newInstance(@NonNull String deviceName) {
            Bundle bundle = new Bundle();
            bundle.putString(DEVICE_NAME_KEY, deviceName);
            SelectCarDialogFragment fragment = new SelectCarDialogFragment();
            fragment.setArguments(bundle);
            return fragment;
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            Bundle bundle = getArguments();
            String deviceName = bundle.getString(DEVICE_NAME_KEY);
            return new AlertDialog.Builder(getActivity())
                    .setTitle(getString(R.string.associated_device_select_device, deviceName))
                    .setNegativeButton(getString(R.string.cancel), mOnCancelListener)
                    .setCancelable(false)
                    .create();
        }

        void setOnCancelListener(DialogInterface.OnClickListener onCancelListener) {
            mOnCancelListener = onCancelListener;
        }
    }

    /** Dialog fragment shows the pairing code. */
    public static class PairingCodeDialogFragment extends DialogFragment {
        private static final String PAIRING_CODE_KEY = "PairingCode";

        private DialogInterface.OnClickListener mOnAcceptListener;
        private DialogInterface.OnClickListener mOnRejectListener;
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            Bundle bundle = getArguments();
            String pairingCode = bundle.getString(PAIRING_CODE_KEY);
            return new AlertDialog.Builder(getActivity())
                    .setTitle(getString(R.string.associated_device_pairing_code_title))
                    .setMessage(pairingCode)
                    .setPositiveButton(getString(R.string.accept), mOnAcceptListener)
                    .setNegativeButton(getString(R.string.reject), mOnRejectListener)
                    .setCancelable(false)
                    .create();
        }

        void setOnAcceptListener(DialogInterface.OnClickListener onAcceptListener) {
            mOnAcceptListener = onAcceptListener;
        }

        void setOnRejectListener(DialogInterface.OnClickListener onRejectListener) {
            mOnRejectListener = onRejectListener;
        }
    }

    /** Dialog fragment notifies the device has been successfully associated. */
    public static class AssociatedDialogFragment extends DialogFragment {
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            return new AlertDialog.Builder(getActivity())
                    .setTitle(getString(R.string.associated_device_success))
                    .setPositiveButton(getString(R.string.confirm), null)
                    .setCancelable(true)
                    .create();
        }
    }
}
