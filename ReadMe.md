# pom.xml to package JAR with dependencies
    <?xml version="1.0" encoding="UTF-8"?>
    <project xmlns="http://maven.apache.org/POM/4.0.0"
            xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
            xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
        <modelVersion>4.0.0</modelVersion>

        <groupId>IPC</groupId>
        <artifactId>IPC_Client</artifactId>
        <version>1.0.0</version>

        <properties>
            <maven.compiler.source>11</maven.compiler.source>
            <maven.compiler.target>11</maven.compiler.target>
            <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        </properties>
        <dependencies>
            <dependency>
                <groupId>software.amazon.awssdk.iotdevicesdk</groupId>
                <artifactId>aws-iot-device-sdk</artifactId>
                <version>1.11.6</version>
            </dependency>
        </dependencies>
        <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-assembly-plugin</artifactId>
                <version>2.6</version>
                <configuration>
                    <descriptorRefs>
                        <descriptorRef>jar-with-dependencies</descriptorRef>
                    </descriptorRefs>
                    <archive>
                        <manifest>
                            <mainClass>ipc.GreenGrassIPC</mainClass>
                        </manifest>
                    </archive>
                </configuration>
                <executions>
                    <execution>
                        <id>make-assembly</id>
                        <phase>package</phase>
                        <goals>
                            <goal>single</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
        </build>
    </project>

# GreengrassIPC.java file to publish messages to 'sample/ipc/client/pub/topic' and subscribe to 'sample/ipc/client/sub/topic' IoT Core Topics
    package ipc;

    import java.nio.charset.StandardCharsets;
    import java.time.*;
    import java.time.format.DateTimeFormatter;
    import java.util.concurrent.CompletableFuture;
    import java.util.concurrent.TimeUnit;

    import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPCClientV2;
    import software.amazon.awssdk.aws.greengrass.SubscribeToIoTCoreResponseHandler;
    import software.amazon.awssdk.aws.greengrass.model.*;
    import java.util.Optional;


    class GreenGrassIPC {
        public static void onComponentFailure(Throwable cause){
            System.out.println(cause.toString());
        }
        public static String messageBuilder(String message){
            String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH:mm:ss").format(LocalDateTime.now());
            StringBuilder builder = new StringBuilder();
            builder.append("{");
            builder.append("\"timestamp\":\"" + timestamp + "\",");
            builder.append("\"message\":"+message+"\"");
            builder.append("}");
            return builder.toString();
        }
        public static void logMessage(String message){
            System.out.println(message);
        }

        public static void main(String[] args){
            logMessage("Starting Greengrass IPC component");
            GreengrassCoreIPCClientV2 ipcClient = null;
            try {
                ipcClient = GreengrassCoreIPCClientV2.builder().build();
            } catch (Exception ex) {
                logMessage("Failed to create Greengrass IPC client!");
                onComponentFailure(ex);
                System.exit(-1);
            }

            if (ipcClient == null) {
                logMessage("Failed to create Greengrass IPC client!");
                onComponentFailure(new Throwable("Error - IPC client not initialized!"));
                System.exit(-1);
            }

            String pubTopicName = "sample/ipc/client/pub/topic";
            String subTopicName="sample/ipc/client/sub/topic";

            PublishToIoTCoreRequest publishRequest = new PublishToIoTCoreRequest();
            publishRequest.setQos(QOS.AT_LEAST_ONCE);
            publishRequest.setTopicName(pubTopicName);

            try {
                logMessage("Will attempt to send IPC publishes to IoT Core");
                publishRequest.withPayload(messageBuilder("sample message publisher").getBytes(StandardCharsets.UTF_8));
                CompletableFuture<PublishToIoTCoreResponse> publishFuture = ipcClient.publishToIoTCoreAsync(publishRequest);

                try {
                    publishFuture.get(60, TimeUnit.SECONDS);
                    logMessage("Successfully published IPC message to IoT Core");
                } catch (Exception ex) {
                    logMessage("Failed to publish IPC message to IoT Core");
                }

                Thread.sleep(1000);
                logMessage("All publishes sent. Finishing sample...");
                ipcClient.close();

            } catch (Exception ex) {
                logMessage("Shutting down ");
                onComponentFailure(ex);
                try {
                    ipcClient.close();
                } catch (Exception closeEx) {
                    onComponentFailure(closeEx);
                }
                logMessage("Greengrass IPC sample finished with error");
                System.exit(-1);
            }

            logMessage("Greengrass IPC pub sample finished");
            try {
                SubscribeToIoTCoreRequest request = new SubscribeToIoTCoreRequest().withTopicName(subTopicName).withQos(QOS.AT_LEAST_ONCE);
                GreengrassCoreIPCClientV2 ipcSubClient = GreengrassCoreIPCClientV2.builder().build();
                GreengrassCoreIPCClientV2.StreamingResponse<SubscribeToIoTCoreResponse,
                        SubscribeToIoTCoreResponseHandler> response =
                        ipcSubClient.subscribeToIoTCore(request, GreenGrassIPC::onStreamEvent,
                                Optional.of(GreenGrassIPC::onStreamError),
                                Optional.of(GreenGrassIPC::onStreamClosed));
                SubscribeToIoTCoreResponseHandler responseHandler = response.getHandler();
                logMessage(String.format("Successfully subscribed to topic: " + subTopicName));
                try {
                    while (true) {
                        Thread.sleep(10000);
                    }
                } catch (InterruptedException e) {
                    logMessage("Subscribe interrupted.");
                }

                responseHandler.closeStream();
            }
            catch (Exception e) {
                if (e.getCause() instanceof UnauthorizedError) {
                    logMessage("Unauthorized error while publishing to topic: " + subTopicName);
                } else {
                    logMessage("Exception occurred when using IPC.");
                }
                onComponentFailure(e);
                System.exit(1);
            }

            System.exit(0);
        }

        private static void onStreamEvent(IoTCoreMessage ioTCoreMessage) {
            try {
                MQTTMessage binaryMessage = ioTCoreMessage.getMessage();
                String message = new String(binaryMessage.getPayload(), StandardCharsets.UTF_8);
                String topic = binaryMessage.getTopicName();
                logMessage(String.format("Received new message on topic %s: %s%n", topic, message));
            } catch (Exception e) {
                logMessage("Exception occurred while processing subscription response message.");
                onComponentFailure(e);
            }
        }

        public static boolean onStreamError(Throwable error) {
            logMessage("Received a stream error.");
            onComponentFailure(error);
            return false; 
        }

        public static void onStreamClosed() {
            logMessage("Subscribe to topic stream closed.");
        }
    }

# Packinging the Maven application
    $ mvn clean package

# Uploading jar with dependencies to S3 bucket
    $ aws s3 cp IPC_Client-1.0.0-jar-with-dependencies.jar s3://kumo-article-bucket/

# Greengrass V2 component recipe
    ---
    RecipeFormatVersion: '2020-01-25'
    ComponentName: IPC.Client.PubSub
    ComponentVersion: '1.0.0'
    ComponentDescription: A component that publishes messages and subscribet to IoT Topics using IPC client
    ComponentPublisher: samba723
    ComponentConfiguration:
      DefaultConfiguration:
        accessControl:
          aws.greengrass.ipc.mqttproxy:
            com.example.IoTCorePublisherCpp:mqttproxy:1:
              policyDescription: Allows access to publish to all topics.
              operations:
                - aws.greengrass#PublishToIoTCore
                - aws.greengrass#SubscribeToIoTCore
              resources:
                - "sample/ipc/client/pub/topic"
                - "sample/ipc/client/sub/topic"
    Manifests:
      - Platform:
        os: linux
        Lifecycle:
          Run: |-
            java -jar {artifacts:path}/IPC_Client-1.0.0-jar-with-dependencies.jar
          Artifacts:
            - URI: s3://componet-source-bucket/IPC_Client-1.0.0-jar-with-dependencies.jar

# Update GreengrassV2 TokenExchange Role with S3 permissions
![Update GreengrassV2 TokenExchange Role with S3 permissions](/Screenshots/UpdateGreengrassV2TokenExchangeRoleWithS3Permissions.png)

# Created Greengrass component
![Component created](/Screenshots/GreengrassComponentCreated.png)

# Component deployment
![Component Deployment](/Screenshots/GreengrassComponentDeployment.png)

# IPC Componet successfully published message to IoT Core
![IPC Componet successfully published message to IoT Core](/Screenshots/IPCComponetSuccessfullyPublishedMessageToIoTCore.png)

# IPC Component logs & successful subscription to IoT Core topics
![IPC Component logs   successful subscription to IoT Core topics](/Screenshots/IPCComponentLogs&SuccessfulSubscriptionToIoTCoreTopics.png)
