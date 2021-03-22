/**
 * Copyright (c) 2013, Redsolution LTD. All rights reserved.
 * <p>
 * This file is part of Xabber project; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License, Version 3.
 * <p>
 * Xabber is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License,
 * along with this program. If not, see http://www.gnu.org/licenses/.
 */
package com.xabber.android.data.message.chat;

import android.net.Uri;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Pair;

import com.xabber.android.data.Application;
import com.xabber.android.data.NetworkException;
import com.xabber.android.data.SettingsManager;
import com.xabber.android.data.connection.StanzaSender;
import com.xabber.android.data.database.DatabaseManager;
import com.xabber.android.data.database.realmobjects.AttachmentRealmObject;
import com.xabber.android.data.database.realmobjects.ForwardIdRealmObject;
import com.xabber.android.data.database.realmobjects.MessageRealmObject;
import com.xabber.android.data.database.repositories.MessageRepository;
import com.xabber.android.data.entity.AccountJid;
import com.xabber.android.data.entity.BaseEntity;
import com.xabber.android.data.entity.ContactJid;
import com.xabber.android.data.extension.carbons.CarbonManager;
import com.xabber.android.data.extension.chat_markers.BackpressureMessageReader;
import com.xabber.android.data.extension.cs.ChatStateManager;
import com.xabber.android.data.extension.file.FileManager;
import com.xabber.android.data.extension.file.UriUtils;
import com.xabber.android.data.extension.httpfileupload.HttpFileUploadManager;
import com.xabber.android.data.extension.otr.OTRManager;
import com.xabber.android.data.extension.references.ReferenceElement;
import com.xabber.android.data.extension.references.ReferencesManager;
import com.xabber.android.data.extension.references.decoration.Markup;
import com.xabber.android.data.extension.reliablemessagedelivery.DeliveryManager;
import com.xabber.android.data.extension.reliablemessagedelivery.RetryReceiptRequestElement;
import com.xabber.android.data.log.LogManager;
import com.xabber.android.data.message.BackpressureMessageSaver;
import com.xabber.android.data.message.ClipManager;
import com.xabber.android.data.message.ForwardManager;
import com.xabber.android.data.message.NotificationState;
import com.xabber.android.data.notification.MessageNotificationManager;
import com.xabber.android.data.notification.NotificationManager;
import com.xabber.android.ui.OnMessageUpdatedListener;
import com.xabber.android.ui.OnNewMessageListener;
import com.xabber.android.ui.adapter.chat.FileMessageVH;
import com.xabber.android.utils.Utils;
import com.xabber.xmpp.sid.OriginIdElement;

import org.jetbrains.annotations.NotNull;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.Message.Type;
import org.jivesoftware.smack.packet.Stanza;
import org.jivesoftware.smack.util.StringUtils;
import org.jivesoftware.smackx.delay.packet.DelayInformation;
import org.jivesoftware.smackx.forward.packet.Forwarded;
import org.jxmpp.jid.Jid;
import org.jxmpp.jid.parts.Resourcepart;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import io.realm.Realm;
import io.realm.RealmChangeListener;
import io.realm.RealmList;
import io.realm.RealmResults;
import io.realm.Sort;

/**
 * Chat instance.
 *
 * @author alexander.ivanov
 */
public abstract class AbstractChat extends BaseEntity implements
        RealmChangeListener<RealmResults<MessageRealmObject>> {

    private static final String LOG_TAG = AbstractChat.class.getSimpleName();

    /**
     * Whether chat is open and should be displayed as active chat.
     */
    protected boolean active;
    /**
     * Whether user never received notifications from this chat.
     */
    private boolean firstNotification;
    /**
     * Current thread id.
     */
    private String threadId;
    /**
     * The timestamp of the last chat action, such as: deletion, history clear, etc.
     */
    protected Long lastActionTimestamp;
    private int lastPosition;
    private boolean archived;
    private NotificationState notificationState;
    private final Set<String> waitToMarkAsRead = new HashSet<>();
    private MessageRealmObject lastMessage;
    protected RealmResults<MessageRealmObject> messages;
    private RealmResults<MessageRealmObject> unreadMessages;
    private String lastMessageId = null;
    private boolean addContactSuggested = false;
    private boolean historyIsFull = false;
    private boolean historyRequestedAtStart = false;

    protected AbstractChat(@NonNull final AccountJid account, @NonNull final ContactJid user) {
        super(account, user);
        threadId = StringUtils.randomString(12);
        active = false;
        firstNotification = true;
        notificationState = new NotificationState(NotificationState.NotificationMode.byDefault, 0);

        Application.getInstance().runOnUiThread(this::getMessages);
    }

    private static int getSizeOfEncodedChars(String str) {
        return Utils.xmlEncode(str).toCharArray().length;
    }

    protected static Date getDelayStamp(Message message) {
        DelayInformation delayInformation = DelayInformation.from(message);
        if (delayInformation != null) {
            return delayInformation.getStamp();
        } else {
            return null;
        }
    }

    public boolean isActive() {
        return active;
    }

    public void openChat() {
        active = true;
    }

    void closeChat() {
        active = false;
        firstNotification = true;
    }

    public RealmResults<MessageRealmObject> getMessages() {
        if (messages == null) {
            messages = MessageRepository.getChatMessages(account, contactJid);
            updateLastMessage();

            messages.addChangeListener(this);
        }

        return messages;
    }

    /**
     * @return Target address for sending message.
     */
    @NonNull
    public abstract Jid getTo();

    /**
     * @return Message type to be assigned.
     */
    public abstract Type getType();

    /**
     * @return Whether user never received notifications from this chat. And
     * mark as received.
     */
    public boolean getFirstNotification() {
        boolean result = firstNotification;
        firstNotification = false;
        return result;
    }

    /**
     * @return Whether user should be notified about incoming messages in chat.
     */
    public boolean notifyAboutMessage() {
        if (notificationState.getMode().equals(NotificationState.NotificationMode.byDefault))
            return SettingsManager.eventsOnChat();
        return notificationState.getMode().equals(NotificationState.NotificationMode.enabled);
    }

    public void enableNotificationsIfNeed() {
        int currentTime = (int) (System.currentTimeMillis() / 1000L);
        NotificationState.NotificationMode mode = notificationState.getMode();

        if ((mode.equals(NotificationState.NotificationMode.snooze15m)
                && currentTime > notificationState.getTimestamp() + TimeUnit.MINUTES.toSeconds(15))
                || (mode.equals(NotificationState.NotificationMode.snooze1h)
                && currentTime > notificationState.getTimestamp() + TimeUnit.HOURS.toSeconds(1))
                || (mode.equals(NotificationState.NotificationMode.snooze2h)
                && currentTime > notificationState.getTimestamp() + TimeUnit.HOURS.toSeconds(2))
                || (mode.equals(NotificationState.NotificationMode.snooze1d)
                && currentTime > notificationState.getTimestamp() + TimeUnit.DAYS.toSeconds(1))) {

            setNotificationStateOrDefault(new NotificationState(
                    NotificationState.NotificationMode.enabled, 0), true);
        }
    }

    abstract public MessageRealmObject createNewMessageItem(String text);

    /**
     * Creates new action.
     *
     * @param resource can be <code>null</code>.
     * @param text     can be <code>null</code>.
     */
    public void newAction(Resourcepart resource, String text, ChatAction action) {
        createAndSaveNewMessage(true, UUID.randomUUID().toString(), resource, text, null,
                action, null, null, true, false, false, false,
                null, null, null, null, null, false, null,
                false, null, false);
    }

    /**
     * Creates new action with the same timestamp as the last message,
     * as not to disturb the order of chatList elements.
     * <p>
     *
     * @param resource can be <code>null</code>.
     * @param text     can be <code>null</code>.
     */
    public void newSilentAction(Resourcepart resource, String text, ChatAction action) {
        Long lastMessageTimestamp = getLastTimestampFromBackground();
        Date silentTimestamp = null;
        if (lastMessageTimestamp != null) {
            silentTimestamp = new Date(lastMessageTimestamp + 1);
        }
        createAndSaveNewMessage(true, UUID.randomUUID().toString(), resource, text, null,
                action, silentTimestamp, null, true, false, false, false,
                null, null, null, null, null, false, null,
                false, null, false);
    }

    /**
     * Creates new message.
     * <p/>
     * Any parameter can be <code>null</code> (except boolean values).
     *
     * @param resource       Contact's resource or nick in conference.
     * @param text           message.
     * @param action         Informational message.
     * @param delayTimestamp Time when incoming message was sent or outgoing was created.
     * @param incoming       Incoming message.
     * @param notify         Notify user about this message when appropriated.
     * @param encrypted      Whether encrypted message in OTR chat was received.
     * @param offline        Whether message was received from server side offline storage.
     * @return
     */
    protected void createAndSaveNewMessage(boolean ui, String uid, Resourcepart resource,
                                 String text, String markupText, final ChatAction action,
                                 final Date timestamp, final Date delayTimestamp,
                                 final boolean incoming, boolean notify,
                                 final boolean encrypted, final boolean offline,
                                 final String stanzaId, final String originId,
                                 final String originalStanza, final String parentMessageId,
                                 final String originalFrom, boolean isForwarded,
                                 final RealmList<ForwardIdRealmObject> forwardIdRealmObjects,
                                 boolean fromMAM, String groupchatUserId, boolean isGroupchatSystem) {

        final MessageRealmObject messageRealmObject = createMessageItem(uid, resource, text,
                markupText, action, timestamp, delayTimestamp, incoming, notify, encrypted, offline,
                stanzaId, originId, null, originalStanza, parentMessageId,
                originalFrom, isForwarded, forwardIdRealmObjects, fromMAM, groupchatUserId, isGroupchatSystem);

        saveMessageItem(ui, messageRealmObject);
    }

    protected void createAndSaveFileMessage(boolean ui, String uid, Resourcepart resource, String text,
                                  String markupText, final ChatAction action, final Date timestamp,
                                  final Date delayTimestamp, final boolean incoming, boolean notify,
                                  final boolean encrypted, final boolean offline,
                                  final String stanzaId,
                                  String originId, RealmList<AttachmentRealmObject> attachmentRealmObjects,
                                  final String originalStanza, final String parentMessageId,
                                  final String originalFrom, boolean isForwarded,
                                  final RealmList<ForwardIdRealmObject> forwardIdRealmObjects,
                                  boolean fromMAM, String groupchatUserId) {

        final MessageRealmObject messageRealmObject = createMessageItem(uid, resource, text,
                markupText, action, timestamp, delayTimestamp, incoming, notify, encrypted, offline,
                stanzaId, originId, attachmentRealmObjects, originalStanza, parentMessageId,
                originalFrom, isForwarded, forwardIdRealmObjects, fromMAM, groupchatUserId, false);

        saveMessageItem(ui, messageRealmObject);
    }

    private void saveMessageItem(boolean ui, final MessageRealmObject messageRealmObject) {
        if (ui)
            BackpressureMessageSaver.getInstance().saveMessageItem(messageRealmObject);
        else {
            final long startTime = System.currentTimeMillis();
            Realm realm = null;
            try {
                realm = Realm.getDefaultInstance();
                realm.executeTransaction(realm1 -> {
                    realm1.copyToRealm(messageRealmObject);
                    LogManager.d("REALM", Thread.currentThread().getName()
                            + " save message item: " + (System.currentTimeMillis() - startTime));
                });
            } catch (Exception e) {
                LogManager.exception(LOG_TAG, e);
            } finally {
                if (realm != null) realm.close();
            }

            for (OnNewMessageListener listener : Application.getInstance().getUIListeners(OnNewMessageListener.class)){
                listener.onNewMessage();
            }
        }
    }

    protected MessageRealmObject createMessageItem(Resourcepart resource, String text, String markupText,
                                                   ChatAction action, Date delayTimestamp, boolean incoming,
                                                   boolean notify, boolean encrypted, boolean offline,
                                                   String stanzaId, String originId,
                                                   RealmList<AttachmentRealmObject> attachmentRealmObjects,
                                                   String originalStanza, String parentMessageId,
                                                   String originalFrom, boolean isForwarded,
                                                   RealmList<ForwardIdRealmObject> forwardIdRealmObjects) {

        return createMessageItem(UUID.randomUUID().toString(), resource, text, markupText, action,
                null, delayTimestamp, incoming, notify, encrypted, offline, stanzaId,
                originId, attachmentRealmObjects, originalStanza, parentMessageId, originalFrom,
                isForwarded, forwardIdRealmObjects, false, null, false);
    }

    protected MessageRealmObject createMessageItem(String uid, Resourcepart resource, String text,
                                                   String markupText, ChatAction action,
                                                   Date timestamp, Date delayTimestamp,
                                                   boolean incoming, boolean notify,
                                                   boolean encrypted, boolean offline,
                                                   String stanzaId, String originId,
                                                   RealmList<AttachmentRealmObject> attachmentRealmObjects,
                                                   String originalStanza, String parentMessageId,
                                                   String originalFrom, boolean isForwarded,
                                                   RealmList<ForwardIdRealmObject> forwardIdRealmObjects,
                                                   boolean fromMAM, String groupchatUserId,
                                                   boolean isGroupchatSystem) {

        final boolean visible = ChatManager.getInstance().isVisibleChat(this);
        boolean read = !incoming;
        boolean send = incoming;
        if (action == null && text == null) {
            throw new IllegalArgumentException();
        }
        if (text == null) {
            text = " ";
        }
        if (action != null) {
            read = true;
            send = true;
        }

        if (timestamp == null) timestamp = new Date();

        if (text.trim().isEmpty()
                && (forwardIdRealmObjects == null || forwardIdRealmObjects.isEmpty())
                && (attachmentRealmObjects == null || attachmentRealmObjects.isEmpty())) {
            notify = false;
        }

        if (notify || !incoming) {
            openChat();
        }
        if (!incoming) {
            notify = false;
        }

        MessageRealmObject messageRealmObject = new MessageRealmObject(uid);

        messageRealmObject.setAccount(account);
        messageRealmObject.setUser(contactJid);

        if (resource == null) {
            messageRealmObject.setResource(Resourcepart.EMPTY);
        } else {
            messageRealmObject.setResource(resource);
        }

        if (action != null) {
            messageRealmObject.setAction(action.toString());
        }
        messageRealmObject.setText(text);
        if (markupText != null) messageRealmObject.setMarkupText(markupText);
        messageRealmObject.setTimestamp(timestamp.getTime());
        if (delayTimestamp != null) {
            messageRealmObject.setDelayTimestamp(delayTimestamp.getTime());
        }
        messageRealmObject.setIncoming(incoming);
        messageRealmObject.setRead(fromMAM || read);
        messageRealmObject.setSent(send);
        messageRealmObject.setEncrypted(encrypted);
        messageRealmObject.setOffline(offline);
        messageRealmObject.setStanzaId(stanzaId);
        messageRealmObject.setOriginId(originId);
        if (attachmentRealmObjects != null)
            messageRealmObject.setAttachmentRealmObjects(attachmentRealmObjects);


        // forwarding
        if (forwardIdRealmObjects != null)
            messageRealmObject.setForwardedIds(forwardIdRealmObjects);
        messageRealmObject.setOriginalStanza(originalStanza);
        messageRealmObject.setOriginalFrom(originalFrom);
        messageRealmObject.setParentMessageId(parentMessageId);
        messageRealmObject.setForwarded(isForwarded);

        // remove notifications if get outgoing message with 2 sec delay
        if (!incoming) MessageNotificationManager.getInstance().removeChatWithTimer(account, contactJid);

        // when getting new message, unarchive chat if chat not muted
        if (this.notifyAboutMessage())
            this.archived = false;

        // update last id in chat
        messageRealmObject.setPreviousId(getLastMessageId());
        String id = messageRealmObject.getArchivedId();
        if (id == null) id = messageRealmObject.getStanzaId();
        setLastMessageId(id);

        // groupchat
        if (groupchatUserId != null)
            messageRealmObject.setGroupchatUserId(groupchatUserId);
        if (isGroupchatSystem){
            messageRealmObject.setGroupchatSystem(true);
            messageRealmObject.setRead(true);
        }

        // notification
        enableNotificationsIfNeed();
        if (notify && notifyAboutMessage() && !visible && !isGroupchatSystem)
            NotificationManager.getInstance().onMessageNotification(messageRealmObject);

        if (action != null && (groupchatUserId != null || isGroupchatSystem))
            return null;

        return messageRealmObject;
    }

    public String newFileMessageWithFwr(final List<File> files, final List<Uri> uris,
                                        final String messageAttachmentType,
                                        final List<String> forwards) {
        final String messageId = UUID.randomUUID().toString();

        Realm realm = Realm.getDefaultInstance();
        realm.executeTransaction(realm1 -> {
            RealmList<AttachmentRealmObject> attachmentRealmObjects;

            if (files != null)
                attachmentRealmObjects = attachmentsFromFiles(files, messageAttachmentType);
            else
                attachmentRealmObjects = attachmentsFromUris(uris);

            String initialID = UUID.randomUUID().toString();

            MessageRealmObject messageRealmObject = new MessageRealmObject(messageId);

            if (forwards != null && forwards.size() > 0) {
                RealmList<ForwardIdRealmObject> ids = new RealmList<>();

                for (String forward : forwards) {
                    ids.add(new ForwardIdRealmObject(forward));
                }
                messageRealmObject.setForwardedIds(ids);
            }

            messageRealmObject.setAccount(account);
            messageRealmObject.setUser(contactJid);
            messageRealmObject.setOriginalFrom(account.toString());
            messageRealmObject.setText(FileMessageVH.UPLOAD_TAG);
            messageRealmObject.setAttachmentRealmObjects(attachmentRealmObjects);
            messageRealmObject.setTimestamp(System.currentTimeMillis());
            messageRealmObject.setRead(true);
            messageRealmObject.setSent(true);
            messageRealmObject.setError(false);
            messageRealmObject.setIncoming(false);
            messageRealmObject.setInProgress(true);
            messageRealmObject.setStanzaId(initialID);
            messageRealmObject.setOriginId(initialID);
            realm1.copyToRealm(messageRealmObject);
        });
        if (Looper.getMainLooper() != Looper.myLooper()) realm.close();
        return messageId;
    }

    private RealmList<AttachmentRealmObject> attachmentsFromFiles(List<File> files,
                                                                  String messageAttachmentType) {
        RealmList<AttachmentRealmObject> attachmentRealmObjects = new RealmList<>();
        for (File file : files) {
            boolean isImage = FileManager.fileIsImage(file);
            AttachmentRealmObject attachmentRealmObject = new AttachmentRealmObject();
            attachmentRealmObject.setFilePath(file.getPath());
            attachmentRealmObject.setFileSize(file.length());
            attachmentRealmObject.setTitle(file.getName());
            attachmentRealmObject.setIsImage(isImage);
            attachmentRealmObject.setMimeType(HttpFileUploadManager.getMimeType(file.getPath()));
            if ("voice".equals(messageAttachmentType)) {
                attachmentRealmObject.setIsVoice(true);
                attachmentRealmObject.setDuration(HttpFileUploadManager
                        .getVoiceLength(file.getPath()));
            } else {
                attachmentRealmObject.setDuration((long) 0);
            }

            if (isImage) {
                HttpFileUploadManager.ImageSize imageSize =
                        HttpFileUploadManager.getImageSizes(file.getPath());
                attachmentRealmObject.setImageHeight(imageSize.getHeight());
                attachmentRealmObject.setImageWidth(imageSize.getWidth());
            }
            attachmentRealmObjects.add(attachmentRealmObject);
        }
        return attachmentRealmObjects;
    }

    private RealmList<AttachmentRealmObject> attachmentsFromUris(List<Uri> uris) {
        RealmList<AttachmentRealmObject> attachmentRealmObjects = new RealmList<>();
        for (Uri uri : uris) {
            AttachmentRealmObject attachmentRealmObject = new AttachmentRealmObject();
            attachmentRealmObject.setTitle(UriUtils.getFullFileName(uri));
            attachmentRealmObject.setIsImage(UriUtils.uriIsImage(uri));
            attachmentRealmObject.setMimeType(UriUtils.getMimeType(uri));
            attachmentRealmObject.setDuration((long) 0);
            attachmentRealmObjects.add(attachmentRealmObject);
        }
        return attachmentRealmObjects;
    }

    /**
     * @return Whether chat accepts packets from specified user.
     */
    private boolean accept(ContactJid jid) {
        return this.contactJid.equals(jid);
    }

    @Nullable
    public synchronized MessageRealmObject getLastMessage() {
        return lastMessage;
    }

    protected void updateLastMessage() {
        lastMessage = messages.last(null);
    }

    /**
     * @return Time of last message in chat. Can be <code>null</code>.
     */
    public Date getLastTime() {
        MessageRealmObject lastMessage = getLastMessage();
        if (lastMessage != null) {
            return new Date(lastMessage.getTimestamp());
        } else {
            if (lastActionTimestamp != null) {
                return new Date(getLastActionTimestamp());
            }
            return new Date();
        }
    }

    private Long getLastTimestampFromBackground() {
        Long timestamp;
        Realm bgRealm = DatabaseManager.getInstance().getDefaultRealmInstance();
        MessageRealmObject lastMessage = bgRealm
                .where(MessageRealmObject.class)
                .equalTo(MessageRealmObject.Fields.ACCOUNT, account.toString())
                .equalTo(MessageRealmObject.Fields.USER, contactJid.toString())
                .isNull(MessageRealmObject.Fields.PARENT_MESSAGE_ID)
                .isNotNull(MessageRealmObject.Fields.TEXT)
                .sort(MessageRealmObject.Fields.TIMESTAMP, Sort.ASCENDING)
                .findAll()
                .last(null);
        if (lastMessage != null && lastMessage.getTimestamp() != null) {
            timestamp = lastMessage.getTimestamp();
        } else if (lastActionTimestamp != null) {
            timestamp = lastActionTimestamp;
        } else {
            return null;
        }
        if (Looper.myLooper() != Looper.getMainLooper()) bgRealm.close();
        return timestamp;
    }

    public Long getLastActionTimestamp() {
        return lastActionTimestamp;
    }

    public void setLastActionTimestamp(Long timestamp) {
        lastActionTimestamp = timestamp;
    }

    public void setLastActionTimestamp() {
        MessageRealmObject lastMessage = getLastMessage();
        if (lastMessage != null) {
            lastActionTimestamp = lastMessage.getTimestamp();
        }
    }

    private Message createMessagePacket(String body, String stanzaId) {
        Message message = createMessagePacket(body);
        if (stanzaId != null) message.setStanzaId(stanzaId);
        return message;
    }

    private Message createMessageWithMarkupPacket(String body, String markup, String stanzaId) {
        Message message = createMessagePacket(body);
        if (stanzaId != null) message.setStanzaId(stanzaId);
        createMarkupReferences(message, markup, new StringBuilder());
        return message;
    }

    /**
     * @return New message packet to be sent.
     */
    public Message createMessagePacket(String body) {
        Message message = new Message();
        message.setTo(getTo().asBareJid());
        message.setType(getType());
        message.setBody(body);
        message.setThread(threadId);
        return message;
    }

    private Message createFileAndForwardMessagePacket(String stanzaId,
                                                      RealmList<AttachmentRealmObject> attachmentRealmObjects,
                                                      String[] forwardIds, String text) {

        Message message = new Message();
        message.setTo(getTo().asBareJid());
        message.setType(getType());
        message.setThread(threadId);
        if (stanzaId != null) message.setStanzaId(stanzaId);

        StringBuilder builder = new StringBuilder();
        createForwardMessageReferences(message, forwardIds, builder);
        builder.append(text);
        createFileMessageReferences(message, attachmentRealmObjects, builder);

        message.setBody(builder);
        return message;
    }

    /**
     * Send stanza with data-references.
     */
    private Message createFileMessagePacket(String stanzaId,
                                            RealmList<AttachmentRealmObject> attachmentRealmObjects,
                                            String body) {

        Message message = new Message();
        message.setTo(getTo().asBareJid());
        message.setType(getType());
        message.setThread(threadId);
        if (stanzaId != null) message.setStanzaId(stanzaId);

        StringBuilder builder = new StringBuilder(body);
        createFileMessageReferences(message, attachmentRealmObjects, builder);

        message.setBody(builder);
        return message;
    }

    private Message createForwardMessagePacket(String stanzaId, String[] forwardIds, String text) {
        Message message = new Message();
        message.setTo(getTo().asBareJid());
        message.setType(getType());
        message.setThread(threadId);
        if (stanzaId != null) message.setStanzaId(stanzaId);

        StringBuilder builder = new StringBuilder();
        createForwardMessageReferences(message, forwardIds, builder);
        builder.append(text);

        message.setBody(builder);
        return message;
    }

    private Message createForwardMessageWithMarkupPacket(String stanzaId, String[] forwardIds,
                                                         String text, String markupText) {
        Message message = new Message();
        message.setTo(getTo().asBareJid());
        message.setType(getType());
        message.setThread(threadId);
        if (stanzaId != null) message.setStanzaId(stanzaId);

        StringBuilder builder = new StringBuilder();
        createForwardMessageReferences(message, forwardIds, builder);
        createMarkupReferences(message, markupText, builder);
        builder.append(text);

        message.setBody(builder);
        return message;
    }

    private void createFileMessageReferences(Message message,
                                             RealmList<AttachmentRealmObject> attachmentRealmObjects,
                                             StringBuilder builder) {
        for (AttachmentRealmObject attachmentRealmObject : attachmentRealmObjects) {
            StringBuilder rowBuilder = new StringBuilder();
            if (builder.length() > 0) rowBuilder.append("\n");
            rowBuilder.append(attachmentRealmObject.getFileUrl());

            int begin = getSizeOfEncodedChars(builder.toString());
            builder.append(rowBuilder);
            ReferenceElement reference;
            if (attachmentRealmObject.isVoice()) {
                reference = ReferencesManager.createVoiceReferences(attachmentRealmObject,
                        begin, getSizeOfEncodedChars(builder.toString()));
            } else {
                reference = ReferencesManager.createMediaReferences(attachmentRealmObject,
                        begin, getSizeOfEncodedChars(builder.toString()));
            }
            message.addExtension(reference);
        }
    }

    private void createForwardMessageReferences(Message message, String[] forwardedIds,
                                                StringBuilder builder) {
        Realm realm = DatabaseManager.getInstance().getDefaultRealmInstance();
        RealmResults<MessageRealmObject> items = realm
                .where(MessageRealmObject.class)
                .in(MessageRealmObject.Fields.UNIQUE_ID, forwardedIds)
                .findAll();

        if (items != null && !items.isEmpty()) {
            for (MessageRealmObject item : items) {
                String forward = ClipManager.createMessageTree(realm, item.getUniqueId()) + "\n";
                int begin = getSizeOfEncodedChars(builder.toString());
                builder.append(forward);
                ReferenceElement reference = ReferencesManager.createForwardReference(item,
                        begin, getSizeOfEncodedChars(builder.toString()));
                message.addExtension(reference);
            }
        }
        if (Looper.myLooper() != Looper.getMainLooper()) realm.close();
    }

    // TODO make a proper html-markup parsing(or implement Html.fromHtml() and
    //  check for unnecessary newlines for paragraph-type tags newlines)
    //  This method only works with quotes.
    private void createMarkupReferences(Message message, String markupText,
                                        StringBuilder originalBuilder) {
        if (!markupText.contains("<blockquote>")) return;

        int begin = getSizeOfEncodedChars(originalBuilder.toString());
        StringBuilder markupBuilder = new StringBuilder(markupText);
        List<Pair<Integer, Integer>> spans = new ArrayList<>();

        while (true) {
            int startIndex = markupBuilder.indexOf("<blockquote>");
            if (startIndex < 0) break;
            int encodedStartIndex = getSizeOfEncodedChars(markupBuilder.substring(0, startIndex));
            markupBuilder.delete(startIndex, startIndex + "<blockquote>".length());

            int endIndex = markupBuilder.indexOf("</blockquote>");
            if (endIndex < 0) break;
            int encodedEndIndex = getSizeOfEncodedChars(markupBuilder.substring(0, endIndex));
            markupBuilder.delete(endIndex, endIndex + "</blockquote>".length());

            if (startIndex > endIndex) continue;

            spans.add(new Pair<>(encodedStartIndex, encodedEndIndex));
        }

        for (Pair<Integer, Integer> span : spans) {
            if (span.first != null && span.second != null)
                message.addExtension(new Markup(
                        begin + span.first,
                        begin + span.second,
                        false,
                        false,
                        false,
                        false,
                        true,
                        null)
            );
        }
    }

    /**
     * Prepare text to be send.
     *
     * @return <code>null</code> if text shouldn't be send.
     */
    protected String prepareText(String text) {
        return text;
    }

    public void sendMessages() {
        Application.getInstance().runInBackgroundNetworkUserRequest(() -> {
            Realm realm = null;
            try {
                realm = DatabaseManager.getInstance().getDefaultRealmInstance();
                realm.executeTransaction(realm1 -> {
                    RealmResults<MessageRealmObject> messagesToSend = realm1
                            .where(MessageRealmObject.class)
                            .equalTo(MessageRealmObject.Fields.ACCOUNT, account.toString())
                            .equalTo(MessageRealmObject.Fields.USER, contactJid.toString())
                            .equalTo(MessageRealmObject.Fields.SENT, false)
                            .sort(MessageRealmObject.Fields.TIMESTAMP, Sort.ASCENDING)
                            .findAll();

                    for (final MessageRealmObject messageRealmObject : messagesToSend) {
                        if (messageRealmObject.isInProgress()) continue;
                        if (!sendMessage(messageRealmObject)) {
                            break;
                        }
                    }
                });
            } catch (Exception e) {
                LogManager.exception(LOG_TAG, e);
            } finally {
                if (realm != null) realm.close();
            }
        });
    }

    public boolean canSendMessage() {
        return true;
    }

    public boolean sendMessage(MessageRealmObject messageRealmObject) {
        String text = prepareText(messageRealmObject.getText());
        messageRealmObject.setEncrypted(OTRManager.getInstance().isEncrypted(text));
        Long timestamp = messageRealmObject.getTimestamp();

        Date currentTime = new Date(System.currentTimeMillis());
        Date delayTimestamp = null;

        if (timestamp != null) {
            if (currentTime.getTime() - timestamp > 60000) {
                delayTimestamp = currentTime;
            }
        }

        Message message = null;

        if (messageRealmObject.haveAttachments()) {
            if (messageRealmObject.haveForwardedMessages()) {
                message = createFileAndForwardMessagePacket(messageRealmObject.getStanzaId(),
                        messageRealmObject.getAttachmentRealmObjects(),
                        messageRealmObject.getForwardedIdsAsArray(), text);
            } else {
                message = createFileMessagePacket(messageRealmObject.getStanzaId(),
                        messageRealmObject.getAttachmentRealmObjects(), text);
            }
        } else if (messageRealmObject.haveForwardedMessages()) {
            if (messageRealmObject.getMarkupText() != null
                    && !messageRealmObject.getMarkupText().isEmpty()) {
                message = createForwardMessageWithMarkupPacket(messageRealmObject.getStanzaId(),
                        messageRealmObject.getForwardedIdsAsArray(), text,
                        messageRealmObject.getMarkupText());
            } else {
                message = createForwardMessagePacket(messageRealmObject.getStanzaId(),
                        messageRealmObject.getForwardedIdsAsArray(), text);
            }
        } else if (text != null) {
            if (messageRealmObject.getMarkupText() != null
                    && !messageRealmObject.getMarkupText().isEmpty()) {
                message = createMessageWithMarkupPacket(text, messageRealmObject.getMarkupText(),
                        messageRealmObject.getStanzaId());
            } else {
                message = createMessagePacket(text, messageRealmObject.getStanzaId());
            }
        }

        if (message != null) {
            ChatStateManager.getInstance().updateOutgoingMessage(AbstractChat.this, message);
            CarbonManager.getInstance().updateOutgoingMessage(AbstractChat.this, message);
            LogManager.d(AbstractChat.class.toString(), "Message sent. Invoke CarbonManager updateOutgoingMessage");
            message.addExtension(new OriginIdElement(messageRealmObject.getStanzaId()));
            if (DeliveryManager.getInstance().isSupported(account))
                if (!messageRealmObject.isDelivered() && messageRealmObject.isSent()) {
                    message.addExtension(new RetryReceiptRequestElement());
                }
            if (delayTimestamp != null) {
                message.addExtension(new DelayInformation(delayTimestamp));
            }

            final String messageId = messageRealmObject.getUniqueId();
            try {
                StanzaSender.sendStanza(account, message, packet -> {
                    Realm realm = DatabaseManager.getInstance().getDefaultRealmInstance();
                    realm.executeTransaction(realm1 -> {
                        MessageRealmObject acknowledgedMessage = realm1
                                .where(MessageRealmObject.class)
                                .equalTo(MessageRealmObject.Fields.UNIQUE_ID, messageId)
                                .findFirst();

                        if (acknowledgedMessage != null && !DeliveryManager
                                .getInstance().isSupported(account)) {
                            acknowledgedMessage.setAcknowledged(true);
                        }
                    });
                    if (Looper.myLooper() != Looper.getMainLooper()) realm.close();
                });
            } catch (NetworkException e) {
                return false;
            }
        }

        if (message == null) {
            messageRealmObject.setError(true);
            messageRealmObject.setErrorDescription("Internal error: message is null");
        } else {
            message.setFrom(account.getFullJid());
            messageRealmObject.setOriginalStanza(message.toXML().toString());
        }

        if (delayTimestamp != null) {
            messageRealmObject.setDelayTimestamp(delayTimestamp.getTime());
        }
        if (messageRealmObject.getTimestamp() == null) {
            messageRealmObject.setTimestamp(currentTime.getTime());
        }
        messageRealmObject.setSent(true);
        return true;
    }

    public String getThreadId() {
        return threadId;
    }

    /**
     * Update thread id with new value.
     *
     * @param threadId <code>null</code> if current value shouldn't be changed.
     */
    protected void updateThreadId(String threadId) {
        if (threadId == null) {
            return;
        }
        this.threadId = threadId;
    }

    /**
     * Processes incoming packet.
     *
     * @return Whether packet was directed to this chat.
     */
    public boolean onPacket(ContactJid contactJid, Stanza packet, boolean isCarbons) {
        return accept(contactJid);
    }

    /**
     * Connection complete.f
     */
    protected void onComplete() {
    }

    /**
     * Disconnection occurred.
     */
    void onDisconnect() {
        setLastMessageId(null);
    }

    @Override
    public void onChange(@NotNull RealmResults<MessageRealmObject> messageRealmObjects) {
        updateLastMessage();
    }

    /**
     * UNREAD MESSAGES
     */

    public String getFirstUnreadMessageId() {
        String id = null;
        RealmResults<MessageRealmObject> results = getAllUnreadMessages();
        if (results != null && !results.isEmpty()) {
            MessageRealmObject firstUnreadMessage = results.first();
            if (firstUnreadMessage != null)
                id = firstUnreadMessage.getUniqueId();
        }
        return id;
    }

    public int getUnreadMessageCount() {
        int unread = getAllUnreadMessages().size() - waitToMarkAsRead.size();
        if (unread < 0) unread = 0;
        MessageRealmObject lastMessage = getLastMessage();
        if (lastMessage != null && lastMessage.isValid() && !lastMessage.isIncoming()) unread = 0;
        return unread;
    }

    private void addUnreadListener() {
        unreadMessages.addChangeListener(messageRealmObjects -> {
            for (Iterator<String> iterator = waitToMarkAsRead.iterator(); iterator.hasNext(); ) {
                String id = iterator.next();
                if (unreadMessages.where().equalTo(MessageRealmObject.Fields.UNIQUE_ID, id)
                        .findFirst() == null) {
                    iterator.remove();
                }
            }
            for (OnMessageUpdatedListener listener :
                    Application.getInstance().getUIListeners(OnMessageUpdatedListener.class)){
                listener.onMessageUpdated();
            }
        });
    }

    private void executeRead(String messageId, ArrayList<String> stanzaId, boolean trySendDisplay) {
        for (OnMessageUpdatedListener listener :
                Application.getInstance().getUIListeners(OnMessageUpdatedListener.class)){
            listener.onMessageUpdated();
        }
        BackpressureMessageReader.getInstance().markAsRead(messageId, stanzaId, account, contactJid,
                trySendDisplay);
    }

    private void executeRead(MessageRealmObject messageRealmObject, boolean trySendDisplay) {
        BackpressureMessageReader.getInstance().markAsRead(messageRealmObject, trySendDisplay);
        for (OnMessageUpdatedListener listener : Application.getInstance().getUIListeners(OnMessageUpdatedListener.class)){
            listener.onMessageUpdated();
        }
    }

    public void markAsRead(String messageId, ArrayList<String> stanzaId, boolean trySendDisplay) {
        executeRead(messageId, stanzaId, trySendDisplay);
    }

    public void markAsRead(MessageRealmObject messageRealmObject, boolean trySendDisplay) {
        if (waitToMarkAsRead.add(messageRealmObject.getUniqueId())) {
            LogManager.d(LOG_TAG, "onBind called, first time trying to read this message with id = "
                    + messageRealmObject.getOriginId()
                    + "and message timestamp = "
                    + messageRealmObject.getTimestamp());
            executeRead(messageRealmObject, trySendDisplay);
        }
    }

    public void markAsReadAll(boolean trySendDisplay) {
        LogManager.d(LOG_TAG, "executing markAsReadAll");
        RealmResults<MessageRealmObject> results = getAllUnreadMessages();
        if (results != null && !results.isEmpty()) {
            for (MessageRealmObject message : results) {
                waitToMarkAsRead.add(message.getUniqueId());
            }
            MessageRealmObject lastMessage = results.last();
            if (lastMessage != null) executeRead(lastMessage, trySendDisplay);
        }
    }

    private RealmResults<MessageRealmObject> getAllUnreadMessages() {
        if (unreadMessages == null) {
            if (messages == null) {
                unreadMessages = getMessages().where()
                        .equalTo(MessageRealmObject.Fields.INCOMING, true)
                        .equalTo(MessageRealmObject.Fields.READ, false)
                        .findAll();
            } else {
                unreadMessages = messages.where()
                        .equalTo(MessageRealmObject.Fields.INCOMING, true)
                        .equalTo(MessageRealmObject.Fields.READ, false)
                        .findAll();
            }
            addUnreadListener();
        }
        return unreadMessages;
    }

    public boolean isArchived() {
        return archived;
    }

    public void setArchived(boolean archived) {
        this.archived = archived;
        ChatManager.getInstance().saveOrUpdateChatDataToRealm(this);
    }

    public void setArchivedWithoutRealm(boolean archived) {
        this.archived = archived;
    }

    public boolean isAddContactSuggested() {
        return addContactSuggested;
    }

    public void setAddContactSuggested(boolean suggested) {
        addContactSuggested = suggested;
    }

    public NotificationState getNotificationState() {
        enableNotificationsIfNeed();
        return notificationState;
    }

    public void setNotificationState(NotificationState notificationState, boolean needSaveToRealm) {
        this.notificationState = notificationState;

        if (notificationState.getMode() == NotificationState.NotificationMode.disabled && needSaveToRealm){
            NotificationManager.getInstance().removeMessageNotification(account, contactJid);
            ChatManager.getInstance().saveOrUpdateChatDataToRealm(this);
        }
    }

    public void setNotificationStateOrDefault(NotificationState notificationState,
                                              boolean needSaveToRealm) {

        if (notificationState.getMode() != NotificationState.NotificationMode.enabled
                && notificationState.getMode() != NotificationState.NotificationMode.disabled)
            throw new IllegalStateException("In this method mode must be enabled or disabled.");

        if (!eventsOnChatGlobal()
                && notificationState.getMode() == NotificationState.NotificationMode.disabled
                || eventsOnChatGlobal()
                && notificationState.getMode() == NotificationState.NotificationMode.enabled)
            notificationState.setMode(NotificationState.NotificationMode.byDefault);

        setNotificationState(notificationState, needSaveToRealm);
    }

    private boolean eventsOnChatGlobal() {
        return SettingsManager.eventsOnChat();
    }

    public int getLastPosition() {
        return lastPosition;
    }

    public void setLastPosition(int lastPosition) {
        this.lastPosition = lastPosition;
    }

    public void saveLastPosition(int lastPosition) {
        this.lastPosition = lastPosition;
        ChatManager.getInstance().saveOrUpdateChatDataToRealm(this);
    }

    public RealmList<ForwardIdRealmObject> parseForwardedMessage(boolean ui, Stanza packet,
                                                                 String parentMessageId) {
        List<Forwarded> forwarded = ReferencesManager.getForwardedFromReferences(packet);
        if (forwarded.isEmpty()) forwarded = ForwardManager.getForwardedFromStanza(packet);
        if (forwarded.isEmpty()) return null;

        RealmList<ForwardIdRealmObject> forwardedIds = new RealmList<>();
        for (Forwarded forward : forwarded) {
            Stanza stanza = forward.getForwardedStanza();
            DelayInformation delayInformation = forward.getDelayInformation();
            Date timestamp = delayInformation.getStamp();
            if (stanza instanceof Message) {
                forwardedIds.add(new ForwardIdRealmObject(parseInnerMessage(ui, (Message) stanza,
                        timestamp, parentMessageId)));
            }
        }
        return forwardedIds;
    }

    protected abstract String parseInnerMessage(boolean ui, Message message, Date timestamp, String parentMessageId);

    public String getLastMessageId() {
        return lastMessageId;
    }

    public void setLastMessageId(String lastMessageId) {
        this.lastMessageId = lastMessageId;
    }

    public boolean historyIsFull() {
        return historyIsFull;
    }

    public void setHistoryIsFull() {
        this.historyIsFull = true;
    }

    public boolean isHistoryRequestedAtStart() {
        return historyRequestedAtStart;
    }

    public void setHistoryRequestedAtStart() {
        this.historyRequestedAtStart = true;
        ChatManager.getInstance().saveOrUpdateChatDataToRealm(this);
    }

    public void setHistoryRequestedWithoutRealm(boolean isHistoryRequested) {
        this.historyRequestedAtStart = isHistoryRequested;
    }

}