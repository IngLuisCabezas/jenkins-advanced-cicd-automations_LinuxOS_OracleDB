# Technical Reference

Detailed per-job reference for the scripts and pipelines in this repository. For the project overview, see the root [README](../README.md).

## Repository structure

```
repo-root/
├── README.md
├── .gitignore
├── jenkins/
│   └── pipelines/                    # Jenkins Pipeline (Groovy) and Freestyle (shell) job scripts
└── scripts/
    ├── .gitkeep                      # Placeholder for future standalone scripts
    └── PrePostReleaseScript/         # Pre/post-release health checks (manual, SV1)
        ├── PrePost_Release_Health_Checks.sh
        ├── health_check.conf
        └── README.md
```

## Folder descriptions

| Folder | Purpose |
|--------|---------|
| [`jenkins/`](../jenkins/) | Jenkins automation assets. Currently contains pipeline scripts only; add shared Groovy libraries or job config here as the project grows. |
| [`jenkins/pipelines/`](../jenkins/pipelines/) | Jenkins job scripts: declarative **Pipeline** definitions (`.groovy`) and **Freestyle** execute-shell scripts (`.sh`). Each file maps to one Jenkins job. Groovy pipelines validate agents, run work in parallel across labeled nodes, and archive combined reports. |
| [`scripts/`](../scripts/) | Standalone scripts (bash, Python, etc.) that are **not** tied to a Jenkins job. Use subfolders per automation (for example `PrePostReleaseScript/`). |
| [`scripts/PrePostReleaseScript/`](../scripts/PrePostReleaseScript/) | Pre- and post-release health validation for Tool clusters. Run manually from the **SV1 Linux server** — not from Jenkins. See [PrePostReleaseScript](#prepostreleasescript) below and [`scripts/PrePostReleaseScript/README.md`](../scripts/PrePostReleaseScript/README.md). |

## Jenkins pipelines

Create a **Pipeline** job in Jenkins, point it at the matching file under `jenkins/pipelines/`, and define the job parameters listed for each pipeline.

| Pipeline | File | Summary |
|----------|------|---------|
| **Master release installation** | [`MasterReleaseInstallation.groovy`](../jenkins/pipelines/MasterReleaseInstallation.groovy) | Orchestrates end-to-end release prep and install on the `built-in` agent: optional bundle download, filesystem backup, DB restore point, pre/post health checks, and environment install job. Archives global and stage logs and emails them via remote `mailx`. |
| **Filesystem application backup** | [`FileSystemApplicatonBackup.groovy`](../jenkins/pipelines/FileSystemApplicatonBackup.groovy) | Validates nodes and disk space, runs parallel filesystem backup on all agents with the environment label. On SV1, exports Tool tables and config items first. Uses `pigz` compression and produces one combined log artifact. |
| **Filesystem application restore** | [`FileSystemApplicatonRestore.groovy`](../jenkins/pipelines/FileSystemApplicatonRestore.groovy) | Validates nodes, disk space, and presence of a restore archive, then removes existing application directories and extracts the backup tarball in parallel on all labeled nodes. Produces one combined restore report. |

### Jenkins Freestyle jobs (shell)

Create a **Freestyle project** in Jenkins, run the matching shell script from SCM (or paste inline), restrict the job to agents with the `Environment` label, and archive the log artifacts listed for each job.

| Job | File | Summary |
|-----|------|---------|
| **DB restore point management** | [`DBRestorePointManagement.sh`](../jenkins/pipelines/DBRestorePointManagement.sh) | Lists, creates, or deletes Oracle restore points on a Tool database node via `sqlplus` (`sysbackup`). Writes a timestamped workspace log and supports optional auto-generated restore point names on create. |
| **Bundle download** | [`download_bundle.sh`](../jenkins/pipelines/download_bundle.sh) | Downloads a bundle file from an SFTP/HTTPS server via curl. Optionally validates the file checksum against `CKSUM_BUNDLE` when `CKSUM_IS_NEEDED` is `true`. Writes a timestamped workspace log for every build. |
| **Pre/post health checks (Jenkins)** | [`PrePost_Jenkins_Job.sh`](../jenkins/pipelines/PrePost_Jenkins_Job.sh) | Jenkins-oriented health checks (disk, memory, Oracle, Tool). Writes `Stage4Pre_6Post_Heath_Checks_*.log`. Used by jobs such as `SV_CHECK_STATUS` and triggered from the master release pipeline (stages 4 and 6). |

#### DBRestorePointManagement.sh

Shell script for a single-node **Freestyle** job (not a declarative pipeline). Connects with `sqlplus "${ATASVRCONNECT} as sysbackup"` and performs one action per build.

**Supported actions (`ACTION` parameter):**

| Action | Description |
|--------|-------------|
| `List_Restore_Points` | Query `v$restore_point` (name, SCN, time) |
| `Create_new_Restore_Point` | `CREATE RESTORE POINT ... GUARANTEE FLASHBACK DATABASE`; lists points after create |
| `Delete_a_Restore_Point` | `DROP RESTORE POINT`; requires `RESTORE_POINT_NAME`; lists points after delete |

**Job parameters:**

| Parameter | Purpose |
|-----------|---------|
| `Environment` | Jenkins agent label (use *Restrict where this project can be run*) |
| `ACTION` | One of the actions above |
| `RESTORE_POINT_NAME` | Required for delete; optional for create (auto-generated as `JENKINS_RP_<build>_<timestamp>` if empty) |

**Agent environment variables:**

| Variable | Purpose |
|----------|---------|
| `ATA_HOME` | Tool installation root; used to source `.profile` (required) |
| `ORACLE_SID` | Oracle service name for optional `tnsping` check (required) |
| `ATASVRCONNECT` | Oracle connect string for `sqlplus` (required) |
| `ATA_INSTANCE` | Logged when set; optional |

**Jenkins artifact:** `DBRestorePoint_*.log` (configure *Post-build action → Archive the artifacts*).

**Operational notes:**

- Run on a database-capable agent with Flashback Database enabled when creating guaranteed restore points.
- Restore point names must start with a letter and may contain letters, digits, `_`, `$`, or `#`.
- The script validates required environment variables and surfaces Oracle `ORA-` errors before completing the build.

#### download_bundle.sh

Shell script for a **Freestyle** job that downloads a release or application bundle from an SFTP/HTTPS server (host provided through `SFTP_HOST`) using `curl` over HTTPS. Checksum validation is optional and controlled by a Jenkins boolean parameter.

**Build flow:**

1. Log job parameters and build metadata.
2. **Before download** — if `CKSUM_IS_NEEDED=true`, require `CKSUM_BUNDLE`; otherwise skip checksum validation for the rest of the build.
3. Download `$BUNDLE_FILE` from `https://${SFTP_HOST}${SFTP_SERVER_PATH}/${BUNDLE_FILE}` using `SFTP_USER` credentials.
4. **After download** — if `CKSUM_IS_NEEDED=false`, run `cksum` for information only and exit successfully; if `true`, compare the first field of `cksum` output to `CKSUM_BUNDLE` and fail the build on mismatch.

**Job parameters:**

| Parameter | Type | Purpose |
|-----------|------|---------|
| `BUNDLE_FILE` | String | Bundle file name to download |
| `CKSUM_IS_NEEDED` | Boolean | `true` to validate checksum after download; `false` to skip validation |
| `CKSUM_BUNDLE` | String | Expected `cksum` value (first field); required when `CKSUM_IS_NEEDED=true` |
| `SFTP_SERVER_PATH` | String | Remote directory path on the SFTP server (for example `/releases/project-x`) |

**Agent environment variables:**

| Variable | Purpose |
|----------|---------|
| `SFTP_HOST` | SFTP server hostname, without scheme (required) |
| `SFTP_USER` | Username for HTTPS/curl authentication against the SFTP server (required) |
| `WORKSPACE` | Jenkins workspace directory; log file is written here (required) |

**Jenkins artifact:** `DownloadBundle_*.log` (configure *Post-build action → Archive the artifacts*).

**Operational notes:**

- `CKSUM_IS_NEEDED` is validated **before** the download starts so missing `CKSUM_BUNDLE` fails fast when validation is enabled.
- When validation is disabled, the script still prints `cksum` output for traceability but does not compare against `CKSUM_BUNDLE`.
- Failed downloads and checksum mismatches exit with code `1` and log `[FAIL]` messages; successful validation logs `[PASS]`.

#### PrePost_Jenkins_Job.sh

Shell script for a **Freestyle** job (for example `SV_CHECK_STATUS`) that runs Tool and Oracle health checks on the agent and cluster nodes discovered via `da_dump InstanceStatus`. Thresholds are supplied as Jenkins parameters / environment variables (not a local `health_check.conf`).

**Job parameters / env (required numeric):**

| Variable | Purpose |
|----------|---------|
| `MIN_ATA_HOME_FREE_PCT` | Minimum free disk under `$ATA_HOME` (%) |
| `MIN_TMP_FREE_PCT` | Minimum free disk under `/tmp` (%) |
| `MIN_MEM_AVAILABLE_PCT` | Minimum available memory (%) |
| `MAX_TNSPING_LATENCY_MS` | Maximum Oracle Net latency (ms) |
| `MAX_TABLESPACE_USED_PCT` | Maximum tablespace usage (%) |
| `ORASQL_EXEC_LIMIT` | Flag long-running SQL at or above this duration (seconds) |

**Jenkins artifact:** `Stage4Pre_6Post_Heath_Checks_*.log`.

**Note:** The manual SV1 script under [`scripts/PrePostReleaseScript/`](../scripts/PrePostReleaseScript/) remains available for non-Jenkins runs. Prefer this Freestyle script when triggered from Jenkins or from [`MasterReleaseInstallation.groovy`](../jenkins/pipelines/MasterReleaseInstallation.groovy).

---

### MasterReleaseInstallation.groovy

Declarative **Pipeline** that runs on the Jenkins `built-in` agent and coordinates the full pre/post release installation flow by calling other Jenkins jobs. Writes `pipeline_global_<build>.log`, copies downstream `*.log` artifacts into `logs/`, archives both, then (on success or failure) SCPs logs to a log host (`LOG_HOST_USER@LOG_HOST`, configurable through environment variables) and sends email with `mailx`.

**Stages:**

| Stage | Downstream job / action | Gated by |
|-------|-------------------------|----------|
| Validate parameters | Local validation; parse `BUNDLE_FILE` into `PROJECT_CODE` / `RELEASE_CODE` / `RELEASE_TYPE` / `RELEASE_MODE` | Always |
| 1 — Download the Bundle | `DOWNLOAD_BUNDLES` | `BUNDLE_IS_ALREADY_DOWNLOADED` does not contain `YES` |
| 2 — Create filesystem backups | `sv_backup_app` | `CREATE_FILESYSTEM_BACKUP` |
| 3 — Create DB restore point | `db_restore_point` (`DELETE_ANY_AND_CREATE_NEW_DB_RESTORE_POINT`) | `DELETE_ANY_AND_CREATE_NEW_DB_RESTORE_POINT` |
| 4 — Pre-release health checks | `SV_CHECK_STATUS` | `PRE_RELEASE_INSTALLATION_HEALTH_CHECKS` |
| 5 — Release installation | Per-env install job (see mapping below) | `I_WANT_TO_DEPLOY_THE_RELEASE` |
| 6 — Post-release health checks | `SV_CHECK_STATUS` | `POST_RELEASE_INSTALLATION_HEALTH_CHECKS` |

**Environment → node / install job mapping** (extend in the script as needed):

| `ENVIRONMENT` | `NODE_NAME` | Install job |
|---------------|-------------|-------------|
| `env_trn` | `node-trn` | `INSTALL_REL_TRN` |
| `env_dev` | `node-dev` | `INSTALL_REL_DEV` |
| `env_poc` | `node-poc` | `INSTALL_REL_POC` |
| `env_sit` | `node-sit` | `INSTALL_REL_SIT` |
| `env_uat` | `node-uat` | `INSTALL_REL_UAT` |
| `env_stg` | `node-stg` | `INSTALL_REL_STG` |

**Job parameters (typical):**

| Parameter | Purpose |
|-----------|---------|
| `BUNDLE_IS_ALREADY_DOWNLOADED` | Skip download when value contains `YES` |
| `BUNDLE_FILE` | Bundle name ending in `.tar.gz` (mandatory) |
| `DOWNLOADED_BUNDLE_BUILD_NUMBER` | Prior `DOWNLOAD_BUNDLES` build number when bundle already downloaded |
| `SFTP_SERVER_PATH` / `CKSUM_BUNDLE` | Required when download is needed |
| `ENVIRONMENT` | Target env key (must not be `env_not_selected`) |
| `CREATE_FILESYSTEM_BACKUP` | Enable stage 2 |
| `DELETE_ANY_AND_CREATE_NEW_DB_RESTORE_POINT` | Enable stage 3 |
| `PRE_RELEASE_INSTALLATION_HEALTH_CHECKS` | Enable stage 4 |
| `I_WANT_TO_DEPLOY_THE_RELEASE` / `STEP_NUM` | Enable stage 5; `STEP_NUM` required when deploying |
| `POST_RELEASE_INSTALLATION_HEALTH_CHECKS` | Enable stage 6 |

**Post actions:**

- Archive `pipeline_global_*.log` and `logs/**/*`.
- SCP logs to `LOG_REMOTE_DIR` on `LOG_HOST` and email the build user (`Build User Vars` plugin + `wrap([$class: 'BuildUser'])`) plus an optional extra recipient (`NOTIFY_EXTRA_EMAIL`) via remote `mailx`.

**Plugins / prerequisites:** Copy Artifact, Build User Vars; passwordless SSH from `built-in` to `LOG_HOST_USER@LOG_HOST`; downstream jobs listed above must exist.

**Jenkins artifacts:** `pipeline_global_*.log`, `logs/**/*`.

---

### FileSystemApplicatonBackup.groovy

Four-stage pipeline:

1. **Validate node** — Ensures an agent exists with the label in `params.Environment` (20 s timeout).
2. **Validate disk space** — On all nodes with that label, verifies `ATA_HOME` is set and at least `params.REQUIRED_GB` GB are free; aborts if any node fails.
3. **Parallel backup** — On each node, runs SV1 config/table export (when applicable), builds the filesystem archive with `pigz`, and stashes per-node logs.
4. **Combine logs** — Unstashes all node logs and archives a single `BackupReport_${BUILD_NUMBER}.log` Jenkins artifact.

**Backup outputs (per node on the agent host):**

| Output | Location | When |
|--------|----------|------|
| Config items + Tool table dumps | `$ATA_HOME/ConfigItemsBk_${BUILD_NUMBER}_$DATE/` | `ATA_INSTANCE=SV1` only |
| Filesystem archive | `$ATA_HOME/FSBackup_${BUILD_NUMBER}_$DATE.tar.gz` | All nodes |

**SV1: main Tool tables and config items** — When `ATA_INSTANCE` is `SV1`, the pipeline creates `$ATA_HOME/ConfigItemsBk_${BUILD_NUMBER}_$DATE`, exports data into that folder, then continues with the filesystem backup.

*Tool tables* (`da_dump` / `rt_dump` → CSV files):

| Command | Table / object |
|---------|----------------|
| `rt_dump` | `ATA_INSTANCE` |
| `da_dump` | `CE_PartitionResourceMapping` |
| `da_dump` | `ClusterResource` |
| `da_dump` | `ClusterStandby` |
| `da_dump` | `CustomerPartition` |
| `da_dump` | `CustomerPartitionRange` |
| `da_dump` | `EventErrorPartitionMap` |
| `da_dump` | `EventErrorPartitionRange` |
| `da_dump` | `EventPartitionRange` |
| `da_dump` | `InstanceGroup` |
| `da_dump` | `InstanceGroupMap` |
| `da_dump` | `InstanceStatus` |
| `da_dump` | `InstanceTypeGateway` |
| `da_dump` | `ScheduleReplicationGroup` |

*Config items* — `cfg -x config_items.ini` exports configuration items into the same `ConfigItemsBk_*` directory.

**Filesystem backup (all nodes)** — After the SV1 step (if applicable), copies a versioned `.profile` and builds `FSBackup_${BUILD_NUMBER}_$DATE.tar.gz` under `ATA_HOME` using parallel gzip (`pigz -p 4 -9`). Excludes logs, archives, releases, invoices, output paths, and other entries defined in the pipeline.

**Job parameters:**

| Parameter | Purpose |
|-----------|---------|
| `Environment` | Jenkins label for the target environment (e.g. prod, preprod) |
| `REQUIRED_GB` | Minimum free space required under `ATA_HOME` |

**Agent environment variables:**

| Variable | Purpose |
|----------|---------|
| `ATA_HOME` | Root of the Tool installation (required) |
| `ATA_INSTANCE` | Instance identifier; when `SV1`, triggers table dumps and config export |

**Jenkins artifact:** `BackupReport_${BUILD_NUMBER}.log` (combined report from all nodes).

---

### FileSystemApplicatonRestore.groovy

Four-stage pipeline:

1. **Validate node** — Ensures an agent exists with the label in `params.Environment` (20 s timeout).
2. **Validate disk space and archive** — On each labeled node, verifies `ATA_HOME`, confirms `$ATA_HOME/$RESTORE_FILE` exists, and checks free space ≥ `REQUIRED_GB`; aborts with per-node reasons on failure.
3. **Parallel restore** — On each node, removes existing application directories (`admin`, `data`, `imp`, env files, etc.), extracts the restore archive with `pigz -dc`, and stashes per-node logs. **This stage performs a destructive restore** — stop application services before running in production.
4. **Combine logs** — Archives a single `Restore_Report_${BUILD_NUMBER}.log` Jenkins artifact.

**Job parameters:**

| Parameter | Purpose |
|-----------|---------|
| `Environment` | Jenkins label for the target environment |
| `REQUIRED_GB` | Minimum free space under `ATA_HOME` before restore |
| `RESTORE_FILE` | Filename of the backup tarball under `ATA_HOME` (e.g. `FSBackup_42_20250622.tar.gz`) |

**Agent environment variables:**

| Variable | Purpose |
|----------|---------|
| `ATA_HOME` | Root of the Tool installation (required) |
| `ATA_INSTANCE` | Logged in reports; optional for restore logic |

**Jenkins artifact:** `Restore_Report_${BUILD_NUMBER}.log` (combined report from all nodes).

**Operational notes:**

- The restore archive must already exist on each node at `$ATA_HOME/$RESTORE_FILE` (typically produced by the backup pipeline).
- Stage 3 deletes listed directories under `ATA_HOME` before extraction. Coordinate application shutdown and post-restore validation (for example [`PrePostReleaseScript`](#prepostreleasescript)) before and after use.
- SV1 config/table backups (`ConfigItemsBk_*`) are separate from the filesystem tarball; restore those manually if required.

---

## Shell scripts

| Script | Path | Description |
|--------|------|-------------|
| Pre/post-release health checks | [`scripts/PrePostReleaseScript/PrePost_Release_Health_Checks.sh`](../scripts/PrePostReleaseScript/PrePost_Release_Health_Checks.sh) | Orchestrator run **manually from SV1**. Discovers cluster instances via `da_dump InstanceStatus`, SSHs to each node, runs Tool and Oracle checks, and prints PASS / WARN / FAIL with a release go/no-go summary. |
| Health check thresholds | [`scripts/PrePostReleaseScript/health_check.conf`](../scripts/PrePostReleaseScript/health_check.conf) | Numeric thresholds sourced by the health check script (disk, memory, Oracle latency, tablespace usage, long-running SQL). |

### PrePostReleaseScript

**Where to run:** SV1 Tool Linux server, logged in as the environment user. This version is **not** designed for Jenkins.

```bash
cd /path/to/scripts/PrePostReleaseScript
chmod +x PrePost_Release_Health_Checks.sh
./PrePost_Release_Health_Checks.sh health_check.conf
```

**Log output:** Each run creates `Validation_Checks_YYYYMMDD_HHMMSS.log` in the **current working directory**. The file persists after the script ends and contains the full check output and summary.

**Configurable thresholds** (in `health_check.conf`):

| Variable | Default | Description |
|----------|---------|-------------|
| `MIN_ATA_HOME_FREE_PCT` | 30 | Minimum free disk under `$ATA_HOME` (%) |
| `MIN_TMP_FREE_PCT` | 40 | Minimum free disk under `/tmp` (%) |
| `MIN_MEM_AVAILABLE_PCT` | 40 | Minimum available system memory (%) |
| `MAX_TNSPING_LATENCY_MS` | 200 | Maximum Oracle Net latency (ms) |
| `MAX_TABLESPACE_USED_PCT` | 80 | Maximum Oracle tablespace usage (%) |
| `ORASQL_EXEC_LIMIT` | 300 | Flag SQL at or above this duration (seconds) |

**Release decision:**

| Result | Action |
|--------|--------|
| Any FAIL | Do not install release until resolved |
| WARN only | May proceed; review warnings |
| All PASS | Release can be installed |

Full check list, topology matrix, and troubleshooting: [`scripts/PrePostReleaseScript/README.md`](../scripts/PrePostReleaseScript/README.md).

---

## Using a pipeline in Jenkins

1. Create a **Pipeline** job (one job per `.groovy` file).
2. Under *Pipeline script from SCM*, set the repository URL and script path (for example `jenkins/pipelines/FileSystemApplicatonBackup.groovy` or `jenkins/pipelines/MasterReleaseInstallation.groovy`).
3. Define the job parameters for that pipeline (see sections above).
4. For backup/restore, ensure agents have `ATA_HOME` (and `ATA_INSTANCE` where needed). For the master release pipeline, use the `built-in` agent, install **Build User Vars** and **Copy Artifact**, and ensure SSH access to the log host.

## Using a Freestyle job in Jenkins

### DB restore point management

1. Create a **Freestyle project** (for example DB restore point management).
2. Under *Restrict where this project can be run*, set the label to match the `Environment` choice parameter.
3. Add an *Execute shell* build step that runs the script from SCM (for example `jenkins/pipelines/DBRestorePointManagement.sh`) or paste the script contents.
4. Define job parameters: `Environment`, `ACTION`, and `RESTORE_POINT_NAME` (string, optional for create).
5. Add *Archive the artifacts* post-build action: `DBRestorePoint_*.log`.
6. Ensure the target agent has `ATA_HOME`, `ORACLE_SID`, and `ATASVRCONNECT` configured.

### Bundle download

1. Create a **Freestyle project** for bundle download.
2. Add an *Execute shell* build step that runs `jenkins/pipelines/download_bundle.sh` from SCM or paste the script contents.
3. Define job parameters: `BUNDLE_FILE` (string), `CKSUM_IS_NEEDED` (boolean), `CKSUM_BUNDLE` (string), and `SFTP_SERVER_PATH` (string).
4. Add *Archive the artifacts* post-build action: `DownloadBundle_*.log`.
5. Ensure the Jenkins agent has `SFTP_USER` configured (typically as a credential or node environment variable) and network access to `SFTP_HOST`.

### Pre/post health checks (Jenkins)

1. Create a **Freestyle project** (for example `SV_CHECK_STATUS`).
2. Restrict the job to the target environment / node label as required.
3. Run `jenkins/pipelines/PrePost_Jenkins_Job.sh` from SCM (or paste inline).
4. Pass the six numeric threshold parameters listed above.
5. Archive `Stage4Pre_6Post_Heath_Checks_*.log`.

## Adding new automation

1. Place Jenkins Pipeline (`.groovy`) and Freestyle (`.sh`) job scripts under `jenkins/pipelines/`, and standalone scripts under `scripts/<name>/`.
2. Update this README: folder table (if new top-level folder), Jenkins pipelines / Freestyle jobs tables, and shell scripts table.
3. Commit and push to the remote repository.

## General requirements

| Area | Requirements |
|------|----------------|
| Jenkins | Access to Jenkins with nodes labeled by environment (`Environment` parameter) |
| Backup / restore | `tar`, `pigz`, `df`, `du`, and a POSIX shell on agents |
| SV1 backup | `rt_dump`, `da_dump`, and `cfg` on the agent PATH |
| Permissions | Read/write on `ATA_HOME` on agents (restore requires write and deletes directories) |
| Health checks | Run from **SV1** on Linux; passwordless SSH to cluster nodes; `ksh` on remotes; Tool and Oracle utilities (`svstatus`, `cache_check`, `orasize`, `dbverify`, `orasql`, `tnsping`, `sqlplus`) |
| DB restore points | `sqlplus` with `sysbackup` role; `ATASVRCONNECT`, `ORACLE_SID`, and `ATA_HOME` on the agent; Flashback Database for guaranteed restore points |
| Bundle download | `curl`, `cksum`, and `SFTP_HOST` and `SFTP_USER` on the agent; HTTPS access to the SFTP host |
| Master release installation | `built-in` agent; Copy Artifact + Build User Vars plugins; downstream jobs (`DOWNLOAD_BUNDLES`, `sv_backup_app`, `db_restore_point`, `SV_CHECK_STATUS`, per-env install jobs); SSH/`scp`/`mailx` path to the log host |

## License

See the root README.
