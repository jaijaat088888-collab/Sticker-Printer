package com.biltysticker.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.util.Base64;

import androidx.core.content.ContextCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Classic Bluetooth (SPP / RFCOMM) printer bridge.
 * JS name: BtPrinter -> list(), connect({address}), write({data: base64}), disconnect(), isConnected(),
 * scan(), pair({address}), openSettings(), shareText({text})
 */
@CapacitorPlugin(name = "BtPrinter")
public class BtPrinterPlugin extends Plugin {
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private BluetoothSocket socket;
    private OutputStream out;

    private boolean hasPerm() {
        if (Build.VERSION.SDK_INT < 31) return true;
        return ContextCompat.checkSelfPermission(getContext(), Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private synchronized void closeQuiet() {
        try { if (out != null) out.close(); } catch (Exception ignored) { }
        try { if (socket != null) socket.close(); } catch (Exception ignored) { }
        out = null;
        socket = null;
    }

    @SuppressLint("MissingPermission")
    @PluginMethod
    public void list(PluginCall call) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) { call.reject("Is phone me Bluetooth nahi hai"); return; }
        if (!hasPerm()) { call.reject("Bluetooth permission do (Allow dabao), phir Refresh karo"); return; }
        if (!adapter.isEnabled()) { call.reject("Bluetooth band hai, chalu karo"); return; }
        try {
            JSArray arr = new JSArray();
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            for (BluetoothDevice d : bonded) {
                JSObject o = new JSObject();
                String name = d.getName();
                o.put("name", name != null ? name : d.getAddress());
                o.put("address", d.getAddress());
                arr.put(o);
            }
            JSObject res = new JSObject();
            res.put("devices", arr);
            call.resolve(res);
        } catch (SecurityException e) {
            call.reject("Bluetooth permission nahi mili");
        }
    }


    private boolean hasScanPerm() {
        if (Build.VERSION.SDK_INT >= 31) {
            return ContextCompat.checkSelfPermission(getContext(), Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(getContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Nearby Bluetooth devices dhoondhta hai (12-15 second). */
    @SuppressLint("MissingPermission")
    @PluginMethod
    public void scan(PluginCall call) {
        final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) { call.reject("Is phone me Bluetooth nahi hai"); return; }
        if (!adapter.isEnabled()) { call.reject("Bluetooth band hai, chalu karo"); return; }
        if (!hasPerm() || !hasScanPerm()) {
            call.reject("Permission do (Allow dabao). Android 11 ya purane me Location permission bhi chahiye.");
            return;
        }
        final Map<String, JSObject> found = new LinkedHashMap<>();
        final CountDownLatch done = new CountDownLatch(1);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                String act = i.getAction();
                if (BluetoothDevice.ACTION_FOUND.equals(act)) {
                    BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (d == null) return;
                    String n = i.getStringExtra(BluetoothDevice.EXTRA_NAME);
                    if (n == null) n = d.getName();
                    JSObject o = new JSObject();
                    o.put("name", n != null ? n : d.getAddress());
                    o.put("address", d.getAddress());
                    o.put("bonded", d.getBondState() == BluetoothDevice.BOND_BONDED);
                    synchronized (found) { found.put(d.getAddress(), o); }
                } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(act)) {
                    done.countDown();
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_FOUND);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        getContext().registerReceiver(receiver, f);
        try {
            if (adapter.isDiscovering()) adapter.cancelDiscovery();
            adapter.startDiscovery();
            done.await(15, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        } finally {
            try { adapter.cancelDiscovery(); } catch (Exception ignored) { }
            try { getContext().unregisterReceiver(receiver); } catch (Exception ignored) { }
        }
        JSArray arr = new JSArray();
        synchronized (found) { for (JSObject o : found.values()) arr.put(o); }
        JSObject res = new JSObject();
        res.put("devices", arr);
        call.resolve(res);
    }

    /** Device ko pair karta hai. PIN ka box Android khud dikhata hai. */
    @SuppressLint("MissingPermission")
    @PluginMethod
    public void pair(PluginCall call) {
        final String address = call.getString("address");
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || address == null) { call.reject("Printer chuna nahi gaya"); return; }
        if (!hasPerm()) { call.reject("Bluetooth permission do (Allow dabao)"); return; }
        final BluetoothDevice dev = adapter.getRemoteDevice(address);
        if (dev.getBondState() == BluetoothDevice.BOND_BONDED) { call.resolve(); return; }
        final CountDownLatch done = new CountDownLatch(1);
        final boolean[] ok = {false};
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (d == null || !address.equals(d.getAddress())) return;
                int state = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
                int prev = i.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR);
                if (state == BluetoothDevice.BOND_BONDED) { ok[0] = true; done.countDown(); }
                else if (state == BluetoothDevice.BOND_NONE && prev == BluetoothDevice.BOND_BONDING) { done.countDown(); }
            }
        };
        getContext().registerReceiver(receiver, new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED));
        try {
            try { adapter.cancelDiscovery(); } catch (Exception ignored) { }
            if (!dev.createBond()) { call.reject("Pair shuru nahi ho paya"); return; }
            done.await(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            call.reject("Pair error: " + e.getMessage());
            return;
        } finally {
            try { getContext().unregisterReceiver(receiver); } catch (Exception ignored) { }
        }
        if (ok[0]) call.resolve(); else call.reject("Pair nahi hua (PIN galat ya cancel). Dobara try karo.");
    }

    @SuppressLint("MissingPermission")
    @PluginMethod
    public synchronized void connect(PluginCall call) {
        String address = call.getString("address");
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || address == null) { call.reject("Printer chuna nahi gaya"); return; }
        if (!hasPerm()) { call.reject("Bluetooth permission do (Allow dabao)"); return; }
        closeQuiet();
        BluetoothSocket s = null;
        try {
            BluetoothDevice dev = adapter.getRemoteDevice(address);
            try { adapter.cancelDiscovery(); } catch (Exception ignored) { }
            try {
                s = dev.createRfcommSocketToServiceRecord(SPP);
                s.connect();
            } catch (Exception e1) {
                try { if (s != null) s.close(); } catch (Exception ignored) { }
                try {
                    s = dev.createInsecureRfcommSocketToServiceRecord(SPP);
                    s.connect();
                } catch (Exception e2) {
                    try { if (s != null) s.close(); } catch (Exception ignored) { }
                    Method m = dev.getClass().getMethod("createRfcommSocket", int.class);
                    s = (BluetoothSocket) m.invoke(dev, 1);
                    s.connect();
                }
            }
            socket = s;
            out = s.getOutputStream();
            call.resolve();
        } catch (Exception e) {
            try { if (s != null) s.close(); } catch (Exception ignored) { }
            call.reject("Connect nahi hua: " + e.getMessage());
        }
    }

    @PluginMethod
    public synchronized void write(PluginCall call) {
        String b64 = call.getString("data");
        if (out == null) { call.reject("Printer juda nahi hai"); return; }
        if (b64 == null) { call.reject("Data nahi mila"); return; }
        try {
            byte[] b = Base64.decode(b64, Base64.DEFAULT);
            int n = 512;
            for (int i = 0; i < b.length; i += n) {
                out.write(b, i, Math.min(n, b.length - i));
            }
            out.flush();
            call.resolve();
        } catch (Exception e) {
            closeQuiet();
            call.reject("Write error: " + e.getMessage());
        }
    }

    @PluginMethod
    public void disconnect(PluginCall call) {
        closeQuiet();
        call.resolve();
    }

    @PluginMethod
    public void isConnected(PluginCall call) {
        JSObject res = new JSObject();
        res.put("connected", socket != null && socket.isConnected());
        call.resolve(res);
    }

    @PluginMethod
    public void openSettings(PluginCall call) {
        Intent i = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(i);
        call.resolve();
    }

    @PluginMethod
    public void shareText(PluginCall call) {
        String text = call.getString("text", "");
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, text);
        Intent chooser = Intent.createChooser(send, "Backup bhejo");
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(chooser);
        call.resolve();
    }
}
