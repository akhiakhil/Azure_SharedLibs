/*

    Utils to use CAS aks deployer scripts

*/

/* generate and stash files with artifact and config artifact urls

List of files to be stashed:

unstash 'artifactType' artifactType.txt

unstash 'configDir' // Getting configDir that was stashed in build stage. If the stashed file is not present , then fetching the config from Workspace

unstash 'AppImageDetails' AppImageRepoURL.txt  AppVersion.txt

*/

//import groovy.json.JsonSlurperClassic

 

def generateStash(config){

    def ARTIFACTORY_URL = LoadPipelineProps('artifactory-url')

    //def config = readJSON file: "$WORKSPACE/pipeline.json"

    def artifactType = config.'artifactType'

    def artifactVersion

    def artifactName

    def downloadUrl

    def dockerRepoUrl = ""

    def packageUri = ""

 

    def appName = config.'productName'

    if (!appName?.trim())

        throw new Exception('productName not specified in pipeline json')

    echo 'productName from pipeline json: ' + appName

 

     //Retrieve imageDir

    def projectName = config.'imageDir'

    if (!projectName?.trim())

        projectName = CreateApplicationID(appName)

    echo 'projectName: ' + projectName

   

    echo "[INFO] Detected build type -> $artifactType"

    if(artifactType == "NODEJS"){

        artifactNameAndUrl = getAppArtifactNameUrlVersion(config)

        artifactVersion = artifactNameAndUrl['artifactVersion']

        artifactName = artifactNameAndUrl['artifactName']

        env.APP_ARTIFACT_PATH = artifactNameAndUrl['artifactUrl']

        downloadUrl = env.APP_ARTIFACT_PATH

        packageUri = "npm-release"

        // Getting full version with timestamp

        if(artifactNameAndUrl['artifactUrl'].contains("npm-snapshot")){

            artifactVersion = artifactNameAndUrl['artifactUrl'].split('/')[-1]

            artifactVersion = artifactVersion.minus('.tgz').minus("${artifactName}-")

            echo "[INFO] artifactVersion: ${artifactVersion}"

        }

        // For node JS artifact name is appname@version

        artifactName = artifactName + "@" + artifactVersion

        env.SRC_BUILD_NUMBER = ''

        //node specific stash file

        sh script: "echo $downloadUrl > nodeArtifactURL.txt"

        stash name: "nodeArtifactUrl", includes: "nodeArtifactURL.txt"

    }else if(artifactType == "JAVA"){

        artifactDetails = getAppArtifactNameUrlVersion(config)

        artifactVersion = artifactDetails['artifactVersion']

        artifactName = artifactDetails['artifactName']

        extension = config.stages.'maven-build'.'app-extension' ?: "war"

        artifactName = artifactName + "-" + artifactVersion

        // search for artifact in artifactory

        env.APP_ARTIFACT_PATH = utils.searchMavenArtifact(artifactName, ARTIFACTORY_URL, extension)

        downloadUrl = env.APP_ARTIFACT_PATH

        env.SRC_BUILD_NUMBER = getBuildNumFromUrl(downloadUrl) // parse url for build number

        packageUri = downloadUrl

    }else {

        echo "Couldn't determine build type from pipeline.json"

        error(utils.getError("AKS005"))

    }

 

    // make it to lowercase before writting to stash

    artifactName = artifactName.toLowerCase()

    artifactVersion = artifactVersion.toLowerCase()

 

    def gitCommit = sh returnStdout: true, script: 'git rev-parse  HEAD | tr -d \'\n\''

    sh script: "echo $gitCommit > gitCommit.txt && \

                echo $artifactType > artifactType.txt && \

                echo $artifactVersion > artifactVersion.txt && \

                echo $artifactName > artifactName.txt && \

                echo $downloadUrl > artifactURL.txt" // JAVA - for java app

    stash name: "artifactName", includes: "gitCommit.txt,artifactConfigURL.txt,artifactName.txt,artifactURL.txt,artifactType.txt,artifactVersion.txt,pom.xml,package.json"

 

    // to used when deploying specific artifact version -- if push event job, params.AKS_ENV will be empty

    dockerRepoUrl = getDockerRepoUrl(params.AKS_ENV, projectName, appName)

 

    echo "[INFO] Generating stash for deployment"

    sh script: "echo $artifactVersion > AppVersion.txt && \

                echo $dockerRepoUrl > AppImageRepoURL.txt"

    stash name: "AppImageDetails", includes: "AppImageRepoURL,AppVersion.txt"

   // saving deployment files

   sh script: "echo $packageUri > packageUri.txt"

   stash name: "packageUri", includes: "packageUri.txt"

}

 

/*

    Read CI Env from pipeline.json and return

*/

def getCiEnv(config){

    def ciEnv

    def buildStageName

    if(config.artifactType == "JAVA") {

        buildStageName = "maven-build"

    } else if(config.artifactType == "NODEJS") {

        buildStageName = "npm-build"

    } else {

        echo "[ERROR] Unknown artifactType in pipeline.json -> ${config.artifactType}"

        error(utils.getError("AKS005"))

    }

    ciEnv = config.stages."${buildStageName}".'ci-environment'

    if(!ciEnv) {

        echo "[INFO] ci-environment was not configured in pipeline.json. Setting ci Env to be dev"

        ciEnv = "dev"

    }

    return ciEnv

}

 

/*

    AKSDeployer.groovy needs configDir as a stash

*/

def createStashForConfig(config){

    //def config = readJSON file: "$WORKSPACE/pipeline.json"

    def ARTIFACTORY_URL = LoadPipelineProps('artifactory-url')

    def githubUser = LoadPipelineProps("checkout-scm-id")

    def configRepoUrl = config.stages."aksDeploy"."configRepoUrl"

    def configVersion = env.CONFIG_VERSION

    if(!configRepoUrl) {

            echo "ERROR: Please configure configUrl in pipeline.json"

            error(utils.getError("AKS006"))

        }

    if(!configVersion){

            echo "ERROR: Please configure configVersion in pipeline.json"

            error(utils.getError("AKS007"))

        }

    echo "[INFO] Config version -> $configVersion"

 

    dir("config"){

        try{

            git branch: 'master', credentialsId: "$githubUser", url: "$configRepoUrl"

        } catch(e){

            // tempoary for supporting sandbox

            git branch: 'master', credentialsId: "azure-shared-lib-githubapp", url: "$configRepoUrl"

        }

        configPom = readMavenPom file: "pom.xml"

        artifactName = (configPom.artifactId + "-" + configVersion).toLowerCase()

        downloadUrl = utils.searchMavenArtifact(artifactName, ARTIFACTORY_URL, "zip")

        env.CONFIG_BUILD_NUMBER = getBuildNumFromUrl(downloadUrl) // parse url for build number

        echo "[INFO] Config Artifact Url -> $downloadUrl"

        sh "rm -r * && curl -O ${downloadUrl}"

        fileName = sh script: "ls | grep .zip", returnStdout: true

        folderName = fileName.replace(".zip","").trim()

        if (configVersion.toLowerCase().contains('latest-snapshot')){

            versionNumber = folderName.split("config-")[1].split("-")[0]

            folderName = folderName.replaceAll(/\d*\.\d*\.\d*.*/,"") + versionNumber + "-" + "SNAPSHOT"

        } else if (configVersion.toLowerCase().contains('snapshot')) {

            folderName = folderName.replaceAll(/\d*\.\d*\.\d*.*/,"")

            version = configVersion.split("-")

            folderName = folderName + version[0] + "-" + version[1]

        }

        setupConfigDir = "unzip -q ${fileName}\n" +

                        "rm ${fileName}\n" +

                        "mv ${folderName}" + '/env . \n' +

                        "rm -r ${folderName}\n" +

                        'cp -R env/* . && rm -r env'

        sh script: "$setupConfigDir", returnStdout: true

    }

 

    dir("${WORKSPACE}"){

        stash includes: "config/**", name: 'configDir'

        sh "rm -r config"

    }

}

 

/*

    Based on environment formulate the docker repo url. Copied code from CAS Shared Libs -> AKSDeployer.groovy -> promoteAppImage()

*/

 

def getDockerRepoUrl(environment, projectName, appName){

    echo "[INFO] Formulate Docker Repo URL"

    def virtualAppImageUrl =''

    //Virtual Repos

    def DEV_VIMG_REG = "dockerdev.devopsrepo.kp.org"

    def PREPROD_VIMG_REG = "dockerpreprod.devopsrepo.kp.org"

    def PROD_VIMG_REG = "dockerprod.devopsrepo.kp.org"

    echo "environment param is: " + environment

 

    if (environment == "error" || environment == null || environment == ""){

        environment = "dev"

    }

    echo "env used to determine docker repo url: " + environment

 

    try {

        if (environment.contains('dev') || environment.contains('eng')

            || environment.contains('trng')) {

            virtualAppImageUrl = DEV_VIMG_REG + '/' + projectName + '/' + appName

        } else if (environment.contains('dit')

                || environment.contains('qa')

                || environment.contains('uat')

                || environment.contains('preprod')

                || environment.contains('perf')

                                     || environment.contains('drn')) {

            virtualAppImageUrl = PREPROD_VIMG_REG + '/' + projectName + '/' + appName

        } else if (environment.contains('prod') || environment.contains('dr')) {

            virtualAppImageUrl = PROD_VIMG_REG + '/' + projectName + '/' + appName

        }

    } catch (err) {

        echo "Failed to determine docker repo url -> ${err}"

        error(utils.getError("AKS001"))

    }

    echo 'virtualAppImageUrl = ' + virtualAppImageUrl

    return virtualAppImageUrl

}

 

def getConfigHashMap(){

    // Additional Read - to comply with AKS hash map requirement

    inputFile = readFile("${env.WORKSPACE}/pipeline.json")

    //hashMap = new JsonSlurperClassic().parseText(inputFile)

    hashMap = readJSON text: inputFile

    //"ansible" key to be used as flag in CAS shared libs, AKSDeployer() to differentiate between Ansible and groovy deployment flows

    hashMap.put("ansible", true)

    return hashMap

}

 

/*

    Based on env name determine the approval group. Will be used from InitAksPipeline & DeployToAKS

*/

def getApprovalGrp(deployEnv){

    switch(deployEnv.toLowerCase()) {

        case "dev":

            return "DEV"

        case ["qa", "perf"]:

            return "QA"

        break;

        case ["uat", "uat2", "drn"]:

            return "PREPROD"

        break;

        case ["prod", "dr"]:

            return "PROD"

        break;

        default:

            echo "[Error] Could Not Recognize Deploy Param ${deployEnv}"

            error(utils.getError("AKS008"))

        break;

    }

}

 

/*

    Determine if a given environment is HIGHER environment. To be used for gating criterion.

*/

def isHigherEnv(deployEnv){

    def listOfHigherEnvs = ["drn", "qa", "uat", "preprod", "pre-prod", "perf", "dr", "prod"]

    if(listOfHigherEnvs.contains(deployEnv.toLowerCase())){ return true}

    return false

}

 

/*

    Verifying artifact Sonar Quality gate status

*/

def artifactSonarGateStatus(deployEnv, artifactUrl){

    // check if project is eligible for gating. env.ENABLE_QUALITY_GATE if set true, go and enforce.

    if (!QualityGate("sonar")) {

        return false

    }

 

    echo "[INFO] Check the artifact properties and match with environment entry criteria ${artifactUrl}"

    def apiUrl = "${artifactUrl}".replaceAll("/artifactory/", "/artifactory/api/storage/")

    def propsList = "sonar_quality_gate,sonar_blocker,sonar_major,sonar_critical,sonar_coverage"

 

    def artifactProps = sh(returnStdout: true, script: "curl '${apiUrl}?properties=${propsList}'")

    artifactProps = readJSON text: artifactProps

    echo "[INFO] Artifact properties : " + "${artifactProps}"

 

    if (artifactProps.properties){

        echo "[INFO] Fetching Sonar Quality Gate property from Artifactory"

       sonar_quality_gate_status = artifactProps.properties.sonar_quality_gate[0]

    } else {

        echo "[INFO] Sonar Quality Gate property not found in Artifactory"

        sonar_quality_gate_status = "NOT_FOUND"

    }

 

    echo "[INFO] Sonar Quality Gate Status for the Artifact : " + "${sonar_quality_gate_status}"

 

    //validate sonar quality gate status for higher environments only

    if (isHigherEnv(deployEnv)){

        if(sonar_quality_gate_status == "OK"){

            return true

        } else {

            echo ("[ERROR] Artifact does not meet Sonar Quality Gate standards.\n Failing deployment to ${deployEnv}")

            error(utils.getError("AKS009"))

        }

    }

}

 

/*

    Pre-checks before proceeding with deployment

*/

def aksDeployPreChecks(deployEnv,config){

    def isHigherEnv = isHigherEnv(deployEnv)

    def isApproved = true

    //Validating build artifact's sonar quality gate status and regression thresholds

    if(isHigherEnv){

        CheckEnvironmentDeployCriteria(deployEnv, config, params.operation)

    }

 

    //set build display name

    currentBuild.displayName = "#${env.ARTIFACT_VERSION}${env.SRC_BUILD_NUMBER}_${env.CONFIG_VERSION}${env.CONFIG_BUILD_NUMBER}_${env.BUILD_NUMBER}@${deployEnv}"

 

    //smoke test validation for QA space only

    if (deployEnv.equalsIgnoreCase("qa")){

        //smoke test environment for deployEnv must be defined before checking for test-suite-xml and ui-env

        if (config.stages."smoke-test"."test-config"."${deployEnv}" == null){

            echo "[Error]: Smoke test environment configuration for ${deployEnv} not defined."

            error(utils.getError("AKS010"))

        }

 

        testSuiteXml = config.stages."smoke-test"."test-config"."${deployEnv}"."test-suite-xml" ?: null

        testDataRepoURL = config.stages."smoke-test"."test-data-repo-url" ?: null

        smokeTestEnv = config.stages."smoke-test"."test-config"."${deployEnv}"."test-env-name" ?: null

 

        echo "testSuiteXml for ${deployEnv} in Jenkinsfile = ${testSuiteXml}"

        echo "testDatarepoGitHttpURL for ${deployEnv} in Jenkinsfile = ${testDataRepoURL}"

        echo "smokeTestEnv for ${deployEnv} in Jenkinsfile = ${smokeTestEnv}"

        if (testSuiteXml == null || testDataRepoURL == null || smokeTestEnv == null){

            echo "[Error]: One or all of mandatory Smoke test parameters: test-suite-xml, test-data-repo-url or ui-env are not defined for ${deployEnv}. Deployment cannot proceed."

            error(utils.getError("AKS011"))

        }

    }

 

    //CheckApproval Deployment approval for Higher Envs, or blue/green for checkpoint purposes

    if (isHigherEnv || (params.'BLUE_GREEN_DEPLOYMENT' == 'true')) {

        //setting skip stage parameter to true to add skip option when deploying to DR or DRN

        if (deployEnv.toLowerCase() == "dr" || deployEnv.toLowerCase() == "drn") {

            env.SKIP_DR_DEPLOY = false

            isApproved = CheckApproval(config, getApprovalGrp(deployEnv), deployEnv, null, 30, null, true)

            if (isApproved == null){

                env.SKIP_DR_DEPLOY = true

            }

        }

        else{

            CheckApproval(config, getApprovalGrp(deployEnv), deployEnv)

        }

    }

    return isApproved

}

 

/*

    Get App artifact url for Node & Java projects

*/

def getAppArtifactNameUrlVersion(config, extension=null){

    try{

        def artifactUrl

        def artifactName

        def artifactVersion = env.ARTIFACT_VERSION

        def artifactoryUrl = LoadPipelineProps('artifactory-url')

 

        if(config.'artifactType' == "NODEJS"){

            sourceDir = config.stages.'npm-build'.'source-directory' ?: ""

            def packageJson = readJSON file: "$WORKSPACE/$sourceDir/package.json"

            artifactName = packageJson.name

            artifactUrl = utils.getNpmArtifactUrl(artifactoryUrl, artifactName, artifactVersion)

        }else if(config.'artifactType' == "JAVA") {

            sourceDir = config.stages.'maven-build'.'source-directory' ?: ""

            def configExtension = config.stages.'maven-build'.'app-extension' ?: "war"

            def deployableModule = config.stages.'maven-build'.'module-name' ?: ""

            def pomFolder = ""

            if(sourceDir && deployableModule){

                pomFolder = sourceDir + "/" + deployableModule

            }else if(sourceDir || deployableModule){

                pomFolder = sourceDir ? sourceDir : deployableModule

            }

            pomFolder = (pomFolder == "" || pomFolder == null) ? "${WORKSPACE}/pom.xml" : "${WORKSPACE}/${pomFolder}/pom.xml"

            echo "[info] Reading pom.xml from -> $pomFolder"

            pom = readMavenPom file: pomFolder

            artifactName = pom.artifactId

            nameForSearch = pom.artifactId + "-" + artifactVersion

 

            // search for artifact in artifactory

              if (extension) {

                  artifactUrl = utils.searchMavenArtifact(nameForSearch, artifactoryUrl, extension)

            } else {

                  artifactUrl = utils.searchMavenArtifact(nameForSearch, artifactoryUrl, configExtension)

              }

          }else {

              echo "Exception from vars/aksUtils.groovy -> getAppArtifactNameUrlVersion() : Unkown artifactType in pipeline.json"

              error(utils.getError("AKS005"))

          }

 

        // validate all values were extracted

        if(!(artifactUrl && artifactName && artifactVersion)){

            throw new Exception("Couldn't determine artifactUrl -> $artifactUrl / artifactName -> $artifactName / artifactVersion -> $artifactVersion")

        }

        return ["artifactUrl": artifactUrl, "artifactName": artifactName, "artifactVersion": artifactVersion]

    }catch(e){

        error "Exception from vars/aksUtils.groovy -> getAppArtifactNameUrlVersion() : ${e}"

    }

}

 

def getApigeeArtifactNameUrlVersion(config) {

    //overridding these to use parent pom.xml for APIGEE artifacts

    if(config.stages.'maven-build'){

        config.stages.'maven-build'.'source-directory' = ""

        config.stages.'maven-build'.'module-name' = ""

    }

    return getAppArtifactNameUrlVersion(config, 'zip')

}

 

def getTestArtifactUrl(config, testType){

    try {

        def testArtifactUrl

        def artifactoryUrl = LoadPipelineProps('artifactory-url')

        if(config.'artifactType' == "NODEJS"){

            // test artifact and app artifact are the same. App artifact would have a folder holding test suites.

            testArtifactUrl = getAppArtifactNameUrlVersion(config)['artifactUrl']

        }else if(config.'artifactType' == "JAVA"){

            // test artifact is separate than app artifact. Has its own gavc

            testSuiteDir = config.stages."${testType}".'test-suite-dir' ?: null

            testSuitePom = readMavenPom file: "$WORKSPACE/$testSuiteDir/pom.xml"

            testArtifactVersion = env.ARTIFACT_VERSION

            artifactName = testSuitePom.artifactId + "-" + testArtifactVersion

            // search for artifact in artifactory

            testArtifactUrl = utils.searchMavenArtifact(artifactName, artifactoryUrl, "zip")

        }else {

            echo "Exception from vars/aksUtils.groovy -> getTestArtifactUrl() : Unkown artifactType in pipeline.json"

            error(utils.getError("AKS005"))

        }

        echo "[INFO] Test Artifact Url -> ${testArtifactUrl}"

        return testArtifactUrl

    }catch(e){

        error "Exception from vars/aksUtils.groovy -> getTestArtifactUrl() : ${e}"

    }

}

 

/*

    For preprod and prod deployment.

    Reads Jira Deployment Ticket to extract artifact version, config version, and whether dark deployment is enabled.

*/

def getInfoFromDeploymentTicket(deploymentTicketId) {

  try {

    withCredentials([

      usernamePassword(credentialsId: 'jira-fotfuser',

      passwordVariable: 'JIRA_PASSWORD', usernameVariable: 'JIRA_USERNAME')

      ]) {

        writeFile file: "py_data_utils.json",

        text: """{\"action_type\": \"get_info_from_rm_deployment_issue\",

        \"jira_ticket_id\": \"${deploymentTicketId}\"}"""

 

        if (!fileExists("${WORKSPACE}/pipeline_properties.json")) {

          utils.loadShellScripts("pipeline_properties.json")

        }

        utils.loadShellScripts("jira_utils_kp_org.py")

        sh 'python3 jira_utils_kp_org.py'

        // deployment_ticket_info.json is created by jira_utils_kp_org.py

        resultJson = readJSON file: "${WORKSPACE}/deployment_ticket_info.json"

 

        // extract versions from full artifact

        if (resultJson['artifactFull'].contains('-SNAPSHOT')) { // prevent preprod/prod release with snapshot version

          echo "[ERROR] Deployment to preprod or prod cannot happen with '-SNAPSHOT' artifact version"

          error(utils.getError("AKS012"))

          } else {

            resultJson['artifactVersion'] = resultJson['artifactFull'].substring(resultJson['artifactFull'].lastIndexOf('-') + 1, resultJson['artifactFull'].length())

          }

          if(resultJson['artifactType'] == 'AKS') {

            if (resultJson['configFull'].contains('-SNAPSHOT')) { // prevent preprod/prod release with snapshot version

              echo "[ERROR] Deployment to preprod or prod cannot happen with '-SNAPSHOT' config version"

              error(utils.getError("AKS013"))

              } else {

                resultJson['configVersion'] = resultJson['configFull'].substring(resultJson['configFull'].lastIndexOf('-') + 1, resultJson['configFull'].length())

              }

            }

 

            // Jira Deployment Ticket validations

            if ((resultJson['issueType'] == 'Deployment') && (resultJson['artifactType'] == 'AKS' || resultJson['artifactType'] == 'APIGEE') && (resultJson['issueStatus'].toLowerCase() == 'approved for deployment' || resultJson['issueStatus'].toLowerCase() == 'deployed to pre-prod')) {

              return resultJson

              } else {

                echo '[ERROR] Deployment ticket did not contain valid information'

                error(utils.getError("AKS014"))

              }

            }

            } catch(e) {

              echo "Exception from vars/aksUtils.groovy -> getInfoFromDeploymentTicket() : ${e}"

              error(utils.getError("AKS002"))

            }

          }

 

/*

    Method to set env.ARTIFACT_VERSION and env.CONFIG_VERSION for use in rest of the pipeline.

    Depending on which pipeline stage called the method, attempts to find version first from the stage-specific location.

    If not found in stage-specific location, attempts to find version from pom.xml or package.json.

*/

def setEnvArtifactAndConfigVersion(config, stage) {

    // deploy-to-env job, called from DeployToAKS.groovy

    if (stage == 'deploy' || stage == 'apigee') {

        // deploy to preprod or prod env -- version from jira deployment ticket, only if not part of exclusion list

        if ((params.operation == 'deploy-to-preprod' || params.operation == 'deploy-to-prod' || params.operation == 'deploy-to-dr' || params.APIGEE_ENV == 'prod') && QualityGate("deploymentWorkflow")) {

            deploymentTicketJson = getInfoFromDeploymentTicket(params.DEPLOYMENT_TICKET)

            env.ARTIFACT_VERSION = deploymentTicketJson['artifactVersion'].replaceAll(" ", "")

            env.CONFIG_VERSION = deploymentTicketJson['configVersion']

            env.DARK_DEPLOY = false

            // if (deploymentTicketJson['releaseType'] == "AKS Migration"){

            //     env.DARK_DEPLOY = true

            // }

            if (deploymentTicketJson['releaseType'] != "Pre-Production" && deploymentTicketJson['releaseType'] != "Emergency Release") {

                error("[ERROR] Release type Field in the JIRA ticket needs to have the value Pre-Production or Emergency Release, approved by a Release Engineer, and status Approved for Deployment.")

            }

            echo '[INFO] Setting artifact, config versions, and dark deploy value from Jira deployment ticket'

        }

 

        // deploy to other env -- version from params

        else if (params.operation.startsWith('deploy-to-') || params.APIGEE_ENV != 'prod') {

            // artifact_version

            if (params.ARTIFACT_VERSION) {

                env.ARTIFACT_VERSION = params.ARTIFACT_VERSION

                echo '[INFO] Setting artifact version from params.ARTIFACT_VERSION'

            }

            // config_version

            if (params.CONFIG_VERSION) {

                env.CONFIG_VERSION = params.CONFIG_VERSION

                echo '[INFO] Setting config version from params.CONFIG_VERSION'

            } else {

                env.CONFIG_VERSION = config.stages."aksDeploy"."configVersion"

                echo '[INFO] Setting config version from pipeline.json stages.aksDeploy.configVersion'

            }

        }

    }

 

    // DeploymentValidation job, called from DeploymentValidation.groovy

    // note: env.CONFIG_VERSION not needed

    if (stage == 'deploymentValidation') {

        if (params.TEST_ARTIFACT_VERSION) {

            env.ARTIFACT_VERSION = params.TEST_ARTIFACT_VERSION

            echo '[INFO] Setting artifact version from params.TEST_ARTIFACT_VERSION'

        }

    }

 

    // Regression job, called from RegressionAKS.groovy

    if (stage.startsWith('regression-')) {

        if (params.TEST_ARTIFACT_VERSION) {

            env.ARTIFACT_VERSION = params.TEST_ARTIFACT_VERSION

            echo '[INFO] Setting artifact version from params.TEST_ARTIFACT_VERSION'

        } else {

            def appName = config.'productName'

            def testEnv = stage.substring(11, stage.length()) // 'regression-'.length() is 11

            lastDeployedVersion = utilsDeploy.getLastDeployedVersion(testEnv, appName, 'aks').trim()

            if (lastDeployedVersion && (lastDeployedVersion != "NOT_FOUND")) {

                env.ARTIFACT_VERSION = lastDeployedVersion

                echo '[INFO] Setting artifact version from insights last deployed version'

            }

        }

    }

    // to be used for docker

    if (stage == 'default') {

        // artifact_version

        if (params.ARTIFACT_VERSION) {

            env.ARTIFACT_VERSION = params.ARTIFACT_VERSION

            echo '[INFO] Setting artifact version from params.ARTIFACT_VERSION'

        }

        // config_version

        if (params.CONFIG_VERSION) {

            env.CONFIG_VERSION = params.CONFIG_VERSION

            echo '[INFO] Setting config version from params.CONFIG_VERSION'

        } else {

            env.CONFIG_VERSION = config.stages."aksDeploy"."configVersion"

            echo '[INFO] Setting config version from pipeline.json stages.aksDeploy.configVersion'

        }

    }

 

    // look in pom.xml or package.json as catch-all for artifact version

    if (!env.ARTIFACT_VERSION && config) {

        if (config.'artifactType' == "NODEJS") {

            sourceDir = config.stages.'npm-build'.'source-directory' ?: ""

            def packageJson = readJSON file: "$WORKSPACE/$sourceDir/package.json"

            env.ARTIFACT_VERSION = packageJson.version

            echo '[INFO] Setting artifact version from package.json'

            if (env.ARTIFACT_VERSION.toUpperCase().contains("SNAPSHOT")){

                env.ARTIFACT_VERSION = utils.getNpmSnapshotVrsn(packageJson.name, env.ARTIFACT_VERSION)

            }

        } else if (config.'artifactType' == "JAVA") {

            sourceDir = config.stages.'maven-build'.'source-directory' ?: ""

            def deployableModule = config.stages.'maven-build'.'module-name' ?: ""

            def pomFolder = ""

            if (sourceDir && deployableModule) {

                pomFolder = sourceDir + "/" + deployableModule

           } else if (sourceDir || deployableModule){

                pomFolder = sourceDir ? sourceDir : deployableModule

            }

            pomFolder = (pomFolder == "" || pomFolder == null) ? "${WORKSPACE}/pom.xml" : "${WORKSPACE}/${pomFolder}/pom.xml"

            pom = readMavenPom file: pomFolder

            env.ARTIFACT_VERSION = pom.version ?: pom.parent.version

            echo '[INFO] Setting artifact version from pom.xml'

        }

    } else if (!env.ARTIFACT_VERSION && !config) {

        echo "[ERROR] aksUtils.setEnvArtifactAndConfigVersion() could not determine artifact version"

        error(utils.getError("AKS015"))

    }

 

    // use artifact version as catch-all for config version

    if (!env.CONFIG_VERSION) {

        env.CONFIG_VERSION = env.ARTIFACT_VERSION

    }

 

    echo "[INFO] artifact version: ${env.ARTIFACT_VERSION}, config version: ${env.CONFIG_VERSION}"

}

 

def getNamespace(config){

    ciEnv = getCiEnv(config) //grab ciEnv configured or else it will be defaulted to dev

 

    def namespace = ""

    //if aksDeploy section is not configured or the aksDeploy ciEnv does not have namespace configured, check for namespace under imageBuild

    if (!(config.stages."aksDeploy") || !(config.stages."aksDeploy"."deployEnvironments") || !(config.stages."aksDeploy"."deployEnvironments"."${ciEnv}") || !(config.stages."aksDeploy"."deployEnvironments"."${ciEnv}".namespace)){

        if (!(config.stages."imageBuild") || !(config.stages.imageBuild.'namespace')){

            //if namespace is not configured in aksDeploy or imageBuild, error out

            echo "[ERROR]: namespace is not configured correctly in stages.aksDeploy.deployEnvironments.${ciEnv}.namespace or stages.imageBuild.namespace"

            error(utils.getError("AKS004"))

        }else{

            namespace = config.stages.imageBuild.'namespace'

        }

    }else{

        //set namespace to be namespace name without env at the end

        deployNamespace = config.stages."aksDeploy"."deployEnvironments"."${ciEnv}".namespace

        try {

            namespace = deployNamespace.substring(0,deployNamespace.lastIndexOf(ciEnv)-1)

        } catch (e) {

            echo "ERROR when trying to read namespace for ${ciEnv} in pipeline.json, please verify namespace value is correct"

            echo e

            error(utils.getError("AKS003"))

        }

    }

    return namespace

}

 

def getBuildNumFromUrl(downloadUrl) {

    def downloadSuffix = downloadUrl.split('/')[downloadUrl.split('/').length-1].split('-').last()

    def isMvnSnapshot = downloadSuffix.count('.')

    def buildNum = isMvnSnapshot == 1 ? '-' + downloadSuffix.split('\\.')[0] : ''

 

    return buildNum

}