pipeline {
    agent {
        label 'built-in'
    }

    stages {

   stage('🔍 Validate Parameters') {
    steps {
        cleanWs()

        script {
            try {
                // Promote params → env (available in every later stage)
                env.BUNDLE_IS_ALREADY_DOWNLOADED    = params.BUNDLE_IS_ALREADY_DOWNLOADED.toString()           
                env.BUNDLE_FILE                     = params.BUNDLE_FILE?.toString()?.trim()
                env.DOWNLOADED_BUNDLE_BUILD_NUMBER  = params.DOWNLOADED_BUNDLE_BUILD_NUMBER?.toString()?.trim() ?: ''          
                env.SFTP_SERVER_PATH            = params.SFTP_SERVER_PATH?.toString()?.trim() ?: ''
                env.CKSUM_BUNDLE                    = params.CKSUM_BUNDLE?.toString()?.trim() ?: ''
                env.ENVIRONMENT                     = params.ENVIRONMENT?.toString()?.trim() 
                env.I_WANT_TO_DEPLOY_THE_RELEASE    = params.I_WANT_TO_DEPLOY_THE_RELEASE.toString()
                env.STEP_NUM                        = params.STEP_NUM?.toString()?.trim() ?: ''
                env.CREATE_FILESYSTEM_BACKUP        = params.CREATE_FILESYSTEM_BACKUP.toString()
                env.DELETE_ANY_AND_CREATE_NEW_DB_RESTORE_POINT      = params.DELETE_ANY_AND_CREATE_NEW_DB_RESTORE_POINT.toString()
                env.PRE_RELEASE_INSTALLATION_HEALTH_CHECKS           = params.PRE_RELEASE_INSTALLATION_HEALTH_CHECKS.toString()
                env.I_WANT_TO_DEPLOY_THE_RELEASE                    = params.I_WANT_TO_DEPLOY_THE_RELEASE.toString()
                env.POST_RELEASE_INSTALLATION_HEALTH_CHECKS          = params.POST_RELEASE_INSTALLATION_HEALTH_CHECKS.toString()
              //env.NODE_NAME
                def nodeByEnv = [
                    'env_trn': 'node-trn',
                    'env_dev': 'node-dev',
                    'env_poc': 'node-poc',
                    'env_sit': 'node-sit',
					'env_uat': 'node-uat',
					'env_stg': 'node-stg'
                    // 'env_trn2': 'node-trn',
                ]

                env.NODE_NAME = nodeByEnv[env.ENVIRONMENT]
                    if (!env.NODE_NAME) {
                error "No NODE_NAME mapping for ENVIRONMENT='${env.ENVIRONMENT}'"
                }

             //env.INSTALL_RELEASE_JOB_NAME
                def InstallReleaseJobNameByEnv = [
                    'env_trn': 'INSTALL_REL_TRN',
                    'env_dev': 'INSTALL_REL_DEV',
                    'env_poc': 'INSTALL_REL_POC',
                    'env_sit': 'INSTALL_REL_SIT',
                    'env_uat': 'INSTALL_REL_UAT',
					'env_stg': 'INSTALL_REL_STG'
                ]

                env.INSTALL_RELEASE_JOB_NAME = InstallReleaseJobNameByEnv[env.ENVIRONMENT]
                    if (!env.INSTALL_RELEASE_JOB_NAME) {
                error "No INSTALL_RELEASE_JOB_NAME mapping for ENVIRONMENT='${env.ENVIRONMENT}'"
                }

                def buildNum = currentBuild.number
                env.GLOBAL_LOG = "pipeline_global_${buildNum}.log"
                def startTime = new Date().format('yyyy-MM-dd HH:mm:ss')
                writeFile file: env.GLOBAL_LOG, text: "PIPELINE START - ${startTime}\n\n"

                def alreadyDownloaded = env.BUNDLE_IS_ALREADY_DOWNLOADED.contains("YES")
                //sh "echo 'BUNDLE_IS_ALREADY_DOWNLOADED: ${params.BUNDLE_IS_ALREADY_DOWNLOADED}' >> ${env.GLOBAL_LOG}"
                sh "echo '' >> ${env.GLOBAL_LOG}"
                def bundleFile = env.BUNDLE_FILE?.trim()
                if (!bundleFile) {
                    error "BUNDLE_FILE is mandatory"
                    sh "echo '' >> ${env.GLOBAL_LOG}"
                }
                if (!bundleFile.endsWith('.tar.gz')) {
                    error "BUNDLE_FILE must end with .tar.gz"
                    sh "echo '' >> ${env.GLOBAL_LOG}"
                }

                // --- Parse BUNDLE_FILE → PROJECT_CODE / RELEASE_CODE / RELEASE_TYPE / RELEASE_MODE ---
                // Example: ABC_REL_1.2.3_HOTFIX_FULL.tar.gz
                //   PROJECT_CODE = ABC
                //   RELEASE_CODE = REL_1.2.3
                //   RELEASE_TYPE = HOTFIX
                //   RELEASE_MODE = FULL

                bundleFile = bundleFile.replaceFirst(/\.tar\.gz$/, "")
                def parts = bundleFile.split("_")
                if (parts.size() < 4) {
                    error "Unable to parse BUNDLE_FILE format: '${env.BUNDLE_FILE}'"
                }
                env.PROJECT_CODE = parts[0]
                env.RELEASE_MODE = parts[-1]
                env.RELEASE_TYPE = parts[-2]
                env.RELEASE_CODE = parts[1..-3].join("_")
    
                if (alreadyDownloaded) {
                    def buildNumber = env.DOWNLOADED_BUNDLE_BUILD_NUMBER?.toString()?.trim()
                    if (!buildNumber || !(buildNumber ==~ /^\d+$/)) {
                        error "DOWNLOADED_BUNDLE_BUILD_NUMBER is mandatory and must contain only numbers"
                    }
                } else {
                    if (!env.SFTP_SERVER_PATH?.trim()) {
                        error "SFTP_SERVER_PATH is mandatory when BUNDLE_IS_ALREADY_DOWNLOADED is NO"
                    }

                    def cksum = env.CKSUM_BUNDLE?.toString()?.trim()
                    if (!cksum || !(cksum ==~ /^\d+$/)) {
                        error "CKSUM_BUNDLE is mandatory and must contain only numbers"
                    }

                }
                
                if (env.ENVIRONMENT?.toString()?.trim() == 'env_not_selected') {
                    error "env_not_selected — please select an environment"
                }
                sh "echo 'ENVIRONMENT: ${params.ENVIRONMENT}' >> ${env.GLOBAL_LOG}"
                sh "echo '' >> ${env.GLOBAL_LOG}"               

                // I_WANT_TO_DEPLOY_THE_RELEASE (boolean) + STEP_NUM
                def wantDeploy = env.I_WANT_TO_DEPLOY_THE_RELEASE.toString().equalsIgnoreCase('true')
                if (wantDeploy) {
                    def stepNum = env.STEP_NUM?.toString()?.trim()
                    // OK: 1, 200  OR  201.000001, 201.240000 (exactly 6 digits after '.')
                    // FAIL: 12.000, 12.1, 12.0000001
                    if (!stepNum || !(stepNum ==~ /^\d+(\.\d{6})?$/)) {
                     error "STEP_NUM is mandatory when deploying: use an integer (e.g. 1, 200) or exactly 6 digits after the point (e.g. 201.000001, 201.240000)"
                     }
                }
                sh "echo '' >> ${env.GLOBAL_LOG}"
                
            } catch (err) {
                // ✅ now it works
                sh "echo '❌ VALIDATION FAILED: ${err.getMessage()}' >> ${env.GLOBAL_LOG}"
                throw err
            }
        }
    }
}

        stage('📦 Stage 1: Download the Bundle') {

            steps {
                script {

                    def v = env.BUNDLE_IS_ALREADY_DOWNLOADED.toString()

                    if (v.toUpperCase().contains('YES')) {
                        sh "echo '--- Stage 1 SKIPPED: Bundle already downloaded ---' >> ${env.GLOBAL_LOG}"
                        sh "echo '' >> ${env.GLOBAL_LOG}"
                        sh "echo 'Validation OK (bundle already downloaded).' >> ${env.GLOBAL_LOG}"
                        sh "echo 'BUNDLE_FILE=${env.BUNDLE_FILE}' >> ${env.GLOBAL_LOG}"
                        sh "echo 'DOWNLOADED_BUNDLE_BUILD_NUMBER=${env.DOWNLOADED_BUNDLE_BUILD_NUMBER}' >> ${env.GLOBAL_LOG}"
                        sh "echo '' >> ${env.GLOBAL_LOG}"
                     return
                    }
                    
                    try {
                        sh "echo '--- Stage 1 START ---' >> ${env.GLOBAL_LOG}"
                        sh "echo '' >> ${env.GLOBAL_LOG}"
                        sh "echo 'Validation OK (download required).' >> ${env.GLOBAL_LOG}"
                        sh "echo 'SFTP_SERVER_PATH=${env.SFTP_SERVER_PATH}' >> ${env.GLOBAL_LOG}"
                        sh "echo 'BUNDLE_FILE=${env.BUNDLE_FILE}' >> ${env.GLOBAL_LOG}"
                        sh "echo 'CKSUM_BUNDLE=${env.CKSUM_BUNDLE}' >> ${env.GLOBAL_LOG}"
                        sh "echo '' >> ${env.GLOBAL_LOG}"

                        def jobResult = build job: 'DOWNLOAD_BUNDLES',
                            wait: true,
                            propagate: false,
                            parameters: [
                                string(name: 'SFTP_SERVER_PATH', value: env.SFTP_SERVER_PATH),
                                string(name: 'BUNDLE_FILE', value: env.BUNDLE_FILE),
                                booleanParam(name: 'CKSUM_IS_NEEDED', value: true),
                                string(name: 'CKSUM_BUNDLE', value: env.CKSUM_BUNDLE)
                            ]

                        sh "echo 'DOWNLOAD_BUNDLES result: ${jobResult.result}' >> ${env.GLOBAL_LOG}"
                        sh "echo 'DOWNLOAD_BUNDLES build:  ${jobResult.number}' >> ${env.GLOBAL_LOG}"
                        env.DOWNLOADED_BUNDLE_BUILD_NUMBER = jobResult.number.toString()
                        sh "echo 'DOWNLOADED_BUNDLE_BUILD_NUMBER=${env.DOWNLOADED_BUNDLE_BUILD_NUMBER}' >> ${env.GLOBAL_LOG}"

                        step([
                            $class: 'CopyArtifact',
                            projectName: 'DOWNLOAD_BUNDLES',
                            selector: [$class: 'SpecificBuildSelector', buildNumber: jobResult.number.toString()],
                            target: 'logs',
                            filter: '*.log'
                        ])

                        if (jobResult.result != 'SUCCESS') {
                            error "DOWNLOAD_BUNDLES failed"
                        }
                        sh "echo '' >> ${env.GLOBAL_LOG}"
                        sh "echo '--- Stage 1 SUCCESS ---' >> ${env.GLOBAL_LOG}"
                        sh "echo '' >> ${env.GLOBAL_LOG}"

                    } catch (err) {
                        sh "echo '' >> ${env.GLOBAL_LOG}"
                        sh "echo 'Stage 1 ERROR: ${err}' >> ${env.GLOBAL_LOG}"
                        throw err
                    }
                }
            }
        }

     stage('💾 Stage 2: Create filesystem Backups') {

            steps {
                script {

                    def st2 = params.CREATE_FILESYSTEM_BACKUP
                    
                    if (!st2 ) {
                        sh "echo '--- Stage 2 SKIPPED: Create filesystem Backups ---' >> ${env.GLOBAL_LOG}"
                        sh "echo '' >> ${env.GLOBAL_LOG}"
                        sh "echo 'CREATE_FILESYSTEM_BACKUP: ${env.CREATE_FILESYSTEM_BACKUP}' >> ${env.GLOBAL_LOG}"
                        sh "echo '' >> ${env.GLOBAL_LOG}"
                        return
                    }
                    try {
                        sh "echo -e '\\n--- Stage 2 START ---' >> ${env.GLOBAL_LOG}"
                        sh "echo '' >> ${env.GLOBAL_LOG}"
                        sh "echo 'CREATE_FILESYSTEM_BACKUP: ${env.CREATE_FILESYSTEM_BACKUP}' >> ${env.GLOBAL_LOG}"
                        sh "echo 'Triggering sv_backup_app' >> ${env.GLOBAL_LOG}"
                        sh "echo 'Environment=${env.ENVIRONMENT}' >> ${env.GLOBAL_LOG}"
                        sh "echo 'REQUIRED_GB=15' >> ${env.GLOBAL_LOG}"
                        sh "echo '' >> ${env.GLOBAL_LOG}"
                        def jobResult = build job: 'sv_backup_app',
                            wait: true,
                            propagate: false,
                            parameters: [
                                string(name: 'Environment', value: env.ENVIRONMENT),
                                string(name: 'REQUIRED_GB', value: '15')
                            ]

                            sh "echo 'sv_backup_app result: ${jobResult.result}' >> ${env.GLOBAL_LOG}"
                            sh "echo 'sv_backup_app build: ${jobResult.number}' >> ${env.GLOBAL_LOG}"

                        step([
                            $class: 'CopyArtifact',
                            projectName: 'sv_backup_app',
                            selector: [$class: 'SpecificBuildSelector', buildNumber: jobResult.number.toString()],
                            target: 'logs',
                            filter: '*.log'
                            //optional: true
                        ])

                        if (jobResult.result != 'SUCCESS') {
                            error "sv_backup_app failed with result: ${jobResult.result}"
                        }
                        sh "echo '' >> ${env.GLOBAL_LOG}"
                        sh "echo '--- Stage 2 SUCCESS ---' >> ${env.GLOBAL_LOG}"
                        sh "echo '' >> ${env.GLOBAL_LOG}"
                    } catch (err) {
                        sh "echo 'Stage 2 ERROR: ${err}' >> ${env.GLOBAL_LOG}"
                        throw err
                    }
                }
            }
        }

        stage('🗄️ Stage 3: Create DB Restore Point') {

            steps {
                script {

                    def st3 = params.DELETE_ANY_AND_CREATE_NEW_DB_RESTORE_POINT
                    
                    if (!st3 ) {
                        sh "echo -e '\\n--- Stage 3 SKIPPED: Create DB Restore Point ---' >> ${env.GLOBAL_LOG}"
                        sh "echo '' >> ${env.GLOBAL_LOG}"
                        sh "echo 'DELETE_ANY_AND_CREATE_NEW_DB_RESTORE_POINT: ${env.DELETE_ANY_AND_CREATE_NEW_DB_RESTORE_POINT}' >> ${env.GLOBAL_LOG}"
                        sh "echo '' >> ${env.GLOBAL_LOG}"
                        return
                    }
                    try {
                        sh "echo -e '\\n--- Stage 3 START ---' >> ${env.GLOBAL_LOG}"
                        sh "echo '' >> ${env.GLOBAL_LOG}"
                        sh "echo 'DELETE_ANY_AND_CREATE_NEW_DB_RESTORE_POINT: ${env.DELETE_ANY_AND_CREATE_NEW_DB_RESTORE_POINT}' >> ${env.GLOBAL_LOG}"
                        sh "echo 'Triggering SV DB Restore Point ' >> ${env.GLOBAL_LOG}"

                        sh "echo 'NODE_NAME=${env.NODE_NAME}' >> ${env.GLOBAL_LOG}"
                        sh "echo 'ACTION=DELETE_ANY_AND_CREATE_NEW_DB_RESTORE_POINT' >> ${env.GLOBAL_LOG}"
                        sh "echo '' >> ${env.GLOBAL_LOG}"
                        def jobResult = build job: 'db_restore_point',
                            wait: true,
                            propagate: false,
                            parameters: [
                                string(name: 'NODE_NAME', value: env.NODE_NAME),
                                string(name: 'ACTION', value: 'DELETE_ANY_AND_CREATE_NEW_DB_RESTORE_POINT')
                            ]

                            sh "echo 'db_restore_point result: ${jobResult.result}' >> ${env.GLOBAL_LOG}"
                            sh "echo 'db_restore_point build: ${jobResult.number}' >> ${env.GLOBAL_LOG}"

                        step([
                            $class: 'CopyArtifact',
                            projectName: 'db_restore_point',
                            selector: [$class: 'SpecificBuildSelector', buildNumber: jobResult.number.toString()],
                            target: 'logs',
                            filter: '*.log'
                            //optional: true
                        ])

                        if (jobResult.result != 'SUCCESS') {
                            error "db_restore_point failed with result: ${jobResult.result}"
                        }
                        sh "echo '' >> ${env.GLOBAL_LOG}"
                        sh "echo '--- Stage 3 SUCCESS ---' >> ${env.GLOBAL_LOG}"
                        sh "echo '' >> ${env.GLOBAL_LOG}"
                    } catch (err) {
                        sh "echo 'Stage 3 ERROR: ${err}' >> ${env.GLOBAL_LOG}"
                        throw err
                    }
                }
            }
        }

        stage('🔍 Stage 4: Pre_Release Installation Health Checks') {

       steps {
                script {

                    def st4 = params.PRE_RELEASE_INSTALLATION_HEALTH_CHECKS
                    
                    if (!st4 ) {
                        sh "echo -e '\\n--- Stage 4 SKIPPED: Pre_Release Installation Health Checks ---' >> ${env.GLOBAL_LOG}"
                        sh "echo '' >> ${env.GLOBAL_LOG}"
                        sh "echo 'PRE_RELEASE_INSTALLATION_HEALTH_CHECKS: ${env.PRE_RELEASE_INSTALLATION_HEALTH_CHECKS}' >> ${env.GLOBAL_LOG}"
                        sh "echo '' >> ${env.GLOBAL_LOG}"
                        return
                    }
                    try {
                        sh "echo -e '\\n--- Stage 4 START ---' >> ${env.GLOBAL_LOG}"
                        sh "echo '' >> ${env.GLOBAL_LOG}"
                        sh "echo 'PRE_RELEASE_INSTALLATION_HEALTH_CHECKS: ${env.PRE_RELEASE_INSTALLATION_HEALTH_CHECKS}' >> ${env.GLOBAL_LOG}"
                        sh "echo '' >> ${env.GLOBAL_LOG}"
                        sh "echo 'Triggering Pre_Release Health Checks ' >> ${env.GLOBAL_LOG}"
                        sh "echo '' >> ${env.GLOBAL_LOG}"
                        sh "echo 'NODE_NAME=${env.NODE_NAME}' >> ${env.GLOBAL_LOG}"
                        sh "echo 'MIN_ATA_HOME_FREE_PCT= 10' >> ${env.GLOBAL_LOG}"
                        sh "echo 'MIN_TMP_FREE_PCT= 10' >> ${env.GLOBAL_LOG}"
                        sh "echo 'MIN_MEM_AVAILABLE_PCT= 10' >> ${env.GLOBAL_LOG}"
                        sh "echo 'MAX_TNSPING_LATENCY_MS= 10' >> ${env.GLOBAL_LOG}"
                        sh "echo 'MAX_TABLESPACE_USED_PCT= 95' >> ${env.GLOBAL_LOG}"
                        sh "echo 'ORASQL_EXEC_LIMIT= 300' >> ${env.GLOBAL_LOG}"

                        sh "echo '' >> ${env.GLOBAL_LOG}"
                        def jobResult = build job: 'SV_CHECK_STATUS',
                            wait: true,
                            propagate: false,
                            parameters: [
                                string(name: 'NODE_NAME', value: env.NODE_NAME),
                                string(name: 'MIN_ATA_HOME_FREE_PCT', value: '10'),
                                string(name: 'MIN_TMP_FREE_PCT', value: '10'),
                                string(name: 'MIN_MEM_AVAILABLE_PCT', value: '10'),
                                string(name: 'MAX_TNSPING_LATENCY_MS', value: '10'),
                                string(name: 'MAX_TABLESPACE_USED_PCT', value: '95'),
                                string(name: 'ORASQL_EXEC_LIMIT', value: '300')
                            ]

                            sh "echo 'SV_CHECK_STATUS: ${jobResult.result}' >> ${env.GLOBAL_LOG}"
                            sh "echo 'SV_CHECK_STATUS build: ${jobResult.number}' >> ${env.GLOBAL_LOG}"

                        step([
                            $class: 'CopyArtifact',
                            projectName: 'SV_CHECK_STATUS',
                            selector: [$class: 'SpecificBuildSelector', buildNumber: jobResult.number.toString()],
                            target: 'logs',
                            filter: '*.log'
                            //optional: true
                        ])
 
                        if (jobResult.result != 'SUCCESS') {
                            error "SV_CHECK_STATUS failed with result: ${jobResult.result}"
                        }
                        sh "echo '' >> ${env.GLOBAL_LOG}"
                        sh "echo 'Stage 4 Result: No blocking failures detected. look for warnings if any, Log: Stage4Pre_6Post_Heath_Checks_*_log' >> ${env.GLOBAL_LOG}"
                        sh "echo '' >> ${env.GLOBAL_LOG}"
                        sh "echo '--- Stage 4 SUCCESS ---' >> ${env.GLOBAL_LOG}"
                        sh "echo '' >> ${env.GLOBAL_LOG}"
                    } catch (err) {
                        sh "echo '' >> ${env.GLOBAL_LOG}"
                        sh "echo 'Stage 4 Result: The release cannot be installed. All detected failures must be resolved first, Log: Stage4Pre_6Post_Heath_Checks_*_log' >> ${env.GLOBAL_LOG}"
                        sh "echo '' >> ${env.GLOBAL_LOG}"
                        sh "echo 'Stage 4 ERROR: ${err}' >> ${env.GLOBAL_LOG}"
                        throw err
                    }
                }
            }

        }

    stage('🚀 Stage 5: Release Installation') {

        steps {
            script {

                def st5 = params.I_WANT_TO_DEPLOY_THE_RELEASE

                if (!st5) {
                    sh "echo -e '\\n--- Stage 5 SKIPPED: Release Installation ---' >> ${env.GLOBAL_LOG}"
                    sh "echo '' >> ${env.GLOBAL_LOG}"
                    sh "echo 'I_WANT_TO_DEPLOY_THE_RELEASE: ${env.I_WANT_TO_DEPLOY_THE_RELEASE}' >> ${env.GLOBAL_LOG}"
                    sh "echo '' >> ${env.GLOBAL_LOG}"
                    return
                }
                
                try {
                    sh "echo -e '\\n--- Stage 5 START ---' >> ${env.GLOBAL_LOG}"
                    sh "echo '' >> ${env.GLOBAL_LOG}"

                    // ✅ DEBUG + ENV LOGGING
                    def jobName = "${env.INSTALL_RELEASE_JOB_NAME}".trim()

                    sh "echo 'INSTALL_RELEASE_JOB_NAME=${jobName}' >> ${env.GLOBAL_LOG}"
                    sh "echo '' >> ${env.GLOBAL_LOG}"

                    sh "echo 'ENVIRONMENT=${env.ENVIRONMENT}' >> ${env.GLOBAL_LOG}"
                    sh "echo 'DOWNLOADED_BUNDLE_BUILD_NUMBER=${env.DOWNLOADED_BUNDLE_BUILD_NUMBER}' >> ${env.GLOBAL_LOG}"
                    sh "echo '' >> ${env.GLOBAL_LOG}"

                    sh "echo 'PROJECT_CODE=${env.PROJECT_CODE}' >> ${env.GLOBAL_LOG}"
                    sh "echo 'RELEASE_CODE=${env.RELEASE_CODE}' >> ${env.GLOBAL_LOG}"
                    sh "echo 'RELEASE_TYPE=${env.RELEASE_TYPE}' >> ${env.GLOBAL_LOG}"
                    sh "echo 'RELEASE_MODE=${env.RELEASE_MODE}' >> ${env.GLOBAL_LOG}"
                    sh "echo '' >> ${env.GLOBAL_LOG}"

                   def bn = env.DOWNLOADED_BUNDLE_BUILD_NUMBER?.toString()?.trim()

                   def buildSelectorXml =
    "<SpecificBuildSelector plugin=\"copyartifact@795.ve8e151429b_27\">" +
    "<buildNumber>${bn}</buildNumber>" +
    "</SpecificBuildSelector>"

                    def jobBuild = build(
                        job: jobName,
                        wait: false,
                        waitForStart: true,
                        propagate: false,
                        parameters: [
                            string(name: 'PROJECT_CODE', value: "${env.PROJECT_CODE}"),
                            string(name: 'RELEASE_CODE', value: "${env.RELEASE_CODE}"),
                            string(name: 'RELEASE_TYPE', value: "${env.RELEASE_TYPE}"),
                            string(name: 'RELEASE_MODE', value: "${env.RELEASE_MODE}"),
                            string(name: 'STEP_NUM', value: "${env.STEP_NUM}"),
                            [$class: 'StringParameterValue',
                                name: 'BUILD_NUM',
                                value: buildSelectorXml
                            ]
                        ])

                    // Console URL while remote job is running
                    def consoleUrl = "${env.JENKINS_URL}job/${env.INSTALL_RELEASE_JOB_NAME}/${jobBuild.number}/console"
                    echo "Open remote job console: ${consoleUrl}"
                    sh "echo '${jobName} console: ${consoleUrl}' >> ${env.GLOBAL_LOG}"
                    sh "echo '' >> ${env.GLOBAL_LOG}"
                    
                    // Wait until remote job finishes
                    timeout(time: 12, unit: 'HOURS') {
                        waitUntil(initialRecurrencePeriod: 5000) {
                            return jobBuild.result != null
                        }
                    }

                    sh "echo '${jobName}: ${jobBuild.result}' >> ${env.GLOBAL_LOG}"
                    sh "echo '${jobName} build: ${jobBuild.number}' >> ${env.GLOBAL_LOG}"
                
                    // ✅ COPY ARTIFACTS (FIXED)
                    step([
                        $class: 'CopyArtifact',
                        projectName: jobName,
                        selector: [$class: 'SpecificBuildSelector', buildNumber: jobBuild.number.toString()],
                        target: 'logs',
                        filter: '*.log',
                        optional: true
                    ])

                    if (jobBuild.result != 'SUCCESS') {
                        error "${jobName} failed with result: ${jobBuild.result}"
                    }
                        sh "echo '' >> ${env.GLOBAL_LOG}"
                        sh "echo 'Stage 5 Result: Release installation completed successfully' >> ${env.GLOBAL_LOG}"
                        sh "echo '' >> ${env.GLOBAL_LOG}"
                        sh "echo '--- Stage 5 SUCCESS ---' >> ${env.GLOBAL_LOG}"
                        sh "echo '' >> ${env.GLOBAL_LOG}"
                    } catch (err) {
                        sh "echo '' >> ${env.GLOBAL_LOG}"
                        sh "echo 'Stage 5 Result: The release installation failed. All detected failures must be resolved first before retrying.' >> ${env.GLOBAL_LOG}"
                        sh "echo '' >> ${env.GLOBAL_LOG}"
                        sh "echo 'Stage 5 ERROR: ${err}' >> ${env.GLOBAL_LOG}"
                        throw err
                    }
                }
            }
        }

    stage('✅ Stage 6: Post_Release Installation Health Checks') {

       steps {
                script {

                    def st6 = params.POST_RELEASE_INSTALLATION_HEALTH_CHECKS
                    
                    if (!st6 ) {
                        sh "echo -e '\\n--- Stage 6 SKIPPED: Post_Release Installation Health Checks ---' >> ${env.GLOBAL_LOG}"
                        sh "echo '' >> ${env.GLOBAL_LOG}"
                        sh "echo 'POST_RELEASE_INSTALLATION_HEALTH_CHECKS: ${env.POST_RELEASE_INSTALLATION_HEALTH_CHECKS}' >> ${env.GLOBAL_LOG}"
                        sh "echo '' >> ${env.GLOBAL_LOG}"
                        return
                    }
                    try {
                        sh "echo -e '\\n--- Stage 6 START ---' >> ${env.GLOBAL_LOG}"
                        sh "echo '' >> ${env.GLOBAL_LOG}"
                        sh "echo 'POST_RELEASE_INSTALLATION_HEALTH_CHECKS: ${env.POST_RELEASE_INSTALLATION_HEALTH_CHECKS}' >> ${env.GLOBAL_LOG}"
                        sh "echo '' >> ${env.GLOBAL_LOG}"
                        sh "echo 'Triggering Post_Release Health Checks ' >> ${env.GLOBAL_LOG}"
                        sh "echo '' >> ${env.GLOBAL_LOG}"
                        sh "echo 'NODE_NAME=${env.NODE_NAME}' >> ${env.GLOBAL_LOG}"
                        sh "echo 'MIN_ATA_HOME_FREE_PCT= 10' >> ${env.GLOBAL_LOG}"
                        sh "echo 'MIN_TMP_FREE_PCT= 10' >> ${env.GLOBAL_LOG}"
                        sh "echo 'MIN_MEM_AVAILABLE_PCT= 10' >> ${env.GLOBAL_LOG}"
                        sh "echo 'MAX_TNSPING_LATENCY_MS= 10' >> ${env.GLOBAL_LOG}"
                        sh "echo 'MAX_TABLESPACE_USED_PCT= 95' >> ${env.GLOBAL_LOG}"
                        sh "echo 'ORASQL_EXEC_LIMIT= 300' >> ${env.GLOBAL_LOG}"

                        sh "echo '' >> ${env.GLOBAL_LOG}"
                        def jobResult = build job: 'SV_CHECK_STATUS',
                            wait: true,
                            propagate: false,
                            parameters: [
                                string(name: 'NODE_NAME', value: env.NODE_NAME),
                                string(name: 'MIN_ATA_HOME_FREE_PCT', value: '10'),
                                string(name: 'MIN_TMP_FREE_PCT', value: '10'),
                                string(name: 'MIN_MEM_AVAILABLE_PCT', value: '10'),
                                string(name: 'MAX_TNSPING_LATENCY_MS', value: '10'),
                                string(name: 'MAX_TABLESPACE_USED_PCT', value: '95'),
                                string(name: 'ORASQL_EXEC_LIMIT', value: '300')
                            ]

                            sh "echo 'SV_CHECK_STATUS: ${jobResult.result}' >> ${env.GLOBAL_LOG}"
                            sh "echo 'SV_CHECK_STATUS build: ${jobResult.number}' >> ${env.GLOBAL_LOG}"
                            sh "echo '' >> ${env.GLOBAL_LOG}"
                        step([
                            $class: 'CopyArtifact',
                            projectName: 'SV_CHECK_STATUS',
                            selector: [$class: 'SpecificBuildSelector', buildNumber: jobResult.number.toString()],
                            target: 'logs',
                            filter: '*.log'
                            //optional: true
                        ])
 
                        if (jobResult.result != 'SUCCESS') {
                            //error "SV_CHECK_STATUS failed with result: ${jobResult.result}"
                            sh "echo 'Stage 6 Result: SV_CHECK_STATUS failed with result: ${jobResult.result}, Log: Stage4Pre_6Post_Heath_Checks_*_log' >> ${env.GLOBAL_LOG}"
                            sh "echo 'see the log for more details before notifying the RM team that the environment ${env.ENVIRONMENT} is ready to be used' >> ${env.GLOBAL_LOG}"
                            //currentBuild.result = 'SUCCESS'   // or 'UNSTABLE' if you prefer
                            return
                        }       
                        
                        sh "echo 'Stage 6 Result: No blocking failures detected. look for warnings if any, Log: Stage4Pre_6Post_Heath_Checks_*_log' >> ${env.GLOBAL_LOG}"
                        sh "echo '' >> ${env.GLOBAL_LOG}"
                        sh "echo '--- Stage 6 SUCCESS ---' >> ${env.GLOBAL_LOG}"
                        sh "echo '' >> ${env.GLOBAL_LOG}"
                    } catch (err) {
                        sh "echo '' >> ${env.GLOBAL_LOG}"
                        sh "echo 'Stage 6 Result: The release cannot be installed. All detected failures must be resolved first, Log: Stage4Pre_6Post_Heath_Checks_*_log' >> ${env.GLOBAL_LOG}"
                        sh "echo '' >> ${env.GLOBAL_LOG}"
                        sh "echo 'Stage 6 ERROR: ${err}' >> ${env.GLOBAL_LOG}"
                        throw err
                    }
                }
            }

        }

    }

    post {
        always {
            sh """
               echo "" >> ${env.GLOBAL_LOG}
               echo "FINAL STATUS: ${currentBuild.currentResult} - \$(date '+%Y-%m-%d %H:%M:%S')" >> ${env.GLOBAL_LOG}
               """
            archiveArtifacts artifacts: "pipeline_global_*.log, logs/**/*", fingerprint: true, allowEmptyArchive: true

 
    script {
        wrap([$class: 'BuildUser']) {
            def recipient = [env.BUILD_USER_EMAIL, env.NOTIFY_EXTRA_EMAIL].findAll { it }.join(' ')
            def logHost   = env.LOG_HOST ?: 'log-host.example.com'
            def logUser   = env.LOG_HOST_USER ?: 'app_user'
            def logDir    = env.LOG_REMOTE_DIR ?: '/opt/app/workspace/INSTALL_RELEASE'
            def mailFrom  = env.MAIL_FROM ?: 'Release Automation <jenkins@example.com>'
            def userName  = env.BUILD_USER

            def mailSubject = "Pipeline Master - Pre-Post Steps for Release Installation ${env.ENVIRONMENT ?: 'n/a'} - ${currentBuild.currentResult}"
            def mailBody = """Final pipeline status: ${currentBuild.currentResult}

Executor username: ${userName}

Below are the logs for each stage:

PIPELINE: pipeline_global_*.log (Description of each stage)

Stage 1: Download the Bundle → Stage1_*.log (if applicable)  
Stage 2: Create Filesystem Backup → Stage2_*.log (if applicable)  
Stage 3: Create DB Restore Point → Stage3_*.log (if applicable)  
Stage 4: Pre-Release Health Checks → Stage46_*.log (if applicable)  
Stage 5: Release Installation → Install_*.log (if applicable)  
Stage 6: Post-Release Health Checks → Stage46_*.log (if applicable)  

Please review the logs for detailed information about each step.

Regards,
Release Automation
"""
            // Escape for single-quoted remote shell strings
            def shSingleQuote = { String s -> s.replace("'", "'\\''") }

            //sh """
            sh """#!/bin/bash +x
            #set -e

            REMOTE=${logUser}@${logHost}
            REMOTE_DIR=${logDir}
            LOCAL_WS='${env.WORKSPACE}'

            # 1) Clean remote folder (must run via ssh — path is on the log host, not built-in)
            ssh -T -q \$REMOTE "
            if [ -d \$REMOTE_DIR ]; then
                rm -rf \$REMOTE_DIR/*
                echo 'Folder cleaned'
            else
                mkdir -p \$REMOTE_DIR
                echo 'Folder created'
            fi
            "

            # 2) Copy logs from Jenkins workspace (only if files exist; avoid literal *.log)
            shopt -s nullglob
            ROOT_LOGS=( \"\$LOCAL_WS\"/*.log )
            STAGE_LOGS=( \"\$LOCAL_WS\"/logs/*.log )
            if [ \${#ROOT_LOGS[@]} -gt 0 ]; then
                scp -q \"\${ROOT_LOGS[@]}\" \$REMOTE:\$REMOTE_DIR/
            fi
            if [ \${#STAGE_LOGS[@]} -gt 0 ]; then
                scp -q \"\${STAGE_LOGS[@]}\" \$REMOTE:\$REMOTE_DIR/
            fi
            shopt -u nullglob

            # verify on REMOTE (not on built-in agent)
            ssh -T -q \$REMOTE \"ls -alrth \$REMOTE_DIR\"

            # 3) Email — same pattern that works on the log host (ksh + mailx -a/-s/-r)
            ssh -T -q \$REMOTE /bin/bash --noprofile --norc << 'REMOTE_EOF'
LOG_DIR="${logDir}"
set --
for f in "\$LOG_DIR"/*.log; do
  [ -f "\$f" ] || continue
  set -- "\$@" -a "\$f"
done

mailx "\$@" \\
-s '${shSingleQuote(mailSubject)}' \\
-r "${mailFrom}" \\
${recipient} <<EOM
${mailBody}
EOM
REMOTE_EOF
            """
        }
            }
        }

        failure {
            echo "❌ Pipeline FAILED"
        }

        success {
            echo "✅ Pipeline SUCCESS"
        }
    }
}
