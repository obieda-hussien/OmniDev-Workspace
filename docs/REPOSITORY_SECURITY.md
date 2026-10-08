# Repository access and proprietary source protection

OmniDev Workspace is proprietary and intentionally hosted as a **public GitHub repository** at the owner's request. [LICENSE.md](../LICENSE.md) reserves rights in original material while preserving applicable third-party and platform permissions. [SECURITY.md](../SECURITY.md) addresses application vulnerability reports; this document addresses the repository itself.

## What public hosting permits

Anyone who can reach the public repository can read published files and obtain copies by Git clone, archive download or GitHub fork. GitHub's [Terms of Service](https://docs.github.com/en/site-policy/github-terms/github-terms-of-service) include viewing and on-platform forking permissions for public content. An ownership notice cannot disable those platform rights. There is no public-repository setting that makes the complete published source unreadable to others.

Public source visibility does not grant an additional general open-source license. Reuse, redistribution, resale and relicensing of original material require an applicable permission. Separately licensed libraries retain their own grants. Copyright/permissions govern allowed uses; they are not technical copy prevention. Copies already obtained cannot be recalled through upstream settings.

Public Actions logs, artifacts, comments, releases and source history must be treated as published information. Never put credentials, signing material, user data or confidential configuration in them. Masking known secrets cannot make an arbitrary diagnostic safe to publish.

## Owner-controlled development

The owner is `obieda-hussien`. At the settings review on **8 October 2026**, GitHub listed **zero invited collaborator accounts**. Installed applications have a separate authorization channel. Their actual repository installation permissions, account authorization and GitHub policies determine what they can do.

Names such as ChatGPT, Codex, Claude, Gemini, Jules and Copilot are development credits, not GitHub access-control identities. Do not grant an account access because it resembles an assistant name. A bot author, commit email, PR label or familiar display name is not an authenticated authorization boundary.

Existing installed integrations were preserved. No collaborators were added and no new application privileges or bypass permissions were granted. The installed-app inventory includes development and service integrations beyond the assistants named in the credits; the owner must review their scope and revoke an integration if it is no longer intended. An installed write-capable application is part of the trusted computing base, even if it is not an invited human collaborator.

## Applied GitHub controls

The following settings were applied and read back on **8 October 2026**. They are live GitHub configuration; merging a Markdown file does not enforce them.

| Control | Setting | Effect and boundary |
| --- | --- | --- |
| Visibility | Public | Source remains visible and copyable |
| Private vulnerability reporting | Enabled | External reporters can submit a private security report instead of exposing an exploit in the tracker |
| Secret scanning | Enabled | GitHub scans for supported secret patterns; it does not recognize every possible credential |
| Push protection | Enabled | Blocks detected supported secrets on covered pushes; legitimate bypass mechanisms and detection limits still exist |
| Dependency security alerts and updates | Enabled, existing settings preserved | Dependabot alerts and security updates remain available; this does not mean every dependency is vulnerability-free |
| New pull requests | Collaborators only | Blocks unsolicited ordinary-user PR creation; the owner and permitted integrations use their actual GitHub authorization |
| New issues | Collaborators only | Keeps the tracker for authorized development |
| Commit comments | Disabled | Stops new comments on individual commits; existing comments are preserved |
| Code review approvals/change requests | Explicit repository access required | Public readers cannot submit approving or blocking reviews; comment-only review remains subject to interaction controls |
| Repository interactions | Collaborators only, six months | Temporary GitHub moderation limit; renew it when it expires |
| Wiki editing | Collaborators only | Public readers cannot edit the wiki |
| Archive Program | Opted out | Declines this optional preservation setting; does not block clones, caches or existing copies |
| Actions sources | Owner actions, GitHub-created actions, `android-actions/setup-android@*`, `gradle/actions/setup-gradle@*` | Limits workflow action sources; this is separate from the installed AI application list |
| Action revisions | Full-length commit SHA required | Rejects mutable action tags/branches where this policy applies |
| External fork workflows | Approval required for all external contributors | Adds a repository-level gate before external PR workflows can run |
| Default workflow token | Read repository contents/packages | Avoids an ambient writable `GITHUB_TOKEN`; explicit YAML permissions still matter |
| Actions PR creation/approval | Disabled | CI tokens do not create/approve their own PRs; installed integrations have separate credentials/permissions |
| Default-branch ruleset | `OmniDev main integrity`, Active | Applies to current default branch `main` |
| Branch deletion and force pushes | Blocked; bypass list empty | Protects the default branch against deletion/history rewriting through normal pushes |
| Branch changes | Pull request required | No direct push route to the protected branch |
| Required security check | `Secret history and workflow hardening`, GitHub Actions source | The quick current-tree/workflow/history guard must pass before merging |
| Review discussions | Resolution required before merge | Existing unresolved review conversations block merging |

The branch ruleset uses **zero required approving reviews** because the owner is currently the only human maintainer and cannot approve a PR authored as that same account. This does not grant anyone write access. It retains the owner's automated PR/merge workflow while requiring a PR record and the quick security gate. It does not claim a second-person review. Full Android CI is not a required branch check; its results must still be assessed for application changes. A repository administrator can manage rulesets; rules are not a defense against an already compromised owner account.

The interaction restriction is temporary: GitHub reports six months remaining from the 8 October 2026 review. It must be renewed at expiry. The separate collaborators-only PR/issue creation policies persist until changed. This task does not install a privileged auto-renewal bot or create new credentials.

## Workflow defense in depth

The Android pipeline's root quality job runs for this upstream repository only, and PR builds require the head branch to belong to the same repository. A public-fork PR is skipped even if someone previously contributed. Downstream Android build jobs depend on the root job. The security-gate workflow uses the same upstream/same-repository constraint.

This constraint trusts actual repository write authorization, not a list of user-controlled names. Authorized integrations should use branches inside this repository. An integration that only submits fork branches must be adapted by the owner; it is not silently exempted by an assistant name.

Workflows retain read-only contents permissions, pinned actions and checkouts with `persist-credentials: false`. Admin signing/Telegram delivery remains scoped to the trusted `main` push/manual-dispatch path and its existing environment. PRs do not receive its environment secrets. The current guard scans high-confidence credential patterns and sensitive filenames in the tree and full history; it is not a proof that every possible secret format is absent.

## Maintenance and incident response

- Keep account two-factor authentication, recovery methods and device access under the owner's control. Repository configuration does not verify or change those personal-account settings.
- Review installed apps, OAuth authorizations, deploy keys, webhooks and environment access. Keep scopes narrow and remove only integrations the owner no longer intends to authorize.
- When introducing a new action, review the source, pin its full commit and deliberately update the repository allowlist if needed. Do not restore “allow all” to bypass an unexpected CI failure.
- Before merging, inspect the actual head commit, security result and relevant application validation. Do not trust a label or a posted “tests passed” comment as a status check.
- If a real secret was exposed, revoke/rotate it through the owning service. Deleting its current file is insufficient because history/copies may retain it. Never post the secret in an issue or a remediation log.
- Keep attribution and prior licensed material intact. If misuse is suspected, preserve precise evidence and consult the applicable permission terms before making a rights claim; this document does not automatically send legal complaints or takedown requests.

Run the versioned guard locally:

```sh
python3 scripts/public_repo_guard.py
python3 scripts/public_repo_guard.py --history
```

See [authorized development](../CONTRIBUTING.md), [repository maintenance](REPOSITORY_MAINTENANCE.md) and [reproducible statistics](REPOSITORY_STATS.md).
