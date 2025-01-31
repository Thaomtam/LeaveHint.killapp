package com.KTAify.LeaveHint;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class LeaveHint implements IXposedHookLoadPackage {
    // Cache để theo dõi trạng thái các task
    private final ConcurrentHashMap<Integer, TaskInfo> taskCache = new ConcurrentHashMap<>();
    
    private static final List<String> PROTECTED_PACKAGES = Arrays.asList(
        "com.google.android.systemui",
        "com.android.systemui",
        "com.google.android.gms",
        "com.android.vending",
        "google.gms.persistent",
        "app.lawnchair",
        "com.google.android.apps.photos",
        "com.drdisagree.iconify.debug",
        "com.zing.zalo"
    );

    private static class TaskInfo {
        String packageName;
        int processId;
        long lastActiveTime;
        boolean isSystemApp;

        TaskInfo(String packageName, int processId, long lastActiveTime, boolean isSystemApp) {
            this.packageName = packageName;
            this.processId = processId;
            this.lastActiveTime = lastActiveTime;
            this.isSystemApp = isSystemApp;
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals("android")) {
            return;
        }

        // Hook vào RecentTasks class
        hookRecentTasks(lpparam.classLoader);
        
        // Hook TaskRecord để theo dõi thông tin task
        hookTaskRecord(lpparam.classLoader);
    }

    private void hookRecentTasks(ClassLoader classLoader) {
        Class<?> recentTasksClass = XposedHelpers.findClass(
            "com.android.server.wm.RecentTasks",
            classLoader
        );

        // Hook phương thức thêm task vào recents
        XposedHelpers.findAndHookMethod(recentTasksClass, 
            "add", 
            "com.android.server.wm.Task",
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object task = param.args[0];
                    updateTaskCache(task);
                }
            }
        );

        // Hook phương thức xóa task khỏi recents
        XposedHelpers.findAndHookMethod(recentTasksClass,
            "removeTask",
            int.class,
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    int taskId = (int) param.args[0];
                    handleTaskRemoval(taskId);
                }
            }
        );
    }

    private void hookTaskRecord(ClassLoader classLoader) {
        Class<?> taskRecordClass = XposedHelpers.findClass(
            "com.android.server.wm.Task",
            classLoader
        );

        // Hook để cập nhật lastActiveTime
        XposedHelpers.findAndHookMethod(taskRecordClass,
            "setLastActiveTime",
            long.class,
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object task = param.thisObject;
                    int taskId = (int) XposedHelpers.callMethod(task, "getTaskId");
                    long activeTime = (long) param.args[0];
                    
                    TaskInfo info = taskCache.get(taskId);
                    if (info != null) {
                        info.lastActiveTime = activeTime;
                    }
                }
            }
        );
    }

    private void updateTaskCache(Object task) {
        try {
            int taskId = (int) XposedHelpers.callMethod(task, "getTaskId");
            String packageName = (String) XposedHelpers.getObjectField(
                task, "realActivityName"
            );
            Object processRecord = XposedHelpers.getObjectField(task, "mRootProcess");
            int processId = processRecord != null ? 
                (int) XposedHelpers.getIntField(processRecord, "pid") : -1;
            long lastActiveTime = (long) XposedHelpers.callMethod(task, "getLastActiveTime");
            
            boolean isSystemApp = isSystemPackage(packageName);
            
            taskCache.put(taskId, new TaskInfo(
                packageName, 
                processId, 
                lastActiveTime,
                isSystemApp
            ));
            
            XposedBridge.log("[RecentsKiller] Added task to cache: " + packageName);
        } catch (Exception e) {
            XposedBridge.log("[RecentsKiller] Error updating cache: " + e.getMessage());
        }
    }

    private void handleTaskRemoval(int taskId) {
        TaskInfo info = taskCache.remove(taskId);
        if (info == null) return;

        if (!shouldKillTask(info)) {
            XposedBridge.log("[RecentsKiller] Skipped protected task: " + info.packageName);
            return;
        }

        try {
            // Kill process using both process ID và package name để đảm bảo
            if (info.processId > 0) {
                Runtime.getRuntime().exec(
                    new String[] {"su", "-c", "kill " + info.processId}
                );
            }
            Runtime.getRuntime().exec(
                new String[] {"su", "-c", "am force-stop " + info.packageName}
            );
            XposedBridge.log("[RecentsKiller] Killed task: " + info.packageName);
        } catch (Exception e) {
            XposedBridge.log("[RecentsKiller] Error killing task: " + e.getMessage());
        }
    }

    private boolean shouldKillTask(TaskInfo info) {
        return !info.isSystemApp && 
               !PROTECTED_PACKAGES.contains(info.packageName) &&
               !info.packageName.startsWith("com.android.") &&
               !info.packageName.startsWith("com.google.android.");
    }

    private boolean isSystemPackage(String packageName) {
        try {
            PackageManager pm = XposedHelpers.getObjectField(
                XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null),
                    "currentActivityThread"
                ),
                "mSystemContext"
            ).getPackageManager();
            
            ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
            return (ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
        } catch (Exception e) {
            return false;
        }
    }
}
