def checkout(String gitRepoName, String gitRepoUrl) {
    echo "Download ${gitRepoName} from gitlab"

    checkout([$class: "GitSCM",
              branches: [[name: "*/main"]],
              doGenerateSubmoduleConfigurations: false,
              extensions: [lfs(), [$class: "LocalBranch", localBranch: "**"]],
              submoduleCfg: [],
              userRemoteConfigs: [[credentialsId: "sshkey_for_gitlab",
                                   url: "${gitRepoUrl}"]]
    ])
}

def createJar(String jarName){
    echo "create jar ${jarName}"

    sh "mvn package -Dmaven.test.skip -DoutputDirectory=."
    sh "mv target/${jarName} ./${jarName}"
}

def createJarUsingGradle(String jarName){
    echo "create jar ${jarName}"

    sh "./gradlew build"
    sh "mv build/libs/${jarName} ./${jarName}"
}

def buildDockerImage(String nodeName, String sudoPw, String ecrRepositoryName){
    echo "Build Docker Image"

    if("${nodeName}" == "office-ryeong-macstudio") { //office-ryeong-macstudio
        sh "echo ${sudoPw} | sudo -S docker buildx build --platform=linux/amd64 -t ${ecrRepositoryName} . "
    } else { //intel office
        sh "docker buildx build --platform=linux/amd64 -t ${ecrRepositoryName} . "
    }
}

def pushDockerImageToEcr(String keyChainPw, String awsRegion, String ecrRepositoryAddress, String ecrRepositoryUrl, String ecrRepositoryName, String dockerImageTag){
    echo "Push Docker Image to ECR"

    withCredentials([[
             $class           : "AmazonWebServicesCredentialsBinding",
             credentialsId    : "aws-jenkins-eb",
             accessKeyVariable: "AWS_ACCESS_KEY_ID",
             secretKeyVariable: "AWS_SECRET_ACCESS_KEY"
     ]]) {
        sh "security unlock-keychain -p ${keyChainPw} '/Users/jenkins/Library/Keychains/jenkins.keychain-db'"
        sh "aws ecr get-login-password --region ${awsRegion} | docker login --username AWS --password-stdin ${ecrRepositoryAddress}"

        //Push the Docker image to ECR
        docker.withRegistry("${ecrRepositoryUrl}")
        {
            docker.image("${ecrRepositoryName}:${dockerImageTag}").push()
        }
    }
}

def deployToElasticBeanstalk(String awsRegion, String appName, String envName, String versionLabel, String s3Bucket, String s3Key){
    echo "Deploy to Elastic Beanstalk"
    withCredentials([[
             $class: "AmazonWebServicesCredentialsBinding",
             credentialsId: "aws-jenkins-eb",
             accessKeyVariable: "AWS_ACCESS_KEY_ID",
             secretKeyVariable: "AWS_SECRET_ACCESS_KEY"
     ]]) {
        sh  """
            aws elasticbeanstalk create-application-version \
                --region ${awsRegion} \
                --application-name ${appName} \
                --version-label ${versionLabel} \
                --source-bundle S3Bucket=${s3Bucket},S3Key=${s3Key}
            """

        sh  """
            aws elasticbeanstalk describe-application-versions \
                --application-name ${appName} \
                --version-labels ${versionLabel} \
                --query "ApplicationVersions[0].Status"
            """

        sh  """
            aws elasticbeanstalk update-environment \
                --region ${awsRegion} \
                --environment-name ${envName} \
                --version-label ${versionLabel}
            """
        sh "aws elasticbeanstalk wait environment-updated --environment-name ${envName}"
    }
}

def deleteCurrentAmi(String amiName, String awsRegion){
    echo "delete current ami"
    withCredentials([[
             $class: "AmazonWebServicesCredentialsBinding",
             credentialsId: "aws-jenkins-eb",
             accessKeyVariable: "AWS_ACCESS_KEY_ID",
             secretKeyVariable: "AWS_SECRET_ACCESS_KEY"
     ]]) {
        sh """
            # Get the list of AMI IDs
            AMI_IDS=\$(aws ec2 describe-images \
                --filters \"Name=name,Values=${amiName}\" \"Name=state,Values=available\" \"Name=is-public,Values=false\" \
                --query \"Images[*].{ID:ImageId}\" \
                --region=${awsRegion} \
                --output text)
        
            # Deregister each AMI and remove the associated snapshots
            for AMI_ID in \$AMI_IDS; do
                echo \"Deregistering AMI ID \$AMI_ID\"
                SNAPSHOT_IDS=\$(aws ec2 describe-images \
                    --image-ids \$AMI_ID \\
                    --filters \"Name=is-public,Values=false\" \
                    --query \"Images[*].BlockDeviceMappings[*].Ebs.SnapshotId\" \
                    --region=${awsRegion} \\
                    --output text)
        
                aws ec2 deregister-image \
                    --image-id \$AMI_ID \
                    --region=${awsRegion};
        
                for SNAPSHOT_ID in \$SNAPSHOT_IDS; do
                    echo \"Deleting snapshot ID \$SNAPSHOT_ID\"
                    aws ec2 delete-snapshot \
                        --snapshot-id \$SNAPSHOT_ID \
                        --region=${awsRegion};
                done
            done
        """
    }
}

def createNewAmiAndAutoScalingUpdate(String appName, String awsRegion, String envName, String versionLabel, String amiName){
    withCredentials([[
             $class: "AmazonWebServicesCredentialsBinding",
             credentialsId: "aws-jenkins-eb",
             accessKeyVariable: "AWS_ACCESS_KEY_ID",
             secretKeyVariable: "AWS_SECRET_ACCESS_KEY"
     ]]) {
        sh """
            EC2_ID=\$(aws elasticbeanstalk describe-environment-resources \
                        --environment-name ${envName} \
                        --query \"EnvironmentResources.Instances[*].Id\" \
                        --region=${awsRegion} \
                        --output text)

            NEW_AMI_ID=\$(aws ec2 create-image \
                --instance-id \$EC2_ID \
                --name ${amiName} \
                --region=${awsRegion} \
                --no-reboot \
                --output text)
                
            echo "NEW_AMI_ID: \$NEW_AMI_ID"
            
            aws elasticbeanstalk update-environment \
                --environment-name ${envName} \
                --option-settings Namespace=aws:autoscaling:launchconfiguration,OptionName=ImageId,Value=\$NEW_AMI_ID \
                --region ${awsRegion} \
                --version-label ${versionLabel} 
           """

        waitForEnvironment("${appName}","${envName}", 900)
    }
}

def waitForEnvironment(applicationName, environmentName, timeoutSeconds) {
    def sleepTimeSeconds = 10
    def maxIterationsCount = timeoutSeconds / sleepTimeSeconds
    def iterations = 0
    println "Waiting for a maximum of ${timeoutSeconds} seconds for ${environmentName} to become ready"
    def status = getStatus(applicationName, environmentName)
    while (status != "Ready" && iterations < maxIterationsCount) {
        println status
        sleep(sleepTimeSeconds * 1000)
        status = getStatus(applicationName, environmentName)
        iterations++
    }
    if (status == "Ready") {
        println "Environment update completed successfully."
    } else {
        println "Environment update did not complete within the timeout period."
    }
}

def getStatus(applicationName, environmentName) {
    def cmd = "aws elasticbeanstalk describe-environments --application-name ${applicationName} --environment-name ${environmentName}"
    def process = cmd.execute()
    def output = process.text
    process.waitFor()
    def status = output.readLines().collect { it as Map }.findResult { it.Environments?.first()?.Status }
    return status
}