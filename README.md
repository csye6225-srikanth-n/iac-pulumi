# iac-pulumi
Repository for Infrastructure as code created as part of Cloud Computing Class 

#Install and Set up AWS cli using the below commands

aws configure --profile dev
aws configure --profile demo

#Install Pulumi by following the official installation instructions for your platform: https://www.pulumi.com/docs/get-started/install/

To run the project using the following command 

pulummi up

To change stack : pulumi select stack dev/demo 

To create a new stack :  pulumi stack init example

Run the below command in the AWS CLI to create a certificate for the domain name
aws iam upload-server-certificate --server-certificate-name srikanthnandikonda.me --certificate-body file://certificate.crt --private-key file://private.key --certificate-chain file://ca_bundle.crt





