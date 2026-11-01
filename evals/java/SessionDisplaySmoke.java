import com.thoughtcoding.observability.ModelTracing;
import com.thoughtcoding.observability.Telemetry;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;

/** Synthetic renderer check only: no provider request and no benchmark score. */
public final class SessionDisplaySmoke {
    public static void main(String[] args) {
        if (args.length != 1) throw new IllegalArgumentException("Expected canary session ID");
        Telemetry.initialize();
        if (!Telemetry.enabled()) throw new IllegalStateException("Tracing must be enabled");
        try (var task = Telemetry.task(args[0], "Synthetic display check; not a benchmark task")) {
            ModelTracing.sync("display_canary", "synthetic-no-model-request",
                    "Display verification: can you show this user message?", () ->
                    ChatResponse.builder().aiMessage(AiMessage.from(
                            "Display verification passed: this is a synthetic assistant response, not a real model call.")).build());
            task.output("Synthetic display verification complete");
        } finally { Telemetry.shutdown(); }
    }
}
