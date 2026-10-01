# Attribution and maintainer roles

**OmniDev Workspace developer and maintainer:**
[Abdelrahman Hussein — عبدالرحمن حسين / Obieda](https://github.com/obieda-hussien).

The maintainer's role covers original Workspace development, updates, modifications, and
interoperability with OmniLinkSDK, Omni Launcher, AndroidIDE and the wider Omni ecosystem.
New launcher-ingress and capability-policy work is attributed to the maintainer separately from
any upstream application, library or protocol implementation retained from another project.

## Preserve original authors

Original source headers, copyright statements, license texts, dependency credits and author
attributions must be retained wherever third-party material is included or modified.
A maintainer credit does not transfer ownership of upstream code or imply upstream endorsement.
Keep existing notices; mark new contributions and modifications separately.

Omni Launcher is derived from Lawnchair and AOSP Launcher3. Its inherited code remains credited
to those original authors under the licenses and per-file notices in that repository. Workspace
integration with the launcher does not make Workspace the author of the launcher foundation.
AndroidIDE and any other connected application similarly retain their own provenance and terms.

OmniLinkSDK has its own [LICENSE](https://github.com/obieda-hussien/OmniLinkSDK/blob/main/LICENSE)
and [NOTICE.md](https://github.com/obieda-hussien/OmniLinkSDK/blob/main/NOTICE.md).
Other dependencies retain their respective owners and licenses. This attribution document does
not introduce a new repository-wide license or relicense upstream/third-party material.

## Launcher integration scope

The proposal on feature/omni-launcher-integration adds public question draft ingress, singleTop
navigation, preservation of existing drafts/runs, a launcher capability ceiling and agent guidance.
Its checks are described in [docs/LAUNCHER_INTEGRATION.md](docs/LAUNCHER_INTEGRATION.md).
The Launcher side implements the app-owned settings/UI capabilities. The SDK supplies the existing
v3.0.0 protocol and trust primitives; there is no new SDK release or ABI change for this proposal.
