# Repository statistics

Snapshot commit: `07328faa8a066a77727343a45fce5b7e25b545cb`.

| Metric | Count |
| --- | ---: |
| Tracked files | 893 |
| UTF-8 text files | 881 |
| Binary/non-UTF-8 files | 12 |
| Symbolic links | 0 |
| Total tracked blob bytes | 7,153,149 |
| Physical text lines | 136,140 |
| Nonblank text lines | 122,672 |
| Source/script files | 747 |
| Source/script physical lines | 126,802 |
| App Kotlin files (including tests) | 700 |
| App Kotlin physical lines (including tests) | 122,425 |
| Test source files | 173 |
| Test source physical lines | 13,588 |

## Text breakdown

| Format | Files | Physical lines | Nonblank lines | Bytes |
| --- | ---: | ---: | ---: | ---: |
| AIDL | 6 | 92 | 80 | 3,347 |
| C++ | 2 | 680 | 582 | 29,745 |
| JSON | 5 | 436 | 436 | 17,243 |
| Java | 2 | 72 | 67 | 3,401 |
| JavaScript | 3 | 284 | 268 | 12,552 |
| Kotlin | 703 | 122,588 | 111,657 | 6,056,562 |
| Kotlin build scripts | 5 | 667 | 609 | 30,741 |
| Markdown | 70 | 5,635 | 3,887 | 594,011 |
| Other text | 20 | 691 | 593 | 29,332 |
| Properties | 2 | 37 | 32 | 1,724 |
| Python | 18 | 1,936 | 1,683 | 87,317 |
| Shell | 8 | 483 | 419 | 18,721 |
| TOML | 1 | 80 | 75 | 5,082 |
| XML | 26 | 1,123 | 1,100 | 83,615 |
| YAML | 10 | 1,336 | 1,184 | 50,265 |

## Method and scope

Counts come from committed Git blobs at the exact snapshot above, not the working directory. Untracked files, build output, downloaded models, caches and Git history are excluded. Tracked binary assets are counted as files/bytes but have no line count. Symlinks are counted separately and not followed. Git submodule contents are excluded.

Physical lines include comments and blank lines, with a final unterminated line counted. Nonblank lines include comments. These are not parser-derived statements or comment-free SLOC. Source/script totals cover Kotlin, Kotlin scripts, Java, Python, shell, JavaScript, C/C++, headers and AIDL; XML/resources, data and documentation are reported separately. Test counts overlap source totals and include flavor-specific roots such as testAdvanced, testAgent and testTeam.

This report remains tied to the exact commit above; it is not a live size badge. Rerun the command for a newer commit when an updated count is needed.

Reproduce this snapshot:

```sh
python3 scripts/repository_stats.py --ref 07328faa8a066a77727343a45fce5b7e25b545cb --format markdown
```

Count the currently checked-out commit:

```sh
python3 scripts/repository_stats.py --format json
```
