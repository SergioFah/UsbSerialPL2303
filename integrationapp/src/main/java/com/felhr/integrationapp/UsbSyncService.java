package com.sergiofah.integrationapp;

import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import com.sergiofah.usbserial.CDCSerialDevice;
import com.sergiofah.usbserial.UsbSerialDevice;
import com.sergiofah.usbserial.UsbSerialInterface;

import java.util.HashMap;
import java.util.Map;

import okio.Buffer;

/**
 * Created by Felipe Herranz(felhr85@gmail.com) on 2019-07-04.
 */
public class UsbSyncService extends Service {

    public static final String TAG = "UsbService";

    private static final int SIZE_TEST_1 = 1024;
    private static final int SIZE_TEST_2 = 2048;
    private static final int SIZE_TEST_3 = 16384;

    private static final String TEST_1 = "test1";
    private static final String TEST_2 = "test2";
    private static final String TEST_3 = "test3";
    private static final String TEST_4 = "test4";
    private static final String TEST_5 = "test5";

    private static final int BUFFER_SYNC = 100;


    public static final String ACTION_USB_READY = "com.sergiofah.connectivityservices.USB_READY";
    public static final String ACTION_USB_ATTACHED = "android.hardware.usb.action.USB_DEVICE_ATTACHED";
    public static final String ACTION_USB_DETACHED = "android.hardware.usb.action.USB_DEVICE_DETACHED";
    public static final String ACTION_USB_NOT_SUPPORTED = "com.sergiofah.usbservice.USB_NOT_SUPPORTED";
    public static final String ACTION_NO_USB = "com.sergiofah.usbservice.NO_USB";
    public static final String ACTION_USB_PERMISSION_GRANTED = "com.sergiofah.usbservice.USB_PERMISSION_GRANTED";
    public static final String ACTION_USB_PERMISSION_NOT_GRANTED = "com.sergiofah.usbservice.USB_PERMISSION_NOT_GRANTED";
    public static final String ACTION_USB_DISCONNECTED = "com.sergiofah.usbservice.USB_DISCONNECTED";
    public static final String ACTION_CDC_DRIVER_NOT_WORKING = "com.sergiofah.connectivityservices.ACTION_CDC_DRIVER_NOT_WORKING";
    public static final String ACTION_USB_DEVICE_NOT_WORKING = "com.sergiofah.connectivityservices.ACTION_USB_DEVICE_NOT_WORKING";
    public static final int MESSAGE_TEST_1 = 0;
    public static final int MESSAGE_TEST_2 = 1;
    public static final int MESSAGE_TEST_3 = 2;
    private static final String ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION";
    private static final int BAUD_RATE = 115200; // BaudRate. Change this value if you need
    public static boolean SERVICE_CONNECTED = false;

    private IBinder binder = new UsbSyncService.UsbBinder();

    private Context context;
    private Handler mHandler;
    private UsbManager usbManager;
    private UsbDevice device;
    private UsbDeviceConnection connection;
    private UsbSerialDevice serialPort;

    private Buffer buffer = new Buffer();
    private String mode;

    private ReadThread readThread;

    private boolean serialPortConnected;

    /*
     * Different notifications from OS will be received here (USB attached, detached, permission responses...)
     * About BroadcastReceiver: http://developer.android.com/reference/android/content/BroadcastReceiver.html
     */
    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context arg0, Intent arg1) {
            if (arg1.getAction().equals(ACTION_USB_PERMISSION)) {
                boolean granted = arg1.getExtras().getBoolean(UsbManager.EXTRA_PERMISSION_GRANTED);
                if (granted) // User accepted our USB connection. Try to open the device as a serial port
                {
                    Intent intent = new Intent(ACTION_USB_PERMISSION_GRANTED);
                    arg0.sendBroadcast(intent);
                    connection = usbManager.openDevice(device);
                    new ConnectionThread().start();
                } else // User not accepted our USB connection. Send an Intent to the Main Activity
                {
                    Intent intent = new Intent(ACTION_USB_PERMISSION_NOT_GRANTED);
                    arg0.sendBroadcast(intent);
                }
            } else if (arg1.getAction().equals(ACTION_USB_ATTACHED)) {
                if (!serialPortConnected)
                    findSerialPortDevice(); // A USB device has been attached. Try to open it as a Serial port
            } else if (arg1.getAction().equals(ACTION_USB_DETACHED)) {
                // Usb device was disconnected. send an intent to the Main Activity
                Intent intent = new Intent(ACTION_USB_DISCONNECTED);
                arg0.sendBroadcast(intent);
                if (serialPortConnected) {
                    serialPort.syncClose();
                }
                serialPortConnected = false;
            }
        }
    };

    /*
     * onCreate will be executed when service is started. It configures an IntentFilter to listen for
     * incoming Intents (USB ATTACHED, USB DETACHED...) and it tries to open a serial port.
     */
    @Override
    public void onCreate() {
        this.context = this;
        serialPortConnected = false;
        UsbService.SERVICE_CONNECTED = true;
        setFilter();
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        findSerialPortDevice();
        mode = TEST_1;
    }

    /* MUST READ about services
     * http://developer.android.com/guide/components/services.html
     * http://developer.android.com/guide/components/bound-services.html
     */
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return Service.START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        serialPort.close();
        unregisterReceiver(usbReceiver);
        UsbService.SERVICE_CONNECTED = false;
    }


    public void setHandler(Handler mHandler) {
        this.mHandler = mHandler;
    }

    private void findSerialPortDevice() {
        // This snippet will try to open the first encountered usb device connected, excluding usb root hubs
        HashMap<String, UsbDevice> usbDevices = usbManager.getDeviceList();
        if (!usbDevices.isEmpty()) {

            // first, dump the hashmap for diagnostic purposes
            for (Map.Entry<String, UsbDevice> entry : usbDevices.entrySet()) {
                device = entry.getValue();
                Log.d(TAG, String.format("USBDevice.HashMap (vid:pid) (%X:%X)-%b class:%X:%X name:%s",
                        device.getVendorId(), device.getProductId(),
                        UsbSerialDevice.isSupported(device),
                        device.getDeviceClass(), device.getDeviceSubclass(),
                        device.getDeviceName()));
            }

            for (Map.Entry<String, UsbDevice> entry : usbDevices.entrySet()) {
                device = entry.getValue();
                int deviceVID = device.getVendorId();
                int devicePID = device.getProductId();

//                if (deviceVID != 0x1d6b && (devicePID != 0x0001 && devicePID != 0x0002 && devicePID != 0x0003) && deviceVID != 0x5c6 && devicePID != 0x904c) {
                if (UsbSerialDevice.isSupported(device)) {
                    // There is a supported device connected - request permission to access it.
                    requestUserPermission();
                    break;
                } else {
                    connection = null;
                    device = null;
                }
            }
            if (device==null) {
                // There are no USB devices connected (but usb host were listed). Send an intent to MainActivity.
                Intent intent = new Intent(ACTION_NO_USB);
                sendBroadcast(intent);
            }
        } else {
            Log.d(TAG, "findSerialPortDevice() usbManager returned empty device list." );
            // There is no USB devices connected. Send an intent to MainActivity
            Intent intent = new Intent(ACTION_NO_USB);
            sendBroadcast(intent);
        }
    }

    private void setFilter() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(ACTION_USB_DETACHED);
        filter.addAction(ACTION_USB_ATTACHED);
        registerReceiver(usbReceiver, filter);
    }

    /*
     * Request user permission. The response will be received in the BroadcastReceiver
     */
    private void requestUserPermission() {
        Log.d(TAG, String.format("requestUserPermission(%X:%X)", device.getVendorId(), device.getProductId() ) );
        PendingIntent mPendingIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), 0);
        usbManager.requestPermission(device, mPendingIntent);
    }

    public class UsbBinder extends Binder {
        public UsbSyncService getService() {
            return UsbSyncService.this;
        }
    }

    /*
     * A simple thread to open a serial port.
     * Although it should be a fast operation. moving usb operations away from UI thread is a good thing.
     */
    private class ConnectionThread extends Thread {
        @Override
        public void run() {
            serialPort = UsbSerialDevice.createUsbSerialDevice(device, connection);
            if (serialPort != null) {
                if (serialPort.syncOpen()) {
                    serialPortConnected = true;
                    serialPort.setBaudRate(BAUD_RATE);
                    serialPort.setDataBits(UsbSerialInterface.DATA_BITS_8);
                    serialPort.setStopBits(UsbSerialInterface.STOP_BITS_1);
                    serialPort.setParity(UsbSerialInterface.PARITY_NONE);
                    /**
                     * Current flow control Options:
                     * UsbSerialInterface.FLOW_CONTROL_OFF
                     * UsbSerialInterface.FLOW_CONTROL_RTS_CTS only for CP2102 and FT232
                     * UsbSerialInterface.FLOW_CONTROL_DSR_DTR only for CP2102 and FT232
                     */
                    serialPort.setFlowControl(UsbSerialInterface.FLOW_CONTROL_OFF);

                    //
                    // Some Arduinos would need some sleep because firmware wait some time to know whether a new sketch is going
                    // to be uploaded or not
                    //Thread.sleep(2000); // sleep some. YMMV with different chips.

                    // Everything went as expected. Send an intent to MainActivity
                    Intent intent = new Intent(ACTION_USB_READY);
                    context.sendBroadcast(intent);

                    readThread = new ReadThread();
                    readThread.start();
                } else {
                    // Serial port could not be opened, maybe an I/O error or if CDC driver was chosen, it does not really fit
                    // Send an Intent to Main Activity
                    if (serialPort instanceof CDCSerialDevice) {
                        Intent intent = new Intent(ACTION_CDC_DRIVER_NOT_WORKING);
                        context.sendBroadcast(intent);
                    } else {
                        Intent intent = new Intent(ACTION_USB_DEVICE_NOT_WORKING);
                        context.sendBroadcast(intent);
                    }
                }
            } else {
                // No driver for given device, even generic CDC driver could not be loaded
                Intent intent = new Intent(ACTION_USB_NOT_SUPPORTED);
                context.sendBroadcast(intent);
            }
        }
    }

    private class ReadThread extends Thread {
        @Override
        public void run() {
            while(true){
                byte[] tmpBuffer = new byte[16384];
                int n = serialPort.syncRead(tmpBuffer, 0);

                if(n > 0) {
                    buffer.write(tmpBuffer, 0, n);
                }

                if(mode.equals(TEST_1)){
                    if(buffer.size() == SIZE_TEST_1){
                        serialPort.syncWrite(buffer.readByteArray(), 0);
                        mode = TEST_2;
                        mHandler.obtainMessage(MESSAGE_TEST_1, "Test 1Kb completed correctly").sendToTarget();
                    }
                }else if(mode.equals(TEST_2)){
                    if(buffer.size() == SIZE_TEST_2){
                        serialPort.syncWrite(buffer.readByteArray(), 0);
                        mode = TEST_3;
                        mHandler.obtainMessage(MESSAGE_TEST_2, "Test 2Kb completed correctly").sendToTarget();
                    }

                }else if(mode.equals(TEST_3)){
                    if(buffer.size() == SIZE_TEST_3){
                        serialPort.syncWrite(buffer.readByteArray(), 0);
                        mode = TEST_1;
                        mHandler.obtainMessage(MESSAGE_TEST_3, "Test 16Kb completed correctly").sendToTarget();
                    }
                }
            }
        }
    }
}
