package com.thoughtcoding.service;

import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.core.CancelToken;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LangChainRecoveryBoundaryTest {
    @Test void abandonedRequestCannotDeliverLateMessagesOrTools() throws Exception {
        Fixture f = new Fixture();
        f.invoke();
        f.completion.cancel(true);
        f.callback.get().onPartialResponse("late text");
        f.callback.get().onCompleteResponse(mock(ChatResponse.class));
        f.callback.get().onError(new IllegalStateException("late error"));
        verifyNoInteractions(f.handler);
    }

    @Test void failedAttemptCannotLaterDeliverAResponseDuringRetry() throws Exception {
        Fixture f = new Fixture();
        f.invoke();
        RuntimeException failure = new IllegalStateException("retryable failure");
        f.callback.get().onError(failure);
        f.callback.get().onCompleteResponse(mock(ChatResponse.class));
        f.callback.get().onPartialResponse("late text");
        verify(f.handler).onError(failure);
        verifyNoMoreInteractions(f.handler);
    }

    @Test void alreadyCancelledRequestNeverStarts() throws Exception {
        Fixture f = new Fixture();
        f.token.cancel();
        f.invoke();
        verifyNoInteractions(f.model, f.handler);
    }

    private static final class Fixture {
        final StreamingChatModel model = mock(StreamingChatModel.class);
        final StreamingChatResponseHandler handler = mock(StreamingChatResponseHandler.class);
        final CompletableFuture<Void> completion = new CompletableFuture<>();
        final CancelToken token = new CancelToken();
        final AtomicReference<StreamingChatResponseHandler> callback = new AtomicReference<>();
        final LangChainService service = new LangChainService(mock(AppConfig.class), null, null);

        Fixture() throws Exception {
            var field = LangChainService.class.getDeclaredField("streamingChatModel");
            field.setAccessible(true);
            field.set(service, model);
            doAnswer(invocation -> { callback.set(invocation.getArgument(1)); return null; })
                    .when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        }

        void invoke() throws Exception {
            var method = LangChainService.class.getDeclaredMethod("guardedStream", ChatRequest.class,
                    StreamingChatResponseHandler.class, CancelToken.class, CompletableFuture.class);
            method.setAccessible(true);
            method.invoke(service, mock(ChatRequest.class), handler, token, completion);
        }
    }
}
