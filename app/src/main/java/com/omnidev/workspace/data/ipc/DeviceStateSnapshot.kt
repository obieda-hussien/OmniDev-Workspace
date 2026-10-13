package com.omnidev.workspace.data.ipc

data class DeviceStateSnapshot(
    val buildFingerprint: String,
    val sdkInt: Int,
    val model: String,
    val androidVersion: String,
    val shizukuReady: Boolean,
    val rishAvailable: Boolean,
    val rootAvailable: Boolean,
    val foregroundPackage: String,
    val batteryLevel: Int,
    val totalRamMb: Long
) {
    fun toJson(): String = buildString {
        append("{")
        append("\"buildFingerprint\":\"${buildFingerprint.jsonEscape()}\",")
        append("\"sdkInt\":$sdkInt,")
        append("\"model\":\"${model.jsonEscape()}\",")
        append("\"androidVersion\":\"${androidVersion.jsonEscape()}\",")
        append("\"shizukuReady\":$shizukuReady,")
        append("\"rishAvailable\":$rishAvailable,")
        append("\"rootAvailable\":$rootAvailable,")
        append("\"foregroundPackage\":\"${foregroundPackage.jsonEscape()}\",")
        append("\"batteryLevel\":$batteryLevel,")
        append("\"totalRamMb\":$totalRamMb")
        append("}")
    }

    private fun String.jsonEscape(): String = this
        .replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
}
