package se.kth.castor.rockstofetch.instrument;

import se.kth.castor.rockstofetch.serialization.JavaSnippet;
import java.util.UUID;

public record RecordedMockedInvocation(
    UUID parentInvocationId,
    RecordedMethod recordedMethod,
    JavaSnippet returned,
    int targetId
) implements RecordedTargetedInvocation {

}
