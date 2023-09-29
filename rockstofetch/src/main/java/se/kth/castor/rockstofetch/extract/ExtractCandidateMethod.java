package se.kth.castor.rockstofetch.extract;

import spoon.reflect.declaration.CtMethod;

public record ExtractCandidateMethod(
    CtMethod<?> spoonMethod,
    RecordingCandidateMethod recordingCandidate
) {
}
