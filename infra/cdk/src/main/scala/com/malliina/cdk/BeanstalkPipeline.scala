package com.malliina.cdk

import software.amazon.awscdk.services.codebuild._
import software.amazon.awscdk.services.codecommit.Repository
import software.amazon.awscdk.services.codepipeline.actions.{CodeBuildAction, CodeCommitSourceAction}
import software.amazon.awscdk.services.codepipeline.{Artifact, IAction, Pipeline, StageProps}
import software.amazon.awscdk.services.elasticbeanstalk.{CfnApplication, CfnConfigurationTemplate, CfnEnvironment}
import software.amazon.awscdk.services.iam.{CfnInstanceProfile, ManagedPolicy, Role}

object BeanstalkPipeline {
  def apply(stack: AppStack): BeanstalkPipeline =
    new BeanstalkPipeline(stack)
}

class BeanstalkPipeline(stack: AppStack) extends CDKBuilders {
  val envName = s"${stack.prefix}-refapp"
  val app = CfnApplication.Builder
    .create(stack, "MyCdkBeanstalk")
    .applicationName(envName)
    .description("Built with CDK in Helsinki")
    .build()
  val appName = app.getApplicationName
  val namePrefix = "MyCdk"
  val dockerSolutionStackName = "64bit Amazon Linux 2 v3.1.0 running Docker"
  val javaSolutionStackName = "64bit Amazon Linux 2 v3.1.0 running Corretto 11"
  val solutionStack = javaSolutionStackName

  val branch = "master"

  def makeId(name: String) = s"$envName-$name"

  // Environment

  val serviceRole = Role.Builder
    .create(stack, makeId("ServiceRole"))
    .assumedBy(principal("elasticbeanstalk.amazonaws.com"))
    .managedPolicies(
      list(
        ManagedPolicy.fromAwsManagedPolicyName("service-role/AWSElasticBeanstalkEnhancedHealth"),
        ManagedPolicy.fromAwsManagedPolicyName("service-role/AWSElasticBeanstalkService")
      )
    )
    .build()
  val appRole = Role.Builder
    .create(stack, makeId("AppRole"))
    .assumedBy(principal("ec2.amazonaws.com"))
    .managedPolicies(
      list(ManagedPolicy.fromAwsManagedPolicyName("AWSElasticBeanstalkWebTier"))
    )
    .build()
  val instanceProfile = CfnInstanceProfile.Builder
    .create(stack, makeId("InstanceProfile"))
    .path("/")
    .roles(list(appRole.getRoleName))
    .build()
  val configurationTemplate = CfnConfigurationTemplate.Builder
    .create(stack, makeId("BeanstalkConfigurationTemplate"))
    .applicationName(appName)
    .solutionStackName(solutionStack)
    .optionSettings(
      list[AnyRef](
        optionSetting(
          "aws:autoscaling:launchconfiguration",
          "IamInstanceProfile",
          instanceProfile.getRef
        ),
        optionSetting("aws:elasticbeanstalk:environment", "ServiceRole", serviceRole.getRoleName),
        optionSetting("aws:elasticbeanstalk:application:environment", "PORT", "9000"),
        optionSetting(
          "aws:elasticbeanstalk:application:environment",
          "APPLICATION_SECRET",
          "{{resolve:secretsmanager:dev/refapp/secrets:SecretString:appsecret}}"
        )
      )
    )
    .build()
  val beanstalkEnv = CfnEnvironment.Builder
    .create(stack, makeId("Env"))
    .applicationName(appName)
    .environmentName(envName)
    .templateName(configurationTemplate.getRef)
    .solutionStackName(solutionStack)
    .build()

  // Pipeline

  val buildEnv =
    BuildEnvironment
      .builder()
      .buildImage(LinuxBuildImage.STANDARD_4_0)
      .computeType(ComputeType.SMALL)
      .build()
  val buildSpec =
    if (solutionStack == javaSolutionStackName) "buildspec-java.yml" else "buildspec.yml"
  val codebuildProject =
    PipelineProject.Builder
      .create(stack, makeId("Build"))
      .buildSpec(BuildSpec.fromSourceFilename(buildSpec))
      .environment(buildEnv)
      .build()
  val repo = Repository.Builder
    .create(stack, makeId("Code"))
    .repositoryName(makeId("Repo"))
    .description(s"Repository for $envName environment of app $appName.")
    .build()
  val sourceOut = new Artifact()
  val buildOut = new Artifact()
  val pipelineRole = Role.Builder
    .create(stack, makeId("PipelineRole"))
    .assumedBy(principal("codepipeline.amazonaws.com"))
    .managedPolicies(
      list(
        ManagedPolicy.fromAwsManagedPolicyName("AmazonS3FullAccess"),
        ManagedPolicy.fromAwsManagedPolicyName("AWSCodeCommitFullAccess"),
        ManagedPolicy.fromAwsManagedPolicyName("AWSCodePipelineFullAccess"),
        ManagedPolicy.fromAwsManagedPolicyName("AWSElasticBeanstalkFullAccess")
      )
    )
    .build()
  val pipeline: Pipeline = Pipeline.Builder
    .create(stack, makeId("Pipeline"))
    .role(pipelineRole)
    .stages(
      list[StageProps](
        StageProps
          .builder()
          .stageName("Source")
          .actions(
            list[IAction](
              CodeCommitSourceAction.Builder
                .create()
                .actionName("SourceAction")
                .repository(repo)
                .branch(branch)
                .output(sourceOut)
                .build()
            )
          )
          .build(),
        StageProps
          .builder()
          .stageName("Build")
          .actions(
            list[IAction](
              CodeBuildAction.Builder
                .create()
                .actionName("BuildAction")
                .project(codebuildProject)
                .input(sourceOut)
                .outputs(list(buildOut))
                .build()
            )
          )
          .build(),
        StageProps
          .builder()
          .stageName("Deploy")
          .actions(
            list[IAction](
              new BeanstalkDeployAction(
                EBDeployActionData(
                  "DeployAction",
                  buildOut,
                  appName,
                  beanstalkEnv.getEnvironmentName
                )
              )
            )
          )
          .build()
      )
    )
    .build()
}
