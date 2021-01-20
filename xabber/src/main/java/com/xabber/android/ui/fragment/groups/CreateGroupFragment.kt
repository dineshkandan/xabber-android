package com.xabber.android.ui.fragment.groups

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.NestedScrollView
import com.xabber.android.R
import com.xabber.android.data.Application
import com.xabber.android.data.SettingsManager
import com.xabber.android.data.account.AccountManager
import com.xabber.android.data.entity.AccountJid
import com.xabber.android.data.entity.ContactJid
import com.xabber.android.data.extension.avatar.AvatarManager
import com.xabber.android.data.extension.groupchat.create.CreateGroupchatIqResultListener
import com.xabber.android.data.message.chat.groupchat.GroupchatIndexType
import com.xabber.android.data.message.chat.groupchat.GroupchatManager
import com.xabber.android.data.message.chat.groupchat.GroupchatMembershipType
import com.xabber.android.data.message.chat.groupchat.GroupchatPrivacyType
import com.xabber.android.data.roster.RosterManager
import com.xabber.android.ui.activity.ChatActivity
import com.xabber.android.ui.activity.CreateGroupActivity
import com.xabber.android.ui.color.ColorManager
import com.xabber.android.ui.fragment.CircleEditorFragment
import com.xabber.android.ui.widget.AccountSpinner
import com.xabber.android.utils.StringUtils

class CreateGroupFragment private constructor(): CircleEditorFragment(), CreateGroupchatIqResultListener, AccountSpinner.Listener {

    private var listenerActivity: Listener? = null

    private var isIncognito: Boolean = false

    private var isAccountSelected = AccountManager.getInstance().enabledAccounts.size == 1

    private lateinit var accountSpinner: AccountSpinner
    private lateinit var exceptSpinnerLayout: NestedScrollView

    private lateinit var groupNameEt: EditText
    private lateinit var groupNameTv: TextView
    private lateinit var groupNameVw: View

    private lateinit var groupJidEt: EditText
    private lateinit var groupJidTv: TextView
    private lateinit var groupjidVw: View
    private lateinit var serversSp: Spinner

    private lateinit var groupDescriptionEt: EditText
    private lateinit var groupDescriptionTv: TextView
    private lateinit var groupDescriptionVw: View

    private lateinit var membershipTv: TextView
    private lateinit var membershipRg: RadioGroup

    private lateinit var indexTv: TextView
    private lateinit var indexRg: RadioGroup

    private lateinit var circlesLayout: LinearLayout

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is Listener){
            listenerActivity = context
        } else {
            throw IllegalArgumentException("Parent activity must implement CreateGroupFragment.Listener!")
        }
    }

    override fun onResume() {
        if (AccountManager.getInstance().enabledAccounts.size == 1){
            setAccount(AccountManager.getInstance().firstAccount)
            circlesLayout.visibility = View.VISIBLE
            setAccountCircles()
            updateCircles()
        } else {
            circlesLayout.visibility = View.GONE
        }
        super.onResume()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {

        val view = inflater.inflate(R.layout.create_groupchat_fragment, container, false)

        isIncognito = arguments?.getBoolean(ARGUMENT_IS_INCOGNITO) ?: false

        initializeViewsVars(view)

        colorizeEverything(AccountManager.getInstance().firstAccount)

        setupAccountSpinner()
        setupGroupNameEt()
        setupGroupJidEt()
        setupMembership()
        setupIndex()
        setupServerSp()

        return view
    }

    private fun initializeViewsVars(view: View){
        accountSpinner = view.findViewById(R.id.contact_account)
        exceptSpinnerLayout = view.findViewById(R.id.except_spinner)

        groupNameEt = view.findViewById(R.id.create_groupchat_name_et)
        groupNameTv = view.findViewById(R.id.groupchat_name_hint)
        groupNameVw = view.findViewById(R.id.groupchat_name_vw)

        groupJidEt = view.findViewById(R.id.groupchat_jid_et)
        groupJidTv = view.findViewById(R.id.groupchat_jid_hint)
        groupjidVw = view.findViewById(R.id.groupchat_jid_vw)

        serversSp = view.findViewById(R.id.create_groupchat_server_sp)

        membershipTv = view.findViewById(R.id.groupchat_membership_hint)
        membershipRg = view.findViewById(R.id.groupchat_membership_rg)

        indexTv = view.findViewById(R.id.groupchat_indexed_hint)
        indexRg = view.findViewById(R.id.groupchat_indexed_rg)

        groupDescriptionEt = view.findViewById(R.id.groupchat_description_et)
        groupDescriptionTv = view.findViewById(R.id.groupchat_description_hint)
        groupDescriptionVw = view.findViewById(R.id.groupchat_description_vw)

        circlesLayout = view.findViewById(R.id.circles_layout)
    }

    private fun setupAccountSpinner() {
        if (AccountManager.getInstance().enabledAccounts.size <= 1) {
            accountSpinner.visibility = View.GONE
            setupServerSp()


            (exceptSpinnerLayout.layoutParams as ViewGroup.MarginLayoutParams).topMargin = 0
            exceptSpinnerLayout.requestLayout()

        } else {
            val jids = AccountManager.getInstance().enabledAccounts.toList().sortedWith { o1, o2 -> o1.compareTo(o2) }

            val avatars = mutableListOf<Drawable>()
            for (jid in jids){
                avatars.add(AvatarManager.getInstance().getAccountAvatar(jid))
            }

            val nicknames = mutableListOf<String?>()
            for (jid in jids){
                val name = RosterManager.getInstance().getBestContact(jid, ContactJid.from(jid.fullJid.asBareJid())).name
                if (!name.isNullOrEmpty()){
                    nicknames.add(name)
                } else {
                    nicknames.add(null)
                }
            }

            accountSpinner.setup(getString(R.string.create_to), getString(R.string.choose_account), jids, avatars,
                    nicknames, this)
        }
    }

    private fun setupGroupNameEt(){
        groupNameEt.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                groupJidEt.setText(StringUtils.getLocalpartHintByString(s.toString()))
            }
        })

        if (isIncognito){
            groupNameEt.hint = getString(R.string.groupchat_incognito_group)
        } else groupNameEt.hint = getString(R.string.groupchat_public_group)

    }

    private fun setupGroupJidEt(){
        groupJidEt.hint = if (isIncognito) "incognito-group" else "public-group"
    }

    private fun setupMembership() {
        membershipRg.check(R.id.group_membership_members_only_rb)
    }

    private fun setupIndex() {
        indexRg.check(R.id.group_index_none_rb)
    }

    private fun colorizeEverything(accountJid: AccountJid){
        val color = ColorManager.getInstance().accountPainter.getAccountSendButtonColor(accountJid)
        val defaultLabelTextColor =
                if (SettingsManager.interfaceTheme() == SettingsManager.InterfaceTheme.dark)
                    ColorManager.getColorWithAlpha(Color.GRAY, 0.1f)
                else ColorManager.getColorWithAlpha(Color.GRAY, 0.9f)
        val defaultLineBackgroundColor =
                if (SettingsManager.interfaceTheme() == SettingsManager.InterfaceTheme.dark)
                    ColorManager.getColorWithAlpha(Color.GRAY, 0.1f)
                else ColorManager.getColorWithAlpha(Color.GRAY, 0.9f)

        fun colorizeEt(editText: EditText, textView: TextView, view: View){
            editText.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus){
                    textView.setTextColor(color)
                    view.setBackgroundColor(color)
                } else {
                    textView.setTextColor(defaultLabelTextColor)
                    view.setBackgroundColor(defaultLineBackgroundColor)
                }
            }
            if (editText.isFocused){
                textView.setTextColor(color)
                view.setBackgroundColor(color)
            }
        }

        fun colorizeSpinnerEt(editText: EditText, textView: TextView, view: View, spinner: Spinner){
            colorizeEt(editText, textView, view)
//            spinner.onFocusChangeListener {
//                textView.setTextColor(color)
//                view.setBackgroundColor(color)
//            }
            if (spinner.isActivated){
                textView.setTextColor(color)
                view.setBackgroundColor(color)
            }
        }

        fun colorizeRb(textView: TextView, radioGroup: RadioGroup){
            textView.setTextColor(color)
            for (iterator in 0 until radioGroup.childCount){
                if (radioGroup.getChildAt(iterator) is RadioButton && Build.VERSION.SDK_INT >= 21){
                    (radioGroup.getChildAt(iterator) as RadioButton).buttonTintList = ColorStateList(
                                arrayOf(intArrayOf(-android.R.attr.state_checked),
                                        intArrayOf(android.R.attr.state_checked)),
                                intArrayOf( Color.GRAY, color))
                }
            }
        }

        colorizeEt(groupNameEt, groupNameTv, groupNameVw)
        colorizeSpinnerEt(groupJidEt, groupJidTv, groupjidVw, serversSp)
        colorizeEt(groupDescriptionEt, groupDescriptionTv, groupDescriptionVw)
        colorizeRb(membershipTv, membershipRg)
        colorizeRb(indexTv, indexRg)
    }

    override fun onSelected(accountJid: AccountJid) {
        if (listenerActivity != null){
            listenerActivity?.onAccountSelected(accountJid)
        }

        colorizeEverything(accountJid)
        if (accountJid != getAccount()){
            setAccount(accountJid)
            setAccountCircles()
            updateCircles()
        }
        circlesLayout.visibility = View.VISIBLE

        isAccountSelected = true
//        if (!groupNameEt.text.isNullOrEmpty() && !groupJidEt.text.isNullOrEmpty()) //todo check to not null server
//            listenerActivity?. // todo this
    }

    private fun createListOfServers(customServer: String = ""): List<String> {
        val list = arrayListOf<String>()

        if (AccountManager.getInstance().enabledAccounts.size <= 1) {

            val accounts = arrayListOf<AccountJid>()
            accounts.addAll(AccountManager.getInstance().enabledAccounts)

            val serversList = GroupchatManager.getInstance()
                    .getAvailableGroupchatServersForAccountJid(accounts[0])

            if (serversList != null && serversList.isNotEmpty())
                for (jid in serversList)
                    list.add(jid.toString())

        } else if (accountSpinner.selected != null) {
            for (jid in GroupchatManager.getInstance().getAvailableGroupchatServersForAccountJid(accountSpinner.selected))
                list.add(jid.toString())
        }

        if (customServer.isNotEmpty()) list.add(customServer)

        list.add(getString(R.string.groupchat_custom_server))

        return list
    }

    private fun setupServerSp() {
        serversSp.adapter = ArrayAdapter(context!!, R.layout.group_server_spinner_item, createListOfServers()).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        serversSp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int,
                                        id: Long) {
                if (serversSp.adapter.count > 1 && position == serversSp.adapter.count - 1
                        || serversSp.adapter.count == 1 && position == 0) {
                    val dialog = AlertDialog.Builder(context!!).apply {
                        setTitle(getString(R.string.groupchat_server))
                        val editText = EditText(activity?.baseContext)
                        setView(editText)
                        setPositiveButton(getString(R.string.groupchat_add_custom_server)) { _, _ ->
                            serversSp.adapter = ArrayAdapter(context, R.layout.group_server_spinner_item,
                                    createListOfServers(editText.text.toString())).apply {
                                        setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                            }

                            serversSp.setSelection(serversSp.adapter.count - 2)
                        }
                        setNegativeButton(R.string.cancel) { _, _ -> serversSp.setSelection(0) }
                    }
                    dialog.create().show()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    fun createGroupchat() {
        val membershipType = when (membershipRg.checkedRadioButtonId){
           R.id.group_membership_members_only_rb -> GroupchatMembershipType.MEMBER_ONLY
           R.id.group_membership_open_rb -> GroupchatMembershipType.OPEN
           else -> GroupchatMembershipType.NONE
        }

        val indexType = when (indexRg.checkedRadioButtonId){
            R.id.group_index_global_rb -> GroupchatIndexType.GLOBAL
            R.id.group_index_local_rb -> GroupchatIndexType.LOCAL
            else -> GroupchatIndexType.NONE
        }

        val privacyType = if (isIncognito) GroupchatPrivacyType.INCOGNITO else GroupchatPrivacyType.PUBLIC

        GroupchatManager.getInstance().sendCreateGroupchatRequest(accountSpinner.selected,
                serversSp.selectedItem.toString(), groupNameEt.text.toString(), groupDescriptionEt.text.toString(),
                groupJidEt.text.toString(), membershipType, indexType, privacyType, this)

        //todo working with circles

    }

    override fun onSuccessfullyCreated(accountJid: AccountJid?, contactJid: ContactJid?) {
        Application.getInstance().runOnUiThread {
            if (activity is CreateGroupActivity)
                activity?.startActivity(ChatActivity.createSendIntent(context, accountJid, contactJid, null))
        }
    }

    override fun onJidConflict() {
        Application.getInstance().runOnUiThread {
            Toast.makeText(context,
                    getString(R.string.groupchat_failed_to_create_groupchat_jid_already_exists),
                    Toast.LENGTH_LONG).show()
            //todo show as group jid field underline
            listenerActivity?.showProgress(false)
            listenerActivity?.toolbarSetEnabled(false)
        }
    }

    override fun onOtherError() {
        Application.getInstance().runOnUiThread {
            Toast.makeText(context,
                    getString(R.string.groupchat_failed_to_create_groupchat_with_unknown_reason),
                    Toast.LENGTH_LONG).show()
            listenerActivity?.showProgress(false)
            listenerActivity?.toolbarSetEnabled(false)
        }
    }

    interface Listener {
        fun onAccountSelected(account: AccountJid?)
        fun showProgress(show: Boolean)
        fun toolbarSetEnabled(enabled: Boolean)
    }

    companion object {

        private val LOG_TAG = this::class.java.simpleName.toString()

        private const val ARGUMENT_IS_INCOGNITO = "com.xabber.android.ui.fragment.groups..CreateGroupFragment.ARGUMENT_IS_INCOGNITO"

        private const val SAVED_ACCOUNT = "com.xabber.android.ui.fragment.groups..CreateGroupFragment.SAVED_ACCOUNT"
        private const val SAVED_GROUP_NAME = "com.xabber.android.ui.fragment.groups..CreateGroupFragment.SAVED_GROUP_NAME"
        private const val SAVED_GROUP_JID = "com.xabber.android.ui.fragment.groups..CreateGroupFragment.SAVED_GROUP_JID"
        private const val SAVED_GROUP_SERVER = "com.xabber.android.ui.fragment.groups..CreateGroupFragment.SAVED_GROUP_SERVER"
        private const val SAVED_GROUP_DESCRIPTION = "com.xabber.android.ui.fragment.groups..CreateGroupFragment.SAVED_GROUP_DESCRIPTION"
        private const val SAVED_ERROR = "com.xabber.android.ui.fragment.groups..CreateGroupFragment.SAVED_ERROR"

        @JvmStatic
        fun newInstance(isIncognito: Boolean) = CreateGroupFragment().apply {
            arguments = Bundle().apply { putBoolean(ARGUMENT_IS_INCOGNITO, isIncognito) }
        }

    }

}