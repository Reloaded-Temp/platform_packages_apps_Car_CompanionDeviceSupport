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

import static com.android.car.connecteddevice.util.SafeLog.loge;

import android.annotation.NonNull;
import android.annotation.Nullable;
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
    private static final String TAG = "AssociatedDevicesAdapter";
    private final List<AssociatedDevice> mDevices = new ArrayList<>();
    private OnDeleteClickListener mListener;

    @Override
    public DeviceViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.associated_device_list_item, parent, false);
        return new DeviceViewHolder(view);
    }

    @Override
    public void onBindViewHolder(DeviceViewHolder holder, int position) {
        holder.mDeviceNameView.setText(mDevices.get(position).getDeviceName());
        holder.mIconContainer.setOnClickListener(v -> {
            if (mListener == null) {
                loge(TAG, "OnDeleteClickListener is null.");
                return;
            }
            mListener.onDeleteClick(mDevices.get(position));
        });
    }

    @Override
    public int getItemCount() {
        return mDevices.size();
    }

    void setDevices(@NonNull List<AssociatedDevice> devices) {
        mDevices.clear();
        mDevices.addAll(devices);
    }

    void setOnDeleteClickListener(@Nullable OnDeleteClickListener listener) {
        mListener = listener;
    }

    class DeviceViewHolder extends RecyclerView.ViewHolder {
        TextView mDeviceNameView;
        View mIconContainer;

        DeviceViewHolder(View view) {
            super(view);
            mDeviceNameView = view.findViewById(R.id.device_list_item);
            mIconContainer = view.findViewById(R.id.device_list_item_delete_icon_container);
        }
    }

    public interface OnDeleteClickListener {
        void onDeleteClick(AssociatedDevice device);
    }
}
