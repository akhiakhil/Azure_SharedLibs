def addCommentsToPR(map, prbuilder) {
     //loop through the PRCommentMap and append the values of strings to one variable
     def commentsData = ' ;
     map.each {
          key, value -> commentsData = commentsData + value + "\\n"
     }
     // [TODO] refactor check if we can use "checkout-sem-id": "CDO-xx-ORG": "svcfofprod-github-token" and remove prbuilder variable
     def githubCredId = prbuilder.github-cred-id'
     def githubPRData = """{"body": "${commentsData}"}"""
     withCredentials([usernamePassword(credentialsId: githubCredid, passwordVariable: 'token_pw', usernameVariable: 'token_user')]) {
          def githubComments = sh(script: "curl -H 'Authorization: Token ${token_pw}' -X POST https://github.xx.org/api/v3/repos/${utils.getOrgName ()}/${utils.getRepoName()}/issues/${env.CHANGE_ID}/comments -d '${githubPRData}'", returnStdout: true)
      }
}

def commentOnPR(gitHubPRmsk, property, Jenkins LogErr) {
     //if branch is not a PR branch
     if (!env. BRANCH_NAME.startsWith( 'PR-')) {
          echo '[INFO] Not on PR branch' 
          return
     }
     //if PR branch, only run PR checker if target branch is master, release/", or develop 
     else{ 
         if (env.CHANGE_TARGET = "master" && !env.CHANGE_TARGET.contains("release/") && env.CHANGE_TARGET != "develop"){
         echo "[INFO] PR Target branch is not master, release, or develop"
         return
         }
      }

       // Get values needed to call add CommentsToPR(PRCommentMap, prbuilder)Standard Pool 2 32-bit
       def PR CommentMap = [:] 
       PRCommentMap.put(property, gitHubPRmsg)

       def githubRepoPrefix = utils.getRepoName().split('-')[0]   // squadPrefix
       def prbuilder = LoadPipelineProps('prbuilder') 
       def squadValue = prbuilder."${property}".'squads'

       addCommentsToPR(PRCommentMap, prbuilder)
       //check if merge check should be enabled 
       if (squadValue) {
            //inclusion list to block merge based on squad prefix 
            if (squadValue.contains (githubRepoPrefix)) {
                 //SQ block merge - only for jenkins shared libs
                 if (property == "sonarqubeCheck") { 
                    if (jenkinsLogErr == "Error") {
                        blockMerge(prbuilder, jenkinsLogErr)
                  }
            } else{
                 // squad specific enforcement
                 blockMerge(prbuilder, jenkins LogErr)
            }
       } else {
            //global enforce
            if (jenkins LogErr.toString().equalsIgnoreCase('Error')) blockMerge(prbuilder, jenkins LogErr)
       }
 }

 def sqDecoratorComment(orgName, repoName, prNum) { 
     if (org Name == "CDO-xx-ORG") {
         withCredentials([string(credentialsId: 'svcfofprodSQ-64', variable: 'sonarqubeAuth')]) {
            def sqGateStatus = sh(script: "curl -H Authorization: Basic ${sonarqubeAuth}' "https://sonarqubebluemix.xx.org/api/qualitygates/project_status?projectKey=${orgName} :${repoName)&pullRequest-${prNum}' | jq '.projectStatus.status'", returnStdout: true)
            println "PR sqGateStatus: " + sqGateStatus.trim().replaceAll("\"","")
            if (sqGateStatus.trim().replaceAll("\"","") == "OK")
                sqComment = ":white_check_mark: SonarQube Code Analysis - Quality Gate passed for pull request"
                // [todo) - rewrite - to have variable for PR block merge in the last passed parameter 
                commentOnPR(SqComment, "sonarqubeCheck", "Success")
             }
             else{
                sq Comment = ":x: **SonarQube Code Analysis - Quality Gate failed for pull request**"
                // [todo) - rewrite - to have variable for PR block merge in the last passed parameter
                commentOnPR (sq Comment, "sonarqubeCheck", "Error")
             }
           }
       }
  }

  def blockMerge(prbuilder, err = ''){
       def errDetail =''
       prURL = utils.getRepoUrl().minus('git') + "/pull/${env. CHANGE_ID}/"

       try { 
           withCredentials([string(credentialsId: prbuilder.'github-api-token', variable: 'githubtoken')]) {
                utils. loadShellScripts("github_status_checks.py")
                sh "python3 gåthub_status_checks.py ${utils.getOrgName()} ${utils.getRepoName()} ${githubtoken} prcheck ${env.CHANGE_TARGET}"
           }
       } catch (errp) {
           echo "FAILURE: While checking the github status: ${errp}"
       }

        //only add pipeline failure message when Build failure 
       if (err != '') {
           errDetail = "Error encountered ${err}"
        }

        ansiColor('xterm') {
              echo "\033[31m\033[1mGo to the Pull Request page comments for more details: \033[Om ${prURL}" 
              currentBuild.result = 'FAILURE'
              error "\033[31m\033[im[ERROR]: Pull Request Checks have failed. ${errDetail}\033[0m" // use currentBuild.result = 'FAILURE' if want to stages to continue btu build to break
        }
 }









