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

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProviders;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.android.car.companiondevicesupport.R;
import com.android.car.ui.recyclerview.CarUiRecyclerView;

/**
 * Fragment which contains associated device list and add associated device button.
 */
public class AssociationFragment extends Fragment {
    private AssociatedDevicesAdapter mAdapter;

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.association_settings_fragment, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        mAdapter = new AssociatedDevicesAdapter();
        CarUiRecyclerView deviceList = view.findViewById(R.id.associated_device_list);
        deviceList.setLayoutManager(new LinearLayoutManager(getContext()));
        deviceList.setAdapter(mAdapter);

        AssociatedDeviceViewModel model = ViewModelProviders.of(getActivity())
                .get(AssociatedDeviceViewModel.class);
        model.getDevices().observe(this, devices -> {
            mAdapter.setDevices(devices);
            mAdapter.notifyDataSetChanged();
        });
        View addIconContainer = view.findViewById(R.id.add_device_button);
        addIconContainer.setOnClickListener(v -> model.setSelected(true));
        mAdapter.setOnDeleteClickListener(model::setDeviceToRemove);
    }
}
