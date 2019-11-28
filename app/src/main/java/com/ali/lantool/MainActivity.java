package com.ali.lantool;

import android.annotation.TargetApi;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Parcelable;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.ali.lantool.com.ali.utils.AppExecutors;
import com.ali.lantool.com.ali.utils.NetworkUtil;
import com.ali.lantool.com.ali.utils.ShellUtil;
import com.ali.lantool.com.ali.widget.BaseRecyclerAdapter;

import java.lang.ref.WeakReference;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {

    public static final String TAG = MainActivity.class.getSimpleName();
    private ExecutorService mExecutorService;
    private WifiManager mWifiManager;
    private List<LanDevice> mLanDeviceList = new ArrayList<>();
    private LanDevice mMeLanDevice;
    private Map<String, String> mMacMap = new HashMap<>();
    private NetworkChangeReceiver mReceiver;
    private Handler mHandler;
    private TextView mDeviceView;
    private TextView mDeviceIpView;
    private TextView mDeviceMacView;
    private TextView mSsidView;
    private TextView mStrengthView;
    private TextView mSpeedView;
    private TextView mFrequencyView;
    private TextView mRouterView;
    private TextView mRouterIpView;
    private TextView mRouterMacView;
    private View mDnsLayout;
    private View mDnsLayout2;
    private TextView mDns1View;
    private TextView mDns2View;
    private TextView mDns3View;
    private TextView mDns4View;
    private TextView[] mDnsViews;
    private TextView mMaskView;
    private String mIp;
    private String mRouterIp;
    private LanDeviceAdapter mAdapter;
    private boolean mIsWifiConnected;

    static class LanDeviceHandler extends Handler {
        static final int MSG_ADD = 0;
        private final WeakReference<MainActivity> mActivity;

        LanDeviceHandler(MainActivity activity) {
            mActivity = new WeakReference<>(activity);
        }

        @Override
        public void handleMessage(Message msg) {
            MainActivity activity = mActivity.get();
            if (activity != null) {
                switch (msg.what) {
                    case MSG_ADD:
                        LanDevice device = (LanDevice) msg.obj;
                        if (device.isSelf()) {
                            activity.mMeLanDevice = device;
                        } else {
                            activity.mLanDeviceList.add(device);
                            activity.mAdapter.notifyDataSetChanged();
                        }
                        break;
                    default:
                        break;
                }
            }
        }
    }

    private static class NetworkChangeReceiver extends BroadcastReceiver {
        private MainActivity mActivity;

        public NetworkChangeReceiver(MainActivity activity) {
            mActivity = activity;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(action)) {
                Parcelable parcelableExtra = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
                if (parcelableExtra != null) {
                    NetworkInfo networkInfo = (NetworkInfo) parcelableExtra;
                    NetworkInfo.State state = networkInfo.getState();
                    mActivity.mIsWifiConnected = state == NetworkInfo.State.CONNECTED;// 当然，这边可以更精确的确定状态
                    Log.i(TAG, "isConnected: " + mActivity.mIsWifiConnected);
                    if (mActivity.mIsWifiConnected) {
                        mActivity.refresh();
                    } else {
                        if (mActivity.mExecutorService != null) {
                            mActivity.mExecutorService.shutdownNow();
                        }
                    }
                }
            }
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.lan_device);
        mHandler = new LanDeviceHandler(this);
        mReceiver = new NetworkChangeReceiver(this);
        mWifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        initViews();
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        registerReceiver(mReceiver, intentFilter);
    }

    private void initViews() {
        mDeviceView = findViewById(R.id.device);
        mDeviceIpView = findViewById(R.id.device_ip);
        mDeviceMacView = findViewById(R.id.device_mac);
        mSsidView = findViewById(R.id.ssid);
        mStrengthView = findViewById(R.id.strength);
        mSpeedView = findViewById(R.id.speed);
        mFrequencyView = findViewById(R.id.frequency);
        mRouterView = findViewById(R.id.router);
        mRouterIpView = findViewById(R.id.router_ip);
        mRouterMacView = findViewById(R.id.router_mac);
        mDnsLayout = findViewById(R.id.dns_layout);
        mDnsLayout2 = findViewById(R.id.dns_layout2);
        mDns1View = findViewById(R.id.dns1);
        mDns2View = findViewById(R.id.dns2);
        mDns3View = findViewById(R.id.dns3);
        mDns4View = findViewById(R.id.dns4);
        mDnsViews = new TextView[]{mDns1View, mDns2View, mDns3View, mDns4View};
        mMaskView = findViewById(R.id.mask);
        RecyclerView recyclerView = findViewById(R.id.recycler_view);
        mAdapter = new LanDeviceAdapter(mLanDeviceList);
        recyclerView.setAdapter(mAdapter);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            findViewById(R.id.dns_layout).setVisibility(View.GONE);
            mFrequencyView.setVisibility(View.GONE);
        }
    }

    private void refresh() {
        getRouterIp();
        AppExecutors.getInstance().networkIO().execute(() -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                getDnsServers();
            }

            WifiInfo connectionInfo = mWifiManager.getConnectionInfo();
            int ipAddress = connectionInfo.getIpAddress();
            mIp = Formatter.formatIpAddress(ipAddress);
            String prefix = mIp.substring(0, mIp.lastIndexOf(".") + 1);
            Log.d(TAG, "prefix: " + prefix);
            runOnUiThread(() -> {
                mDeviceIpView.setText(mIp);
                mSsidView.setText(connectionInfo.getSSID().replaceAll("^\"|\"$", ""));
                mStrengthView.setText(connectionInfo.getRssi() + " dBm");
                mSpeedView.setText(connectionInfo.getLinkSpeed() + " " + WifiInfo.LINK_SPEED_UNITS);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    mFrequencyView.setText(connectionInfo.getFrequency() + " " + WifiInfo.FREQUENCY_UNITS);
                }
            });
            mLanDeviceList.clear();
            mMacMap.clear();
            try {
                mExecutorService = Executors.newFixedThreadPool(48);
                for (int i = 0; i < 256; i++) {
                    String ip = prefix + i;
                    mExecutorService.execute(new ScannerRunnable(mHandler, mIp, ip));
                }
                mExecutorService.shutdown();
                mExecutorService.awaitTermination(5, TimeUnit.MINUTES);

                if (mIsWifiConnected && !isFinishing()) {
                    if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                        Log.w(TAG, "can't read arp in android 10");
                    } else {
                        readArp();
                    }
                    fillDeviceMac();
                    runOnUiThread(() -> {
                        mDeviceMacView.setText(mMacMap.get(mIp));
                        mRouterMacView.setText(mMacMap.get(mRouterIp));
                        Collections.sort(mLanDeviceList, (o1, o2) -> {
                            String ip1 = o1.getIp();
                            String ip2 = o2.getIp();
                            return stringIp2Int(ip1.split("\\.")) - stringIp2Int(ip2.split("\\."));
                        });
                        mAdapter.notifyDataSetChanged();
                    });
                } else {
                    runOnUiThread(() -> {
                        mLanDeviceList.clear();
                        mMacMap.clear();
                        mAdapter.notifyDataSetChanged();
                    });
                }
            } catch (Exception e) {
                Log.w(TAG, e);
            }
        });
    }

    @Override
    protected void onPostCreate(@Nullable Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        setTitle("Lan Devices");
    }

    private void fillDeviceMac() {
        for (LanDevice device : mLanDeviceList) {
            device.setMac(mMacMap.get(device.getIp()));
            Log.d(TAG, "device: " + device);
        }
        if (mMeLanDevice != null) {
            mMeLanDevice.setMac(mMacMap.get(mIp));
        }
        Log.d(TAG, "device: " + mMeLanDevice);
    }

    private void getRouterIp() {
        final DhcpInfo dhcp = mWifiManager.getDhcpInfo();
        mRouterIp = Formatter.formatIpAddress(dhcp.gateway);
        final String mask = Formatter.formatIpAddress(dhcp.netmask);
        mRouterIpView.setText(mRouterIp);
        mMaskView.setText("Mask: " + mask);
        Log.d(TAG, "route address: " + mRouterIp);
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void getDnsServers() {
        List<InetAddress> servers = new ArrayList<>();
        ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        Network[] networks = connectivityManager == null ? null : connectivityManager.getAllNetworks();
        if (networks == null) {
            return;
        }
        int length = networks.length;
        for (int i = 0; i < length; ++i) {
            Network network = networks[i];
            NetworkCapabilities networkCapabilities = connectivityManager.getNetworkCapabilities(network);
            NetworkInfo networkInfo = connectivityManager.getNetworkInfo(network);
            if (!networkInfo.isConnected() || !networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                continue;
            }
            LinkProperties linkProperties = connectivityManager.getLinkProperties(network);
            if (linkProperties != null) {
                servers.addAll(linkProperties.getDnsServers());
            }
        }

        int size = servers.size();
        runOnUiThread(() -> setDnsViewsVisibility(size));
        for (int i = 0; i < size; i++) {
            InetAddress server = servers.get(i);
            int finalI = i;
            runOnUiThread(() -> mDnsViews[finalI].setText("DNS" + (finalI + 1) + ": " + server.getHostAddress()));
            Log.d(TAG, "DNS server: " + server.getHostName() + " (" + server.getHostAddress() + ")");
        }
    }

    private void setDnsViewsVisibility(int size) {
        if (size == 0) {
            mDnsLayout.setVisibility(View.GONE);
            mDnsLayout2.setVisibility(View.GONE);
        } else if (size <= 2) {
            mDnsLayout.setVisibility(View.VISIBLE);
            mDnsLayout2.setVisibility(View.GONE);
        } else {
            mDnsLayout.setVisibility(View.VISIBLE);
            mDnsLayout2.setVisibility(View.VISIBLE);
        }

        for (int i = 0; i < size; i++) {
            mDnsViews[i].setVisibility(View.VISIBLE);
        }
        for (int i = size; i < 4; i++) {
            mDnsViews[i].setVisibility(View.GONE);
        }
    }

    //read mac from arp
    private void readArp() {
        List<String> lines = ShellUtil.readArp();
        if (lines.isEmpty()) {
            Log.e(TAG, "read arp error!");
        } else {
            for (String line : lines) {
                if (TextUtils.isEmpty(line)) {
                    continue;
                }
                String[] results = line.split("\\s+");
                if (results.length >= 4 && !TextUtils.equals("00:00:00:00:00:00", results[3])) {
                    mMacMap.put(results[0], results[3]);
                    LanDevice device = new LanDevice();
                    device.setIp(results[0]);
                    device.setMac(results[3]);
                    if (!contains(device.getIp())) {
                        runOnUiThread(() -> {
                            mLanDeviceList.add(device);
                            mAdapter.notifyItemInserted(mLanDeviceList.size() - 1);
                        });
                    }
                } else if (!TextUtils.equals("00:00:00:00:00:00", results[3])) {
                    Log.d(TAG, "wrong result: " + line);
                }
            }
            if (TextUtils.isEmpty(mMacMap.get(mIp))) {
                mMacMap.put(mIp, NetworkUtil.getMacAddress());
            }
        }
    }

    private int stringIp2Int(@NonNull String[] ip) {
        int ipNumbers = 0;
        for (int i = 0; i < 4; i++) {
            ipNumbers += Integer.parseInt(ip[i]) << (24 - (8 * i));
        }
        return ipNumbers;
    }

    private boolean contains(String ip) {
        for (LanDevice device : mLanDeviceList) {
            if (TextUtils.equals(device.getIp(), ip)) {
                return true;
            }
        }
        return false;
    }

    private static class LanDeviceAdapter extends RecyclerView.Adapter<BaseRecyclerAdapter.BaseViewHolder> {

        private List<LanDevice> mLanDeviceList;

        LanDeviceAdapter(@NonNull List<LanDevice> lanDeviceList) {
            this.mLanDeviceList = lanDeviceList;
        }

        @NonNull
        @Override
        public BaseRecyclerAdapter.BaseViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(getLayoutId(viewType), parent, false);
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                return new BaseRecyclerAdapter.BaseViewHolder(view);
            }
            if (viewType == BaseRecyclerAdapter.ITEM_TYPE_HEADER) {
                view.findViewById(R.id.title_mac).setVisibility(View.GONE);
            } else {
                view.findViewById(R.id.mac).setVisibility(View.GONE);
            }
            return new BaseRecyclerAdapter.BaseViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull BaseRecyclerAdapter.BaseViewHolder holder, int position) {
            int type = getItemViewType(position);
            if (type == BaseRecyclerAdapter.ITEM_TYPE_NORMAL) {
                LanDevice lanDevice = mLanDeviceList.get(position - 1);
                TextView ipView = holder.getView(R.id.ip);
                TextView deviceView = holder.getView(R.id.device);
                ipView.setText(lanDevice.getIp());
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    TextView macView = holder.getView(R.id.mac);
                    macView.setText(lanDevice.getMac());
                }
                String hostName = lanDevice.getHostName();
                if (hostName == null) {
                    hostName = "";
                }
                deviceView.setText(hostName.replaceFirst("\\.lan$", ""));
            }
        }

        private int getLayoutId(int itemType) {
            if (itemType == BaseRecyclerAdapter.ITEM_TYPE_HEADER) {
                return R.layout.workbox_item_lan_device_title;
            } else {
                return R.layout.workbox_item_lan_device;
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == 0) {
                return BaseRecyclerAdapter.ITEM_TYPE_HEADER;
            }
            return BaseRecyclerAdapter.ITEM_TYPE_NORMAL;
        }

        @Override
        public int getItemCount() {
            return mLanDeviceList.size() + 1;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mReceiver);
        if (mExecutorService != null) {
            mExecutorService.shutdownNow();
        }
    }

}