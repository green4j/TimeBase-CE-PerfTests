# TimeBase CE performance tests

End-to-end correctness, throughput and latency campaigns for TimeBase CE: measure a baseline alone, or compare it
with candidates — two branches, a release against a patch — under identical, randomised conditions.

A [profile](#profile-files) says what to measure, a [bench](#bench-files) what runs it: the baseline, the
candidates and the client, each in its configurations. The [command line](#cli) gives either one, or overrides parts
of it. Every run starts a fresh TimeBase server (a `url:` server is shared instead), runs the client of one
scenario, stops the server and writes one JSON file. A report aggregates repetitions into mean ± standard deviation
and, with candidates, their difference from the baseline.

- [Quick start](#quick-start)
- [Bundled profiles](#bundled-profiles)
- [Profile files](#profile-files)
  - [Profile](#profile)
  - [Depth](#depth)
  - [Suite](#suite)
  - [Suite parameters](#suite-parameters)
- [Bench files](#bench-files)
  - [Baseline and candidates](#baseline-and-candidates)
  - [Configurations](#configurations)
  - [Client](#client)
- [CLI](#cli)
  - [Profile options](#profile-options)
  - [Bench options](#bench-options)
  - [Campaign options](#campaign-options)
  - [`perftests report`](#perftests-report)
- [Results and report](#results-and-report)
  - [Reading the report](#reading-the-report)
- [Methodology](#methodology)

In every table of keys and options **yes** marks the required ones; the others take their default when left out.

## Quick start

Prerequisites:

- JDK 17+ to build and run the suite; its classes target Java 11.
- JDK 11–15 to build TimeBase CE 6.2 from git, given as `TB_BUILD_JAVA_HOME`: its Gradle 6.8 does not run on newer JDKs.
- `git` and a local copy of TimeBase CE; `../TimeBase-CE` below stands for it, wherever it is.
- Free disk space under `results/`: a clone and a build of every measured ref, plus what a run writes; the runner
  checks it before every run that starts a server ([Disk](#results-and-report)).
- A machine with nothing else running on it, a second campaign included: the client and the server compete for
  the same cores and the tails absorb it.

```bash
git clone https://github.com/finos/TimeBase-CE.git            # or your fork, with your branch
git clone https://github.com/green4j/TimeBase-CE-PerfTests.git
cd TimeBase-CE-PerfTests
./gradlew installDist
export TB_BUILD_JAVA_HOME=/path/to/jdk-11
```

**Measure the baseline only** — the current state of your copy, uncommitted changes included: give `--baseline`
alone.

```bash
# Is anything broken? Correctness plus one latency and one throughput check per loader mode, ~2 min
build/install/perftests/bin/perftests run --profile=sanity --baseline=mine=git:../TimeBase-CE@WORKTREE

# First look at latency (1K, 50K, 200K msg/s) and at throughput, 1 writer and 3 readers, ~5-12 min each
build/install/perftests/bin/perftests run --profile=latency --baseline=mine=git:../TimeBase-CE@WORKTREE
build/install/perftests/bin/perftests run --profile=throughput --baseline=mine=git:../TimeBase-CE@WORKTREE
```

**Compare with a candidate** — your branch with the branch it started from: add `--candidate`.

```bash
build/install/perftests/bin/perftests run --profile=sanity \
    --baseline=base=git:../TimeBase-CE@<base-branch> --candidate=mine=git:../TimeBase-CE@<your-branch>

# ~10-25 min each
build/install/perftests/bin/perftests run --profile=latency \
    --baseline=base=git:../TimeBase-CE@<base-branch> --candidate=mine=git:../TimeBase-CE@<your-branch>
build/install/perftests/bin/perftests run --profile=throughput \
    --baseline=base=git:../TimeBase-CE@<base-branch> --candidate=mine=git:../TimeBase-CE@<your-branch>
```

Each ref is checked out into a private clone and built once, about a minute, then cached; your clone is not
touched; `@WORKTREE` takes `HEAD` plus your uncommitted changes. Every command prints its number of runs and expected
duration first, and at the end prints the report and writes it to `results/<campaign>/report.md` — the campaign
is named after the profile and the baseline and candidate ids, e.g. `results/latency-fast-one2three-mine/report.md`
or `results/latency-fast-one2three-base-vs-mine/report.md`.

Running the same command again resumes it. If a ref has moved on, or anything else about the baseline, a candidate
or a configuration changed, the suite refuses to mix the new results with the old ones: give other ids, pass
`--name=<campaign>`, or delete the campaign directory.

## Bundled profiles

`perftests profiles` lists them. `--depth` chooses repetitions and run lengths, `--load` the `latency` rates —
`light` 1K msg/s, `moderate` 50K, `heavy` 200K, `saturation` 500K — and `--topology` one, three or six live
readers ([Profile options](#profile-options)).

| Profile | `fast` | `medium` | `full` |
|---|---|---|---|
| `latency` — 1 and 1 000 symbols, open loop | 1K, 50K, 200K msg/s; one message at a time; 3 repetitions; 10K, 300K, 600K messages measured; 72 runs, ~18 min | same loads, bursts 1 and 64; 7 repetitions; 20K, 600K, 600K messages; 336 runs, ~2 h | + 500K msg/s, bursts 1, 64, 256; 20 repetitions; 30K, 1.5M, 1.5M, 1.5M messages; 1 920 runs, ~14 h |
| `throughput` — writer max rate | 1, 1 000, 25 000 symbols; 3 × (3 + 5 s); 36 runs, ~9 min | 1 … 131 072 symbols; 7 × (3 + 5 s); 168 runs, ~45 min | same; 10 repetitions; 240 runs, ~1 h |
| `correctness` — every message exactly once, in order | bursts 0 and 2 000, 300K messages; 1 repetition; 8 runs | bursts 0, 50, 2 000, 3M messages; 3 repetitions; 36 runs | same; 10 repetitions; 120 runs |
| `sanity` | One depth: correctness, plus latency at 200K msg/s and throughput at 1 000 symbols; 12 runs, ~3 min | | |

- `correctness` has 8 live, one slow and one late-joining reader of its own and takes no topology.
- Every profile covers both `MIN_CPU_USAGE` and `LOW_LATENCY` loaders; latency and throughput messages carry
  200 bytes of payload (`payloadBytes`, on top of the message's own fields).
- The messages measured are `measureMessages`, but no fewer than 3 s and no more than `measureSeconds` at the rate.
- `--set=readers=…` replaces the topology; the campaign is then named `readers<values>` instead of `one2three`.
- `fast` is a first look: three repetitions show large differences, not small ones, and give latency no interval of
  Diff. Decide on `medium` or `full`: to see a difference of δ with 95 % confidence and 80 % power takes about
  n ≈ (2.8 · σ / δ)² repetitions, σ the spread of the paired differences — 8 for a 5 % difference at 5 % spread.
- Times are for a baseline and one candidate on a 10-core laptop; each further candidate adds the same again.

Change any axis or parameter with `--set`; one value sets a parameter, several make an axis, `<suite>.` limits it
to one suite of the profile, and unknown keys are rejected:

```bash
--set=burst=1,16,256 --set=payloadBytes=64 --set=readers=1,6,16 --set=repetitions=5 --set=latency.rate=1000,10000
```

## Profile files

A profile says what to measure. A profile file has the format of the
[bundled profiles](src/main/resources/io/github/green4j/timebase/perftests/profiles);
[`profiles/example.json`](profiles/example.json) — throughput, one reader, 64-byte payloads, 1 and 1 000 symbols — is
a starting point:

```bash
build/install/perftests/bin/perftests run --profile=profiles/example.json \
    --baseline=base=git:../TimeBase-CE@<base-branch> --candidate=mine=git:../TimeBase-CE@<your-branch>
```

- `matrix` axes are combined as a cartesian product; `params` apply to every combination. Profile, depth and suite
  values are applied in this order, the later replacing a key of either kind; one key in both at one level is an
  error. An unknown key is an error too.
- `${NAME}` is replaced with an environment variable; an unset variable is an error.
- Every run writes to its own new stream named `perftests_<definition hash>_<nonce>`; the suite never writes to,
  or deletes, a stream it did not create.

### Profile

| Key | Required | Default | Meaning |
|---|---|---|---|
| `type` | **yes**, unless every suite gives its own | | Suite type: `latency`, `throughput` or `correctness` |
| `name` | | the file name without `.json` | Profile name, the start of the campaign name |
| `description` | | | For people; `perftests profiles` shows those of the bundled profiles |
| `topology` | | `one2three` | Default of `--topology` |
| `seed` | | 1 | Default of `--seed` |
| `loads` | | | Map of load name to msg/s, for `--load` and depth `loads` |
| `params`, `matrix` | | | Parameters and axes for every suite, see [suite parameters](#suite-parameters) |
| `depths` | | one depth made of the profile's own `repetitions` and `suites`, at all `loads` | Map of depth name to [depth](#depth) |
| `repetitions` | | 1 | Without `depths` only; with them an error |
| `suites` | | one suite named after `type` | Without `depths` only; with them an error |

### Depth

| Key | Required | Default | Meaning |
|---|---|---|---|
| `suites` | **yes** | | [Suites](#suite) of the depth, at least one |
| `repetitions` | | 1 | Repetitions of every suite |
| `loads` | | none | Load names measured without `--load`; they become the `rate` axis |
| `matrix`, `params` | | | Axes and parameters for every suite of the depth |

### Suite

| Key | Required | Default | Meaning |
|---|---|---|---|
| `name` | | its `type` | Prefix of the run ids; target of `--set=<name>.key=…` |
| `type` | | the profile's `type` | Suite type |
| `repetitions` | | the depth's | Repetitions of this suite |
| `matrix`, `params` | | | Axes and parameters of this suite |

### Suite parameters

Each type takes the parameters below; the defaults are those of the client, the bundled profiles set many of them.
A key no type takes is an error; a key only other types take is skipped for that suite, so profile-wide `params`
may serve suites of several types. `--set` with a key that no suite of the profile takes is an error. `mode` is the
loader's `ChannelPerformance`: `MIN_CPU_USAGE`, `HIGH_THROUGHPUT`, `LOW_LATENCY` or `LATENCY_CRITICAL`.

`latency` — open loop at a fixed rate, one measured reader:

| Parameter | Required | Default | Meaning |
|---|---|---|---|
| `mode` | | `LOW_LATENCY` | Loader mode |
| `rate` | | 200000 | Messages per second, on average |
| `burst` | | 1 | Messages sent back to back at each tick of the schedule |
| `readers` | | 1 | Live readers, the measured one included |
| `symbols` | | 1 | Symbols, round-robin |
| `payloadBytes` | | 0 | Payload bytes on top of the message's own fields |
| `warmupMessages` | | 200000 | Sent first as fast as the loader takes them, for the JIT, and read up before going on |
| `warmupSeconds` | | 1 | Then sent at `rate` and not measured, to warm the paths of the rate itself |
| `measureMessages` | | 600000 | Messages measured, but within the two bounds below |
| `measureSeconds` | | 20 | At most this long a measurement; at least 3 s, or `measureSeconds` if shorter |
| `drainSeconds` | | 10 | How long readers may take to read the warmup, and to receive everything after the last send |

`throughput` — the writer as fast as the loader accepts:

| Parameter | Required | Default | Meaning |
|---|---|---|---|
| `mode` | | `MIN_CPU_USAGE` | Loader mode |
| `readers` | | 0 | Live readers; 0 measures the writer alone |
| `symbols` | | 1 | Symbols, round-robin |
| `payloadBytes` | | 0 | Payload bytes on top of the message's own fields |
| `warmupSeconds` | | 5 | Written and not measured |
| `measureSeconds` | | 10 | Written and measured |
| `drainSeconds` | | 30 | How long readers may take to receive everything after the writer stops |
| `raw` | | `true` | Readers take raw messages without decoding them |

`correctness` — every reader must receive every message exactly once, in order:

| Parameter | Required | Default | Meaning |
|---|---|---|---|
| `mode` | | `MIN_CPU_USAGE` | Loader mode |
| `total` | | 1000000 | Messages written |
| `liveReaders` | | 8 | Live readers, besides one slow and one late-joining reader |
| `symbols` | | 100 | Symbols, round-robin |
| `burst` | | 2000 | Messages between pauses; 0 writes without pauses |
| `pauseMillis` | | 2 | Pause after each burst, so that readers fall asleep |
| `timeoutSeconds` | | 60 | How long the late reader may take to go live, and readers to receive everything |
| `clientTimeoutSeconds` | | 900 | How long the runner waits for the client before failing the run |

## Bench files

A bench says what runs the measurements: the `baseline`, the `candidates` compared with it and the `client` — the
writer and the readers of this suite — each in its configurations. Give it as `--bench=<file.json>`; a file with a
`baseline` replaces `--baseline` and `--candidate`, one with only `client` keeps them on the command line.

```json
{
  "baseline":   {"id": "orig", "git": {"repo": "../TimeBase-CE", "ref": "6707be73"},
                 "configurations": [{"id": "g1", "jvmArgs": ["-Xmx4g", "-Xms4g", "-XX:+UseG1GC"], "prefix": ["taskset", "-c", "0-3"]}]},
  "candidates": [{"id": "f2", "git": {"repo": "../TimeBase-CE", "ref": "live-reader-notify-6.2"},
                 "configurations": [{"id": "g1", "jvmArgs": ["-Xmx4g", "-Xms4g", "-XX:+UseG1GC"], "prefix": ["taskset", "-c", "0-3"]}]}],
  "client":     {"configurations": [{"prefix": ["taskset", "-c", "4-9"]}]}
}
```

```bash
build/install/perftests/bin/perftests run --profile=latency --bench=my-bench.json
```

| Key | Required | Default | Meaning |
|---|---|---|---|
| `baseline` | **yes**, with `candidates` | `--baseline` | What the candidates are compared with, or what is measured alone; with it `--baseline` and `--candidate` are errors |
| `candidates` | | none | What is compared with the baseline; only with a `baseline` |
| `client` | | one configuration `default` | `{"configurations": [...]}` of the [client](#client) |
| `description` | | | For people |

- Paths (`git.repo`, `dist`, `overlay`) are relative to the file, so a committed file works from any directory.
- `${NAME}` is replaced with an environment variable; an unset variable is an error.
- Changing the baseline, a candidate or a configuration under the same id changes the definition of its runs:
  resuming the campaign is refused, as for any other change.
- In a nightly or CI file committed to a repository give commits and image digests, not branches or tags.

[`benches/example.json`](benches/example.json) compares the original with the patch, each with G1 and with the
parallel GC.

### Baseline and candidates

The baseline and each candidate give exactly one of `git`, `dist`, `image` or `url`, and its
[configurations](#configurations); any other key is an error. On the command line the same is
`--baseline=[id=]<git:…|dist:…|image:…|url:…>`:

| Key | Command line | Started per run | Use for |
|---|---|---|---|
| `dist` | `dist:<dir>[+overlay:<jar>]` | A fresh local server process from a distribution directory; the overlay goes first on the classpath and replaces the stock classes | A release against the same release with patched classes |
| `git` | `git:<repo>@<branch, tag, commit or WORKTREE>` | A fresh local server process from a distribution the runner builds from the repository | Branches and commits, or the current state of a copy |
| `image` | `image:<docker image>` | A fresh container | Released images, without building anything |
| `url` | `url:dxtick://host:port` | Nothing: the same running server for every run | Remote or production-like servers; runs are marked as not isolated |

- **dist.** A directory with `lib/timebase-server-<version>.jar` and its dependencies. `timebase-server` is not
  published to Maven Central; one way to get 6.2.34:

  ```bash
  docker create --name tb finos/timebase-ce-server:6.2.34
  docker cp tb:/timebase-server ./timebase-server-6.2.34
  docker rm tb
  ```

- **git.** The ref is checked out into a private clone under `results/.builds/<commit>`, so the repository itself
  is not touched, and built with `./gradlew :java:timebase:server:copyTimebaseServerContainerDependensies`.
  `WORKTREE` builds `HEAD` plus the uncommitted changes and untracked files of the repository; its cache key adds a
  hash of those changes. The build runs on `git.javaHome`, else on `TB_BUILD_JAVA_HOME`; one of them is
  **required**. `<repo>` is the path of any local copy of TimeBase CE.
- **image.** A tag or an image ID. A tag is resolved to its image ID once when the campaign starts (pulling the image
  if it is not present), and every run starts that ID, so a tag moved by a later `docker pull` cannot mix two
  images into one campaign; a resumed campaign whose tag now points elsewhere is refused. The image's
  `tdbserver.sh` reads `JAVA_OPTS`, which gets the configuration's `jvmArgs`; its port 8011 is published on the
  configuration's `port`. An overlay cannot be used with an image. On macOS Docker runs in a virtual machine, which
  distorts latency. This kind has not been run end to end yet.
- **url.** Nothing is started or stopped. Each run deletes its own stream at the end, but server state carries
  over between runs and other clients may share the server, so prefer the other kinds for comparisons.

| Key | Required | Default | Applies to | Meaning |
|---|---|---|---|---|
| `id` | **yes** for each candidate when there are several | `orig` for the baseline, `patched` for a single candidate | all | `[A-Za-z0-9_.-]+`, unique; names the baseline or candidate in run ids and reports |
| `git.repo` | **yes** for `git` | | `git` | Path of a local copy of TimeBase CE |
| `git.ref` | **yes** for `git` | | `git` | Branch, tag, commit or `WORKTREE` |
| `git.javaHome` | | `TB_BUILD_JAVA_HOME` | `git` | JDK that builds the distribution; not the one that runs it |
| `git.task` | | `:java:timebase:server:copyTimebaseServerContainerDependensies` | `git` | Gradle task that makes the distribution |
| `git.output` | | `java/timebase/server/build/docker/timebase` | `git` | The distribution directory inside the repository |
| `git.gradleArgs` | | | `git` | More Gradle arguments |
| `dist` | **yes** for `dist` | | `dist` | Distribution directory |
| `overlay` | | | `git`, `dist` | Jar put first on the server classpath |
| `image` | **yes** for `image` | | `image` | Docker image tag or id |
| `containerPort` | | 8011 | `image` | TimeBase port inside the container |
| `url` | **yes** for `url` | | `url` | `dxtick://host:port` of a running server |
| `configurations` | | one configuration `default` | all | Its [configurations](#configurations); the same ids as the baseline's |

### Configurations

The baseline and every candidate run in each of their configurations and are compared in the configuration of the
same id: `f2` in `g1` with `orig` in `g1`. So all of them must have the same configuration ids; what a configuration
holds may differ. Let a candidate differ from the baseline in one thing in a configuration — the code — or the
difference cannot be told apart. Without `configurations` there is one, `default`; one configuration leaves the
run ids as they are, several become the `serverConfig` axis.

| Key | Required | Default | Applies to | Meaning |
|---|---|---|---|---|
| `id` | **yes**, with several | `default` | all | `[A-Za-z0-9_.-]+`; pairs the candidates with the baseline |
| `jvmArgs` | | `-Xmx4g -Xms4g`; for the client `-Xmx2g -Xms2g` | `git`, `dist`, `image`, client | JVM options; replace the defaults |
| `java` | | the Java running the suite | `git`, `dist`, client | Java executable |
| `prefix` | | | `git`, `dist`, client | Prepended to the command line, e.g. `taskset -c 0-3` |
| `port` | | 18011 | `git`, `dist`, `image` | Server port |
| `webPort` | | `port + 10` | `git`, `dist` | Port of the server's web monitor |
| `startTimeoutSeconds` | | 60 | `git`, `dist`, `image` | How long the server may take to start |
| `dockerArgs` | | | `image` | More `docker run` options, e.g. `--cpuset-cpus=4-7`; not the ones the runner sets or reserves: `--name`, `-p`/`--publish`, `-P`/`--publish-all`, `-d`/`--detach`, `--rm`, `--entrypoint`, `JAVA_OPTS` |

A `url` takes only `id`: nothing is started.

### Client

The client is the code of this suite: the writer and the readers of every run. `client.configurations` takes `id`,
`jvmArgs`, `java` and `prefix` ([Configurations](#configurations)); several become the `clientConfig` axis and are
each measured with every configuration of the baseline and the candidates.

## CLI

`perftests run` runs a campaign, `perftests report` rebuilds its report, `perftests profiles` lists the bundled
profiles. Options are `--name=value` or `--flag`; an unknown option is an error.

### Profile options

| Option | Required | Default | Meaning |
|---|---|---|---|
| `--profile` | **yes** | | A bundled profile (`latency`, `throughput`, `correctness`, `sanity`) or a [profile file](#profile-files) (a path ending in `.json` or containing `/`) |
| `--depth` | | `fast`, or the only depth of the profile | A depth of the profile: `fast`, `medium` or `full` in the bundled ones |
| `--load` | | the depth's loads | Comma-separated loads of the profile: `light`, `moderate`, `heavy`, `saturation` in `latency`; profiles with `loads` only |
| `--topology` | | the profile's `topology`, else `one2three` | `one2one`, `one2three`, `one2six`: 1, 3 or 6 live readers; not for profiles without readers |
| `--set` | | | `[suite.]key=value[,value...]`, repeatable: one value sets a parameter, several make an axis; `repetitions` too |
| `--seed` | | the profile's `seed`, else 1 | Seed of the run order |

### Bench options

| Option | Required | Default | Meaning |
|---|---|---|---|
| `--bench` | | | A [bench file](#bench-files) |
| `--baseline` | **yes**, unless the bench gives a `baseline` | | `[id=]<git:…\|dist:…\|image:…\|url:…>` ([Baseline and candidates](#baseline-and-candidates)); alone it is what is measured. Id: `orig` |
| `--candidate` | | | The same, repeatable: what is compared with the baseline. Id: `patched` for one; required with several |
| `--port` | | the configuration's `port`, else 18011 | Server port, for every configuration of the baseline and the candidates but a `url` |
| `--serverJava` | | the configuration's `java`, else the Java running the suite | Java of every `git` and `dist` configuration |
| `--clientJava` | | the configuration's `java`, else the Java running the suite | Java of every client configuration |

### Campaign options

| Option | Required | Default | Meaning |
|---|---|---|---|
| `--name` | | `<profile>-[<depth>-][<loads>-][<topology>-]<ids joined by -vs->` | Campaign name and directory |
| `--out` | | `results` | Directory of the campaigns and of `.builds` |
| `--dryRun` | | off | List the runs without running them |
| `--print` | | off | Print the generated campaign without running it |

### `perftests report`

| Option | Required | Default | Meaning |
|---|---|---|---|
| `--results` | **yes** | | The campaign directory, e.g. `results/<campaign>` |
| `--baseline` | | the campaign's baseline | Id of the baseline or of a candidate to give the differences from |

A campaign measures one baseline and any number of candidates. Several candidates in one campaign — versions of a
patch, JVM options of one build, neighbouring releases — run under the same conditions, block by block, and the
baseline is measured once for all of them; separate campaigns hours apart differ by more than the machine's drift
allows. Any other pair comes from the same runs, without measuring again:

```bash
build/install/perftests/bin/perftests report --results=results/<campaign> --baseline=<id>
```

## Results and report

Results go to `results/<campaign>/`:

| Path | Content |
|---|---|
| `generated-campaign.json` | The campaign generated from the profile, with the profile, depth, loads, topology and overrides it came from, and the bench file if any |
| `campaign.json` | The campaign as executed: git builds, image ids and SHA-256 of distributions and overlays resolved |
| `expanded-plan.json` | Every run in execution order |
| `environment.json` | OS, CPU count, Java versions, suite commit, SHA-256 of the server jar and overlays |
| `runs/<run>.json` | Parameters, status, metrics, server and client command lines of one run |
| `runs.jsonl` | The same, one line per attempt, in completion order; a retried run has several |
| `logs/<run>/` | Server and client output |
| `report.md`, `summary.csv` | Aggregated results; `report.md` starts with the setup — profile, bench, time, machine, the baseline and the candidates with their code and configurations, the client — and gives the time of every scenario |

A run is `ok`, `invalid` (it completed but its measurements must not be used: lost or reordered messages,
readers that fell behind or failed, a failed correctness check) or `failed` (it did not complete). Only `ok` runs
are aggregated, and `run` exits with code 1 if any run is not `ok`.

Starting a campaign again skips the runs that are already `ok` and retries the others, so an interrupted campaign
resumes where it stopped. A runner stopped by Ctrl+C or `kill` stops what it has started — a build, the server, the
client — on the way out; after `kill -9` they keep running, and the next campaign may find the port taken. Every
run file carries a hash of its definition — parameters, the baseline or candidate as built (with the SHA-256 of the
distribution's files and of the overlay, or the image id), its configuration and the client's — and the runner
refuses to resume over files made from a different definition. Delete `results/<campaign>` to start over.

**Disk.** The server data of a run is deleted before the next run starts, and right after the run when it is
`ok`. Before every run that starts a server the runner estimates what the run may write — messages × (payload + 64 bytes) plus 1 GB,
assuming 1M msg/s for throughput — and stops the campaign if the disk has less free space; free some and start it
again to resume. Every `run` also deletes the builds in `results/.builds` that no campaign under `results/` uses,
so deleting a campaign directory frees its builds on the next run.

### Reading the report

Every metric table has a row per scenario and a column per baseline and candidate. A cell is the mean ± sample
standard deviation over repetitions and the coefficient of variation; `n=… of …` when fewer runs than planned are
`ok`. A latency percentile or max comes first from the merged histograms of the runs, then `runs` gives the mean ±
standard deviation of the runs' own values: `1.10, runs 1.12 ± 0.05 (4 %)`. `summary.csv` also has the median, quartiles, min and max. A metric no `ok` run of the suite has — `send()` in
runs made before it was measured — gets no table.

| Suite | Table | Unit | Better | Meaning |
|---|---|---|---|---|
| `latency` | p50, p99, p99.9, p99.99, max | ms | lower | Transport: `receive − sent`, from just before the writer's `send()` to the measured reader — the client library, the connection, the server, the delivery and the reader's wakeup |
| `latency` | p99 from schedule, max from schedule | ms | lower | `receive − scheduled`: transport plus the time the message waited to be sent |
| `latency` | send() p99, send() max | µs | lower | How long the writer's `send()` took: serialising, the loader's buffer, and blocking when the server holds the writer back |
| `throughput` | Writer rate | K msg/s | higher | Messages the writer sent per second |
| `throughput` | Writer CPU | ns/msg | lower | CPU time of the writer's thread per message |
| `correctness` | Pass | | 1 | 1 when every reader received every message exactly once, in order |

The latencies of one message cover these intervals:

```
scheduled ──(waiting to be sent)──► sent ──(send())──► send() returned ──(after the client)──► received
│                                    │                                                             │
│                                    └───────────────────────── transport ─────────────────────────┤
└───────────────────────────────────────── from schedule ──────────────────────────────────────────┘
```

- **Waiting to be sent**, only from schedule: the writer woke up late, earlier `send()` calls blocked, or the message
  waited for the earlier messages of its burst, which share its `scheduled`.
- **`send()`**, in both and in the send() tables: serialising, the loader's buffer, blocking when the server holds
  the writer back. A `send()` that blocks adds to the transport of its own message and to the waiting of the next
  ones.
- **After the client**, in both: the connection, the server, the delivery and the wakeup of the reader.

Percentiles of the intervals do not add up — the tails of different messages differ — they show where to look.

- **Transport and from schedule.** Equal: the writer started `send()` on schedule, so the delay is in `send()` or
  after it. From schedule larger: messages waited to be sent — the writer woke up late, earlier `send()` calls
  blocked, a burst was serialised, or the server did not keep up with the rate. At light loads the two usually
  match. The table *Writer and schedule* of every latency section compares, over the runs that have both, the means
  of p99 and max for each baseline and candidate: `on time` within 10 %, else `late to send` by so many ms.
- **Transport and send().** A long transport with a short `send()`: the delay is after the client, in the server or
  in the delivery. A long `send()`: the delay is in the send path — serialising, the loader's buffer, the writer's
  thread being descheduled, or a server that holds the writer back; the later messages then also wait to be sent.
- **Diff** is the candidate's pooled value, for throughput its mean, against the baseline's, in percent: negative is
  better for latency and CPU, positive for the rate. In brackets follows its 95 % confidence interval over the
  repetitions, paired because the baseline and the candidates of a repetition run back to back on the same machine
  state: `+10.0 % [+8.9, +11.0]`. `noise` marks an interval that holds 0, a difference the runs do not show. The
  interval narrows as 1/√n: 3 repetitions show only large differences, conclusions about a few percent take 8–10 or
  more. Read Diff with its interval rather than two cells' spreads; overlapping spreads do not mean there is no
  difference.
- **Wins** `k/n`: in `k` of `n` repetitions the candidate was better than the baseline in the same repetition.
  Read it with Diff: a large Diff with `k` close to `n` is a consistent difference; with `k` near `n/2` the
  candidate is not consistently better, and the Diff may come from outliers.
- **Tails.** p99.9, p99.99 and max of one run come from a handful of messages and vary a lot between runs. The
  pooled max is the worst message of all runs: compare the tails over many repetitions (≥ 20), with Wins and the
  spread of the runs, and on a machine where nothing else runs.

## Methodology

- **Order.** For each repetition, scenarios run in a seeded random order, and the baseline and the candidates of one
  scenario run back to back in a seeded random order. Each scenario/repetition block therefore compares all of them
  under the same machine state, and the order is reproducible from the seed.
- **Fresh server per run**, with an empty home directory (except `url`).
- **Readiness.** Every reader opens its cursor with real-time notification and counts as ready only after the
  server's `RealTimeStartMessage`, i.e. once the cursor is live; writers start after that.
- **Messages.** One fixed-type stream of `PerfMessage`: global sequence, per-symbol sequence, scheduled and actual
  send time, optional binary payload. Symbols are assigned round-robin.
- **Correctness.** Message `i` has timestamp `base + i` ns, so every reader must see exactly 0, 1, 2, … with the
  expected symbol, per-symbol sequence and timestamp. A loss, a duplicate or a reordering fails the run. The writer
  alternates bursts and pauses so that readers keep falling asleep and waking up; one reader is slow; at 30 % of
  the stream a late reader joins and the writer waits until it has read the history and gone live.
- **Throughput.** Readers run in one process, each with its own connection. The writer sends as fast as the loader
  accepts and reports the rate, its thread CPU per message and the duration of `send()` after the warmup. Then
  every reader must receive exactly as many messages as were written within `drainSeconds`, or the run is
  invalid.
- **Latency.** Open loop: bursts are sent on a fixed schedule whether or not readers keep up, so overload shows up
  as latency instead of a slower writer. The measured reader records `receive − sent` (transport, `sent` taken just
  before `send()`) and `receive − scheduled` (schedule, includes the time a burst waited to be sent) for every
  measured message; the writer records how long each measured `send()` took.
  Writer and measured reader share one JVM and so one `System.nanoTime()` origin; the other readers only consume.
  The JIT is warmed by `warmupMessages` sent as fast as the loader takes them; once every reader has read them,
  `warmupSeconds` at the rate warm what only the rate exercises, e.g. readers falling asleep between messages at
  light loads. Then the measured messages follow on the schedule: `measureMessages`, within 3 s and
  `measureSeconds` at the rate, so that a light load still gives enough samples for the tails and a heavy one does
  not run longer than it needs. Warmup and measurement are split by sequence number. Every reader must see every
  message exactly once and the messages of each symbol in order: the server stamps messages with milliseconds, and
  TimeBase orders the messages of different symbols only by timestamp. Missing messages (`lost`), sequence
  violations or negative latencies make the run invalid. `actualRate` is measured between the first and the last
  measured send.
- **Calculation**, as JMH does it. Throughput: a run gives the messages sent after the warmup divided by the time
  they took, and the writer thread's CPU time divided by the same messages; the report takes the arithmetic mean
  over the `ok` runs of a scenario. Latency: a run records every measured message in an HdrHistogram (3
  significant digits) and stores it with its percentiles; the report merges the histograms of the `ok` runs of a
  scenario and takes p50 … p99.99 and max from the merged one, so a pause in one run weighs by the messages it
  delayed among all the measured ones, not by a whole run. Runs recorded without histograms get the mean of their
  percentiles. Next to it, every cell has the mean, the sample standard deviation and the coefficient of variation
  of the runs' own values; `summary.csv` adds the median and quartiles (linear interpolation), min and max. Diff
  compares the pooled values, or the means, Wins compares the runs of the same repetition.
- **Interval of Diff**, 95 %, over the repetitions where both the baseline and the candidate are `ok`. Throughput
  and runs without histograms: Student's t interval of the mean of `log(candidate / baseline)` per repetition, from
  2 pairs; the log makes it an interval of the ratio and tames skewed values. Pooled latency: a percentile
  bootstrap, 2 000 resamples of the pairs with a fixed seed, each pooling the histograms of the runs drawn, from 5
  pairs, since fewer give too few distinct resamples. Runs, not messages, are resampled: the messages of a run share
  its pauses and are not independent.
