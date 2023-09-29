package se.kth.castor.rockstofetch.extract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.visitor.CtScanner;

public class CandidateMethodExtractor extends CtScanner {

  private int totalMethodCount;
  private final List<ExtractCandidateMethod> candidates;

  public CandidateMethodExtractor() {
    this.candidates = new ArrayList<>();
  }

  public List<ExtractCandidateMethod> getCandidates() {
    return Collections.unmodifiableList(candidates);
  }

  public int getTotalMethodCount() {
    return totalMethodCount;
  }

  @Override
  public <T> void visitCtMethod(CtMethod<T> m) {
    if (m.getBody() != null && !m.isAbstract() && !m.isStatic()) {
      totalMethodCount += 1;
    }
    // FIXME: Remove
    if (m.getType().equals(m.getFactory().Type().VOID_PRIMITIVE)) {
//      return;
    }
    if (m.getBody() != null && m.getBody().getStatements().size() == 1) {
      return;
    }
    // TODO: Ignore fields declared in superclasses?

    // ## Extract
    //  - Ignore methods, if:

    //    - is not `public`
    // TODO: Allow package private?
    if (!m.isPublic() || !hierarchyIsPublic(m.getDeclaringType())) {
      return;
    }

    //    - is `abstract`
    if (m.isAbstract()) {
      return;
    }

    //    - is `static`
    if (m.isStatic()) {
      return;
    }

    //    - is `@Deprecated`
    if (isDeprecated(m)) {
      return;
    }
    if (m.getParent(CtType.class) != null && isDeprecated(m.getParent(CtType.class))) {
      return;
    }

    //    - is empty
    if (m.getBody() == null || m.getBody().getStatements().isEmpty()) {
      return;
    }

    //    - parent is not a CtClass: Ignore Interface, Annotations
    if (!(m.getDeclaringType() instanceof CtClass<?>)) {
      return;
    }

    //    - in anonymous or local types
    if (m.getDeclaringType().isAnonymous() || m.getDeclaringType().isLocalType()) {
      return;
    }

    // Candidate found!
    candidates.add(new ExtractCandidateMethod(
        m, RecordingCandidateMethod.fromCtMethod(m)
    ));
  }

  private static boolean isDeprecated(CtElement element) {
    return element.getAnnotation(Deprecated.class) != null;
  }

  private boolean hierarchyIsPublic(CtType<?> start) {
    if (start == null) {
      return true;
    }
    if (!start.isPublic()) {
      return false;
    }
    return hierarchyIsPublic(start.getDeclaringType());
  }
}
