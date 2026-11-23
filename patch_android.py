"""Run after `npx cap add android`: adds the native Bluetooth plugin, MainActivity and permissions."""
import os, shutil

pkg_dir = "android/app/src/main/java/com/biltysticker/app"
os.makedirs(pkg_dir, exist_ok=True)
for f in ("BtPrinterPlugin.java", "MainActivity.java"):
    shutil.copy(f, os.path.join(pkg_dir, f))
    print("copied", f)

mp = "android/app/src/main/AndroidManifest.xml"
m = open(mp, encoding="utf-8").read()
perms = """    <uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <uses-permission android:name="android.permission.BLUETOOTH_SCAN" android:usesPermissionFlags="neverForLocation" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-feature android:name="android.hardware.bluetooth" android:required="false" />
    <uses-feature android:name="android.hardware.camera" android:required="false" />
"""
if "BLUETOOTH_CONNECT" not in m:
    assert "<application" in m, "AndroidManifest.xml me <application> nahi mila"
    m = m.replace("<application", perms + "\n    <application", 1)
    open(mp, "w", encoding="utf-8").write(m)
    print("manifest patched")
else:
    print("manifest already patched")
