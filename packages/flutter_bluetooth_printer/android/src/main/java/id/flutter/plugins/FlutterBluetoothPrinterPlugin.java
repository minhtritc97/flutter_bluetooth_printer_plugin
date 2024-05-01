package id.flutter.plugins;

import static android.os.Build.VERSION.SDK_INT;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry;

public class FlutterBluetoothPrinterPlugin implements FlutterPlugin, ActivityAware, MethodCallHandler, PluginRegistry.RequestPermissionsResultListener, EventChannel.StreamHandler {
    private MethodChannel channel;
    private Activity activity;
    private BluetoothAdapter bluetoothAdapter;
    private FlutterPluginBinding flutterPluginBinding;
    private Map<String, BluetoothSocket> connectedDevices = new HashMap<>();
    private Handler mainThread;

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
        this.flutterPluginBinding = flutterPluginBinding;
        this.mainThread = new Handler(Looper.getMainLooper());

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            BluetoothManager bluetoothManager = flutterPluginBinding.getApplicationContext().getSystemService(BluetoothManager.class);
            bluetoothAdapter = bluetoothManager.getAdapter();
        } else {
            bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        }


        channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), "maseka.dev/flutter_bluetooth_printer");
        channel.setMethodCallHandler(this);

        EventChannel discoveryChannel = new EventChannel(flutterPluginBinding.getBinaryMessenger(), "maseka.dev/flutter_bluetooth_printer/discovery");
        discoveryChannel.setStreamHandler(this);

        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        flutterPluginBinding.getApplicationContext().registerReceiver(discoveryReceiver, filter);

        IntentFilter stateFilter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        flutterPluginBinding.getApplicationContext().registerReceiver(stateReceiver, stateFilter);
    }

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                final int value = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF);
                if (value == BluetoothAdapter.STATE_OFF) {
                    Map<String, Object> data = new HashMap<>();
                    data.put("code", 1);
                    for (EventChannel.EventSink sink : sinkList.values()) {
                        sink.success(data);
                    }
                } else if (value == BluetoothAdapter.STATE_ON) {
                    startDiscovery(false);
                }
            }
        }
    };

    private final BroadcastReceiver discoveryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                final Map<String, Object> map = deviceToMap(device);

                for (EventChannel.EventSink sink : sinkList.values()) {
                    sink.success(map);
                }
            }
        }
    };

    private Map<String, Object> deviceToMap(BluetoothDevice device) {
        final HashMap<String, Object> map = new HashMap<>();
        map.put("code", 4);
        map.put("name", device.getName());
        map.put("address", device.getAddress());
        map.put("type", device.getType());
        return map;
    }

    private boolean ensurePermission(boolean request) {
        if (SDK_INT >= Build.VERSION_CODES.M) {
            if (SDK_INT >= 31) {
                final boolean bluetooth = activity.checkSelfPermission(Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED;
                final boolean bluetoothScan = activity.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
                final boolean bluetoothConnect = activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;

                if (bluetooth && bluetoothScan && bluetoothConnect) {
                    return true;
                }

                if (!request) return false;
                activity.requestPermissions(new String[]{Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}, 919191);
            } else {
                boolean bluetooth = activity.checkSelfPermission(Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED;
                boolean fineLocation = activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
                boolean coarseLocation = activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;

                if (bluetooth && (fineLocation || coarseLocation)) {
                    return true;
                }

                if (!request) return false;
                activity.requestPermissions(new String[]{Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 919191);
            }

            return false;
        }

        return true;
    }

    private void startDiscovery(boolean requestPermission) {
        if (!ensurePermission(requestPermission)) {
            return;
        }

        if (bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.cancelDiscovery();
        }

        bluetoothAdapter.startDiscovery();

        Set<BluetoothDevice> bonded = bluetoothAdapter.getBondedDevices();
        for (BluetoothDevice device : bonded) {
            final Map<String, Object> map = deviceToMap(device);
            for (EventChannel.EventSink sink : sinkList.values()) {
                sink.success(map);
            }
        }
    }


    private void stopDiscovery() {
        if (bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.cancelDiscovery();
        }
    }

    private void updatePrintingProgress(int total, int progress) {
        mainThread.post(() -> {
            Map<String, Object> data = new HashMap<>();
            data.put("total", total);
            data.put("progress", progress);

            channel.invokeMethod("onPrintingProgress", data);
        });
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
        final String method = call.method;
        switch (method) {
            case "connect" : {
                new Thread(() -> {
                    synchronized (FlutterBluetoothPrinterPlugin.this) {
                        try {
                            String address = call.argument("address");
                            final BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
                            UUID uuid = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");
                            BluetoothSocket bluetoothSocket = device.createRfcommSocketToServiceRecord(uuid);
                            bluetoothSocket.connect();
                            connectedDevices.put(address, bluetoothSocket);
                            mainThread.post(() -> {
                                // DONE
                                result.success(true);
                            });
                        } catch (Exception e) {
                            mainThread.post(() -> {
                                result.error("error", e.getMessage(), null);
                            });
                        }
                    }
                }).start();
            }
            case "getState": {
                if (!ensurePermission(false)) {
                    result.success(3);
                    return;
                }

                if (!bluetoothAdapter.isEnabled()) {
                    result.success(1);
                    return;
                }

                final int state = bluetoothAdapter.getState();
                if (state == BluetoothAdapter.STATE_OFF) {
                    result.success(1);
                    return;
                }

                if (state == BluetoothAdapter.STATE_ON) {
                    result.success(2);
                    return;
                }
                return;
            }

            case "disconnect": {
                new Thread(() -> {
                    synchronized (FlutterBluetoothPrinterPlugin.this) {
                        try {
                            String address = call.argument("address");
                            BluetoothSocket socket = connectedDevices.remove(address);
                            if (socket != null) {
                                socket.close();
                                mainThread.post(() -> {
                                    // DONE
                                    result.success(true);
                                });
                            }
                        } catch (Exception e) {
                            mainThread.post(() -> {
                                result.error("error", e.getMessage(), null);
                            });
                        }
                    }
                }).start();
                return;
            }

            case "write": {
                // CONNECTING
                channel.invokeMethod("didUpdateState", 1);

                new Thread(() -> {
                    synchronized (FlutterBluetoothPrinterPlugin.this) {
                        try {
                            String address = call.argument("address");
                            boolean keepConnected = call.argument("keep_connected");
                            byte[] data = call.argument("data");
                            int maxTxPacketSize = 512;
                            if (call.hasArgument("max_buffer_size")) {
                                maxTxPacketSize = call.argument("max_buffer_size");
                            }

                            BluetoothSocket bluetoothSocket = connectedDevices.remove(address);
                            if (bluetoothSocket == null) {
                                final BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
                                UUID uuid = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");
                                bluetoothSocket = device.createRfcommSocketToServiceRecord(uuid);
                                bluetoothSocket.connect();
                            }

                            try {
                                if (keepConnected) {
                                    connectedDevices.put(address, bluetoothSocket);
                                }


                                OutputStream writeStream = bluetoothSocket.getOutputStream();

                                // PRINTING
                                mainThread.post(() -> channel.invokeMethod("didUpdateState", 2));
                                updatePrintingProgress(data.length, 0);

                                int tmpOffset = 0;
                                int bytesToWrite = data.length;
                                while (bytesToWrite > 0) {
                                    int tmpLength = Math.min(bytesToWrite, maxTxPacketSize);
                                    int delay = tmpLength / 16;
                                    writeStream.write(data, tmpOffset, tmpLength);
                                    tmpOffset += tmpLength;
                                    updatePrintingProgress(data.length, tmpOffset);
                                    bytesToWrite -= tmpLength;
                                    Thread.sleep(delay);
                                }
                                writeStream.flush();
                                updatePrintingProgress(data.length, data.length);
                                // waiting for printing completed
                                int waitTime = data.length/16;
                                Thread.sleep(waitTime);
                                writeStream.close();

                                mainThread.post(() -> {
                                    // COMPLETED
                                    channel.invokeMethod("didUpdateState", 3);

                                    // DONE
                                    result.success(true);
                                });
                            } finally {
                                if (!keepConnected) {
                                    bluetoothSocket.close();
                                }
                            }
                        } catch (Exception e) {
                            mainThread.post(() -> {
                                result.error("error", e.getMessage(), null);
                            });
                        }
                    }
                }).start();
                return;
            }

            default:
                result.notImplemented();
                break;
        }
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        channel.setMethodCallHandler(null);
        flutterPluginBinding.getApplicationContext().unregisterReceiver(discoveryReceiver);
        flutterPluginBinding.getApplicationContext().unregisterReceiver(stateReceiver);
    }

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        activity = binding.getActivity();
        binding.addRequestPermissionsResultListener(this);
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {

    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {

    }

    @Override
    public void onDetachedFromActivity() {
        activity = null;
    }

    @Override
    public boolean onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == 919191) {
            for (final int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    Map<String, Object> data = new HashMap<>();
                    data.put("code", 3);

                    for (EventChannel.EventSink sink : sinkList.values()) {
                        sink.success(data);
                    }

                    return true;
                }
            }

            if (!bluetoothAdapter.isEnabled()) {
                Map<String, Object> data = new HashMap<>();
                data.put("code", 1);

                for (EventChannel.EventSink sink : sinkList.values()) {
                    sink.success(data);
                }
                return true;
            }

            final int state = bluetoothAdapter.getState();
            if (state == BluetoothAdapter.STATE_OFF) {
                Map<String, Object> data = new HashMap<>();
                data.put("code", 1);

                for (EventChannel.EventSink sink : sinkList.values()) {
                    sink.success(data);
                }
                return true;
            }

            startDiscovery(false);
            return true;
        }

        return false;
    }

    private final Map<Object, EventChannel.EventSink> sinkList = new HashMap<>();

    @Override
    public void onListen(Object arguments, EventChannel.EventSink events) {
        sinkList.put(arguments, events);
        startDiscovery(true);
    }

    @Override
    public void onCancel(Object arguments) {
        sinkList.remove(arguments);
        if (sinkList.isEmpty()) {
            stopDiscovery();
        }
    }
}
