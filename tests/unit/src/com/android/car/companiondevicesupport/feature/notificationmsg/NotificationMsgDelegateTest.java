/*
 * Copyright (C) 2020 The Android Open Source Project
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


import static androidx.core.app.NotificationCompat.CATEGORY_MESSAGE;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.drawable.Icon;

import androidx.core.app.NotificationCompat;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.car.companiondevicesupport.api.external.CompanionDevice;
import com.android.car.messenger.NotificationMsgProto.NotificationMsg.ConversationNotification;
import com.android.car.messenger.NotificationMsgProto.NotificationMsg.MessagingStyle;
import com.android.car.messenger.NotificationMsgProto.NotificationMsg.MessagingStyleMessage;
import com.android.car.messenger.NotificationMsgProto.NotificationMsg.Person;
import com.android.car.messenger.NotificationMsgProto.NotificationMsg.PhoneToCarMessage;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class NotificationMsgDelegateTest {
    private static final String NOTIFICATION_KEY_1 = "notification_key_1";
    private static final String NOTIFICATION_KEY_2 = "notification_key_2";

    private static final String COMPANION_DEVICE_ID = "sampleId";
    private static final String COMPANION_DEVICE_NAME = "sampleName";

    private static final String MESSAGING_APP_NAME = "Messaging App";
    private static final String MESSAGING_PACKAGE_NAME = "com.android.messaging.app";
    private static final String CONVERSATION_TITLE = "Conversation";
    private static final String USER_DISPLAY_NAME = "User";
    private static final String SENDER_1 = "Sender";

    private static final MessagingStyleMessage MESSAGE_2 = MessagingStyleMessage.newBuilder()
            .setTextMessage("Message 2")
            .setSender(Person.newBuilder()
                    .setName(SENDER_1))
            .setTimestamp((long) 1577909718950f)
            .build();

    private static final MessagingStyle VALID_STYLE = MessagingStyle.newBuilder()
            .setConvoTitle(CONVERSATION_TITLE)
            .setUserDisplayName(USER_DISPLAY_NAME)
            .setIsGroupConvo(false)
            .addMessagingStyleMsg(MessagingStyleMessage.newBuilder()
                    .setTextMessage("Message 1")
                    .setSender(Person.newBuilder()
                            .setName(SENDER_1))
                    .setTimestamp((long) 1577909718050f)
                    .build())
            .build();

    private static final ConversationNotification VALID_CONVERSATION =
            ConversationNotification.newBuilder()
                    .setMessagingAppDisplayName(MESSAGING_APP_NAME)
                    .setMessagingAppPackageName(MESSAGING_PACKAGE_NAME)
                    .setTimeMs((long) 1577909716000f)
                    .setMessagingStyle(VALID_STYLE)
                    .build();

    private static final PhoneToCarMessage VALID_CONVERSATION_MSG = PhoneToCarMessage.newBuilder()
            .setNotificationKey(NOTIFICATION_KEY_1)
            .setConversation(VALID_CONVERSATION)
            .build();

    @Mock
    CompanionDevice mCompanionDevice;
    @Mock
    NotificationManager mMockNotificationManager;

    ArgumentCaptor<Notification> mNotificationCaptor =
            ArgumentCaptor.forClass(Notification.class);
    ArgumentCaptor<Integer> mNotificationIdCaptor = ArgumentCaptor.forClass(Integer.class);

    Context mContext = ApplicationProvider.getApplicationContext();
    NotificationMsgDelegate mNotificationMsgDelegate;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mCompanionDevice.getDeviceId()).thenReturn(COMPANION_DEVICE_ID);
        when(mCompanionDevice.getDeviceName()).thenReturn(COMPANION_DEVICE_NAME);

        mNotificationMsgDelegate = new NotificationMsgDelegate(mContext);
        mNotificationMsgDelegate.setNotificationManager(mMockNotificationManager);
    }

    @Test
    public void newConversationShouldPostNewNotification() {
        // Test that a new conversation notification is posted with the correct fields.
        mNotificationMsgDelegate.onMessageReceived(mCompanionDevice, VALID_CONVERSATION_MSG);

        verify(mMockNotificationManager).notify(anyInt(), mNotificationCaptor.capture());

        Notification postedNotification = mNotificationCaptor.getValue();
        verifyNotification(VALID_CONVERSATION, postedNotification);
    }

    @Test
    public void multipleNewConversationShouldPostMultipleNewNotifications() {
        mNotificationMsgDelegate.onMessageReceived(mCompanionDevice, VALID_CONVERSATION_MSG);

        verify(mMockNotificationManager).notify(mNotificationIdCaptor.capture(),
                any(Notification.class));
        int firstNotificationId = mNotificationIdCaptor.getValue();

        mNotificationMsgDelegate.onMessageReceived(mCompanionDevice,
                createSecondConversation());
        verify(mMockNotificationManager, times(2)).notify(mNotificationIdCaptor.capture(),
                any(Notification.class));

        // Verify the notification id is different than the first.
        assertThat((long) mNotificationIdCaptor.getValue()).isNotEqualTo(firstNotificationId);
    }

    @Test
    public void invalidConversationShouldDoNothing() {
        // Test that a conversation without all the required fields is dropped.
        PhoneToCarMessage newConvo = PhoneToCarMessage.newBuilder()
                .setNotificationKey(NOTIFICATION_KEY_1)
                .setConversation(VALID_CONVERSATION.toBuilder().clearMessagingStyle())
                .build();
        mNotificationMsgDelegate.onMessageReceived(mCompanionDevice, newConvo);

        verify(mMockNotificationManager, never()).notify(anyInt(), any(Notification.class));
    }

    @Test
    public void newMessageShouldUpdateConversationNotification() {
        // Check whether a new message updates the notification of the conversation it belongs to.
        mNotificationMsgDelegate.onMessageReceived(mCompanionDevice, VALID_CONVERSATION_MSG);
        verify(mMockNotificationManager).notify(mNotificationIdCaptor.capture(),
                any(Notification.class));
        int notificationId = mNotificationIdCaptor.getValue();
        int messageCount = VALID_CONVERSATION_MSG.getConversation().getMessagingStyle()
                .getMessagingStyleMsgCount();

        PhoneToCarMessage updateConvo = PhoneToCarMessage.newBuilder()
                .setNotificationKey(NOTIFICATION_KEY_1)
                .setMessage(MESSAGE_2)
                .build();
        mNotificationMsgDelegate.onMessageReceived(mCompanionDevice, updateConvo);

        // Verify same notification id is posted twice.
        verify(mMockNotificationManager, times(2)).notify(eq(notificationId),
                mNotificationCaptor.capture());

        // Verify the notification contains one more message.
        NotificationCompat.MessagingStyle messagingStyle = getMessagingStyle(
                mNotificationCaptor.getValue());
        assertThat(messagingStyle.getMessages().size()).isEqualTo(messageCount + 1);

        // Verify notification's latest message matches the new message.
        verifyMessage(MESSAGE_2, messagingStyle.getMessages().get(messageCount));
    }

    @Test
    public void existingConversationShouldUpdateNotification() {
        // Test that a conversation that already exists, but gets a new conversation message
        // is updated with the new conversation metadata.
        mNotificationMsgDelegate.onMessageReceived(mCompanionDevice, VALID_CONVERSATION_MSG);
        verify(mMockNotificationManager).notify(mNotificationIdCaptor.capture(),
                any(Notification.class));
        int notificationId = mNotificationIdCaptor.getValue();

        ConversationNotification updatedConversation = addSecondMessageToConversation().toBuilder()
                .setMessagingAppDisplayName("New Messaging App")
                .build();
        mNotificationMsgDelegate.onMessageReceived(mCompanionDevice, PhoneToCarMessage.newBuilder()
                .setNotificationKey(NOTIFICATION_KEY_1)
                .setConversation(updatedConversation)
                .build());

        verify(mMockNotificationManager, times(2)).notify(eq(notificationId),
                mNotificationCaptor.capture());
        Notification postedNotification = mNotificationCaptor.getValue();

        // Verify Conversation level metadata does NOT change
        verifyConversationLevelMetadata(VALID_CONVERSATION, postedNotification);
        // Verify the MessagingStyle metadata does update with the new message.
        verifyMessagingStyle(updatedConversation.getMessagingStyle(), postedNotification);
    }

    @Test
    public void messageForUnknownConversationShouldDoNothing() {
        // A message for an unknown conversation should be dropped.
        PhoneToCarMessage updateConvo = PhoneToCarMessage.newBuilder()
                .setNotificationKey(NOTIFICATION_KEY_1)
                .setMessage(MESSAGE_2)
                .build();
        mNotificationMsgDelegate.onMessageReceived(mCompanionDevice, updateConvo);

        verify(mMockNotificationManager, never()).notify(anyInt(), any(Notification.class));
    }

    @Test
    public void invalidMessageShouldDoNothing() {
        // Message without all the required fields is dropped.
        mNotificationMsgDelegate.onMessageReceived(mCompanionDevice, VALID_CONVERSATION_MSG);

        // Create a MessagingStyleMessage without a required field (Sender information).
        MessagingStyleMessage invalidMsgStyleMessage = MessagingStyleMessage.newBuilder()
                .setTextMessage("Message 2")
                .setTimestamp((long) 1577909718950f)
                .build();
        PhoneToCarMessage invalidMessage = PhoneToCarMessage.newBuilder()
                .setNotificationKey(NOTIFICATION_KEY_1)
                .setMessage(invalidMsgStyleMessage)
                .build();
        mNotificationMsgDelegate.onMessageReceived(mCompanionDevice, invalidMessage);

        // Verify only one notification is posted, and never updated.
        verify(mMockNotificationManager).notify(anyInt(), any(Notification.class));
    }

    private void verifyNotification(ConversationNotification expected, Notification notification) {
        verifyConversationLevelMetadata(expected, notification);
        verifyMessagingStyle(expected.getMessagingStyle(), notification);
    }

    /**
     * Verifies the conversation level metadata and other aspects of a notification that do not
     * change when a new message is added to it (such as the actions, intents).
     */
    private void verifyConversationLevelMetadata(ConversationNotification expected,
            Notification notification) {
        assertThat(notification.category).isEqualTo(CATEGORY_MESSAGE);

        assertThat(notification.getSmallIcon()).isNotNull();
        if (!expected.getAppIcon().isEmpty()) {
            byte[] iconBytes = expected.getAppIcon().toByteArray();
            Icon appIcon = Icon.createWithData(iconBytes, 0, iconBytes.length);
            assertThat(notification.getSmallIcon()).isEqualTo(appIcon);
        }

        assertThat(notification.deleteIntent).isNotNull();

        if (expected.getMessagingAppPackageName() != null) {
            CharSequence appName = notification.extras.getCharSequence(
                    Notification.EXTRA_SUBSTITUTE_APP_NAME);
            assertThat(appName).isEqualTo(expected.getMessagingAppDisplayName());
        }

        assertThat(notification.actions.length).isEqualTo(2);
        for (NotificationCompat.Action action : getAllActions(notification)) {
            if (action.getSemanticAction() == NotificationCompat.Action.SEMANTIC_ACTION_REPLY) {
                assertThat(action.getRemoteInputs().length).isEqualTo(1);
            }
            assertThat(action.getShowsUserInterface()).isFalse();
        }
    }

    private void verifyMessagingStyle(MessagingStyle expected, Notification notification) {
        final NotificationCompat.MessagingStyle messagingStyle = getMessagingStyle(notification);

        assertThat(messagingStyle.getUser().getName()).isEqualTo(expected.getUserDisplayName());
        assertThat(messagingStyle.isGroupConversation()).isEqualTo(expected.getIsGroupConvo());
        assertThat(messagingStyle.getMessages().size()).isEqualTo(expected.getMessagingStyleMsgCount());

        for (int i = 0; i < expected.getMessagingStyleMsgCount(); i++) {
            MessagingStyleMessage expectedMsg = expected.getMessagingStyleMsg(i);
            NotificationCompat.MessagingStyle.Message actualMsg = messagingStyle.getMessages().get(
                    i);
            verifyMessage(expectedMsg, actualMsg);

        }
    }

    private void verifyMessage(MessagingStyleMessage expectedMsg,
            NotificationCompat.MessagingStyle.Message actualMsg) {
        assertThat(actualMsg.getTimestamp()).isEqualTo(expectedMsg.getTimestamp());
        assertThat(actualMsg.getText()).isEqualTo(expectedMsg.getTextMessage());

        Person expectedSender = expectedMsg.getSender();
        androidx.core.app.Person actualSender = actualMsg.getPerson();
        assertThat(actualSender.getName()).isEqualTo(expectedSender.getName());
        if (!expectedSender.getAvatar().isEmpty()) {
            assertThat(actualSender.getIcon()).isNotNull();
        } else {
            assertThat(actualSender.getIcon()).isNull();
        }
    }

    private NotificationCompat.MessagingStyle getMessagingStyle(Notification notification) {
        return NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(
                notification);
    }

    private List<NotificationCompat.Action> getAllActions(Notification notification) {
        List<NotificationCompat.Action> actions = new ArrayList<>();
        actions.addAll(NotificationCompat.getInvisibleActions(notification));
        for (int i = 0; i < NotificationCompat.getActionCount(notification); i++) {
            actions.add(NotificationCompat.getAction(notification, i));
        }
        return actions;
    }

    private PhoneToCarMessage createSecondConversation() {
        return VALID_CONVERSATION_MSG.toBuilder()
                .setNotificationKey(NOTIFICATION_KEY_2)
                .setConversation(addSecondMessageToConversation())
                .build();
    }

    private ConversationNotification addSecondMessageToConversation() {
        return VALID_CONVERSATION.toBuilder()
                .setMessagingStyle(
                        VALID_STYLE.toBuilder().addMessagingStyleMsg(MESSAGE_2)).build();
    }
}
