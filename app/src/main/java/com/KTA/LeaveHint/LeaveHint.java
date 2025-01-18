package com.KTA.LeaveHint;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class KillAppNow implements IXposedHookLoadPackage {

    private final Set<String> previousRunningApps = new HashSet<>();
    private Context mContext;

    // Danh sách loại trừ - KHÔNG buộc dừng các gói này
    // (Ví dụ: system packages, Google Services, v.v.)
    private final Set<String> excludedPackages = new HashSet<>();

    // Tránh gọi detect quá thường xuyên trong thời gian ngắn
    private static final long MIN_DELAY_BETWEEN_CHECKS_MS = 3000;
    private long lastCheckTime = 0;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {

        // Ta chỉ can thiệp vào framework "android"
        if (!lpparam.packageName.equals("android")) {
            return;
        }

        // Lấy system context
        mContext = (Context) XposedHelpers.callMethod(
                XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null),
                    "currentActivityThread"
                ),
                "getSystemContext"
        );
        if (mContext == null) {
            Log.e("KillAppNow", "Không lấy được systemContext. Dừng module.");
            return;
        }

        // Khởi tạo danh sách loại trừ cơ bản (bạn có thể tùy chỉnh)
        initExcludedPackages();

        // Hook vào phương thức onUserLeaveHint() của android.app.Activity
        XposedHelpers.findAndHookMethod(
            "android.app.Activity",
            lpparam.classLoader,
            "onUserLeaveHint",
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    // Mỗi lần người dùng rời 1 Activity, ta cập nhật danh sách
                    // Nhưng cần hạn chế tần suất để tránh gọi liên tục
                    long now = System.currentTimeMillis();
                    if (now - lastCheckTime > MIN_DELAY_BETWEEN_CHECKS_MS) {
                        lastCheckTime = now;
                        detectExitedApps();  // Chạy trên main thread
                    }
                }
            }
        );
    }

    /**
     * Khởi tạo danh sách các gói không buộc dừng (vd: SystemUI, GMS, ...)
     * Bạn có thể mở rộng, tùy chỉnh theo ý muốn.
     */
    private void initExcludedPackages() {
        // Ví dụ:
        excludedPackages.add("android");
        excludedPackages.add("com.google.android.systemui");
        excludedPackages.add("com.android.systemui");
        excludedPackages.add("com.google.android.gms");
        excludedPackages.add("com.android.vending");
        excludedPackages.add("google.gms.persistent");
        excludedPackages.add("app.lawnchair");
        excludedPackages.add("com.google.android.apps.photos");
        excludedPackages.add("com.drdisagree.iconify.debug");
        excludedPackages.add("com.drdisagree.iconify");
    }

    /**
     * Kiểm tra process nào đã bị hệ thống kill, từ đó forceStop
     */
    private void detectExitedApps() {
        Handler mainHandler = new Handler(Looper.getMainLooper());
        mainHandler.post(() -> {
            try {
                ActivityManager am = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
                List<ActivityManager.RunningAppProcessInfo> processes = am.getRunningAppProcesses();

                Set<String> currentRunningApps = new HashSet<>();
                if (processes != null) {
                    for (ActivityManager.RunningAppProcessInfo processInfo : processes) {
                        currentRunningApps.add(processInfo.processName);
                    }
                }

                // Tìm các gói trong previousRunningApps mà không còn trong currentRunningApps
                // => chúng đã bị hệ thống dọn dẹp
                for (String packageName : previousRunningApps) {
                    if (!currentRunningApps.contains(packageName)) {
                        // Kiểm tra xem có nằm trong danh sách loại trừ không
                        if (!excludedPackages.contains(packageName)) {
                            forceStopApp(packageName);
                        } else {
                            Log.d("KillAppNow", "Bỏ qua (excluded): " + packageName);
                        }
                    }
                }

                // Cập nhật lại danh sách cũ
                previousRunningApps.clear();
                previousRunningApps.addAll(currentRunningApps);

            } catch (Throwable t) {
                Log.e("KillAppNow", "Lỗi khi phát hiện ứng dụng đã thoát", t);
            }
        });
    }

    /**
     * Thực hiện forceStopPackage qua reflection.
     * Chỉ hữu dụng nếu ROM/Thiết bị cho phép.
     */
    private void forceStopApp(String packageName) {
        try {
            ActivityManager am = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
            // reflect hàm forceStopPackage
            XposedHelpers.callMethod(am, "forceStopPackage", packageName);
            Log.d("KillAppNow", "Đã buộc dừng: " + packageName);
        } catch (Exception e) {
            Log.e("KillAppNow", "Lỗi khi buộc dừng " + packageName, e);
        }
    }
}
