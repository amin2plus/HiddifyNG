package com.v2ray.ang.util

import com.google.gson.Gson
import com.tencent.mmkv.MMKV
import com.v2ray.ang.dto.ServerAffiliationInfo
import com.v2ray.ang.dto.ServerConfig
import com.v2ray.ang.dto.ServersCache
import com.v2ray.ang.dto.SubscriptionItem

object MmkvManager {
    const val ID_MAIN = "MAIN"
    const val ID_SERVER_CONFIG = "SERVER_CONFIG"
    const val ID_SERVER_RAW = "SERVER_RAW"
    const val ID_SERVER_AFF = "SERVER_AFF"
    const val ID_SUB = "SUB"
    const val ID_SETTING = "SETTING"
    const val KEY_SELECTED_SERVER = "SELECTED_SERVER"
    const val KEY_SELECTED_SUB = "SELECTED_SUB"
    const val KEY_ANG_CONFIGS = "ANG_CONFIGS"

    private val mainStorage by lazy { MMKV.mmkvWithID(ID_MAIN, MMKV.MULTI_PROCESS_MODE) }
    private val serverStorage by lazy { MMKV.mmkvWithID(ID_SERVER_CONFIG, MMKV.MULTI_PROCESS_MODE) }
    private val serverAffStorage by lazy { MMKV.mmkvWithID(ID_SERVER_AFF, MMKV.MULTI_PROCESS_MODE) }
    private val subStorage by lazy { MMKV.mmkvWithID(ID_SUB, MMKV.MULTI_PROCESS_MODE) }

    fun decodeServerList(): MutableList<String> {
        val json = mainStorage?.decodeString(KEY_ANG_CONFIGS)
        return if (json.isNullOrBlank()) {
            mutableListOf()
        } else {
            Gson().fromJson(json, Array<String>::class.java).toMutableList()
        }
    }

    fun decodeServerConfig(guid: String): ServerConfig? {
        if (guid.isBlank()) {
            return null
        }
        val json = serverStorage?.decodeString(guid)
        if (json.isNullOrBlank()) {
            return null
        }
        return Gson().fromJson(json, ServerConfig::class.java)
    }

    fun encodeServerConfig(guid: String, config: ServerConfig): String {
        val key = guid.ifBlank { Utils.getUuid() }
        serverStorage?.encode(key, Gson().toJson(config))
        val serverList = decodeServerList()
        if (!serverList.contains(key)) {
            serverList.add(0, key)
            mainStorage?.encode(KEY_ANG_CONFIGS, Gson().toJson(serverList))
            if (mainStorage?.decodeString(KEY_SELECTED_SERVER).isNullOrBlank()) {
                mainStorage?.encode(KEY_SELECTED_SERVER, key)
            }
        }
        return key
    }

    fun removeServer(guid: String) {
        if (guid.isBlank()) {
            return
        }
        if (mainStorage?.decodeString(KEY_SELECTED_SERVER) == guid) {
            mainStorage?.remove(KEY_SELECTED_SERVER)
        }
        val serverList = decodeServerList()
        serverList.remove(guid)
        mainStorage?.encode(KEY_ANG_CONFIGS, Gson().toJson(serverList))
        serverStorage?.remove(guid)
        serverAffStorage?.remove(guid)
    }

    fun removeServerViaSubid(subid: String) {
        if (subid.isBlank()) {
            return
        }
        serverStorage?.allKeys()?.forEach { key ->
            decodeServerConfig(key)?.let { config ->
                if (config.subscriptionId == subid) {
                    removeServer(key)
                }
            }
        }
    }

    fun decodeServerAffiliationInfo(guid: String): ServerAffiliationInfo? {
        if (guid.isBlank()) {
            return null
        }
        val json = serverAffStorage?.decodeString(guid)
        if (json.isNullOrBlank()) {
            return null
        }
        return Gson().fromJson(json, ServerAffiliationInfo::class.java)
    }

    fun encodeServerTestDelayMillis(guid: String, testResult: Long,subid: String) {
        if (guid.isBlank()) {
            return
        }
        val aff = decodeServerAffiliationInfo(guid) ?: ServerAffiliationInfo()
        aff.testDelayMillis = testResult
        aff.subid=subid
        serverAffStorage?.encode(guid, Gson().toJson(aff))
    }

    fun clearAllTestDelayResults() {
        val selectedSub=HiddifyUtils.getSelectedSubId()
        serverAffStorage?.allKeys()?.forEach { key ->
            decodeServerAffiliationInfo(key)?.let { aff ->
                if (aff.subid!=selectedSub)return@let
                aff.testDelayMillis = 0
                serverAffStorage?.encode(key, Gson().toJson(aff))
            }
        }
    }
    fun getDefaultSubscription(): SubscriptionItem {
        val subscriptions = decodeSubscriptions()
        subscriptions.forEach {
            if (it.first== "default") {
                return it.second
            }
        }
        val subItem = SubscriptionItem()
        subItem.remarks = "Default"
        subStorage?.encode("default", Gson().toJson(subItem))
        return subItem
    }
    fun importUrlAsSubscription(url: String): String {
        val subscriptions = decodeSubscriptions()
        subscriptions.forEach {
            if (it.second.url == url) {
                return it.first
            }
        }
        val subItem = SubscriptionItem()
        subItem.remarks = "import sub"
        subItem.url = url
        var uuid = Utils.getUuid()
        subStorage?.encode(uuid, Gson().toJson(subItem))
        return uuid
    }

    fun decodeSubscriptions(): List<Pair<String, SubscriptionItem>> {
        var subscriptions = mutableListOf<Pair<String, SubscriptionItem>>()
        subStorage?.allKeys()?.forEach { key ->
            val json = subStorage?.decodeString(key)
            if (!json.isNullOrBlank()) {
                subscriptions.add(Pair(key, Gson().fromJson(json, SubscriptionItem::class.java)))
            }
        }
        return subscriptions.sortedBy { (guid, value) -> if (guid=="default") Long.MIN_VALUE else -value.addedTime }

    }


    fun removeSubscription(subid: String) {

        if (subid==HiddifyUtils.getSelectedSubId()){
            HiddifyUtils.setSelectedSub("default")
        }
        if(subid!="default")
            subStorage?.remove(subid)
        removeServerViaSubid(subid)
    }

    fun removeAllServer() {
        mainStorage?.clearAll()
        serverStorage?.clearAll()
        serverAffStorage?.clearAll()
    }

    fun removeInvalidServer() {
        serverAffStorage?.allKeys()?.forEach { key ->
            decodeServerAffiliationInfo(key)?.let { aff ->
                if(aff.subid+"1"==key||aff.subid+"2"==key)
                    return@let
                if (aff.testDelayMillis <= 0L) {
                    removeServer(key)
                }
            }
        }
    }

    fun sortByTestResults( ) {
        data class ServerDelay(var guid: String, var testDelayMillis: Long)

        val serverDelays = mutableListOf<ServerDelay>()
        val serverList = decodeServerList()
        serverList.forEach { key ->
            var delay = decodeServerAffiliationInfo(key)?.testDelayMillis ?: 0L
            if (delay <= 0L) delay=999999
            if (key==HiddifyUtils.getSelectedSubId()+"1")
                delay=1
            if (key==HiddifyUtils.getSelectedSubId()+"2")
                delay=2
            serverDelays.add(ServerDelay(key, delay))
        }
        serverDelays.sortBy { it.testDelayMillis }

        serverDelays.forEach {
            serverList.remove(it.guid)
            serverList.add(it.guid)
        }

        mainStorage?.encode(KEY_ANG_CONFIGS, Gson().toJson(serverList))
    }


    fun getServerConfigs(subid: String?):List<Pair<String,ServerConfig>>{
        var res=ArrayList<Pair<String,ServerConfig>>()
        for (guid in MmkvManager.decodeServerList()) {
            val config = MmkvManager.decodeServerConfig(guid) ?: continue
            if (!subid.isNullOrEmpty() && subid != config.subscriptionId) {
                continue
            }
            res.add(Pair(guid,config))
        }
        return res
    }
    fun selectConfig(subid: String?,mode:Int){
        var res=ArrayList<Pair<String,ServerConfig>>()
        for (guid in MmkvManager.decodeServerList()) {
            val config = MmkvManager.decodeServerConfig(guid) ?: continue
            if (!subid.isNullOrEmpty() && subid != config.subscriptionId) {
                continue
            }
            if (guid==subid+mode){
                mainStorage?.encode(KEY_SELECTED_SERVER, guid)
                break
            }
            res.add(Pair(guid,config))
        }

    }

}
