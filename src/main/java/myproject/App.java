package myproject;

import com.pulumi.Pulumi;
import com.pulumi.asset.FileArchive;
import com.pulumi.aws.AwsFunctions;
import com.pulumi.aws.autoscaling.inputs.GroupLaunchTemplateArgs;
import com.pulumi.aws.autoscaling.inputs.GroupTagArgs;
import com.pulumi.aws.cloudwatch.LogGroup;
import com.pulumi.aws.cloudwatch.LogGroupArgs;
import com.pulumi.aws.cloudwatch.MetricAlarmArgs;
import com.pulumi.aws.dynamodb.Table;
import com.pulumi.aws.dynamodb.TableArgs;
import com.pulumi.aws.dynamodb.inputs.TableAttributeArgs;
import com.pulumi.aws.lambda.*;
import com.pulumi.aws.lambda.inputs.FunctionEnvironmentArgs;
import com.pulumi.aws.lb.*;

import com.pulumi.aws.autoscaling.GroupArgs;
import com.pulumi.aws.autoscaling.PolicyArgs;
import com.pulumi.aws.ec2.*;
import com.pulumi.aws.ec2.inputs.*;
import com.pulumi.aws.ec2.outputs.GetAmiResult;
import com.pulumi.aws.iam.*;
import com.pulumi.aws.iam.inputs.GetPolicyArgs;
import com.pulumi.aws.iam.inputs.GetPolicyDocumentArgs;
import com.pulumi.aws.iam.inputs.GetPolicyDocumentStatementArgs;
import com.pulumi.aws.iam.inputs.GetPolicyDocumentStatementPrincipalArgs;
import com.pulumi.aws.iam.outputs.GetPolicyDocumentResult;
import com.pulumi.aws.iam.outputs.GetPolicyResult;
import com.pulumi.aws.inputs.GetAvailabilityZonesArgs;
import com.pulumi.aws.lb.inputs.ListenerDefaultActionArgs;
import com.pulumi.aws.lb.inputs.TargetGroupHealthCheckArgs;
//import com.pulumi.aws.lb.inputs.TargetGroupTargetHealthStateArgs;
import com.pulumi.aws.outputs.GetAvailabilityZonesResult;
import com.pulumi.aws.rds.ParameterGroup;
import com.pulumi.aws.rds.ParameterGroupArgs;
import com.pulumi.aws.rds.SubnetGroup;
import com.pulumi.aws.rds.SubnetGroupArgs;
import com.pulumi.aws.route53.Record;
import com.pulumi.aws.route53.RecordArgs;
import com.pulumi.aws.route53.Route53Functions;
import com.pulumi.aws.route53.inputs.GetZoneArgs;
import com.pulumi.aws.route53.inputs.RecordAliasArgs;
import com.pulumi.aws.route53.outputs.GetZoneResult;
import com.pulumi.aws.sns.Topic;
import com.pulumi.aws.sns.TopicSubscription;
import com.pulumi.aws.sns.TopicSubscriptionArgs;
import com.pulumi.core.Output;
import com.pulumi.gcp.serviceaccount.*;
import com.pulumi.gcp.storage.Bucket;
import com.pulumi.gcp.storage.BucketArgs;
import com.pulumi.gcp.storage.inputs.*;
import com.pulumi.resources.CustomResourceOptions;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.pulumi.aws.cloudwatch.MetricAlarm;
import com.pulumi.resources.Resource;

import javax.naming.Binding;
import java.util.List;
import java.util.stream.Collectors;


public class App {

    public static void main(String[] args) {

        Properties properties = new Properties();
        try (FileInputStream fis = new FileInputStream(".env")) {
            properties.load(fis);
        } catch (IOException e) {
            e.printStackTrace();
        }


        Pulumi.run(ctx -> {
            var config = ctx.config();
            Map<String,Object> data = config.requireObject("data", Map.class);

            var v = data.get("name");
            if(null == v || v.toString().isEmpty()){
                v = UUID.randomUUID().toString().replace("-", "");
            }
            String vpcName = v.toString();
            var cidrBlock = data.get("cidr-block");
            if(null == cidrBlock || cidrBlock.toString().isEmpty()){
                cidrBlock = "10.0.0.0/16";
            }
            String cidr = cidrBlock.toString();
            var vpc = new Vpc(vpcName,
                    VpcArgs.builder()
                            .cidrBlock(cidr)
                            .enableDnsHostnames(true)
                            .instanceTenancy("default")
                            .tags(Map.of("Name",vpcName))
                            .build());
            var n = data.get("num_of_subnets");
            int num;
            if(null == n || Double.parseDouble(n.toString()) <= 0 ){
                num = 3;
            }else{
                num = (int) Double.parseDouble(n.toString());
            }
            var publicCidr = data.get("public-cidr");
            if(null == publicCidr || publicCidr.toString().isEmpty()){
                publicCidr = "0.0.0.0/0";
            }

            Output<GetAvailabilityZonesResult> availabilityZones = AwsFunctions.getAvailabilityZones(GetAvailabilityZonesArgs.builder().state("available").build());


            Object finalPublicCidr = publicCidr;
            availabilityZones.applyValue(availabilityZonesResult -> {
                int noOfZones = Math.min(availabilityZonesResult.names().size(),num);
                List<String> strings = calculateSubnets(cidr,noOfZones*2);
                List<Subnet> publicSubNetList = createPublicSubNets(noOfZones,vpcName,vpc,availabilityZonesResult.names(),strings);
                List<Subnet> privateSubNetList =createPrivateSubnets(noOfZones,vpcName,vpc,availabilityZonesResult.names(),strings);

                var igw = new InternetGateway("my-igw",
                        InternetGatewayArgs.builder()
                                .vpcId(vpc.id())
                                .tags(Map.of("Name", vpcName+ "_igw"))
                                .build());
                var routeTable = new RouteTable(vpcName + "_public_route_table",
                        RouteTableArgs.builder()
                                .tags(Map.of("Name", vpcName+ "_public_route_table"))
                                .vpcId(vpc.id())
                                .routes(RouteTableRouteArgs.builder().cidrBlock(finalPublicCidr.toString()).gatewayId(igw.id()).build())
                                .build()
                );
                var routeTable2 = new RouteTable(vpcName + "_private_route_table",
                        RouteTableArgs.builder()
                                .tags(Map.of("Name", vpcName+ "_private_route_table"))
                                .vpcId(vpc.id())
                                .build()
                );
                for(int i =0 ; i < noOfZones; i++){
                    new RouteTableAssociation("pu_route_table_association_" + i,
                            RouteTableAssociationArgs.builder()
                                    .subnetId(publicSubNetList.get(i).id())
                                    .routeTableId(routeTable.id())
                                    .build());

                    new RouteTableAssociation("pr_route_table_association_" + i,
                            RouteTableAssociationArgs.builder()
                                    .subnetId(privateSubNetList.get(i).id())
                                    .routeTableId(routeTable2.id())
                                    .build());
                }
                createLoadBalancerSecurityGroup(vpc, data);
                createAppSecurityGroup(vpc, data);
                createDbSecurityGroup(vpc, data);
                com.pulumi.aws.rds.Instance dbInstance = createDBInstance(data,publicSubNetList);
                Topic snsTopic = createSNSTopic(data);
                createLaunchTemplate(data, dbInstance, snsTopic);
                createTargetGroup(vpc,data);
                createApplicationLoadBalancer(vpc,data,publicSubNetList);
                createAutoScalingGroup(vpc,data,publicSubNetList);
//                Instance instance =createEc2(publicSubNetList.get(0),data,dbInstance);
                createARecord(data);
                Bucket assignemntStorage = createGCPResources(data);
                createLambdaFunction(data,assignemntStorage,properties);
                createDynamoDB(data);
//                ctx.log().info(data.get("gcp_service_account_key").toString());
//                ctx.export("instance-id", instance.id());
                return  null;
            });
            ctx.export("vpc-id", vpc.id());
        });
    }



    public static List<Subnet> createPublicSubNets(int num,String vpcName,Vpc vpc,List<String> list,List<String> subnetStrings){
        List<Subnet> publicSubNetList = new ArrayList<>();
        for (int i = 0; i <num ; i++) {
            String subnetName = vpcName + "_public_" +i;
            var publicSubnet = new Subnet(subnetName,
                    SubnetArgs.builder()
                            .cidrBlock(subnetStrings.get(i))
                            .vpcId(vpc.id())
                            .availabilityZone(list.get(i))
                            .mapPublicIpOnLaunch(true)
                            .tags(Map.of("Name",subnetName))
                            .build());
            publicSubNetList.add(publicSubnet);
        }
        return publicSubNetList;
    }

    public static List<Subnet> createPrivateSubnets(int num,String vpcName,Vpc vpc,List<String> list,List<String> subnetString){
        List<Subnet> privateSubnetList = new ArrayList<>();

        for (int i = 0; i < num; i++) {
            String subnetName = vpcName + "_private_" +i;
            var publicSubnet = new Subnet(subnetName,
                    SubnetArgs.builder()
                            .cidrBlock(subnetString.get(i+num))
                            .vpcId(vpc.id())
                            .availabilityZone(list.get(i))
                            .tags(Map.of("Name",subnetName))
                            .build());
            privateSubnetList.add(publicSubnet);
        }
        return privateSubnetList;
    }
    public static List<String> calculateSubnets(String vpcCidr, int numSubnets) {
        try {
            InetAddress vpcAddress = Inet4Address.getByName(vpcCidr.split("/")[0]);
            int vpcCidrPrefixLength = Integer.parseInt(vpcCidr.split("/")[1]);
            double ceil = Math.ceil(Math.log(numSubnets) / Math.log(2));
            int subnetPrefixLength = vpcCidrPrefixLength + (int) ceil;
            int availableAddresses = 32 - subnetPrefixLength;

            List<String> subnets = new ArrayList<>();
            for (int i = 0; i < numSubnets; i++) {
                int subnetSize = (int) Math.pow(2, availableAddresses);
                String subnetCidr = vpcAddress.getHostAddress() + "/" + subnetPrefixLength;
                subnets.add(subnetCidr);
                vpcAddress = InetAddress.getByName(advanceIPAddress(vpcAddress.getHostAddress(), subnetSize));
            }

            return subnets;
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static String advanceIPAddress(String ipAddress, int offset) throws UnknownHostException {
        InetAddress addr = Inet4Address.getByName(ipAddress);
        byte[] bytes = addr.getAddress();
        int value = 0;
        for (byte b : bytes) {
            value = (value << 8) | (b & 0xff);
        }
        value += offset;

        for (int i = 0; i < 4; i++) {
            bytes[i] = (byte) ((value >> (24 - i * 8)) & 0xff);
        }
        return InetAddress.getByAddress(bytes).getHostAddress();
    }

    public static void createAppSecurityGroup(Vpc vpc, Map<String,Object> data){
        String security_group = data.get("name") + "_application_security_group";
        String publicIpString = data.get("public-cidr").toString();
        Double appPort = (Double) data.get("app_port");
        Output<String> loadBalanceSG = (Output<String>) data.get("load_balancer_sg");
        List<SecurityGroupIngressArgs> securityGroupIngressArgsList = new ArrayList<>();

        securityGroupIngressArgsList.add(SecurityGroupIngressArgs.builder()
                .fromPort(22)
                .toPort(22)
                .protocol("tcp")
                .cidrBlocks(publicIpString).build());
        securityGroupIngressArgsList.add(SecurityGroupIngressArgs.builder()
                .fromPort(appPort.intValue())
                .toPort(appPort.intValue())
                .protocol("tcp")
                .securityGroups(loadBalanceSG.applyValue(Collections::singletonList)).build());
        List<SecurityGroupEgressArgs> securityGroupEgressArgsList = new ArrayList<>();
        securityGroupEgressArgsList.add(SecurityGroupEgressArgs.builder()
                        .description("Allow TCP connections")
                        .fromPort(443)
                        .toPort(443)
                        .protocol("tcp")
                        .cidrBlocks(publicIpString)
                        .build());
        var allowTcp = new SecurityGroup(security_group,
                SecurityGroupArgs.builder()
                        .description("Allow connections from load balancer and ssh")
                        .vpcId(vpc.id()).ingress(securityGroupIngressArgsList)
                        .egress(securityGroupEgressArgsList)
                        .tags(Map.of("Name",security_group))
                        .build());
        data.put("ec2_sg",allowTcp.id());
    }

    public static void createDbSecurityGroup(Vpc vpc, Map<String,Object> data){
        String security_group = data.get("name") + "_database_sg";
        Double dbPort = (Double) data.get("db_port");
        Output<String> securityGroupId = (Output<String>) data.get("ec2_sg");
        List<SecurityGroupIngressArgs> securityGroupIngressArgsList = new ArrayList<>();
        securityGroupIngressArgsList.add(SecurityGroupIngressArgs.builder()
                .fromPort(dbPort.intValue())
                .toPort(dbPort.intValue())
                .protocol("tcp")
                .cidrBlocks("0.0.0.0/0").build());
//                .securityGroups(securityGroupId.applyValue(Collections::singletonList)).build());


        var allowTcp = new SecurityGroup(security_group,
                SecurityGroupArgs.builder()
                        .description("Allow TCP connections")
                        .vpcId(vpc.id()).ingress(securityGroupIngressArgsList)
                        .tags(Map.of("Name",security_group))
                        .build());
        var outBound = new SecurityGroupRule("ec2-rds-outbound",
                SecurityGroupRuleArgs.builder()
                        .description("Allow TCP connections")
                        .type("egress")
                        .fromPort(dbPort.intValue())
                        .toPort(dbPort.intValue())
                        .protocol("tcp")
                        .sourceSecurityGroupId(allowTcp.id())
                        .securityGroupId(securityGroupId)
                        .build());
        data.put("database_sg",allowTcp.id());
    }

    private static void createLoadBalancerSecurityGroup(Vpc vpc, Map<String, Object> data) {
        String load_security_group = data.get("name") + "_load_balancer_security_group";
        List<Double> ports = (List<Double>) data.get("load_balancer_ports");
        String publicIpString = data.get("public-cidr").toString();
        List<SecurityGroupIngressArgs> securityGroupIngressArgsList = new ArrayList<>();
        for(Double port : ports){
            securityGroupIngressArgsList.add(SecurityGroupIngressArgs.builder()
                    .fromPort(port.intValue())
                    .toPort(port.intValue())
                    .protocol("tcp")
                    .cidrBlocks(publicIpString).build());
        }
        List<SecurityGroupEgressArgs> securityGroupEgressArgsList = new ArrayList<>();
        securityGroupEgressArgsList.add(SecurityGroupEgressArgs.builder()
                .description("Allow TCP connections")
                .fromPort(0)
                .toPort(0)
                .protocol("-1")
                .cidrBlocks(publicIpString)
                .build());
        var loadBalancerSG =new SecurityGroup(load_security_group,
                SecurityGroupArgs.builder()
                        .description("Allow TCP connections")
                        .vpcId(vpc.id()).ingress(securityGroupIngressArgsList)
                        .egress(securityGroupEgressArgsList)
                        .tags(Map.of("Name",load_security_group))
                        .build());
        data.put("load_balancer_sg",loadBalancerSG.id());
    }

    public static Instance createEc2(Subnet subnet, Map<String,Object> data, com.pulumi.aws.rds.Instance dbInstance){
        String instanceName = data.get("name") + "_instance";
        Double size = (Double) data.get("volume");
        Output<String> securityGroupId = (Output<String>) data.get("ec2_sg");
        final Output<GetPolicyResult> cloudWatchAgentServerPolicy = IamFunctions.getPolicy(GetPolicyArgs.builder()
                        .name("CloudWatchAgentServerPolicy")
                .build());
        final var amazonSSMManagedInstanceCore = IamFunctions.getPolicy(GetPolicyArgs.builder()
                .name("AmazonSSMManagedInstanceCore")
                .build());
        final var amazonEC2RoleforSSM = IamFunctions.getPolicy(GetPolicyArgs.builder()
                .name("AmazonEC2RoleforSSM")
                .build());

        final var assumeRole = IamFunctions.getPolicyDocument(GetPolicyDocumentArgs.builder()
                .statements(GetPolicyDocumentStatementArgs.builder()
                        .effect("Allow")
                        .principals(GetPolicyDocumentStatementPrincipalArgs.builder()
                                .type("Service")
                                .identifiers("ec2.amazonaws.com")
                                .build())
                        .actions("sts:AssumeRole")
                        .build())
                .build());
        var role = new Role("cloudWatchRole-1", RoleArgs.builder()
                .assumeRolePolicy(assumeRole.applyValue(GetPolicyDocumentResult::json))
                .build());
        RolePolicyAttachment rolePolicyAttachment = new RolePolicyAttachment("cloudWatchRolePolicyAttachment-1",
                RolePolicyAttachmentArgs.builder()
                        .role(role.name())
                        .policyArn(cloudWatchAgentServerPolicy.applyValue(GetPolicyResult::arn))
                        .build());
        RolePolicyAttachment rolePolicyAttachment2 = new RolePolicyAttachment("cloudWatchRolePolicyAttachment2-2",
                RolePolicyAttachmentArgs.builder()
                        .role(role.name())
                        .policyArn(amazonSSMManagedInstanceCore.applyValue(GetPolicyResult::arn))
                        .build());
        RolePolicyAttachment rolePolicyAttachment3 = new RolePolicyAttachment("cloudWatchRolePolicyAttachment3-4",
                RolePolicyAttachmentArgs.builder()
                        .role(role.name())
                        .policyArn(amazonEC2RoleforSSM.applyValue(GetPolicyResult::arn))
                        .build());
        var instanceProfile = new InstanceProfile("instanceProfile-1", InstanceProfileArgs.builder()
                .role(role.id())
                .build());
        InstanceEbsBlockDeviceArgs ebsBlockDevice = InstanceEbsBlockDeviceArgs.builder()
                .deviceName("/dev/xvda")
                .volumeType("gp2")
                .volumeSize(size.intValue())
                .deleteOnTermination(true)
                .build();
        String ami_id = (String) data.get("ami_id");
        List<InstanceEbsBlockDeviceArgs> ebsBlockDeviceArgsList = new ArrayList<>();
        ebsBlockDeviceArgsList.add(ebsBlockDevice);
        InstanceArgs.Builder instanceArgs = InstanceArgs.builder();
        if(ami_id.isEmpty()){
            String ami_name = (String) data.get("ami_name");
            final var customAmi = Ec2Functions.getAmi(GetAmiArgs.builder()
                    .mostRecent(true)
                    .owners(data.get("owner_id").toString())
                    .filters(GetAmiFilterArgs.builder()
                            .name("name")
                            .values(ami_name)
                            .build())
                    .build());
            instanceArgs.ami(customAmi.applyValue(GetAmiResult::id));
        }else{
            instanceArgs.ami(ami_id);
        }
        String dbName = (String) data.get("db_name");
        String userName = (String) data.get("user_name");
        Output<String> userData = dbInstance.address().applyValue(v -> String.format("#!/bin/bash\n" +
                        "echo \"export DB_HOST=%s\" >> /opt/csye6225/application.properties\n" +
                        "echo \"export DB_USER=%s\" >> /opt/csye6225/application.properties\n" +
                        "echo \"export DB_NAME=%s\" >> /opt/csye6225/application.properties\n" +
                        "echo \"export DB_PASS=%s\" >> /opt/csye6225/application.properties\n" +
                        "echo \"export DB_PORT=%d\" >> /opt/csye6225/application.properties\n" +
                        "echo \"export FILE_PATH=%s\" >> /opt/csye6225/application.properties\n" +
                        "echo \"export LOG_FILE_PATH=%s\" >> /opt/csye6225/application.properties\n" +
                        "echo \"export YOUR_DOMAIN_NAME=%s\" >> /opt/csye6225/application.properties\n" +
                        "echo \"export API_KEY=%s\" >> /opt/csye6225/application.properties\n" +
                        "echo \"export SG_API_KEY=%s\" >> /opt/csye6225/application.properties\n" +
                        "echo \"export TEMPLATE_ID=%s\" >> /opt/csye6225/application.properties\n" +

                        "sudo /opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl -a fetch-config -m ec2 -c file:/etc/cloudwatch/cloudwatch-config.json -s \n" +
                        "sudo systemctl restart amazon-cloudwatch-agent.service\n" ,
                v,
                userName,
                dbName,
                userName,
                ((Double) data.get("db_port")).intValue(),
                data.get("file_path"),
                data.get("log_file_path"),
                data.get("domain_name"),
                data.get("api_key"),data.get("sg_api_key"),data.get("template_id")));
        return new Instance(instanceName, instanceArgs
                .instanceType(data.get("instance_type").toString())
                .ebsBlockDevices(ebsBlockDeviceArgsList)
                .subnetId(subnet.id())
                .keyName(data.get("key_name").toString())
                .associatePublicIpAddress(true)
                .vpcSecurityGroupIds(securityGroupId.applyValue(Collections::singletonList))
                .disableApiTermination(false)
                .tags(Map.of("Name",instanceName))
                .userData(userData)
                .iamInstanceProfile(instanceProfile.id())
                .build(), CustomResourceOptions.builder()
                .dependsOn(Collections.singletonList(dbInstance)).build());
    }


    public static com.pulumi.aws.rds.Instance createDBInstance(Map<String, Object> data, List<Subnet> subnetList){
        String name = data.get("name") + "-csye6225";
        String dbName = (String) data.get("db_name");
        String userName = (String) data.get("user_name");
        Double storageSpace = (Double) data.get("storage_space");
        Output<String> ec2SecurityGroup = (Output<String>) data.get("database_sg");
        List<Output<String>> subnetIds = new ArrayList<>();
        for (Subnet subnet : subnetList) {
            subnetIds.add(subnet.id());
        }
        String rdsDBFamily = (String) data.get("rdsDBFamily");

        ParameterGroup rdsDBParameterGroup = new ParameterGroup("rdsgroup", ParameterGroupArgs.builder()
                .family(rdsDBFamily)
                .tags(Map.of("Name","rdsgroup"))
                .build());

        // Create a stack output from the list of subnet IDs
        Output<List<String>> subnetIdsOutput = Output.all(subnetIds).applyValue(ids -> ids);
        SubnetGroup subnetGroup = new SubnetGroup(name, SubnetGroupArgs.builder()
                                                .subnetIds(subnetIdsOutput)
                                                .build());

        return new com.pulumi.aws.rds.Instance(name,com.pulumi.aws.rds.InstanceArgs
                .builder()
                .instanceClass("db.t3.micro")
                .allowMajorVersionUpgrade(true)
                .dbName(dbName)
                .engine("mariadb")
                .storageType("gp2")
                .tags(Map.of("Name",name))
                .autoMinorVersionUpgrade(false)
                .skipFinalSnapshot(true)
                .dbSubnetGroupName(subnetGroup.name())
                .allocatedStorage(storageSpace.intValue())
                .multiAz(false)
                .engineVersion("10.11.5")
                .parameterGroupName(rdsDBParameterGroup.name())
                .username(userName)
                .password(userName)
                .multiAz(false)
                .vpcSecurityGroupIds(ec2SecurityGroup.applyValue(Collections::singletonList))
                .tags(Map.of("Name",name))
                .build());
    }

    private static void createLaunchTemplate(Map<String, Object> data, com.pulumi.aws.rds.Instance dbInstance, Topic snsTopic) {
        String launch_template = data.get("name") + "_launch_template";
        String key_name = (String) data.get("key_name");
        Double size = (Double) data.get("volume");

        String ami_id = (String) data.get("ami_id");
        String instanceType = (String) data.get("instance_type");
        LaunchTemplateArgs.Builder builder = LaunchTemplateArgs.builder();
        final Output<GetAmiResult> customAmi;
        if(ami_id.isEmpty()){
            String ami_name = (String) data.get("ami_name");
            customAmi = Ec2Functions.getAmi(GetAmiArgs.builder()
                    .mostRecent(true)
                    .owners(data.get("owner_id").toString())
                    .filters(GetAmiFilterArgs.builder()
                            .name("name")
                            .values(ami_name)
                            .build())
                    .build());
            builder.imageId(customAmi.applyValue(GetAmiResult::id));
        }else{
            builder.imageId(ami_id);
        }
        Output<String> securityGroupId = (Output<String>) data.get("ec2_sg");
        String dbName = (String) data.get("db_name");
        String userName = (String) data.get("user_name");
        Output<String> srn = (Output<String>) data.get("sns_topic_arn");
        Output<String> dbData = dbInstance.address();

        Output<String> userData = Output.all(srn,dbData).applyValue(v -> String.format("#!/bin/bash\n" +
                        "echo \"export DB_HOST=%s\" >> /opt/csye6225/application.properties\n" +
                        "echo \"export DB_USER=%s\" >> /opt/csye6225/application.properties\n" +
                        "echo \"export DB_NAME=%s\" >> /opt/csye6225/application.properties\n" +
                        "echo \"export DB_PASS=%s\" >> /opt/csye6225/application.properties\n" +
                        "echo \"export DB_PORT=%d\" >> /opt/csye6225/application.properties\n" +
                        "echo \"export FILE_PATH=%s\" >> /opt/csye6225/application.properties\n" +
                        "echo \"export LOG_FILE_PATH=%s\" >> /opt/csye6225/application.properties\n" +
                        "echo \"export YOUR_DOMAIN_NAME=%s\" >> /opt/csye6225/application.properties\n" +
                        "echo \"export API_KEY=%s\" >> /opt/csye6225/application.properties\n" +
                        "echo \"export SG_API_KEY=%s\" >> /opt/csye6225/application.properties\n" +
                        "echo \"export TEMPLATE_ID=%s\" >> /opt/csye6225/application.properties\n" +
                        "echo \"export TOPIC_ARN=%s\" >> /opt/csye6225/application.properties\n" +
                        "echo \"export REGION=%s\" >> /opt/csye6225/application.properties\n" +
                        "sudo /opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl -a fetch-config -m ec2 -c file:/etc/cloudwatch/cloudwatch-config.json -s \n" +
                        "sudo systemctl restart amazon-cloudwatch-agent.service\n" ,
                v.get(1),
                userName,
                dbName,
                userName,
                ((Double) data.get("db_port")).intValue(),
                data.get("file_path"),
                data.get("log_file_path"),
                data.get("domain_name"),
                data.get("api_key"),data.get("sg_api_key"),data.get("template_id"),v.get(0),
                data.get("region")));
        Output<String> encodedUserData = userData.applyValue(s -> Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)));
        builder.keyName(key_name);
        builder.instanceType(instanceType);
        builder.userData(encodedUserData);
        builder.vpcSecurityGroupIds(securityGroupId.applyValue(Collections::singletonList));
        final Output<GetPolicyResult> cloudWatchAgentServerPolicy = IamFunctions.getPolicy(GetPolicyArgs.builder()
                        .name("CloudWatchAgentServerPolicy")
                .build());
        final var amazonSSMManagedInstanceCore = IamFunctions.getPolicy(GetPolicyArgs.builder()
                .name("AmazonSSMManagedInstanceCore")
                .build());
        final var amazonEC2RoleforSSM = IamFunctions.getPolicy(GetPolicyArgs.builder()
                .name("AmazonEC2RoleforSSM")
                .build());
        final var amazonEC2RoleforSSN = IamFunctions.getPolicy(GetPolicyArgs.builder()
                .name("AmazonSNSFullAccess")
                .build());
        final var assumeRole = IamFunctions.getPolicyDocument(GetPolicyDocumentArgs.builder()
                .statements(GetPolicyDocumentStatementArgs.builder()
                        .effect("Allow")
                        .principals(GetPolicyDocumentStatementPrincipalArgs.builder()
                                .type("Service")
                                .identifiers("ec2.amazonaws.com")
                                .build())
                        .actions("sts:AssumeRole")
                        .build())
                .build());
        var role = new Role("cloudWatchRole", RoleArgs.builder()
                .assumeRolePolicy(assumeRole.applyValue(GetPolicyDocumentResult::json))
                .tags(Map.of("Name","cloudWatchRole"))
                .build());
        RolePolicyAttachment rolePolicyAttachment = new RolePolicyAttachment("cloudWatchRolePolicyAttachment",
                RolePolicyAttachmentArgs.builder()
                        .role(role.name())
                        .policyArn(cloudWatchAgentServerPolicy.applyValue(GetPolicyResult::arn))
                        .build());
        RolePolicyAttachment rolePolicyAttachment2 = new RolePolicyAttachment("cloudWatchRolePolicyAttachment2",
                RolePolicyAttachmentArgs.builder()
                        .role(role.name())
                        .policyArn(amazonSSMManagedInstanceCore.applyValue(GetPolicyResult::arn))
                        .build());
        RolePolicyAttachment rolePolicyAttachment3 = new RolePolicyAttachment("cloudWatchRolePolicyAttachment3",
                RolePolicyAttachmentArgs.builder()
                        .role(role.name())
                        .policyArn(amazonEC2RoleforSSM.applyValue(GetPolicyResult::arn))
                        .build());
        RolePolicyAttachment rolePolicyAttachment4 = new RolePolicyAttachment("snsRolePolicyAttachment",
                RolePolicyAttachmentArgs.builder()
                        .role(role.name())
                        .policyArn(amazonEC2RoleforSSN.applyValue(GetPolicyResult::arn))
                        .build());
        var instanceProfile = new InstanceProfile("instanceProfile", InstanceProfileArgs.builder()
                .role(role.id())
                .build());

        builder.blockDeviceMappings(LaunchTemplateBlockDeviceMappingArgs.builder()
                .deviceName("/dev/xvda")
                .ebs(LaunchTemplateBlockDeviceMappingEbsArgs.builder()
                        .volumeSize(size.intValue())
                        .volumeType("gp2")
                        .deleteOnTermination(String.valueOf(true))
                        .build())
                .build());
        builder.iamInstanceProfile(LaunchTemplateIamInstanceProfileArgs.builder()
                .arn(instanceProfile.arn())
                .build());
        builder.disableApiTermination(false);
        builder.tagSpecifications(LaunchTemplateTagSpecificationArgs.builder()
                .resourceType("instance")
                .tags(Map.of("Name",launch_template))
                .build());
        LaunchTemplate launchTemplate = new LaunchTemplate(launch_template, builder.build(),CustomResourceOptions.builder()
                .dependsOn(Collections.singletonList(snsTopic)).build());
        data.put("launch_template",launchTemplate.id());
    }


    public static void createARecord(Map<String, Object> data){
        String zoneName = data.get("zone_name").toString();
        final var selected = Route53Functions.getZone(GetZoneArgs
                                    .builder()
                                    .name(zoneName)
                                    .privateZone(false)
                                    .build());
        Output<String> zoneId = (Output<String>) data.get("load_balancer_zone_id");
        Output<String> loadBalancerDns = (Output<String>) data.get("load_balancer_dns");
        var aliasLoadBalancer = new Record("alias-loadbalancer", RecordArgs.builder()
                .zoneId(selected.applyValue(GetZoneResult::zoneId))
                .name(zoneName)
                .type("A")
                .aliases(RecordAliasArgs.builder()
                        .name(loadBalancerDns)
                        .zoneId(zoneId)
                        .evaluateTargetHealth(true)
                        .build())
                .build());
    }

    public static void createTargetGroup(Vpc vpc, Map<String,Object> data){
        String target_group_name = (String) data.get("target_group_name");

        try{
//            List<TargetGroupTargetHealthStateArgs> targetHealthStates = new ArrayList<>();
//            targetHealthStates.add(TargetGroupTargetHealthStateArgs.builder()
//                    .enableUnhealthyConnectionTermination(Output.of(false))
//                    .build());

            var tcp_example = new TargetGroup(target_group_name, TargetGroupArgs.builder()
                    .port(8080)
                    .protocol("HTTP")
                    .vpcId(vpc.id())
                    .healthCheck(TargetGroupHealthCheckArgs.builder()
                            .enabled(true)
                            .healthyThreshold(3)
                            .interval(30)
                            .matcher("200")
                            .path("/healthz")
                            .port("8080")
                            .protocol("HTTP")
                            .timeout(5)
                            .unhealthyThreshold(3)
                            .build())
                    .build());
            data.put("target_group_arn",tcp_example.arn());
        }catch (Exception e){
            System.out.println("Created Target Group");
        }
//        final var test = LbFunctions.getTargetGroup(GetTargetGroupArgs.builder()
//                .arn(data.get("target_group_arn").toString())
//                .name(target_group_name)
//                .build());
    }

    public static void createApplicationLoadBalancer(Vpc vpc, Map<String,Object> data, List<Subnet> publicSubNetList){
        List<Output<String>> subnetIds = new ArrayList<>();
        Output<String> securityGroupId = (Output<String>) data.get("load_balancer_sg");
        for (Subnet subnet : publicSubNetList) {
            subnetIds.add(subnet.id());
        }
        Output<String> targetGroupArn = (Output<String>) data.get("target_group_arn");
        Output<List<String>> subnetIdsOutput = Output.all(subnetIds).applyValue(ids -> ids);
        String load_balancer_name = data.get("name") + "-load-balancer";
        var loadBalancer = new LoadBalancer(load_balancer_name, LoadBalancerArgs.builder()
                .internal(false)
                .ipAddressType("ipv4")
                .loadBalancerType("application")
                .tags(Map.of("Name",load_balancer_name))
                .securityGroups(securityGroupId.applyValue(Collections::singletonList))
                .subnets(subnetIdsOutput)
                .build());
        data.put("load_balancer_arn",loadBalancer.arn());
        data.put("load_balancer_dns",loadBalancer.dnsName());
        data.put("load_balancer_zone_id",loadBalancer.zoneId());
        var listener = new Listener("listener", ListenerArgs.builder()
                .loadBalancerArn(loadBalancer.arn())
                .port(80)
                .protocol("HTTP")
                .defaultActions(Collections.singletonList(ListenerDefaultActionArgs.builder()
                        .type("forward")
                        .targetGroupArn(targetGroupArn)
                        .build()))
                .build());

    }


    public static void createAutoScalingGroup(Vpc vpc, Map<String,Object> data, List<Subnet> publicSubNetList){
        String name = data.get("name") + "-auto-scaling-group";
        List<Output<String>> subnetIds = new ArrayList<>();
        Output<String> securityGroupId = (Output<String>) data.get("load_balancer_sg");
        for (Subnet subnet : publicSubNetList) {
            subnetIds.add(subnet.id());
        }
        Output<String> targetGroupArn = (Output<String>) data.get("target_group_arn");
        Output<List<String>> subnetIdsOutput = Output.all(subnetIds).applyValue(ids -> ids);
        Output<String> launchTemplate = (Output<String>) data.get("launch_template");
        Output<String> loadBalancerArn = (Output<String>) data.get("load_balancer_arn");
        var autoScalingGroup = new com.pulumi.aws.autoscaling.Group(name, GroupArgs.builder()
                .maxSize(3)
                .minSize(1)
                .healthCheckGracePeriod(300)
                .healthCheckType("ELB")
                .forceDelete(false)
                .terminationPolicies(Collections.singletonList("OldestInstance"))
                .vpcZoneIdentifiers(subnetIdsOutput)
                .metricsGranularity("1Minute")
                .tags(GroupTagArgs.builder().propagateAtLaunch(true).key("Name").value(name).build(),GroupTagArgs.builder().propagateAtLaunch(true).key("Project").value("csye6225").build())
                .targetGroupArns(targetGroupArn.applyValue(Collections::singletonList))
                .launchTemplate(GroupLaunchTemplateArgs.builder().id(launchTemplate).build())
                .build());

        var scalueUp = new com.pulumi.aws.autoscaling.Policy("scale-up-policy", PolicyArgs.builder()
                .scalingAdjustment(1)
                .adjustmentType("ChangeInCapacity")
                .policyType("SimpleScaling")
                .cooldown(300)
                .autoscalingGroupName(autoScalingGroup.name())
                .build());
        var scalueDown = new com.pulumi.aws.autoscaling.Policy("scale-down-policy", PolicyArgs.builder()
                .scalingAdjustment(-1)
                .adjustmentType("ChangeInCapacity")
                .policyType("SimpleScaling")
                .cooldown(300)
                .autoscalingGroupName(autoScalingGroup.name())
                .build());
        var scaleUpAlarm = new MetricAlarm("scale-up-alarm",
                MetricAlarmArgs.builder()
                        .comparisonOperator("GreaterThanOrEqualToThreshold")
                        .evaluationPeriods(2)
                        .metricName("CPUUtilization")
                        .namespace("AWS/EC2")
                        .period(60)
                        .statistic("Average").threshold(5.0)
                        .alarmDescription("Alarm if server CPU too high")
                        .alarmActions(scalueUp.arn().applyValue(Collections::singletonList))
                        .dimensions(autoScalingGroup.name().applyValue(s -> Map.of("AutoScalingGroupName", s)))
                        .build()
        );
        var scaleDownAlarm = new MetricAlarm("scale-down-alarm",
                MetricAlarmArgs.builder()
                        .comparisonOperator("LessThanOrEqualToThreshold")
                        .evaluationPeriods(2)
                        .metricName("CPUUtilization")
                        .namespace("AWS/EC2")
                        .period(60)
                        .statistic("Average").threshold(3.0)
                        .alarmDescription("Alarm if server CPU too low")
                        .alarmActions(scalueDown.arn().applyValue(Collections::singletonList))
                        .dimensions(autoScalingGroup.name().applyValue(s -> Map.of("AutoScalingGroupName", s)))
                        .build());
    }

    public static Topic  createSNSTopic(Map<String, Object> data){
        String topicName = data.get("name") + "-sns-topic";
        Topic topic = new Topic(topicName, com.pulumi.aws.sns.TopicArgs.builder()
                .displayName(topicName)
                .build());
        data.put("sns_topic_arn",topic.arn());
        return topic;
    }

    public static Bucket createGCPResources(Map<String,Object> data){


        String name = data.get("name").toString();
        var sa = new Account(name + "lambda", AccountArgs.builder()
                .accountId("lambda-account")
                .displayName("A service account that be used by AWS lambda function")
                .build());
        Output<String> serviceAccountEmail = sa.email().applyValue(v -> "serviceAccount:" + v);
        Output<List<String>> serviceAccountEmails = serviceAccountEmail.applyValue(Collections::singletonList);
//        var iamBinding = new IAMBinding("iamBinding", IAMBindingArgs.builder()
//                .role("roles/storage.objectCreator")
//                .members(serviceAccountEmails)
//                .build());

        Key key = new Key("key", KeyArgs.builder()
                .serviceAccountId(sa.name())
                .build());
        data.put("gcp_service_account",sa.email());
        data.put("gcp_service_account_key",key.privateKey());
        Bucket assignmentStorage = new Bucket("assignment-storage", BucketArgs.builder()
                .forceDestroy(true)
                .publicAccessPrevention("enforced")
                .location("US")
                .versioning(BucketVersioningArgs.builder()
                        .enabled(true)
                        .build())
                .lifecycleRules(BucketLifecycleRuleArgs.builder()
                        .action(BucketLifecycleRuleActionArgs.builder()
                                .type("Delete")
                                .build())
                        .condition(BucketLifecycleRuleConditionArgs.builder()
                                .numNewerVersions(4)
                                .build())
                        .build())
                .build());
        data.put("gcp_bucket_name",assignmentStorage.name());
        return assignmentStorage;
    }

    public static void createLambdaFunction(Map<String, Object> map, Bucket assignemntStorage,Properties properties){
        final var assumeRole = IamFunctions.getPolicyDocument(GetPolicyDocumentArgs.builder()
                .statements(GetPolicyDocumentStatementArgs.builder()
                        .effect("Allow")
                        .principals(GetPolicyDocumentStatementPrincipalArgs.builder()
                                .type("Service")
                                .identifiers("lambda.amazonaws.com")
                                .build())
                        .actions("sts:AssumeRole")
                        .build())
                .build());


        var iamForLambda = new Role("iamForLambda", RoleArgs.builder()
                .assumeRolePolicy(assumeRole.applyValue(GetPolicyDocumentResult::json))
                .build());

        final var lambdaLoggingPolicyDocument = IamFunctions.getPolicyDocument(GetPolicyDocumentArgs.builder()
                .statements(GetPolicyDocumentStatementArgs.builder()
                        .effect("Allow")
                        .actions(
                                "logs:CreateLogGroup",
                                "logs:CreateLogStream",
                                "logs:PutLogEvents")
                        .resources("arn:aws:logs:*:*:*")
                        .build())
                .build());

        final var dynamoDBPolicyDocument = IamFunctions.getPolicyDocument(GetPolicyDocumentArgs.builder()
                .statements(GetPolicyDocumentStatementArgs.builder()
                        .effect("Allow")
                        .actions(
                                "dynamodb:PutItem",
                                "dynamodb:GetItem",
                                "dynamodb:DeleteItem",
                                "dynamodb:UpdateItem")
                        .resources("arn:aws:dynamodb:*:*:*")
                        .build())
                .build());

        var lambdaLoggingPolicy = new Policy("lambdaLoggingPolicy", com.pulumi.aws.iam.PolicyArgs.builder()
                .path("/")
                .description("IAM policy for logging from a lambda")
                .policy(lambdaLoggingPolicyDocument.applyValue(GetPolicyDocumentResult::json))
                .build());

        var dynamoDBPolicy = new Policy("dynamoDBPolicy", com.pulumi.aws.iam.PolicyArgs.builder()
                .path("/")
                .description("IAM policy for dynamoDB")
                .policy(dynamoDBPolicyDocument.applyValue(GetPolicyDocumentResult::json))
                .build());

        var lambdaLogs = new RolePolicyAttachment("lambdaLogs", RolePolicyAttachmentArgs.builder()
                .role(iamForLambda.name())
                .policyArn(lambdaLoggingPolicy.arn())
                .build());

        var dynamoDB = new RolePolicyAttachment("dynamoDB", RolePolicyAttachmentArgs.builder()
                .role(iamForLambda.name())
                .policyArn(dynamoDBPolicy.arn())
                .build());

//        Map<String,String> env = new HashMap<>();
//        Output<String> key = (Output<String>) map.get("gcp_service_account_key");
//        key.applyValue(v -> {
//           env.put("GCP_SERVICE_ACCOUNT_KEY",v);
//           return v;
//        });
//        env.put("GCP_BUCKET_NAME",map.get("bucket_name").toString());
//        Output<Map<String,String>> input= Output.of(env);

        Map<String, Output<String>> env = new HashMap<>();
        Output<String> key = (Output<String>) map.get("gcp_service_account_key");
        Output<String> bucketName = (Output<String>) map.get("gcp_bucket_name");
        env.put("GCP_SERVICE_ACCOUNT_KEY", key);

        env.put("GCP_BUCKET_NAME", bucketName);
        env.put("SG_API_KEY", Output.of(properties.getProperty("SG_API_KEY")));
        env.put("TEMPLATE_ID", Output.of(map.get("template_id").toString()));
        Output<Map<String, String>> outputEnv = Output.all(env.values()).applyValue(values -> {
            Map<String, String> finalEnv = new HashMap<>();
            int i = 0;
            for (String k : env.keySet()) {
                finalEnv.put(k, values.get(i));
                i++;
            }
            return finalEnv;
        });


        List<Resource> resources = new ArrayList<>();
        resources.add(assignemntStorage);
        resources.add(lambdaLogs);
        resources.add(dynamoDB);
        Function testLambda = new Function("testLambda", FunctionArgs.builder()
                .code(new FileArchive("C:\\Users\\srika\\CSYE6225\\pulumi\\src\\main\\java\\myproject\\serverless.zip"))
                .role(iamForLambda.arn())
                .handler("index.handler")
                .runtime("nodejs18.x")
                .environment(FunctionEnvironmentArgs.builder()
                        .variables(outputEnv)
                        .build())
                .build(), CustomResourceOptions.builder().dependsOn(resources).build());
        Output<String> topicArn = (Output<String>) map.get("sns_topic_arn");
        TopicSubscription eventSourceMapping = new TopicSubscription("topic-subscription", TopicSubscriptionArgs.builder()
                .topic(topicArn)
                .protocol("lambda")
                .endpoint(testLambda.arn())
                .build());
        Permission permission = new Permission("permission", PermissionArgs.builder()
                .function(testLambda.name())
                .action("lambda:InvokeFunction")
                .principal("sns.amazonaws.com")
                .sourceArn(topicArn)
                .build());
    }

    public static void createDynamoDB(Map<String,Object> data){
        String tableName = "assignment-submissions";
        var table = new Table(tableName, TableArgs.builder()
                .name(tableName)
                .billingMode("PROVISIONED")
                .attributes(TableAttributeArgs.builder()
                        .name("submission_id")
                        .type("S")
                        .build())
//                        TableAttributeArgs.builder()
//                                .name("submission_url")
//                                .type("S")
//                                .build(),
//                        TableAttributeArgs.builder()
//                                .name("timestamp")
//                                .type("S")
//                                .build(),
//                        TableAttributeArgs.builder()
//                                .name("mail_status")
//                                .type("S")
//                                .build(),
//                        TableAttributeArgs.builder()
//                                .name("email_id")
//                                .type("S")
//                                .build(),
//                        TableAttributeArgs.builder()
//                                .name("assignment_id")
//                                .type("S")
//                                .build())
                .tags(Map.ofEntries(
                        Map.entry("Environment", "development"),
                        Map.entry("Name", "assignment-submissions")
                ))
                .hashKey("submission_id")
                .writeCapacity(5)
                .readCapacity(5)
                .build());
    }



}
