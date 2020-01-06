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

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.android.car.companiondevicesupport.api.internal.association.AssociatedDevice;
import com.android.car.companiondevicesupport.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Implementation of {@link RecyclerView.Adapter} that provides a data binding to display associated
 * device list.
 */
class AssociatedDevicesAdapter extends
        RecyclerView.Adapter<AssociatedDevicesAdapter.DeviceViewHolder> {
    private final List<AssociatedDevice> mDevices = new ArrayList<>();

    @Override
    public DeviceViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.associated_device_list_item, parent, false);
        return new DeviceViewHolder(view);
    }

    @Override
    public void onBindViewHolder(DeviceViewHolder holder, int position) {
        holder.mTextView.setText(mDevices.get(position).getDeviceName());
    }

    @Override
    public int getItemCount() {
        return mDevices.size();
    }

    void setDevices(List<AssociatedDevice> devices) {
        mDevices.clear();
        mDevices.addAll(devices);
    }

    class DeviceViewHolder extends RecyclerView.ViewHolder {
        TextView mTextView;
        DeviceViewHolder(View view) {
            super(view);
            mTextView = view.findViewById(R.id.device_name);
        }
    }
}
