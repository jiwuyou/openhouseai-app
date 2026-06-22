package com.termux.app.openhouse;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import com.termux.R;
import com.termux.shared.logger.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class OpenHouseStartupPermissionHelper {

    private static final String LOG_TAG = "OpenHouseStartupPermission";

    private OpenHouseStartupPermissionHelper() {
    }

    public static void openStartupPermissionSettings(Activity activity) {
        if (activity == null) return;

        List<Intent> intents = new ArrayList<>();
        addManufacturerIntents(activity, intents);
        addFallbackIntents(activity, intents);

        for (Intent intent : intents) {
            try {
                activity.startActivity(intent);
                Toast.makeText(activity, R.string.permission_open_startup_hint, Toast.LENGTH_LONG).show();
                return;
            } catch (ActivityNotFoundException | SecurityException e) {
                Logger.logDebug(LOG_TAG, "Startup permission intent unavailable: " + intent);
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to open startup permission settings", e);
            }
        }

        Toast.makeText(activity, R.string.permission_open_startup_failed, Toast.LENGTH_SHORT).show();
    }

    private static void addManufacturerIntents(Context context, List<Intent> intents) {
        String manufacturer = Build.MANUFACTURER == null
            ? ""
            : Build.MANUFACTURER.toLowerCase(Locale.ROOT);
        String brand = Build.BRAND == null
            ? ""
            : Build.BRAND.toLowerCase(Locale.ROOT);
        String device = manufacturer + " " + brand;

        if (device.contains("xiaomi") || device.contains("redmi") || device.contains("poco")) {
            intents.add(component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"));
            intents.add(component("com.miui.securitycenter", "com.miui.powercenter.PowerSettings"));
            intents.add(component("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity"));
        }

        if (device.contains("oppo") || device.contains("realme") || device.contains("oneplus")) {
            intents.add(component("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"));
            intents.add(component("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"));
            intents.add(component("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"));
            intents.add(component("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"));
        }

        if (device.contains("vivo") || device.contains("iqoo")) {
            intents.add(component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"));
            intents.add(component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"));
            intents.add(component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"));
        }

        if (device.contains("huawei") || device.contains("honor")) {
            intents.add(component("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"));
            intents.add(component("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"));
            intents.add(component("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"));
        }

        if (device.contains("samsung")) {
            intents.add(component("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"));
            intents.add(component("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity"));
        }

        if (device.contains("meizu")) {
            intents.add(component("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"));
            intents.add(component("com.meizu.safe", "com.meizu.safe.security.HomeActivity"));
        }

        intents.add(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:" + context.getPackageName())));
    }

    private static void addFallbackIntents(Context context, List<Intent> intents) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            intents.add(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
        intents.add(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:" + context.getPackageName())));
        intents.add(new Intent(Settings.ACTION_SETTINGS));
    }

    private static Intent component(String packageName, String className) {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(packageName, className));
        return intent;
    }
}
