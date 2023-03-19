package com.v2ray.ang.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import androidx.lifecycle.lifecycleScope
import com.google.zxing.WriterException
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityLogcatBinding
import com.v2ray.ang.extension.toast
import com.v2ray.ang.util.AngConfigManager
import com.v2ray.ang.util.MmkvManager
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class UrlSchemeActivity : BaseActivity() {
    private lateinit var binding: ActivityLogcatBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogcatBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)

        var shareUrl: String = ""
        try {
            intent?.apply {
                when (action) {
                    Intent.ACTION_SEND -> {
                        if ("text/plain" == type) {
                            intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
                                shareUrl = it
                            }
                        }
                    }
                    Intent.ACTION_VIEW -> {
                        val uri: Uri? = intent.data
                        shareUrl = uri?.getQueryParameter("url")!!
                    }
                }
            }
            toast(shareUrl)
            val count = AngConfigManager.importBatchConfig(shareUrl, "", false)
            if (count > 0) {
                toast(R.string.toast_success)
                val intent = Intent("com.hiddify.UPDATE_UI_ACTION")
                sendBroadcast(intent);


            } else {
                toast(R.string.toast_failure)
            }
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        } catch (e: WriterException) {
            e.printStackTrace()
        }
    }



}