# Azure Pipeline Library

A library for Azure DevOps pipeline to build opinionated pipelines. This framework/model will help development teams rapidly create CI/CD pipelines for their applications.

## **How to use the library?**
Developer creates a yaml file in the application and add the pipeline repository as an external repository reference. The pipeline will extend the pipeline_template to build the application.

**Note**: The external repository is referred as `pipelinelibrary`. This must not be changed.


Example configuration for a .Net application

```yaml
resources:
  repositories:
  - repository: pipelinelibrary # The name used to reference this repository in the checkout step. This name must be set to pipelinelibrary
    type: github
    endpoint: 'vijaykumar-r'
    name: 'vijaykumar-r/azure-pipeline-library'
    ref: refs/heads/master

extends:
  template: pipeline_templates/pipeline_template.yml@pipelinelibrary
  parameters:
    projectName: 'eShop On Web'
    workingDirectory: 'eshoponweb'
    buildType: 'dotnet'
    dotnetCoreSDKVersion: '5.0.403'
    sonarProjectKey: 'eshop-web'
    infraTerraformFolder: 'infrastructure'
    dockerFilePath: 'src/Web/Dockerfile'
    dotnetProjectORSolution: 'src/Web/Web.csproj'
```

Example configuration for a Java application

```yaml
resources:
  repositories:
  - repository: pipelinelibrary # The name used to reference this repository in the checkout step. This name must be set to pipelinelibrary
    type: github
    endpoint: 'vijaykumar-r'
    name: 'vijaykumar-r/azure-pipeline-library'
    ref: refs/heads/master

extends:
  template: pipeline_templates/pipeline_template.yml@pipelinelibrary
  parameters:
    projectName: 'Spring Boot App Gradle'
    workingDirectory: 'spring-boot-application-gradle'
    buildType: 'java'
    javaBuildTool: 'gradle'
    jdkVersion: '1.11'
    enableSmokeTest: true
    enableFunctionalTest: true
    javaOutputJarFile: 'build/libs/spring-boot-app.jar'
    sonarProjectKey: 'spring-boot-application-gradle'
    infraTerraformFolder: 'infrastructure'
    dockerFilePath: 'Dockerfile'
```

## **Opinionated application pipeline**

This library contains opinionated pipeline that can build and test Java, .Net applications. More languages will be added in future updates.

The pipeline contains the following stages:

| Stage  | Operation  |
| ---------- | --------- |
| Build/Test/Scan | Builds application, runs code quality checks using Sonar Cloud, checks application for vulnerabilities using OWASP dependency checks, runs unit, smoke, funtional and integration tests. The test and reports are stored as an artifact in the pipeline.
| Build Infrastructure | Creates application dependant infrastructure on Azure using Terraform. For example, database, storage account etc.
| Build Image | Builds docker images using the artifact, scans images using Trivy scanner for vulnerabilities and pushes it to Docker Hub.

This version of the library supports Gradle as the build tool for Java applications.


## **Branch to Environment mapping**

This opinionated pipeline use the following branch mappings to determine the different environments.

| Branch | Environment |
| --- | --- |
| `master` | `prod`
| `dev` | `dev`
| `test` | `test`

**Note:** Pull request branch environment will be `pr`

## **Extending the opinionated pipeline**

The pipeline stages are fixed and cannot be modified. However, it is possible to add additional steps to the stages. The before and after steps hooks can be used to add custom steps that is required by your application. These hooks are detailed in the parameters reference section.


## **Parameter Reference**

| Parameter  | Mandatory | Usuage |
| ---------- | --------- | -------|
| projectName | Yes | The name of the project. This name will be used to build the docker image and store Terraform state files
| workingDirectory | Yes | Usually the root folder of your application
| buildType | Yes | The build type of your application. Accepted values are `java`, `nodejs`, `dotnet`, `dotnetcore`
| javaBuildTool | No | The build tool for a Java application. Accepted values are `gradle` and `maven`. Defaulta to `gradle`
| jdkVersion | No | The JDK version to use for a Java application. Defaults to 1.11
| javaOutputJarFile | No | Requried if buildType is `java`. Relative path where the jar or war file is created when the application is built. For example, 'build/libs/spring-boot-app.jar'
| dotnetCoreSDKVersion | No | The SDK version to use for a .Net application. Defaults to 5.0.403
| nodeJsVersion | No | The NodeJS version to use for a NodeJS application. Defaults to TBC
| dotnetProjectORSolution | No | Requried if the buildType is `dotnet` or `dotnetcore`.Relative path of the `.sln` or `.csproj` file. For example, 'src/Web/Web.csproj'
| sonarProjectKey | Yes | The project key for the application in Sonar Cloud
| beforeBuildSteps | No | Azure pipeline steps to execute before the build steps starts
| afterBuildSteps | No | Azure pipeline steps to execute after the build steps starts
| infraTerraformFolder | No | The relative path where the terraform configurations exists
| beforeBuildInfraSteps | No | Azure pipeline steps to execute before the build infrastructure steps starts
| afterBuildInfraSteps | No | Azure pipeline steps to execute before the build infrastructure steps starts
| dockerFilePath | No | The relative path of the Dockerfile. For example, 'src/Web/Dockerfile'
| beforeImageBuildSteps | No | Azure pipeline steps to execute before the Docker build steps starts
| afterImageBuildSteps | No | Azure pipeline steps to execute before the Docker build steps starts


## **Docker Image Name and Tags**

The master build will produce a docker image with the name of the application and tagged with the last commit ID (Short). For example, if the projectName is `Spring Boot App Gradle`, the name of the image and tag will be `spring-boot-app-gradle:prod-e38547b`.

Pull Request images will be tagged as `spring-boot-app-gradle:pr-4-8aeeb4d`.

## ***Contributing to this library***

1. Use Github pull request to submit changes
2. Point the branch reference in reposiytory reference to test changes