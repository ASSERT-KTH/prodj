package se.kth.castor.rockstofetch.instrument;

import com.fasterxml.jackson.annotation.JsonIgnore;
import se.kth.castor.rockstofetch.serialization.JavaSnippet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public record RecordedInvocation(
    UUID parentInvocationId,
    UUID invocationId,
    RecordedMethod recordedMethod,
    List<JavaSnippet> parameters,
    Map<Integer, String> idToNameMap,
    JavaSnippet receiverPre,
    JavaSnippet receiverPost,
    JavaSnippet returned
) {

  @JsonIgnore
  public boolean isVoid() {
    return returned.staticType().equals("void");
  }

  public String targetName(int id) {
    return idToNameMap.get(id);
  }

  public boolean hasTarget(int id) {
    return idToNameMap.containsKey(id);
  }

  public Optional<UUID> parentId() {
    return Optional.ofNullable(parentInvocationId());
  }

}
