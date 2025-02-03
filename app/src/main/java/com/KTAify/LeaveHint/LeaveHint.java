package com.KTAify.LeaveHint;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import android.content.pm.ApplicationInfo;
import android.content.Intent;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class LeaveHint implements IXposedHookLoadPackage {

    private final List<String> mKillIgnoreList = new ArrayList<>(Arrays.asList(
            "com.android.systemui",
            "com.google.android.systemui", 
            "com.google.android.gms",
            "com.android.vending",
            "google.gms.persistent",
            "app.lawnchair",
            "com.google.android.apps.photos",
            "com.drdisagree.iconify.debug",
            "com.zing.zalo"
    ));

    private Context mContext;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (!lpparam.packageName.equals("android"))
            return;

        mContext = (Context) XposedHelpers.callMethod(
                XposedHelpers.callStaticMethod(XposedHelpers.findClass("android.app.ActivityThread", null),
                        "currentActivityThread"), "getSystemContext");

        // Hook cho sự kiện khóa màn hình
        XposedHelpers.findAndHookMethod("com.android.server.policy.PhoneWindowManager",
                lpparam.classLoader, "screenTurnedOff", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        killBackgroundApps(); // Thay đổi từ killForegroundApp sang killBackgroundApps
                    }
                });
    }

    private void killBackgroundApps() {
        Handler mainHandler = new Handler(Looper.getMainLooper());

        mainHandler.post(() -> {
            try {
                ActivityManager am = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
                List<ActivityManager.RunningAppProcessInfo> processes = am.getRunningAppProcesses();
                int killedCount = 0;

                for (ActivityManager.RunningAppProcessInfo processInfo : processes) {
                    if (processInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND 
                        || processInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_EMPTY) {
                        
                        String packageName = processInfo.processName;
                        
                        // Kiểm tra xem app có trong danh sách bỏ qua không
                        if (!mKillIgnoreList.contains(packageName) 
                            && !packageName.equals(getDefaultLauncherPackageName())) {
                            
                            // Sử dụng killBackgroundProcesses thay vì forceStopPackage
                            am.killBackgroundProcesses(packageName);
                            killedCount++;
                            
                            // Log để debug
                            Log.d("LeaveHint", "Killed background app: " + packageName);
                        }
                    }
                }

                // Thông báo số lượng app đã đóng
                if (killedCount > 0) {
                    Toast.makeText(mContext, 
                        "Đã đóng " + killedCount + " ứng dụng chạy nền", 
                        Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(mContext, 
                        "Không có ứng dụng nền cần đóng", 
                        Toast.LENGTH_SHORT).show();
                }

            } catch (Throwable t) {
                Log.e("LeaveHint", "Lỗi trong killBackgroundApps", t);
            }
        });
    }

    private String getDefaultLauncherPackageName() {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_HOME);
        ResolveInfo resolveInfo = mContext.getPackageManager()
            .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
        if (resolveInfo != null) {
            return resolveInfo.activityInfo.packageName;
        }
        return null;
    }

    private String getApplicationLabel(String packageName, PackageManager pm) {
        try {
            ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
            return (String) pm.getApplicationLabel(appInfo);
        } catch (PackageManager.NameNotFoundException e) {
            return packageName;
        }
    }
}
