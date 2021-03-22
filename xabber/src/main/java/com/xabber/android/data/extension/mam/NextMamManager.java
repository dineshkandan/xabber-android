package com.xabber.android.data.extension.mam;

import android.os.Looper;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.xabber.android.data.Application;
import com.xabber.android.data.account.AccountItem;
import com.xabber.android.data.account.AccountManager;
import com.xabber.android.data.connection.ConnectionItem;
import com.xabber.android.data.connection.listeners.OnPacketListener;
import com.xabber.android.data.database.DatabaseManager;
import com.xabber.android.data.database.realmobjects.AttachmentRealmObject;
import com.xabber.android.data.database.realmobjects.ForwardIdRealmObject;
import com.xabber.android.data.database.realmobjects.MessageRealmObject;
import com.xabber.android.data.database.realmobjects.SyncInfoRealmObject;
import com.xabber.android.data.entity.AccountJid;
import com.xabber.android.data.entity.ContactJid;
import com.xabber.android.data.extension.groups.GroupInviteManager;
import com.xabber.android.data.extension.groups.GroupMemberManager;
import com.xabber.android.data.extension.groups.GroupsManager;
import com.xabber.android.data.extension.httpfileupload.HttpFileUploadManager;
import com.xabber.android.data.extension.otr.OTRManager;
import com.xabber.android.data.extension.references.ReferencesManager;
import com.xabber.android.data.extension.reliablemessagedelivery.TimeElement;
import com.xabber.android.data.extension.vcard.VCardManager;
import com.xabber.android.data.log.LogManager;
import com.xabber.android.data.message.ForwardManager;
import com.xabber.android.data.message.chat.AbstractChat;
import com.xabber.android.data.message.chat.ChatManager;
import com.xabber.android.data.message.chat.GroupChat;
import com.xabber.android.data.notification.NotificationManager;
import com.xabber.android.data.push.SyncManager;
import com.xabber.android.data.roster.OnRosterReceivedListener;
import com.xabber.android.data.roster.PresenceManager;
import com.xabber.android.data.roster.RosterContact;
import com.xabber.android.data.roster.RosterManager;
import com.xabber.android.ui.OnLastHistoryLoadFinishedListener;
import com.xabber.android.ui.OnLastHistoryLoadStartedListener;
import com.xabber.android.ui.OnNewMessageListener;
import com.xabber.android.utils.StringUtils;
import com.xabber.xmpp.groups.GroupMemberExtensionElement;
import com.xabber.xmpp.groups.GroupchatExtensionElement;
import com.xabber.xmpp.groups.invite.incoming.IncomingInviteExtensionElement;
import com.xabber.xmpp.sid.UniqueIdsHelper;
import com.xabber.xmpp.smack.XMPPTCPConnection;

import net.java.otr4j.io.SerializationUtils;
import net.java.otr4j.io.messages.PlainTextMessage;

import org.jivesoftware.smack.SmackException;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.packet.ExtensionElement;
import org.jivesoftware.smack.packet.IQ;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.Stanza;
import org.jivesoftware.smack.util.PacketParserUtils;
import org.jivesoftware.smackx.delay.packet.DelayInformation;
import org.jivesoftware.smackx.disco.ServiceDiscoveryManager;
import org.jivesoftware.smackx.forward.packet.Forwarded;
import org.jivesoftware.smackx.mam.MamManager;
import org.jivesoftware.smackx.mam.element.MamElements;
import org.jivesoftware.smackx.mam.element.MamFinIQ;
import org.jivesoftware.smackx.mam.element.MamPrefsIQ;
import org.jivesoftware.smackx.mam.element.MamQueryIQ;
import org.jivesoftware.smackx.rsm.packet.RSMSet;
import org.jivesoftware.smackx.xdata.FormField;
import org.jivesoftware.smackx.xdata.packet.DataForm;
import org.jxmpp.jid.Jid;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.realm.Realm;
import io.realm.RealmList;
import io.realm.RealmResults;
import io.realm.Sort;

public class NextMamManager implements OnRosterReceivedListener, OnPacketListener {

    private static final String LOG_TAG = NextMamManager.class.getSimpleName();
    public static final String NAMESPACE = "urn:xmpp:mam:tmp";

    private static NextMamManager instance;

    private final Map<AccountJid, Boolean> supportedByAccount = new ConcurrentHashMap<>();
    private boolean isRequested = false;
    private final Object lock = new Object();
    private final Map<String, ContactJid> waitingRequests = new HashMap<>();
    private final Map<AccountItem, Iterator<RosterContact>> rosterItemIterators = new ConcurrentHashMap<>();

    private static final Comparator<Forwarded> archiveMessageTimeComparator = (o1, o2) -> {
        long time1 = o1.getDelayInformation().getStamp().getTime();
        long time2 = o2.getDelayInformation().getStamp().getTime();
        return Long.compare(time1, time2);
    };

    public static NextMamManager getInstance() {
        if (instance == null)
            instance = new NextMamManager();
        return instance;
    }

    @Override
    public void onRosterReceived(AccountItem accountItem) {
        LogManager.d(LOG_TAG, "onRosterReceivedStarted");
        updateIsSupported(accountItem);
        //updatePreferencesFromServer(accountItem);
        //LogManager.d("AccountRosterListener", "finished updating preferences");
        Realm realm = DatabaseManager.getInstance().getDefaultRealmInstance();
        accountItem.setStartHistoryTimestamp(getLastMessageTimestamp(accountItem, realm));
        if (accountItem.getStartHistoryTimestamp() == 0) {
            initializeStartTimestamp(realm, accountItem);
            loadMostRecentMessages(realm, accountItem);
            startLoadingLastMessageInAllChats(accountItem);
        } else {
            if (isNeedMigration(accountItem, realm)) runMigrationToNewArchive(accountItem, realm);
            String lastArchivedId = getLastMessageArchivedId(accountItem, realm);
            if (lastArchivedId != null) {
                boolean historyCompleted = loadAllNewMessages(realm, accountItem, lastArchivedId);
                if (!historyCompleted) {
                    startLoadingLastMessageInAllChats(accountItem);
                } else startLoadingLastMessageInMissedChats(realm, accountItem);
            } else startLoadingLastMessageInAllChats(accountItem);
        }
        updatePreferencesFromServer(accountItem);
        if (Looper.myLooper() != Looper.getMainLooper()) realm.close();
    }

    public void onChatOpen(final AbstractChat chat) {
        final AccountItem accountItem = AccountManager.getInstance().getAccount(chat.getAccount());
        if (accountItem == null
                || accountItem.getLoadHistorySettings() == LoadHistorySettings.none
                || !isSupported(accountItem.getAccount())) return;

        Application.getInstance().runInBackgroundNetworkUserRequest(() -> {
            Realm realm = DatabaseManager.getInstance().getDefaultRealmInstance();

            // if history is empty - load last message
            MessageRealmObject firstMessage = getFirstMessage(chat, realm);
            if (firstMessage == null) loadLastMessage(realm, accountItem, chat);

            synchronized (lock) {
                if (isRequested) {
                    return;
                } else isRequested = true;
            }

            // load prev page if history is not enough
            if (historyIsNotEnough(realm, chat) && !chat.historyIsFull()) {
                for (OnLastHistoryLoadStartedListener listener :
                        Application.getInstance().getUIListeners(OnLastHistoryLoadStartedListener.class)){
                    listener.onLastHistoryLoadStarted(chat.getAccount(), chat.getContactJid());
                }
                loadNextHistory(realm, accountItem, chat);
                for (OnLastHistoryLoadFinishedListener listener :
                        Application.getInstance().getUIListeners(OnLastHistoryLoadFinishedListener.class)){
                    listener.onLastHistoryLoadFinished(chat.getAccount(), chat.getContactJid());
                }
            }

            // load missed messages if need
            List<MessageRealmObject> messages = findMissedMessages(realm, chat);
            if (messages != null && !messages.isEmpty() && accountItem != null) {
                for (MessageRealmObject message : messages) {
                    loadMissedMessages(realm, accountItem, chat, message);
                }
            }

            synchronized (lock) {
                isRequested = false;
            }
            if (Looper.myLooper() != Looper.getMainLooper()) realm.close();
        });
    }

    public void onScrollInChat(final AbstractChat chat) {
        final AccountItem accountItem = AccountManager.getInstance().getAccount(chat.getAccount());
        if (accountItem == null
                || accountItem.getLoadHistorySettings() == LoadHistorySettings.none
                || !isSupported(accountItem.getAccount())) return;

        if (chat.historyIsFull()) return;
        Application.getInstance().runInBackgroundNetworkUserRequest(() -> {
            synchronized (lock) {
                if (isRequested){
                    return;
                } else isRequested = true;
            }
            for (OnLastHistoryLoadStartedListener listener :
                    Application.getInstance().getUIListeners(OnLastHistoryLoadStartedListener.class)){
                listener.onLastHistoryLoadStarted(chat.getAccount(), chat.getContactJid());
            }
            Realm realm = DatabaseManager.getInstance().getDefaultRealmInstance();
            loadNextHistory(realm, accountItem, chat);
            if (Looper.myLooper() != Looper.getMainLooper()) realm.close();
            for (OnLastHistoryLoadFinishedListener listener :
                    Application.getInstance().getUIListeners(OnLastHistoryLoadFinishedListener.class)){
                listener.onLastHistoryLoadFinished(chat.getAccount(), chat.getContactJid());
            }
            synchronized (lock) {
                isRequested = false;
            }
        });
    }

    public void loadFullChatHistory(AbstractChat chat) {
        final AccountItem accountItem = AccountManager.getInstance().getAccount(chat.getAccount());
        if (accountItem == null || !isSupported(accountItem.getAccount()) || chat.historyIsFull()) return;

        Realm realm = DatabaseManager.getInstance().getDefaultRealmInstance();

        // if history is empty - load last message
        MessageRealmObject firstMessage = getFirstMessage(chat, realm);
        if (firstMessage == null) loadLastMessage(realm, accountItem, chat);

        boolean complete = false;
        while (!complete) {
            complete = loadNextHistory(realm, accountItem, chat);
        }

        if (Looper.myLooper() != Looper.getMainLooper()) realm.close();
    }

    public void onRequestUpdatePreferences(AccountJid accountJid) {
        final AccountItem accountItem = AccountManager.getInstance().getAccount(accountJid);
        if (accountItem == null || !isSupported(accountJid)) return;

        Application.getInstance().runInBackgroundNetworkUserRequest(() -> requestUpdatePreferences(accountItem));
    }

    @Override
    public void onStanza(ConnectionItem connection, Stanza packet) {
        if (packet instanceof Message) {
            for (ExtensionElement packetExtension : packet.getExtensions()) {
                if (packetExtension instanceof MamElements.MamResultExtension) {
                    MamElements.MamResultExtension resultExtension = (MamElements.MamResultExtension) packetExtension;
                    String resultID = resultExtension.getQueryId();
                    if (waitingRequests.containsKey(resultID)) {
                        Stanza forwardedStanza = resultExtension.getForwarded().getForwardedStanza();
                        if (forwardedStanza.hasExtension(IncomingInviteExtensionElement.ELEMENT,
                                IncomingInviteExtensionElement.NAMESPACE)){
                            try{
                                IncomingInviteExtensionElement inviteElement = forwardedStanza
                                        .getExtension(IncomingInviteExtensionElement.ELEMENT,
                                                IncomingInviteExtensionElement.NAMESPACE);
                                long timestamp = 0;
                                if (forwardedStanza.hasExtension(TimeElement.ELEMENT, TimeElement.NAMESPACE)) {
                                    TimeElement timeElement = (TimeElement) forwardedStanza.getExtension(TimeElement.ELEMENT,
                                            TimeElement.NAMESPACE);
                                    timestamp = StringUtils.parseReceivedReceiptTimestampString(timeElement.getStamp()).getTime();
                                }
                                GroupInviteManager.INSTANCE.processIncomingInvite(inviteElement, connection.getAccount(),
                                        ContactJid.from(forwardedStanza.getFrom()), timestamp);
                            } catch (Exception e) { LogManager.exception(LOG_TAG, e); }
                            return;
                        }
                        Realm realm = DatabaseManager.getInstance().getDefaultRealmInstance();
                        parseAndSaveMessageFromMamResult(realm, connection.getAccount(), resultExtension.getForwarded());
                        ContactJid contactJid = waitingRequests.get(resultID);
                        AbstractChat chat = ChatManager.getInstance().getChat(connection.getAccount(), contactJid);
                        if (chat != null && !chat.isHistoryRequestedAtStart()) chat.setHistoryRequestedAtStart();
                        waitingRequests.remove(resultID);
                        loadNextLastMessageAsync(connection.getAccount());
                        if (Looper.myLooper() != Looper.getMainLooper()) realm.close();
                    }
                }
            }
        }
        if (packet instanceof MamFinIQ) {
            MamFinIQ finIQ = (MamFinIQ) packet;
            if (finIQ.isComplete() && waitingRequests.containsKey(finIQ.getQueryId())) {
                ContactJid contactJid = waitingRequests.get(finIQ.getQueryId());
                AbstractChat chat = ChatManager.getInstance().getChat(connection.getAccount(), contactJid);
                if (chat != null && !chat.isHistoryRequestedAtStart()) chat.setHistoryRequestedAtStart();
                waitingRequests.remove(finIQ.getQueryId());
                loadNextLastMessageAsync(connection.getAccount());
            }
        } else if (packet instanceof MamQueryIQ) {
            MamQueryIQ queryIQ = (MamQueryIQ) packet;
            if (queryIQ.getError() != null && waitingRequests.containsKey(queryIQ.getQueryId())) {
                waitingRequests.remove(queryIQ.getQueryId());
                loadNextLastMessageAsync(connection.getAccount());
            }
        }
    }

    public boolean isSupported(AccountJid accountJid) {
        Boolean isSupported = supportedByAccount.get(accountJid);
        if (isSupported != null) {
            return isSupported;
        } else return false;
    }

    public void resetContactHistoryIterator(AccountJid accountJid) {
        AccountItem accountItem = AccountManager.getInstance().getAccount(accountJid);
        rosterItemIterators.remove(accountItem);
    }

    /**
     * Start the process of loading last messages one by one in chats
     * without last message and with history not requested.
     * <p>
     * This is needed just in case the initial last message
     * loading was interrupted previously.
     * */
    private void startLoadingLastMessageInMissedChats(Realm realm, AccountItem accountItem) {
        if (accountItem.getLoadHistorySettings() != LoadHistorySettings.all
                || !isSupported(accountItem.getAccount())) return;

        Collection<RosterContact> contacts = RosterManager.getInstance()
                .getAccountRosterContacts(accountItem.getAccount());
        Collection<RosterContact> contactsWithoutHistory = new ArrayList<>();

        for (RosterContact contact : contacts) {
            AbstractChat chat = ChatManager.getInstance().getChat(contact.getAccount(), contact.getContactJid());
            if (chat == null) {
                chat = ChatManager.getInstance().createRegularChat(contact.getAccount(), contact.getContactJid());
            }
            if (getFirstMessage(chat, realm) == null && !chat.isHistoryRequestedAtStart()) {
                contactsWithoutHistory.add(contact);
            }
        }


        if (rosterItemIterators.get(accountItem) == null) {
            rosterItemIterators.put(accountItem, contactsWithoutHistory.iterator());
        }

        loadNextLastMessageAsync(accountItem);
    }

    /**
     * Start the process of loading last messages one by one for all contacts.
     * */
    private void startLoadingLastMessageInAllChats(AccountItem accountItem) {
        if (accountItem.getLoadHistorySettings() != LoadHistorySettings.all || !isSupported(accountItem.getAccount())) {
            return;
        }

        if (rosterItemIterators.get(accountItem) == null) {
            Collection<RosterContact> contacts = RosterManager.getInstance()
                    .getAccountRosterContacts(accountItem.getAccount());
            rosterItemIterators.put(accountItem, contacts.iterator());
        }

        loadNextLastMessageAsync(accountItem);
    }

    private void loadNextLastMessageAsync(AccountJid accountJid) {
        AccountItem accountItem = AccountManager.getInstance().getAccount(accountJid);
        if (accountItem != null) loadNextLastMessageAsync(accountItem);
    }

    private void loadNextLastMessageAsync(AccountItem accountItem) {
        if (accountItem.getLoadHistorySettings() != LoadHistorySettings.all || !isSupported(accountItem.getAccount())) {
            return;
        }

        Iterator<RosterContact> iterator = rosterItemIterators.get(accountItem);
        if (iterator != null) {
            if (iterator.hasNext()) {
                RosterContact contact = iterator.next();
                LogManager.d(LOG_TAG, "load last message in " + contact + " chat");
                AbstractChat chat = ChatManager.getInstance().getChat(contact.getAccount(), contact.getContactJid());
                if (chat == null){
                    chat = ChatManager.getInstance().createRegularChat(contact.getAccount(), contact.getContactJid());
                }
                requestLastMessageAsync(accountItem, chat);
            } else {
                LogManager.d(LOG_TAG, "finished loading first messages of " + accountItem.getAccount());
                VCardManager.getInstance().onHistoryLoaded(accountItem);
                PresenceManager.getInstance().onHistoryLoaded(accountItem);
                rosterItemIterators.remove(accountItem);
            }
        }
    }

    private void loadLastMessage(Realm realm, AccountItem accountItem, AbstractChat chat) {
        LogManager.d(LOG_TAG, "load last messages in chat: " + chat.getContactJid());
        MamManager.MamQueryResult queryResult = requestLastMessage(accountItem, chat);
        if (queryResult != null) {
            List<Forwarded> messages = new ArrayList<>(queryResult.forwardedMessages);
            saveOrUpdateMessages(realm,
                    parseMessage(accountItem, chat.getAccount(), chat.getContactJid(), messages, null));
        }
        updateLastMessageId(chat, realm);
    }

    private void loadMostRecentMessages(Realm realm, AccountItem accountItem) {
        if (accountItem.getLoadHistorySettings() != LoadHistorySettings.all || !isSupported(accountItem.getAccount())) {
            return;
        }

        LogManager.d(LOG_TAG, "load new messages");
        List<Forwarded> messages = new ArrayList<>();

        MamManager.MamQueryResult queryResult = requestRecentMessages(accountItem, null);
        if (queryResult != null) messages.addAll(queryResult.forwardedMessages);


        if (!messages.isEmpty()) {
            HashMap<String, ArrayList<Forwarded>> messagesByChat;
            List<MessageRealmObject> parsedMessages = new ArrayList<>();
            List<AbstractChat> chatsNeedUpdateLastMessageId = new ArrayList<>();

            messagesByChat = sortNewMessagesByChats(messages, accountItem);
            for (Map.Entry<String, ArrayList<Forwarded>> entry : messagesByChat.entrySet()) {
                ArrayList<Forwarded> list = entry.getValue();
                if (list != null) {
                    try {
                        AbstractChat chat = ChatManager.getInstance().getChat(accountItem.getAccount(), ContactJid.from(entry.getKey()));
                        if (chat == null){
                            chat = ChatManager.getInstance()
                                    .createRegularChat(accountItem.getAccount(), ContactJid.from(entry.getKey()));
                        }

                        int oldSize = parsedMessages.size();
                        parsedMessages.addAll(parseNewMessagesInChat(list, chat, accountItem));

                        if (parsedMessages.size() - oldSize > 0) chatsNeedUpdateLastMessageId.add(chat);

                    } catch (ContactJid.ContactJidCreateException e) {
                        LogManager.d(LOG_TAG, e.toString());
                    }
                }
            }

            saveOrUpdateMessages(realm, parsedMessages);
            for (AbstractChat chat : chatsNeedUpdateLastMessageId) {
                updateLastMessageId(chat, realm);
                chat.setHistoryRequestedWithoutRealm(true);
            }
            for (AbstractChat chat : chatsNeedUpdateLastMessageId) {
                ChatManager.getInstance().saveOrUpdateChatDataToRealm(chat);
            }
        }
    }

    private boolean loadAllNewMessages(Realm realm, AccountItem accountItem, String lastArchivedId) {
        if (accountItem.getLoadHistorySettings() != LoadHistorySettings.all
                || !isSupported(accountItem.getAccount())) {
            return true;
        }

        LogManager.d(LOG_TAG, "load new messages");
        List<Forwarded> messages = new ArrayList<>();
        boolean complete = false;
        String id = lastArchivedId;
        int pageLoaded = 0;
        // Request all new messages after last archived id
        while (!complete && id != null && pageLoaded < 2) {
//            ArrayList<Jid> archiveAdresses;
//            for (AbstractChat abstractChat : ChatManager.getInstance().getChats())

            MamManager.MamQueryResult queryResult = requestMessagesFromId(accountItem, null, id);
            if (queryResult != null) {
                messages.addAll(queryResult.forwardedMessages);
                complete = queryResult.mamFin.isComplete();
                id = getNextArchivedId(queryResult);
                pageLoaded++;
            } else complete = true;
        }

        if (!messages.isEmpty()) {
            HashMap<String, ArrayList<Forwarded>> messagesByChat;
            List<MessageRealmObject> parsedMessages = new ArrayList<>();
            List<AbstractChat> chatsNeedUpdateLastMessageId = new ArrayList<>();

            messagesByChat = sortNewMessagesByChats(messages, accountItem);

            // parse message lists
            for (Map.Entry<String, ArrayList<Forwarded>> entry : messagesByChat.entrySet()) {
                ArrayList<Forwarded> list = entry.getValue();
                if (list != null) {
                    try {
                        AbstractChat chat = ChatManager.getInstance()
                                .getChat(accountItem.getAccount(), ContactJid.from(entry.getKey()));
                        if (chat == null) chat = ChatManager.getInstance()
                                .createRegularChat(accountItem.getAccount(), ContactJid.from(entry.getKey()));

                        int oldSize = parsedMessages.size();
                        parsedMessages.addAll(parseNewMessagesInChat(list, chat, accountItem));

                        if (parsedMessages.size() - oldSize > 0) chatsNeedUpdateLastMessageId.add(chat);

                    } catch (ContactJid.ContactJidCreateException e) {
                        LogManager.d(LOG_TAG, e.toString());
                    }
                }
            }

            // save messages to Realm
            saveOrUpdateMessages(realm, parsedMessages);
            for (AbstractChat chat : chatsNeedUpdateLastMessageId) {
                updateLastMessageId(chat, realm);
            }
        }
        return complete;
    }

    private static HashMap<String, ArrayList<Forwarded>> sortNewMessagesByChats(List<Forwarded> messages,
                                                                                AccountItem accountItem) {
        HashMap<String, ArrayList<Forwarded>> sortedMapOfChats = new HashMap<>();
        for (Forwarded forwarded : messages) {
            Stanza stanza = forwarded.getForwardedStanza();
            Jid user = stanza.getFrom().asBareJid();
            if (user.equals(accountItem.getAccount().getFullJid().asBareJid())) user = stanza.getTo().asBareJid();

            if (!sortedMapOfChats.containsKey(user.toString())) {
                sortedMapOfChats.put(user.toString(), new ArrayList<>());
            }

            ArrayList<Forwarded> list = sortedMapOfChats.get(user.toString());
            if (list != null) list.add(forwarded);
        }
        return sortedMapOfChats;
    }

    private List<MessageRealmObject> parseNewMessagesInChat(ArrayList<Forwarded> chatMessages, AbstractChat chat,
                                                            AccountItem accountItem) {
        Collections.sort(chatMessages, archiveMessageTimeComparator);
        return new ArrayList<>(parseMessage(accountItem, accountItem.getAccount(), chat.getContactJid(),
                chatMessages, chat.getLastMessageId()));
    }

    private boolean loadNextHistory(Realm realm, AccountItem accountItem, AbstractChat chat) {
        LogManager.d(LOG_TAG, "load next history in chat: " + chat.getContactJid());
        MessageRealmObject firstMessage = getFirstMessage(chat, realm);
        if (firstMessage != null) {
            if (firstMessage.getArchivedId().equals(firstMessage.getPreviousId())) {
                chat.setHistoryIsFull();
                return true;
            }

            MamManager.MamQueryResult queryResult = requestMessagesBeforeId(accountItem, chat,
                    firstMessage.getArchivedId());

            if (queryResult != null) {
                List<Forwarded> messages = new ArrayList<>(queryResult.forwardedMessages);
                if (!messages.isEmpty()) {
                    List<MessageRealmObject> savedMessages = saveOrUpdateMessages(realm,
                            parseMessage(accountItem, chat.getAccount(), chat.getContactJid(), messages, null));

                    if (savedMessages != null && !savedMessages.isEmpty()) {
                        realm.beginTransaction();
                        firstMessage.setPreviousId(savedMessages.get(savedMessages.size() - 1).getArchivedId());
                        realm.commitTransaction();
                        return false;
                    }
                } else if (queryResult.mamFin.isComplete()) {
                    realm.beginTransaction();
                    firstMessage.setPreviousId(firstMessage.getArchivedId());
                    realm.commitTransaction();
                }
            }
        }
        return true;
    }

    private void loadMissedMessages(Realm realm, AccountItem accountItem, AbstractChat chat, MessageRealmObject m1) {
        LogManager.d(LOG_TAG, "load missed messages in chat: " + chat.getContactJid());
        MessageRealmObject m2 = getMessageForCloseMissedMessages(realm, m1);
        if (m2 != null && !m2.getUniqueId().equals(m1.getUniqueId())) {
            Date startDate = new Date(m2.getTimestamp());
            Date endDate = new Date(m1.getTimestamp());

            List<Forwarded> messages = new ArrayList<>();
            boolean complete = false;

            while (!complete && startDate != null) {
                MamManager.MamQueryResult queryResult = requestMissedMessages(accountItem, chat, startDate, endDate);
                if (queryResult != null) {
                    messages.addAll(queryResult.forwardedMessages);
                    complete = queryResult.mamFin.isComplete();
                    startDate = getNextDate(queryResult);
                } else complete = true;
            }

            if (!messages.isEmpty()) {
                List<MessageRealmObject> savedMessages = saveOrUpdateMessages(realm,
                        parseMessage(accountItem, chat.getAccount(), chat.getContactJid(), messages, m2.getArchivedId()));

                if (savedMessages != null && !savedMessages.isEmpty()) {
                    realm.beginTransaction();
                    m1.setPreviousId(savedMessages.get(savedMessages.size() - 1).getArchivedId());
                    realm.commitTransaction();
                }

            } else {
                realm.beginTransaction();
                m1.setPreviousId(m2.getArchivedId());
                realm.commitTransaction();
            }
        }
    }

    /** Request most recent message from all history and save it timestamp to startHistoryTimestamp
     *  If message is null save current time to startHistoryTimestamp */
    private void initializeStartTimestamp(Realm realm, @NonNull AccountItem accountItem) {
        long startHistoryTimestamp = System.currentTimeMillis();

        MamManager.MamQueryResult queryResult = requestLastMessage(accountItem, null);
        if (queryResult != null && !queryResult.forwardedMessages.isEmpty()) {
            Forwarded forwarded = queryResult.forwardedMessages.get(0);
            startHistoryTimestamp = forwarded.getDelayInformation().getStamp().getTime();
            Stanza forwardedStanza = forwarded.getForwardedStanza();
            if (forwardedStanza.hasExtension(IncomingInviteExtensionElement.ELEMENT,
                    IncomingInviteExtensionElement.NAMESPACE)){
                try{
                    IncomingInviteExtensionElement inviteElement = forwardedStanza
                            .getExtension(IncomingInviteExtensionElement.ELEMENT,
                                    IncomingInviteExtensionElement.NAMESPACE);
                    long timestamp = 0;
                    if (forwardedStanza.hasExtension(TimeElement.ELEMENT, TimeElement.NAMESPACE)) {
                        TimeElement timeElement = (TimeElement) forwardedStanza.getExtension(TimeElement.ELEMENT,
                                TimeElement.NAMESPACE);
                        timestamp = StringUtils.parseReceivedReceiptTimestampString(timeElement.getStamp()).getTime();
                    }
                    GroupInviteManager.INSTANCE.processIncomingInvite(inviteElement, accountItem.getAccount(),
                            ContactJid.from(forwardedStanza.getFrom()), timestamp);
                } catch (Exception e) { LogManager.exception(LOG_TAG, e); }
                accountItem.setStartHistoryTimestamp(startHistoryTimestamp);
                return;
            }
            parseAndSaveMessageFromMamResult(realm, accountItem.getAccount(), forwarded);
        }
        accountItem.setStartHistoryTimestamp(startHistoryTimestamp);
    }

    private void updateIsSupported(AccountItem accountItem) {
        boolean isSupported;
        try {
            isSupported = ServiceDiscoveryManager.getInstanceFor(accountItem.getConnection())
                    .supportsFeature(accountItem.getConnection().getUser().asBareJid(),
                            MamElements.NAMESPACE);
        } catch (SmackException.NoResponseException | XMPPException.XMPPErrorException
                | InterruptedException | SmackException.NotConnectedException | ClassCastException e) {
            LogManager.exception(LOG_TAG, e);
            isSupported = false;
        }
        supportedByAccount.put(accountItem.getAccount(), isSupported);
        AccountManager.getInstance().onAccountChanged(accountItem.getAccount());
        if (!isSupported) VCardManager.getInstance().onHistoryLoaded(accountItem);
    }

    private void updatePreferencesFromServer(@NonNull AccountItem accountItem) {
        MamManager.MamPrefsResult prefsResult = requestPreferencesFromServer(accountItem);
        if (prefsResult != null) {
            MamPrefsIQ.DefaultBehavior behavior = prefsResult.mamPrefs.getDefault();
            AccountManager.getInstance().setMamDefaultBehaviour(accountItem.getAccount(), behavior);
        }
    }

    /** T extends MamManager.MamQueryResult or T extends MamManager.MamPrefsResult */
    abstract static class MamRequest<T>  {
        abstract T execute(MamManager manager) throws Exception;
    }

    /** T extends MamManager.MamQueryResult or T extends MamManager.MamPrefsResult */
    private <T> T requestToMessageArchive(ConnectionItem accountItem, MamRequest<T> request) {
        T result = null;
        XMPPTCPConnection connection = accountItem.getConnection();

        if (connection.isAuthenticated()) {
            MamManager mamManager = MamManager.getInstanceFor(connection);
            try {
                result = request.execute(mamManager);
            } catch (Exception e) {
                LogManager.exception(LOG_TAG, e);
            }
        }
        return result;
    }

    /** Request recent message from chat history if chat not null
     *  Else request most recent message from all history*/
    private @Nullable MamManager.MamQueryResult requestLastMessage(@NonNull AccountItem accountItem,
                                                                   @Nullable final AbstractChat chat) {
        return requestToMessageArchive(accountItem, new MamRequest<MamManager.MamQueryResult>() {
            @Override
            MamManager.MamQueryResult execute(MamManager manager) throws Exception {
                if (chat != null) {
                    return manager.mostRecentPage(chat.getContactJid().getJid(), 1);
                } else return manager.mostRecentPage(null, 1);
            }
        });
    }

    private @Nullable MamManager.MamQueryResult requestRecentMessages(@NonNull AccountItem accountItem,
                                                                      @Nullable final AbstractChat chat) {
        return requestToMessageArchive(accountItem, new MamRequest<MamManager.MamQueryResult>() {
            @Override
            MamManager.MamQueryResult execute(MamManager manager) throws Exception {
                if (chat != null) {
                    return manager.mostRecentPage(chat.getContactJid().getJid(), 50);
                } else return manager.mostRecentPage(null, 50);
            }
        });
    }

    /** Send async request for recent message from chat history */
    private void requestLastMessageAsync(@NonNull final ConnectionItem accountItem, @NonNull final AbstractChat chat) {
        requestToMessageArchive(accountItem, new MamRequest<MamManager.MamQueryResult>() {
            @Override
            MamManager.MamQueryResult execute(MamManager manager) throws Exception {
                // add request id to waiting list
                String queryID = UUID.randomUUID().toString();
                waitingRequests.put(queryID, chat.getContactJid());

                // send request stanza
                RSMSet rsmSet = new RSMSet(null, "", -1, -1, null, 1, null, -1);
                DataForm dataForm = getNewMamForm();
                addWithJid(chat.getContactJid().getJid(), dataForm);
                MamQueryIQ mamQueryIQ = new MamQueryIQ(queryID, null, dataForm);
                mamQueryIQ.setType(IQ.Type.set);
                if (chat instanceof GroupChat) {
                    mamQueryIQ.setTo(chat.getContactJid().getBareJid());
                } else mamQueryIQ.setTo((Jid) null);
                mamQueryIQ.addExtension(rsmSet);
                accountItem.getConnection().sendStanza(mamQueryIQ);
                return null;
            }
        });
    }

    public void requestSingleMessageAsync(@NonNull final ConnectionItem accountItem, @NonNull final AbstractChat chat,
                                          String stanzaId){
        requestToMessageArchive(accountItem, new MamRequest<MamManager.MamQueryResult>() {
            @Override
            MamManager.MamQueryResult execute(MamManager manager) throws Exception {
                // add request id to waiting list
                String queryID = UUID.randomUUID().toString();
                waitingRequests.put(queryID, chat.getContactJid());

                // send request stanza
                DataForm dataForm = getNewMamForm();
                addWithStanzaId(stanzaId, dataForm);
                MamQueryIQ mamQueryIQ = new MamQueryIQ(queryID, null, dataForm);
                mamQueryIQ.setType(IQ.Type.set);
                if (chat instanceof GroupChat) {
                    mamQueryIQ.setTo(chat.getTo());
                } else mamQueryIQ.setTo((Jid) null);
                accountItem.getConnection().sendStanza(mamQueryIQ);
                return null;
            }
        });
    }

    /** Request messages after archivedID from chat history
    *  Else request messages after archivedID from all history */
    private @Nullable MamManager.MamQueryResult requestMessagesFromId(@NonNull AccountItem accountItem,
                                                                      @Nullable final AbstractChat chat,
                                                                      final String archivedId) {
        return requestToMessageArchive(accountItem, new MamRequest<MamManager.MamQueryResult>() {
            @Override
            MamManager.MamQueryResult execute(MamManager manager) throws Exception {
                if (chat != null) {
                    return manager.pageAfter(chat.getContactJid().getJid(), archivedId, 50);
                } else return manager.pageAfter(null, archivedId, 50);
            }
        });
    }

    /** Request messages before archivedID from chat history */
    private @Nullable MamManager.MamQueryResult requestMessagesBeforeId(@NonNull AccountItem accountItem,
                                                                        @NonNull final AbstractChat chat,
                                                                        final String archivedId) {
        return requestToMessageArchive(accountItem, new MamRequest<MamManager.MamQueryResult>() {
            @Override
            MamManager.MamQueryResult execute(MamManager manager) throws Exception {
                return manager.pageBefore(chat.getContactJid().getJid(), archivedId, 50);
            }
        });
    }

    /** Request messages started with startID and ending with endID from chat history */
    private @Nullable MamManager.MamQueryResult requestMissedMessages(@NonNull AccountItem accountItem,
                                                                      @NonNull final AbstractChat chat,
                                                                      final Date startDate, final Date endDate) {
        return requestToMessageArchive(accountItem, new MamRequest<MamManager.MamQueryResult>() {
            @Override
            MamManager.MamQueryResult execute(MamManager manager) throws Exception {
                return manager.queryArchive(50, startDate, endDate, chat.getContactJid().getJid(), null);
            }
        });
    }

    /** Request update archiving preferences on server */
    private void requestUpdatePreferences(@NonNull final AccountItem accountItem) {
        requestToMessageArchive(accountItem, new MamRequest<MamManager.MamPrefsResult>() {
            @Override
            MamManager.MamPrefsResult execute(MamManager manager) throws Exception {
                return manager.updateArchivingPreferences(null, null, accountItem.getMamDefaultBehaviour());
            }
        });
    }

    /** Request archiving preferences from server */
    private @Nullable MamManager.MamPrefsResult requestPreferencesFromServer(@NonNull final AccountItem accountItem) {
        return requestToMessageArchive(accountItem, new MamRequest<MamManager.MamPrefsResult>() {
            @Override
            MamManager.MamPrefsResult execute(MamManager manager) throws Exception {
                return manager.retrieveArchivingPreferences();
            }
        });
    }

    /** PARSING */

    private void parseAndSaveMessageFromMamResult(Realm realm, AccountJid account,Forwarded forwarded) {
        Stanza stanza = forwarded.getForwardedStanza();
        AccountItem accountItem = AccountManager.getInstance().getAccount(account);
        Jid user = stanza.getFrom().asBareJid();
        if (user.equals(account.getFullJid().asBareJid())) user = stanza.getTo().asBareJid();

        try {
            AbstractChat chat = ChatManager.getInstance().getChat(account, ContactJid.from(user));
            if (chat == null) chat = ChatManager.getInstance().createRegularChat(account, ContactJid.from(user));

            MessageRealmObject messageRealmObject = parseMessage(accountItem, account, chat.getContactJid(), forwarded,
                    null);
            if (messageRealmObject != null) {
                saveOrUpdateMessages(realm, Collections.singletonList(messageRealmObject), true);
                updateLastMessageId(chat, realm);
            }
        } catch (ContactJid.ContactJidCreateException e) {
            LogManager.d(LOG_TAG, e.toString());
        }
    }

    private List<MessageRealmObject> parseMessage(AccountItem accountItem, AccountJid account, ContactJid user,
                                                  List<Forwarded> forwardedMessages, String prevID) {
        List<MessageRealmObject> messageRealmObjects = new ArrayList<>();
        String lastOutgoingId = null;
        for (Forwarded forwarded : forwardedMessages) {
            MessageRealmObject message = parseMessage(accountItem, account, user, forwarded, prevID);
            if (message != null) {
                messageRealmObjects.add(message);
                prevID = message.getArchivedId();
                if (!message.isIncoming()) lastOutgoingId = message.getUniqueId();
            }
        }

        // mark messages before outgoing as read
        if (lastOutgoingId != null) {
            for (MessageRealmObject message : messageRealmObjects) {
                if (lastOutgoingId.equals(message.getUniqueId())) break;
                message.setRead(true);
            }
        }

        return messageRealmObjects;
    }

    @Nullable
    private MessageRealmObject parseMessage(AccountItem accountItem, AccountJid account, ContactJid user,
                                            Forwarded forwarded, String prevID) {

        if (!(forwarded.getForwardedStanza() instanceof Message)) return null;

        Message message = (Message) forwarded.getForwardedStanza();

        if (message.hasExtension(IncomingInviteExtensionElement.ELEMENT, IncomingInviteExtensionElement.NAMESPACE)){
            try{
                IncomingInviteExtensionElement inviteElement = message
                        .getExtension(IncomingInviteExtensionElement.ELEMENT, IncomingInviteExtensionElement.NAMESPACE);
                long timestamp = 0;
                if (message.hasExtension(TimeElement.ELEMENT, TimeElement.NAMESPACE)) {
                    TimeElement timeElement = (TimeElement) message.getExtension(TimeElement.ELEMENT, TimeElement.NAMESPACE);
                    timestamp = StringUtils.parseReceivedReceiptTimestampString(timeElement.getStamp()).getTime();
                }
                GroupInviteManager.INSTANCE.processIncomingInvite(inviteElement, accountItem.getAccount(),
                        ContactJid.from(message.getFrom()), timestamp);
            } catch (Exception e) { LogManager.exception(LOG_TAG, e); }
            return null;
        }

        DelayInformation delayInformation = forwarded.getDelayInformation();

        DelayInformation messageDelay = DelayInformation.from(message);

        String body = message.getBody();
        net.java.otr4j.io.messages.AbstractMessage otrMessage;
        try {
            otrMessage = SerializationUtils.toMessage(body);
        } catch (IOException e) {
            return null;
        }
        boolean encrypted = false;
        if (otrMessage != null) {
            if (otrMessage.messageType != net.java.otr4j.io.messages.AbstractMessage.MESSAGE_PLAINTEXT) {
                encrypted = true;
                try {
                    // this transforming just decrypt message if have keys. No action as injectMessage or something else
                    body = OTRManager.getInstance().transformReceivingIfSessionExist(account, user, body);
                    if (OTRManager.getInstance().isEncrypted(body)) return null;
                } catch (Exception e) {
                    return null;
                }
            }
            else body = ((PlainTextMessage) otrMessage).cleanText;
        }

        // forward comment (to support previous forwarded xep)
        String forwardComment = ForwardManager.parseForwardComment(message);
        if (forwardComment != null) body = forwardComment;

        // modify body with references
        Pair<String, String> bodies = ReferencesManager.modifyBodyWithReferences(message, body);
        body = bodies.first;
        String markupBody = bodies.second;

        boolean incoming = message.getFrom().asBareJid().equals(user.getJid().asBareJid());

        String uid = UUID.randomUUID().toString();
        MessageRealmObject messageRealmObject = new MessageRealmObject(uid);
        messageRealmObject.setPreviousId(prevID);

        long timestamp = delayInformation.getStamp().getTime();

        messageRealmObject.setAccount(account);
        messageRealmObject.setUser(user);
        messageRealmObject.setResource(user.getJid().getResourceOrNull());
        messageRealmObject.setText(body);
        if (markupBody != null) messageRealmObject.setMarkupText(markupBody);
        messageRealmObject.setTimestamp(timestamp);
        if (messageDelay != null) messageRealmObject.setDelayTimestamp(messageDelay.getStamp().getTime());

        messageRealmObject.setIncoming(incoming);
        messageRealmObject.setOriginId(UniqueIdsHelper.getOriginId(message));
        messageRealmObject.setPacketId(message.getStanzaId());
        messageRealmObject.setReceivedFromMessageArchive(true);
        messageRealmObject.setRead(timestamp <= accountItem.getStartHistoryTimestamp());
        messageRealmObject.setSent(true);
        messageRealmObject.setAcknowledged(true);
        messageRealmObject.setEncrypted(encrypted);

        // attachments
        // FileManager.processFileMessage(messageRealmObject);

        RealmList<AttachmentRealmObject> attachmentRealmObjects = HttpFileUploadManager.parseFileMessage(message);
        if (attachmentRealmObjects.size() > 0)
            messageRealmObject.setAttachmentRealmObjects(attachmentRealmObjects);

        // forwarded
        messageRealmObject.setOriginalStanza(message.toXML().toString());
        messageRealmObject.setOriginalFrom(message.getFrom().toString());

        // groupchat
        GroupMemberExtensionElement groupchatUser = ReferencesManager.getGroupchatUserFromReferences(message);
        if (groupchatUser != null) {
            GroupMemberManager.getInstance().saveGroupUser(groupchatUser, user.getBareJid(), timestamp);
            messageRealmObject.setGroupchatUserId(groupchatUser.getId());
            messageRealmObject.setStanzaId(UniqueIdsHelper.getStanzaIdBy(message, user.getBareJid().toString()));
            messageRealmObject.setArchivedId(UniqueIdsHelper.getArchivedIdBy(message, user.getBareJid().toString()));
        } else if (message.hasExtension(GroupchatExtensionElement.ELEMENT, GroupsManager.SYSTEM_MESSAGE_NAMESPACE)) {
            messageRealmObject.setGroupchatSystem(true);
            messageRealmObject.setStanzaId(UniqueIdsHelper.getStanzaIdBy(message, user.getBareJid().toString()));
            messageRealmObject.setArchivedId(UniqueIdsHelper.getArchivedIdBy(message, user.getBareJid().toString()));
        } else {
            messageRealmObject.setStanzaId(UniqueIdsHelper.getStanzaIdBy(message, account.getBareJid().toString()));
            messageRealmObject.setArchivedId(UniqueIdsHelper.getArchivedIdBy(message, account.getBareJid().toString()));
        }

        return messageRealmObject;
    }

    /** SAVING */

    private List<MessageRealmObject> saveOrUpdateMessages(Realm realm, final Collection<MessageRealmObject> messages) {
        return saveOrUpdateMessages(realm, messages, false);
    }

    private List<MessageRealmObject> saveOrUpdateMessages(Realm realm, final Collection<MessageRealmObject> messages,
                                                          boolean ui) {
        List<MessageRealmObject> messagesToSave = new ArrayList<>();
        realm.refresh();
        if (messages != null && !messages.isEmpty()) {
            for (MessageRealmObject message : messages) {
                MessageRealmObject newMessage = determineSaveOrUpdate(realm, message, ui);
                if (newMessage != null) messagesToSave.add(newMessage);
            }
        }
        realm.beginTransaction();
        realm.copyToRealmOrUpdate(messagesToSave);
        realm.commitTransaction();
        SyncManager.getInstance().onMessageSaved();
        for (OnNewMessageListener listener : Application.getInstance().getUIListeners(OnNewMessageListener.class)){
            listener.onNewMessage();
        }
        return messagesToSave;
    }

    private MessageRealmObject determineSaveOrUpdate(Realm realm, final MessageRealmObject message, boolean ui) {
        Message originalMessage = null;
        try {
            originalMessage = (Message) PacketParserUtils.parseStanza(message.getOriginalStanza());
        } catch (Exception e) {
            LogManager.exception(LOG_TAG, e);
            LogManager.e(LOG_TAG, message.getOriginalStanza());
        }

        AbstractChat chat = ChatManager.getInstance().getChat(message.getAccount(), message.getUser());
        if (chat == null) return null;

        MessageRealmObject localMessage = findSameLocalMessage(realm, chat, message);
        if (localMessage == null) {

            // forwarded
            if (originalMessage != null) {
                RealmList<ForwardIdRealmObject> forwardIdRealmObjects = chat.parseForwardedMessage(ui, originalMessage, message.getUniqueId());
                if (forwardIdRealmObjects != null && !forwardIdRealmObjects.isEmpty()) {
                    message.setForwardedIds(forwardIdRealmObjects);
                }
            }

            // notify about new message
            chat.enableNotificationsIfNeed();
            boolean notify = !message.isRead()
                    && (message.getText() != null && !message.getText().trim().isEmpty())
                    && message.isIncoming()
                    && chat.notifyAboutMessage();
            boolean visible = ChatManager.getInstance().isVisibleChat(chat);
            if (notify && !visible) NotificationManager.getInstance().onMessageNotification(message);
            //
            return message;
        } else {
            LogManager.d(LOG_TAG, "Matching message found! Updating message");
            realm.beginTransaction();
            localMessage.setArchivedId(message.getArchivedId());
            realm.commitTransaction();
            return localMessage;
        }
    }

    /** UTILS */

    private static DataForm getNewMamForm() {
        FormField field = new FormField(FormField.FORM_TYPE);
        field.setType(FormField.Type.hidden);
        field.addValue(MamElements.NAMESPACE);
        DataForm form = new DataForm(DataForm.Type.submit);
        form.addField(field);
        return form;
    }

    private static void addWithJid(Jid withJid, DataForm dataForm) {
        if (withJid == null) return;
        FormField formField = new FormField("with");
        formField.addValue(withJid.toString());
        dataForm.addField(formField);
    }

    private static void addWithStanzaId(String stanzaid, DataForm dataForm) {
        if (stanzaid == null) return;
        FormField formField = new FormField("{urn:xmpp:sid:0}stanza-id");
        formField.addValue(stanzaid);
        dataForm.addField(formField);
    }

    private String getNextArchivedId(MamManager.MamQueryResult queryResult) {
        if (queryResult.forwardedMessages != null && !queryResult.forwardedMessages.isEmpty()) {
            Stanza lastForwardedStanza =
                    queryResult.forwardedMessages.get(queryResult.forwardedMessages.size() - 1).getForwardedStanza();
            String to = queryResult.mamFin.getTo().asBareJid().toString();
            String from = lastForwardedStanza.getFrom().asBareJid().toString();
            if (lastForwardedStanza.hasExtension(GroupchatExtensionElement.ELEMENT, GroupchatExtensionElement.NAMESPACE)
                || lastForwardedStanza.hasExtension(GroupchatExtensionElement.ELEMENT, GroupsManager.SYSTEM_MESSAGE_NAMESPACE)){
                return UniqueIdsHelper.getArchivedIdBy(lastForwardedStanza, from);
            } else return UniqueIdsHelper.getArchivedIdBy(lastForwardedStanza, to);
        } else return null;
    }

    private Date getNextDate(MamManager.MamQueryResult queryResult) {
        Date date = null;
        if (queryResult.forwardedMessages != null && !queryResult.forwardedMessages.isEmpty()) {
            Forwarded forwarded = queryResult.forwardedMessages.get(queryResult.forwardedMessages.size() - 1);
            DelayInformation delayInformation = forwarded.getDelayInformation();
            date = new Date(delayInformation.getStamp().getTime() + 1);
        }
        return date;
    }

    @Nullable private List<MessageRealmObject> findMissedMessages(Realm realm, AbstractChat chat) {
        RealmResults<MessageRealmObject> results = realm.where(MessageRealmObject.class)
                .equalTo(MessageRealmObject.Fields.ACCOUNT, chat.getAccount().toString())
                .equalTo(MessageRealmObject.Fields.USER, chat.getContactJid().toString())
                .isNull(MessageRealmObject.Fields.PARENT_MESSAGE_ID)
                .isNotNull(MessageRealmObject.Fields.ARCHIVED_ID)
                .isNull(MessageRealmObject.Fields.PREVIOUS_ID)
                .findAll()
                .sort(MessageRealmObject.Fields.TIMESTAMP, Sort.DESCENDING);

        if (results != null && !results.isEmpty()) {
            return new ArrayList<>(results);
        } else return null;
    }

    private MessageRealmObject getMessageForCloseMissedMessages(Realm realm, MessageRealmObject messageRealmObject) {
        RealmResults<MessageRealmObject> results = realm.where(MessageRealmObject.class)
                .equalTo(MessageRealmObject.Fields.ACCOUNT, messageRealmObject.getAccount().toString())
                .equalTo(MessageRealmObject.Fields.USER, messageRealmObject.getUser().toString())
                .isNull(MessageRealmObject.Fields.PARENT_MESSAGE_ID)
                .isNotNull(MessageRealmObject.Fields.ARCHIVED_ID)
                .lessThan(MessageRealmObject.Fields.TIMESTAMP, messageRealmObject.getTimestamp())
                .findAll()
                .sort(MessageRealmObject.Fields.TIMESTAMP, Sort.DESCENDING);

        if (results != null && !results.isEmpty()) {
            return results.first();
        } else return null;
    }

    private boolean isNeedMigration(AccountItem account, Realm realm) {
        MessageRealmObject result = realm.where(MessageRealmObject.class)
                .equalTo(MessageRealmObject.Fields.ACCOUNT, account.getAccount().toString())
                .notEqualTo(MessageRealmObject.Fields.PREVIOUS_ID, "legacy")
                .findFirst();
        return result == null;
    }

    private boolean historyIsNotEnough(Realm realm, AbstractChat chat) {
        RealmResults<MessageRealmObject> results = realm.where(MessageRealmObject.class)
                .equalTo(MessageRealmObject.Fields.ACCOUNT, chat.getAccount().toString())
                .equalTo(MessageRealmObject.Fields.USER, chat.getContactJid().toString())
                .isNull(MessageRealmObject.Fields.PARENT_MESSAGE_ID)
                .findAll();
        return results.size() < 30;
    }

    private String getLastMessageArchivedId(AccountItem account, Realm realm) {
        RealmResults<MessageRealmObject> results = realm.where(MessageRealmObject.class)
                .equalTo(MessageRealmObject.Fields.ACCOUNT, account.getAccount().toString())
                .isNull(MessageRealmObject.Fields.PARENT_MESSAGE_ID)
                .isNotNull(MessageRealmObject.Fields.ARCHIVED_ID)
                .findAll()
                .sort(MessageRealmObject.Fields.TIMESTAMP, Sort.ASCENDING);

        if (results != null && !results.isEmpty()) {
            MessageRealmObject lastMessage = results.last();
            return lastMessage.getArchivedId();
        } else return null;
    }

    private MessageRealmObject getFirstMessage(AbstractChat chat, Realm realm) {
        RealmResults<MessageRealmObject> results = realm.where(MessageRealmObject.class)
                .equalTo(MessageRealmObject.Fields.ACCOUNT, chat.getAccount().toString())
                .equalTo(MessageRealmObject.Fields.USER, chat.getContactJid().toString())
                .isNull(MessageRealmObject.Fields.PARENT_MESSAGE_ID)
                .isNotNull(MessageRealmObject.Fields.ARCHIVED_ID)
                .findAll()
                .sort(MessageRealmObject.Fields.TIMESTAMP, Sort.ASCENDING);

        if (results != null && !results.isEmpty()) {
            return results.first();
        } else return null;
    }

    private MessageRealmObject getFirstMessageForMigration(AbstractChat chat, Realm realm) {
        RealmResults<MessageRealmObject> results = realm.where(MessageRealmObject.class)
                .equalTo(MessageRealmObject.Fields.ACCOUNT, chat.getAccount().toString())
                .equalTo(MessageRealmObject.Fields.USER, chat.getContactJid().toString())
                .isNull(MessageRealmObject.Fields.PARENT_MESSAGE_ID)
                .findAll()
                .sort(MessageRealmObject.Fields.TIMESTAMP, Sort.ASCENDING);

        if (results != null && !results.isEmpty()) {
            return results.first();
        } else return null;
    }

    private long getLastMessageTimestamp(AccountItem account, Realm realm) {
        RealmResults<MessageRealmObject> results = realm.where(MessageRealmObject.class)
                .equalTo(MessageRealmObject.Fields.ACCOUNT, account.getAccount().toString())
                .isNull(MessageRealmObject.Fields.PARENT_MESSAGE_ID)
                .findAll()
                .sort(MessageRealmObject.Fields.TIMESTAMP, Sort.ASCENDING);

        if (results != null && !results.isEmpty()) {
            MessageRealmObject lastMessage = results.last();
            return lastMessage.getTimestamp();
        } else return 0;
    }

    private void updateLastMessageId(AbstractChat chat, Realm realm) {
        RealmResults<MessageRealmObject> results = realm.where(MessageRealmObject.class)
                .equalTo(MessageRealmObject.Fields.ACCOUNT, chat.getAccount().toString())
                .equalTo(MessageRealmObject.Fields.USER, chat.getContactJid().toString())
                .isNull(MessageRealmObject.Fields.PARENT_MESSAGE_ID)
                .findAll()
                .sort(MessageRealmObject.Fields.TIMESTAMP, Sort.ASCENDING);

        if (results != null && !results.isEmpty()) {
            MessageRealmObject lastMessage = results.last();
            String id = lastMessage.getArchivedId();
            if (id == null) id = lastMessage.getStanzaId();
            chat.setLastMessageId(id);
        }
    }

    private MessageRealmObject findSameLocalMessage(Realm realm, AbstractChat chat, MessageRealmObject message) {
        return realm.where(MessageRealmObject.class)
                .equalTo(MessageRealmObject.Fields.ACCOUNT, chat.getAccount().toString())
                .equalTo(MessageRealmObject.Fields.USER, chat.getContactJid().toString())
                .equalTo(MessageRealmObject.Fields.TEXT, message.getText())
                .isNull(MessageRealmObject.Fields.PARENT_MESSAGE_ID)
                .beginGroup()
                    .equalTo(MessageRealmObject.Fields.ORIGIN_ID, message.getOriginId())
                    .or()
                    .equalTo(MessageRealmObject.Fields.ORIGIN_ID, message.getStanzaId())
                    .or()
                    .equalTo(MessageRealmObject.Fields.ORIGIN_ID, message.getPacketId())
                    .or()
                    .equalTo(MessageRealmObject.Fields.STANZA_ID, message.getOriginId())
                    .or()
                    .equalTo(MessageRealmObject.Fields.STANZA_ID, message.getStanzaId())
                    .or()
                    .equalTo(MessageRealmObject.Fields.STANZA_ID, message.getPacketId())
                    .or()
                    .equalTo(MessageRealmObject.Fields.STANZA_ID, message.getArchivedId())
                    .or()
                    .equalTo(MessageRealmObject.Fields.ARCHIVED_ID, message.getArchivedId())
                .endGroup()
                .findFirst();
    }

    private void runMigrationToNewArchive(AccountItem accountItem, Realm realm) {
        LogManager.d(LOG_TAG, "run migration for account: " + accountItem.getAccount().toString());
        Collection<RosterContact> contacts = RosterManager.getInstance()
                .getAccountRosterContacts(accountItem.getAccount());

        for (RosterContact contact : contacts) {
            AbstractChat chat = ChatManager.getInstance().getChat(contact.getAccount(), contact.getContactJid());

            MessageRealmObject firstMessage = getFirstMessageForMigration(chat, realm);
            SyncInfoRealmObject syncInfoRealmObject = realm.where(SyncInfoRealmObject.class)
                    .equalTo(SyncInfoRealmObject.Fields.FIELD_ACCOUNT, accountItem.getAccount().toString())
                    .equalTo(SyncInfoRealmObject.Fields.FIELD_USER, chat.getContactJid().toString()).findFirst();

            if (firstMessage != null && syncInfoRealmObject != null) {
                realm.beginTransaction();
                firstMessage.setArchivedId(syncInfoRealmObject.getFirstMamMessageMamId());
                firstMessage.setPreviousId(null);
                realm.commitTransaction();
            }
        }
    }
}
