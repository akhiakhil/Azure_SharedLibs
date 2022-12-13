/*
    AKS Deployment script
*/

def call(deployEnv,config, deployType){
    try {
        echo "[INFO] Deployment to ${deployEnv} - ${deployType}"
        checkout scm

        // aksUtils method to set env.ARTIFACT_VERSION and env.CONFIG_VERSION
        aksUtils.setEnvArtifactAndConfigVersion(config, 'deploy')

        switch("${deployType}") {
            case "deploy":
                deployNoBg(deployEnv,config)
            break;
            case "deployTest":
                deployTest(deployEnv,config)
            break;
            case "switch":
                switchTestToLive(deployEnv,config)
            break;
            case "rollback":
                rollbackStage(deployEnv,config)
            break;
            default:
                error "Unknown operation -> ${deployType} from function call vars/DeployToAks.groovy"
            break;
        }

    }catch(ex){
        error("[ERROR] Exception occurred while deploying to AKS in vars/DeployToAKS.groovy -> ${ex}")
    }
}

def deployNoBg(deployEnv,config){
    echo "************************* Deploying App *************************"
    aksUtils.createStashForConfig(config)
    aksUtils.generateStash(config)
    def configHashMap = aksUtils.getConfigHashMap()
    aksUtils.aksDeployPreChecks(deployEnv,config)
    AKSDeployer(deployEnv, configHashMap)

    // push to insights
    def appName = config.productName
    def version = trimOffTimestamp(env.ARTIFACT_VERSION, config)
    def deploymentData = [name: appName, version: version, appType: "aks", rollback: false]
    utilsDeploy.sendDetailsToInsights(deploymentData, deployEnv)

    // store previous version in environment variable
    env.AKS_APP_DEPLOYED = true
    if (fileExists("$WORKSPACE/deployment_playbook/previous_deployment/deployment.yaml")) {
        previousDeploymentYaml = readYaml file: "$WORKSPACE/deployment_playbook/previous_deployment/deployment.yaml"
        env.AKS_ROLLBACK_VERSION_APP = getVersionFromPreviousApp(previousDeploymentYaml, appName)
    }

    // smoke test auto-rollback
    smokeAndRollback(deployEnv, config, "rolling", 'live')
}

def deployTest(deployEnv,config){
    echo "************************* Deploying Test App *************************"

    artifactName = aksUtils.getAppArtifactNameUrlVersion(config)['artifactName']

    //if deploying to DR, verify whether the application is deployed in Prod first
    if (deployEnv.toLowerCase() == "dr"){
        versionMatch = true
        //checking if current deployed version was deployed to Prod env
        lastDeployedVersion = utilsDeploy.getLastDeployedVersion("prod", artifactName, "aks")
        echo "[INFO] Last deployed version in Prod: " + lastDeployedVersion + ", DR deploy version: " + env.ARTIFACT_VERSION
        if (lastDeployedVersion != env.ARTIFACT_VERSION ){
            versionMatch = false
        }
        echo "[INFO] Last deployed version in Prod and current deployed version to DR match: " + versionMatch
        //if returned NOT_FOUND or the DR and Prod app versions don't match, then prompt user input. User must click Proceed to continue
        if (lastDeployedVersion == "NOT_FOUND" || versionMatch == false){
            ansiColor('xterm') {
                input(message: '\033[31m\033[1mDR app version was not deployed to Prod. Please proceed with caution.')
            }
        }
    }

    aksUtils.createStashForConfig(config)
    aksUtils.generateStash(config)
    isApproved = aksUtils.aksDeployPreChecks(deployEnv,config)
    if (isApproved){
        def configHashMap = aksUtils.getConfigHashMap()
        // to enable green deployment is CAS shared libs
        configHashMap.'stages'.'aksDeploy'.'deployEnvironments'."${deployEnv}".'blue-green-flag' = true
        // flag to allow replica count exception to be in effect during values.yaml check in AKSDeployer()
        configHashMap.'checkReplicaCount' = QualityGate("replicaCountException")
        AKSDeployer(deployEnv, configHashMap)

        //stash kubeconfig file
        stash name: "kubeconfig-${deployEnv}", includes: "cnoeaksconfig-${deployEnv}"

        //if rollback file was generated, then stash file
        if(fileExists("${WORKSPACE}/deployment_playbook/rollbackSpec-${deployEnv}.yaml")){

            // store previous version in environment variable before stashing
            rollbackSpecYaml = readYaml file: "${WORKSPACE}/deployment_playbook/rollbackSpec-${deployEnv}.yaml"
            if (deployEnv == 'dr') {
                env.AKS_ROLLBACK_VERSION_DR = getVersionFromRollbackSpec(rollbackSpecYaml)
            } else if (deployEnv == 'drn') {
                env.AKS_ROLLBACK_VERSION_DRN = getVersionFromRollbackSpec(rollbackSpecYaml)
            } else {
                env.AKS_ROLLBACK_VERSION_APP = getVersionFromRollbackSpec(rollbackSpecYaml)
            }

            echo "[INFO]: stashing rollback spec"
            stash name: "rollbackSpec-${deployEnv}", includes: "deployment_playbook/rollbackSpec-${deployEnv}.yaml"
            echo "[INFO]: finished stashing rollback spec"
        }

        // push to insights
        def appName = aksUtils.getAppArtifactNameUrlVersion(config)['artifactName']
        def deploymentData = [name: appName, version: env.ARTIFACT_VERSION, appType: "aks", rollback: false, mode: "test"]
        if ((deployEnv.toLowerCase() == "prod" || deployEnv.toLowerCase() == "dr") && env.DARK_DEPLOY == "true"){
            deploymentData["aksDarkDeploy"] = true
        }
        utilsDeploy.sendDetailsToInsights(deploymentData, deployEnv)

        if (deployEnv == 'dr') {
            env.AKS_DR_DEPLOYED = true
        } else if (deployEnv == 'drn') {
            env.AKS_DRN_DEPLOYED = true
        } else {
            env.AKS_APP_DEPLOYED = true
        }

        // smoke test and auto-rollback
        smokeAndRollback(deployEnv, config, "blueGreen", 'test')
    }else {
        echo "************************* Skipping Deploy App to Test Route *************************"
    }

}

def switchTestToLive(deployEnv,config){
    echo "************************ Switching From Test to Live *************************"
    isApproved = false

    def deployEnvSection = config.stages.aksDeploy.deployEnvironments."${deployEnv}"
    def timeout = 30
    if (deployEnvSection){
        def userInputTimeouts = config.stages.aksDeploy.deployEnvironments."${deployEnv}".'userInputTimeouts'
        if (userInputTimeouts){
            def bluegreenValidation = config.stages.aksDeploy.deployEnvironments."${deployEnv}".userInputTimeouts.'bluegreenValidation'
            if (bluegreenValidation){
                timeout = bluegreenValidation
            }
        }
    }

    // Proceed with Switch checkpoint when DR Deploy Test checkpoint is not skipped and all other non-DR deployment scenarios
    echo "[INFO]: Blue green validation time out is " + timeout + " minutes"
    if (env.SKIP_DR_DEPLOY != "true"){
        isApproved = CheckApproval(config, aksUtils.getApprovalGrp(deployEnv), deployEnv, "${STAGE_NAME}?", timeout, null, true)
    }
    if(isApproved){
        if (aksUtils.getApprovalGrp(deployEnv) == "PROD" && env.DARK_DEPLOY == "false") {
            // switch to Live for PROD and DR can be done only during change window: weekday between 6pm-5am
            // prod/dr deployments outside this window should only be approved if emergency release
            isValid = utils.validDeploymentWindow(deployEnv, config)
            if(!isValid) {
               error '[ERROR] This deployment was not confirmed as emergency deployment -- Aborting deployment'
            }
        }

        unstash 'artifactName'
        unstash "kubeconfig-${deployEnv}"
        verifyKubeconfig(deployEnv,config)
        def appName = config."productName"
        appVersion = readFile file: "$WORKSPACE/artifactVersion.txt"
        appVersion = appVersion.replace(".","-").trim()
        def namespace = config.stages.aksDeploy.deployEnvironments."${deployEnv}"."namespace"
        cloneAnsiblePlaybookRepo()
        ansiColor('xterm') {
            sh "export ANSIBLE_STDOUT_CALLBACK=yaml ANSIBLE_FORCE_COLOR=true && ansible-playbook ${WORKSPACE}/deployment_playbook/blue_green_deployment.yaml -e deployOperation=switch -e deployNamespace=${namespace} -e appName=${appName} -e appVersion=${appVersion} -e workspace=${WORKSPACE} -e env=${deployEnv}"
        }

        // push to insights
        def version = trimOffTimestamp(env.ARTIFACT_VERSION, config)
        def deploymentData = [name: appName, version: version, appType: "aks", rollback: false]
        if ((deployEnv.toLowerCase() == "prod" || deployEnv.toLowerCase() == "dr") && env.DARK_DEPLOY == "true"){
            deploymentData["aksDarkDeploy"] = true
        }
        utilsDeploy.sendDetailsToInsights(deploymentData, deployEnv)

        if (deployEnv == 'dr') {
            env.AKS_DR_DEPLOYED = true
        } else if (deployEnv == 'drn') {
            env.AKS_DRN_DEPLOYED = true
        } else {
            env.AKS_APP_DEPLOYED = true
        }

        // smoke test and auto-rollback
        smokeAndRollback(deployEnv, config, "blueGreen", 'live')
    }else {
        echo "************************* Skipping Switch Test To Live *************************"
    }
}

/**
* Smoke test and rollback
**/
def smokeAndRollback(deployEnv, config, rollbackType, routing='live') {
    def smokeTestResult = false
    if (params.operation != "deploy-to-prod" && params.operation != "deploy-to-dr") {
        smokeTestResult = SoapUITestAKS(deployEnv, config, 'smoke-test', null, routing)
    }
    if (smokeTestResult == false && QualityGate("autoRollback") &&
        params.operation != "build" && params.operation != "deploy-to-dev" &&
        params.operation != "deploy-to-prod" && params.operation != "deploy-to-dr") {

        // rollback apigee first
        def apigeeSmokeTestEnabled = LoadPipelineProps("apigee-smoke-test")
        if (apigeeSmokeTestEnabled) {
            apigeeRollback()
        }

        if (rollbackType == "blueGreen") {
            echo '[INFO] Rolling back blue-green deployment'
            rollbackBlueGreen(deployEnv,config)
            error("[ERROR] Finished Auto-rollback on smoke failure -- End of pipeline")
        } else {
            echo '[INFO] Rolling back rolling deployment'
            rollbackRolling(deployEnv, config)
            error("[ERROR] Finished Auto-rollback on smoke failure -- End of pipeline")
        }
    }
}

def rollbackBlueGreen(deployEnv,config){
    echo "************************* Performing App Rollback *************************"
    if (aksUtils.getApprovalGrp(deployEnv) == "PROD" && env.DARK_DEPLOY == "false") {
        // switch to Live for PROD and DR can be done only during change window: weekday between 6pm-5am
        // prod/dr deployments outside this window should only be approved if emergency release
        isValid = utils.validDeploymentWindow(deployEnv, config)
        if(!isValid) {
           error '[ERROR] This deployment was not confirmed as emergency deployment -- Aborting deployment'
        }
    }
    cloneAnsiblePlaybookRepo()
    def rollbackSpec = ""
    unstash 'artifactName'
    unstash "kubeconfig-${deployEnv}"
    verifyKubeconfig(deployEnv,config)
    try{
        echo "[INFO]: before unstashing rollbackSpec"
        unstash "rollbackSpec-${deployEnv}"
        if(fileExists("$WORKSPACE/deployment_playbook/rollbackSpec-${deployEnv}.yaml")){
            rollbackSpec = "$WORKSPACE/deployment_playbook/rollbackSpec-${deployEnv}.yaml"
            sh "ls ${WORKSPACE}/deployment_playbook"
            echo "[INFO]: setting rollbackSpec to be : " + rollbackSpec
        }
    } catch(e){
        echo "Rollback spec not available. " + e
    }
    echo "[INFO]: rollbackSpec value : " + rollbackSpec
    def appName = config."productName"
    appVersion = readFile file: "$WORKSPACE/artifactVersion.txt"
    appVersion = appVersion.replace(".","-").trim()
    def namespace = config.stages.aksDeploy.deployEnvironments."${deployEnv}"."namespace"

    def rollbackVersion = ""
    def rollbackDeploymentName = ""
    if (rollbackSpec != ""){
        def rollbackSpecYaml = readYaml file: rollbackSpec
        rollbackDeploymentName = rollbackSpecYaml.spec.selector.'app.kubernetes.io/name'
        echo "rollbackDeploymentName: " + rollbackDeploymentName
        rollbackVersion = getVersionFromRollbackSpec(rollbackSpecYaml)
        rollbackVersion = trimOffTimestamp(rollbackVersion, config)
    }
    ansiColor('xterm') {
        sh "export ANSIBLE_STDOUT_CALLBACK=yaml ANSIBLE_FORCE_COLOR=true && ansible-playbook ${WORKSPACE}/deployment_playbook/blue_green_deployment.yaml -e deployOperation=rollback -e deployNamespace=${namespace} -e appName=${appName} -e appVersion=${appVersion} -e rollbackSpec=${rollbackSpec} -e rollbackDeploymentName=${rollbackDeploymentName} -e env=${deployEnv}"
        // log is only created and saved if a deployment is up after rollback
        if (rollbackDeploymentName != ""){
            archiveArtifacts artifacts: '**/*.log', followSymlinks: false
        }
    }

    // pushing to insights after rollback
    if (rollbackSpec != ""){
        def deploymentData = [name: appName, version: rollbackVersion, appType: "aks", rollback: true]
        if ((deployEnv.toLowerCase() == "prod" || deployEnv.toLowerCase() == "dr") && env.DARK_DEPLOY == "true"){
            deploymentData["aksDarkDeploy"] = true
        }
        utilsDeploy.sendDetailsToInsights(deploymentData, deployEnv)
    }
}

def rollbackStage(deployEnv, config) {
    // Request all the approval inputs first
    echo '******************** REQUESTING INPUT FOR ROLLBACK APPROVALS ********************'
    def checkApproval = new CheckApproval()
    def approvalMap = checkApproval.aksRollbackApproval(deployEnv)

    // Roll back each approved deployment type
    if (approvalMap['apigee_approved']) {
        echo '[INFO] Apigee rollback approved, attempting rollback'
        try {
            apigeeRollback()
        } catch (ex) {
            echo '[ERROR] Exception during apigee rollback: ' + ex
        }
    }
    if (approvalMap["app_approved"]) {
        echo '[INFO] App rollback approved, attempting rollback'
        try {
            if (params.'BLUE_GREEN_DEPLOYMENT' == "true") {
                rollbackBlueGreen(deployEnv, config)
            } else {
                rollbackRolling(deployEnv, config)
            }
        } catch (ex) {
            echo '[ERROR] Exception during app rollback: ' + ex
        }
    }
    if (approvalMap['dr_approved']) {
        echo '[INFO] DR rollback approved, attempting rollback'
        try {
            rollbackBlueGreen('dr', config)
        } catch (ex) {
            echo '[ERROR] Exception during dr rollback: ' + ex
        }
    }
    if (approvalMap['drn_approved']) {
        echo '[INFO] DRN rollback approved, attempting rollback'
        try {
            rollbackBlueGreen('drn', config)
        } catch (ex) {
            echo '[ERROR] Exception during drn rollback: ' + ex
        }
    }
}

def cloneAnsiblePlaybookRepo(){
    def githubUser = LoadPipelineProps("checkout-scm-id")
    // checkout deployment Ansible playbook repo using github creds
    withCredentials([usernameColonPassword(credentialsId: githubUser, variable: 'creds')]) {
        ansibleRepo = "https://${creds}@github.kp.org/CSG/kp-aks-ansible-playbook.git"
        def gitClone = "git clone ${ansibleRepo} -b master ."
        sh script: "rm -rf ${WORKSPACE}/deployment_playbook \
        && mkdir -p ${WORKSPACE}/deployment_playbook \
        && cd ${WORKSPACE}/deployment_playbook && ${gitClone} \
        && cd ${WORKSPACE}"
    }
}

def rollbackRolling(deployEnv, config) {
    echo "[INFO] ATTEMPTING TO ROLLBACK APP TO PREVIOUS DEPLOYMENT"
    def deployNamespace = config.'stages'.'aksDeploy'.'deployEnvironments'."${deployEnv}".'namespace'
    def appName = config.productName
    def appVersion = readFile file: "$WORKSPACE/artifactVersion.txt"
    def previousAppSvcYaml = ""
    def previousAppDeployYaml = ""
    if (fileExists("$WORKSPACE/deployment_playbook/previous_deployment/svc.yaml")){
        previousAppSvcYaml = "$WORKSPACE/deployment_playbook/previous_deployment/svc.yaml"
        previousAppDeployYaml = "$WORKSPACE/deployment_playbook/previous_deployment/deployment.yaml"
    }

    ansiColor('xterm') {
        modAppVersion = appVersion.replace(".","-").trim()
        echo "appVersion: " + appVersion
        sh "export ANSIBLE_STDOUT_CALLBACK=yaml ANSIBLE_FORCE_COLOR=true \
            && ansible-playbook ${WORKSPACE}/deployment_playbook/rolling_deployment.yaml \
            -v -e deployOperation=rollback -e previousDeployYaml=${previousAppDeployYaml} -e previousSvcYaml=${previousAppSvcYaml} \
            -e deployNamespace=${deployNamespace} -e appName=${appName.trim()} -e appVersion=${modAppVersion} -e env=${deployEnv}"
        archiveArtifacts artifacts: '**/*.log', followSymlinks: false
    }
    echo "[INFO] ROLLBACK TO PREVIOUS DEPLOYMENT IS COMPLETE"

    // push to insights after rollback
    def rollbackVersion
    if (previousAppDeployYaml != "") {
        previousYaml = readYaml file: previousAppDeployYaml
        rollbackVersion = getVersionFromPreviousApp(previousYaml, appName)
        rollbackVersion = trimOffTimestamp(rollbackVersion, config)
    } else {
        echo '[INFO] No version available from previous app deployment.yaml'
    }
    def deploymentData = [name: appName, version: rollbackVersion, appType: "aks", rollback: true]
    utilsDeploy.sendDetailsToInsights(deploymentData, deployEnv)
}

def getVersionFromPreviousApp(previousApp, appName) {
    if (previousApp.metadata.labels.'app.kubernetes.io/name'.contains(appName)) {
        def version = previousApp.metadata.labels.'app.kubernetes.io/instance'
        echo '[INFO] Version from PreviousApp yaml is: ' + version
        return version
    }
    return null
}

def getVersionFromRollbackSpec(rollbackSpec) {
    def version = rollbackSpec.spec.selector.'app.kubernetes.io/instance'
    if(version.contains("snapshot")) {
        version = version.toUpperCase()
    }
    echo '[INFO] Version from RollbackSpec yaml is: ' + version
    return version
}

def apigeeRollback(){
    // smoke fail auto APIGEE rollback
    try {
        if (env.APIGEE_ROLLBACK_ELIGIBLE == 'true') {
            echo "[INFO] Rolling back APIGEE in env ${params.APIGEE_ENV} to version ${env.APIGEE_ROLLBACK_VERSION}"
            env.APIGEE_ROLLBACK = true
            ApigeeDeployer(["apigeeEnv":"${params.APIGEE_ENV}", "proxyDef":"${env.APIGEE_ROLLBACK_PROXYDEF}"])
        }
    } catch (ex) {
        echo "[ERROR] Exception during APIGEE rollback: ${ex}"
    }
}

def verifyKubeconfig(deployEnv, config){
    //verify if kubeconfig is still valid
    def subscriptionId = config.'stages'.'aksDeploy'.'deployEnvironments'."${deployEnv}".'subscriptionId'
    def resourceGroup = config.'stages'.'aksDeploy'.'deployEnvironments'."${deployEnv}".'resourceGroup'
    def clusterName = config.'stages'.'aksDeploy'.'deployEnvironments'."${deployEnv}".'clusterName'
    def deployNamespace = config.'stages'.'aksDeploy'.'deployEnvironments'."${deployEnv}".'namespace'

    try{
        validKubeconfig = sh returnStdout: true, script: "kubectl --kubeconfig=cnoeaksconfig-${deployEnv} get pods -n ${deployNamespace}"
    }
    catch(e){
        def deployerUtils = new utils.deployerutils()
        deployerUtils.azlogin()
        deployerUtils.createKubeConfig(subscriptionId,resourceGroup,clusterName,deployNamespace)
        sh "rm -f $WORKSPACE/cnoeaksconfig-${environment}"
        sh "cp $WORKSPACE/cnoeaksconfig $WORKSPACE/cnoeaksconfig-${environment}"
        validKubeconfig = sh returnStdout: true, script: "kubectl --kubeconfig=cnoeaksconfig-${deployEnv} get pods -n ${deployNamespace}"
    }
}

def trimOffTimestamp(version, config) {
    if (version.toLowerCase().contains("snapshot") && config.artifactType == "NODEJS") {
        version = (version =~ /(\d+)\.(\d+)\.(\d+)\-SNAPSHOT/)[0][0]
    }
    return version
}