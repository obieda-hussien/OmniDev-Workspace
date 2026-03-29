package com.omnidev.workspace.data.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.util.zip.ZipFile

/**
 * EnhancedAppManifestAnalyzerTool — Adds additional inspection helpers to the existing
 * AppManifestAnalyzerTool without modifying it directly. Safe-to-add companion: it
 * focuses on signing fingerprints, APK-native library enumeration, debug-signing
 * heuristics, expanded intent resolver (services/receivers), and a tiny cache.
 */
object EnhancedAppManifestAnalyzerTool {

    private const val CACHE_FILE = "manifest_analyzer_cache.json"
    private const val CACHE_MAX_AGE_MS = 1000L * 60L * 60L // 1 hour

    data class SignatureInfo(
        val sha1: String?,
        val sha256: String?,
        val isDebugSigned: Boolean
    )

    // Public helper: get signatures and debug key heuristic
    fun getSigningInfo(context: Context, pkg: String): SignatureInfo? {
        val pm = context.packageManager
        val pi = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
            }
        } catch (e: Exception) {
            return null
        }

        try {
            val certFactory = CertificateFactory.getInstance("X.509")
            val signerBytes = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && pi.signingInfo != null -> {
                    // prefer apkContentsSigners
                    pi.signingInfo?.apkContentsSigners?.map { it.toByteArray() }
                }
                else -> {
                    @Suppress("DEPRECATION")
                    pi.signatures?.map { it.toByteArray() }
                }
            } ?: emptyList()

            var sha1: String? = null
            var sha256: String? = null
            var debug = false

            signerBytes.forEach { sb ->
                try {
                    val cert = certFactory.generateCertificate(sb.inputStream())
                    val encoded = cert.encoded

                    val d1 = MessageDigest.getInstance("SHA-1").digest(encoded)
                    val d2 = MessageDigest.getInstance("SHA-256").digest(encoded)

                    sha1 = d1.joinToString(":") { String.format("%02X", it) }
                    sha256 = d2.joinToString(":") { String.format("%02X", it) }

                    // Heuristic: debug key subjects often contain "Android Debug"
                    val subj = try {
                        val x509 = cert as java.security.cert.X509Certificate
                        x509.subjectX500Principal.name
                    } catch (_: Exception) { "" }

                    if (subj.contains("Android Debug", ignoreCase = true) || subj.contains("CN=Android Debug", ignoreCase = true)) {
                        debug = true
                    }
                } catch (_: Exception) {
                    // continue
                }
            }

            return SignatureInfo(sha1, sha256, debug)
        } catch (e: Exception) {
            return null
        }
    }

    // Public helper: list native libraries by opening the APK as a zip
    fun listNativeLibraries(packageInfo: PackageInfo): List<String> {
        val appInfo = packageInfo.applicationInfo ?: return emptyList()
        val src = appInfo.sourceDir ?: return emptyList()
        return try {
            val zip = ZipFile(src)
            val entries = zip.entries().asSequence()
            val libs = entries.filter { !it.isDirectory && it.name.endsWith(".so") }
                .map { File(it.name).name }
                .distinct()
                .toList()
            zip.close()
            libs
        } catch (e: Exception) {
            emptyList()
        }
    }

    // Public helper: APK metadata quick info
    fun getApkMetadata(packageInfo: PackageInfo): JSONObject {
        val obj = JSONObject()
        val appInfo = packageInfo.applicationInfo
        obj.put("apkPath", appInfo?.sourceDir ?: JSONObject.NULL)
        try {
            val apkFile = appInfo?.sourceDir?.let { File(it) }
            if (apkFile != null && apkFile.exists()) {
                obj.put("apkSizeBytes", apkFile.length())
                obj.put("lastModified", apkFile.lastModified())
            }
        } catch (_: Exception) {}
        obj.put("nativeLibs", JSONArray(listNativeLibraries(packageInfo)))
        return obj
    }

    // Find other installed packages signed with the same certificate (sha256 normalized) — useful to
    // identify vendor suites, companion apps, or SDK bundles that share the signer.
    fun listAppsSignedBySameCert(context: Context, pkg: String): List<String> {
        val pm = context.packageManager
        val targetSig = getSigningInfo(context, pkg)?.sha256?.replace(":", "")?.lowercase()
            ?: return emptyList()

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else PackageManager.GET_SIGNATURES

        val installed = try {
            pm.getInstalledPackages(flags)
        } catch (e: Exception) { return emptyList() }

        val cf = try { CertificateFactory.getInstance("X.509") } catch (e: Exception) { return emptyList() }
        val md = try { MessageDigest.getInstance("SHA-256") } catch (e: Exception) { return emptyList() }

        val matches = mutableListOf<String>()
        installed.forEach { pi ->
            try {
                val otherPkg = pi.packageName
                val signerBytes = when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && pi.signingInfo != null -> pi.signingInfo?.apkContentsSigners?.map { it.toByteArray() }
                    else -> @Suppress("DEPRECATION") pi.signatures?.map { it.toByteArray() }
                } ?: emptyList()

                signerBytes.forEach { sb ->
                    try {
                        val cert = cf.generateCertificate(sb.inputStream())
                        val d = md.digest(cert.encoded)
                        val hex = d.joinToString("") { String.format("%02x", it) }
                        if (hex == targetSig && otherPkg != pkg) {
                            matches.add(otherPkg)
                        }
                    } catch (_: Exception) { /* continue */ }
                }
            } catch (_: Exception) { /* continue */ }
        }

        return matches.distinct()
    }

    // Extended intent resolver: activities + services + receivers
    fun resolveIntentAll(context: Context, action: String? = null, uri: String? = null, mimeType: String? = null): JSONObject {
        val pm = context.packageManager
        val act = action ?: Intent.ACTION_VIEW
        val intent = Intent(act).apply {
            uri?.let { data = Uri.parse(it) }
            mimeType?.let { type = it }
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PackageManager.ResolveInfoFlags.of((PackageManager.GET_RESOLVED_FILTER or PackageManager.MATCH_ALL).toLong())
        } else 0

        val root = JSONObject()
        root.put("action", act)
        uri?.let { root.put("uri", it) }
        mimeType?.let { root.put("mimeType", it) }

        // Activities
        val actList = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.GET_RESOLVED_FILTER.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, PackageManager.GET_RESOLVED_FILTER)
            }
        } catch (e: Exception) { emptyList() }

        val actsJson = JSONArray()
        actList.forEach { ri ->
            val ai = ri.activityInfo
            val o = JSONObject()
            o.put("package", ai?.packageName)
            o.put("activity", ai?.name)
            o.put("priority", ri.priority)
            actsJson.put(o)
        }
        root.put("activities", actsJson)

        // Services
        val svcList = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(PackageManager.GET_RESOLVED_FILTER.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentServices(intent, PackageManager.GET_RESOLVED_FILTER)
            }
        } catch (e: Exception) { emptyList() }

        val svcsJson = JSONArray()
        svcList.forEach { ri ->
            val si = ri.serviceInfo
            val o = JSONObject()
            o.put("package", si?.packageName)
            o.put("service", si?.name)
            o.put("priority", ri.priority)
            svcsJson.put(o)
        }
        root.put("services", svcsJson)

        // Broadcast receivers
        val rcvList = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryBroadcastReceivers(intent, PackageManager.ResolveInfoFlags.of(PackageManager.GET_RESOLVED_FILTER.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.queryBroadcastReceivers(intent, PackageManager.GET_RESOLVED_FILTER)
            }
        } catch (e: Exception) { emptyList() }

        val rcvsJson = JSONArray()
        rcvList.forEach { ri ->
            val r = ri.activityInfo
            val o = JSONObject()
            o.put("package", r?.packageName)
            o.put("receiver", r?.name)
            o.put("priority", ri.priority)
            rcvsJson.put(o)
        }
        root.put("receivers", rcvsJson)

        return root
    }

    // Simple on-disk cache helpers
    private fun readCache(context: Context): JSONObject {
        return try {
            val f = File(context.cacheDir, CACHE_FILE)
            if (!f.exists()) return JSONObject()
            val text = f.readText()
            JSONObject(text)
        } catch (e: Exception) { JSONObject() }
    }

    private fun writeCache(context: Context, obj: JSONObject) {
        try {
            val f = File(context.cacheDir, CACHE_FILE)
            f.writeText(obj.toString())
        } catch (_: Exception) {}
    }

    fun getCachedAnalysis(context: Context, pkg: String): JSONObject? {
        try {
            val root = readCache(context)
            val entry = root.optJSONObject(pkg) ?: return null
            val ts = entry.optLong("ts", 0L)
            if (System.currentTimeMillis() - ts > CACHE_MAX_AGE_MS) {
                return null
            }
            return entry.optJSONObject("data")
        } catch (_: Exception) { return null }
    }

    fun putCachedAnalysis(context: Context, pkg: String, data: JSONObject) {
        try {
            val root = readCache(context)
            val entry = JSONObject()
            entry.put("ts", System.currentTimeMillis())
            entry.put("data", data)
            root.put(pkg, entry)
            writeCache(context, root)
        } catch (_: Exception) {}
    }

    // High-level convenience: build enhanced JSON summary (merges with existing analyzer output if desired)
    fun buildEnhancedJson(context: Context, packageInfo: PackageInfo): JSONObject {
        val pm = context.packageManager
        val pkg = packageInfo.packageName
        val root = JSONObject()
        root.put("package", pkg)
        root.put("label", packageInfo.applicationInfo?.let { pm.getApplicationLabel(it).toString() } ?: "?")
        root.put("versionName", packageInfo.versionName ?: "?")
        root.put("versionCode", packageInfo.longVersionCode)

        // signatures
        try {
            val sig = getSigningInfo(context, pkg)
            val sigObj = JSONObject()
            sigObj.put("sha1", sig?.sha1)
            sigObj.put("sha256", sig?.sha256)
            sigObj.put("isDebugSigned", sig?.isDebugSigned ?: false)
            root.put("signatures", sigObj)
        } catch (_: Exception) {}

        // apk metadata
        try { root.put("apkMetadata", getApkMetadata(packageInfo)) } catch (_: Exception) {}

        // quick exported counts
        try {
            val exportedActivities = packageInfo.activities?.count { it.exported } ?: 0
            val exportedServices = packageInfo.services?.count { it.exported } ?: 0
            val exportedReceivers = packageInfo.receivers?.count { it.exported } ?: 0
            val exportedProviders = packageInfo.providers?.count { it.exported } ?: 0
            root.put("exportedCounts", JSONObject().apply {
                put("activities", exportedActivities)
                put("services", exportedServices)
                put("receivers", exportedReceivers)
                put("providers", exportedProviders)
            })
        } catch (_: Exception) {}

        return root
    }

    // Export a single-file HTML report to cacheDir and return its absolute path
    fun exportEnhancedReportToHtml(context: Context, packageInfo: PackageInfo): String? {
        return try {
            val pkg = packageInfo.packageName
            val data = buildEnhancedJson(context, packageInfo)

            val sb = StringBuilder()
            sb.append("<!doctype html><html><head><meta charset=\"utf-8\"><title>Manifest Report - $pkg</title>")
            sb.append("<style>body{font-family:Arial,Helvetica,sans-serif;margin:20px;color:#111}h1{font-size:20px}pre{background:#f6f8fa;padding:12px;border-radius:6px;overflow:auto}table{border-collapse:collapse;width:100%;margin-bottom:12px}th,td{border:1px solid #ddd;padding:8px;text-align:left}th{background:#f2f2f2}</style></head><body>")
            sb.append("<h1>Manifest Analysis: ${pkg}</h1>")

            val label = data.optString("label", "?")
            val version = data.optString("versionName", "?")
            val vcode = data.optLong("versionCode", -1)
            sb.append("<p><strong>Label:</strong> ${label} &nbsp; <strong>Version:</strong> ${version} (${vcode})</p>")

            // Signatures
            val sig = data.optJSONObject("signatures")
            if (sig != null) {
                sb.append("<h2>Signatures</h2>")
                sb.append("<table><tr><th>SHA-1</th><th>SHA-256</th><th>Debug-signed</th></tr>")
                sb.append("<tr><td>${sig.optString("sha1", "-")}</td><td>${sig.optString("sha256", "-")}</td><td>${sig.optBoolean("isDebugSigned", false)}</td></tr>")
                sb.append("</table>")
            }

            // APK metadata
            val apkMeta = data.optJSONObject("apkMetadata")
            if (apkMeta != null) {
                sb.append("<h2>APK Metadata</h2>")
                sb.append("<table>")
                sb.append("<tr><th>Path</th><td>${apkMeta.optString("apkPath", "-")}</td></tr>")
                sb.append("<tr><th>Size (bytes)</th><td>${apkMeta.optLong("apkSizeBytes", 0)}</td></tr>")
                sb.append("</table>")

                val libs = apkMeta.optJSONArray("nativeLibs")
                if (libs != null && libs.length() > 0) {
                    sb.append("<h3>Native Libraries</h3>")
                    sb.append("<pre>")
                    for (i in 0 until libs.length()) { sb.append(libs.optString(i)).append('\n') }
                    sb.append("</pre>")
                }
            }

            // Exported counts
            val ec = data.optJSONObject("exportedCounts")
            if (ec != null) {
                sb.append("<h2>Exported Components</h2>")
                sb.append("<table><tr><th>Type</th><th>Count</th></tr>")
                sb.append("<tr><td>Activities</td><td>${ec.optInt("activities",0)}</td></tr>")
                sb.append("<tr><td>Services</td><td>${ec.optInt("services",0)}</td></tr>")
                sb.append("<tr><td>Receivers</td><td>${ec.optInt("receivers",0)}</td></tr>")
                sb.append("<tr><td>Providers</td><td>${ec.optInt("providers",0)}</td></tr>")
                sb.append("</table>")
            }

            // Raw JSON for advanced users
            sb.append("<h2>Raw JSON</h2>")
            sb.append("<pre>")
            sb.append(data.toString(2).replace("<","&lt;").replace(">","&gt;"))
            sb.append("</pre>")

            sb.append("</body></html>")

            val outFile = File(context.cacheDir, "${pkg}_manifest_report.html")
            outFile.writeText(sb.toString())
            outFile.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    // Parse networkSecurityConfig XML files packaged inside the APK (res/xml/*network*)
    // Returns JSON with raw XML snippets and simple heuristics (cleartext allowed, trust-anchors found)
    fun parseNetworkSecurityConfig(packageInfo: PackageInfo): JSONObject {
        val obj = JSONObject()
        try {
            val appInfo = packageInfo.applicationInfo ?: return obj
            val src = appInfo.sourceDir ?: return obj
            val zip = ZipFile(src)
            val entries = zip.entries().asSequence().filter { it.name.startsWith("res/xml/") && it.name.contains("network", ignoreCase = true) }
            val arr = JSONArray()
            entries.forEach { e ->
                try {
                    val ins = zip.getInputStream(e)
                    val txt = ins.readBytes().toString(Charsets.UTF_8)
                    ins.close()
                    val item = JSONObject()
                    item.put("path", e.name)
                    item.put("content", txt)
                    item.put("containsCleartext", txt.contains("cleartextTrafficPermitted", true) || txt.contains("cleartext-traffic", true))
                    item.put("containsTrustAnchors", txt.contains("trust-anchors", true) || txt.contains("trust_anchor", true))
                    arr.put(item)
                } catch (_: Exception) { /* continue */ }
            }
            zip.close()
            obj.put("configs", arr)
            if (arr.length() > 0) {
                val first = arr.getJSONObject(0).optString("content","")
                obj.put("cleartextTrafficDetected", first.contains("cleartextTrafficPermitted", true) || first.contains("cleartext-traffic", true))
            }
        } catch (_: Exception) { }
        return obj
    }

    // Build an attack-surface JSON: lists exported components, bare exports (no permission), and quick commands
    fun buildAttackSurface(context: Context, packageInfo: PackageInfo): JSONObject {
        val root = JSONObject()
        val pkg = packageInfo.packageName
        try {
            val exportedActs = JSONArray()
            packageInfo.activities?.filter { it.exported }?.forEach { a ->
                val comp = a as android.content.pm.ComponentInfo
                val o = JSONObject()
                o.put("name", comp.name)
                o.put("permission", comp.permission)
                o.put("bareExport", comp.exported && comp.permission == null)
                o.put("amStart", "am start -n $pkg/${comp.name}")
                exportedActs.put(o)
            }
            root.put("exportedActivities", exportedActs)
        } catch (_: Exception) {}

        try {
            val exportedSvcs = JSONArray()
            packageInfo.services?.filter { it.exported }?.forEach { s ->
                val comp = s as android.content.pm.ComponentInfo
                val o = JSONObject()
                o.put("name", comp.name)
                o.put("permission", comp.permission)
                o.put("bareExport", comp.exported && comp.permission == null)
                o.put("amStartService", "am startservice -n $pkg/${comp.name}")
                exportedSvcs.put(o)
            }
            root.put("exportedServices", exportedSvcs)
        } catch (_: Exception) {}

        try {
            val exportedRcvs = JSONArray()
            packageInfo.receivers?.filter { it.exported }?.forEach { r ->
                val comp = r as android.content.pm.ComponentInfo
                val o = JSONObject()
                o.put("name", comp.name)
                o.put("permission", comp.permission)
                o.put("bareExport", comp.exported && comp.permission == null)
                o.put("amBroadcast", "am broadcast -n $pkg/${comp.name} -a <ACTION>")
                exportedRcvs.put(o)
            }
            root.put("exportedReceivers", exportedRcvs)
        } catch (_: Exception) {}

        try {
            val exportedPrv = JSONArray()
            packageInfo.providers?.filter { it.exported }?.forEach { p ->
                val o = JSONObject()
                o.put("name", p.name)
                o.put("authority", p.authority)
                o.put("permission", p.readPermission ?: p.writePermission ?: p.permission)
                o.put("bareExport", p.exported && p.readPermission == null && p.writePermission == null && p.permission == null)
                o.put("queryCmd", p.authority?.split(";")?.joinToString("\n") { auth -> "content query --uri content://$auth/" })
                exportedPrv.put(o)
            }
            root.put("exportedProviders", exportedPrv)
        } catch (_: Exception) {}

        // Summary counts
        try {
            root.put("counts", JSONObject().apply {
                put("activities", packageInfo.activities?.count { it.exported } ?: 0)
                put("services", packageInfo.services?.count { it.exported } ?: 0)
                put("receivers", packageInfo.receivers?.count { it.exported } ?: 0)
                put("providers", packageInfo.providers?.count { it.exported } ?: 0)
            })
        } catch (_: Exception) {}

        return root
    }

}
