# Repository statistics

Snapshot commit: `2e36a4d6939b32380e2b87cac989138ee002e918`.

| Metric | Count |
| --- | ---: |
| Tracked files | 753 |
| UTF-8 text files | 742 |
| Binary/non-UTF-8 files | 11 |
| Symbolic links | 0 |
| Total tracked blob bytes | 6,552,619 |
| Physical text lines | 127,290 |
| Nonblank text lines | 114,403 |
| Source/script files | 625 |
| Source/script physical lines | 118,956 |
| App Kotlin files (including tests) | 599 |
| App Kotlin physical lines (including tests) | 115,700 |
| Test source files | 142 |
| Test source physical lines | 10,715 |

## Text breakdown

| Format | Files | Physical lines | Nonblank lines | Bytes |
| --- | ---: | ---: | ---: | ---: |
| C++ | 2 | 680 | 582 | 29,745 |
| JSON | 4 | 326 | 326 | 9,117 |
| Java | 1 | 8 | 8 | 353 |
| JavaScript | 3 | 284 | 268 | 12,552 |
| Kotlin | 599 | 115,700 | 105,116 | 5,649,293 |
| Kotlin build scripts | 3 | 526 | 471 | 23,323 |
| Markdown | 57 | 4,736 | 3,221 | 510,150 |
| Other text | 21 | 691 | 603 | 28,419 |
| Properties | 2 | 37 | 32 | 1,724 |
| Python | 10 | 1,303 | 1,126 | 54,404 |
| Shell | 7 | 455 | 391 | 17,267 |
| TOML | 1 | 89 | 84 | 5,859 |
| XML | 22 | 1,190 | 1,053 | 83,013 |
| YAML | 10 | 1,265 | 1,122 | 46,561 |

## Method and scope

Counts come from committed Git blobs at the exact snapshot above, not the working directory. Untracked files, build output, downloaded models, caches and Git history are excluded. Tracked binary assets are counted as files/bytes but have no line count. Symlinks are counted separately and not followed. Git submodule contents are excluded.

Physical lines include comments and blank lines, with a final unterminated line counted. Nonblank lines include comments. These are not parser-derived statements or comment-free SLOC. Source/script totals cover Kotlin, Kotlin scripts, Java, Python, shell, JavaScript, C/C++ and headers; XML/resources, data and documentation are reported separately. Test counts overlap source totals.

This report remains tied to the exact commit above; it is not a live size badge. Rerun the command for a newer commit when an updated count is needed.

Reproduce this snapshot:

```sh
python3 scripts/repository_stats.py --ref 2e36a4d6939b32380e2b87cac989138ee002e918 --format markdown
```

Count the currently checked-out commit:

```sh
python3 scripts/repository_stats.py --format json
```
