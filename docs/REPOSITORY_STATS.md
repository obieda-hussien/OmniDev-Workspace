# Repository statistics

Snapshot commit: `211149522fd3545e93e4690c0139d82eaed3762a`.

| Metric | Count |
| --- | ---: |
| Tracked files | 803 |
| UTF-8 text files | 791 |
| Binary/non-UTF-8 files | 12 |
| Symbolic links | 0 |
| Total tracked blob bytes | 6,906,422 |
| Physical text lines | 132,409 |
| Nonblank text lines | 119,136 |
| Source/script files | 665 |
| Source/script physical lines | 123,409 |
| App Kotlin files (including tests) | 634 |
| App Kotlin physical lines (including tests) | 119,735 |
| Test source files | 156 |
| Test source physical lines | 12,551 |

## Text breakdown

| Format | Files | Physical lines | Nonblank lines | Bytes |
| --- | ---: | ---: | ---: | ---: |
| C++ | 2 | 680 | 582 | 29,745 |
| JSON | 5 | 436 | 436 | 17,239 |
| Java | 1 | 8 | 8 | 353 |
| JavaScript | 3 | 284 | 268 | 12,552 |
| Kotlin | 634 | 119,735 | 108,939 | 5,878,357 |
| Kotlin build scripts | 3 | 526 | 471 | 23,075 |
| Markdown | 64 | 5,266 | 3,622 | 553,859 |
| Other text | 22 | 705 | 617 | 30,978 |
| Properties | 2 | 37 | 32 | 1,724 |
| Python | 14 | 1,702 | 1,482 | 74,359 |
| Shell | 8 | 474 | 410 | 18,284 |
| TOML | 1 | 89 | 84 | 5,723 |
| XML | 22 | 1,192 | 1,055 | 83,165 |
| YAML | 10 | 1,275 | 1,130 | 47,518 |

## Method and scope

Counts come from committed Git blobs at the exact snapshot above, not the working directory. Untracked files, build output, downloaded models, caches and Git history are excluded. Tracked binary assets are counted as files/bytes but have no line count. Symlinks are counted separately and not followed. Git submodule contents are excluded.

Physical lines include comments and blank lines, with a final unterminated line counted. Nonblank lines include comments. These are not parser-derived statements or comment-free SLOC. Source/script totals cover Kotlin, Kotlin scripts, Java, Python, shell, JavaScript, C/C++ and headers; XML/resources, data and documentation are reported separately. Test counts overlap source totals.

This report remains tied to the exact commit above; it is not a live size badge. Rerun the command for a newer commit when an updated count is needed.

Reproduce this snapshot:

```sh
python3 scripts/repository_stats.py --ref 211149522fd3545e93e4690c0139d82eaed3762a --format markdown
```

Count the currently checked-out commit:

```sh
python3 scripts/repository_stats.py --format json
```
