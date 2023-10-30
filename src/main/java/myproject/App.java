package myproject;

import com.pulumi.Pulumi;
import com.pulumi.aws.AwsFunctions;
import com.pulumi.aws.ec2.*;
import com.pulumi.aws.ec2.inputs.*;
import com.pulumi.aws.ec2.outputs.GetAmiResult;
import com.pulumi.aws.iam.*;
import static com.pulumi.codegen.internal.Serialization.*;
import com.pulumi.aws.iam.inputs.GetPolicyArgs;
import com.pulumi.aws.iam.inputs.GetPolicyDocumentArgs;
import com.pulumi.aws.iam.inputs.GetPolicyDocumentStatementArgs;
import com.pulumi.aws.iam.inputs.GetPolicyDocumentStatementPrincipalArgs;
import com.pulumi.aws.iam.outputs.GetPolicyDocumentResult;
import com.pulumi.aws.iam.outputs.GetPolicyResult;
import com.pulumi.aws.inputs.GetAvailabilityZonesArgs;
import com.pulumi.aws.outputs.GetAvailabilityZonesResult;
import com.pulumi.aws.rds.ParameterGroup;
import com.pulumi.aws.rds.ParameterGroupArgs;
import com.pulumi.aws.rds.SubnetGroup;
import com.pulumi.aws.rds.SubnetGroupArgs;
import com.pulumi.aws.route53.Record;
import com.pulumi.aws.route53.RecordArgs;
import com.pulumi.aws.route53.Route53Functions;
import com.pulumi.aws.route53.inputs.GetZoneArgs;
import com.pulumi.aws.route53.outputs.GetZoneResult;
import com.pulumi.core.Output;
import com.pulumi.resources.CustomResourceOptions;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;


public class App {
    public static void main(String[] args) {
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
                createSecurityGroup(vpc, data);

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
                createDbSecurityGroup(vpc, data);
                com.pulumi.aws.rds.Instance dbInstance = createDBInstance(data,privateSubNetList);
                Instance instance =createEc2(publicSubNetList.get(0),data,dbInstance);
                createARecord(data,instance);
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

    public static void createSecurityGroup(Vpc vpc, Map<String,Object> data){
        String security_group = data.get("name") + "_application_security_group";
        List<Double> ports = (List<Double>) data.get("ports");
        String publicIpString = data.get("public-cidr").toString();
        List<SecurityGroupIngressArgs> securityGroupIngressArgsList = new ArrayList<>();
        for(Double port : ports){
            securityGroupIngressArgsList.add(SecurityGroupIngressArgs.builder()
                    .fromPort(port.intValue())
                    .toPort(port.intValue())
                    .protocol("tcp")
                    .cidrBlocks(publicIpString).build());
        }
        var allowTcp = new SecurityGroup(security_group,
                SecurityGroupArgs.builder()
                        .description("Allow TCP connections")
                        .vpcId(vpc.id()).ingress(securityGroupIngressArgsList)
                        .tags(Map.of("Name",security_group))
                        .build());
        data.put("ec2_sg",allowTcp.id());
    }

    public static void createDbSecurityGroup(Vpc vpc, Map<String,Object> data){
        String security_group = data.get("name") + "_database_sg";
        Double port = (Double) data.get("db_port");
        Output<String> securityGroupId = (Output<String>) data.get("ec2_sg");
        List<SecurityGroupIngressArgs> securityGroupIngressArgsList = new ArrayList<>();
        securityGroupIngressArgsList.add(SecurityGroupIngressArgs.builder()
                .fromPort(port.intValue())
                .toPort(port.intValue())
                .protocol("tcp")
                .securityGroups(securityGroupId.applyValue(Collections::singletonList)).build());


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
                        .fromPort(port.intValue())
                        .toPort(port.intValue())
                        .protocol("tcp")
                        .sourceSecurityGroupId(allowTcp.id())
                        .securityGroupId(securityGroupId)
                        .build());

        data.put("database_sg",allowTcp.id());
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
        var role = new Role("cloudWatchRole", RoleArgs.builder()
                .assumeRolePolicy(assumeRole.applyValue(GetPolicyDocumentResult::json))
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
        var instanceProfile = new InstanceProfile("instanceProfile", InstanceProfileArgs.builder()
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
                        "sudo systemctl start amazon-cloudwatch-agent.service\n" +
                        "sudo /opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl -a fetch-config -m ec2 " +
                        "-c file:/opt/csye6225/cloudwatch-config.json -s \n" +
                v,
                userName,
                dbName,
                userName,
                ((Double) data.get("db_port")).intValue(),
                data.get("file_path")));
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

    public static void createARecord(Map<String, Object> data, Instance instance){
        String zoneName = data.get("zone_name").toString();
        final var selected = Route53Functions.getZone(GetZoneArgs
                                    .builder()
                                    .name(zoneName)
                                    .privateZone(false)
                                    .build());

        var www = new Record("www", RecordArgs.builder()
                .zoneId(selected.applyValue(GetZoneResult::zoneId))
                .name(zoneName)
                .type("A")
                .ttl(60)
                .records(instance.publicIp().applyValue(Collections::singletonList))
                .build());
    }



}
