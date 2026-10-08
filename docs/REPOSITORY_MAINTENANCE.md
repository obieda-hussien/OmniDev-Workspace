# Repository identity, topics and labels

The intended public identity is **OmniDev Workspace**. The GitHub repository path remains `obieda-hussien/OmniDev-Workspace` so established links and integrations keep their current destination.

## Versioned metadata

[`.github/repository-metadata.json`](../.github/repository-metadata.json) records the product title, About description, documentation homepage, topics and issue-label definitions. File changes are reviewable in a PR, but GitHub settings are separate: merging this file does not automatically update the About panel or labels.

The description focuses on implemented Android conversation, agents, local context, learned tasks, voice, media and interoperability. Topics should help people discover the project; do not use topics as claims of secure voice biometrics, universal system access, measured superiority or a license that has not been adopted.

GitHub has a repository name and description, but no separate product-title setting. The README heading carries the product title; this maintenance configuration does not rename the repository.

## Preview and apply

The maintenance script defaults to a local preview and requires no network/authentication for that preview:

```sh
python3 scripts/sync_repo_metadata.py
```

To apply using an installed, authenticated GitHub CLI with the necessary repository administration/issue permissions:

```sh
python3 scripts/sync_repo_metadata.py --apply
```

The script targets only the repository named in the manifest. It updates description/homepage and replaces topics with the declared topic set. It creates missing labels and updates the declared labels' colors/descriptions. It does not delete unrelated labels, rename existing labels, change visibility, rename the repository, modify collaborators/branch protection or configure secrets.

Existing topic values not in the manifest are removed when applying the complete topic set. Review the preview and current repository state before applying. Auth/API errors stop the script; there is no anonymous-write or browser-login fallback.

The apply route relies on `gh` authentication outside the repository. It does not read, print or store token values. Use the least permission set that supports these settings and keep credentials out of Git.

## Label taxonomy

| Group | Intent |
|---|---|
| `bug`, `enhancement`, `documentation`, `question` | Issue/PR type |
| `good first issue`, `help wanted`, `dependencies` | Contribution/dependency workflow |
| `priority:p0` through `priority:p3` | Maintainer-assessed urgency |
| `status:needs-triage`, `status:needs-reproduction`, `status:blocked` | Current intake/action state |
| `area:*` | Affected subsystem, such as agent, voice, media, UI, access, OmniLink or build |

Priority labels are triage decisions, not an automatic severity guarantee. Public `area:security` issues should describe safe maintenance work; vulnerabilities needing private handling belong to the route in [SECURITY.md](../SECURITY.md).

The issue forms use the standard `bug`, `enhancement` and `documentation` labels. Apply subsystem/priority labels after examining the report. Existing GitHub default labels can remain even when not managed by this manifest.

## Community files

The English README links to contribution, support, privacy, licensing, notices, development history and security guidance. Issue forms collect Android-specific evidence and the PR template asks for concrete behavior/validation.

Private vulnerability reporting is a GitHub setting independent of `SECURITY.md`. Verify or enable it through an authorized repository administration route before announcing that the private form is available. The policy includes a safe fallback when it is not enabled.

## Verification

```sh
python3 scripts/check_docs.py
python3 scripts/sync_repo_metadata.py
python3 scripts/public_repo_guard.py
```

After applying external settings, read them back in GitHub and verify the exact description/homepage/topic set and each declared label. Keep successful file validation separate from successful settings application in the change report.
