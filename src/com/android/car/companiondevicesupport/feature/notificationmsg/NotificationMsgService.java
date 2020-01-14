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

package com.android.car.companiondevicesupport.feature.notificationmsg;

import static com.android.car.connecteddevice.util.SafeLog.logd;
import static com.android.car.connecteddevice.util.SafeLog.loge;
import static com.android.car.connecteddevice.util.SafeLog.logw;
import static com.android.car.messenger.common.BaseNotificationDelegate.ACTION_DISMISS_NOTIFICATION;
import static com.android.car.messenger.common.BaseNotificationDelegate.ACTION_MARK_AS_READ;
import static com.android.car.messenger.common.BaseNotificationDelegate.ACTION_REPLY;
import static com.android.car.messenger.common.BaseNotificationDelegate.EXTRA_CONVERSATION_KEY;
import static com.android.car.messenger.common.BaseNotificationDelegate.EXTRA_REMOTE_INPUT_KEY;

import android.annotation.Nullable;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.AudioAttributes;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;

import androidx.core.app.NotificationCompat;
import androidx.core.app.RemoteInput;

import com.android.car.companiondevicesupport.R;
import com.android.car.companiondevicesupport.api.external.CompanionDevice;
import com.android.car.companiondevicesupport.api.external.IConnectedDeviceManager;
import com.android.car.companiondevicesupport.api.external.IConnectionCallback;
import com.android.car.companiondevicesupport.api.external.IDeviceCallback;
import com.android.car.companiondevicesupport.service.CompanionDeviceSupportService;
import com.android.car.messenger.NotificationMsgProto.NotificationMsg;
import com.android.car.messenger.NotificationMsgProto.NotificationMsg.PhoneToCarMessage;
import com.android.car.messenger.common.ConversationKey;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service responsible for handling {@link NotificationMsg} messaging events from the active user's
 * securely paired {@link CompanionDevice}s.
 */
public class NotificationMsgService extends Service {
    private final static String TAG = "NotificationMsgService";

    /* NOTIFICATIONS */
    static final String NOTIFICATION_MSG_CHANNEL_ID = "NOTIFICATION_MSG_CHANNEL_ID";
    private static final String APP_RUNNING_CHANNEL_ID = "APP_RUNNING_CHANNEL_ID";
    private static final int SERVICE_STARTED_NOTIFICATION_ID = Integer.MAX_VALUE;

    private IConnectedDeviceManager mConnectedDeviceManager;
    private NotificationMsgDelegate mNotificationMsgDelegate;
    private final IBinder binder = new LocalBinder();
    private final Map<String, CompanionDevice> mActiveSecureConnectedDevices = new HashMap<>();
    private NotificationManager mNotificationManager;
    // TODO(ritwikam): Move to a config file
    private final static ParcelUuid mFeatureUuid = ParcelUuid.fromString(
            "b2337f58-18ff-4f92-a0cf-4df63ab2c889");

    public class LocalBinder extends Binder {
        NotificationMsgService getService() {
            return NotificationMsgService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Intent cdmIntent = new Intent(/* context= */ this,
                CompanionDeviceSupportService.class);
        cdmIntent.setAction(CompanionDeviceSupportService.ACTION_BIND_CONNECTED_DEVICE_MANAGER);
        bindServiceAsUser(cdmIntent, mConnection, Context.BIND_AUTO_CREATE, UserHandle.SYSTEM);

        mNotificationManager = getSystemService(NotificationManager.class);
        mNotificationMsgDelegate = new NotificationMsgDelegate(this, this.getClass().getName());
        sendServiceRunningNotification();
        setupNotificationChannel();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mNotificationMsgDelegate.cleanupMessagesAndNotifications(key -> true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) return START_STICKY;

        String action = intent.getAction();

        switch (action) {
            case ACTION_REPLY:
                handleReplyIntent(intent);
                break;
            case ACTION_DISMISS_NOTIFICATION:
                handleDismissNotificationIntent(intent);
                break;
            case ACTION_MARK_AS_READ:
                handleMarkAsReadIntent(intent);
                break;
            default:
                logw(TAG, "Unsupported action: " + action);
        }

        return START_STICKY;
    }

    /**
     * Listener to track the connection and messages from the active user's companion device that is
     * connected via a secure channel.
     */
    protected interface OnCompanionDeviceEventCallback {
        void onActiveSecureDeviceConnected(CompanionDevice device);

        void onActiveSecureDeviceDisconnected(CompanionDevice device);

        void onMessageReceived(CompanionDevice device, PhoneToCarMessage message);
    }

    private void setupNotificationChannel() {
        if (mNotificationManager == null) {
            loge(TAG, "Failed to get NotificationManager instance");
            return;
        }

        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .build();
        NotificationChannel msgChannel = new NotificationChannel(NOTIFICATION_MSG_CHANNEL_ID,
                getString(R.string.notification_msg_channel_name),
                NotificationManager.IMPORTANCE_HIGH);
        msgChannel.setDescription(getString(R.string.notification_msg_channel_description));
        msgChannel.setSound(Settings.System.DEFAULT_NOTIFICATION_URI, attributes);
        mNotificationManager.createNotificationChannel(msgChannel);
    }

    /**
     * Posts a service running (silent/hidden) notification, so we don't throw ANR after service
     * is started.
     */
    private void sendServiceRunningNotification() {
        if (mNotificationManager == null) {
            loge(TAG, "Failed to get NotificationManager instance");
            return;
        }

        // Create notification channel for app running notification
        NotificationChannel appRunningNotificationChannel =
                new NotificationChannel(APP_RUNNING_CHANNEL_ID,
                        getString(R.string.app_running_msg_channel_name),
                        NotificationManager.IMPORTANCE_MIN);
        mNotificationManager.createNotificationChannel(appRunningNotificationChannel);

        final Notification notification =
                new NotificationCompat.Builder(this, APP_RUNNING_CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_message)
                        .setContentTitle(getString(R.string.app_running_msg_notification_title))
                        .setContentText(getString(R.string.app_running_msg_notification_content))
                        .build();
        startForeground(SERVICE_STARTED_NOTIFICATION_ID, notification);
    }

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mConnectedDeviceManager = IConnectedDeviceManager.Stub.asInterface(service);
            try {
                mConnectedDeviceManager.registerActiveUserConnectionCallback(mConnectionCallback);
                List<CompanionDevice> activeUserConnectedDevices =
                        mConnectedDeviceManager.getActiveUserConnectedDevices();
                if (activeUserConnectedDevices.isEmpty()) {
                    mConnectedDeviceManager.connectToActiveUserDevice();
                    return;
                }
                for (CompanionDevice device : activeUserConnectedDevices) {
                    initializeCompanionDevice(device);
                }
            } catch (RemoteException e) {
                loge(TAG, "RemoteException thrown while registering ConnectionCallback", e);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mConnectedDeviceManager = null;
        }
    };

    private final IConnectionCallback mConnectionCallback = new IConnectionCallback.Stub() {
        @Override
        public void onDeviceConnected(CompanionDevice companionDevice)
                throws RemoteException {
            initializeCompanionDevice(companionDevice);
        }

        @Override
        public void onDeviceDisconnected(CompanionDevice companionDevice)
                throws RemoteException {
            // TODO (b/144314168): put in some reconnection flakiness logic here.
            if (mActiveSecureConnectedDevices.containsKey(companionDevice.getDeviceId())) {
                mConnectedDeviceManager.unregisterDeviceCallback(companionDevice,
                        mFeatureUuid, mDeviceCallback);
                mNotificationMsgDelegate.onActiveSecureDeviceDisconnected(companionDevice);
                mActiveSecureConnectedDevices.remove(companionDevice.getDeviceId());
            }

        }
    };

    private final IDeviceCallback mDeviceCallback = new IDeviceCallback.Stub() {
        @Override
        public void onSecureChannelEstablished(CompanionDevice companionDevice)
                throws RemoteException {
            mActiveSecureConnectedDevices.put(companionDevice.getDeviceId(), companionDevice);
            mNotificationMsgDelegate.onActiveSecureDeviceConnected(companionDevice);
        }

        @Override
        public void onMessageReceived(CompanionDevice companionDevice, byte[] message)
                throws RemoteException {
            if (mActiveSecureConnectedDevices.containsKey(companionDevice.getDeviceId())) {
                try {
                    PhoneToCarMessage phoneToCarMessage = PhoneToCarMessage.parseFrom(message);
                    mNotificationMsgDelegate.onMessageReceived(companionDevice, phoneToCarMessage);
                } catch (IOException e) {
                    loge(TAG, "Unable to parse PhoneToCarMessage.", e);
                }
            } else {
                logd(TAG, "Dropping message for disconnected device: "
                        + companionDevice.getDeviceId());
            }
        }

        @Override
        public void onDeviceError(CompanionDevice companionDevice, int error)
                throws RemoteException {
            // NO-OP
        }
    };

    private void handleDismissNotificationIntent(Intent intent) {
        if (!canCompleteRequest(intent)) {
            return;
        }
        ConversationKey key = intent.getParcelableExtra(EXTRA_CONVERSATION_KEY);
        CompanionDevice device = mActiveSecureConnectedDevices.get(key.getDeviceId());
        sendData(device, mNotificationMsgDelegate.dismiss(key).toByteArray());
    }

    private void handleMarkAsReadIntent(Intent intent) {
        if (!canCompleteRequest(intent)) {
            return;
        }
        ConversationKey key = intent.getParcelableExtra(EXTRA_CONVERSATION_KEY);
        CompanionDevice device = mActiveSecureConnectedDevices.get(key.getDeviceId());
        sendData(device, mNotificationMsgDelegate.markAsRead(key).toByteArray());
    }

    private void handleReplyIntent(Intent intent) {
        if (!canCompleteRequest(intent)) {
            return;
        }
        ConversationKey key = intent.getParcelableExtra(EXTRA_CONVERSATION_KEY);
        Bundle bundle = RemoteInput.getResultsFromIntent(intent);
        if (bundle == null) {
            logw(TAG, "Dropping voice reply. Received null RemoteInput result!");
            return;
        }
        CompanionDevice device = mActiveSecureConnectedDevices.get(key.getDeviceId());
        CharSequence message = bundle.getCharSequence(EXTRA_REMOTE_INPUT_KEY);
        sendData(device, mNotificationMsgDelegate.reply(key, message.toString()).toByteArray());
    }

    private boolean canCompleteRequest(Intent intent) {
        ConversationKey key = intent.getParcelableExtra(EXTRA_CONVERSATION_KEY);
        if (mActiveSecureConnectedDevices.get(key.getDeviceId()) == null) {
            logw(TAG, intent.getAction() + " failed: Companion Device not found");
            return false;
        }
        if (mConnectedDeviceManager == null) {
            logw(TAG, intent.getAction() + " failed: ConnectedDeviceManager disconnected");
            return false;
        }
        return true;
    }

    private void sendData(@Nullable CompanionDevice device, byte[] message) {
        if (device == null) {
            logw(TAG, "Could not send message to null device");
            return;
        }
        try {
            mConnectedDeviceManager.sendMessageSecurely(device, mFeatureUuid, message);
        } catch (RemoteException e) {
            loge(TAG, "RemoteException thrown while sending message", e);
            // TODO (b/144924164): Notify Delegate action request failed.
        }
    }

    /**
     * Adds a device callback for every device, and stores the securely connected devices for the
     * active user. Only messages received for securely connected devices for the active user will
     * be sent to the {@link OnCompanionDeviceEventCallback}s.
     */
    private void initializeCompanionDevice(CompanionDevice companionDevice) {
        try {
            mConnectedDeviceManager.registerDeviceCallback(companionDevice, mFeatureUuid,
                    mDeviceCallback);
        } catch (RemoteException e) {
            loge(TAG, "Failed to register device callback for " + companionDevice.getDeviceId(), e);
        }
    }
}
