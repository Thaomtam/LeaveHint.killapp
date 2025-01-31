package com.KTAify.LeaveHint;

import android.app.ActivityManager;
import android.content.Context;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class LeaveHint implements IXposedHookLoadPackage {
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

        TaskInfo(String packageName, int processId, long lastActiveTime) {
            this.packageName = packageName;
            this.processId = processId;
            this.lastActiveTime = lastActiveTime;
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals("android")) {
            return;
        }

        // Hook RecentTasks class
        final Class<?> recentTasksClass = XposedHelpers.findClass(
            "com.android.server.wm.RecentTasks",
            lpparam.classLoader
        );

        // Hook Task class
        final Class<?> taskClass = XposedHelpers.findClass(
            "com.android.server.wm.Task",
            lpparam.classLoader
        );

        // Hook method thêm task
        XposedHelpers.findAndHookMethod(recentTasksClass,
            "add",
            taskClass,
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object task = param.args[0];
                    updateTaskCache(task);
                }
            }
        );

        // Hook method xóa task
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

        // Hook cập nhật lastActiveTime
        XposedHelpers.findAndHookMethod(taskClass,
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
            
            // Lấy realActivity thay vì realActivityName
            Object realActivity = XposedHelpers.getObjectField(task, "realActivity");
            if (realActivity == null) return;
            
            String packageName = realActivity.toString().split("/")[0];
            if (packageName.startsWith("{")) {
                packageName = packageName.substring(1);
            }
            
            Object processRecord = XposedHelpers.getObjectField(task, "mRootProcess");
            int processId = processRecord != null ? 
                (int) XposedHelpers.getIntField(processRecord, "pid") : -1;
                
            long lastActiveTime = (long) XposedHelpers.callMethod(task, "getLastActiveTime");

            taskCache.put(taskId, new TaskInfo(
                packageName,
                processId,
                lastActiveTime
            ));
            
            XposedBridge.log("[RecentsKiller] Added task to cache: " + packageName);
        } catch (Exception e) {
            XposedBridge.log("[RecentsKiller] Error updating cache: " + e.getMessage());
        }
    }

    private void handleTaskRemoval(int taskId) {
        TaskInfo info = taskCache.remove(taskId);
        if (info == null) return;

        if (!shouldKillTask(info.packageName)) {
            XposedBridge.log("[RecentsKiller] Skipped protected task: " + info.packageName);
            return;
        }

        try {
            // Kill process bằng cả PID và package name
            if (info.processId > 0) {
                Runtime.getRuntime().exec(
                    new String[] {"su", "-c", "kill " + info.processId}
                );
            }
            
            // Force stop app
            Runtime.getRuntime().exec(
                new String[] {"su", "-c", "am force-stop " + info.packageName}
            );
            
            XposedBridge.log("[RecentsKiller] Killed task: " + info.packageName);
        } catch (Exception e) {
            XposedBridge.log("[RecentsKiller] Error killing task: " + e.getMessage());
        }
    }

    private boolean shouldKillTask(String packageName) {
        return !PROTECTED_PACKAGES.contains(packageName) &&
               !packageName.startsWith("com.android.") &&
               !packageName.startsWith("com.google.android.");
    }
}
