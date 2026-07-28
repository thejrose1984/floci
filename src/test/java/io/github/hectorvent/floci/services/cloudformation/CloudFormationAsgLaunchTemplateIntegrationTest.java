package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * Covers how AWS::AutoScaling::AutoScalingGroup resolves a launch template when it is provisioned
 * by CloudFormation.
 *
 * <p>Only {@code LaunchTemplate.LaunchTemplateName} works today. The three disabled cases
 * reproduce <a href="https://github.com/floci-io/floci/issues/2005">#2005</a> and should be enabled
 * with the fix:
 *
 * <ul>
 *   <li>{@code LaunchTemplate.LaunchTemplateId} is passed to Auto Scaling in the launch template
 *       <em>name</em> slot, so the lookup misses even for a template that exists.</li>
 *   <li>{@code AWS::EC2::LaunchTemplate} is not a provisioned resource type, so {@code Ref} yields
 *       a synthetic {@code <LogicalId>-<hash>} physical id rather than an {@code lt-} id.</li>
 *   <li>{@code MixedInstancesPolicy} is not read from the template at all, so the group is created
 *       with no launch source.</li>
 * </ul>
 */
@QuarkusTest
class CloudFormationAsgLaunchTemplateIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String EC2_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    /** Creates a launch template through the EC2 API and returns its {@code lt-} id. */
    private String createLaunchTemplate(String name) {
        String xml = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", EC2_AUTH)
            .formParam("Action", "CreateLaunchTemplate")
            .formParam("LaunchTemplateName", name)
            .formParam("LaunchTemplateData.ImageId", "ami-12345678")
            .formParam("LaunchTemplateData.InstanceType", "t3.micro")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().body().asString();
        int start = xml.indexOf("<launchTemplateId>") + "<launchTemplateId>".length();
        return xml.substring(start, xml.indexOf("</launchTemplateId>"));
    }

    private void createStack(String stackName, String template) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    private String describeStacks(String stackName) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().body().asString();
    }

    private void assertStackCreated(String stackName) {
        String body = describeStacks(stackName);
        assertThat(body, containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"));
        assertThat(body, not(containsString("The specified launch template does not exist.")));
    }

    @Test
    void asgResolvesLaunchTemplateByName() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String ltName = "cfn-lt-name-" + suffix;
        String stackName = "cfn-lt-name-stack-" + suffix;
        createLaunchTemplate(ltName);

        createStack(stackName, """
                {
                  "Resources": {
                    "Asg": {
                      "Type": "AWS::AutoScaling::AutoScalingGroup",
                      "Properties": {
                        "AutoScalingGroupName": "cfn-asg-name-%s",
                        "LaunchTemplate": {"LaunchTemplateName": "%s", "Version": "1"},
                        "MinSize": 1,
                        "MaxSize": 1,
                        "DesiredCapacity": 1,
                        "AvailabilityZones": ["us-east-1a"]
                      }
                    }
                  }
                }
                """.formatted(suffix, ltName));

        assertStackCreated(stackName);
    }

    @Test
    @Disabled("Reproduces #2005: LaunchTemplateId is passed to Auto Scaling as a launch template name")
    void asgResolvesLaunchTemplateById() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-lt-id-stack-" + suffix;
        String ltId = createLaunchTemplate("cfn-lt-id-" + suffix);

        createStack(stackName, """
                {
                  "Resources": {
                    "Asg": {
                      "Type": "AWS::AutoScaling::AutoScalingGroup",
                      "Properties": {
                        "AutoScalingGroupName": "cfn-asg-id-%s",
                        "LaunchTemplate": {"LaunchTemplateId": "%s", "Version": "1"},
                        "MinSize": 1,
                        "MaxSize": 1,
                        "DesiredCapacity": 1,
                        "AvailabilityZones": ["us-east-1a"]
                      }
                    }
                  }
                }
                """.formatted(suffix, ltId));

        assertStackCreated(stackName);
    }

    @Test
    @Disabled("Reproduces #2005: AWS::EC2::LaunchTemplate is stubbed, so Ref does not yield an lt- id")
    void asgResolvesInStackLaunchTemplateByRef() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-lt-instack-stack-" + suffix;

        createStack(stackName, """
                {
                  "Resources": {
                    "Lt": {
                      "Type": "AWS::EC2::LaunchTemplate",
                      "Properties": {
                        "LaunchTemplateName": "cfn-lt-instack-%s",
                        "LaunchTemplateData": {"ImageId": "ami-12345678", "InstanceType": "t3.micro"}
                      }
                    },
                    "Asg": {
                      "Type": "AWS::AutoScaling::AutoScalingGroup",
                      "Properties": {
                        "AutoScalingGroupName": "cfn-asg-instack-%s",
                        "LaunchTemplate": {
                          "LaunchTemplateId": {"Ref": "Lt"},
                          "Version": {"Fn::GetAtt": ["Lt", "LatestVersionNumber"]}
                        },
                        "MinSize": 1,
                        "MaxSize": 1,
                        "DesiredCapacity": 1,
                        "AvailabilityZones": ["us-east-1a"]
                      }
                    }
                  }
                }
                """.formatted(suffix, suffix));

        assertStackCreated(stackName);
    }

    @Test
    @Disabled("Reproduces #2005: MixedInstancesPolicy is not read by the CloudFormation ASG provisioner")
    void asgResolvesMixedInstancesPolicyLaunchTemplate() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String ltName = "cfn-lt-mip-" + suffix;
        String stackName = "cfn-lt-mip-stack-" + suffix;
        createLaunchTemplate(ltName);

        createStack(stackName, """
                {
                  "Resources": {
                    "Asg": {
                      "Type": "AWS::AutoScaling::AutoScalingGroup",
                      "Properties": {
                        "AutoScalingGroupName": "cfn-asg-mip-%s",
                        "MixedInstancesPolicy": {
                          "LaunchTemplate": {
                            "LaunchTemplateSpecification": {"LaunchTemplateName": "%s", "Version": "1"},
                            "Overrides": [{"InstanceType": "t3.micro"}]
                          }
                        },
                        "MinSize": 1,
                        "MaxSize": 1,
                        "DesiredCapacity": 1,
                        "AvailabilityZones": ["us-east-1a"]
                      }
                    }
                  }
                }
                """.formatted(suffix, ltName));

        assertStackCreated(stackName);
    }
}
