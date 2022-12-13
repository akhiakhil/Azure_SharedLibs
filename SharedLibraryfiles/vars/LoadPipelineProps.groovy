def call(propName) {
  try {
      echo '[INFO] Loading Pipeline Properties'
      //load pipeline shared libs json 
      def loadScript = library Resource "pipeline_properties.json"
      writeFile file: "pipeline_properties.json", text: loadScript 
      inputFile = readFile("${WORKSPACE}/pipeline_properties.json")
      pipelineProps = readJSON text: inputFile 
      if(pipelineProps == null) {
             error ("AEM Environments object is null. Read file: pipeline_properties.json from shared libs pipelineProps == null/LoadPipelineProps.groovy.")
      }

      def env = determineEnv() 
      def org = 'CDO-xx-ORG'
          if( propName == "checkout-scm-id" )
          {
          def orgNameJson = pipelineProps."checkout-scm-id" 
      if("${orgNameJson}" != null && "${orgNameJson}" != "" && "${orgNameJson}" != "null")
          { org = determineOrg( orgNameJson ) } 
         println(" Loading Pipeline Properties org ${org} propName ${propName} orgNameJson ${orgNameJson} ")
      }


      switch(propName) { 
          case 'aks-cas-shared-libs':
             return pipelineProps. 'aks-cas-shared-libs' 
          case 'lighthouse-enable-global':
             return pipeline Props.'lighthouse'.'enable-audit'
          case 'artifactory-url':
             return pipelineProps.'artifactory-url'."${env}"
          case 'sonarqube-url':
             return pipelineProps. 'sonarqube-url'."${env}"
          case 'sonarqube Quality Gate':
             return pipeline Props. 'sonarqubeQualityGate'."${env}"
          case 'sonarqubeQuality Profiles':
             return pipelineProps.'sonarqubeQualityProfiles' 
          case 'rabbitmq-host':
             return pipelineProps. 'rabbitmq-host'."${env}" 
          case 'neo4j-host-port':
             return pipelineProps. 'neo4j-host-port'."${env}" 
          case 'failonSonarException':
             return pipelineProps.failonSonarException
          case 'nexus-webservice':
             return pipelineProps."nexus-webservice"
          case 'whitehat-webservice':
             return pipelineProps. "whitehat-webservice"
          case 'aem-cache-flush':
             return pipelineProps."aem-cache-flush" 
          case 'performance_test_server_url':
             return pipelineProps. 'performance_test_server_url'
          case 'confluence_page_details':
             return pipelineProps.'confluence_page_details'
          case 'maven-home':
             return pipelineProps.'tools'. 'maven-home'
          case 'prbuilder':
             return pipelineProps. 'prbuilder'
          case 'checkout-scm-id':
             return pipelineProps.'checkout-scm-id'."${org}"
          case 'pipeline-checkout-scm-id':
             return pipelineProps.'checkout-scm-id'. 'CDO-xx-ORG'
          case 'notifyFlags':
             return pipelineProps.'notifyFlags'
          case 'tagging-and-my-failures':
             return pipelineProps. 'tagging-and-mq-failures'. 'webhook'
          case 'envNotification Map':
             return pipelineProps. 'envNotificationMap'
          case 'insights_rabbitmq_creds':
             return pipelineProps. 'insights_rabbitmq'.'insights_rabbitmq_creds'
          case 'dod-check-qtest-token':
             return pipelineProps. 'dod-check-qtest-token'.'dod-check-qtest-token' 
          case 'apigee-smoke-test':
             return pipelineProps. 'apigee-smoke-test' . 'enabled'
          case 'regressionQualityGate':
             return pipelineProps. 'regressionQualityGate' 
          case 'nexus-failures':
             return pipelineProps. 'nexus-failures'. 'webhook'
          default:
             return pipelineProps
        }
     } catch(err) {
          error("Caught Exception in LoadPipelineProps.groovy: ${err}")
     }
 }

 def determineEnv() {
   if("${JENKINS_URL}".contains("localhost")) {
       return 'local' 
   } else if("${JENKINS_URL)".contains("sandbox")) {
      return 'sandbox'
   } else {
      return 'production'
   }
 }

/*
  Used to get org name from url and compare with pipelinejson
*/

def determineOrg( orgNameJson ) {
       def orgReturn = 'CDO-xx-ORG' 
    if("${env.configRepoGitHttpURL}" != null && "${env.configRepoGitHttpURL}" != "" && "${env.configRepoGitHttpURL}" != "null")
    {
       def orgNameUrl = "${env.configRepoGitHttpURL)".split('/ '[3] 
       def orgName = orgNameJson."${orgNameUrl}" 
       if("${orgName}" != null && "${orgName}" != "" && "${orgName}" is "null")
       {            orgReturn = orgNameUrl 
       println" orgNameUrl: ${orgNameUrl} orgName: ${orgName} orgReturn: ${orgReturn}"
    }
    return orgReturn
    }








