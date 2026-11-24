#!/bin/bash
#
# DB Restore Point Management — Jenkins Freestyle job shell script
#
# Jenkins job setup:
#   Job type: Freestyle project
#   Build step: Execute shell — run this script from SCM or inline source
#   Restrict where this project can be run: label matching Environment parameter
#
#   Post-build action: Archive the artifacts
#     Files to archive: DBRestorePoint_*.log
#
# Job parameters:
#   Environment          (Choice or use "Restrict where..." with label)
#   ACTION               (Choice)
#     - List_Restore_Points
#     - Create_new_Restore_Point
#     - Delete_a_Restore_Point
#     - DELETE_ANY_AND_CREATE_NEW_DB_RESTORE_POINT
#   RESTORE_POINT_NAME   (String) — required when ACTION is Delete_a_Restore_Point;
#                        optional for Create_new_Restore_Point and
#                        DELETE_ANY_AND_CREATE_NEW_DB_RESTORE_POINT (auto-generated if empty)
#
# Required agent environment variables (configured on the Jenkins node):
#   ATA_HOME, ORACLE_SID, ATASVRCONNECT
#
# Database connection: sqlplus "${ATASVRCONNECT} as sysbackup"

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
NC='\033[0m'

log_info()  { echo -e "${GREEN}|==> [INFO]:${NC} $*"; }
log_warn()  { echo -e "${YELLOW}|==> [WARN]:${NC} $*"; }
log_error() { echo -e "${RED}|==> [ERROR]:${NC} $*" >&2; }

die() {
    log_error "$*"
    exit 1
}

# Capture console output to a Jenkins workspace log artifact
LOG_DIR="${WORKSPACE:-.}"
TS=$(date '+%Y%m%d_%H%M%S')
LOG_FILE="${LOG_DIR}/Stage3_DBRestorePoint_${BUILD_NUMBER:-manual}_${TS}.log"
exec > >(tee -a "$LOG_FILE") 2>&1

# Load SingleView / Oracle environment when available
if [[ -n "${ATA_HOME:-}" && -f "${ATA_HOME}/.profile" ]]; then
    # shellcheck source=/dev/null
    . "${ATA_HOME}/.profile" >/dev/null 2>&1 || true
fi
if [[ -f "${HOME}/.profile" ]]; then
    # shellcheck source=/dev/null
    . "${HOME}/.profile" >/dev/null 2>&1 || true
fi

echo "=============================================="
echo "  DB Restore Point Management"
echo "  Hostname     : $(hostname)"
echo "  Jenkins build: ${BUILD_NUMBER:-manual}"
echo "  Started      : $(date '+%Y-%m-%d %H:%M:%S')"
echo "=============================================="

# --- Validate required environment variables ---
missing=()
[[ -z "${ATA_HOME:-}" ]]       && missing+=("ATA_HOME")
[[ -z "${ORACLE_SID:-}" ]]     && missing+=("ORACLE_SID")
[[ -z "${ATASVRCONNECT:-}" ]]  && missing+=("ATASVRCONNECT")

if (( ${#missing[@]} > 0 )); then
    die "Required environment variable(s) not set on this node: ${missing[*]}"
fi

log_info "ATA_HOME       : ${ATA_HOME}"
log_info "ORACLE_SID     : ${ORACLE_SID}"
log_info "ATA_INSTANCE   : ${ATA_INSTANCE:-<not set>}"

# --- Validate ACTION parameter (Jenkins choice) ---
ACTION="${ACTION:-}"
if [[ -z "$ACTION" ]]; then
    die "ACTION parameter is not set. Choose: List_Restore_Points | Create_new_Restore_Point | Delete_a_Restore_Point | DELETE_ANY_AND_CREATE_NEW_DB_RESTORE_POINT"
fi

RESTORE_POINT_NAME="${RESTORE_POINT_NAME:-}"

validate_restore_point_name() {
    local name="$1"
    if [[ -z "$name" ]]; then
        die "RESTORE_POINT_NAME is required for this action"
    fi
    if [[ ! "$name" =~ ^[A-Za-z][A-Za-z0-9_$#]*$ ]]; then
        die "Invalid restore point name '${name}'. Use letters, digits, _, \$, #; must start with a letter."
    fi
}

run_sqlplus() {
    local sql_block="$1"

    set +e

    output=$(sqlplus -S "${ATASVRCONNECT} as sysbackup" <<EOF
WHENEVER SQLERROR EXIT SQL.SQLCODE
SET PAGESIZE 5000
SET LINESIZE 32000
SET FEEDBACK ON
SET HEADING ON
SET VERIFY OFF
SET TIMING OFF
SET SQLPROMPT ''
${sql_block}
EXIT
EOF
)
    rc=$?
    set -e

    echo "$output"

    # Detect real Oracle errors
    if echo "$output" | grep -q "ORA-"; then
        log_error "Oracle error detected during SQL execution"
        log_error "SQL Output:"
        echo "$output"
        return 1
    fi

    log_info "sqlplus exit code: $rc (ignored if no ORA-)"
    return 0
}

list_restore_points() {
    log_info "Listing restore points..."
    run_sqlplus "
SELECT name,
       scn,
       TO_CHAR(time, 'YYYY-MM-DD HH24:MI:SS') AS restore_time
FROM   v\$restore_point
ORDER BY time;
"
    log_info "Restore point list completed."
}

# Returns restore point names ordered by time (oldest first), one per line
get_restore_point_names() {
    set +e
    names=$(sqlplus -S "${ATASVRCONNECT} as sysbackup" <<EOF
WHENEVER SQLERROR EXIT SQL.SQLCODE
SET PAGESIZE 0
SET LINESIZE 32000
SET FEEDBACK OFF
SET HEADING OFF
SET VERIFY OFF
SET TIMING OFF
SET SQLPROMPT ''
SELECT name FROM v\$restore_point ORDER BY time;
EXIT
EOF
)
    rc=$?
    set -e

    if (( rc != 0 )) || echo "$names" | grep -q "ORA-"; then
        log_error "Failed to query restore points"
        echo "$names"
        return 1
    fi

    # Trim blank lines
    echo "$names" | sed '/^[[:space:]]*$/d'
}

resolve_restore_point_name() {
    if [[ -z "$RESTORE_POINT_NAME" ]]; then
        RESTORE_POINT_NAME="JENKINS_RP_${BUILD_NUMBER:-0}_$(date +%Y%m%d_%H%M%S)"
        log_info "No RESTORE_POINT_NAME supplied; using generated name: ${RESTORE_POINT_NAME}"
    else
        validate_restore_point_name "$RESTORE_POINT_NAME"
    fi
}

create_restore_point() {
    local name="$1"
    log_info "Creating restore point '${name}' (GUARANTEE FLASHBACK DATABASE)..."
    run_sqlplus "
CREATE RESTORE POINT ${name} GUARANTEE FLASHBACK DATABASE;
"
    log_info "Restore point '${name}' created successfully."
}

delete_restore_point() {
    local name="$1"
    log_info "Deleting restore point '${name}'..."
    run_sqlplus "
DROP RESTORE POINT ${name};
"
    log_info "Restore point '${name}' deleted successfully."
}

# Optional connectivity check
if command -v tnsping >/dev/null 2>&1; then
    msec=$(tnsping "$ORACLE_SID" 2>/dev/null | awk -F'[()]' '/msec/ {print $2}' | awk '{print $1}')
    if [[ -z "$msec" ]]; then
        log_warn "tnsping ${ORACLE_SID}: invalid or missing latency value"
    else
        log_info "tnsping ${ORACLE_SID}: ${msec} ms"
    fi
fi

case "$ACTION" in
    List_Restore_Points)
        list_restore_points
        ;;

    Create_new_Restore_Point)
        resolve_restore_point_name
        create_restore_point "$RESTORE_POINT_NAME"
        list_restore_points
        ;;

    Delete_a_Restore_Point)
        validate_restore_point_name "$RESTORE_POINT_NAME"
        delete_restore_point "$RESTORE_POINT_NAME"
        list_restore_points
        ;;

    DELETE_ANY_AND_CREATE_NEW_DB_RESTORE_POINT)
        # 0 restore points  → create new
        # 1 restore point   → delete it, then create new
        # >1 restore points → delete oldest, then create new
        resolve_restore_point_name

        mapfile -t existing_rps < <(get_restore_point_names)
        rp_count=${#existing_rps[@]}

        if (( rp_count == 0 )); then
            log_info "No existing restore points found; creating a new one."
        elif (( rp_count == 1 )); then
            log_info "Found 1 restore point; deleting it before creating a new one."
            delete_restore_point "${existing_rps[0]}"
        else
            log_info "Found ${rp_count} restore points; deleting the oldest ('${existing_rps[0]}') before creating a new one."
            delete_restore_point "${existing_rps[0]}"
        fi

        create_restore_point "$RESTORE_POINT_NAME"
        list_restore_points
        ;;

    *)
        die "Unknown ACTION '${ACTION}'. Expected: List_Restore_Points | Create_new_Restore_Point | Delete_a_Restore_Point | DELETE_ANY_AND_CREATE_NEW_DB_RESTORE_POINT"
        ;;
esac

echo "----------------------------------------------"
echo "  Finished: $(date '+%Y-%m-%d %H:%M:%S')"
echo "  Log file   : ${LOG_FILE}"
echo "----------------------------------------------"
