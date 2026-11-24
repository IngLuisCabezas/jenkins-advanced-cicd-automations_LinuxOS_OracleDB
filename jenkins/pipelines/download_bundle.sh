#!/bin/bash
#
# Bundle Download — Jenkins Freestyle job shell script
#
# Jenkins job setup:
#   Job type: Freestyle project
#   Build step: Execute shell — run this script from SCM or inline source
#
#   Post-build action: Archive the artifacts
#     Files to archive: DownloadBundle_*.log
#
# Job parameters:
#   BUNDLE_FILE          (String) — bundle file name to download
#   CKSUM_IS_NEEDED      (Boolean) — true to validate cksum; false skips validation
#   CKSUM_BUNDLE         (String) — expected cksum (first field); required when CKSUM_IS_NEEDED=true
#   SFTP_SERVER_PATH (String) — remote path on SFTP server
#
# Required agent environment variables:
#   SFTP_HOST (hostname only, no scheme), SFTP_USER, WORKSPACE

#set -x

LOG_DIR="${WORKSPACE}"
TS=$(date '+%Y%m%d_%H%M%S')
LOG_FILE="${LOG_DIR}/DownloadBundle_${BUILD_NUMBER:-manual}_${TS}.log"

exec > >(tee -a "$LOG_FILE") 2>&1

echo " "
echo "=============================================="
echo "  Bundle Download"
echo "  Hostname     : $(hostname)"
echo "  Jenkins build: ${BUILD_NUMBER:-manual}"
echo "  Started      : $(date '+%Y-%m-%d %H:%M:%S')"
echo "=============================================="
echo "BUNDLE_FILE          : ${BUNDLE_FILE:-}"
echo "CKSUM_IS_NEEDED      : ${CKSUM_IS_NEEDED:-false}"
echo "CKSUM_BUNDLE         : ${CKSUM_BUNDLE:-}"
echo "SFTP_SERVER_PATH : ${SFTP_SERVER_PATH:-}"
echo "=============================================="
echo " "
echo " "

if [[ "${CKSUM_IS_NEEDED:-false}" == "true" ]]; then
    if [[ -z "${CKSUM_BUNDLE:-}" ]]; then
        echo "|==> [FAIL]: CKSUM_BUNDLE is required when CKSUM_IS_NEEDED=true"
        echo " "
        echo " "
        exit 1
    fi
    echo "CKSUM validation enabled"
else
    echo "CKSUM validation skipped (CKSUM_IS_NEEDED is not true)"
    echo " "
fi

if [[ -z "${SFTP_HOST:-}" ]]; then
    echo "|==> [FAIL]: SFTP_HOST environment variable is not set"
    exit 1
fi
echo "Downloading ${BUNDLE_FILE:-bundle}..."
echo " "

if curl -sS -f -k --noproxy '*' -u "$SFTP_USER" -o "$BUNDLE_FILE" \
  "https://${SFTP_HOST}${SFTP_SERVER_PATH}/${BUNDLE_FILE}"; then

    echo "Bundle downloaded successfully. Continuing..."
    echo " "

else
    echo " "
    echo "|==> [FAIL]:The bundle could not be downloaded successfully. Please try again."
    echo " "
    exit 1
fi

if [[ "${CKSUM_IS_NEEDED:-false}" != "true" ]]; then
    echo " "
    echo "cksum $BUNDLE_FILE"
    cksum $BUNDLE_FILE
    exit 0
fi

echo "cksum $BUNDLE_FILE"
ACTUAL_CKSUM=$(cksum "$BUNDLE_FILE" | awk '{print $1}')
CKSUM_OUTPUT=$(cksum "$BUNDLE_FILE")
echo "Expected CKSUM_BUNDLE : ${CKSUM_BUNDLE:-}"
echo "Actual cksum          : ${ACTUAL_CKSUM}"
echo "cksum output          : ${CKSUM_OUTPUT}"
echo " "
echo " "
if [[ "${ACTUAL_CKSUM}" == "${CKSUM_BUNDLE}" ]]; then
    echo "|==> [PASS]: Correct bundle downloaded, checksum validation passed"
    echo " "
    exit 0
fi

echo "|==> [FAIL]:Bundle downloaded successfully (Wrong bundle was downloaded or Checksum number was wrong), Checksum validation failed. Expected: ${CKSUM_BUNDLE}, got: ${ACTUAL_CKSUM}"
exit 1
