/* Below are utility methods that used during deployments. */


/** Sending deployment results to neo4j database via rabbitmq
* @param artifactInfo - map with artifact name, version, appType, and rollback flag
* @param envrnmt - deployment environment
*/
def sendDetailsToInsights(artifactInfo, envrnmt) {
  echo "==========================" 
  echo "[INFO] Sending deployment results to neo4j database via rabbitmq."
  echo "[INFO] Deploy environment: ${envrnmt}. Artifact info : ${artifactInfo}"
  // Setting up time stamp
  def epochTime = sh(script: "date +%s", returnStdout: true).trim()
  // This is the human readable time that DB team requested to be inserted as well
  def deployedAt = new Date().format("YYYY-MM-dd HH:mm:ss.SSSSZ")
  echo "[INFO] Deployed date: ${deployedAt}, epochTime : ${epochTime}"
  def decommissioned = artifactInfo.purge ? (, "purge": + true) : ' '
  def mode = artifactInfo.mode ? (', "mode": "' + artifactInfo.mode + '"') : ''
  def aksDarkDeploy = artifactInfo.aksDarkDeploy ? artifactInfo.aksDarkDeploy : ''
  def deployDataJson = '{"data": [{' 
  if (aksDarkDeploy == true){
      aksDarkDeployData = "aksDarkDeploy": + aksDarkDeploy + ', '
      deployDataJson = deployDataJson + aksDarkDeployData
  }
  deployDataJson = deployDataJson + "artifactName": "' + artifactInfo.name +
                         '", "version": "'+ artifactInfo.version +
                         '", "rollback": '+ artifactInfo.rollback +
                         decommissioned + 
                         mode +
                         ', "environment": "'+ envrnmt +
                         '", "appType": "' + artifactInfo.appType + 
                         '", "deployedAt": "' + "${deployedAt}" +
                         '", "deployedAtEpoch": ' + "${epochTime}" +
                         ',"buildURL": "' + "${BUILD URL}" +
                         '"}],"metadata": {"labels": ["DEPLOYMENT_DATA"]}}
   echo "[INFO] deployDataJson: ${deployDataJson}"
   def rabbitM Host = LoadPipelineProps ("rabbitmq-host")
   def insightsMqCreds = "insights_mq_creds"
   if (utils.getDebug("insights-mq")) {
    def loadProps = libraryResource "pipeline_properties.json"
    def pipelineProps = readJSON text: loadProps
    rabbitMqHost = pipelineProps.rabbitmq-host'.'sandbox' 
    insightsMqCreds = "insights_mq_sandbox_creds"
   }
   // Post information to database using the sendmsg_mg_v2.py script 
   withCredentials ([[$class: 'UsernamePasswordMultiBinding', credentialsId: "${insightsMqCreds}",
                       usernameVariable: 'INSIGHTS_USER', passwordVariable: 'INSIGHTS_PASS']]) { 
     utils.loadShellScripts("sendmsg_mq_v2.py")
     sh("python3 sendmsg_mq_v2.py ${rabbitMqHost}" + ' $INSIGHTS_USER $INSIGHTS_PASS +
     "'${deployData)son}' 'null' 'null'")
   }
} // end sendDetailsToInsights
 
/** Validating dependencies exist in environment 
* @param artifactProps artifactory properties 
* @param envrnmt target deployment environment
*/
def validateDependencies(artifactProps, envrnmt) {
  echo "====================="
  echo "[INFO] Validating dependencies." 
  try {
    if (artifactProps.app_dependencies && artifactProps.app_dependencies[0] != "null") {
       artifactProps. 'app_dependencies'.each { dpndncy ->
         dpndncyNmAndVersn = dpndncy.split(':') 
         String dpndncyName = dpndncyNmAndVersn[0] 
         String dpndncyVersnRaw = dpndncyNmAndVersn[1] 
         String dpndncyCmpr = dpndncyVersnRaw.take(1) 
         String dpndncyVersn = dpndncyVersnRaw.replace('~','').replace('^','') 
         echo "----------------------"
         echo "[INFO] Validating: ${dpndncyName}: ${dpndncyVersnRaw}." 
         deployedVersion = getLastDeployedVersion(envramt, dpndncyName)
         checkPassed = checkDenendencyVersion(dpndncyCmpr, deployedVersion, dpndncyVersn) 
         echo "[INFO] Dependency check status: ${checkPassed)" 
         if (!checkPassed) {
          currentBuild.result = UNSTABLE 
          echo ("[ERROR]: Required dependency version ${dpndncyName):${dpndncyVersnRaw}" +
                "is not in target environment. Setting build status to 'UNSTABLE'.")
         }
       }// end artifactProps.'app_dependencies'.each
     } else {
       echo "\033[43m [WARNING]: Unable to find the app_dependencies property in Artifactory \033[0m"
       }
  } catch (err) {
    current Build.result = 'UNSTABLE' 
    echo ("[ERROR]: Unexpected error happened in validateDependencies method. Setting build status to 'UNSTABLE'.")
    echo ("${err}")
   } 
}// end validateDependencies

/**
* Validating dependencies for one manifest 
* @param manifest ison manifest in ison object format
* @param envrnmt target deployment environment 
* @param status map of result of validation, has allDependencies and dependenciesFailed keys
*/ 
def validateDependenciesManifest(manifestJson, envirnmt, status) {
  echo "======================="
  echo "\033[42m#### Validating dependencies in manifast ###\033[0m" 
  try { 
     for (product in manifestJson.products) { 
       if (product.action == "install") {
          // Get artfiactory properties using name and version (manifest does not have any SNAPSHOTS) 
          def artifactLink = manifestGetArtifacts.getFullArtifactURL(product.version)
          def artifactProps = artifactorytagging.getAllProperties(artifactLink)
         // If app_dependencies not empty check if dependency is in the same manifest
         if (artifactProps.app_dependencies && artifactProps.app_dependencies[0] != "null") {
           artifactProps. 'app_dependencies .each { dpndncy ->
              dpndncyNmAndVersn = dpndncy.split(':') 
              String dpndncyName = dpndncyNmAndVersn[0]
              String dpndncyVersnRaw = dpndncyNmAndVersn[1] // optional range comparator and version 
              echo "------------------"
              dependencyInfo = "[INFO] ${product.version) requires dependency: ${dpndncyName}: ${dpndncyVersnaw}.\n" 
              echo "${dependencyInfo}" 
              status.allDependencies += dependencyInfo
              checkManifestDependency(manifestIson, dpndncyVersnRaw, dpndncyName, envirnmt, status, product) 
              } // end artifactProps. 'app_dependencies'.each
            } else {
              echo "\033[43m [WARNING]: Unable to find the app_dependencies property in Artifactory \033[0m"
               } 
              } else {
                  echo ("\033[32m [INFO] Skipping dependencies validation for the artifact" +
                         product.version + ", action: " + product.action + ".\033[Om")
                }
          } // end looping over products in manifest
       } catch (err) {
         echo ("[ERROR]: Unexpected error happened in validateDependenciesManifest method. ${err}")
       }
   } // end validateDependenciesManifest


\**
* Checking if dependency is in same manifest or it is in the target environment
* @param manifestJson full manifest json object
* @param dpndncyName string name of dependency that required
* @param dpndncyVersnRaw has optional "A" or "w" for range compare and mandatory
* ".." of required dependency (ex: ".1.20.3") @param envrnmt target deployment environment 
* @param status map that holds dependenciesFailed key, failed dependencies added there 
* @param product map that holds version key, contains name-version of artifact that dependant on dpndncyName
* @return true if dependency is in manifest or environment, false if not
**/
def checkManifestDependency(manifest)son, dpndncyVersnRaw, dpndncyName, envirnmt, status, product) {
  echo "[INFO] Checking if the ${dpndricyName} dependency is in manifest, if not checking target environment."
  String dpndncyCmpr = dpndncyVersnRaw.take(1)
  String dpndncyVersn = dpndncyVersnRaw.replace('~'',').replace('^','')

  echo "[INFO] Checking if the dependency is in manifest"
  for (inManifestDepend in manifestIson.products) { 
   if (inManifestDepend.version ==~ /$dpndncyName-(\d+)(\d+).(\d+) /{
     def in ManifestVersion = inManifestDepend.version.minus("${dpndncyName}-") 
     checkPassed = checkDenendencyVersion(dpndncyCmpr, inManifestVersion, dpndncyVersn)
     echo "[INFO] Version in manifest: ${inManifestVersion}. Status: ${checkPassed}"
     if (!checkPassed) {
       echo ("[ERROR]: Dependency version in manifest does not pass check.") 
       status.dependenciesFailed += ("* **${product.version}** for **${envirnmt}** " +
                                     "requires dependency: **${dpndncyName}:${dpndncyVersnRaw}** \\n")
     }
    }
} // end checking products in manifest
echo "[INFO] Dependency is not in manifest, cheking if deployed to envirnmt
deployedVersion = getLastDeployedVersion(envirnmt, dpndncyName)
checkPassed = checkDenendencyVersion (dpndncyCmpr, deployedVersion, dpndncyVersn) 
echo "[INFO] Version check in environment status: ${checkPassed}" 
if (!checkPassed) {
    echo ("[ERROR]: Dependency version in environment does not pass check.") 
    status.dependenciesFailed += ("* **${product.version)** for **${envirnmt)** " +
                                  "requires dependency: **${dpndncyName}:${dpndncyVersnRaw}** \\n")
  }
} // end checkManifestDependency


/**
* • Getting data from database of what version last deployed to environment 
* @param envrnmt target deloyment environment 
* @param artifactName name of artifact to retrive from DB
* @param appType - optional parameter for future aks and bmx use 
* @return string NOT_FOUND if not in database or version last deployeds
*/
def getLastDeployedVersion(envrnmt, artifactName, appType = null, mode = null) { 
  try {
     echo "========================"
     echo "[INFO] Getting last deployed version of ${artifactName} to ${envrnmt) from neo4j database."
     // aks and bmx apps may deploy same version therefore in future might need 
     // appType in query, please update this comment once appTypeQuery is in use
     def appTypeQuery = appType ? " and n.appType = '${appType}' " : ""
     def modeQuery = mode ? " and n.mode = '${mode}'" : ""

     queryString = ("{\"statements\":[{\"statement\":\"MATCH (n:DEPLOYMENT_DATA)" +
          " where n.environment = \'${envrnmt}\' and n.artifact Name = \'${artifactName}\' " +
            appTypeQuery + modeQuery +
            "RETURN distinct n. artifactName as artifactName, n.version as version," +
            "n.purge as purge, n.deployedAt as deployedAt, n.deployedAtEpoch as deployedAtEpoch, " +
            "n.buildURL as buildurl, n.environment as environment " +
            "ORDER by deployedAtEpoch DESC limit 1\"}]}") 
     echo "[INFO] Query: ${queryString}" 
     writeFile file: 'data.json', text: queryString

    def neo4jHostPort = LoadPipelineProps("neo4j-host-port") 
    def neo4jCreds = "insights_neo4j_creds"
    if (utils.getDebug("neo4j")) {
       def loadProps = libraryResource "pipeline_properties.json" 
       def pipelineProps = readJSON text: loadProps 
       neo4j HostPort = pipelineProps. 'neo4j-host-port'.'sandbox'
       neo4jCreds = "insights_neo4j_sandbox_creds"
     }
     withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: "${neo4jCreds}",
                       usernameVariable: "USRNM', passwordVariable: 'PASWD' ]]) {
        neo4jCmd = ('curl -u $USRNM: $PASWD ' +
                     "-X POST -H "Content-Type: application/json' " + 
                     "-H accept: application/json --data @data.json " +
                     "http://${neo4jHostPort}/db/data/transaction/commit") 
        neo4jResp = sh returnStdout:true, script: neo4Cmd
     }
     echo "[INFO] neo4j DB response: ${neo4j Resp}"
     neo4jRespJson = readJSON text: neo4jResp 
     dbDpndncy = neo4jRespJson.'results'.'data'.'row'
     // If data exist for artifact name and if purge flag not ==true 
     if (dbDpndncy[0][0] != null && dbDpndncy[0][0][2] != true) {
        dbVersion = dbDpndncy[0][0][1] 
        echo "[INFO] Last deployed version in ${envrnmt} is ${artifactName}:${dbVersion}"
        return dbVersion 
     } else { 
       echo ("\033[41m [ERROR] \033[Om No version of ${artifactName}" +
             "in neo4j for ${envrnmt} environment.") 
       return "NOT_FOUND"
      }
   } catch (err) {
     echo "[ERROR] Unable to get last deployed version from DB, error: ${err}"
   }  
} // end getLastDeployedVersion

/**
* Checking and comparing deployed and dependency version 
* @param dpndncyCmpr string type. ^ or ~ for range compare, or number if exact compare applied
* @param availableVersion string, version in target environment or in manifest 
* @param requiredVersn string, required version (dependency)
* @return false if compare check failed and if success returns string describing why successfull
*/
def checkDenendencyVersion (dpndncyCmpr, availableVersion, requiredVersn) { 
  echo ("[INFO] Comparing versions, compare: ${dpndncyCmpr}, dependency: ${requiredVersn}, " +
        "available version: ${availableVersion}")
  availableVersionSplit = availableVersion.split("[.]")
  requiredVersnSplit = requiredVersn.split("[.]")

  if (availableVersion == "NOT_FOUND") {
    return false 
  } else if (availableVersion == requiredVersn) {
    return "Check passed. Exact version deployed." 
  } else if (availableVersion.contains ("SNAPSHOT") && !requiredVersn.contains("SNAPSHOT") ||
             !availableVersion.contains("SNAPSHOT") && requiredVersn.contains("SNAPSHOT")) {
    return false
  } else if ((dpndncyCmpr == '~' || dpndncyCmpr == "^") &&
              availableVersionSplit[0] == requiredVersnSplit[0] && // Major version
              availableVersionSplit[1] == requiredVersnSplit[1] && // Minor version
              availableVersionSplit[2] >= requiredVersnSplit[2]) { // Patch 
    return "Check passed. Deployed version has same major and minor versions and patch >=than minimum."
  } else if (dpndncyCmpr == '^' &&
             availableVersionSplit[0] == requiredVersnSplit[0] && // Major version
             availableVersionSplit[1] > requiredVersnSplit[1]) { // Minor version 
    return "Check passed. Deployed version has same major version and minor version > than minimum."
  } else {
    return false
 } 
}// end checkDenendencyVersion

/*********** END Dependencies validation methods***********/

// check for emergency release status to exempt regression quality gate
 def checkEcroRegression(jiraTicketId) {
   if (!fileExists("${WORKSPACE}/pipeline_properties.json")) utils.loadShellScripts("pipeline_properties.json")
   if (!fileExists("${WORKSPACE}/jira_utils_kp_org.py")) utils. loadShellScripts("jira_utils_kp_org.py")
   writeFile file: "py_data_utils.json", text: """{\"action_type": \"get_info_from_rm_deployment_issue\", \"jira_ticket_id\":\"${jiraTicketId}\"}"""
   withCredentials([usernamePassword(credentialsId: 'jira-fotfuser', passwordVariable: 'JIRA_PASSWORD', usernameVariable: "JIRA_USERNAME')]) {
     sh "python3 jira_utils_kp_org.py"
  }
  jiraValues = readJSON file: "${WORKSPACE}/deployment_ticket_info.json"
  env.EMERGENCY_RELEASE = (jiraValues['releaseType'] && jiraValues['releaseType'].equalsIgnoreCase("Emergency Release"))? "EMERGENCY" : "NORMAL" 
  echo "[INFO] --> Emergency release status: ${EMERGENCY_RELEASE) for Jira ${jiraTicketId}"
}






















