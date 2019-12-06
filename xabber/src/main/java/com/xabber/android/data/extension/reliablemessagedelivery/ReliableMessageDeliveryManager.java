package com.xabber.android.data.extension.reliablemessagedelivery;

import com.xabber.android.data.account.AccountItem;
import com.xabber.android.data.connection.ConnectionItem;
import com.xabber.android.data.connection.listeners.OnPacketListener;
import com.xabber.android.data.database.MessageDatabaseManager;
import com.xabber.android.data.database.messagerealm.MessageItem;
import com.xabber.android.data.log.LogManager;
import com.xabber.android.data.message.MessageUpdateEvent;
import com.xabber.android.utils.StringUtils;
import com.xabber.xmpp.smack.XMPPTCPConnection;

import org.greenrobot.eventbus.EventBus;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.Stanza;
import org.jivesoftware.smack.provider.ProviderManager;
import org.jivesoftware.smackx.disco.ServiceDiscoveryManager;

import java.util.LinkedList;

import io.realm.Realm;

public class ReliableMessageDeliveryManager implements OnPacketListener {

    public static final String NAMESPACE = "http://xabber.com/protocol/delivery";
    public static final String LOG_TAG = ReliableMessageDeliveryManager.class.getSimpleName();

    private static ReliableMessageDeliveryManager instance;
    private LinkedList<String> originIdsWithoutReceipt = new LinkedList<String>();

    public static ReliableMessageDeliveryManager getInstance() {
        if (instance == null)
            instance = new ReliableMessageDeliveryManager();
        ProviderManager.addExtensionProvider(ReceiptElement.ELEMENT, ReceiptElement.NAMESPACE, new ReceiptElement.ReceiptElementProvider());
        return instance;
    }

    public boolean isSupported(XMPPTCPConnection xmpptcpConnection) {
        try {
            return ServiceDiscoveryManager.getInstanceFor(xmpptcpConnection).serverSupportsFeature(NAMESPACE);
        } catch (Exception e) {
            LogManager.exception(LOG_TAG, e);
            return false;
        }
    }

    public boolean isSupported(AccountItem accountItem) {
        return isSupported(accountItem.getConnection());
    }

    public void addMessageStanzaIdToReceiptWaitingList(String originId) {
        if (!originIdsWithoutReceipt.contains(originId)) {
            originIdsWithoutReceipt.add(originId);
            LogManager.d(LOG_TAG, "Added a origin-id to waiting receipt list: " + originId);
        }
    }

    private void deleteMessageFromWaitingReceiptsList(String id) throws NoSuchFieldException {
        if (originIdsWithoutReceipt.contains(id))
            originIdsWithoutReceipt.remove(id);
        else
            throw new NoSuchFieldException("Can't find message in waiting for receipt list with provided id: " + id);
    }

    private void markMessageReceivedInDatabase(final String time, final String originId, final String stanzaId) {
        Realm realm = MessageDatabaseManager.getInstance().getRealmUiThread();
        final Long millis = StringUtils.parseReceivedReceiptTimestampString(time).getTime();
        realm.executeTransaction(new Realm.Transaction() {
            @Override
            public void execute(Realm realm) {
                MessageItem messageItem = realm
                        .where(MessageItem.class)
                        .equalTo(MessageItem.Fields.STANZA_ID, originId)
                        .findFirst();
                LogManager.d(LOG_TAG, "Started apply changes to message: \nStanza ID: " + messageItem.getStanzaId() + "\nTimestamp: " + messageItem.getTimestamp());
                messageItem.setStanzaId(stanzaId);
                messageItem.setTimestamp(millis);
                LogManager.d(LOG_TAG, "Changes was applied. New message: \nStanza ID: " + messageItem.getStanzaId() + "\nTimestamp: " + messageItem.getTimestamp());
            }
        });
    }


    @Override
    public void onStanza(ConnectionItem connection, Stanza stanza) {
        if (stanza instanceof Message
                && ((Message) stanza).getType().equals(Message.Type.headline)
                && stanza.hasExtension(NAMESPACE)) {
            try {
                ReceiptElement receipt = (ReceiptElement) stanza.getExtension(NAMESPACE);
                String timestamp = receipt.getTimeElement().getStamp();
                String originId = receipt.getOriginIdElement().getId();
                String stanzaId = receipt.getOriginIdElement().getId();
                LogManager.d(LOG_TAG, "Receipt received with timestamp: " + timestamp + "; origin-id: " + originId + "; stanza-id: " + stanzaId + ". Trying to wite it to database");
                markMessageReceivedInDatabase(timestamp, originId, stanzaId);
                deleteMessageFromWaitingReceiptsList(originId);
                EventBus.getDefault().post(new MessageUpdateEvent());
            } catch (Exception e) {
                LogManager.exception(LOG_TAG, e);
            }
        }
    }

}
