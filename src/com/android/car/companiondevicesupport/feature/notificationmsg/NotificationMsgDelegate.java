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

import android.content.Context;
import android.graphics.BitmapFactory;

import com.android.car.companiondevicesupport.api.external.CompanionDevice;
import com.android.car.messenger.NotificationMsgProto.NotificationMsg.Action;
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

import java.util.List;

/**
 * Posts Message notifications sent from the {@link CompanionDevice}, and relays user interaction
 *  with the messages back to the device.
 **/
public class NotificationMsgDelegate extends BaseNotificationDelegate implements
        NotificationMsgService.OnCompanionDeviceEventCallback {
    private static final String TAG = "NotificationMsgDelegate";

    /** The different {@link PhoneToCarMessage} Message Types. **/
    private static final String NEW_CONVERSATION_MESSAGE_TYPE = "NEW_CONVERSATION";
    private static final String NEW_MESSAGE_MESSAGE_TYPE = "NEW_MESSAGE";
    private static final String ACTION_STATUS_UPDATE_MESSAGE_TYPE = "ACTION_STATUS_UPDATE";
    private static final String OTHER_MESSAGE_TYPE = "OTHER";

    /** Key for the Reply string in a {@link Action#getActionDataMap()}. **/
    private static final String REPLY_KEY = "REPLY";

    /** The companion device for the active user, that is connected with a secure channel. **/
    private CompanionDevice mCompanionDevice;

    public NotificationMsgDelegate(Context context, String className) {
        super(context, className);
    }

    @Override
    public void onActiveSecureDeviceConnected(CompanionDevice device) {
        mCompanionDevice = device;
    }

    @Override
    public void onActiveSecureDeviceDisconnected(CompanionDevice device) {
        cleanupMessagesAndNotifications(key -> key.matches(mCompanionDevice.getDeviceId()));
        mCompanionDevice = null;
    }

    @Override
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
        // TODO(ritwikam): add a request id to the action.
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
        ConversationNotificationInfo info = mNotificationInfos.get(convoKey);
        for (MessageKey key : info.mMessageKeys) {
            Message message = mMessages.get(key);
            message.markMessageAsRead();
        }
        // TODO(ritwikam): add a request id to the action.
        Action action = Action.newBuilder()
                .setActionName(Action.ActionName.MARK_AS_READ)
                .setNotificationKey(convoKey.getSubKey())
                .build();
        return CarToPhoneMessage.newBuilder()
                .setNotificationKey(convoKey.getSubKey())
                .setActionRequest(action)
                .build();
    }

    protected CarToPhoneMessage reply(ConversationKey convoKey, String message) {
        // TODO(ritwikam): add a request id to the action.
        Action action = Action.newBuilder()
                .setActionName(Action.ActionName.REPLY)
                .setNotificationKey(convoKey.getSubKey())
                .putActionData(REPLY_KEY, message)
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
                createConversationNotificationInfo(device,
                notification, notificationKey);
        mNotificationInfos.put(convoKey, convoInfo);

        List<MessagingStyleMessage> messages =
                notification.getMessagingStyle().getMessagingStyleMsgList();
        for (MessagingStyleMessage messagingStyleMessage : messages) {
            createNewMessage(deviceAddress, messagingStyleMessage, convoKey);
        }
        //TODO (b/146500180): post using app-specific channel id
        postNotification(convoKey, convoInfo, NotificationMsgService.NOTIFICATION_MSG_CHANNEL_ID);
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
        //TODO (b/146500180): post using app-specific channel id
        postNotification(convoKey, mNotificationInfos.get(convoKey),
                NotificationMsgService.NOTIFICATION_MSG_CHANNEL_ID);
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
}
