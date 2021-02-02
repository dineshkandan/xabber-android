package com.xabber.android.data.database.realmobjects;

import java.util.UUID;

import io.realm.RealmObject;
import io.realm.annotations.PrimaryKey;
import io.realm.annotations.Required;

public class GroupMemberRealmObject extends RealmObject {

    @PrimaryKey
    @Required
    private String uniqueId;
    private String jid;
    private String groupJid;
    private String nickname;
    private String role;
    private String badge;
    private String avatarHash;
    private String avatarUrl;
    private String lastPresent;
    private boolean isMe;
    private boolean isCanRestrictMembers;
    private boolean isCanBlockMembers;
    private boolean isCanChangeBadge;
    private boolean isCanChangeNickname;
    private boolean isCanDeleteMessages;
    private boolean isRestrictedToSendMessages;
    private boolean isRestrictedToReadMessages;
    private boolean isRestrictedToSendInvitations;
    private boolean isRestrictedToSendAudio;
    private boolean isRestrictedToSendImages;

    public GroupMemberRealmObject(String uniqueId) {
        this.uniqueId = uniqueId;
    }
    public GroupMemberRealmObject() {
        this.uniqueId = UUID.randomUUID().toString();
    }

    public boolean isMe() { return isMe; }
    public void setMe(boolean me) { isMe = me; }

    public String getUniqueId() {
        return uniqueId;
    }

    public String getJid() {
        return jid;
    }
    public void setJid(String jid) {
        this.jid = jid;
    }

    public String getNickname() {
        return nickname;
    }
    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getRole() {
        return role;
    }
    public void setRole(String role) {
        this.role = role;
    }

    public String getBadge() {
        return badge;
    }
    public void setBadge(String badge) {
        this.badge = badge;
    }

    public String getGroupJid() {
        return groupJid;
    }
    public void setGroupJid(String groupJid) {
        this.groupJid = groupJid;
    }

    public String getAvatarHash() {
        return avatarHash;
    }
    public void setAvatarHash(String avatarHash) {
        this.avatarHash = avatarHash;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }
    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public String getLastPresent() {
        return lastPresent;
    }
    public void setLastPresent(String lastPresent) {
        this.lastPresent = lastPresent;
    }

    public boolean isCanRestrictMembers() {
        return isCanRestrictMembers;
    }
    public void setCanRestrictMembers(boolean canRestrictMembers) {
        isCanRestrictMembers = canRestrictMembers;
    }

    public boolean isCanBlockMembers() {
        return isCanBlockMembers;
    }
    public void setCanBlockMembers(boolean canBlockMembers) {
        isCanBlockMembers = canBlockMembers;
    }

    public boolean isCanChangeBadge() {
        return isCanChangeBadge;
    }
    public void setCanChangeBadge(boolean canChangeBadge) {
        isCanChangeBadge = canChangeBadge;
    }

    public boolean isCanChangeNickname() {
        return isCanChangeNickname;
    }
    public void setCanChangeNickname(boolean canChangeNickname) {
        isCanChangeNickname = canChangeNickname;
    }

    public boolean isCanDeleteMessages() {
        return isCanDeleteMessages;
    }
    public void setCanDeleteMessages(boolean canDeleteMessages) {
        isCanDeleteMessages = canDeleteMessages;
    }

    public boolean isRestrictedToSendMessages() {
        return isRestrictedToSendMessages;
    }
    public void setRestrictedToSendMessages(boolean restrictedToSendMessages) {
        isRestrictedToSendMessages = restrictedToSendMessages;
    }

    public boolean isRestrictedToReadMessages() {
        return isRestrictedToReadMessages;
    }
    public void setRestrictedToReadMessages(boolean restrictedToReadMessages) {
        isRestrictedToReadMessages = restrictedToReadMessages;
    }

    public boolean isRestrictedToSendInvitations() {
        return isRestrictedToSendInvitations;
    }
    public void setRestrictedToSendInvitations(boolean restrictedToSendInvitations) {
        isRestrictedToSendInvitations = restrictedToSendInvitations;
    }

    public boolean isRestrictedToSendAudio() {
        return isRestrictedToSendAudio;
    }
    public void setRestrictedToSendAudio(boolean restrictedToSendAudio) {
        isRestrictedToSendAudio = restrictedToSendAudio;
    }

    public boolean isRestrictedToSendImages() {
        return isRestrictedToSendImages;
    }
    public void setRestrictedToSendImages(boolean restrictedToSendImages) {
        isRestrictedToSendImages = restrictedToSendImages;
    }

    public static class Fields {
        public static final String UNIQUE_ID = "uniqueId";
        public static final String JID = "jid";
        public static final String GROUP_JID = "groupJid";
        public static final String NICKNAME = "nickname";
        public static final String ROLE = "role";
        public static final String BADGE = "badge";
        public static final String AVATAR_HASH = "avatarHash";
        public static final String AVATAR_URL = "avatarUrl";
        public static final String LAST_PRESENT = "lastPresent";
        public static final String TIMESTAMP = "timestamp";

        public static final String IS_ME = "isMe";

        public static final String IS_CAN_RESTRICT_MEMBERS = "isCanRestrictMembers";
        public static final String IS_CAN_BLOCK_MEMBERS = "isCanBlockMembers";
        public static final String IS_CAN_CHANGE_BADGE = "isCanChangeBadge";
        public static final String IS_CAN_CHANGE_NICKNAME = "isCanChangeNickname";
        public static final String IS_CAN_DELETE_MESSAGES = "isCanDeleteMessages";

        public static final String IS_RESTRICTED_TO_SEND_MESSAGES = "isRestrictedToSendMessages";
        public static final String IS_RESTRICTED_TO_READ_MESSAGES = "isRestrictedToReadMessages";
        public static final String IS_RESTRICTED_TO_SEND_INVITATIONS = "isRestrictedToSendInvitations";
        public static final String IS_RESTRICTED_TO_SEND_AUDIO = "isRestrictedToSendAudio";
        public static final String IS_RESTRICTED_TO_SEND_IMAGES = "isRestrictedToSendImages";
    }

}
