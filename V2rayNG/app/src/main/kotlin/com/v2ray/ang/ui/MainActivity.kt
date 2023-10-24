package com.v2ray.ang.ui

import android.Manifest
import android.content.*
import android.content.res.ColorStateList
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.navigation.NavigationView
import com.tbruyelle.rxpermissions.RxPermissions
import com.tencent.mmkv.MMKV
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.ANG_PACKAGE
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityMainBinding
import com.v2ray.ang.dto.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.helper.SimpleItemTouchHelperCallback
import com.v2ray.ang.service.V2RayServiceManager
import com.v2ray.ang.util.*
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.*
import me.drakeet.support.toast.ToastCompat
import rx.Observable
import rx.android.schedulers.AndroidSchedulers
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class MainActivity : BaseActivity(), NavigationView.OnNavigationItemSelectedListener {
    private lateinit var binding: ActivityMainBinding

    private val adapter by lazy { MainRecyclerAdapter(this) }
    private val mainStorage by lazy { MMKV.mmkvWithID(MmkvManager.ID_MAIN, MMKV.MULTI_PROCESS_MODE) }
    private val settingsStorage by lazy { MMKV.mmkvWithID(MmkvManager.ID_SETTING, MMKV.MULTI_PROCESS_MODE) }
    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            startV2Ray()
        }
    }
    private var mItemTouchHelper: ItemTouchHelper? = null
    val mainViewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)
        title = getString(R.string.title_server)
        setSupportActionBar(binding.toolbar)

        binding.fab.setOnClickListener {
            if (mainViewModel.isRunning.value == true) {
                Utils.stopVService(this)
            } else if (settingsStorage?.decodeString(AppConfig.PREF_MODE) ?: "VPN" == "VPN") {
                val intent = VpnService.prepare(this)
                if (intent == null) {
                    startV2Ray()
                } else {
                    requestVpnPermission.launch(intent)
                }
            } else {
                startV2Ray()
            }
        }
        binding.layoutTest.setOnClickListener {
            if (mainViewModel.isRunning.value == true) {
                setTestState(getString(R.string.connection_test_testing))
                mainViewModel.testCurrentServerRealPing()
            } else {
//                tv_test_state.text = getString(R.string.connection_test_fail)
            }
        }

        binding.recyclerView.setHasFixedSize(true)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        val callback = SimpleItemTouchHelperCallback(adapter)
        mItemTouchHelper = ItemTouchHelper(callback)
        mItemTouchHelper?.attachToRecyclerView(binding.recyclerView)


        val toggle = ActionBarDrawerToggle(
            this, binding.drawerLayout, binding.toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close
        )
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
        binding.navView.setNavigationItemSelectedListener(this)
        binding.version.text = "v${BuildConfig.VERSION_NAME} (${SpeedtestUtil.getLibVersion()})"

        setupViewModel()
        copyAssets()
        migrateLegacy()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            RxPermissions(this).request(Manifest.permission.POST_NOTIFICATIONS).subscribe {
                if (!it) toast(R.string.toast_permission_denied)
            }
        }
    }

    private fun setupViewModel() {
        mainViewModel.updateListAction.observe(this) { index ->
            if (index >= 0) {
                adapter.notifyItemChanged(index)
            } else {
                adapter.notifyDataSetChanged()
            }
        }
        mainViewModel.updateTestResultAction.observe(this) { setTestState(it) }
        mainViewModel.isRunning.observe(this) { isRunning ->
            adapter.isRunning = isRunning
            if (isRunning) {
                if (!Utils.getDarkModeStatus(this)) {
                    binding.fab.setImageResource(R.drawable.ic_stat_name)
                }
                binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_orange))
                setTestState(getString(R.string.connection_connected))
                binding.layoutTest.isFocusable = true
            } else {
                if (!Utils.getDarkModeStatus(this)) {
                    binding.fab.setImageResource(R.drawable.ic_stat_name)
                }
                binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_grey))
                setTestState(getString(R.string.connection_not_connected))
                binding.layoutTest.isFocusable = false
            }
            hideCircle()
        }
        mainViewModel.startListenBroadcast()
    }

    private fun copyAssets() {
        val extFolder = Utils.userAssetPath(this)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val geo = arrayOf("geosite.dat", "geoip.dat")
                assets.list("")?.filter { geo.contains(it) }?.filter { !File(extFolder, it).exists() }?.forEach {
                    val target = File(extFolder, it)
                    assets.open(it).use { input ->
                        FileOutputStream(target).use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.i(ANG_PACKAGE, "Copied from apk assets folder to ${target.absolutePath}")
                }
            } catch (e: Exception) {
                Log.e(ANG_PACKAGE, "asset copy failed", e)
            }
        }
    }

    private fun migrateLegacy() {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = AngConfigManager.migrateLegacyConfig(this@MainActivity)
            if (result != null) {
                launch(Dispatchers.Main) {
                    if (result) {
                        toast(getString(R.string.migration_success))
                        mainViewModel.reloadServerList()
                    } else {
                        toast(getString(R.string.migration_fail))
                    }
                }
            }
        }
    }

    fun startV2Ray() {
        if (mainStorage?.decodeString(MmkvManager.KEY_SELECTED_SERVER).isNullOrEmpty()) {
            return
        }
        showCircle()
//        toast(R.string.toast_services_start)
        V2RayServiceManager.startV2Ray(this)
        hideCircle()
    }

    fun restartV2Ray() {
        if (mainViewModel.isRunning.value == true) {
            Utils.stopVService(this)
        }
        Observable.timer(500, TimeUnit.MILLISECONDS).observeOn(AndroidSchedulers.mainThread()).subscribe {
            startV2Ray()
        }
    }

    public override fun onResume() {
        super.onResume()
        mainViewModel.reloadServerList()
    }

    public override fun onPause() {
        super.onPause()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.import_qrcode -> {
            importQRcode(true)
            true
        }
        R.id.import_clipboard -> {
            importClipboard()
            true
        }
        R.id.import_manually_vmess -> {
            importManually(EConfigType.VMESS.value)
            true
        }
        R.id.import_manually_vless -> {
            importManually(EConfigType.VLESS.value)
            true
        }
        R.id.import_manually_ss -> {
            importManually(EConfigType.SHADOWSOCKS.value)
            true
        }
        R.id.import_manually_socks -> {
            importManually(EConfigType.SOCKS.value)
            true
        }
        R.id.import_manually_trojan -> {
            importManually(EConfigType.TROJAN.value)
            true
        }
        R.id.import_config_custom_clipboard -> {
            importConfigCustomClipboard()
            true
        }
        R.id.import_config_custom_local -> {
            importConfigCustomLocal()
            true
        }
        R.id.import_config_custom_url -> {
            importConfigCustomUrlClipboard()
            true
        }
        R.id.import_config_custom_url_scan -> {
            importQRcode(false)
            true
        }

//        R.id.sub_setting -> {
//            startActivity<SubSettingActivity>()
//            true
//        }

        R.id.sub_update -> {
            importConfigViaSub()
            true
        }

        R.id.sub_custom_update -> {
//            var date: Date = Date()
//            val rightNow = Calendar.getInstance()
//            rightNow.time = date
//            rightNow.add(Calendar.DAY_OF_WEEK, -1)
//            val dt = rightNow.time
            importConfigCustomViaSub(Date())
            true
        }
        R.id.sub_custom_set_update -> {
            importConfigCustomSetViaSub()
            true
        }

        R.id.sub_free_update1 -> {
            importConfigFree1ViaSub()
            true
        }
        R.id.sub_free_update2 -> {
            importConfigFree2ViaSub()
            true
        }
        R.id.sub_free_update3 -> {
            importConfigFree3ViaSub()
            true
        }
        R.id.sub_free_update4 -> {
            importConfigFree4ViaSub()
            true
        }
        R.id.sub_free_update5 -> {
            importConfigFree5ViaSub()
            true
        }
        R.id.sub_free_update6 -> {
            importConfigFree6ViaSub()
            true
        }
        R.id.sub_free_update7 -> {
            importConfigFree7ViaSub()
            true
        }
        R.id.sub_free_update8 -> {
            importConfigFree8ViaSub(Date())
            true
        }
        R.id.sub_free_update9 -> {
            importConfigFree9ViaSub()
            true
        }
        R.id.sub_free_update10 -> {
            importConfigFree10ViaSub()
            true
        }
        R.id.sub_free_update11 -> {
            importConfigFree11ViaSub()
            true
        }
        R.id.sub_free_update12 -> {
            importConfigFree12ViaSub()
            true
        }
        R.id.sub_free_update13 -> {
            importConfigFree13ViaSub()
            true
        }
        R.id.sub_free_update14 -> {
            importConfigFree14ViaSub()
            true
        }
        R.id.sub_free_update15 -> {
            importConfigFree15ViaSub()
            true
        }
        R.id.sub_free_update16 -> {
            importConfigFree16ViaSub(Date())
            true
        }
        R.id.sub_free_update17 -> {
            importConfigFree17ViaSub()
            true
        }
        R.id.sub_free_update18 -> {
            importConfigFree18ViaSub()
            true
        }
        R.id.sub_free_update19 -> {
            importConfigFree19ViaSub()
            true
        }
        R.id.sub_free_update20 -> {
            importConfigFree20ViaSub()
            true
        }
        R.id.sub_free_update21 -> {
            importConfigFree21ViaSub()
            true
        }
        R.id.export_all -> {
            if (AngConfigManager.shareNonCustomConfigsToClipboard(this, mainViewModel.serverList) == 0) {
                toast(R.string.toast_success)
            } else {
                toast(R.string.toast_failure)
            }
            true
        }

        R.id.ping_all -> {
            mainViewModel.testAllTcping()
            true
        }

        R.id.real_ping_all -> {
            mainViewModel.testAllRealPing()
            true
        }

        R.id.service_restart -> {
            restartV2Ray()
            true
        }

        R.id.del_all_config -> {
            AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm).setPositiveButton(android.R.string.ok) { _, _ ->
                MmkvManager.removeAllServer()
                mainViewModel.reloadServerList()
            }.show()
            true
        }
        R.id.del_duplicate_config -> {
            AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm).setPositiveButton(android.R.string.ok) { _, _ ->
                mainViewModel.removeDuplicateServer()
            }.show()
            true
        }
        R.id.del_invalid_config -> {
            AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm).setPositiveButton(android.R.string.ok) { _, _ ->
                MmkvManager.removeInvalidServer()
                mainViewModel.reloadServerList()
            }.show()
            true
        }
        R.id.sort_by_test_results -> {
            MmkvManager.sortByTestResults()
            mainViewModel.reloadServerList()
            true
        }
        R.id.filter_config -> {
            mainViewModel.filterConfig(this)
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    private fun importManually(createConfigType: Int) {
        startActivity(
            Intent().putExtra("createConfigType", createConfigType).putExtra("subscriptionId", mainViewModel.subscriptionId).setClass(this, ServerActivity::class.java)
        )
    }

    /**
     * import config from qrcode
     */
    fun importQRcode(forConfig: Boolean): Boolean {
//        try {
//            startActivityForResult(Intent("com.google.zxing.client.android.SCAN")
//                    .addCategory(Intent.CATEGORY_DEFAULT)
//                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP), requestCode)
//        } catch (e: Exception) {
        RxPermissions(this).request(Manifest.permission.CAMERA).subscribe {
            if (it) if (forConfig) scanQRCodeForConfig.launch(Intent(this, ScannerActivity::class.java))
            else scanQRCodeForUrlToCustomConfig.launch(Intent(this, ScannerActivity::class.java))
            else toast(R.string.toast_permission_denied)
        }
//        }
        return true
    }

    private val scanQRCodeForConfig = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            importBatchConfig(it.data?.getStringExtra("SCAN_RESULT"))
        }
    }

    private val scanQRCodeForUrlToCustomConfig = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            importConfigCustomUrl(it.data?.getStringExtra("SCAN_RESULT"))
        }
    }

    /**
     * import config from clipboard
     */
    fun importClipboard(): Boolean {
        try {
            val clipboard = Utils.getClipboard(this)
            importBatchConfig(clipboard)
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    fun importBatchConfig(server: String?, subid: String = "") {
        val subid2 = if (subid.isNullOrEmpty()) {
            mainViewModel.subscriptionId
        } else {
            subid
        }
        val append = subid.isNullOrEmpty()

        var count = AngConfigManager.importBatchConfig(server, subid2, append)
        if (count <= 0) {
            count = AngConfigManager.importBatchConfig(Utils.decode(server!!), subid2, append)
        }
        if (count > 0) {
            toast(R.string.toast_success)
            mainViewModel.reloadServerList()
        } else {
            toast(R.string.toast_failure)
        }
    }

    fun importConfigCustomClipboard(): Boolean {
        try {
            val configText = Utils.getClipboard(this)
            if (TextUtils.isEmpty(configText)) {
                toast(R.string.toast_none_data_clipboard)
                return false
            }
            importCustomizeConfig(configText)
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    /**
     * import config from local config file
     */
    fun importConfigCustomLocal(): Boolean {
        try {
            showFileChooser()
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    fun importConfigCustomUrlClipboard(): Boolean {
        try {
            val url = Utils.getClipboard(this)
            if (TextUtils.isEmpty(url)) {
                toast(R.string.toast_none_data_clipboard)
                return false
            }
            return importConfigCustomUrl(url)
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    /**
     * import config from url
     */
    fun importConfigCustomUrl(url: String?): Boolean {
        try {
            if (!Utils.isValidUrl(url)) {
                toast(R.string.toast_invalid_url)
                return false
            }
            lifecycleScope.launch(Dispatchers.IO) {
                val configText = try {
                    Utils.getUrlContentWithCustomUserAgent(url)
                } catch (e: Exception) {
                    e.printStackTrace()
                    ""
                }
                launch(Dispatchers.Main) {
                    importCustomizeConfig(configText)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    /**
     * import config from sub
     */
    fun importConfigViaSub(): Boolean {
        try {
            toast(R.string.title_sub_custom_update)
            MmkvManager.decodeSubscriptions().forEach {
                if (TextUtils.isEmpty(it.first) || TextUtils.isEmpty(it.second.remarks) || TextUtils.isEmpty(it.second.url)) {
                    return@forEach
                }
                if (!it.second.enabled) {
                    return@forEach
                }
                val url = Utils.idnToASCII(it.second.url)
                if (!Utils.isValidUrl(url)) {
                    return@forEach
                }
                Log.d(ANG_PACKAGE, url)
                lifecycleScope.launch(Dispatchers.IO) {
                    val configText = try {
                        Utils.getUrlContentWithCustomUserAgent(url)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        launch(Dispatchers.Main) {
                            toast("\"" + it.second.remarks + "\" " + getString(R.string.toast_failure))
                        }
                        return@launch
                    }
                    launch(Dispatchers.Main) {
                        importBatchConfig(configText, it.first)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    /**
     * import config from sub
     */
    fun importConfigCustomSetViaSub(): Boolean {
        try {
            toast(R.string.title_sub_custom_update)
            var data: Date = Date()
            val arrs = arrayOf(
                "https://freenode.me/wp-content/uploads/" + sdf1.format(data) + "/" + sdf2.format(data) + "/" + sdf3.format(data) + ".txt",
                "https://ghproxy.com/https://raw.githubusercontent.com/Pawdroid/Free-servers/main/sub",
                "https://bulinkbulink.com/freefq/free/master/v2",
                "https://ghproxy.com/https://raw.githubusercontent.com/aiboboxx/v2rayfree/main/v2",
                "https://ghproxy.com/https://raw.githubusercontent.com/umelabs/node.umelabs.dev/master/Subscribe/v2ray.md",
                "https://raw.gitmirror.com/ripaojiedian/freenode/main/sub",
                "https://gitlab.com/mianfeifq/share/-/raw/master/data2023109.txt",
                "https://ghproxy.com/https://raw.githubusercontent.com/mfuu/v2ray/master/clash.yaml",
                "https://nodefree.org/dy/" + sdf1.format(data) + "/" + sdf2.format(data) + "/" + sdf4.format(data) + ".txt",
                "https://ghproxy.com/https://raw.githubusercontent.com/ermaozi01/free_clash_vpn/main/subscribe/v2ray.txt",
                "https://ghproxy.com/https://raw.githubusercontent.com/a2470982985/getNode/main/v2ray.txt",
                "https://ghproxy.com/https://raw.githubusercontent.com/freev2/free/main/v2",
                "https://ghproxy.com/https://raw.githubusercontent.com/adiwzx/freenode/main/adifree.txt",
                "https://ghproxy.com/https://raw.githubusercontent.com/adiwzx/freenode/main/adispeed.txt",
                "https://ghproxy.com/https://raw.githubusercontent.com/vveg26/chromego_merge/main/sub/shadowrocket_base64.txt",
                "https://ghproxy.com/https://raw.githubusercontent.com/codingbox/Free-Node-Merge/main/node.txt",
                "https://ghproxy.com/https://raw.githubusercontent.com/vpn-free-nodes/blob/master/node-list/" + sdf1.format(data) + "-" + sdf2.format(data) + "/" + sdf5.format(data) + "日00时00分.md",
                "https://ghproxy.com/https://raw.githubusercontent.com/ZywChannel/free/main/sub",
                "https://ghproxy.com/https://raw.githubusercontent.com/Lewis-1217/FreeNodes/main/bpjzx1",
                "https://ghproxy.com/https://raw.githubusercontent.com/Lewis-1217/FreeNodes/main/bpjzx2",
                "https://ghproxy.com/https://raw.githubusercontent.com/ts-sf/fly/main/v2",
                "https://ghproxy.com/https://raw.githubusercontent.com/outnow/outnowmain/free"
            )
            arrs.forEach {
                lifecycleScope.launch(Dispatchers.IO) {
                    val configText = try {
                        Utils.getUrlContentWithCustomUserAgent(it)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        launch(Dispatchers.Main) {
                            toast("\"" + it + "\" " + getString(R.string.toast_failure))
                        }
                        return@launch
                    }
                    launch(Dispatchers.Main) {
                        importBatchConfig(configText, it)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    val sdf1 = SimpleDateFormat("yyyy")
    val sdf2 = SimpleDateFormat("MM")
    val sdf3 = SimpleDateFormat("MMdd")
    val sdf4 = SimpleDateFormat("yyyyMMdd")
    val sdf5 = SimpleDateFormat("dd")

    /**
     * import config from sub
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun importConfigCustomViaSub(data: Date): Boolean {
        try {
            toast(R.string.title_sub_custom_update)
//            MmkvManager.decodeSubscriptions().forEach {
//                if (TextUtils.isEmpty(it.first)
//                    || TextUtils.isEmpty(it.second.remarks)
//                    || TextUtils.isEmpty(it.second.url)
//                ) {
//                    return@forEach
//                }
//                if (!it.second.enabled) {
//                    return@forEach
//                }
//                val url = Utils.idnToASCII(it.second.url)
//                if (!Utils.isValidUrl(url)) {
//                    return@forEach
//                }
//                Log.d(ANG_PACKAGE, url)
//                lifecycleScope.launch(Dispatchers.IO) {
//                    val configText = try {
//                        Utils.getUrlContentWithCustomUserAgent(url)
//                    } catch (e: Exception) {
//                        e.printStackTrace()
//                        launch(Dispatchers.Main) {
//                            toast("\"" + it.second.remarks + "\" " + getString(R.string.toast_failure))
//                        }
//                        return@launch
//                    }
//                    launch(Dispatchers.Main) {
//                        importBatchConfig(configText, it.first)
//                    }
//                }
//        }
//        } catch (e: Exception) {
//            e.printStackTrace()
//            return false
//        }
            lifecycleScope.launch(Dispatchers.IO) {
                val configText = try {
                    //https://freenode.me/wp-content/uploads/2023/10/1014.txt
//                    var newDate: Date = Date()
                    //date.getUTCFullYear().toString(), (date.getUTCMonth() + 1).toString().padStart(2, "0"), date.getUTCDate().toString().padStart(2, "0")

                    launch(Dispatchers.Main) {
                        toast("\"" + ("https://freenode.me/wp-content/uploads/" + sdf1.format(data) + "/" + sdf2.format(data) + "/" + sdf3.format(data) + ".txt") + "\" " + getString(R.string.toast_failure))
                    }
                    Utils.getUrlContentWithCustomUserAgent("https://freenode.me/wp-content/uploads/" + sdf1.format(data) + "/" + sdf2.format(data) + "/" + sdf3.format(data) + ".txt");
                } catch (e: Exception) {
                    e.printStackTrace()
//                    var newDate: Date = Date()
//                    var day: Int = -1;
//                    while (true) {
//                        val rightNow = Calendar.getInstance()
//                        rightNow.time = newDate
//                        rightNow.add(Calendar.DAY_OF_WEEK, day)
//                        val dt = rightNow.time
//                        //date.getUTCFullYear().toString(), (date.getUTCMonth() + 1).toString().padStart(2, "0"), date.getUTCDate().toString().padStart(2, "0")
//                        launch(Dispatchers.Main) {
//                            toast("\"" + ("https://freenode.me/wp-content/uploads/" + sdf1.format(dt) + "/" + sdf2.format(dt) + "/" + sdf3.format(dt) + ".txt") + "\" " + getString(R.string.toast_failure))
//                        }
//                        day--;
//                        try {
//                            Utils.getUrlContentWithCustomUserAgent("https://freenode.me/wp-content/uploads/" + sdf1.format(dt) + "/" + sdf2.format(dt) + "/" + sdf3.format(dt) + ".txt");
//                            break;
//                        }catch (e:Exception) {}
//                }
                    return@launch
                }
                launch(Dispatchers.Main) {
                    importBatchConfig(configText, "")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    /**
     * import config from sub
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun importConfigFree1ViaSub(): Boolean {
        try {
            toast(R.string.title_sub_free1_update)
            lifecycleScope.launch(Dispatchers.IO) {
                val configText = try {
                    launch(Dispatchers.Main) {
                        toast("\"" + ("https://raw.githubusercontent.com/Pawdroid/Free-servers/main/sub") + "\" " + getString(R.string.toast_failure))
                    }
                    Utils.getUrlContentWithCustomUserAgent("https://ghproxy.com/https://raw.githubusercontent.com/Pawdroid/Free-servers/main/sub");
                } catch (e: Exception) {
                    e.printStackTrace()
                    return@launch
                }
                launch(Dispatchers.Main) {
                    importBatchConfig(configText, "")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    /**
     * import config from sub
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun importConfigFree2ViaSub(): Boolean {
        try {
            toast(R.string.title_sub_free2_update)
            lifecycleScope.launch(Dispatchers.IO) {
                val configText = try {
                    launch(Dispatchers.Main) {
                        toast("\"" + ("https://bulinkbulink.com/freefq/free/master/v2") + "\" " + getString(R.string.toast_failure))
                    }
                    Utils.getUrlContentWithCustomUserAgent("https://bulinkbulink.com/freefq/free/master/v2");
                } catch (e: Exception) {
                    e.printStackTrace()
                    return@launch
                }
                launch(Dispatchers.Main) {
                    importBatchConfig(configText, "")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    /**
     * import config from sub
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun importConfigFree3ViaSub(): Boolean {
        try {
            toast(R.string.title_sub_free3_update)
            lifecycleScope.launch(Dispatchers.IO) {
                val configText = try {
                    launch(Dispatchers.Main) {
                        toast("\"" + ("https://raw.githubusercontent.com/aiboboxx/v2rayfree/main/v2") + "\" " + getString(R.string.toast_failure))
                    }
                    Utils.getUrlContentWithCustomUserAgent("https://ghproxy.com/https://raw.githubusercontent.com/aiboboxx/v2rayfree/main/v2");
                } catch (e: Exception) {
                    e.printStackTrace()
                    return@launch
                }
                launch(Dispatchers.Main) {
                    importBatchConfig(configText, "")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    /**
     * import config from sub
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun importConfigFree4ViaSub(): Boolean {
        try {
            toast(R.string.title_sub_free4_update)
            lifecycleScope.launch(Dispatchers.IO) {
                val configText = try {
                    launch(Dispatchers.Main) {
                        toast("\"" + ("https://raw.githubusercontent.com/umelabs/node.umelabs.dev/master/Subscribe/v2ray.md") + "\" " + getString(R.string.toast_failure))
                    }
                    Utils.getUrlContentWithCustomUserAgent("https://ghproxy.com/https://raw.githubusercontent.com/umelabs/node.umelabs.dev/master/Subscribe/v2ray.md");
                } catch (e: Exception) {
                    e.printStackTrace()
                    return@launch
                }
                launch(Dispatchers.Main) {
                    importBatchConfig(configText, "")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    /**
     * import config from sub
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun importConfigFree5ViaSub(): Boolean {
        try {
            toast(R.string.title_sub_free5_update)
            lifecycleScope.launch(Dispatchers.IO) {
                val configText = try {
                    launch(Dispatchers.Main) {
                        toast("\"" + ("https://raw.gitmirror.com/ripaojiedian/freenode/main/sub") + "\" " + getString(R.string.toast_failure))
                    }
                    Utils.getUrlContentWithCustomUserAgent("https://raw.gitmirror.com/ripaojiedian/freenode/main/sub");
                } catch (e: Exception) {
                    e.printStackTrace()
                    return@launch
                }
                launch(Dispatchers.Main) {
                    importBatchConfig(configText, "")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    /**
     * import config from sub
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun importConfigFree6ViaSub(): Boolean {
        try {
            toast(R.string.title_sub_free6_update)
            lifecycleScope.launch(Dispatchers.IO) {
                val configText = try {
                    launch(Dispatchers.Main) {
                        toast("\"" + ("https://gitlab.com/mianfeifq/share/-/raw/master/data2023109.txt") + "\" " + getString(R.string.toast_failure))
                    }
                    Utils.getUrlContentWithCustomUserAgent("https://gitlab.com/mianfeifq/share/-/raw/master/data2023109.txt");
                } catch (e: Exception) {
                    e.printStackTrace()
                    return@launch
                }
                launch(Dispatchers.Main) {
                    importBatchConfig(configText, "")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    /**
     * import config from sub
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun importConfigFree7ViaSub(): Boolean {
        try {
            toast(R.string.title_sub_free7_update)
            lifecycleScope.launch(Dispatchers.IO) {
                val configText = try {
                    launch(Dispatchers.Main) {
                        toast("\"" + ("https://raw.githubusercontent.com/mfuu/v2ray/master/clash.yaml") + "\" " + getString(R.string.toast_failure))
                    }
                    Utils.getUrlContentWithCustomUserAgent("https://ghproxy.com/https://raw.githubusercontent.com/mfuu/v2ray/master/clash.yaml");
                } catch (e: Exception) {
                    e.printStackTrace()
                    return@launch
                }
                launch(Dispatchers.Main) {
                    importBatchConfig(configText, "")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    /**
     * import config from sub
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun importConfigFree8ViaSub(data: Date): Boolean {
        try {
            toast(R.string.title_sub_free8_update)
            lifecycleScope.launch(Dispatchers.IO) {

                //https://nodefree.org/dy/2023/10/20231024.txt
                // launch(Dispatchers.Main) {
                //                        toast("\"" + ("https://freenode.me/wp-content/uploads/" + sdf1.format(data) + "/" + sdf2.format(data) + "/" + sdf3.format(data) + ".txt") + "\" " + getString(R.string.toast_failure))
                //                    }
                //                    Utils.getUrlContentWithCustomUserAgent("https://freenode.me/wp-content/uploads/" + sdf1.format(data) + "/" + sdf2.format(data) + "/" + sdf3.format(data) + ".txt");
                val configText = try {
                    launch(Dispatchers.Main) {
                        toast("\"" + ("https://nodefree.org/dy/" + sdf1.format(data) + "/" + sdf2.format(data) + "/" + sdf4.format(data) + ".txt") + "\" " + getString(R.string.toast_failure))
                    }
                    Utils.getUrlContentWithCustomUserAgent("https://nodefree.org/dy/" + sdf1.format(data) + "/" + sdf2.format(data) + "/" + sdf4.format(data) + ".txt");
                } catch (e: Exception) {
                    e.printStackTrace()
                    return@launch
                }
                launch(Dispatchers.Main) {
                    importBatchConfig(configText, "")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    /**
     * import config from sub
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun importConfigFree9ViaSub(): Boolean {
        try {
            toast(R.string.title_sub_free9_update)
            lifecycleScope.launch(Dispatchers.IO) {
                val configText = try {
                    launch(Dispatchers.Main) {
                        toast("\"" + ("https://raw.githubusercontent.com/ermaozi01/free_clash_vpn/main/subscribe/v2ray.txt") + "\" " + getString(R.string.toast_failure))
                    }
                    Utils.getUrlContentWithCustomUserAgent("https://ghproxy.com/https://raw.githubusercontent.com/ermaozi01/free_clash_vpn/main/subscribe/v2ray.txt");
                } catch (e: Exception) {
                    e.printStackTrace()
                    return@launch
                }
                launch(Dispatchers.Main) {
                    importBatchConfig(configText, "")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    /**
     * import config from sub
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun importConfigFree10ViaSub(): Boolean {
        try {
            toast(R.string.title_sub_free10_update)
            lifecycleScope.launch(Dispatchers.IO) {
                val configText = try {
                    launch(Dispatchers.Main) {
                        toast("\"" + ("https://raw.githubusercontent.com/a2470982985/getNode/main/v2ray.txt") + "\" " + getString(R.string.toast_failure))
                    }
                    Utils.getUrlContentWithCustomUserAgent("https://ghproxy.com/https://raw.githubusercontent.com/a2470982985/getNode/main/v2ray.txt");
                } catch (e: Exception) {
                    e.printStackTrace()
                    return@launch
                }
                launch(Dispatchers.Main) {
                    importBatchConfig(configText, "")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    /**
     * import config from sub
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun importConfigFree11ViaSub(): Boolean {
        try {
            toast(R.string.title_sub_free11_update)
            lifecycleScope.launch(Dispatchers.IO) {
                val configText = try {
                    launch(Dispatchers.Main) {
                        toast("\"" + ("https://raw.githubusercontent.com/freev2/free/main/v2") + "\" " + getString(R.string.toast_failure))
                    }
                    Utils.getUrlContentWithCustomUserAgent("https://ghproxy.com/https://raw.githubusercontent.com/freev2/free/main/v2");
                } catch (e: Exception) {
                    e.printStackTrace()
                    return@launch
                }
                launch(Dispatchers.Main) {
                    importBatchConfig(configText, "")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    /**
     * import config from sub
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun importConfigFree12ViaSub(): Boolean {
        try {
            toast(R.string.title_sub_free12_update)
            lifecycleScope.launch(Dispatchers.IO) {
                val configText = try {
                    launch(Dispatchers.Main) {
                        toast("\"" + ("https://raw.githubusercontent.com/adiwzx/freenode/main/adifree.txt") + "\" " + getString(R.string.toast_failure))
                    }
                    Utils.getUrlContentWithCustomUserAgent("https://ghproxy.com/https://raw.githubusercontent.com/adiwzx/freenode/main/adifree.txt");
                } catch (e: Exception) {
                    e.printStackTrace()
                    return@launch
                }
                launch(Dispatchers.Main) {
                    importBatchConfig(configText, "")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    /**
     * import config from sub
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun importConfigFree13ViaSub(): Boolean {
        try {
            toast(R.string.title_sub_free13_update)
            lifecycleScope.launch(Dispatchers.IO) {
                val configText = try {
                    launch(Dispatchers.Main) {
                        toast("\"" + ("https://raw.githubusercontent.com/adiwzx/freenode/main/adispeed.txt") + "\" " + getString(R.string.toast_failure))
                    }
                    Utils.getUrlContentWithCustomUserAgent("https://ghproxy.com/https://raw.githubusercontent.com/adiwzx/freenode/main/adispeed.txt");
                } catch (e: Exception) {
                    e.printStackTrace()
                    return@launch
                }
                launch(Dispatchers.Main) {
                    importBatchConfig(configText, "")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    /**
     * import config from sub
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun importConfigFree14ViaSub(): Boolean {
        try {
            toast(R.string.title_sub_free14_update)
            lifecycleScope.launch(Dispatchers.IO) {
                val configText = try {
                    launch(Dispatchers.Main) {
                        toast("\"" + ("https://raw.githubusercontent.com/vveg26/chromego_merge/main/sub/shadowrocket_base64.txt") + "\" " + getString(R.string.toast_failure))
                    }
                    Utils.getUrlContentWithCustomUserAgent("https://ghproxy.com/https://raw.githubusercontent.com/vveg26/chromego_merge/main/sub/shadowrocket_base64.txt");
                } catch (e: Exception) {
                    e.printStackTrace()
                    return@launch
                }
                launch(Dispatchers.Main) {
                    importBatchConfig(configText, "")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    /**
     * import config from sub
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun importConfigFree15ViaSub(): Boolean {
        try {
            toast(R.string.title_sub_free15_update)
            lifecycleScope.launch(Dispatchers.IO) {
                val configText = try {
                    launch(Dispatchers.Main) {
                        toast("\"" + ("https://raw.githubusercontent.com/codingbox/Free-Node-Merge/main/node.txt") + "\" " + getString(R.string.toast_failure))
                    }
                    Utils.getUrlContentWithCustomUserAgent("https://ghproxy.com/https://raw.githubusercontent.com/codingbox/Free-Node-Merge/main/node.txt");
                } catch (e: Exception) {
                    e.printStackTrace()
                    return@launch
                }
                launch(Dispatchers.Main) {
                    importBatchConfig(configText, "")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    /**
     * import config from sub
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun importConfigFree16ViaSub(data: Date): Boolean {
        try {
            toast(R.string.title_sub_free16_update)
            lifecycleScope.launch(Dispatchers.IO) {
                val configText = try {
                    launch(Dispatchers.Main) {
                        toast(
                            "\"" + ("https://raw.githubusercontent.com/vpn-free-nodes/blob/master/node-list/" + sdf1.format(data) + "-" + sdf2.format(data) + "/" + sdf5.format(data) + "日00时00分.md") + "\" " + getString(
                                R.string.toast_failure
                            )
                        )
                    }
                    Utils.getUrlContentWithCustomUserAgent(
                        "https://ghproxy.com/https://raw.githubusercontent.com/vpn-free-nodes/blob/master/node-list/" + sdf1.format(data) + "-" + sdf2.format(data) + "/" + sdf5.format(
                            data
                        ) + "日00时00分.md"
                    );
                } catch (e: Exception) {
                    e.printStackTrace()
                    return@launch
                }
                launch(Dispatchers.Main) {
                    importBatchConfig(configText, "")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    /**
     * import config from sub
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun importConfigFree17ViaSub(): Boolean {
        try {
            toast(R.string.title_sub_free17_update)
            lifecycleScope.launch(Dispatchers.IO) {
                val configText = try {
                    launch(Dispatchers.Main) {
                        toast("\"" + ("https://raw.githubusercontent.com/ZywChannel/free/main/sub") + "\" " + getString(R.string.toast_failure))
                    }
                    Utils.getUrlContentWithCustomUserAgent("https://ghproxy.com/https://raw.githubusercontent.com/ZywChannel/free/main/sub");
                } catch (e: Exception) {
                    e.printStackTrace()
                    return@launch
                }
                launch(Dispatchers.Main) {
                    importBatchConfig(configText, "")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    /**
     * import config from sub
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun importConfigFree18ViaSub(): Boolean {
        try {
            toast(R.string.title_sub_free18_update)
            lifecycleScope.launch(Dispatchers.IO) {
                val configText = try {
                    launch(Dispatchers.Main) {
                        toast("\"" + ("https://raw.githubusercontent.com/Lewis-1217/FreeNodes/main/bpjzx1") + "\" " + getString(R.string.toast_failure))
                    }
                    Utils.getUrlContentWithCustomUserAgent("https://ghproxy.com/https://raw.githubusercontent.com/Lewis-1217/FreeNodes/main/bpjzx1");
                } catch (e: Exception) {
                    e.printStackTrace()
                    return@launch
                }
                launch(Dispatchers.Main) {
                    importBatchConfig(configText, "")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    /**
     * import config from sub
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun importConfigFree19ViaSub(): Boolean {
        try {
            toast(R.string.title_sub_free19_update)
            lifecycleScope.launch(Dispatchers.IO) {
                val configText = try {
                    launch(Dispatchers.Main) {
                        toast("\"" + ("https://raw.githubusercontent.com/Lewis-1217/FreeNodes/main/bpjzx2") + "\" " + getString(R.string.toast_failure))
                    }
                    Utils.getUrlContentWithCustomUserAgent("https://ghproxy.com/https://raw.githubusercontent.com/Lewis-1217/FreeNodes/main/bpjzx2");
                } catch (e: Exception) {
                    e.printStackTrace()
                    return@launch
                }
                launch(Dispatchers.Main) {
                    importBatchConfig(configText, "")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    /**
     * import config from sub
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun importConfigFree20ViaSub(): Boolean {
        try {
            toast(R.string.title_sub_free20_update)
            lifecycleScope.launch(Dispatchers.IO) {
                val configText = try {
                    launch(Dispatchers.Main) {
                        toast("\"" + ("https://raw.githubusercontent.com/ts-sf/fly/main/v2") + "\" " + getString(R.string.toast_failure))
                    }
                    Utils.getUrlContentWithCustomUserAgent("https://ghproxy.com/https://raw.githubusercontent.com/ts-sf/fly/main/v2");
                } catch (e: Exception) {
                    e.printStackTrace()
                    return@launch
                }
                launch(Dispatchers.Main) {
                    importBatchConfig(configText, "")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    /**
     * import config from sub
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun importConfigFree21ViaSub(): Boolean {
        try {
            toast(R.string.title_sub_free21_update)
            lifecycleScope.launch(Dispatchers.IO) {
                val configText = try {
                    launch(Dispatchers.Main) {
//                        toast("\"" + ("https://sub.gitpub.top/freedoor") + "\" " + getString(R.string.toast_failure))
                        toast("\"" + ("https://raw.githubusercontent.com/outnow/outnowmain/free") + "\" " + getString(R.string.toast_failure))
                    }
//                    Utils.getUrlContentWithCustomUserAgent("https://sub.gitpub.top/freedoor");
                    Utils.getUrlContentWithCustomUserAgent("https://ghproxy.com/https://raw.githubusercontent.com/outnow/outnowmain/free");
                } catch (e: Exception) {
                    e.printStackTrace()
                    return@launch
                }
                launch(Dispatchers.Main) {
                    importBatchConfig(configText, "")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    /**
     * show file chooser
     */
    private fun showFileChooser() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "*/*"
        intent.addCategory(Intent.CATEGORY_OPENABLE)

        try {
            chooseFileForCustomConfig.launch(Intent.createChooser(intent, getString(R.string.title_file_chooser)))
        } catch (ex: ActivityNotFoundException) {
            toast(R.string.toast_require_file_manager)
        }
    }

    private val chooseFileForCustomConfig = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val uri = it.data?.data
        if (it.resultCode == RESULT_OK && uri != null) {
            readContentFromUri(uri)
        }
    }

    /**
     * read content from uri
     */
    private fun readContentFromUri(uri: Uri) {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        RxPermissions(this).request(permission).subscribe {
            if (it) {
                try {
                    contentResolver.openInputStream(uri).use { input ->
                        importCustomizeConfig(input?.bufferedReader()?.readText())
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else toast(R.string.toast_permission_denied)
        }
    }

    /**
     * import customize config
     */
    fun importCustomizeConfig(server: String?) {
        try {
            if (server == null || TextUtils.isEmpty(server)) {
                toast(R.string.toast_none_data)
                return
            }
            mainViewModel.appendCustomConfigServer(server)
            mainViewModel.reloadServerList()
            toast(R.string.toast_success)
            //adapter.notifyItemInserted(mainViewModel.serverList.lastIndex)
        } catch (e: Exception) {
            ToastCompat.makeText(this, "${getString(R.string.toast_malformed_josn)} ${e.cause?.message}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
            return
        }
    }

    fun setTestState(content: String?) {
        binding.tvTestState.text = content
    }

//    val mConnection = object : ServiceConnection {
//        override fun onServiceDisconnected(name: ComponentName?) {
//        }
//
//        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
//            sendMsg(AppConfig.MSG_REGISTER_CLIENT, "")
//        }
//    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }


    fun showCircle() {
        binding.fabProgressCircle.show()
    }

    fun hideCircle() {
        try {
            Observable.timer(300, TimeUnit.MILLISECONDS).observeOn(AndroidSchedulers.mainThread()).subscribe {
                try {
                    if (binding.fabProgressCircle.isShown) {
                        binding.fabProgressCircle.hide()
                    }
                } catch (e: Exception) {
                    Log.w(ANG_PACKAGE, e)
                }
            }
        } catch (e: Exception) {
            Log.d(ANG_PACKAGE, e.toString())
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            //super.onBackPressed()
            onBackPressedDispatcher.onBackPressed()
        }
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        // Handle navigation view item clicks here.
        when (item.itemId) {
            //R.id.server_profile -> activityClass = MainActivity::class.java
            R.id.sub_setting -> {
                startActivity(Intent(this, SubSettingActivity::class.java))
            }
            R.id.settings -> {
                startActivity(
                    Intent(this, SettingsActivity::class.java).putExtra("isRunning", mainViewModel.isRunning.value == true)
                )
            }
            R.id.user_asset_setting -> {
                startActivity(Intent(this, UserAssetActivity::class.java))
            }
            R.id.feedback -> {
                Utils.openUri(this, AppConfig.v2rayNGIssues)
            }
            R.id.promotion -> {
                Utils.openUri(this, "${Utils.decode(AppConfig.promotionUrl)}?t=${System.currentTimeMillis()}")
            }
            R.id.logcat -> {
                startActivity(Intent(this, LogcatActivity::class.java))
            }
        }
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }
}
