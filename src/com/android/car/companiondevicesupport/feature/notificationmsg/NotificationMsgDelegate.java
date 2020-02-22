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
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.provider.Settings;

import com.android.car.companiondevicesupport.api.external.CompanionDevice;
import com.android.car.messenger.NotificationMsgProto.NotificationMsg.Action;
import com.android.car.messenger.NotificationMsgProto.NotificationMsg.ActionDataFieldEntry;
import com.android.car.messenger.NotificationMsgProto.NotificationMsg.CarToPhoneMessage;
import com.android.car.messenger.NotificationMsgProto.NotificationMsg.ConversationNotification;
import com.android.car.messenger.NotificationMsgProto.NotificationMsg.MessagingStyleMessage;
import com.android.car.messenger.NotificationMsgProto.NotificationMsg.PhoneToCarMessage;
import com.android.car.messenger.common.BaseNotificationDelegate;
import com.android.car.messenger.common.ConversationKey;
import com.android.car.messenger.common.ConversationNotificationInfo;
import com.android.car.messenger.common.Message;
import com.android.car.messenger.common.MessageKey;
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

    /** The different {@link PhoneToCarMessage} Message Types. **/
    private static final String NEW_CONVERSATION_MESSAGE_TYPE = "NEW_CONVERSATION";
    private static final String NEW_MESSAGE_MESSAGE_TYPE = "NEW_MESSAGE";
    private static final String ACTION_STATUS_UPDATE_MESSAGE_TYPE = "ACTION_STATUS_UPDATE";
    private static final String OTHER_MESSAGE_TYPE = "OTHER";
    /** Key for the Reply string in a {@link ActionDataFieldEntry}. **/
    private static final String REPLY_KEY = "REPLY";

    private static final AudioAttributes AUDIO_ATTRIBUTES = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .build();

    private Map<String, String> mAppNameToChannelId = new HashMap<>();

    public NotificationMsgDelegate(Context context, String className) {
        super(context, className);
    }

    public void onMessageReceived(CompanionDevice device, PhoneToCarMessage message) {
        String notificationKey = message.getNotificationKey();

        switch (message.getMessageType()) {
            case NEW_CONVERSATION_MESSAGE_TYPE:
                if (message.getConversation() != null) {
                    initializeNewConversation(device, message.getConversation(), notificationKey);
                } else {
                    logw(TAG, "NEW_CONVERSATION_MESSAGE_TYPE is missing Conversation!");
                }
                break;
            case NEW_MESSAGE_MESSAGE_TYPE:
                if (message.getMessage() != null) {
                    initializeNewMessage(device.getDeviceId(), message.getMessage(), notificationKey);
                } else {
                    logw(TAG, "NEW_MESSAGE_MESSAGE_TYPE is missing Message!");
                }
                break;
            case ACTION_STATUS_UPDATE_MESSAGE_TYPE:
                // TODO (ritwikam): implement Action Request tracking logic.
                break;
            case OTHER_MESSAGE_TYPE:
                // NO-OP for now.
                break;
            default:
                logw(TAG, "Unsupported messageType " + message.getMessageType());
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
        ActionDataFieldEntry entry = ActionDataFieldEntry.newBuilder()
                .setKey(REPLY_KEY)
                .setValue(message)
                .build();
        Action action = Action.newBuilder()
                .setActionName(Action.ActionName.REPLY)
                .setNotificationKey(convoKey.getSubKey())
                .addActionDataField(entry)
                .build();
        return CarToPhoneMessage.newBuilder()
                .setNotificationKey(convoKey.getSubKey())
                .setActionRequest(action)
                .build();
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
        if (!mAppNameToChannelId.containsKey(appDisplayName)) {
            setupImportantNotificationChannel(generateNotificationChannelId(), appDisplayName);
        }

        List<MessagingStyleMessage> messages =
                notification.getMessagingStyle().getMessagingStyleMsgList();
        for (MessagingStyleMessage messagingStyleMessage : messages) {
            createNewMessage(deviceAddress, messagingStyleMessage, convoKey);
        }
        postNotification(convoKey, convoInfo, mAppNameToChannelId.get(appDisplayName));
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

        postNotification(convoKey, convoInfo,
                mAppNameToChannelId.get(convoInfo.getAppDisplayName()));
    }

    private void createNewMessage(String deviceAddress, MessagingStyleMessage messagingStyleMessage,
            ConversationKey convoKey) {
        Message message = Message.parseFromMessage(deviceAddress, messagingStyleMessage);
        addMessageToNotificationInfo(message, convoKey);
        SenderKey senderKey = message.getSenderKey();
        if (!mSenderLargeIcons.containsKey(senderKey)
                && messagingStyleMessage.getSender().getIcon() != null) {
            byte[] iconArray = messagingStyleMessage.getSender().getIcon().toByteArray();
            mSenderLargeIcons.put(senderKey,
                    BitmapFactory.decodeByteArray(iconArray, 0, iconArray.length));
        }
    }

    private void setupImportantNotificationChannel(String channelId, String channelName) {
        NotificationChannel msgChannel = new NotificationChannel(channelId,
                channelName,
                NotificationManager.IMPORTANCE_HIGH);
        msgChannel.setDescription(channelName);
        msgChannel.setSound(Settings.System.DEFAULT_NOTIFICATION_URI, AUDIO_ATTRIBUTES);
        mNotificationManager.createNotificationChannel(msgChannel);
        mAppNameToChannelId.put(channelName, channelId);
    }

    private String generateNotificationChannelId() {
        return NotificationMsgService.NOTIFICATION_MSG_CHANNEL_ID + "|"
                + NotificationChannelIdGenerator.generateChannelId();
    }

    /** Helper class that generates unique IDs per Notification Channel. **/
    static class NotificationChannelIdGenerator {
        private static int NEXT_NOTIFICATION_CHANNEL_ID = 0;

        static int generateChannelId() {
            return ++NEXT_NOTIFICATION_CHANNEL_ID;
        }
    }
}
