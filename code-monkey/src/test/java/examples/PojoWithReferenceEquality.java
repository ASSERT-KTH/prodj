package examples;

public class PojoWithReferenceEquality {

  private final TrivialPojo first;
  private final TrivialPojo second;

  // Can be set to the same thing
  public PojoWithReferenceEquality(TrivialPojo first, TrivialPojo second) {
    this.first = first;
    this.second = second;
  }

}
