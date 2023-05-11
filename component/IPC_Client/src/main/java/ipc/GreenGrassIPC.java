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
