package org.gnuradio.grtemplateusrp;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.XmlResourceParser;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Xml;
import android.widget.SeekBar;

import org.apache.log4j.chainsaw.Main;
import org.gnuradio.controlport.BaseTypes;
import org.gnuradio.grcontrolport.RPCConnection;
import org.gnuradio.grcontrolport.RPCConnectionThrift;
import org.xmlpull.v1.XmlPullParser;

import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static class SetKnobsHandler extends Handler {
        private RPCConnection mConnection;

        public SetKnobsHandler(RPCConnection conn) {
            super();
            mConnection = conn;
        }

        public void handleMessage(Message m) {
            Bundle b = m.getData();
            if (b != null) {
                HashMap<String, RPCConnection.KnobInfo> k =
                        (HashMap<String, RPCConnection.KnobInfo>) b.getSerializable("Knobs");

                Log.d("MainActivity", "Set Knobs: " + k);
                if ((k != null) && (!k.isEmpty())) {
                    mConnection.setKnobs(k);
                }
            }
        }
    }

    public class RunNetworkThread implements Runnable {

        private RPCConnection mConnection;
        private String mHost;
        private Integer mPort;
        private Boolean mConnected;
        private Handler mHandler;

        RunNetworkThread(String host, Integer port) {
            this.mHost = host;
            this.mPort = port;
            this.mConnected = false;
        }

        public void run() {
            if (!mConnected) {
                mConnection = new RPCConnectionThrift(mHost, mPort);
                mConnected = true;
            }

            Looper.prepare();
            mHandler = new SetKnobsHandler(mConnection);
            Looper.loop();
        }

        public RPCConnection getConnection() {
            if (mConnection == null) {
                throw new IllegalStateException("connection not established");
            }
            return mConnection;
        }

        public Handler getHandler() {
            return mHandler;
        }
    }

    private void postSetKnobMessage(HashMap<String, RPCConnection.KnobInfo> knobs) {
        Handler h = mControlPortThread.getHandler();
        Bundle b = new Bundle();
        b.putSerializable("Knobs", knobs);
        Message m = h.obtainMessage();
        m.setData(b);
        h.sendMessage(m);
    }

    public RunNetworkThread mControlPortThread;
    private final String mult_knob_name = "multiply_const_ff0::coefficient";

    public static Intent intent = null;
    private String usbfs_path = null;
    private int fd = -1;
    private static final String ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION";
    private static final String DEFAULT_USBFS_PATH = "/dev/bus/usb";
    private UsbManager mUsbManager;
    private UsbDevice mUsbDevice;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Create the seekBar to move update the amplitude
        SeekBar ampSeekBar = (SeekBar) findViewById(R.id.seekBar);
        ampSeekBar.setMax(100);      // max value -> 1.0
        ampSeekBar.setProgress(50);  // match 0.5 starting value
        ampSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                Double amp = progress / 100.0; // rescale by 100
                RPCConnection.KnobInfo _k =
                        new RPCConnection.KnobInfo(mult_knob_name, amp,
                                BaseTypes.DOUBLE);
                HashMap<String, RPCConnection.KnobInfo> _map = new HashMap<>();
                _map.put(mult_knob_name, _k);
                postSetKnobMessage(_map);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        // Start setting up for USB permission request
        intent = getIntent();
        mUsbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        registerReceiver(mUsbReceiver, filter);
        mUsbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if (mUsbDevice == null) {
            Log.d("GrTemplateUSRP", "Didn't get a device; finding it now.");
            final HashSet<String> allowed_devices = getAllowedDevices(this);
            final HashMap<String, UsbDevice> usb_device_list = mUsbManager.getDeviceList();
            for (UsbDevice candidate : usb_device_list.values()) {
                String candstr = "v" + candidate.getVendorId() + "p" + candidate.getProductId();
                if (allowed_devices.contains(candstr)) {
                    // Need to handle case where we have more than one device connected
                    mUsbDevice = candidate;
                }
            }
        }
        Log.d("GrTemplateUSRP", "Selected Device: " + mUsbDevice);
        PendingIntent permissionIntent = PendingIntent.getBroadcast(this, 0,
                new Intent(ACTION_USB_PERMISSION), 0);

        // Launch dialog to ask for permission.
        // If use hits OK, the broadcast receiver will be launched.
        mUsbManager.requestPermission(mUsbDevice, permissionIntent);
    }

    private void SetupUSB() {
        final UsbDeviceConnection connection = mUsbManager.openDevice(mUsbDevice);

        if (connection != null) {
            fd = connection.getFileDescriptor();
        } else {
            Log.d("GrTemplateUSRP", "Didn't get a USB Device Connection");
            finish();
        }

        if (mUsbDevice != null) {
            usbfs_path = properDeviceName(mUsbDevice.getDeviceName());
        } else {
            Log.d("GrTemplateUSRP", "Didn't get a USB Device");
            finish();
        }

        int vid = mUsbDevice.getVendorId();
        int pid = mUsbDevice.getProductId();

        Log.d("GrTemplateUSRP", "Found fd: " + fd + "  usbfs_path: " + usbfs_path);
        Log.d("GrTemplateUSRP", "Found vid: " + vid + "  pid: " + pid);

        StartRadio();
    }

    private void StartRadio() {
        SetTMP(getCacheDir().getAbsolutePath());
        FgInit(fd, usbfs_path);
        FgStart();

        // Make the ControlPort connection in the network thread
        mControlPortThread = new RunNetworkThread("localhost", 65001);
        Executor executor = Executors.newSingleThreadExecutor();
        executor.execute(mControlPortThread);

        // Wait here until we have confirmation that the connection succeeded.
        while (true) {
            try {
                mControlPortThread.getConnection();
                break;
            } catch (IllegalStateException e0) {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e1) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        // Initialize the multiplier with a value of 0.5
        RPCConnection.KnobInfo k =
                new RPCConnection.KnobInfo(mult_knob_name, 0.5,
                        BaseTypes.DOUBLE);
        HashMap<String, RPCConnection.KnobInfo> map = new HashMap<>();
        map.put(mult_knob_name, k);
        postSetKnobMessage(map);
    }

    private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbDevice device = (UsbDevice)intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if(device != null){
                            mUsbDevice = device;
                            SetupUSB();
                        }
                    }
                    else {
                        Log.d("GrTemplateUSRP", "Permission denied for device " + device);
                    }
                }
            }
        }
    };

    /*
     * Reads from the device_filter.xml file to get a list of the allowable devices.
     */
    private static HashSet<String> getAllowedDevices(final Context ctx) {
        final HashSet<String> ans = new HashSet<String>();
        try {
            final XmlResourceParser xml = ctx.getResources().getXml(R.xml.device_filter);

            xml.next();
            int eventType;
            while ((eventType = xml.getEventType()) != XmlPullParser.END_DOCUMENT) {

                switch (eventType) {
                    case XmlPullParser.START_TAG:
                        if (xml.getName().equals("usb-device")) {
                            final AttributeSet as = Xml.asAttributeSet(xml);
                            final Integer vendorId = Integer.valueOf(as.getAttributeValue(null, "vendor-id"), 10);
                            final Integer productId = Integer.valueOf(as.getAttributeValue(null, "product-id"), 10);
                            ans.add("v" + vendorId + "p" + productId);
                        }
                        break;
                }
                xml.next();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return ans;
    }

    public final static String properDeviceName(String deviceName) {
        if (deviceName == null) return DEFAULT_USBFS_PATH;
        deviceName = deviceName.trim();
        if (deviceName.isEmpty()) return DEFAULT_USBFS_PATH;

        final String[] paths = deviceName.split("/");
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < paths.length - 2; i++)
            if (i == 0)
                sb.append(paths[i]);
            else
                sb.append("/").append(paths[i]);
        final String stripped_name = sb.toString().trim();
        if (stripped_name.isEmpty())
            return DEFAULT_USBFS_PATH;
        else
            return stripped_name;
    }

    public native void SetTMP(String tmpname);

    public native void FgInit(int fd, String usbfs_path);

    public native void FgStart();

    static {
        System.loadLibrary("fg");
    }
}

