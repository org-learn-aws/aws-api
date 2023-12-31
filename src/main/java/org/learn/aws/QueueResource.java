package org.learn.aws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.smallrye.mutiny.subscription.Cancellable;
import lombok.Synchronized;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.context.Destroyed;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
@Path("/queues")
@Slf4j
public class QueueResource {

    private static final Integer MAX_NUMBEROF_MESSAGES = 10;

    private static final Integer WAIT_TIME_SECONDS = 10;
    private static final String DETAIL_TYPE_CHIME_MEETING_STATE_CHANGE = "Chime Meeting State Change";
    private static final String DETAIL_TYPE_CHIME_MEDIA_PIPELINE_STATE_CHANGE = "Chime Media Pipeline State Change";

    @Inject
    SqsClient sqsClient;

    @Inject
    ObjectMapper mapper;

    Map<String, Cancellable> subscriptions = new HashMap<>();

    public void destroy(@Observes @Destroyed(ApplicationScoped.class) Object event) {
        log.info("Destroyed : {}", event);
        subscriptions.forEach((url, cancellable) -> {
            log.info("canceling subscription for {}", url);
            try {
                cancellable.cancel();
                log.info("subscription successfully canceled for {}", url);
            } catch (Exception e) {
                log.info("subscription failed to cancel for " + url, e);
            }
        });
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<String> list() {
        final List<String> urls = sqsClient.listQueues().queueUrls();
        urls.forEach(this::subscribe);
        return urls;
    }

    public List<software.amazon.awssdk.services.sqs.model.Message> fetchMessages(String url) {
        log.debug("fetching messages for queue : {}", url);
        final ReceiveMessageRequest request = ReceiveMessageRequest.builder()
                                                                   .queueUrl(url)
                                                                   .maxNumberOfMessages(MAX_NUMBEROF_MESSAGES)
                                                                   .waitTimeSeconds(WAIT_TIME_SECONDS)
                                                                   .visibilityTimeout(10)
                                                                   .build();
        final ReceiveMessageResponse response = sqsClient.receiveMessage(request);
        log.debug("received messages for queue : {} : {}", url, response.messages().size());
        return response.messages();
    }

    @Synchronized
    private void subscribe(String url) {

        if (!url.equals("https://sqs.us-east-1.amazonaws.com/622938434879/chime-sqs")) {
            log.info("subscription ignored for : {}", url);
            return;
        }

        if (!subscriptions.containsKey(url)) {
            log.info("creating subscription for {}", url);
            Cancellable cancellable =
                Uni.createFrom().item(() -> fetchMessages(url))
                   .repeat().indefinitely()
                   .emitOn(Infrastructure.getDefaultExecutor())
                   .runSubscriptionOn(Infrastructure.getDefaultExecutor())
                   .flatMap(Multi.createFrom()::iterable)
                   .onItem().invoke(m -> {
                       try {
                           final Message msg = mapper.readValue(m.body(), Message.class);
                           if (msg.getDetailType().equals(DETAIL_TYPE_CHIME_MEDIA_PIPELINE_STATE_CHANGE)) {
                               log.info("pipeline : {}", msg.getDetail().getMediaPipelineId());
                           }
                           log.info("message : {}", msg);
                           sqsClient.deleteMessage(DeleteMessageRequest.builder().queueUrl(url).receiptHandle(m.receiptHandle()).build());
//                           log.info("deleted : {}", m.body());
                       } catch (JsonProcessingException e) {
                           log.error("error parsing message : " + m.body(), e);
                       }

                   })
                   .subscribe().with(
                       m -> log.trace("queue {} message {}", url, m.body()),
                       ex -> log.error("queue " + url + " error", ex),
                       () -> log.info("queue {} completed", url));
            subscriptions.put(url, cancellable);
        } else {
            log.info("subscription already exists for {}", url);
        }
    }
}
