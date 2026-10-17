# Repository statistics

Snapshot commit: `928b7ed285cf91036787ab5f94eda663681fb1bf`.

| Metric | Count |
| --- | ---: |
| Tracked files | 902 |
| UTF-8 text files | 890 |
| Binary/non-UTF-8 files | 12 |
| Symbolic links | 0 |
| Total tracked blob bytes | 7,238,770 |
| Physical text lines | 137,266 |
| Nonblank text lines | 123,701 |
| Source/script files | 755 |
| Source/script physical lines | 127,769 |
| App Kotlin files (including tests) | 707 |
| App Kotlin physical lines (including tests) | 123,352 |
| Test source files | 177 |
| Test source physical lines | 14,075 |

## Text breakdown

| Format | Files | Physical lines | Nonblank lines | Bytes |
| --- | ---: | ---: | ---: | ---: |
| AIDL | 6 | 92 | 80 | 3,347 |
| C++ | 2 | 680 | 582 | 29,745 |
| JSON | 5 | 440 | 440 | 17,533 |
| Java | 2 | 72 | 67 | 3,401 |
| JavaScript | 3 | 284 | 268 | 12,552 |
| Kotlin | 710 | 123,515 | 112,524 | 6,119,961 |
| Kotlin build scripts | 5 | 667 | 609 | 30,741 |
| Markdown | 71 | 5,786 | 4,004 | 613,400 |
| Other text | 20 | 691 | 593 | 29,332 |
| Properties | 2 | 37 | 32 | 1,724 |
| Python | 19 | 1,976 | 1,722 | 89,736 |
| Shell | 8 | 483 | 419 | 18,721 |
| TOML | 1 | 80 | 75 | 5,082 |
| XML | 26 | 1,124 | 1,100 | 83,616 |
| YAML | 10 | 1,339 | 1,186 | 50,388 |

## Method and scope

Counts come from committed Git blobs at the exact snapshot above, not the working directory. Untracked files, build output, downloaded models, caches and Git history are excluded. Tracked binary assets are counted as files/bytes but have no line count. Symlinks are counted separately and not followed. Git submodule contents are excluded.

Physical lines include comments and blank lines, with a final unterminated line counted. Nonblank lines include comments. These are not parser-derived statements or comment-free SLOC. Source/script totals cover Kotlin, Kotlin scripts, Java, Python, shell, JavaScript, C/C++, headers and AIDL; XML/resources, data and documentation are reported separately. Test counts overlap source totals and include flavor-specific roots such as testAdvanced, testAgent and testTeam.

This report remains tied to the exact commit above; it is not a live size badge. Rerun the command for a newer commit when an updated count is needed.

Reproduce this snapshot:

```sh
python3 scripts/repository_stats.py --ref 928b7ed285cf91036787ab5f94eda663681fb1bf --format markdown
```

Count the currently checked-out commit:

```sh
python3 scripts/repository_stats.py --format json
```
