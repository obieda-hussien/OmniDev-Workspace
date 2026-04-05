# OmniDev Workspace System Permission Setup

After installing OmniDev Workspace, run the following commands:

```bash
adb shell pm grant com.omnidev.workspace android.permission.WRITE_SECURE_SETTINGS
adb shell pm grant com.omnidev.workspace android.permission.DUMP
adb shell pm grant com.omnidev.workspace android.permission.PACKAGE_USAGE_STATS
adb shell pm grant com.omnidev.workspace android.permission.CLEAR_APP_CACHE
adb shell pm grant com.omnidev.workspace android.permission.CLEAR_APP_USER_DATA
adb shell pm grant com.omnidev.workspace android.permission.DELETE_PACKAGES
adb shell pm grant com.omnidev.workspace android.permission.INSTALL_PACKAGES
adb shell pm grant com.omnidev.workspace android.permission.READ_LOGS
adb shell pm grant com.omnidev.workspace android.permission.INTERACT_ACROSS_USERS_FULL
adb shell pm grant com.omnidev.workspace android.permission.BATTERY_STATS
adb shell pm grant com.omnidev.workspace android.permission.UPDATE_APP_OPS_STATS
adb shell pm grant com.omnidev.workspace android.permission.SET_TIME
```
