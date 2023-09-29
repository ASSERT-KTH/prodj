package se.kth.castor.rockstofetch.instrument;

import se.kth.castor.rockstofetch.serialization.JavaSnippet;
import java.util.List;
import java.util.UUID;

public record RecordedNestedInvocation(
    UUID parentInvocationId,
    RecordedMethod recordedMethod,
    List<JavaSnippet> parameters,
    JavaSnippet receiverPre,
    JavaSnippet receiverPost,
    JavaSnippet returned,
    int targetId
) implements RecordedTargetedInvocation {

}
