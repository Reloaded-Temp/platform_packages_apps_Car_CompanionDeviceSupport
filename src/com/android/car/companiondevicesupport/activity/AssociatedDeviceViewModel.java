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

import android.annotation.NonNull;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.android.car.companiondevicesupport.api.internal.association.AssociatedDevice;

import java.util.List;

/**
 * Implementation {@link ViewModel} for sharing associated devices data between
 * {@link AssociationFragment} and {@link AssociationActivity}
 */
public class AssociatedDeviceViewModel extends ViewModel {
    private final MutableLiveData<List<AssociatedDevice>> mDevices = new MutableLiveData<>();
    private final MutableLiveData<Boolean> mIsSelected = new MutableLiveData<>(false);

    /**
     * Set the associated devices.
     * @param devices Associated devices.
     */
    public void setDevices(@NonNull List<AssociatedDevice> devices) {
        mDevices.setValue(devices);
    }

    /**
     * Set the select status of the add associated device button.
     * @param isSelected Select status.
     */
    public void setSelected(boolean isSelected) {
        mIsSelected.setValue(isSelected);
    }

    @NonNull
    public LiveData<List<AssociatedDevice>> getDevices() {
        return mDevices;
    }

    public LiveData<Boolean> isSelected() {
        return mIsSelected;
    }
}
