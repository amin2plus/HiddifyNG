package com.v2ray.ang.viewmodel

import android.app.Application
import android.content.*
import android.util.Log
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.tencent.mmkv.MMKV
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.ANG_PACKAGE
import com.v2ray.ang.R
import com.v2ray.ang.databinding.DialogConfigFilterBinding
import com.v2ray.ang.dto.*
import com.v2ray.ang.extension.toast
import com.v2ray.ang.util.*
import com.v2ray.ang.util.MmkvManager.KEY_ANG_CONFIGS
import kotlinx.coroutines.*
import java.util.*

open class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val mainStorage by lazy { MMKV.mmkvWithID(MmkvManager.ID_MAIN, MMKV.MULTI_PROCESS_MODE) }
    private val serverRawStorage by lazy { MMKV.mmkvWithID(MmkvManager.ID_SERVER_RAW, MMKV.MULTI_PROCESS_MODE) }
    private var lastPing=0L;
    var subscriptions = MmkvManager.decodeSubscriptions()
    var serverList = MmkvManager.decodeServerList()
    var subscriptionId: String = HiddifyUtils.getSelectedSubId()
    var keywordFilter: String = ""
        public set
    val serversCache = mutableListOf<ServersCache>()
    val isRunning by lazy { MutableLiveData<Boolean>() }
    val updateListAction by lazy { MutableLiveData<Int>() }
    val updateTestResultAction by lazy { MutableLiveData<Pair<Long,String>>() }

    private val tcpingTestScope by lazy { CoroutineScope(Dispatchers.IO) }

    fun startListenBroadcast() {
        isRunning.value = false
        getApplication<AngApplication>().registerReceiver(mMsgReceiver, IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY))
        MessageUtil.sendMsg2Service(getApplication(), AppConfig.MSG_REGISTER_CLIENT, "")
    }

    override fun onCleared() {
        getApplication<AngApplication>().unregisterReceiver(mMsgReceiver)
        tcpingTestScope.coroutineContext[Job]?.cancelChildren()
        SpeedtestUtil.closeAllTcpSockets()
        Log.i(ANG_PACKAGE, "Main ViewModel is cleared")
        super.onCleared()
    }

    fun reloadServerList() {
        serverList = MmkvManager.decodeServerList()
        subscriptions = MmkvManager.decodeSubscriptions()

        updateCache()
        updateListAction.value = -1
//        MessageUtil.sendMsg2UI(getApplication(),AppConfig.MSG_HIDDIFY_DO_TEST_PING,"")

    }

    fun removeServer(guid: String) {
        serverList.remove(guid)
        MmkvManager.removeServer(guid)
        val index = getPosition(guid)
        if(index >= 0){
            serversCache.removeAt(index)
        }
    }

    fun appendCustomConfigServer(response: Utils.Response) {
        val server=response.content;
        val config = ServerConfig.create(EConfigType.CUSTOM)
        config.remarks = System.currentTimeMillis().toString()
        config.subscriptionId = subscriptionId
        config.fullConfig = Gson().fromJson(server, V2rayConfig::class.java)
        val key = MmkvManager.encodeServerConfig("", config)
        serverRawStorage?.encode(key, server)
        serverList.add(0, key)
        serversCache.add(0, ServersCache(key,config))
    }

    fun swapServer(fromPosition: Int, toPosition: Int) {
        Collections.swap(serverList, fromPosition, toPosition)
        Collections.swap(serversCache, fromPosition, toPosition)
        mainStorage?.encode(KEY_ANG_CONFIGS, Gson().toJson(serverList))
    }

    @Synchronized
    fun updateCache() {
        serversCache.clear()
        for (guid in serverList) {
            val config = MmkvManager.decodeServerConfig(guid) ?: continue
            if (subscriptionId.isNotEmpty() && subscriptionId != config.subscriptionId) {
                continue
            }

            if (keywordFilter.isEmpty() || config.remarks.contains(keywordFilter)) {
                if(config.configType==EConfigType.LoadBalance||config.configType==EConfigType.LowestPing)
                    serversCache.add(0,ServersCache(guid, config))
                else
                    serversCache.add(ServersCache(guid, config))
            }
        }

    }

    fun testAllTcping() {
        tcpingTestScope.coroutineContext[Job]?.cancelChildren()
        SpeedtestUtil.closeAllTcpSockets()
        MmkvManager.clearAllTestDelayResults()
        updateListAction.value = -1 // update all

        getApplication<AngApplication>().toast(R.string.connection_test_testing)
        for (item in serversCache) {
            item.config.getProxyOutbound()?.let { outbound ->
                val serverAddress = outbound.getServerAddress()
                val serverPort = outbound.getServerPort()
                if (serverAddress != null && serverPort != null) {
                    tcpingTestScope.launch {
                        val testResult = SpeedtestUtil.tcping(serverAddress, serverPort)
                        launch(Dispatchers.Main) {
                            MmkvManager.encodeServerTestDelayMillis(item.guid, testResult,subscriptionId)
                            updateListAction.value =  getPosition(item.guid)
                        }
                    }
                }
            }
        }
    }

    fun testAllRealPing() {
        lastPing=System.currentTimeMillis()
        MessageUtil.sendMsg2TestService(getApplication(), AppConfig.MSG_MEASURE_CONFIG_CANCEL, "")
        MmkvManager.clearAllTestDelayResults()
        updateListAction.value = -1 // update all

//        getApplication<AngApplication>().toast(R.string.connection_test_testing)
        viewModelScope.launch(Dispatchers.Default) { // without Dispatchers.Default viewModelScope will launch in main thread
            for (item in ArrayList(serversCache)) {
                if (!subscriptionId.isNullOrEmpty() && item.config.subscriptionId!=subscriptionId)
                    continue
                val config = V2rayConfigUtil.getV2rayConfig(getApplication(), item.guid)
                if (config.status) {
                    MessageUtil.sendMsg2TestService(getApplication(), AppConfig.MSG_MEASURE_CONFIG, Pair(item.guid, config.content))
                }
            }
        }
    }

    fun testCurrentServerRealPing() {
        MessageUtil.sendMsg2Service(getApplication(), AppConfig.MSG_MEASURE_DELAY, "")
    }


    fun filterConfig(context :Context) {
        val subscriptions = MmkvManager.decodeSubscriptions()
        val listId = subscriptions.map { it.first }.toList().toMutableList()
        val listRemarks = subscriptions.map { it.second.remarks }.toList().toMutableList()
        listRemarks += context.getString(R.string.filter_config_all)
        val checkedItem = if (subscriptionId.isNotEmpty()) {
            listId.indexOf(subscriptionId)
        } else {
            listRemarks.count() - 1
        }

        val ivBinding = DialogConfigFilterBinding.inflate(LayoutInflater.from(context))
        ivBinding.spSubscriptionId2.adapter = ArrayAdapter<String>( context, android.R.layout.simple_spinner_dropdown_item, listRemarks)
        ivBinding.spSubscriptionId2.setSelection(checkedItem)
        ivBinding.etKeyword.text = Utils.getEditable(keywordFilter)
        val builder = AlertDialog.Builder(context).setView(ivBinding.root)
        builder.setTitle(R.string.title_filter_config)
        builder.setPositiveButton(R.string.tasker_setting_confirm) { dialogInterface: DialogInterface?, _: Int ->
            try {
                val position = ivBinding.spSubscriptionId2.selectedItemPosition
                subscriptionId = if (listRemarks.count() - 1 == position) {
                    ""
                } else {
                    subscriptions[position].first
                }
                HiddifyUtils.setSelectedSub(subscriptionId)
                keywordFilter = ivBinding.etKeyword.text.toString()
                reloadServerList()

                dialogInterface?.dismiss()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        builder.show()
//        AlertDialog.Builder(context)
//            .setSingleChoiceItems(listRemarks.toTypedArray(), checkedItem) { dialog, i ->
//                try {
//                    subscriptionId = if (listRemarks.count() - 1 == i) {
//                        ""
//                    } else {
//                        subscriptions[i].first
//                    }
//                    reloadServerList()
//                    dialog.dismiss()
//                } catch (e: Exception) {
//                    e.printStackTrace()
//                }
//            }.show()
    }


    fun getPosition(guid: String) : Int {
        serversCache.forEachIndexed { index, it ->
            if (it.guid == guid)
                return index
        }
        return -1
    }

    fun removeDuplicateServer() {
        val deleteServer = mutableListOf<String>()
        serversCache.forEachIndexed { index, it ->
            val outbound = it.config.getProxyOutbound()
            serversCache.forEachIndexed { index2, it2 ->
                if(index2 > index){
                    val outbound2 = it2.config.getProxyOutbound()
                    if( outbound == outbound2 && !deleteServer.contains(it2.guid))
                    {
                        deleteServer.add(it2.guid)
                    }
                }
            }
        }
        for(it in deleteServer){
            MmkvManager.removeServer(it)
        }
        reloadServerList()
        getApplication<AngApplication>().toast(getApplication<AngApplication>().getString(R.string.title_del_duplicate_config_count, deleteServer.count()))
    }

    private val mMsgReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_RUNNING -> {
                    isRunning.value = true
                }
                AppConfig.MSG_STATE_NOT_RUNNING -> {
                    isRunning.value = false
                }
                AppConfig.MSG_STATE_START_SUCCESS -> {
//                    getApplication<AngApplication>().toast(R.string.toast_services_success)
                    isRunning.value = true
                }
                AppConfig.MSG_STATE_START_FAILURE -> {
                    getApplication<AngApplication>().toast(R.string.toast_services_failure)
                    isRunning.value = false
                }
                AppConfig.MSG_STATE_START_FAILURE_CONFIG_ERROR -> {
                    getApplication<AngApplication>().toast(R.string.toast_services_failure_config_error)
                    isRunning.value = false
                }
                AppConfig.MSG_STATE_STOP_SUCCESS -> {
                    isRunning.value = false
                }
                AppConfig.MSG_MEASURE_DELAY_SUCCESS -> {
                    updateTestResultAction.value = intent.getSerializableExtra("content") as Pair<Long,String>
                }
                AppConfig.MSG_MEASURE_CONFIG_SUCCESS -> {
                    val resultPair = intent.getSerializableExtra("content") as Pair<String, Long>
                    MmkvManager.encodeServerTestDelayMillis(resultPair.first, resultPair.second,subscriptionId)
                    updateListAction.value = getPosition(resultPair.first)
                }
                AppConfig.MSG_HIDDIFY_DO_TEST_PING->{
                    if (System.currentTimeMillis()-lastPing>10000)
                        testAllRealPing()
                }
            }
        }

    }



    //hiddify
    fun currentSubscription(): Pair<String, SubscriptionItem>? {
        var enableSubscription = HiddifyUtils.getSelectedSub()
        if (enableSubscription==null)
            enableSubscription=subscriptions.find { it.second.enabled }
        return enableSubscription;
    }




}
