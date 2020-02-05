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
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.widget.Toast;

import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.ViewModelProviders;

import com.android.car.companiondevicesupport.R;
import com.android.car.companiondevicesupport.api.external.AssociatedDevice;
import com.android.car.companiondevicesupport.service.CompanionDeviceSupportService;
import com.android.car.companiondevicesupport.api.external.IDeviceAssociationCallback;
import com.android.car.companiondevicesupport.api.internal.association.IAssociatedDeviceManager;
import com.android.car.companiondevicesupport.api.internal.association.IAssociationCallback;
import com.android.car.ui.toolbar.MenuItem;
import com.android.car.ui.toolbar.Toolbar;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Activity class for association */
public class AssociationActivity extends FragmentActivity {
    private static final String TAG = "CompanionAssociationActivity";
    private static final String ADD_DEVICE_FRAGMENT_TAG = "AddAssociatedDeviceFragment";
    private static final String DEVICE_DETAIL_FRAGMENT_TAG = "AssociatedDeviceDetailFragment";
    private static final String PAIRING_CODE_FRAGMENT_TAG = "ConfirmPairingCodeFragment";
    private static final String REMOVE_DEVICE_DIALOG_TAG = "RemoveDeviceDialog";
    private static final String DEVICE_TO_REMOVE_KEY = "DeviceToRemoveKey";
    private static final String IS_IN_ASSOCIATION_KEY = "IsInAssociationKey";

    private Toolbar mToolbar;
    private AssociatedDeviceViewModel mModel;
    private IAssociatedDeviceManager mAssociatedDeviceManager;
    private AssociatedDevice mDeviceToRemove;
    private AtomicBoolean mIsInAssociation = new AtomicBoolean(false);

    @Override
    public void onCreate(Bundle saveInstanceState) {
        super.onCreate(saveInstanceState);
        setContentView(R.layout.base_activity);
        mToolbar = findViewById(R.id.toolbar);
        observeViewModel();
        if (saveInstanceState != null) {
            resumePreviousState(saveInstanceState);
        }
        mToolbar.showProgressBar();
    }

    @Override
    public void onStart() {
        super.onStart();
        Intent intent = new Intent(this, CompanionDeviceSupportService.class);
        intent.setAction(ACTION_BIND_ASSOCIATION);
        bindServiceAsUser(intent, mConnection, Context.BIND_AUTO_CREATE, UserHandle.SYSTEM);
    }

    @Override
    public void onStop() {
        super.onStop();
        try {
            mAssociatedDeviceManager.unregisterDeviceAssociationCallback();
            mAssociatedDeviceManager.unregisterAssociationCallback();
        } catch (RemoteException e) {
            loge(TAG, "Failed to unregister DeviceAssociationCallback. ", e);
        }
        unbindService(mConnection);
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putParcelable(DEVICE_TO_REMOVE_KEY, mDeviceToRemove);
        outState.putBoolean(IS_IN_ASSOCIATION_KEY, mIsInAssociation.get());
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        if (mIsInAssociation.get()) {
            stopAssociation();
            mToolbar.hideProgressBar();
        }
    }

    private void observeViewModel() {
        mModel = ViewModelProviders.of(this).get(AssociatedDeviceViewModel.class);
        mModel.isSelected().observe(this, isSelected -> {
            if (isSelected) {
                mModel.setSelected(false);
                if (mAssociatedDeviceManager == null) {
                    loge(TAG, "AssociatedDeviceManager is null.");
                    return;
                }
                try {
                    mAssociatedDeviceManager.startAssociation();
                } catch (RemoteException e) {
                    loge(TAG, "Failed to start association.", e);
                }
            }
        });
        mModel.getDeviceToRemove().observe(this, device -> {
            if (device != null) {
                mModel.setDeviceToRemove(null);
                logd(TAG, "device: "+ device.getDeviceId() + " selected.");
                mDeviceToRemove = device;
                runOnUiThread(() -> showRemoveDeviceDialog(device));
            }
        });
    }

    private void showAddAssociatedDeviceFragment(String deviceName) {
        AddAssociatedDeviceFragment fragment = AddAssociatedDeviceFragment.newInstance(deviceName);
        mToolbar.showProgressBar();
        launchFragment(fragment, ADD_DEVICE_FRAGMENT_TAG);
    }

    private void showConfirmPairingCodeFragment(String pairingCode) {
        ConfirmPairingCodeFragment fragment = ConfirmPairingCodeFragment.newInstance(pairingCode);
        launchFragment(fragment, PAIRING_CODE_FRAGMENT_TAG);
        showConfirmButtons();
        mToolbar.hideProgressBar();
    }

    private void showAssociatedDeviceDetailFragment() {
        AssociatedDeviceDetailFragment fragment = new AssociatedDeviceDetailFragment();
        launchFragment(fragment, DEVICE_DETAIL_FRAGMENT_TAG);
    }

    private void showConfirmButtons() {
        MenuItem cancelButton = MenuItem.builder(this)
                .setTitle(R.string.cancel)
                .setOnClickListener(i -> {
                    stopAssociation();
                    finish();
                }).build();
        MenuItem confirmButton = MenuItem.builder(this)
                .setTitle(R.string.confirm)
                .setOnClickListener(i -> acceptVerification())
                .build();
        if (mToolbar == null) {
            loge(TAG, "Toolbar is null");
            return;
        }
        mToolbar.setMenuItems(Arrays.asList(cancelButton, confirmButton));
    }

    private void dismissConfirmButtons() {
        mToolbar.setMenuItems(null);
    }

    private void showRemoveDeviceDialog(AssociatedDevice device) {
        RemoveDeviceDialogFragment removeDeviceDialogFragment =
                RemoveDeviceDialogFragment.newInstance(device.getDeviceName(),
                        (d, which) -> removeAssociatedDevice(device));
        removeDeviceDialogFragment.show(getSupportFragmentManager(), REMOVE_DEVICE_DIALOG_TAG);
    }

    private void resumePreviousState(Bundle saveInstanceState) {
        mDeviceToRemove = saveInstanceState.getParcelable(DEVICE_TO_REMOVE_KEY);

        mIsInAssociation.set(saveInstanceState.getBoolean(IS_IN_ASSOCIATION_KEY));

        if (getSupportFragmentManager().findFragmentByTag(PAIRING_CODE_FRAGMENT_TAG) != null) {
            showConfirmButtons();
        }

        RemoveDeviceDialogFragment removeDeviceDialogFragment =
                (RemoveDeviceDialogFragment) getSupportFragmentManager()
                .findFragmentByTag(REMOVE_DEVICE_DIALOG_TAG);
        if (removeDeviceDialogFragment != null) {
            removeDeviceDialogFragment.setOnConfirmListener((d, which) ->
                    removeAssociatedDevice(mDeviceToRemove));
        }
    }

    private void launchFragment(Fragment fragment, String tag) {
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, fragment, tag)
                .commit();
    }

    private void acceptVerification() {
        if (mAssociatedDeviceManager == null) {
            loge(TAG, "Failed to accept verification. Service not connected.");
            return;
        }
        try {
            mAssociatedDeviceManager.acceptVerification();
        } catch (RemoteException e) {
            loge(TAG, "Error while accepting verification.", e);
        }
        dismissConfirmButtons();
    }

    private void stopAssociation() {
        if (mAssociatedDeviceManager == null) {
            loge(TAG, "Failed to stop association. Service not connected.");
            return;
        }
        try {
            mAssociatedDeviceManager.stopAssociation();
        } catch (RemoteException e) {
            loge(TAG, "Error while stopping association process.", e);
        }
        dismissConfirmButtons();
        mIsInAssociation.set(false);
    }

    private void refreshDeviceList() {
        try {
            mModel.setDevices(mAssociatedDeviceManager.getActiveUserAssociatedDevices());
        } catch (RemoteException e) {
            loge(TAG, "Failed to get associated device list", e);
            runOnUiThread(() -> Toast.makeText(getApplicationContext(),
                    getString(R.string.refresh_list_failure_toast_text),
                    Toast.LENGTH_SHORT).show());
        }
    }

    private void removeAssociatedDevice(
            AssociatedDevice device) {
        try {
            mAssociatedDeviceManager.removeAssociatedDevice(device.getDeviceId());
        } catch (RemoteException e) {
            loge(TAG, "Failed to remove associated device: " + device, e);
            runOnUiThread(() -> Toast.makeText(getApplicationContext(),
                    getString(R.string.device_removed_failure_toast_text, device.getDeviceName()),
                    Toast.LENGTH_SHORT).show());
        }
    }

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mAssociatedDeviceManager = IAssociatedDeviceManager.Stub.asInterface(service);
            try {
                mAssociatedDeviceManager.registerAssociationCallback(mAssociationCallback);
                mAssociatedDeviceManager
                        .registerDeviceAssociationCallback(mDeviceAssociationCallback);
                List<AssociatedDevice> devices = mAssociatedDeviceManager
                        .getActiveUserAssociatedDevices();
                if (devices == null) {
                    return;
                }
                mModel.setDevices(devices);
                if (devices.size() > 0) {
                    showAssociatedDeviceDetailFragment();
                } else if (!mIsInAssociation.get()) {
                    mAssociatedDeviceManager.startAssociation();
                }
            } catch (RemoteException e) {
                loge(TAG, "Initial set failed onServiceConnected", e);
            }
            logd(TAG, "Service connected:" + name.getClassName());
            if (getSupportFragmentManager().findFragmentByTag(ADD_DEVICE_FRAGMENT_TAG) == null) {
                mToolbar.hideProgressBar();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mAssociatedDeviceManager = null;
            logd(TAG, "Service disconnected: " + name.getClassName());
            finish();
        }
    };

    private final IAssociationCallback mAssociationCallback =
            new IAssociationCallback.Stub() {
        @Override
        public void onAssociationStartSuccess(String deviceName) {
            mIsInAssociation.set(true);
            runOnUiThread(() -> showAddAssociatedDeviceFragment(deviceName));
        }
        @Override
        public void onAssociationStartFailure() {
            loge(TAG, "Failed to start association.");
            mIsInAssociation.set(false);
            finish();
        }

        @Override
        public void onAssociationError(int error) throws RemoteException {
            loge(TAG, "Encountered an error during association: " + error);
            mIsInAssociation.set(false);
            finish();
        }

        @Override
        public void onVerificationCodeAvailable(String code) throws RemoteException {
            // Need to run this part of code in UI thread to show the dialog as the callback is
            // triggered in a separate thread.
            runOnUiThread(() -> showConfirmPairingCodeFragment(code));
        }

        @Override
        public void onAssociationCompleted() {
            mIsInAssociation.set(false);
            runOnUiThread(() -> {
                refreshDeviceList();
                showAssociatedDeviceDetailFragment();
            });
        }
    };

    private final IDeviceAssociationCallback mDeviceAssociationCallback =
            new IDeviceAssociationCallback.Stub() {
        @Override
        public void onAssociatedDeviceAdded(String deviceId) { }

        @Override
        public void onAssociatedDeviceRemoved(String deviceId) {
            refreshDeviceList();
            String deviceName = deviceId;
            if (mDeviceToRemove != null && mDeviceToRemove.getDeviceId().equals(deviceId)) {
                deviceName = mDeviceToRemove.getDeviceName();
            }
            String removeText = getString(R.string.device_removed_success_toast_text, deviceName);
            runOnUiThread(() ->
                    Toast.makeText(getBaseContext(), removeText, Toast.LENGTH_SHORT).show());
            finish();
        }

        @Override
        public void onAssociatedDeviceUpdated(AssociatedDevice device) {
            refreshDeviceList();
        }
    };

    /** Dialog fragment to confirm removing an associated device. */
    public static class RemoveDeviceDialogFragment extends DialogFragment {
        private static final String DEVICE_NAME_KEY = "device_name";

        private DialogInterface.OnClickListener mOnConfirmListener;

        static RemoveDeviceDialogFragment newInstance(@NonNull String deviceName,
                DialogInterface.OnClickListener listener) {
            Bundle bundle = new Bundle();
            bundle.putString(DEVICE_NAME_KEY, deviceName);
            RemoveDeviceDialogFragment fragment = new RemoveDeviceDialogFragment();
            fragment.setArguments(bundle);
            fragment.setOnConfirmListener(listener);
            return fragment;
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            Bundle bundle = getArguments();
            String deviceName = bundle.getString(DEVICE_NAME_KEY);
            return new AlertDialog.Builder(getActivity())
                    .setTitle(getString(R.string.remove_associated_device_title, deviceName))
                    .setMessage(getString(R.string.remove_associated_device_message))
                    .setNegativeButton(getString(R.string.remove), mOnConfirmListener)
                    .setPositiveButton(getString(R.string.cancel), null)
                    .setCancelable(true)
                    .create();
        }

        void setOnConfirmListener(DialogInterface.OnClickListener onConfirmListener) {
            mOnConfirmListener = onConfirmListener;
        }
    }
}
