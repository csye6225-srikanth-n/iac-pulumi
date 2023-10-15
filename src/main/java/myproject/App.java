package myproject;

import com.pulumi.Pulumi;
import com.pulumi.aws.AwsFunctions;
import com.pulumi.aws.ec2.*;
import com.pulumi.aws.ec2.inputs.RouteTableRouteArgs;
import com.pulumi.aws.inputs.GetAvailabilityZonesArgs;
import com.pulumi.aws.outputs.GetAvailabilityZonesResult;
import com.pulumi.core.Output;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;


public class App {
    public static void main(String[] args) {
        Pulumi.run(ctx -> {
            var config = ctx.config();
            var data = config.requireObject("data", Map.class);

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
                List<Subnet> publicSubNetList = createPublicSubNets(num,vpcName,vpc,availabilityZonesResult.names(),noOfZones,strings);
                List<Subnet> privateSubNetList =createPrivateSubnets(num,vpcName,vpc,availabilityZonesResult.names(),noOfZones,strings);
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
                return  null;
            });
            ctx.export("vpc-id", vpc.id());
        });
    }

    public static List<Subnet> createPublicSubNets(int num,String vpcName,Vpc vpc,List<String> list, int noOfZones,List<String> subnetStrings){
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

    public static List<Subnet> createPrivateSubnets(int num,String vpcName,Vpc vpc,List<String> list, int noOfZones,List<String> subnetString){
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


}




