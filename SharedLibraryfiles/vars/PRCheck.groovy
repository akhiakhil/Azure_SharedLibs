/*
PRCheck is used in BuildMvn and BuildNpm to prevent PR merge on Sonar or build fail
Additionally, it check for correct Jira tiket linked to PR, and Jenkinsfile for 
correct agent declaration. 
Uses PRCheckUtils to do blocking and commenting on PR.
*/

def call(config) {
 //if branch is not a PR branch
 if (!env.BRANCH_NAME.startsWith('PR-')) {
  echo '[INFO] Not on PR branch' 
   return
 }
 //if PR branch, only run PR checker if target branch is master, release/*, or develop 
 else{ 
  if (env. CHANGE_TARGET != "master" && !env. CHANGE_TARGET.contains("release/") && env.CHANGE_TARGET != "develop"){
    echo "[INFO] PR Target branch is not master, release, or develop" 
    return
   }
 }

def PRCommentMap = [:] //overall hash map 
def enforcelist = [] 
def prbuilder = LoadPipelineProps('prbuilder') 
def appType = "" 
if (config){
  appType = config.'application-type'
}

def githubRepoPrefix = utils.getRepoName().split('-')[0]

// PR check steps 
def parBuildSteps = [:]
def parSteps = ["DoD", "Generic"]
parSteps.each {
 def step = "Running ${it}"
 parBuildSteps[step] = { ->
   switch(it) {
     case "DoD":
       runDoDCheck(PRCommentMap, prbuilder, enforceList, githubRepoPrefix, appType) 
       break 
     case "Generic": 
       node {
         deleteDir()
         if (!fileExists("${WORKSPACE}/pipeline.json")) checkout scm
         def resultMap = [:] //local instance 
         // validates Jenkinsfile for top level agent any/agent label in stages or top level agent any/agent any in stages 
         resultMap = jenkinsFileCheckprbuilder, resultMap) 
         generateResults(resultMap, PRCommentMap, enforceList, githubRepoPrefix) 
         // validates Jira ID in PR title is a valid AES Jira ID and the status is "In Progress" 
         resultMap = prTitleCheck(prbuilder, resultMap) 
         generateResults(resultMap, PRCommentMap, enforceList, githubRepoPrefix)
         //Validate AEM Guardrails check
         if (config && appType == "aem" && QualityGate("aemGuardrails")) {
            resultMap = aemGuardrails (prbuilder, resultMap)
            generate Results(resultMap, PRCommentMap, enforceList, githubRepoPrefix)
         }
        if (githubRepoPrefix != "jenkins") {
            //searches artifactory for app_dependencies from app-info.yama
            resultMap = artifactoryCheck (prbuilder, resultMap) 
            generateResults(resultMap, PRCommentMap, enforcelist, githubRepoPrefix)
         }
       //Validate Apigee policies using opa 
       resultMap = proxyConfigFileCheck(prbuilder, resultMap, config)
       generateResults(resultMap, PRCommentMap, enforceList, githubRepoPrefix) 
       if (!(config == "null" || config == null || config == "")) {
         // AKS-specific PR Check non-config repos to validate AppImageBuilder related fields 
         if (config.'platform' && config.'platform' == "aks" && !(utils.getRepoName().contains("config"))) {
            msg = aksConfigCheck(prbuilder.'aksConfigCheck'. 'stage', resultMap, prbuilder.'aksConfigCheck'.'validationInput')
            resultMap.put('msg', msg)
            resultMap.put('squads', prbuilder.'aksConfigCheck'.'squads')
            generateResults(resultMap, PRCommentMap, enforceList, 'githubRepoPrefix')
          }

        // AKS-specific PR Check to validate config repo values.yaml files if PR includes changes to any values.yaml
       if (config.'platform' && config.'platform' == "aks" && utils.getRepoName().contains("config")) {
       def msg = "" //local instance 
       //get modified values.yaml files in PR, if any 
       modValuesYamls = getPRFiles(prbuilder.github-cred-id') 
       //only validate if there are modified values.yaml files 
       if (modValues Yamls.size() > 0) {
           def repoExist = utils.loadAppRepoFromConfig(CHANGE_TARGET) 
           def apigeeEnabled = repoExist ? checkIfApigeeDeploy() : false
           for (valueYaml in modValues Yamls) {
             path = "${WORKSPACE}/" + valueYaml.replaceAll("\"","")
             //checking if values.yaml exists -- scenario where values.yaml gets deleted and is registered as a PR modified values.yaml file will break the build
             if (fileExists("${path)")) {
               def envName = path.split("/")[-2] 
               def jsonText = """{ "env":"${envName}", "apigeeEnabled":${apigeeEnabled}}"""
               sh"rm -rf ${WORKSPACE}/env/${envName} /aksValuesData.json"
               writeFile file: "${WORKSPACE/env/${envName}/aksValuesData.json", text: jsonText
               tempMsg = aksConfigCheck(prbuilder.'aksValuesYam Check'. 'stage', resultMap, path) 
               msg = tempMsg + msg
             }
            }
            resultMap.put('squads', prbuilder.'aksValuesYamlCheck'.'squads')
            resultMap.put('msg', msg)
            echo "final resultMap" + resultMap 
            generateResults(resultMap, PRCommentMap, enforcelist, githubRepoPrefix)
           }
         }
         // Validate izaro version for only AEM or Nginx apps
         if (config?.'application-type'? .equalsIgnoreCase("aem") || config?. 'application-type'?.equalsIgnoreCase("nginx")) {
             resultMap = qsIzaroCheck(prbuilder, resultMap, appType)
             generateResults(resultMap, PRCommentMap, enforceList, githubRepoPrefix)
         }
       }
     }
     break 
   default:
      error "Error in parSteps definition in PRCheck.groovy."
      break
    }
   }
 }
 parallel (parBuildSteps)

 prcheckutils = new PRCheckUtils() 
 prcheckutils.addComments TOPR(PRCommentMap, prbuilder)

 if (enforceList.size() > 0) {
    prcheckutils.blockMerge(prbuilder)
 }
}

def getPRFiles(tokenID) {
  def values FilesList = [] 
  withCredentials([usernamePassword(credentialsId: tokenID, passwordVariable: 'token_pw', usernameVariable: 'token_user')]) {
     def prFiles = sh(script: "curl -H 'Authorization: Token ${token_pw)' -X GET https://github.kp.org/api/v3/repos/${utils.getOrgName()}/ ${utils.getRepoName()}/pulls/${env.CHANGE_ID}/files | jg --raw-output '[[].filename] @csv", returnStdout: true)
     String[] splitFiles = prFiles.split(",") 
     //loop through all the PR files and add the path to any values.yaml files that are included in the PR 
     for (modifiedFile in splitFiles) { 
        if (modifiedFile.contains("values.yaml")) {
          values FilesList.add(modifiedFile.trim()
         }
      }
      echo "modified values.yaml's : " + values FilesList
    }
   return valuesFileslist
 }
/* Getting the change sets.. leaving commented out, could be leveraged in the future for running certain PR checks when the change set includes certain files */ 
// def changeLogSets = current Build.rawBuild.changeSets 
// for (int i = 0; i < changeLogSets.size(); i++) {
//               def entries = changeLogSets[i].items 
//               for (int j = @; 3 < entries.length; j++) {
//                  def entry = entries[j] 
//                   def files = new ArrayList(entry.affectedFiles)
//                    for (int k = @; k < files.size(); k++) {
//                     def file = files[k] 
//                     echo" _____________"
//                     echo $(file.path}"
//                     echo "------------"
//                    }
//     }
// }
/* End getting the change sets */


//first param is passing in specific json property ie. prbuilder.'aksConfigCheck' 
def aksConfigCheck(stage, resultMap, filePath) {
  def msg =""
  def configResults = opaValidation(stage, filePath)

  resultMap.put("title",configResults["title"]) 
  if(configResults["violations").size() == 0) {
  resultMap.put("status", "pass") 
  } else {
   resultMap.put("status","fail") 
   if(stage == "aksValuesCheck"){
       splitPath = filePath.split("/") 
       //add specific eny to msgie. Ceny>/values.yaml configuration - Invalid 
       msg = "\\n :x: **" + splitPath(splitPath.size()-2] + "/" + configResults["title"] + " - Invalid" + "**\\n\\n"
      }
     else{
       msg = "\\n :x: **" + configResults["title"] + "**\\n\\n"
      }
      for(i in configResults["violations"]) {
         msg += "*" + i +"\\n"
       }
     }
    return msg
 }

/**
* Checking whether or not service is exposed using APIGEE 
* Requires pipeline.json from application repo inside WORKSPACE/app-repo. 
* @return boolean of exposed using Apigee or not
**/
def checkIfApigeeDeploy() { 
   if(fileExists("app-repo/pipeline.json")) {
   def jsonObj = readJSON file: "app-repo/pipeline.json" 
   if(!jsonObj.stages || jsonObj.stages.'apigee-deploy') {
      echo "[INFO] pipeline.json does not have 'apigee-deploy'. Service is not exposed using APIGEE."
      return false
    } else {
      echo "[INFO] pipeline.json has 'apigee-deploy'. Service is exposed using APIGEE." 
      return true
   }
   } else {
     echo "[INFO] pipeline.json from application repo not found. Service is not exposed using APIGEE." 
     return false
   }
 }

def prTitleCheck(prbuilder, resultMap) {
  def. githubCredId = prbuilder.github-cred-id'
  def squadList = prbuilder.prTitleCheck'.'squads'

  //msg to be added on PR page 
  def msg =''

  //adding title to resultMap
  resultMap.put('title', prbuilder.'prTitleCheck'.'title')

  //add squads to resultMap 
  resultMap.put('squads', squadList)

  //Verifying GitHub PR Title
  withCredentials([usernamePassword(credentialsId: githubCredid, passwordVariable: 'token_pw', usernameVariable: 'token_user')]) {
    def prTitle = sh(script:" curl -H 'Authorization: Token ${token_pw} https://github.kp.org/api/v3/repos/${utils.getOrgName(}/ ${utils.getRepoName(}/pulls/${env.CHANGE_ID) | '.title", returnStdout: true) 
    resultMap = validateJiraTicket(prbuilder, prTitle, resultMap, msg)
  }
  return resultMap
}

def validateJiraTicket(prbuilder, prTitle, resultMap, msg) {
  prJiraId = prTitle.split(':')[0].trim() 
  prJiraid = prJiraId.replaceAll("\"" , "") 
  jMsg = [] 
  multipleIds = false;

  // if there are multiple Id's, check that there is a comma separator 
  if (prJirald.count("-") > 1 && !prJirald.contains("")){
    multipleIds = true
  }

jIds = prJirald.split(',')
statusCheck =''
def validJirald 
def validCount = 0

for (id in jId) {
   id = id.trim()
   if (prTitle.contains(":") && !multipleIds && id && id = ~ /^[A-Z0-9]{2,)-[0-9]{1,}/) {
      //insert jiraid to curl command to validate Jira id 
      withCredentials([usernamePassword(credentialsId: prbuilder. 'jira-cred-id', passwordVariable: 'pw_cred', usernameVariable: 'user_cred')]) {
         validJiraid = sh(script: "curl -s -U '${user_cred}': '${pw_cred)' -H "Content-Type: application/json' - X GET https://jiraaes.kp.org/rest/api/2/issue/${id)?fields=status", returnStdout true)
         }

    if (validJirald.contains('errorMessages')) {
       jMsg.add("* **${id}** - Invalid AES Jira ID in PR Title. **Valid Format : ** ***PROJECT-XXXX: message*** or ***PROJECT-XXXX, PROJECT-XXXX: message***\\n")
       statusCheck = 'fail' 
    }else {
       msgStatus = "* ${id} - AES Jira ID is valid"

    // check for In Progress status 
    validJiraId = readJSON text: validJirald 
    def ticketStatus = validJirald. fields.status.name
    if (ticketStatus == 'In Progress' || ticketStatus == 'Demo') {
      echo "[INFO][SUCCESS) ${id} - Jira issue is In Progress or Demo status" 
      msgStatus = msgStatus + " and issue status is In Progress or Demo.\\n" 
      jMsg.add(msgStatus)
      validCount++ 
    } else if (ticketStatus 'Todo' Il ticketStatus as 'Backlog') {
      def ticketParams = [action "comment_transition", comment: "IRA ticket status not set to In Progress. Changing status to meet requirements.\\n", transitionId: "31"]
      withEnv(["JIRA_TICKET_ID=${id}"]) {
          utils.update JiraTicket(ticket Params)
       }
       sleep time: 3, unit: "SECONDS" 
       msgStatus = msgStatus + " and issue status has been set to In Progress. \\n"
       jMsg.add(msgStatus)
       validCount++
     } else {
        echo [Error]: JIRA ticket status could not be moved to In Progress."
        msgStatus = msgStatus + but issue could not be transitioned to In Progress. Please set status to In Progress manually.\\n"
        jMsg.add(msgStatus) 
        statusCheck = "fail"
     }
    } 
  } else {
    jMsg.add("* Invalid AES Jira ID in PR Title. **Valid Format:** ***PROJECT-XXXX: message*** or ***PROJECT-XXXX, PROJECT-XXXX: message***\\n") 
    statusCheck = 'fail'
  }
 }

 if (statusCheck == 'fail' && validCount == 0) {
   resultMap.put('status', 'fail')
   msg = ":x: **PR Title Check failed**\\n" + msg
  }
  else {
      resultMap.put('status', 'pass')
      msg = " :white_check_mark: PR Title Check passed\\n" + msg
   }
  for (min jmsg) {
       msg = msg + m
  }
  // add jMsg to msg and put it in resultMap
  resultMap.put('title', prbuilder.prTitleCheck', 'title')
  resultMap.put('msg', msg) 
  return resultMap
}

def generateResults(resultMap, PRCommentMap, enforceList, githubRepoPrefix) {
  echo "[INFO] generateResults msg ${resultMap.msg) --- title: ${resultMap.title)" 
  PRCommentMap.put(resultMap.title, resultMap.msg)

  if (resultMap.status == 'fail') {
    if (resultMap.squads) {
       //exclusion list for PR Title Check and inclusion list for all other PR Checks
       if ((!resultMap.squads.contains(githubRepoPrefix) && resultMap.title == "PR Title Check") || (resultMap.squads.contains(githubRepoPrefix) && resultMap.title != "PR Title Check")) {
      //squad specific enforcement 
      enforceList.add(resultMap.title)
     }
    }
    else {
       //global enforce
        enforceList.add(resultMap.title)
      }
     }
 }

def jenkins FileCheck(prbuilder, resultMap) {
   //msg to be added on PR page.
   def msg = ''
   def valid = true 
   //adding title to resultMap 
   resultMap.put('title', prbuilder.'jenkinsFileCheck.'title')
  //add squads to resultMap 
  resultMap.put('squads', prbuilder.jenkinsFileCheck'.'squads')

  def status = sh(script:'grep -Pzq "(?s)^(\\s*) pipeline(\\s*){(\\s*)agent\\s+any" Jenkinsfile', returnStatus: true) 
  echo [INFO] status: " + status 
  if (status == 0) {
    def statusOut = sh(script:'grep - PC "^(\\s*) agent(\\s+) any" Jenkinsfile', returnStdout:true).trim() 
    // Fail if agent any is defined at top level and # of agent any is more than 1 
    echo '[INFO] statusOut: " + statusOut 
    def agentLabelStatus = sh(script:'grep - Pzc "(\\s*)agent(\\s*){(\\s*)label" Jenkinsfile || true', returnStdout: true).trim() 
    echo '[INFO] agentLabelStatus: ' + agentLabelStatus

    if (statusOut && statusOut.toInteger() > 1) {
    echo "[INFO] Jenkinsfile has 'agent any defined at global & at stage level" 
    resultMap.put('status', 'fail')
    msg = msg + ':X: **Jenkinsfile is invalid. Multiple agent any defined at global level and at stage level. **'
    valid = false
   }

   if (agentLabelStatus && agentLabelStatus.toInteger() > 0) {
     echo "[INFO] Jenkinsfile has 'agent any' defined at global & agent label defined at stage level" 
     resultMap.put('status', 'fail')
     msg = msg + ':: **Jenkinsfile is invalid. Agent any defined at global level and agent label defined at stage level. ** '
    valid = false
   }
 }
 if (valid) {
   resultMap.put('status', 'pass)
   msg = msg + ':white_check_mark: Jenkinsfile agent any configuration is valid.'
 }
 
  resultMap.put('msg', msg) 
  return resultMap
 }


/** Validations that dependencies in artifactory 
* msg is added on PR in GitHub. 
* @param prbuilder - made from pipleine_properties.json
* @param resultMap 
* @return resultMap
*/
def artifactoryCheck(prbuilder, resultMap) {
 try {
   echo "[INFO] Starting dependencies artifactory check." 
   def msg = ""
   def inhouseSnapshot = 'inhouse_snapshot, npm-snapshot'
   def inhouseRelease = 'inhouse_release, npm-release'
   statusCheck = ''
   //adding title to resultMap
   resultMap.put('title', prbuilder.'artifactoryCheck'.'title')
  //add squads to resultMap resultMap.put('squads', prbuilder.'artifactoryCheck'.'squads')
  def ARTIFACTORY_URL = LoadPipelineProps ("artifactory-ur1") 
  if (fileExists("app-info.yaml")) {
    def ymap = read Yaml file : "app-info.yaml" 
    String itemString = "" 
    String[] str 
    for (String itm in ymap['app_dependencies']) {
      itemString = "${itm}"
      itemString = itemString.replace("[","").replace("]","") 
      str = itemString.split(':');
      String artifactId = str[0] 
      String version = str[1] 
      version = version.replace("A","").replace("~","") 
      def repo = version.contains("SNAPSHOT)? inhouseSnapshot : inhouseRelease
      fullArtifactPath = ARTIFACTORY_URL+"/api/search/artifact?name="+artifactId+"-"+version.replace("SNAPSHOT","")+"\\&repos="+repo+ ""
      def status = downloadFileSearch(fullArtifactPath) 
      if (status == 'fail') {
        msg = msg + "* :heavy_exclamation_mark: **" + artifactId + ":" + version + "** not in Artifactory\\n"
        echo "[INFO] Check the artifactory URL ${fullArtifactPath} returned status : ${status}. \\n"
        statusCheck = 'fail'
       }
      else {
          msg = msg + "* heavy_check_mark: ** " + artifactId + ":" + version + "** in Artifactory\\n"
          echo "[INFO] Check the artifactory URL ${fullArtifactPath} returned status: $(status). \\n"
       }
       artifactId=null 
       version=null
    } // end for loop 
   } // end if app-info.yaml exists 
   if (statusCheck == 'fail') {
      resultMap.put('status', 'fail') 
      msg = ":X: **Dependency validation failed**\\n" + "Please review app-info.yaml\\n" +msg
   }

   else {
      resultMap.put('status', 'pass')
      if(msg == "") {
         msg = " warning: No Dependencies added under app_dependencies in app-info.yaml.\\nPlease refer to this confluence page on how to add the dependencies: [Application Dependency Framework](https://confluence-aes.kp.org/x/en5pE) \\n" + msg
      } else {
         msg = ":white_check_mark: Dependency validation passed\\n" + msg
      }
    }
    resultMap.put('msg', msg) 
    echo "[INFO] End artifactoryCheck, resultMap: ${resultMap)" 
    return resultMap 
  } catch(errr) {
     echo "[Error] artifactoryCheck method failed in PRCheck.groovy. $errr)
  }
}

/*check artifactory path status and Download file */
 def downloadFileSearch(address) {
   try{
      echo "[INFO] Searching address: ${address}" 
      def status = "fail" 
      def artifact_status = sh (script: "curi $(address)", returnStdout: true).trim() 
      def responseParse = readJSON text: artifact_status 
      def uristatus = responseParse['results']['uri'] 
      uristatus = uristatus.toString().replaceA11("\\[","") 
      uristatus = uristatus.toString().replaceA11("\\]","")
      if(uristatus == null || uristatus == ""){
         status = "fail" 
       } else {
         status = "pass"
       }
      return status
     } catch(err) {
       echo "[Error] downloadFileSearch method failed in PRCheck.groovy. ${err}"
     }
  }

def proxyConfigFileCheck (prbuilder, resultMap, config) {
  echo ************* Proxy config check ***** 
  //adding title to resultMap
  resultMap.put('title', prbuilder.'proxyConfigCheck'.'title') 
  //add squads to resultMap
   resultMap.put('squads', prbuilder. 'proxyConfigCheck'.'squads)

  def msg =""

  if (config && config.stages && config.'stages'. 'apigee-deploy') {
    def externalProxyValidationMsg = validateProxy(config, "external", resultMap)
    def internalProxyValidationMsg = validateProxy(config, "internal", resultMap)
    msg = externalProxyValidationMss + internalProxyValidationMsg 
  } else {
    resultMap.put("status", "pass")
  }
    resultMap.put("msg".msg)
    return resultMap
 }

def validateProxy(config, proxyType, resultMap) {
  def msg = ""
  def deployGroup = config.stages. 'apigee-deploy'.'proxy-definitions'."${proxy Type}"
  if(deployGroup) {
    def apigeeInfo = deployGroup.'apigeeInfo" 
    def filesLocation = deployGroup.'files-location'
    def configJsonFileName = apigeeInfo.'configIsonFileName'
    configJsonFileName = files Location + "/" + configIsonFileName
    def validationMap = opaValidation("apigee", configJsonFileName)
    resultMap.put("title", validationMap["title"]) 
    def violationArray = validationMap["violations"]
    if(violationArray.size() == 0) {
        resultMap.put("status", "pass")
    } else {
       msg = "\\n memo: **Violations found in " + configJsonFileName + "**\\n\\n"
       resultMap.put("status","fail") \
       for(i in violationArray) {
          msg += " X: **" + i + "**\\n"
       }
    }
  }
 return msg
}

// These are the input variables for the guardrails to read all the pom.xml and their attributes 
def pomDataIntialization (pomData){
  try {
    if(fileExists("${env.WORKSPACE}/pom.xml")) {
        pomData["pomFile"] = readFile "${env.WORKSPACE}/pom.xml" 
        pomData("parsedPom") = new XmlParser.parseText(pomData.pomFile)
        pomData("artifactId"] = pomData. parsedPom. artifactId[0].text()
     }
     if (fileExists("${env.WORKSPACE}/ui.apps/pom.xml")){
       pomData["pomUiApps"] = readFile "$(env.WORKSPACE}/ui.apps/pom.xml" 
       pomData["uiApps Response"] = new XmlParser() .parseText(pomData. pomUiApps)
       pomData["uiAppsValidRoots"] = "${pomData.uiApps Response.build.plugins.plugin.configuration.validatorsSettings.text()}"
       pomData["uiAppscloudManagerTarget"] = pomData.uiApps Response.build.plugins.plugin.configuration.properties.cloudManagerTarget
    }
    if (fileExists("${env.WORKSPACE}/ui.content/pom.xml")) {
       pomData("pomiContent"] = readFile "${env.WORKSPACE}/ui.content/pom.xml"
       pomData("uicontentResponse"] = new XmlParser() .parseText(pomData.pomUiContent) 
       pomData("uiContentValidRoots"] = "${pomData.uicontentResponse.build.plugins.plugin.configuration.validatorsSettings.text()}"
       pomData("uiContentcloudManagerTarget"] = pomData.uicontentResponse.build.plugins.plugin.configuration.properties.cloudManagerTarget
    }
    if (fileExists("${env.WORKSPACE}/ui.apps/src/main/content/META-INF/vault/filter.xml")) { 
       pomData("pomFilter"] = readFile "${env.WORKSPACE}/ui.apps/src/main/content/META-INF/vault/filter.xml"
       pomData("pomFilter Response") = new XmlParser() .parseText(pomData.pomFilter)
    }

    }catch(error) {
     echo "[ERROR]: No pom.xml or filter.xml were found in the repo."
    }
 }

def aemGuardrails(prbuilder, resultMap) {
  def msg = "" 
  resultMap = [:] 
  resultMap.put('title', prbuilder. 'aemGuardrailsCheck'. 'title') 
  // resultMap.put('squads', prbuilder. 'aemGuardrailsCheck'.'squads) // we are globally enforcing this
  def pomData = [:]
  pomDataIntialization(pomData)
  resultMap.put('msg', msg)

  def cloudMTCheck = cloudManagerTargetCheck(pomData, resultMap)
  if (cloudMTCheck){
     msg += "\\n : white_check_mark: AEM Guardrails Cloud Manager Target Validation Passed"
  } else {
     msg += "\\n :x: AEM Guardrails Cloud Manager Target Validations Failed: [Please refer AEM Guardrails Guideline](https://confluenceaes.kp.org/x/JM09G)" + resultMap.msg
  }

 def validRCheck = validRootsCheck (pomData, resultMap)
 if (validCheck){
   msg + "\\n :white_check_mark: AEM Guardrails Roots Check Validations Passed" 
 } else {
   msg += "\\n :X: AEM Guardrails Roots Check Validations Failed: [Please refer AEM Guardrails Guideline](https://confluence-aes.kp.org/x/JM09G)" + resultMap.msg
 }

 def filter XCheck = filterXmlCheck(pomData, resultMap) 
 if (filterXCheck){
    msg += "\\n :white_check_mark: AEM Guardrails Filter XML Check Validations Passed \\n"
 } else {
    msg += "\\n :x: AEM Guardrails Filter XML Check Validations Failed: [Please refer AEM Guardrails Guideline](https://confluenceaes.kp.org/x/JMo9G)" + resultMap.msg
 }

 //if all three checks pass, set overall status to pass. if one or more checks fail, set overall status to fail
 if (cloudMTCheck && validRCheck && filterXCheck){
    resultMap.put("status", "pass") 
 } else {
   resultMap.put("status","fail")
 }

 resultMap.put('msg', msg)
 return resultMap
}

def cloudManagerTargetCheck (pomData, resultMap){
  //Validate if the cloudManagerTarget is set to "none" in any pom.xml in the repo
  // If this is the case, the module will be skipped during deployment
  // if ( pom exists && tag defined && value "none" )
  // x2 for ui.apps and ui.content 
  if (pomData.pomUiApps && pomData.uiAppscloudManagerTarget S& pomData.uiAppscloudManagerTarget.text().equalsIgnoreCase("none")) {
    // || pomData.pomUiContent 88 pomData.uiContentcloudManagerTarget & pomData.uiContentcloudManagerTarget.text(.equals IgnoreCase("none"))){
    msg = resultMap.msg + "\\n :heavy exclamation mark! The cloudManagerTarget should not be set to NONE. Please check your pom.xml \\n"
    echo "[WARN]: The cloudManager Target check Failed. Please check your pom.xml"
    resultMap.put('msg', msg)
    return false
  } else {
    echo "[INFO]: The cloudManagerTarget check passed." 
    return true
 }
}

def validRootsCheck(pomData, resultMap) {
  def checkStatus = true 
  def msg =""
  // Validate if the valid roots has multiple artifact id's and if so the paths are comma separated: validate the response if the value contains kporg/${repoName}
  // ui.apps is required, and must be valid
   if (pomData.uiAppsValidRoots != "") {
    echo "[INFO] - Valid roots: ${pomData.uiAppsValidRoots}" 
    def numberCommas = pomData.uiAppsValidRoots.count(",")
    def numberArtifacts = pomData.uiAppsValidRoots.count(pomData.artifactId) 
    if(numberArtifacts != numberCommas + 1){
       checkStatus = false 
  } else {
    list = pomData.uiAppsValidRoots.split(",")
    list.each{ validRoots ->
       if(checkStatus) {
       checkStatus = validationOfValidRoots(validRoots, pomData)
       }
     }
   }
   } else {
      checkStatus = false
   }
  // ui.content is not required, but must be valid if exists
  if (fileExists("${env.WORKSPACE}/ui.content/pom.xml")) {
    if (check Status && pomData.uiContentValidRoots != ""){
     echo "[INFO] - Valid roots: ${pomData.uiContentValidRoots}"
     def numberContentCommas = pomData.uiContentValidRoots.count(",") 
     def numberContentArtifacts = pomData.uiContentValidRoots.count(pomData.artifactId) 
     if(numberContentArtifacts != numberContent Commas + 1){
         checkStatus = false 
     } else { 
     list = pomData.uiContentValidRoots.split(",")
     list.each( validRoots->
        if(checkStatus) {
          checkStatus = validationOfValidRoots(validRoots, pomData)
         }
      }
    }
    } else {
      checkStatus = false
    }
 }
  if (checkStatus) {
     echo "[INFO]: The Valid roots check passed."
     return true
     // resultMap.status will be populated in cloudManagerTargetCheck
   } else { 
      msg = resultMap.msg + "\\n * :heavy_exclamation_mark: The validRoots are missing or incorrect. Please check your pom.xml\\n" 
      resultMap.put('msg', msg) 
      return false
   }
 }



def validationOfValidRoots (validRoots, pomData){ 
  if (validRoots.contains("/kpors/${pomData.artifactId}")) {
     echo "[INFO] validRoots - ${validRoots} matched with artifactID - ${pomData.artifactId}"
     return true
   } else {
     echo "[WARN) validRoots are incorrect, please check your pom.xml" 
     return false
   }
 }

 def filterXml Check(pomData, resultMap) {
   def checkStatus = true
   def msg = "" 
   //validating the filter.xml 
   if (!pomData.pomFilterResponse || !pomData.pomFilterResponse.filter) {
    checkStatus = false 
   } else { 
     for (filter in pomData, pomFilter Response.filter.@root) { 
       if ("${filter}".startsWith("/apps/")) {
         if (checkStatus) {
             checkStatus = filterXmlValidation(filter, pomData)
          }
        } else if ("${filter}" == "" || "${filter}" == "null" || "$(filter)" == "None") {
          echo "[WARN] No path is configured. Please check your filter.xml."
          checkStatus = false
        }
      }
   }
 if (checkStatus) {
     echo "[INFO]: The Filter xml check passed."
     return true
     // resultMap.status will be populated in cloudManagerTargetCheck 
 } else {
     msg = resultMap.msg + "\\n :heavy_exclamation_mark: The filter.xml have missing or incorrect values. Please check your filter.xml \\n"
     resultMap.put('msg', msg) 
     return false
 }
}

def filterXmlValidation(filter, pomData){
  if ("${filter)".contains ("kporg/${pomData.artifactId}")){
     echo "[INFO] The path ${filter) is configured correctly."
     return true 
  } else {
     echo "[WARN) The path ${filter) is not configured corrrectly." 
     return false
  }
}

\**
* Validate the correct version of Qs Shared 
* libs parent pom (org.kp.quality.izaro) is used 
* @param prbuilder - made from pipleine_properties.json 
* @param resultMap 
* @return resultMap
*/
def qsIzaroCheck (prbuilder, resultMap, appType) {
  echo "[INFO] Starting izaro version check."
  def pom 
  def parentArtifact
  def parent PomVersion
  def folder 
  //msg to be added on PR page
  def msg = ''
  try {
     // need to wrap this in try-catch because there 'aem' type projects that are not app projects 
     // e.g. "nm-kp-ui-kit/" is a module not an application 
     // hardcoded pom version since we only hit this check if the app type in config is AEM 
     // nginx app type has different folder naming than aem
     if (appType == 'nginx'){ 
        folder = "ui.tests"
        pom = readMavenPom file: "$(WORKSPACE)/ui.tests/bobcat/pom.xml"
     } else {
        folder = "it. tests"
        pom = readMavenPom file: "${WORKSPACE}/it.tests/bobcat/pom.xml"
     }
   } catch (err) {
     // purposefully not printing err because it raising too many alarms in jenkins log 
     echo "[INFO] Error reading ${WORKSPACE}/" + folder + "/bobcat/pom.xml"
     msg = "iwarning: Unable to parse ${WORKSPACE}/" + folder + "/bobcat/pom.xml" 
     resultMap.put('status', 'pass')
     // adding title to resultMap
     resultMap.put('title', prbuilder. 'izaroVersionCheck'.'title')
     // add squads to resultMap 
     resultMap.put('squads', prbuilder.'izaroVersionCheck'.'squads) 
     // add message to result map 
     resultMap.put('msg', msg) 
     return resultMap
 }
 parentArtifact = pom.getParent().getArtifactId()
 parentPomVersion = pom.getParent().getVersion()

 echo "Parent pom version: ${parentArtifact} -${parentPomVersion}" 
 def major = getSemVersion ("major", parentPomVersion);
 def minor = getSemVersion ("minor", parentPomVersion);

  if ( major >= 5 && minor >= 0) {
     msg = " :white_check_mark: Appropriate izaro parent pom found."
     resultMap.put('status', 'pass') 
  } else {
     msg = ":warning: IZARO VERSION INCORRECT\\nPlease use version 5.0.0-SNAPSHOT or greater of izaro parent pom." 
     // for now we set result to pass because we aren't forcing izaro versions yet. 
     resultMap.put('status', 'pass')
  }

  // adding title to resultMap 
  resultMap.put('title', prbuilder. 'izaroVersionCheck'.'title')

  // add squads to resultMap 
  resultMap.put('squads', prbuilder.'izaroVersionCheck'.'squads)

  // add message to result map
  resultMap.put('msg', msg)

   return resultMap
 }

 /**
 * Validates definitions/swagger.yaml schema file confirms to specification of 
 * open API 2.0 or 3.0 and blocks PR 
 */
 def apiValidatorCheck(prbuilder, resultMap) {
 def msg =''
 def JsonResult = apivalidator("pre-commit", null, null, null);
 if(jsonResult.dod_status == 'PASSED' ) {
   resultMap.put('status', 'pass')
    msg = "\\n :white_check_mark: Open API Schema Validation Check passed \\n"
  }else {
     resultMap.put('status', 'fail')
     def message = jsonResult.dod_detailed_result
     message = message.replace("\n", " ");
     msg = "\\n :x: **Open API Schema validation Check Failed**\\nPlease refer to this confluence page on how to fix this check: [PR check for DOD checks] " + msg
     current Build.result = FAILED
  }
   resultMap.put('msg', msg) 
   resultMap.put('title', prbuilder. 'openAPISchemaCheck'.'title') 
   resultMap.put('squads', prbuilder. 'openAPISchemaCheck'.'squads') 
   return resultMap
 }

 def loggingLevelCheck(prbuilder, resultMap) {
    resultMap.put('title', prbuilder. 'loggingLevelCheck'. 'title')
    resultMap.put('squads'prbuilder. 'loggingLevelCheck'. 'squads')
    def msg = ''
    def jsonResult = loggingLevelValidator("pre-commit")
    echo "[INFO] Json result: $(jsonResult)" 
    if(jsonResult.dod status == 'PASSED' ) {
        resultMap.put('status', 'pass)
        msg = ":white_check_mark: Logging Level Validation Check passed \\n"
    }else {
       resultMap.put('status', 'fail') 
       def message = jsonResult.dod_detailed_result
       msg = " :warning: Logging Level Validation Check Failed\\nPlease refer to this confluence page on how to fix this check: [PR check for DOO checks] " + msg
    }
    resultMap.put('msg', msg)
    return resultMap
 }

 def runDoDCheck (PRCommentMap, prbuilder, enforceList, githubRepoPrefix, appType) {
   if (appType == "aks") {
    def resultMap = [:] //local instance 
    node("aks") {
      deleteDir() 
   try {
       // checkout sem if node executor happens to be empty 
       if (!fileExists("pipeline.json")) {
       checkout scm
       }
      def runLogLevel = true
      if (fileExists("./definitions / swagger.yaml")) {
          resultMap = apiValidatorCheck(prbuilder, resultMap) 
          generateResults(resultMap, PRCommentMap, enforcelist, githubRepoPrefix)
       }
       else{
         echo "[INFO] Status for open API Schema Validation: SKIPPED"
         echo "[INFO] Message: Couldn't find definitions/swagger.yaml file, Skipping Open API Schema Validation Check" 
         runLogLevel = false
       }
       if(runLogLevel) {
          resultMap = loggingLevelCheck(prbuilder, resultMap)
          generateResults(resultMap, PRCommentMap, enforceList, githubRepoPrefix)  
       }
     } catch (exDoD) {
       echo "[ERROR] failure in DoD: ${exDoD}"
      }
    }
  }
}

def getSemVersion(type, version) {
   def semver 
   def split = version.split("\\.")

   if (type == "patch") {
      def patch = split[2].split("\\-")[0]
      semver = Integer.parseInt(patch[0]) 
   } else if (type == "minor") {
      semver = Integer.parseInt(split[1])
   } else if (type == "major") {
      semver = Integer.parseInt(split[0]) 
   } else {
      echo "[ERROR] This version type (${type}) doesn't exist. It must be either one of 'patch', 'minor' or 'major'."
   }
    return semver
}






























































