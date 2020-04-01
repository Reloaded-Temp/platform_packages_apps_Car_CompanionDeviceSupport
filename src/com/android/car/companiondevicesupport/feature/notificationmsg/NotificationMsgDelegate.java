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
import static com.android.car.connecteddevice.util.SafeLog.logw;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.provider.Settings;

import androidx.annotation.Nullable;

import com.android.car.companiondevicesupport.api.external.CompanionDevice;
import com.android.car.messenger.NotificationMsgProto.NotificationMsg;
import com.android.car.messenger.NotificationMsgProto.NotificationMsg.Action;
import com.android.car.messenger.NotificationMsgProto.NotificationMsg.AvatarIconSync;
import com.android.car.messenger.NotificationMsgProto.NotificationMsg.MapEntry;
import com.android.car.messenger.NotificationMsgProto.NotificationMsg.CarToPhoneMessage;
import com.android.car.messenger.NotificationMsgProto.NotificationMsg.ConversationNotification;
import com.android.car.messenger.NotificationMsgProto.NotificationMsg.MessagingStyleMessage;
import com.android.car.messenger.NotificationMsgProto.NotificationMsg.PhoneToCarMessage;
import com.android.car.messenger.common.BaseNotificationDelegate;
import com.android.car.messenger.common.ConversationKey;
import com.android.car.messenger.common.ConversationNotificationInfo;
import com.android.car.messenger.common.Message;
import com.android.car.messenger.common.MessageKey;
import com.android.car.messenger.common.ProjectionStateListener;
import com.android.car.messenger.common.SenderKey;
import com.android.car.messenger.common.Utils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Posts Message notifications sent from the {@link CompanionDevice}, and relays user interaction
 *  with the messages back to the device.
 **/
public class NotificationMsgDelegate extends BaseNotificationDelegate {
    private static final String TAG = "NotificationMsgDelegate";

    /** Key for the Reply string in a {@link MapEntry}. **/
    private static final String REPLY_KEY = "REPLY";

    private static final AudioAttributes AUDIO_ATTRIBUTES = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .build();

    private Map<String, NotificationChannelWrapper> mAppNameToChannel = new HashMap<>();
    private String mConnectedDeviceBluetoothAddress;
    /**
     * Maps a Bitmap of a sender's Large Icon to the sender's unique key for 1-1 conversations.
     **/
    protected final Map<SenderKey, Bitmap> mOneOnOneConversationAvatarMap = new HashMap<>();

    /** Tracks whether a projection application is active in the foreground. **/
    private ProjectionStateListener mProjectionStateListener;

    public NotificationMsgDelegate(Context context, String className) {
        super(context, className, /* useLetterTile */ false);
        mProjectionStateListener = new ProjectionStateListener(context);
    }

    public void onMessageReceived(CompanionDevice device, PhoneToCarMessage message) {
        String notificationKey = message.getNotificationKey();

        switch (message.getMessageDataCase()) {
            case CONVERSATION:
                initializeNewConversation(device, message.getConversation(), notificationKey);
                return;
            case MESSAGE:
                initializeNewMessage(device.getDeviceId(), message.getMessage(), notificationKey);
                return;
            case STATUS_UPDATE:
                // TODO (b/144924164): implement Action Request tracking logic.
                return;
            case AVATAR_ICON_SYNC:
                storeIcon(new ConversationKey(device.getDeviceId(), notificationKey),
                        message.getAvatarIconSync());
                return;
            case PHONE_METADATA:
                mConnectedDeviceBluetoothAddress =
                        message.getPhoneMetadata().getBluetoothDeviceAddress();
                return;
            case CLEAR_APP_DATA_REQUEST:
                // TODO(b/150326327): implement removal behavior.
                return;
            case FEATURE_ENABLED_STATE_CHANGE:
                // TODO(b/150326327): implement enabled state change behavior.
                return;
            case MESSAGEDATA_NOT_SET:
            default:
                logw(TAG, "PhoneToCarMessage: message data not set!");
        }
    }

    protected CarToPhoneMessage dismiss(ConversationKey convoKey) {
        clearNotifications(key -> key.equals(convoKey));
        excludeFromNotification(convoKey);
        // TODO(b/144924164): add a request id to the action.
        Action action = Action.newBuilder()
                .setActionName(Action.ActionName.DISMISS)
                .setNotificationKey(convoKey.getSubKey())
                .build();
        return CarToPhoneMessage.newBuilder()
                .setNotificationKey(convoKey.getSubKey())
                .setActionRequest(action)
                .build();
    }

    protected CarToPhoneMessage markAsRead(ConversationKey convoKey) {
        excludeFromNotification(convoKey);
        // TODO(b/144924164): add a request id to the action.
        Action action = Action.newBuilder()
                .setActionName(Action.ActionName.MARK_AS_READ)
                .setNotificationKey(convoKey.getSubKey())
                .build();
        return CarToPhoneMessage.newBuilder()
                .setNotificationKey(convoKey.getSubKey())
                .setActionRequest(action)
                .build();
    }

    /**
     * Excludes messages from a notification so that the messages are not shown to the user once
     * the notification gets updated with newer messages.
     */
    private void excludeFromNotification(ConversationKey convoKey) {
        ConversationNotificationInfo info = mNotificationInfos.get(convoKey);
        for (MessageKey key : info.mMessageKeys) {
            Message message = mMessages.get(key);
            message.excludeFromNotification();
        }
    }

    protected CarToPhoneMessage reply(ConversationKey convoKey, String message) {
        // TODO(b/144924164): add a request id to the action.
        MapEntry entry = MapEntry.newBuilder()
                .setKey(REPLY_KEY)
                .setValue(message)
                .build();
        Action action = Action.newBuilder()
                .setActionName(Action.ActionName.REPLY)
                .setNotificationKey(convoKey.getSubKey())
                .addMapEntry(entry)
                .build();
        return CarToPhoneMessage.newBuilder()
                .setNotificationKey(convoKey.getSubKey())
                .setActionRequest(action)
                .build();
    }

    protected void onDestroy() {
        // Erase all the notifications and local data, so that no user data stays on the device
        // after the feature is stopped.
        cleanupMessagesAndNotifications(key -> true);
        mProjectionStateListener.destroy();
        mOneOnOneConversationAvatarMap.clear();
        mAppNameToChannel.clear();
        mConnectedDeviceBluetoothAddress = null;
    }

    protected void onDeviceDisconnected(String deviceId) {
        mConnectedDeviceBluetoothAddress = null;
        cleanupMessagesAndNotifications(key -> key.matches(deviceId));
        mOneOnOneConversationAvatarMap.entrySet().removeIf(
                conversationKey -> conversationKey.getKey().matches(deviceId));
    }

    private void initializeNewConversation(CompanionDevice device,
            ConversationNotification notification, String notificationKey) {
        String deviceAddress = device.getDeviceId();
        ConversationKey convoKey = new ConversationKey(deviceAddress, notificationKey);
        if (mNotificationInfos.containsKey(convoKey)) {
            logw(TAG, "Conversation already exists! " + notificationKey);
        }

        if (!Utils.isValidConversationNotification(notification, /* isShallowCheck= */ false)) {
            logd(TAG, "Failed to initialize new Conversation, object missing required fields");
            return;
        }

        ConversationNotificationInfo convoInfo = ConversationNotificationInfo.
                createConversationNotificationInfo(device.getDeviceName(), device.getDeviceId(),
                notification, notificationKey);
        mNotificationInfos.put(convoKey, convoInfo);

        String appDisplayName = convoInfo.getAppDisplayName();

        List<MessagingStyleMessage> messages =
                notification.getMessagingStyle().getMessagingStyleMsgList();
        MessagingStyleMessage latestMessage = messages.get(0);
        for (MessagingStyleMessage messagingStyleMessage : messages) {
            createNewMessage(deviceAddress, messagingStyleMessage, convoKey);
            if (messagingStyleMessage.getTimestamp() > latestMessage.getTimestamp()) {
                latestMessage = messagingStyleMessage;
            }
        }
        postNotification(convoKey, convoInfo, getChannelId(appDisplayName),
                getAvatarIcon(convoKey, latestMessage));
    }

    private void initializeNewMessage(String deviceAddress,
            MessagingStyleMessage messagingStyleMessage, String notificationKey) {
        ConversationKey convoKey = new ConversationKey(deviceAddress, notificationKey);
        if (!mNotificationInfos.containsKey(convoKey)) {
            logw(TAG, "Conversation not found for notification: " + notificationKey);
            return;
        }

        if (!Utils.isValidMessagingStyleMessage(messagingStyleMessage)) {
            logd(TAG, "Failed to initialize new Message, object missing required fields");
            return;
        }

        createNewMessage(deviceAddress, messagingStyleMessage, convoKey);
        ConversationNotificationInfo convoInfo = mNotificationInfos.get(convoKey);

        postNotification(convoKey, convoInfo, getChannelId(convoInfo.getAppDisplayName()),
                getAvatarIcon(convoKey, messagingStyleMessage));
    }

    @Nullable
    private Bitmap getAvatarIcon(ConversationKey convoKey, MessagingStyleMessage message) {
        ConversationNotificationInfo notificationInfo = mNotificationInfos.get(convoKey);
        if (!notificationInfo.isGroupConvo()) {
            return mOneOnOneConversationAvatarMap.get(
                    createSenderKey(convoKey, message.getSender()));
        } else if (message.getSender().getAvatar() != null) {
            byte[] iconArray = message.getSender().getAvatar().toByteArray();
            return BitmapFactory.decodeByteArray(iconArray, 0, iconArray.length);
        }
        return null;
    }

    private void storeIcon(ConversationKey convoKey, AvatarIconSync iconSync) {
        if (!Utils.isValidAvatarIconSync(iconSync) || !mNotificationInfos.containsKey(convoKey)) {
            logw(TAG, "storeIcon: invalid AvatarIconSync obj or no conversation found.");
            return;
        }
        if (mNotificationInfos.get(convoKey).isGroupConvo()) {
            return;
        }
        byte[] iconArray = iconSync.getPerson().getAvatar().toByteArray();
        Bitmap bitmap = BitmapFactory.decodeByteArray(iconArray, /* offset= */ 0, iconArray.length);
        if (bitmap != null) {
            mOneOnOneConversationAvatarMap.put(createSenderKey(convoKey, iconSync.getPerson()),
                    bitmap);
        } else {
            logw(TAG, "storeIcon: Bitmap could not be created from byteArray");
        }
    }

    private String getChannelId(String appDisplayName) {
        if (!mAppNameToChannel.containsKey(appDisplayName)) {
            mAppNameToChannel.put(appDisplayName,
                    new NotificationChannelWrapper(appDisplayName));
        }
        boolean isProjectionActive = mProjectionStateListener.isProjectionInActiveForeground(
                        mConnectedDeviceBluetoothAddress);
        return mAppNameToChannel.get(appDisplayName).getChannelId(isProjectionActive);
    }

    private void createNewMessage(String deviceAddress, MessagingStyleMessage messagingStyleMessage,
            ConversationKey convoKey) {
        String appPackageName = mNotificationInfos.get(convoKey).getAppPackageName();
        Message message = Message.parseFromMessage(deviceAddress, messagingStyleMessage,
                appPackageName + convoKey.getSubKey());
        addMessageToNotificationInfo(message, convoKey);
        AvatarIconSync iconSync = AvatarIconSync.newBuilder()
                .setPerson(messagingStyleMessage.getSender())
                .setMessagingAppPackageName(appPackageName)
                .build();
        storeIcon(convoKey, iconSync);
    }

    private SenderKey createSenderKey(ConversationKey convoKey, NotificationMsg.Person person) {
        return new SenderKey(convoKey.getDeviceId(), person.getName(), convoKey.getSubKey());
    }

    /** Creates notification channels per unique messaging application. **/
    private class NotificationChannelWrapper {
        private static final String SILENT_CHANNEL_NAME_SUFFIX = "-no-hun";
        private final String mImportantChannelId;
        private final String mSilentChannelId;

        NotificationChannelWrapper(String appDisplayName) {
            mImportantChannelId = generateNotificationChannelId();
            setupImportantNotificationChannel(mImportantChannelId, appDisplayName);
            mSilentChannelId = generateNotificationChannelId();
            setupSilentNotificationChannel(mSilentChannelId,
                    appDisplayName + SILENT_CHANNEL_NAME_SUFFIX);
        }

        /**
         * Returns the channel id based on whether the notification should have a heads-up
         * notification and an alert sound.
         */
        String getChannelId(boolean showSilently) {
            if (showSilently) return mSilentChannelId;
            return mImportantChannelId;
        }

        private void setupImportantNotificationChannel(String channelId, String channelName) {
            NotificationChannel msgChannel = new NotificationChannel(channelId,
                    channelName,
                    NotificationManager.IMPORTANCE_HIGH);
            msgChannel.setDescription(channelName);
            msgChannel.setSound(Settings.System.DEFAULT_NOTIFICATION_URI, AUDIO_ATTRIBUTES);
            mNotificationManager.createNotificationChannel(msgChannel);
        }

        private void setupSilentNotificationChannel(String channelId, String channelName) {
            NotificationChannel msgChannel = new NotificationChannel(channelId,
                    channelName,
                    NotificationManager.IMPORTANCE_LOW);
            mNotificationManager.createNotificationChannel(msgChannel);
        }

        private String generateNotificationChannelId() {
            return NotificationMsgService.NOTIFICATION_MSG_CHANNEL_ID + "|"
                    + NotificationChannelIdGenerator.generateChannelId();
        }
    }

    /** Helper class that generates unique IDs per Notification Channel. **/
    static class NotificationChannelIdGenerator {
        private static int NEXT_NOTIFICATION_CHANNEL_ID = 0;

        static int generateChannelId() {
            return ++NEXT_NOTIFICATION_CHANNEL_ID;
        }
    }
}
