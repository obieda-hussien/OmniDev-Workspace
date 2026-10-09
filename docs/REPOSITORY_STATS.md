# Repository statistics

Snapshot commit: `144c8e0f28f961356841fbe708c734abb8d159be`.

| Metric | Count |
| --- | ---: |
| Tracked files | 796 |
| UTF-8 text files | 784 |
| Binary/non-UTF-8 files | 12 |
| Symbolic links | 0 |
| Total tracked blob bytes | 6,923,762 |
| Physical text lines | 132,443 |
| Nonblank text lines | 119,224 |
| Source/script files | 661 |
| Source/script physical lines | 123,737 |
| App Kotlin files (including tests) | 632 |
| App Kotlin physical lines (including tests) | 120,275 |
| Test source files | 155 |
| Test source physical lines | 12,537 |

## Text breakdown

| Format | Files | Physical lines | Nonblank lines | Bytes |
| --- | ---: | ---: | ---: | ---: |
| C++ | 2 | 680 | 582 | 29,745 |
| JSON | 4 | 326 | 326 | 9,117 |
| Java | 1 | 8 | 8 | 353 |
| JavaScript | 3 | 284 | 268 | 12,552 |
| Kotlin | 632 | 120,275 | 109,485 | 5,914,653 |
| Kotlin build scripts | 3 | 526 | 471 | 23,323 |
| Markdown | 62 | 5,085 | 3,466 | 553,091 |
| Other text | 22 | 705 | 617 | 31,006 |
| Properties | 2 | 37 | 32 | 1,724 |
| Python | 12 | 1,490 | 1,292 | 64,004 |
| Shell | 8 | 474 | 410 | 18,284 |
| TOML | 1 | 89 | 84 | 5,859 |
| XML | 22 | 1,192 | 1,055 | 83,165 |
| YAML | 10 | 1,272 | 1,128 | 47,395 |

## Method and scope

Counts come from committed Git blobs at the exact snapshot above, not the working directory. Untracked files, build output, downloaded models, caches and Git history are excluded. Tracked binary assets are counted as files/bytes but have no line count. Symlinks are counted separately and not followed. Git submodule contents are excluded.

Physical lines include comments and blank lines, with a final unterminated line counted. Nonblank lines include comments. These are not parser-derived statements or comment-free SLOC. Source/script totals cover Kotlin, Kotlin scripts, Java, Python, shell, JavaScript, C/C++ and headers; XML/resources, data and documentation are reported separately. Test counts overlap source totals.

This report remains tied to the exact commit above; it is not a live size badge. Rerun the command for a newer commit when an updated count is needed.

Reproduce this snapshot:

```sh
python3 scripts/repository_stats.py --ref 144c8e0f28f961356841fbe708c734abb8d159be --format markdown
```

Count the currently checked-out commit:

```sh
python3 scripts/repository_stats.py --format json
```
