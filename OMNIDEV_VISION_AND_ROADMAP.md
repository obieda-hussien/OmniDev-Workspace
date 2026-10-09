# OmniDev Workspace future vision and roadmap

> **Historical proposals, September 29, 2026.** This document is not an implementation inventory, pricing commitment or revenue forecast. Current source declares five flavors (`lite/norm/pro/oem/admin`), four modes (`AUTO/CHAT/AGENT/SWARM`) and Room v17. See [README](README.md), [architecture](PROJECT_ARCHITECTURE.md) and [decision engine](DECISION_ENGINE.md) for implemented behavior. Prices, competitive comparisons and model-size targets below are unvalidated planning assumptions.

The proposed direction is an Android agent ecosystem for consumers, advanced users, developers and enterprise integrations. Feature availability must be verified against source, grants and hardware, rather than inferred from this roadmap.

## Proposed tier segmentation

### Lite: consumer assistant

Aim for broad distribution without root or Shizuku. Proposed additions include local ML Kit image analysis/OCR (`image_analyzer`, `text_recognizer`), standard `ACTION_VIEW`/`ACTION_MAIN` launching (`standard_intent_launcher`), consented contact/calendar lookup, weather APIs and small local translation models.

Sensitive-access requirements should use standard Android intents and Scoped Storage/SAF where appropriate. Local image processing is a proposed privacy benefit, not a claim that every image feature stays offline.

### Norm: scripting and advanced use

The original developer proposal included embedded JS/Kotlin scripting (`js_runtime`, `kotlin_script_runner`, JSR-223/Duktape) and static Gradle/build configuration analysis (`build_analyzer`). A bounded script environment would need explicit filesystem and API restrictions; an embedded engine alone does not establish a sandbox.

The later product proposal repositions Norm for advanced users: phone automation, intelligent file management, Telegram/Discord bots, semantic accessibility, scheduling, consented notification/message access and local GGUF models targeting up to 7B, without root/Shizuku. These are proposed entitlements, not current runtime limits.

### Pro: developer and security work

Proposed root/Shizuku capabilities need audit and reversal support because agent edits can be wrong. The historical action-record sketch was:

```kotlin
data class AgentAction(
    val timestamp: Long,
    val toolName: String,
    val affectedPaths: List<String>,
    val preSnapshot: String, // File state before modification
    val reversalCommand: String? // Command to restore the previous state
)
```

A proposed `agent_rollback` tool and `executeWithAudit` wrapper would capture local diffs/backups before modification and use confirmation for destructive operations. The actual rollback implementation has its own contracts and tool names; this sketch is not a source definition.

### OEM: enterprise integration

Potential system-app/MDM integration could include a remote management API, signed commands, device health reports and policy-controlled background automation:

```kotlin
interface OmniRemoteManagementAPI {
    suspend fun pushAgentConfig(config: RemoteAgentConfig)
    suspend fun pullDeviceHealthReport(): HealthSnapshot
    suspend fun emergencyWipe(authToken: String)
}
```

This is a proposal, including any wipe endpoint. Enterprise commands require authenticated channels, asymmetric identity/signatures and explicit administration policies. Background automation without repeated prompts still requires prior valid policy and authorization.

## Proposed architectural features

### Progressive trust

A historical proposal associates successful agent outcomes with a trust score and earned capabilities:

```kotlin
data class AgentTrustProfile(
    val userId: String,
    val trustScore: Float, // 0.0 to 1.0, informed by successful actions
    val earnedCapabilities: Set<String> // Capabilities earned over time
)
```

This could introduce gradual capability discovery. It cannot replace Android grants, explicit consent or tier enforcement.

### Redundant workers

The proposed fault-tolerant worker pool runs selected tasks with a redundancy factor of three and compares results. Majority agreement does not prove correctness or by itself implement Byzantine fault tolerance. It needs independent validation and a measured cost/benefit case. Current worker failure behavior should be read from `SwarmOrchestrator`, not inferred from this historical idea.

### Capability tokens

A hypothetical usage model assigns one token to ordinary web search and 50 to an advanced shell action, allowing small usage packs. These units are proposed billing credits, not model tokens or live prices.

### Context, sensors and protected memory

Potential additions include time-aware scheduling (for example, "Remind me tomorrow morning to finish the code"), consented location/motion signals for voice-oriented driving interactions, and protected personal memory. `EncryptedSharedPreferences` was a historical implementation suggestion; storage design must be evaluated before adoption.

## Product differentiation goals

The proposed advantages are developer workflows such as Git/logcat inspection, hardware-appropriate offline GGUF execution, scoped repository context and multi-agent work division. Claims about outperforming Gemini or other assistants require current comparative research and task benchmarks; this roadmap provides no such evidence.

## Proposed implementation stages

1. Stabilize local OCR/vision and auditable edits with rollback.
2. Evaluate bounded scripting and progressive trust while retaining consent and policy enforcement.
3. Design authenticated enterprise management and MDM integrations.

Every stage needs measured resource use, memory limits and reliable recovery on the intended Android devices.

## Historical pricing and business assumptions

| Proposed plan | Historical price assumption | Proposed value |
| --- | --- | --- |
| Lite | Free | Web search, OCR/vision, bounded vector memory, local models targeting up to 3B |
| Norm | $4.99/month or $39.99/year | Accessibility, scheduling, deeper research, memory and local models targeting up to 7B |
| Pro | $14.99/month or $99.99/year | Root/Shizuku, advanced files/security, audit/rollback, teams and hardware-dependent larger models targeting up to 70B |
| OEM | Custom; initial sketch $499/month base plus $49/additional device | Management API, policy-driven automation, optional white labeling and an SLA |

These values were motivated by acquisition and conversion hypotheses, not an established subscription system. Historical competitor price comparisons are not maintained here. Enterprise sales would require a separate sales/support process.

### Proposed feature comparison

| Capability | Lite | Norm | Pro | OEM |
| --- | --- | --- | --- | --- |
| ML Kit OCR/vision | Proposed | Proposed | Proposed | Proposed |
| Web search/scraping | Proposed | Proposed | Proposed | Proposed |
| Vector knowledge | Bounded | Full proposal | Full proposal | Full proposal |
| GGUF model target | Up to 3B | Up to 7B | Up to 70B | Hardware dependent |
| Accessibility and scheduling | Excluded in this proposal | Proposed | Proposed | Proposed |
| Root/Shizuku | Excluded | Excluded | Proposed | System integration |
| Advanced security/files/audit/rollback/teams | Excluded in this proposal | Excluded in this proposal | Proposed | Proposed |
| Remote management and policy-driven background automation | Excluded | Excluded | Excluded | Proposed |
| White labeling | Excluded | Excluded | Excluded | Optional |

This table records the historical business concept. It does not override `TierToolGate` or imply that implemented shared features are unavailable in a current flavor.

### Historical revenue illustration

For a hypothetical 100,000 active Lite users after one year:

- 7% Lite→Norm conversion: 7,000 × $4.99 = $34,930/month.
- 15% of those Norm users upgrading to Pro: 1,050 × $14.99 = $15,739.50/month before accounting for replaced Norm subscriptions.
- Five OEM base licenses: 5 × $499 = $2,495/month.

The original additive illustration was approximately $53,164/month, or $638,000/year. It omits upgrades replacing existing subscriptions, fees, taxes, churn, support, annual plans and bespoke OEM work, so it is not a financial forecast.

### Historical launch recommendation

Start with a stable Lite release and a proposed goal of 10,000 daily active users. Learn from feature usage and feedback, then assess Norm subscriptions and introduce Pro only after advanced workflows are reliable. The conversion target of 5–10% for Norm remains a hypothesis requiring validation.
