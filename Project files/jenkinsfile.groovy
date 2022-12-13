@Library('jenkins-shared-libs')
pipeline {
   agent none
   tools { nodejs 'nodel4' } // Pick your node version between 8, 10, 12, or 14
   stages {
     Stage('Initialization') {
       agent { label 'aks' }
       steps {
          deleteDir()
          checkout scm
          script {
             echo "[INFO] Loading JSON configuration from : ${env.WORKSPACE}/pipeline. json"
             json0bj = readJSON file: "${env.WORKSPACE}/pipeline. json"
             echo "[INFO] Done Loading JSON configuration"
             InitAksPipeline(json0bj)
             ciEnv = aksUtils.getCiEnv(jsonObj)
         }
        }
    }

  stage( 'Build Pipeline Extension') {
  agent { label 'aks' }
  when {
    /* anyOf { branch ‘master'} */ 
     anyOf { environment name: 'operation', value: 'build-pipeline-extension'}
    }
    steps{
       BuildPipelineExtension(json0bj)
      }
   }

  stage( 'Build' ){
    agent { label ‘aks' }
    when {
      anyOf {
         environment name: 'operation', value: 'build';
         branch 'PR-*'
        } I
     }
    steps{
        BuildMvn(json0bj)
     }
   }

  stage('Docker Build') {
    agent { label 'aks' }
    when {
    allof {
     environment name: 'operation', value: 'build';
     not { branch 'PR-*'}
     }
    }
    steps {
       DockerBuild(json0bj)
     }
   }

  stage( 'Deploy to CI'){
    agent { label 'aks' }
      when { 
         environment name: 'operation', value: 'build';
         not { branch 'PR-*'}
       }
     steps{
       DeployToAKS(ciEnv, json0bj, "deploy")
      }
   }

  stage( 'Deploy APIGEE') {
    agent { label ‘aks }
    when {
       expression { params. APIGEE_ENV != 'None'}
     }
     steps { 
          ApigeeDeployer(["apigeeEnv": "${params.APIGEE_ENV}", "proxyDef":"internal"])
      }
     Post {
      success{
          Notify([message: "APIGEE Deployment successful", build status: “SUCCESS"])
      }
      failure {
          Notify([message: "APIGEE Deployment failed", build_status: "FATLED"})
      }
     }
   }

  Stage("Deploy - NO B/G'){
    agent { label 'aks' }
     when {
       expression { params-operation =~ /deploy-to-*/ 8& params.'AKS DEPLOYMENT' == true && params.'BLUE_GREEN_DEPLOYEMENT' !="true"}
     }
     steps{
        DeployToAKS(params.AKS_ENV, json0bj, "deploy")
     }
     post {
      success{
           Notify([message: "Non-prod deployment successful", build_status: "success"])
        }
      failure {
            Notify([message: "Non-prod deployment failed", build status: "FATLED"])
        }
     }
   }
 
  Stage('Deploy App to Test Route'){
    agent { label 'aks' }
    when {
      expression { params.operation =~ /deploy-to-*/ && params. 'AKS_DEPLOYEMENT' == true && params. 'BLUE GREEN DEPLOYMENT' == "true" }
     }
    steps { 
      DeployToAKS(params.AKS_ENV, json0bj, "deployTest")
    }
    post {
      success{
           Notify([message: "deployment OF TEST app successful", build_status: "success"])
        }
      failure {
            Notify([message: " deployment of test app failed", build status: "FATLED"])
        }
     }
   }

  stage('Proceed to Switch') {
    when {
      expression { params.operation =~/deploy-to-*/ && params. 'AKS_DEPLOYMENT' == true && params. 'BLUE GREEN_DEPLOYMENT' =="true" }
     }
    steps {
     checkpoint "Proceed to  Switch"
    }
   }
 
   stage("Switch Test Route to Live Route") {
     agent { label 'aks }
     when {
        expression {params. operation =~ /deploy-to-*/ && params. 'AKS_DEPLOYMENT' == true && params. 'BLUE_GREEN_DEPLOYMENT =="true"}
     }
     steps {
       DeployToAKS(params.AKS-ENV, jsonObj, "switch")
     }
     post {
       Success {
          Notify([message: "Switch Test App to Live App successful", build_status: "SUCCESS"]) 
        }
       failure {
          Notify ([message: "Switch Test App to Live App failed", build_status: "FAILED"]) 
        }
     }
   }
 
  stage('Proceed to DR deploy') { 
    when {
    expression { params. operation - /deploy-to-prod/ && params. 'AKS_DEPLOYMENT' == true && params. 'BLUE_GREEN_DEPLOYMENT' =="true" } 
    steps {
       checkpoint "Proceed to DR deploy"
     }
  }
  stage("Deploy to DR") {
    agent { label 'aks' } 
     when {
       expressionparams.operation - /deploy-to-prod/ && params. 'AKS_DEPLOYMENT' == true && params. 'BLUE GREEN_DEPLOYMENT' == "true" }
     steps {
        DeployToAKS("dr", sonObj, "deploy Test")
      }
     post {
       Success {
             Notify( [messag je: "DR Deployment successful", build_status: "SUCCESS"])
       }
      failure  {
              Notify([message: "DR Deployment failed", build_status: "FAILED"])
       }
     }
   }
  
   stage('Proceed to DR Switch') {
    when {
      expression { params.operation =~/deploy-to-prod/ && params. 'AKS_DEPLOYMENT' == true && params. 'BLUE GREEN_DEPLOYMENT' =="true" }
     }
    steps {
     checkpoint "Proceed to  DR Switch"
      }
    }
 
   stage(" dr Switch Test Route to Live Route") {
     agent { label 'aks }
     when {
        expression {params. operation =~ /deploy-to-prod/ && params. 'AKS_DEPLOYMENT' == true && params. 'BLUE_GREEN_DEPLOYMENT =="true"}
     }
     steps {
       DeployToAKS("dr", jsonObj, "switch")
     }
     post {
       Success {
          Notify([message: "DR Switch Test App to Live App successful", build_status: "SUCCESS"]) 
        }
       failure {
          Notify ([message: " DR Switch Test App to Live App failed", build_status: "FAILED"]) 
        }
     }
   }
    stage('Proceed to Rollback') {
    when {
      expression { params.operation =~/deploy-to-*/ && params. 'AKS_DEPLOYMENT' == true && params. 'BLUE GREEN_DEPLOYMENT' =="true" }
     }
    steps {
     checkpoint "Proceed to  DR RollBack"
      }
    }
 
   stage(" Rollback") {
     agent { label 'aks }
     when {
        expression {params. operation =~ /deploy-to-*/ && params. 'AKS_DEPLOYMENT' == true && params. 'BLUE_GREEN_DEPLOYMENT =="true"}
     }
     steps {
       DeployToAKS(params.AKS_ENV, jsonObj, "rollback")
     }
     post {
       Success {
          Notify([message: "Rollback successful", build_status: "SUCCESS"]) 
        }
       failure {
          Notify([message: "Rollback failed", build_status: "FAILED"]) 
        }
     }
   }
 



