package se.kth.castor.rockstofetch.instrument;

import se.kth.castor.rockstofetch.serialization.JavaSnippet;

public interface RecordedTargetedInvocation {

  int targetId();

  RecordedMethod recordedMethod();

  JavaSnippet returned();
}
